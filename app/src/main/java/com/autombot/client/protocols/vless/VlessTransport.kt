package com.autombot.client.protocols.vless

import android.util.Log
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

/**
 * Abre uma conexao VLESS sobre WebSocket (com ou sem TLS) pro destino pedido, e ja
 * manda o cabecalho VLESS logo de cara. Usa o mesmo padrao de ponte WebSocket->stream
 * do SSH (ver protocols/ssh/WebSocketBridgeSocket.kt) — mesma incerteza/risco daquele
 * codigo, ainda maior aqui porque tambem depende do codec VLESS estar certo.
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

        val clientBuilder = OkHttpClient.Builder()
            .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .socketFactory(object : javax.net.SocketFactory() {
                // Mesma correcao critica do SSH — sem isso, com a VPN de sistema
                // ativa, essa conexao seria capturada pelo proprio TUN. CORRIGIDO:
                // antes o resultado de protectSocket() era descartado — se falhasse
                // de verdade, a conexao entrava em loop silencioso (via 10.90.0.2, o
                // IP da propria interface TUN) ate estourar timeout, sem nenhuma
                // pista do motivo real. Agora falha na hora, com causa clara.
                override fun createSocket(): java.net.Socket {
                    val socket = java.net.Socket()
                    // CORRECAO: um java.net.Socket() recem-criado pode nao ter o file
                    // descriptor nativo alocado ainda (varias implementacoes so criam o
                    // fd de verdade no primeiro bind()/connect()) — e protect() do
                    // Android precisa desse fd nativo pra funcionar. Bind num endereco
                    // qualquer (porta 0 = o sistema escolhe) forca essa alocacao ANTES
                    // do protect(), sem afetar o connect() que vem depois.
                    socket.bind(java.net.InetSocketAddress(0))
                    if (!protectSocket(socket)) {
                        throw java.io.IOException(
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
        if (!opened) throw IOException("Timeout ao abrir conexão WebSocket com o servidor VLESS")
        failure?.let { throw IOException("Falha ao conectar WebSocket: ${it.message}") }

        val sendStream = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                val sent = webSocket.send(ByteString.of(*b.copyOfRange(off, off + len)))
                if (!sent) throw IOException("Falha ao enviar dados pelo WebSocket VLESS")
            }
        }

        // Manda o cabecalho VLESS assim que a conexao abre — antes de qualquer dado
        // do socks5 chegar. Ver VlessProtocol.kt.
        val header = VlessProtocol.buildRequestHeader(config.uuid, destHost, destPort)
        sendStream.write(header)

        return VlessResponseInputStream(pipedIn) to sendStream
    }
}