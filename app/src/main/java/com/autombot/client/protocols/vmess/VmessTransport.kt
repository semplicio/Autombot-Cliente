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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Abre uma conexao VMess sobre WebSocket pro destino pedido.
 *
 * O handshake AEAD precisa seguir exatamente a ordem definida pelo protocolo:
 * EAuID -> ALength -> Nonce -> AHeader. Depois do handshake, o fechamento do
 * OutputStream representa apenas o fim da direcao cliente -> servidor; o WebSocket
 * permanece vivo para que a resposta servidor -> cliente possa ser drenada.
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
        val transportClosed = AtomicBoolean(false)
        val writeClosed = AtomicBoolean(false)

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
                        throw IOException(
                            "Não consegui isentar esta conexão da VPN (protect() falhou) — verifique " +
                                "se \"Bloquear conexões sem VPN\" está desligado pra este app."
                        )
                    }
                    return socket
                }

                override fun createSocket(host: String?, p: Int): java.net.Socket =
                    createSocket().apply { connect(java.net.InetSocketAddress(host, p)) }

                override fun createSocket(
                    host: String?,
                    p: Int,
                    localHost: java.net.InetAddress?,
                    localPort: Int
                ) = createSocket(host, p)

                override fun createSocket(host: java.net.InetAddress?, p: Int): java.net.Socket =
                    createSocket().apply { connect(java.net.InetSocketAddress(host, p)) }

                override fun createSocket(
                    address: java.net.InetAddress?,
                    p: Int,
                    localAddress: java.net.InetAddress?,
                    localPort: Int
                ) = createSocket(address, p)
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
                if (!transportClosed.get()) {
                    runCatching {
                        pipedOut.write(bytes.toByteArray())
                        pipedOut.flush()
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                failure = t
                transportClosed.set(true)
                latch.countDown()
                // Fechar somente o produtor permite ao leitor observar EOF natural
                // depois de consumir os bytes pendentes, em vez de "Pipe closed".
                runCatching { pipedOut.close() }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                transportClosed.set(true)
                runCatching { pipedOut.close() }
                runCatching { webSocket.close(code, reason) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                transportClosed.set(true)
                runCatching { pipedOut.close() }
            }
        }

        val webSocket = clientBuilder.build().newWebSocket(requestBuilder.build(), listener)

        fun closeTransport() {
            if (transportClosed.compareAndSet(false, true)) {
                runCatching { webSocket.cancel() }
            }
            runCatching { pipedOut.close() }
            runCatching { pipedIn.close() }
        }

        val opened = latch.await(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        if (!opened) {
            closeTransport()
            throw IOException("Timeout ao abrir conexão WebSocket com o servidor VMess")
        }
        failure?.let {
            closeTransport()
            throw IOException("Falha ao conectar WebSocket: ${it.message}")
        }

        val rawSendStream = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

            override fun write(b: ByteArray, off: Int, len: Int) {
                if (writeClosed.get()) throw IOException("Escrita VMess já foi encerrada")
                if (transportClosed.get()) throw IOException("WebSocket VMess já está fechado")

                val sent = webSocket.send(ByteString.of(*b.copyOfRange(off, off + len)))
                if (!sent) {
                    transportClosed.set(true)
                    webSocket.cancel()
                    runCatching { pipedOut.close() }
                    throw IOException("Falha ao enviar dados pelo WebSocket VMess")
                }
            }

            override fun close() {
                // O Data Section do VMess possui seu próprio pacote final autenticado
                // (enviado por VmessOutputStream.close()). Aqui fazemos apenas o
                // half-close lógico da escrita e mantemos o WebSocket vivo para a
                // direção de resposta.
                writeClosed.set(true)
            }
        }

        val request = try {
            VmessCrypto.buildRequest(config.uuid, destHost, destPort)
        } catch (e: Exception) {
            closeTransport()
            throw e
        }

        try {
            // VMess AEAD, ordem obrigatória:
            // 16B EAuID + 18B ALength + 8B Nonce + AHeader.
            // A implementação anterior enviava o Nonce antes de ALength; o Xray
            // interpretava bytes aleatórios como o campo de tamanho autenticado e
            // encerrava a conexão sem produzir o cabeçalho de resposta.
            rawSendStream.write(request.authId)
            rawSendStream.write(request.encryptedLength)
            rawSendStream.write(request.connectionNonce)
            rawSendStream.write(request.encryptedHeader)

            VmessCrypto.decodeResponseHeader(
                requestBodyKey = request.requestBodyKey,
                requestBodyIv = request.requestBodyIv,
                expectedResponseHeaderByte = request.responseHeaderByte,
                input = pipedIn
            )
        } catch (e: Exception) {
            closeTransport()
            throw e
        }

        val responseBodyKey = VmessCrypto.responseKey(request.requestBodyKey)
        val responseBodyIv = VmessCrypto.responseIv(request.requestBodyIv)

        val dataOut = VmessOutputStream(rawSendStream, request.requestBodyKey, request.requestBodyIv)
        val vmessInput = VmessInputStream(pipedIn, responseBodyKey, responseBodyIv)
        val dataIn = object : InputStream() {
            override fun read(): Int = vmessInput.read()

            override fun read(b: ByteArray, off: Int, len: Int): Int =
                vmessInput.read(b, off, len)

            override fun available(): Int = vmessInput.available()

            override fun close() {
                runCatching { vmessInput.close() }
                closeTransport()
            }
        }

        return dataIn to dataOut
    }
}
