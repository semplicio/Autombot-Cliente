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
 * EAuID -> ALength -> Nonce -> AHeader.
 *
 * IMPORTANTE: VMess nao possui uma etapa de handshake sincrona em que o cliente
 * precisa esperar a resposta antes de poder enviar o primeiro payload. O Xray envia
 * request header + request body enquanto, em paralelo, aguarda o response header.
 * Portanto esta implementacao envia apenas o request header durante connect() e
 * devolve imediatamente os streams ao relay SOCKS5. O response header e decodificado
 * de forma lazy na primeira leitura da direcao servidor -> cliente.
 */
object VmessTransport {
    // OkHttp usa uma fila de envio WebSocket assincrona com limite duro de 16 MiB.
    // Manter uma janela bem menor aplica backpressure no relay antes que um burst de
    // upload/Speedtest encha essa fila e faca WebSocket.send() iniciar o fechamento.
    private const val WS_QUEUE_HIGH_WATER_BYTES = 2L * 1024L * 1024L
    private const val WS_QUEUE_DRAIN_TIMEOUT_MS = 15_000L
    private const val RECEIVE_PIPE_BYTES = 512 * 1024

    /**
     * Cria o cliente compartilhado pelo VmessTunnelManager.
     *
     * Um OkHttpClient por canal TCP criava um Dispatcher/ConnectionPool/TaskRunner por
     * conexao do navegador. Reutilizar uma instancia reduz drasticamente threads,
     * alocacoes e latencia de abertura. O payload VMess ja e AES-GCM (incompressivel),
     * portanto desabilitamos compressao WebSocket de saida para nao gastar CPU a toa.
     */
    fun createClient(
        protectSocket: (java.net.Socket) -> Boolean,
        timeoutMs: Int = 10_000
    ): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .minWebSocketMessageToCompress(Long.MAX_VALUE)
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
        .build()

    /** Compatibilidade para qualquer chamador que ainda nao mantenha um cliente compartilhado. */
    fun connect(
        config: VmessConnectionConfig,
        destHost: String,
        destPort: Int,
        protectSocket: (java.net.Socket) -> Boolean,
        timeoutMs: Int = 10_000
    ): Pair<InputStream, OutputStream> = connect(
        config = config,
        destHost = destHost,
        destPort = destPort,
        client = createClient(protectSocket, timeoutMs),
        timeoutMs = timeoutMs
    )

    /** Caminho usado em producao: um OkHttpClient compartilhado entre todos os canais VMess. */
    fun connect(
        config: VmessConnectionConfig,
        destHost: String,
        destPort: Int,
        client: OkHttpClient,
        timeoutMs: Int = 10_000
    ): Pair<InputStream, OutputStream> {
        val pipedOut = PipedOutputStream()
        val pipedIn = PipedInputStream(pipedOut, RECEIVE_PIPE_BYTES)
        val transportClosed = AtomicBoolean(false)
        val writeClosed = AtomicBoolean(false)

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

        val webSocket = client.newWebSocket(requestBuilder.build(), listener)

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
            throw IOException("Timeout ao abrir conexao WebSocket com o servidor VMess")
        }
        failure?.let {
            closeTransport()
            throw IOException("Falha ao conectar WebSocket: ${it.message}")
        }

        fun awaitSendCapacity(nextMessageBytes: Int) {
            val startedAt = System.nanoTime()
            while (webSocket.queueSize() + nextMessageBytes > WS_QUEUE_HIGH_WATER_BYTES) {
                if (transportClosed.get()) {
                    throw IOException("WebSocket VMess ja esta fechado")
                }
                val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
                if (elapsedMs >= WS_QUEUE_DRAIN_TIMEOUT_MS) {
                    throw IOException(
                        "Fila WebSocket VMess nao drenou em ${WS_QUEUE_DRAIN_TIMEOUT_MS / 1000}s " +
                            "(${webSocket.queueSize()} bytes pendentes)"
                    )
                }
                try {
                    Thread.sleep(1L)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("Espera da fila WebSocket VMess foi interrompida", e)
                }
            }
        }

        val rawSendStream = object : OutputStream() {
            private val sendLock = Any()

            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

            override fun write(b: ByteArray, off: Int, len: Int) {
                if (len <= 0) return
                synchronized(sendLock) {
                    if (writeClosed.get()) throw IOException("Escrita VMess ja foi encerrada")
                    if (transportClosed.get()) throw IOException("WebSocket VMess ja esta fechado")

                    // WebSocket.send() retorna imediatamente e apenas enfileira dados.
                    // Bloqueamos o produtor quando a fila passa da janela de seguranca,
                    // preservando throughput sem chegar ao limite duro de 16 MiB do OkHttp.
                    awaitSendCapacity(len)

                    val payload = b.copyOfRange(off, off + len)
                    val sent = webSocket.send(ByteString.of(*payload))
                    if (!sent) {
                        transportClosed.set(true)
                        webSocket.cancel()
                        runCatching { pipedOut.close() }
                        throw IOException("Falha ao enviar dados pelo WebSocket VMess")
                    }
                }
            }

            override fun close() {
                // O Data Section do VMess possui seu proprio pacote final autenticado
                // (enviado por VmessOutputStream.close()). Aqui fazemos apenas o
                // half-close logico da escrita e mantemos o WebSocket vivo para a
                // direcao de resposta.
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
            // Uma unica mensagem WebSocket para o request VMess. Antes eram quatro
            // mensagens pequenas separadas, gerando framing e agendamento extras.
            val requestFrame = ByteArray(
                request.authId.size +
                    request.encryptedLength.size +
                    request.connectionNonce.size +
                    request.encryptedHeader.size
            )
            var offset = 0
            System.arraycopy(request.authId, 0, requestFrame, offset, request.authId.size)
            offset += request.authId.size
            System.arraycopy(request.encryptedLength, 0, requestFrame, offset, request.encryptedLength.size)
            offset += request.encryptedLength.size
            System.arraycopy(request.connectionNonce, 0, requestFrame, offset, request.connectionNonce.size)
            offset += request.connectionNonce.size
            System.arraycopy(request.encryptedHeader, 0, requestFrame, offset, request.encryptedHeader.size)
            rawSendStream.write(requestFrame)
        } catch (e: Exception) {
            closeTransport()
            throw e
        }

        // Nao esperamos o response header aqui. Para HTTPS isso criava um deadlock:
        // o Xray conectava ao destino e esperava o ClientHello, enquanto o app ficava
        // bloqueado esperando o response header antes de devolver o OutputStream ao
        // SOCKS5. O Xray oficial processa uplink e downlink em paralelo.
        val dataOut = VmessOutputStream(rawSendStream, request.requestBodyKey, request.requestBodyIv)
        val responseBodyKey = VmessCrypto.responseKey(request.requestBodyKey)
        val responseBodyIv = VmessCrypto.responseIv(request.requestBodyIv)

        val responseLock = Any()
        var decodedInput: VmessInputStream? = null

        fun ensureResponseInput(): VmessInputStream {
            decodedInput?.let { return it }
            synchronized(responseLock) {
                decodedInput?.let { return it }
                try {
                    VmessCrypto.decodeResponseHeader(
                        requestBodyKey = request.requestBodyKey,
                        requestBodyIv = request.requestBodyIv,
                        expectedResponseHeaderByte = request.responseHeaderByte,
                        input = pipedIn
                    )
                    return VmessInputStream(pipedIn, responseBodyKey, responseBodyIv)
                        .also { decodedInput = it }
                } catch (e: Exception) {
                    closeTransport()
                    if (e is IOException) throw e
                    throw IOException("Falha ao decodificar resposta VMess: ${e.message}", e)
                }
            }
        }

        val dataIn = object : InputStream() {
            override fun read(): Int = ensureResponseInput().read()

            override fun read(b: ByteArray, off: Int, len: Int): Int =
                ensureResponseInput().read(b, off, len)

            override fun available(): Int = decodedInput?.available() ?: 0

            override fun close() {
                decodedInput?.let { runCatching { it.close() } }
                    ?: runCatching { pipedIn.close() }
                closeTransport()
            }
        }

        return dataIn to dataOut
    }
}
