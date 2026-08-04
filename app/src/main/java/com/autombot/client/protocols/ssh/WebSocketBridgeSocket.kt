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

/**
 * Socket "decorador" (mesmo padrao do ComposedSocket em SshTunnelManager.kt) que
 * encapsula o trafego SSH dentro de frames binarios WebSocket, usando o OkHttp — ja
 * era dependencia do projeto (PanelWebhookClient), nao precisou adicionar lib nova.
 *
 * ATENCAO — este e o trecho de MAIOR incerteza deste modulo SSH, mais ainda que o
 * ComposedSocket do proxy/TLS/payload. A ponte entre o mundo assincrono do
 * WebSocketListener (callbacks) e o mundo sincrono/bloqueante que o sshj espera de um
 * Socket (getInputStream().read() bloqueante) usa PipedInputStream/PipedOutputStream,
 * que funciona no papel mas eu nao consegui compilar nem testar de verdade aqui. Se
 * travar, corromper dados, ou o Android Studio acusar erro, me manda o que aconteceu
 * (ou a mensagem de erro) que eu ajusto.
 *
 * Se `useSslTls` estiver ligado junto, usa "wss://" (WebSocket sobre TLS) em vez de
 * "ws://" — nesse caso o TLS e tratado pelo proprio OkHttp, nao pelo ComposedSocket.
 * O toggle de Payload nao se aplica ao modo WebSocket (o "upgrade" do WebSocket em si
 * ja cumpre esse papel de disfarce).
 */
class WebSocketBridgeSocket(private val config: SshConnectionConfig) : Socket() {

    private var webSocket: WebSocket? = null
    private val pipedOut = PipedOutputStream()
    private val pipedIn = PipedInputStream(pipedOut, 256 * 1024)

    private val sendStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            val ws = webSocket ?: throw IOException("WebSocket não conectado")
            val sent = ws.send(ByteString.of(*b.copyOfRange(off, off + len)))
            if (!sent) throw IOException("Falha ao enviar dados pelo WebSocket (fila cheia ou fechado)")
        }
    }

    override fun connect(endpoint: SocketAddress) = connect(endpoint, 0)

    override fun connect(endpoint: SocketAddress, timeout: Int) {
        val addr = endpoint as InetSocketAddress
        val host = addr.hostString
        val port = addr.port
        val effectiveTimeout = if (timeout > 0) timeout else (config.connectionTimeoutSeconds.toIntOrNull() ?: 10) * 1000

        val clientBuilder = OkHttpClient.Builder()
            .connectTimeout(effectiveTimeout.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // sem timeout de leitura — a conexao fica aberta indefinidamente
            .socketFactory(object : javax.net.SocketFactory() {
                // CORRECAO CRITICA (mesma da SshTunnelManager.kt): sem proteger o
                // socket real que o OkHttp abre aqui, com a VPN de sistema ativa essa
                // conexao seria capturada pelo proprio TUN, travando tudo. CORRIGIDO:
                // antes o retorno de protectSocket() era descartado.
                override fun createSocket(): java.net.Socket {
                    val socket = java.net.Socket()
                    // Ver comentario equivalente em VlessTransport.kt: forca o fd
                    // nativo existir antes do protect().
                    socket.bind(InetSocketAddress(0))
                    if (!com.autombot.client.core.AutomBotVpnService.protectSocket(socket)) {
                        throw java.io.IOException("Não consegui isentar esta conexão WebSocket da VPN (protect() falhou)")
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

        webSocket = clientBuilder.build().newWebSocket(requestBuilder.build(), listener)

        val opened = latch.await(effectiveTimeout.toLong(), TimeUnit.MILLISECONDS)
        if (!opened) throw IOException("Timeout ao abrir conexão WebSocket")
        failure?.let { throw IOException("Falha ao conectar WebSocket: ${it.message}") }
    }

    override fun getInputStream(): InputStream = pipedIn
    override fun getOutputStream(): OutputStream = sendStream
    override fun isConnected(): Boolean = webSocket != null
    override fun isClosed(): Boolean = webSocket == null

    override fun close() {
        webSocket?.close(1000, null)
        runCatching { pipedIn.close() }
        runCatching { pipedOut.close() }
    }

    override fun setSoTimeout(timeout: Int) { /* nao aplicavel a transporte WebSocket */ }
    override fun setTcpNoDelay(on: Boolean) { /* nao aplicavel a transporte WebSocket */ }
}