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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * UDP do VMess sobre WebSocket. Uma falha de escrita agora é propagada para o
 * Socks5Server, que pode remover a sessão e reabri-la, em vez de descartar pacotes
 * silenciosamente numa conexão já morta.
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
        val closed = AtomicBoolean(false)

        val clientBuilder = OkHttpClient.Builder()
            .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .socketFactory(object : javax.net.SocketFactory() {
                override fun createSocket(): java.net.Socket {
                    val socket = java.net.Socket()
                    socket.bind(java.net.InetSocketAddress(0))
                    if (!protectSocket(socket)) {
                        runCatching { socket.close() }
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
                if (!closed.get()) runCatching { pipedOut.write(bytes.toByteArray()); pipedOut.flush() }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                failure = t
                closed.set(true)
                latch.countDown()
                runCatching { pipedOut.close() }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                closed.set(true)
                runCatching { pipedOut.close() }
            }
        }

        val webSocket = clientBuilder.build().newWebSocket(requestBuilder.build(), listener)

        val opened = latch.await(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        if (!opened || failure != null) {
            closed.set(true)
            webSocket.cancel()
            return null
        }

        val rawSendStream = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

            override fun write(b: ByteArray, off: Int, len: Int) {
                if (closed.get()) throw IOException("WebSocket VMess UDP está fechado")
                val sent = webSocket.send(ByteString.of(*b.copyOfRange(off, off + len)))
                if (!sent) {
                    closed.set(true)
                    webSocket.cancel()
                    throw IOException("Falha ao enviar dados pelo WebSocket VMess (UDP)")
                }
            }

            override fun close() {
                if (closed.compareAndSet(false, true)) {
                    runCatching { webSocket.close(1000, null) }
                }
                runCatching { pipedIn.close() }
                runCatching { pipedOut.close() }
            }
        }

        val request = try {
            VmessCrypto.buildRequest(config.uuid, destHost, destPort, command = 0x02)
        } catch (_: Exception) {
            runCatching { rawSendStream.close() }
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
        } catch (_: Exception) {
            runCatching { rawSendStream.close() }
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
                while (isActive && !closed.get()) {
                    val n = dataIn.read(buffer)
                    if (n == -1) break
                    if (n > 0) onIncoming(buffer.copyOf(n))
                }
            } catch (_: Exception) {
            } finally {
                closed.set(true)
            }
        }

        return object : UdpBackendSession {
            override suspend fun send(payload: ByteArray) {
                if (closed.get()) throw IOException("Sessão UDP VMess está fechada")
                try {
                    dataOut.write(payload)
                } catch (e: Exception) {
                    closed.set(true)
                    webSocket.cancel()
                    throw IOException("Falha ao enviar UDP VMess: ${e.message}", e)
                }
            }

            override fun close() {
                if (closed.compareAndSet(false, true)) {
                    runCatching { webSocket.close(1000, null) }
                }
                readerJob.cancel()
                runCatching { dataOut.close() }
                runCatching { dataIn.close() }
                runCatching { pipedIn.close() }
                runCatching { pipedOut.close() }
            }
        }
    }
}