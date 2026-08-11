package com.autombot.client.protocols.vless

import com.autombot.client.protocols.ssh.UdpBackendSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Dns
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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * UDP do VLESS: uma conexão WebSocket por destino, com framing de 2 bytes de tamanho.
 * Falhas agora são propagadas ao Socks5Server para que ele descarte a sessão morta e
 * tente abrir outra em vez de continuar enviando pacotes para um WebSocket encerrado.
 */
object VlessUdpTransport {

    fun openSession(
        config: VlessConnectionConfig,
        destHost: String,
        destPort: Int,
        protectSocket: (java.net.Socket) -> Boolean,
        onIncoming: (ByteArray) -> Unit,
        timeoutMs: Int = 10_000,
        dns: Dns = Dns.SYSTEM
    ): UdpBackendSession? {
        val pipedOut = PipedOutputStream()
        val pipedIn = PipedInputStream(pipedOut, 256 * 1024)
        val closed = AtomicBoolean(false)

        val clientBuilder = OkHttpClient.Builder()
            .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            // Mantém o hostname no URL/TLS, mas força a resolução do endpoint pela
            // rede física subjacente em vez do DNS capturado pelo TUN do AutomBot.
            .dns(dns)
            .pingInterval(20, TimeUnit.SECONDS)
            .socketFactory(object : javax.net.SocketFactory() {
                override fun createSocket(): java.net.Socket {
                    val socket = java.net.Socket()
                    socket.bind(java.net.InetSocketAddress(0))
                    if (!protectSocket(socket)) {
                        runCatching { socket.close() }
                        throw IOException("Não consegui isentar esta conexão UDP (VLESS) da VPN (protect() falhou).")
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

        val header = VlessProtocol.buildRequestHeader(config.uuid, destHost, destPort, command = 0x02)
        if (!webSocket.send(ByteString.of(*header))) {
            closed.set(true)
            webSocket.cancel()
            return null
        }

        val strippedIn: InputStream = VlessResponseInputStream(pipedIn)
        val scope = CoroutineScope(Dispatchers.IO)
        val readerJob = scope.launch {
            try {
                while (isActive && !closed.get()) {
                    val lengthBytes = readExact(strippedIn, 2) ?: break
                    val len = ((lengthBytes[0].toInt() and 0xFF) shl 8) or (lengthBytes[1].toInt() and 0xFF)
                    if (len == 0) continue
                    val payload = readExact(strippedIn, len) ?: break
                    onIncoming(payload)
                }
            } catch (_: Exception) {
                // encerramento é refletido em closed e a próxima escrita força reopen
            } finally {
                closed.set(true)
            }
        }

        return object : UdpBackendSession {
            override suspend fun send(payload: ByteArray) {
                if (closed.get()) throw IOException("Sessão UDP VLESS está fechada")
                val framed = VlessProtocol.encodeUdpPacket(payload)
                if (!webSocket.send(ByteString.of(*framed))) {
                    closed.set(true)
                    webSocket.cancel()
                    throw IOException("Falha ao enviar UDP pelo WebSocket VLESS")
                }
            }

            override fun close() {
                if (closed.compareAndSet(false, true)) {
                    runCatching { webSocket.close(1000, null) }
                }
                readerJob.cancel()
                runCatching { pipedIn.close() }
                runCatching { pipedOut.close() }
            }
        }
    }

    private fun readExact(input: InputStream, size: Int): ByteArray? {
        val buf = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val n = input.read(buf, offset, size - offset)
            if (n == -1) return null
            offset += n
        }
        return buf
    }
}
