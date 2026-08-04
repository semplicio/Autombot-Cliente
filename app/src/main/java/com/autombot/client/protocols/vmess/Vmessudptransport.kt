package com.autombot.client.protocols.vmess

import com.autombot.client.protocols.ssh.UdpBackendSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.IOException
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * UDP do VMess: mesma conexão WebSocket que o TCP usa, só que com comando 0x02 no
 * cabeçalho (ver VmessCrypto.kt) — o framing do corpo (VmessOutputStream/
 * VmessInputStream, chunks AES-128-GCM de [2 bytes tamanho][dados cifrados]) é
 * reaproveitado sem mudança nenhuma: como cada datagrama UDP real é bem menor que o
 * limite de um chunk (16KB), UM write() vira UM chunk vira UM pacote, naturalmente —
 * não precisa de nenhum framing extra por cima.
 */
object VmessUdpTransport {

    fun openSession(
        config: VmessConnectionConfig,
        destHost: String,
        destPort: Int,
        protectSocket: (java.net.Socket) -> Boolean,
        onIncoming: (ByteArray) -> Unit,
        timeoutMs: Int = 10_000
    ): UdpBackendSession? {
        val pipedOut = PipedOutputStream()
        val pipedIn = PipedInputStream(pipedOut, 256 * 1024)

        val clientBuilder = OkHttpClient.Builder()
            .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .socketFactory(object : javax.net.SocketFactory() {
                override fun createSocket(): java.net.Socket {
                    val socket = java.net.Socket()
                    socket.bind(java.net.InetSocketAddress(0))
                    if (!protectSocket(socket)) {
                        throw IOException("Não consegui isentar esta conexão UDP (VMess) da VPN (protect() falhou).")
                    }
                    return socket
                }
                override fun createSocket(host: String?, p: Int) = createSocket().apply { connect(java.net.InetSocketAddress(host, p)) }
                override fun createSocket(host: String?, p: Int, localHost: java.net.InetAddress?, localPort: Int) = createSocket(host, p)
                override fun createSocket(host: java.net.InetAddress?, p: Int) = createSocket().apply { connect(java.net.InetSocketAddress(host, p)) }
                override fun createSocket(address: java.net.InetAddress?, p: Int, localAddress: java.net.InetAddress?, localPort: Int) = createSocket(address, p)
            })

        val scheme = if (config.useTls) "wss" else "ws"
        val path = config.wsPath.ifBlank { "/" }
        val url = "$scheme://${config.server}:${config.port}$path"

        val requestBuilder = Request.Builder().url(url)
        if (config.wsHost.isNotBlank()) requestBuilder.header("Host", config.wsHost)

        val latch = CountDownLatch(1)
        var failure: Throwable? = null

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { latch.countDown() }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                runCatching { pipedOut.write(bytes.toByteArray()); pipedOut.flush() }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                failure = t
                latch.countDown()
                runCatching { pipedOut.close() }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                runCatching { pipedOut.close() }
            }
        }

        val webSocket = clientBuilder.build().newWebSocket(requestBuilder.build(), listener)

        val opened = latch.await(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        if (!opened || failure != null) {
            runCatching { webSocket.close(1000, null) }
            return null
        }

        val rawSendStream = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                val sent = webSocket.send(ByteString.of(*b.copyOfRange(off, off + len)))
                if (!sent) throw IOException("Falha ao enviar dados pelo WebSocket VMess (UDP)")
            }
        }

        val request = try {
            VmessCrypto.buildRequest(config.uuid, destHost, destPort, command = 0x02)
        } catch (e: Exception) {
            runCatching { webSocket.close(1000, null) }
            return null
        }
        try {
            rawSendStream.write(request.authId)
            rawSendStream.write(request.connectionNonce)
            rawSendStream.write(request.encryptedLength)
            rawSendStream.write(request.encryptedHeader)

            VmessCrypto.decodeResponseHeader(
                requestBodyKey = request.requestBodyKey,
                requestBodyIv = request.requestBodyIv,
                expectedResponseHeaderByte = request.responseHeaderByte,
                input = pipedIn
            )
        } catch (e: Exception) {
            runCatching { webSocket.close(1000, null) }
            return null
        }

        val responseBodyKey = VmessCrypto.responseKey(request.requestBodyKey)
        val responseBodyIv = VmessCrypto.responseIv(request.requestBodyIv)

        val dataOut = VmessOutputStream(rawSendStream, request.requestBodyKey, request.requestBodyIv)
        val dataIn = VmessInputStream(pipedIn, responseBodyKey, responseBodyIv)

        val scope = CoroutineScope(Dispatchers.IO)
        val readerJob = scope.launch {
            val buffer = ByteArray(16384)
            try {
                while (isActive) {
                    val n = dataIn.read(buffer)
                    if (n == -1) break
                    if (n > 0) onIncoming(buffer.copyOf(n))
                }
            } catch (e: Exception) {
                // conexao fechada — normal
            }
        }

        return object : UdpBackendSession {
            override suspend fun send(payload: ByteArray) {
                runCatching { dataOut.write(payload) }
            }
            override fun close() {
                readerJob.cancel()
                runCatching { webSocket.close(1000, null) }
            }
        }
    }
}