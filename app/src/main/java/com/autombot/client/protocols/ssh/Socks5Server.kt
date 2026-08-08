package com.autombot.client.protocols.ssh

import com.autombot.client.util.AppLog
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds

/**
 * Servidor SOCKS5 mínimo, local (127.0.0.1), sem autenticação.
 * Implementação própria (RFC 1928).
 */
class Socks5Server(
    private val port: Int,
    private val onConnectRequest: suspend (host: String, port: Int) -> Pair<InputStream, OutputStream>?,
    private val onUdpAssociateRequest: UdpAssociateOpener? = null,
    private val protectDatagramSocket: ((DatagramSocket) -> Boolean)? = null,
    private val dns1: String = "8.8.8.8",
    private val dns2: String = "8.8.4.4",
    // CORRECAO: handleConnect() nao registrava NADA — nem tentativa, nem sucesso,
    // nem falha — de cada conexao TCP individual (exatamente o que o navegador usa
    // pra abrir cada site). Por isso, quando a navegacao falhava, nao sobrava
    // rastro nenhum no Logcat pra saber o motivo. logPrefix identifica de qual
    // protocolo/conexao essas linhas novas vieram (ex: "SSH \"bispo\"").
    private val logPrefix: String = "SOCKS5"
) {
    private companion object {
        // Uma resposta grande pode continuar depois de o remetente fazer half-close.
        // Um minuto evita cortar downloads e ainda recolhe peers que nunca encerram.
        const val RELAY_DRAIN_TIMEOUT_MS = 60_000L
        const val COPY_BUFFER_SIZE = 32 * 1024
        const val DNS_SUCCESS_LOG_INTERVAL_MS = 30_000L
    }

    private enum class PipeEnd {
        EOF,
        PEER_GONE,
        ERROR
    }

    private var serverSocket: ServerSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var running = false
    private val lastDnsSuccessLogAt = AtomicLong(0L)

    val totalRx = AtomicLong(0L)
    val totalTx = AtomicLong(0L)

    fun start() {
        if (running) return
        running = true
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress("127.0.0.1", port))
        serverSocket = server

        scope.launch {
            while (running) {
                val client = try {
                    server.accept()
                } catch (e: Exception) {
                    if (running) continue else break
                }
                launch { handleClient(client) }
            }
        }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        scope.cancel()
    }

    private suspend fun handleClient(client: Socket) {
        try {
            client.tcpNoDelay = true
            val input = client.getInputStream()
            val output = client.getOutputStream()

            val ver = input.read()
            if (ver != 0x05) { client.close(); return }
            val nMethods = input.read()
            val methods = ByteArray(nMethods)
            readFully(input, methods)
            output.write(byteArrayOf(0x05, 0x00))
            output.flush()

            val reqVer = input.read()
            val cmd = input.read()
            input.read()
            val atyp = input.read()
            if (reqVer != 0x05) { client.close(); return }

            val (destHost, destPort) = readAddressAndPort(input, atyp) ?: run {
                output.write(byteArrayOf(0x05, 0x08, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                output.flush()
                client.close()
                return
            }

            when (cmd) {
                0x01 -> {
                    if (destPort == 53) {
                        handleDnsOverTcp(client, destHost)
                    } else {
                        handleConnect(client, input, output, destHost, destPort)
                    }
                }
                0x03 -> handleUdpAssociate(client, output)
                else -> {
                    output.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                    output.flush()
                    client.close()
                }
            }
        } catch (e: Exception) {
            runCatching { client.close() }
        }
    }

    private fun readAddressAndPort(input: InputStream, atyp: Int): Pair<String, Int>? {
        val destHost = when (atyp) {
            0x01 -> {
                val addr = ByteArray(4)
                readFully(input, addr)
                addr.joinToString(".") { (it.toInt() and 0xFF).toString() }
            }
            0x03 -> {
                val len = input.read()
                if (len <= 0) return null
                val nameBytes = ByteArray(len)
                readFully(input, nameBytes)
                String(nameBytes, Charsets.US_ASCII)
            }
            0x04 -> {
                val addr = ByteArray(16)
                readFully(input, addr)
                InetAddress.getByAddress(addr).hostAddress ?: "::1"
            }
            else -> return null
        }
        val portBytes = ByteArray(2)
        readFully(input, portBytes)
        val destPort = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)
        return destHost to destPort
    }

    private val ipv6UnsupportedLogged = ConcurrentHashMap.newKeySet<String>()

    private suspend fun handleConnect(client: Socket, input: InputStream, output: OutputStream, destHost: String, destPort: Int) {
        // CORRECAO: log real do usuario mostrou o celular tentando IPv6 primeiro
        // (padrao do Android/navegador quando o site oferece os dois — "Happy
        // Eyeballs"), TODA tentativa falhando (nenhum protocolo aqui suporta IPv6
        // ainda), e só DEPOIS o mesmo destino em IPv4 funcionando. Deixar essas
        // tentativas IPv6 tentarem de verdade (e esperar o timeout de conexao
        // inteiro) e lento — rejeita na hora, sem tentar, pra o navegador cair pro
        // IPv4 (que funciona) o mais rapido possivel, em vez de esperar.
        // Deteccao simples: literal IPv6 sempre tem ":" no meio (hostname e IPv4
        // nunca tem).
        if (destHost.contains(":")) {
            if (ipv6UnsupportedLogged.add(destHost)) {
                AppLog.log(
                    "$logPrefix: IPv6 ainda não suportado — $destHost:$destPort recusado na hora " +
                        "(o app/navegador deve cair pro IPv4 sozinho, sem demora)",
                    AppLog.Level.ERROR
                )
            }
            output.write(byteArrayOf(0x05, 0x08, 0x00, 0x01, 0, 0, 0, 0, 0, 0)) // 0x08 = tipo de endereco nao suportado
            output.flush()
            client.close()
            return
        }

        val remote = onConnectRequest(destHost, destPort)
        if (remote == null) {
            AppLog.log("$logPrefix: falha ao conectar em $destHost:$destPort (navegação/app não vai funcionar pra esse destino)", AppLog.Level.ERROR)
            output.write(byteArrayOf(0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            output.flush()
            client.close()
            return
        }
        output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
        output.flush()

        val (remoteIn, remoteOut) = remote
        relay(input, remoteOut, output, remoteIn, client, destHost, destPort)
    }

    private suspend fun handleDnsOverTcp(client: Socket, originalDest: String) {
        val targetDns = dns1
        val remote = onConnectRequest(targetDns, 53)
        if (remote == null) {
            client.getOutputStream().write(byteArrayOf(0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            client.getOutputStream().flush()
            client.close()
            return
        }

        client.getOutputStream().write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
        client.getOutputStream().flush()

        val (remoteIn, remoteOut) = remote
        relay(client.getInputStream(), remoteOut, client.getOutputStream(), remoteIn, client, targetDns, 53)
    }

    private suspend fun handleUdpAssociate(client: Socket, output: OutputStream) {
        val opener = onUdpAssociateRequest
        if (opener == null) {
            output.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            output.flush()
            client.close()
            return
        }

        val relaySocket = try {
            DatagramSocket(InetSocketAddress("127.0.0.1", 0))
        } catch (e: Exception) {
            output.write(byteArrayOf(0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            output.flush()
            client.close()
            return
        }

        val relayPort = relaySocket.localPort
        val response = ByteArray(10)
        response[0] = 0x05; response[1] = 0x00; response[2] = 0x00; response[3] = 0x01
        val loopback = InetAddress.getByName("127.0.0.1").address
        System.arraycopy(loopback, 0, response, 4, 4)
        response[8] = ((relayPort shr 8) and 0xFF).toByte()
        response[9] = (relayPort and 0xFF).toByte()
        output.write(response)
        output.flush()

        val sessions = ConcurrentHashMap<String, UdpBackendSession>()
        var clientPeer: InetSocketAddress? = null

        val receiveJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(65535)

            suspend fun sendViaBackend(
                destHost: String,
                destPort: Int,
                payload: ByteArray
            ): Boolean {
                val key = "$destHost:$destPort"

                fun incomingHandler(respPayload: ByteArray) {
                    totalRx.addAndGet(respPayload.size.toLong())
                    val wrapped = buildUdpResponse(destHost, destPort, respPayload)
                    val peer = clientPeer
                    if (peer != null) {
                        runCatching {
                            relaySocket.send(DatagramPacket(wrapped, wrapped.size, peer))
                        }
                    }
                }

                // Duas tentativas: a primeira usa a sessão existente; se o canal
                // persistente do udpgw morreu, remove a sessão velha, o manager cria
                // outro canal e o MESMO datagrama é reenviado uma vez. Antes o send()
                // descartava silenciosamente todos os pacotes da rota antiga.
                repeat(2) { attempt ->
                    var session = sessions[key]
                    if (session == null) {
                        session = opener(destHost, destPort, ::incomingHandler) ?: return false
                        val previous = sessions.putIfAbsent(key, session)
                        if (previous != null) {
                            session.close()
                            session = previous
                        }
                    }

                    try {
                        session.send(payload)
                        return true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        sessions.remove(key, session)
                        runCatching { session.close() }
                        if (attempt == 0 && running) {
                            AppLog.log(
                                "$logPrefix: sessão UDP $key perdeu o gateway; reabrindo e reenviando o pacote",
                                AppLog.Level.INFO
                            )
                        }
                    }
                }
                return false
            }

            try {
                while (isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    relaySocket.receive(packet)
                    clientPeer = InetSocketAddress(packet.address, packet.port)

                    val parsed = parseUdpRequest(buffer, packet.length) ?: continue
                    val (fragDestHost, fragDestPort, payload) = parsed
                    totalTx.addAndGet(payload.size.toLong())

                    // Com o badvpn/udpgw habilitado, o DNS usa o próprio túnel UDP:
                    // é mais rápido, não vaza consultas fora da VPN e evita a rota
                    // direta para 8.8.8.8 que o log real mostrou oscilando. Se o
                    // backend não existir (UDP desabilitado), cai no fallback abaixo.
                    if (sendViaBackend(fragDestHost, fragDestPort, payload)) continue

                    if (fragDestPort == 53) {
                        scope.launch(Dispatchers.IO) {
                            val directTried = protectDatagramSocket != null && (dns1 == "8.8.8.8" || dns1 == "1.1.1.1")
                            var respPayload: ByteArray? = null

                            if (directTried) {
                                respPayload = withTimeoutOrNull(1500.milliseconds) { resolveDnsDirectly(dns1, payload) }
                                logDnsResult(
                                    "$logPrefix: DNS direto (fora do túnel) pra $dns1",
                                    respPayload != null
                                )
                            }

                            if (respPayload == null) {
                                respPayload = withTimeoutOrNull(4000.milliseconds) { resolveDnsViaTcp(dns1, payload) }
                                logDnsResult(
                                    "$logPrefix: DNS via túnel (TCP) pra $dns1",
                                    respPayload != null
                                )
                            }

                            if (respPayload == null) {
                                respPayload = withTimeoutOrNull(4000.milliseconds) { resolveDnsViaTcp(dns2, payload) }
                                logDnsResult(
                                    "$logPrefix: DNS via túnel (TCP) pra $dns2 (2º servidor)",
                                    respPayload != null
                                )
                            }

                            if (respPayload != null) {
                                totalRx.addAndGet(respPayload.size.toLong())
                                val wrapped = buildUdpResponse(fragDestHost, fragDestPort, respPayload)
                                val peer = clientPeer
                                if (peer != null) {
                                    runCatching { relaySocket.send(DatagramPacket(wrapped, wrapped.size, peer)) }
                                }
                            } else {
                                AppLog.log(
                                    "$logPrefix: DNS ESGOTOU todas as opções (direto + túnel x2) — resolução falhou de vez, navegador não vai receber resposta nenhuma",
                                    AppLog.Level.ERROR
                                )
                            }
                        }
                        continue
                    }
                }
            } catch (e: Exception) {
            } finally {
                sessions.values.forEach { runCatching { it.close() } }
                sessions.clear()
                runCatching { relaySocket.close() }
            }
        }

        try {
            val ctrlBuffer = ByteArray(1)
            withContext(Dispatchers.IO) {
                val input = client.getInputStream()
                while (isActive) {
                    val n = input.read(ctrlBuffer)
                    if (n == -1) break
                }
            }
        } catch (e: Exception) {
        } finally {
            receiveJob.cancel()
            runCatching { relaySocket.close() }
            runCatching { client.close() }
        }
    }

    private suspend fun resolveDnsDirectly(dnsServerHost: String, dnsQuery: ByteArray): ByteArray? = withContext(Dispatchers.IO) {
        val protect = protectDatagramSocket ?: return@withContext null
        var socket: DatagramSocket? = null
        return@withContext try {
            socket = DatagramSocket()
            val activeSocket = socket
            if (!protect(activeSocket)) {
                return@withContext null
            }
            activeSocket.soTimeout = 1400
            val dstAddr = InetAddress.getByName(dnsServerHost)
            activeSocket.send(DatagramPacket(dnsQuery, dnsQuery.size, dstAddr, 53))

            val respBuffer = ByteArray(1500)
            val respPacket = DatagramPacket(respBuffer, respBuffer.size)
            activeSocket.receive(respPacket)
            respBuffer.copyOf(respPacket.length)
        } catch (e: Exception) {
            null
        } finally {
            runCatching { socket?.close() }
        }
    }

    private suspend fun resolveDnsViaTcp(dnsServerHost: String, dnsQuery: ByteArray): ByteArray? {
        var remote: Pair<InputStream, OutputStream>? = null
        return try {
            val opened = onConnectRequest(dnsServerHost, 53) ?: return null
            remote = opened
            val (remoteIn, remoteOut) = opened
            runInterruptible(Dispatchers.IO) {
                val len = dnsQuery.size
                remoteOut.write((len shr 8) and 0xFF)
                remoteOut.write(len and 0xFF)
                remoteOut.write(dnsQuery)
                remoteOut.flush()

                val respLen1 = remoteIn.read()
                val respLen2 = remoteIn.read()
                if (respLen1 < 0 || respLen2 < 0) return@runInterruptible null
                val respLen = (respLen1 shl 8) or respLen2

                if (respLen !in 1..4096) return@runInterruptible null

                val resp = ByteArray(respLen)
                readFully(remoteIn, resp)
                resp
            }
        } catch (e: Exception) {
            null
        } finally {
            remote?.let { (remoteIn, remoteOut) ->
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { remoteOut.close() }
                    runCatching { remoteIn.close() }
                }
            }
        }
    }

    private fun parseUdpRequest(buffer: ByteArray, length: Int): Triple<String, Int, ByteArray>? {
        if (length < 4) return null
        if (buffer[2].toInt() != 0) return null
        val atyp = buffer[3].toInt() and 0xFF
        var offset = 4
        val host = when (atyp) {
            0x01 -> {
                if (length < offset + 4) return null
                val addr = buffer.copyOfRange(offset, offset + 4)
                offset += 4
                addr.joinToString(".") { (it.toInt() and 0xFF).toString() }
            }
            0x03 -> {
                if (length < offset + 1) return null
                val len = buffer[offset].toInt() and 0xFF
                offset += 1
                if (length < offset + len) return null
                val name = String(buffer, offset, len, Charsets.US_ASCII)
                offset += len
                name
            }
            0x04 -> {
                if (length < offset + 16) return null
                val addr = buffer.copyOfRange(offset, offset + 16)
                offset += 16
                InetAddress.getByAddress(addr).hostAddress ?: return null
            }
            else -> return null
        }
        if (length < offset + 2) return null
        val port = ((buffer[offset].toInt() and 0xFF) shl 8) or (buffer[offset + 1].toInt() and 0xFF)
        offset += 2
        val payload = buffer.copyOfRange(offset, length)
        return Triple(host, port, payload)
    }

    private fun buildUdpResponse(srcHost: String, srcPort: Int, payload: ByteArray): ByteArray {
        val ipv4Regex = Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")
        val addressBytes = if (srcHost.contains(":")) {
            byteArrayOf(0x04) + InetAddress.getByName(srcHost).address
        } else if (ipv4Regex.matches(srcHost)) {
            val parts = srcHost.split(".").map { it.toInt() }
            byteArrayOf(0x01, parts[0].toByte(), parts[1].toByte(), parts[2].toByte(), parts[3].toByte())
        } else {
            val nameBytes = srcHost.toByteArray(Charsets.US_ASCII)
            require(nameBytes.size <= 255) { "Nome de destino SOCKS5 excede 255 bytes" }
            val result = ByteArray(2 + nameBytes.size)
            result[0] = 0x03
            result[1] = nameBytes.size.toByte()
            System.arraycopy(nameBytes, 0, result, 2, nameBytes.size)
            result
        }
        // RFC 1928: RSV(2) + FRAG(1) + ATYP/endereco + porta. O codigo anterior
        // reservava quatro bytes antes do endereco, inserindo um zero extra; o HEV
        // lia esse byte como ATYP=0 e descartava a resposta UDP (inclusive DNS).
        val header = ByteArray(3 + addressBytes.size + 2)
        System.arraycopy(addressBytes, 0, header, 3, addressBytes.size)
        header[3 + addressBytes.size] = ((srcPort shr 8) and 0xFF).toByte()
        header[3 + addressBytes.size + 1] = (srcPort and 0xFF).toByte()
        return header + payload
    }

    private suspend fun relay(
        clientIn: InputStream, remoteOut: OutputStream,
        clientOut: OutputStream, remoteIn: InputStream,
        client: Socket, destHost: String, destPort: Int
    ) = coroutineScope {
        // Cada direcao recebe seu half-close real. No SSHJ, fechar apenas o
        // ChannelOutputStream envia SSH_MSG_CHANNEL_EOF e continua recebendo a
        // resposta. No socket local usamos shutdownOutput(), que envia FIN sem
        // interromper a outra metade. A V4 fechava o canal inteiro nos dois casos,
        // cortando respostas e acumulando relays presos.
        val tag = "$destHost:$destPort"
        var job1: Job? = null
        var job2: Job? = null
        try {
            val firstFinished = CompletableDeferred<PipeEnd>()
            val upstream = launch(Dispatchers.IO) {
                try {
                    firstFinished.complete(
                        pipe(clientIn, remoteOut, totalTx, "$logPrefix ($tag) [enviando]")
                    )
                } finally {
                    withContext(NonCancellable + Dispatchers.IO) {
                        runCatching { remoteOut.flush() }
                        runCatching { remoteOut.close() }
                    }
                }
            }
            val downstream = launch(Dispatchers.IO) {
                try {
                    firstFinished.complete(
                        pipe(remoteIn, clientOut, totalRx, "$logPrefix ($tag) [recebendo]")
                    )
                } finally {
                    withContext(NonCancellable + Dispatchers.IO) {
                        runCatching { clientOut.flush() }
                        if (!client.isClosed && !client.isOutputShutdown) {
                            runCatching { client.shutdownOutput() }
                        }
                    }
                }
            }
            job1 = upstream
            job2 = downstream

            val firstEnd = firstFinished.await()
            // Broken pipe/reset significa que uma das pontas ja desapareceu. Nao
            // existe resposta a drenar: interromper a outra direcao agora devolve
            // imediatamente o canal SSH. Somente EOF/FIN normal recebe a janela de
            // drenagem para terminar downloads e respostas ainda em transito.
            val endedGracefully = if (firstEnd == PipeEnd.EOF) {
                withTimeoutOrNull(RELAY_DRAIN_TIMEOUT_MS) {
                    joinAll(upstream, downstream)
                    true
                } ?: false
            } else {
                upstream.cancel()
                downstream.cancel()
                joinAll(upstream, downstream)
                true
            }
            if (!endedGracefully && running) {
                AppLog.log(
                    "$logPrefix: relay $tag não drenou em ${RELAY_DRAIN_TIMEOUT_MS / 1000}s; fechando o canal",
                    AppLog.Level.INFO
                )
            }
        } finally {
            // Cancelamento, timeout e desligamento passam obrigatoriamente por aqui.
            // remoteIn.close() fecha o canal/lease inteiro e devolve a vaga uma vez.
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { client.close() }
                runCatching { remoteIn.close() }
                runCatching { remoteOut.close() }
                job1?.cancel()
                job2?.cancel()
                runCatching { clientIn.close() }
                runCatching { clientOut.close() }
            }
        }
    }

    private fun CoroutineScope.pipe(
        from: InputStream,
        to: OutputStream,
        counter: AtomicLong,
        tag: String
    ): PipeEnd {
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        var bytesThisPipe = 0L
        try {
            while (isActive) {
                val n = from.read(buffer)
                if (n <= 0) return PipeEnd.EOF
                to.write(buffer, 0, n)
                // SSHJ ja agrupa ate o tamanho maximo de pacote do canal. So
                // descarrega o residual quando nao ha mais bytes prontos, evitando
                // transformar um download continuo em milhares de pacotes pequenos.
                if (runCatching { from.available() }.getOrDefault(0) == 0) {
                    to.flush()
                }
                counter.addAndGet(n.toLong())
                bytesThisPipe += n
            }
            return PipeEnd.PEER_GONE
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val expectedClose = isExpectedClose(e)
            if (running && !expectedClose) {
                AppLog.log(
                    "$tag: interrompido por erro (${e.javaClass.simpleName}: ${e.message}) após $bytesThisPipe bytes",
                    AppLog.Level.ERROR
                )
            }
            return if (expectedClose) PipeEnd.PEER_GONE else PipeEnd.ERROR
        } finally {
            runCatching { to.flush() }
        }
    }

    private fun logDnsResult(prefix: String, success: Boolean) {
        if (!success) {
            AppLog.log("$prefix — falhou/timeout", AppLog.Level.ERROR)
            return
        }
        val now = android.os.SystemClock.elapsedRealtime()
        while (true) {
            val previous = lastDnsSuccessLogAt.get()
            if (now - previous < DNS_SUCCESS_LOG_INTERVAL_MS) return
            if (lastDnsSuccessLogAt.compareAndSet(previous, now)) {
                AppLog.log("$prefix — respondeu", AppLog.Level.INFO)
                return
            }
        }
    }

    private fun isExpectedClose(error: Exception): Boolean {
        val message = error.message.orEmpty().lowercase()
        return message.contains("socket closed") ||
            message.contains("stream closed") ||
            message.contains("broken pipe") ||
            message.contains("connection reset") ||
            message.contains("reset by peer") ||
            message.contains("disconnected") ||
            message.contains("channel is not open")
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val n = input.read(buffer, offset, buffer.size - offset)
            if (n == -1) throw java.io.EOFException()
            offset += n
        }
    }
}
