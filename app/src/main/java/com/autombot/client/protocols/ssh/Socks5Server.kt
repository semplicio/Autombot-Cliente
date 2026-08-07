package com.autombot.client.protocols.ssh

import android.util.Log
import com.autombot.client.util.AppLog
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
    private var serverSocket: ServerSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var running = false

    private val connectSemaphore = Semaphore(permits = 128)

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

        val remote = connectSemaphore.withPermit { onConnectRequest(destHost, destPort) }
        if (remote == null) {
            AppLog.log("$logPrefix: falha ao conectar em $destHost:$destPort (navegação/app não vai funcionar pra esse destino)", AppLog.Level.ERROR)
            output.write(byteArrayOf(0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            output.flush()
            client.close()
            return
        }
        AppLog.log("$logPrefix: conectado em $destHost:$destPort", AppLog.Level.INFO)

        output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
        output.flush()

        val (remoteIn, remoteOut) = remote
        relay(input, remoteOut, output, remoteIn, client, destHost, destPort)
    }

    private suspend fun handleDnsOverTcp(client: Socket, originalDest: String) {
        val targetDns = dns1
        val remote = connectSemaphore.withPermit { onConnectRequest(targetDns, 53) }
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
            try {
                while (isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    relaySocket.receive(packet)
                    clientPeer = InetSocketAddress(packet.address, packet.port)

                    val parsed = parseUdpRequest(buffer, packet.length) ?: continue
                    val (fragDestHost, fragDestPort, payload) = parsed
                    totalTx.addAndGet(payload.size.toLong())

                    val key = "$fragDestHost:$fragDestPort"

                    if (fragDestPort == 53) {
                        scope.launch(Dispatchers.IO) {
                            var respPayload = if (protectDatagramSocket != null && (dns1 == "8.8.8.8" || dns1 == "1.1.1.1")) {
                                withTimeoutOrNull(1500.milliseconds) { resolveDnsDirectly(dns1, payload) }
                            } else null

                            if (respPayload == null) {
                                respPayload = withTimeoutOrNull(4000.milliseconds) {
                                    resolveDnsViaTcp(dns1, payload)
                                }
                            }

                            if (respPayload == null) {
                                respPayload = withTimeoutOrNull(4000.milliseconds) {
                                    resolveDnsViaTcp(dns2, payload)
                                }
                            }

                            if (respPayload != null) {
                                totalRx.addAndGet(respPayload.size.toLong())
                                val wrapped = buildUdpResponse(fragDestHost, fragDestPort, respPayload)
                                val peer = clientPeer
                                if (peer != null) {
                                    runCatching { relaySocket.send(DatagramPacket(wrapped, wrapped.size, peer)) }
                                }
                            }
                        }
                        continue
                    }

                    var session = sessions[key]
                    if (session == null) {
                        val opened = opener(fragDestHost, fragDestPort) { respPayload ->
                            totalRx.addAndGet(respPayload.size.toLong())
                            val wrapped = buildUdpResponse(fragDestHost, fragDestPort, respPayload)
                            val peer = clientPeer
                            if (peer != null) {
                                runCatching {
                                    relaySocket.send(DatagramPacket(wrapped, wrapped.size, peer))
                                }
                            }
                        }
                        if (opened == null) continue
                        sessions[key] = opened
                        session = opened
                    }
                    session.send(payload)
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
        return@withContext try {
            val socket = DatagramSocket()
            if (!protect(socket)) {
                socket.close()
                return@withContext null
            }
            socket.soTimeout = 3000
            val dstAddr = InetAddress.getByName(dnsServerHost)
            socket.send(DatagramPacket(dnsQuery, dnsQuery.size, dstAddr, 53))

            val respBuffer = ByteArray(1500)
            val respPacket = DatagramPacket(respBuffer, respBuffer.size)
            socket.receive(respPacket)
            socket.close()
            respBuffer.copyOf(respPacket.length)
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun resolveDnsViaTcp(dnsServerHost: String, dnsQuery: ByteArray): ByteArray? {
        val remote = connectSemaphore.withPermit { onConnectRequest(dnsServerHost, 53) } ?: return null
        val (remoteIn, remoteOut) = remote
        return try {
            val len = dnsQuery.size
            remoteOut.write((len shr 8) and 0xFF)
            remoteOut.write(len and 0xFF)
            remoteOut.write(dnsQuery)
            remoteOut.flush()

            val respLen1 = remoteIn.read()
            val respLen2 = remoteIn.read()
            if (respLen1 < 0 || respLen2 < 0) return null
            val respLen = (respLen1 shl 8) or respLen2

            if (respLen > 4096) return null

            val resp = ByteArray(respLen)
            readFully(remoteIn, resp)
            resp
        } catch (e: Exception) {
            null
        } finally {
            runCatching { remoteIn.close() }
            runCatching { remoteOut.close() }
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
        val addressBytes = if (ipv4Regex.matches(srcHost)) {
            val parts = srcHost.split(".").map { it.toInt() }
            byteArrayOf(0x01, parts[0].toByte(), parts[1].toByte(), parts[2].toByte(), parts[3].toByte())
        } else {
            val nameBytes = srcHost.toByteArray(Charsets.US_ASCII)
            val result = ByteArray(2 + nameBytes.size)
            result[0] = 0x03
            result[1] = nameBytes.size.toByte()
            System.arraycopy(nameBytes, 0, result, 2, nameBytes.size)
            result
        }
        val header = ByteArray(4 + addressBytes.size + 2)
        System.arraycopy(addressBytes, 0, header, 4, addressBytes.size)
        header[4 + addressBytes.size] = ((srcPort shr 8) and 0xFF).toByte()
        header[4 + addressBytes.size + 1] = (srcPort and 0xFF).toByte()
        return header + payload
    }

    private suspend fun relay(
        clientIn: InputStream, remoteOut: OutputStream,
        clientOut: OutputStream, remoteIn: InputStream,
        client: Socket, destHost: String, destPort: Int
    ) = coroutineScope {
        // CORRECAO: essa funcao (quem de fato move os dados DEPOIS que "conectado"
        // ja apareceu no log) nao registrava nada — nem quantos bytes passaram, nem
        // por que uma direcao parou. Usuario confirmou que "conectado" aparece no
        // log, mas o navegador nao recebe dado NENHUM — ou seja, o problema pode
        // estar bem aqui, nao na conexao em si. Esse log novo mostra exatamente
        // quantos bytes fluiram em cada direcao e o motivo de parar (fim normal,
        // erro, ou cancelado pela outra ponta).
        val tag = "$destHost:$destPort"
        val job1 = launch(Dispatchers.IO) { 
            try { pipe(clientIn, remoteOut, totalTx, "$logPrefix ($tag) [enviando]") } 
            finally { this@coroutineScope.cancel() } 
        }
        val job2 = launch(Dispatchers.IO) { 
            try { pipe(remoteIn, clientOut, totalRx, "$logPrefix ($tag) [recebendo]") } 
            finally { this@coroutineScope.cancel() } 
        }
        
        try {
            joinAll(job1, job2)
        } catch (e: CancellationException) {
        } finally {
            AppLog.log(
                "$logPrefix: relay encerrado pra $tag — total enviado ${totalTx.get()}B, recebido ${totalRx.get()}B (desde que o servidor ligou; nao so essa conexao)",
                AppLog.Level.INFO
            )
            runCatching { client.close() }
            runCatching { clientIn.close() }
            runCatching { remoteOut.close() }
            runCatching { clientOut.close() }
            runCatching { remoteIn.close() }
        }
    }

    private fun CoroutineScope.pipe(from: InputStream, to: OutputStream, counter: AtomicLong, tag: String) {
        val buffer = ByteArray(16384)
        var bytesThisPipe = 0L
        try {
            while (isActive) {
                val n = from.read(buffer)
                if (n <= 0) {
                    AppLog.log("$tag: fim do fluxo (read retornou $n) após $bytesThisPipe bytes", AppLog.Level.INFO)
                    break
                }
                to.write(buffer, 0, n)
                to.flush()
                counter.addAndGet(n.toLong())
                bytesThisPipe += n
            }
        } catch (e: Exception) {
            AppLog.log("$tag: interrompido por erro (${e.javaClass.simpleName}: ${e.message}) após $bytesThisPipe bytes", AppLog.Level.ERROR)
        }
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