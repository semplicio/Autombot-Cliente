package com.autombot.client.protocols.vmess

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
 * Abre uma conexao VMess sobre WebSocket pro destino pedido: monta o cabeçalho AEAD
 * (VmessCrypto.buildRequest), manda pelo WebSocket, LÊ E VALIDA a resposta do servidor
 * (decodeResponseHeader — se o AuthV não bater ou a tag GCM for inválida, falha aqui
 * mesmo, antes de repassar qualquer dado), e só então devolve as streams já
 * criptografadas/descriptografadas prontas pro Socks5Server usar como um socket comum.
 *
 * Mesmo padrão de ponte WebSocket->stream do SSH/VLESS — mesma incerteza sobre esse
 * padrão em si, GANHA a incerteza adicional de todo o protocolo VMess (ver avisos em
 * VmessCrypto.kt).
 */
object VmessTransport {

    fun connect(
        config: VmessConnectionConfig,
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
                override fun createSocket(): java.net.Socket {
                    val socket = java.net.Socket()
                    // Ver comentario equivalente em VlessTransport.kt: forca o fd nativo
                    // existir antes do protect(), que precisa dele pra funcionar.
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
        if (!opened) throw IOException("Timeout ao abrir conexão WebSocket com o servidor VMess")
        failure?.let { throw IOException("Falha ao conectar WebSocket: ${it.message}") }

        val rawSendStream = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                val sent = webSocket.send(ByteString.of(*b.copyOfRange(off, off + len)))
                if (!sent) throw IOException("Falha ao enviar dados pelo WebSocket VMess")
            }
        }

        // Monta e manda o cabecalho de requisicao VMess (AuthID + comprimento cifrado
        // + cabecalho cifrado) assim que a conexao WebSocket abre.
        val request = VmessCrypto.buildRequest(config.uuid, destHost, destPort)
        rawSendStream.write(request.authId)
        rawSendStream.write(request.connectionNonce)
        rawSendStream.write(request.encryptedLength)
        rawSendStream.write(request.encryptedHeader)

        // Le e valida a resposta do servidor ANTES de considerar a conexao pronta —
        // se o AuthV nao bater ou a tag GCM for invalida, falha aqui (rapido, com
        // causa clara), em vez de silenciosamente na primeira leitura de dados.
        VmessCrypto.decodeResponseHeader(
            requestBodyKey = request.requestBodyKey,
            requestBodyIv = request.requestBodyIv,
            expectedResponseHeaderByte = request.responseHeaderByte,
            input = pipedIn
        )

        val responseBodyKey = VmessCrypto.responseKey(request.requestBodyKey)
        val responseBodyIv = VmessCrypto.responseIv(request.requestBodyIv)

        val dataOut = VmessOutputStream(rawSendStream, request.requestBodyKey, request.requestBodyIv)
        val dataIn = VmessInputStream(pipedIn, responseBodyKey, responseBodyIv)

        return dataIn to dataOut
    }
}