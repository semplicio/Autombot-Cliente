package com.autombot.client.protocols.vless

import okhttp3.Dns
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
 *
 * O SOCKS5 trata o fechamento do OutputStream remoto como half-close da direcao
 * cliente -> servidor. WebSocket nao possui half-close equivalente ao TCP, entao
 * fechar o WebSocket nesse momento tambem mataria a resposta servidor -> cliente.
 * O OutputStream abaixo, portanto, encerra apenas a escrita logica; o transporte
 * fisico e os pipes de recepcao so sao fechados quando o InputStream/relay inteiro
 * termina ou quando o WebSocket remoto falha.
 */
object VlessTransport {

    fun connect(
        config: VlessConnectionConfig,
        destHost: String,
        destPort: Int,
        protectSocket: (java.net.Socket) -> Boolean,
        timeoutMs: Int = 10_000,
        dns: Dns = Dns.SYSTEM
    ): Pair<InputStream, OutputStream> {
        val pipedOut = PipedOutputStream()
        val pipedIn = PipedInputStream(pipedOut, 256 * 1024)
        val transportClosed = AtomicBoolean(false)
        val writeClosed = AtomicBoolean(false)

        val clientBuilder = OkHttpClient.Builder()
            .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            // A resolução do endpoint é fornecida pelo manager através de um Dns
            // amarrado à rede física. O URL continua usando o hostname original,
            // portanto TLS/SNI, certificado e Host não são trocados pelo IP.
            .dns(dns)
            // Mantem NAT/proxy/CDN cientes de que a conexao continua viva. Sem ping,
            // conexoes WebSocket ociosas podem ser descartadas silenciosamente.
            .pingInterval(20, TimeUnit.SECONDS)
            .socketFactory(object : javax.net.SocketFactory() {
                override fun createSocket(): java.net.Socket {
                    val socket = java.net.Socket()
                    socket.bind(java.net.InetSocketAddress(0))
                    if (!protectSocket(socket)) {
                        runCatching { socket.close() }
                        throw IOException(
                            "Nao consegui isentar esta conexao da VPN (protect() falhou) — verifique " +
                                "se \"Bloquear conexoes sem VPN\" esta desligado pra este app."
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
                // Fechar somente o produtor faz o PipedInputStream devolver EOF depois
                // dos bytes pendentes. Nao fechamos pipedIn aqui: isso produziria
                // IOException("Pipe closed") artificial na direcao de recebimento.
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
            // Mesmo se o peer ja marcou transportClosed em onClosed/onFailure,
            // sempre fechamos os pipes locais. O CAS controla apenas o cancelamento
            // fisico do WebSocket para evitar chamadas repetidas.
            if (transportClosed.compareAndSet(false, true)) {
                runCatching { webSocket.cancel() }
            }
            runCatching { pipedOut.close() }
            runCatching { pipedIn.close() }
        }

        val opened = latch.await(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        if (!opened) {
            transportClosed.set(true)
            webSocket.cancel()
            runCatching { pipedOut.close() }
            runCatching { pipedIn.close() }
            throw IOException("Timeout ao abrir conexao WebSocket com o servidor VLESS")
        }
        failure?.let {
            transportClosed.set(true)
            webSocket.cancel()
            runCatching { pipedOut.close() }
            runCatching { pipedIn.close() }
            throw IOException("Falha ao conectar WebSocket: ${it.message}")
        }

        val sendStream = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

            override fun write(b: ByteArray, off: Int, len: Int) {
                if (writeClosed.get()) throw IOException("Escrita VLESS ja foi encerrada")
                if (transportClosed.get()) throw IOException("WebSocket VLESS ja esta fechado")

                val sent = webSocket.send(ByteString.of(*b.copyOfRange(off, off + len)))
                if (!sent) {
                    transportClosed.set(true)
                    webSocket.cancel()
                    runCatching { pipedOut.close() }
                    throw IOException("Falha ao enviar dados pelo WebSocket VLESS")
                }
            }

            override fun close() {
                // IMPORTANTE: este close e chamado pelo relay assim que a direcao
                // cliente -> servidor chega em EOF. Fechar o WebSocket/pipedIn aqui
                // interrompia a resposta ainda em andamento e gerava exatamente
                // "IOException: Pipe closed" no pipe [recebendo]. WebSocket nao tem
                // half-close; apenas impedimos novas escritas e deixamos a leitura
                // sobreviver ate EOF remoto ou ate o cleanup final do relay.
                writeClosed.set(true)
            }
        }

        val header = VlessProtocol.buildRequestHeader(config.uuid, destHost, destPort)
        try {
            sendStream.write(header)
        } catch (e: Exception) {
            closeTransport()
            throw e
        }

        val vlessResponse = VlessResponseInputStream(pipedIn)
        val receiveStream = object : InputStream() {
            override fun read(): Int = vlessResponse.read()

            override fun read(b: ByteArray, off: Int, len: Int): Int =
                vlessResponse.read(b, off, len)

            override fun available(): Int = vlessResponse.available()

            override fun close() {
                // O InputStream representa o ciclo de vida completo do transporte.
                // O Socks5Server o fecha no cleanup final, depois de permitir que a
                // direcao de download drene enquanto houver progresso.
                runCatching { vlessResponse.close() }
                closeTransport()
            }
        }

        return receiveStream to sendStream
    }
}
