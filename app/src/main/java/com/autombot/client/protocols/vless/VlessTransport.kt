package com.autombot.client.protocols.vless

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Abre uma conexao VLESS sobre WebSocket (com ou sem TLS) pro destino pedido.
 * A ponte possui encerramento explícito: quando o SOCKS5 termina o canal, o
 * WebSocket correspondente também é fechado, evitando conexões órfãs acumuladas.
 */
object VlessTransport {

    fun connect(
        config: VlessConnectionConfig,
        destHost: String,
        destPort: Int,
        protectSocket: (java.net.Socket) -> Boolean,
        timeoutMs: Int = 10_000
    ): Pair<InputStream, OutputStream> {
        val pipedOut = PipedOutputStream()
        val pipedIn = PipedInputStream(pipedOut, 256 * 1024)
        val closed = AtomicBoolean(false)

        val clientBuilder = OkHttpClient.Builder()
            .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            // Mantém NAT/proxy/CDN cientes de que a conexão continua viva. Sem ping,
            // conexões WebSocket ociosas podem ser descartadas silenciosamente.
            .pingInterval(20, TimeUnit.SECONDS)
            .socketFactory(object : javax.net.SocketFactory() {
                override fun createSocket(): java.net.Socket {
                    val socket = java.net.Socket()
                    socket.bind(java.net.InetSocketAddress(0))
                    if (!protectSocket(socket)) {
                        runCatching { socket.close() }
                        throw IOException(
                            "Não consegui isentar esta conexão da VPN (protect() falhou) — verifique " +
                                "se \"Bloquear conexões sem VPN\" está desligado pra este app."
                        )
                    }
                    return socket
                }
                override fun createSocket(host: String?, p: Int): java.net.Socket = createSocket().apply { connect(java.net.InetSocketAddress(host, p)) }
                override fun createSocket(host: String?, p: Int, localHost: java.net.InetAddress?, localPort: Int) = createSocket(host, p)
                override fun createSocket(host: java.net.InetAddress?, p: Int): java.net.Socket = createSocket().apply { connect(java.net.InetSocketAddress(host, p)) }
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
            override fun onOpen(webSocket: WebSocket, response: Response) {
                latch.countDown()
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (!closed.get()) {
                    runCatching { pipedOut.write(bytes.toByteArray()); pipedOut.flush() }
                }
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
        if (!opened) {
            closed.set(true)
            webSocket.cancel()
            throw IOException("Timeout ao abrir conexão WebSocket com o servidor VLESS")
        }
        failure?.let {
            closed.set(true)
            webSocket.cancel()
            throw IOException("Falha ao conectar WebSocket: ${it.message}")
        }

        val sendStream = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

            override fun write(b: ByteArray, off: Int, len: Int) {
                if (closed.get()) throw IOException("WebSocket VLESS já está fechado")
                val sent = webSocket.send(ByteString.of(*b.copyOfRange(off, off + len)))
                if (!sent) {
                    closed.set(true)
                    webSocket.cancel()
                    throw IOException("Falha ao enviar dados pelo WebSocket VLESS")
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

        val header = VlessProtocol.buildRequestHeader(config.uuid, destHost, destPort)
        try {
            sendStream.write(header)
        } catch (e: Exception) {
            runCatching { sendStream.close() }
            throw e
        }

        return VlessResponseInputStream(pipedIn) to sendStream
    }
}