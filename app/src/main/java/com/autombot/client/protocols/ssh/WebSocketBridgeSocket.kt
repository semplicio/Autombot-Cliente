package com.autombot.client.protocols.ssh

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
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Socket decorador que encapsula SSH em WebSocket. Possui keepalive e estado de
 * fechamento explícito para que quedas de proxy/CDN sejam percebidas pelo sshj em vez
 * de deixar a conexão marcada como ativa sem tráfego.
 */
class WebSocketBridgeSocket(private val config: SshConnectionConfig) : Socket() {

    @Volatile private var webSocket: WebSocket? = null
    private val closed = AtomicBoolean(false)
    private val pipedOut = PipedOutputStream()
    private val pipedIn = PipedInputStream(pipedOut, 256 * 1024)

    private val sendStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (closed.get()) throw IOException("WebSocket SSH está fechado")
            val ws = webSocket ?: throw IOException("WebSocket não conectado")
            val sent = ws.send(ByteString.of(*b.copyOfRange(off, off + len)))
            if (!sent) {
                closeInternal(cancel = true)
                throw IOException("Falha ao enviar dados pelo WebSocket (fila cheia ou fechado)")
            }
        }

        override fun close() {
            this@WebSocketBridgeSocket.close()
        }
    }

    override fun connect(endpoint: SocketAddress) = connect(endpoint, 0)

    override fun connect(endpoint: SocketAddress, timeout: Int) {
        if (closed.get()) throw IOException("Socket WebSocket já foi fechado")
        val addr = endpoint as InetSocketAddress
        val host = addr.hostString
        val port = addr.port
        val effectiveTimeout = if (timeout > 0) timeout else (config.connectionTimeoutSeconds.toIntOrNull() ?: 10) * 1000

        val clientBuilder = OkHttpClient.Builder()
            .connectTimeout(effectiveTimeout.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .socketFactory(object : javax.net.SocketFactory() {
                override fun createSocket(): java.net.Socket {
                    val socket = java.net.Socket()
                    socket.bind(InetSocketAddress(0))
                    if (!com.autombot.client.core.AutomBotVpnService.protectSocket(socket)) {
                        runCatching { socket.close() }
                        throw IOException("Não consegui isentar esta conexão WebSocket da VPN (protect() falhou)")
                    }
                    return socket
                }
                override fun createSocket(host: String?, p: Int): java.net.Socket = createSocket().apply { connect(InetSocketAddress(host, p)) }
                override fun createSocket(host: String?, p: Int, localHost: java.net.InetAddress?, localPort: Int) = createSocket(host, p)
                override fun createSocket(host: java.net.InetAddress?, p: Int): java.net.Socket = createSocket().apply { connect(InetSocketAddress(host, p)) }
                override fun createSocket(address: java.net.InetAddress?, p: Int, localAddress: java.net.InetAddress?, localPort: Int) = createSocket(address, p)
            })

        if (config.useProxy) {
            val proxyPort = config.proxyPort.toIntOrNull() ?: 1080
            val proxyType = if (config.proxyType == ProxyType.SOCKS5) java.net.Proxy.Type.SOCKS else java.net.Proxy.Type.HTTP
            clientBuilder.proxy(java.net.Proxy(proxyType, InetSocketAddress(config.proxyHost, proxyPort)))
        }

        val scheme = if (config.useSslTls) "wss" else "ws"
        val path = config.wsPath.ifBlank { "/" }
        val url = "$scheme://$host:$port$path"

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
                latch.countDown()
                closeInternal(cancel = true)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                closeInternal(cancel = false)
            }
        }

        val ws = clientBuilder.build().newWebSocket(requestBuilder.build(), listener)
        webSocket = ws

        val opened = latch.await(effectiveTimeout.toLong(), TimeUnit.MILLISECONDS)
        if (!opened) {
            closeInternal(cancel = true)
            throw IOException("Timeout ao abrir conexão WebSocket")
        }
        failure?.let {
            closeInternal(cancel = true)
            throw IOException("Falha ao conectar WebSocket: ${it.message}")
        }
    }

    override fun getInputStream(): InputStream = pipedIn
    override fun getOutputStream(): OutputStream = sendStream
    override fun isConnected(): Boolean = webSocket != null && !closed.get()
    override fun isClosed(): Boolean = closed.get()

    override fun close() {
        closeInternal(cancel = false)
    }

    private fun closeInternal(cancel: Boolean) {
        if (!closed.compareAndSet(false, true)) return
        val ws = webSocket
        webSocket = null
        if (cancel) runCatching { ws?.cancel() } else runCatching { ws?.close(1000, null) }
        runCatching { pipedIn.close() }
        runCatching { pipedOut.close() }
    }

    override fun setSoTimeout(timeout: Int) { /* não aplicável a WebSocket */ }
    override fun setTcpNoDelay(on: Boolean) { /* não aplicável a WebSocket */ }
}