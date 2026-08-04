package com.autombot.client.protocols.vless

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
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * UDP do VLESS: mesma conexão WebSocket que o TCP usa, só que com comando 0x02 no
 * cabeçalho (ver VlessProtocol.kt) e cada "pacote" enviado/recebido com um prefixo de
 * 2 bytes de comprimento (VlessProtocol.encodeUdpPacket) — diferente do Shadowsocks,
 * aqui é UMA conexão nova POR DESTINO (igual ao TCP), não uma compartilhada.
 *
 * ATENCAO: implementado a partir da especificação, ainda não testado contra um
 * servidor VLESS de verdade em modo UDP — se o servidor recusar ou os pacotes vierem
 * corrompidos, é o primeiro lugar a revisar (junto com VlessProtocol.kt).
 */
object VlessUdpTransport {

    fun openSession(
        config: VlessConnectionConfig,
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
                        throw IOException(
                            "Não consegui isentar esta conexão UDP (VLESS) da VPN (protect() falhou)."
                        )
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

        // Cabecalho VLESS com comando UDP (0x02) — ver VlessProtocol.kt.
        val header = VlessProtocol.buildRequestHeader(config.uuid, destHost, destPort, command = 0x02)
        val sent = webSocket.send(ByteString.of(*header))
        if (!sent) {
            runCatching { webSocket.close(1000, null) }
            return null
        }

        // Descarta o cabecalho de resposta (mesmo formato do TCP: versao + addons) —
        // reaproveita o VlessResponseInputStream que ja faz exatamente isso.
        val strippedIn: InputStream = VlessResponseInputStream(pipedIn)

        val scope = CoroutineScope(Dispatchers.IO)
        val readerJob = scope.launch {
            try {
                while (isActive) {
                    val lengthBytes = readExact(strippedIn, 2) ?: break
                    val len = ((lengthBytes[0].toInt() and 0xFF) shl 8) or (lengthBytes[1].toInt() and 0xFF)
                    if (len == 0) continue
                    val payload = readExact(strippedIn, len) ?: break
                    onIncoming(payload)
                }
            } catch (e: Exception) {
                // conexao fechada — normal
            }
        }

        return object : UdpBackendSession {
            override suspend fun send(payload: ByteArray) {
                val framed = VlessProtocol.encodeUdpPacket(payload)
                runCatching { webSocket.send(ByteString.of(*framed)) }
            }
            override fun close() {
                readerJob.cancel()
                runCatching { webSocket.close(1000, null) }
            }
        }
    }

    private fun readExact(input: InputStream, size: Int): ByteArray? {
        val buf = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val n = input.read(buf, offset, size - offset)
            if (n == -1) return if (offset == 0) null else null
            offset += n
        }
        return buf
    }
}