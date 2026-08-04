package com.autombot.client.protocols.ssh

import android.util.Log
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
 * Servidor SOCKS5 minimo, local (127.0.0.1), sem autenticacao. Implementacao propria
 * (RFC 1928) porque nenhuma lib do projeto fornece isso pronto; nao depende de nada
 * alem de sockets Java puros.
 *
 * Suporta dois comandos:
 *  - CONNECT (0x01): pra cada conexao aceita, chama [onConnectRequest] com o
 *    host/porta de destino, espera um par de streams (input/output) ja conectadas ao
 *    destino real (via SSH/VLESS/VMess/Shadowsocks), e so faz o relay de bytes.
 *  - UDP ASSOCIATE (0x03): CORRECAO — antes nao suportado, o motor de tun2socks
 *    NATIVO (hev-socks5-tunnel, ver SPEC.md Etapa 61/62) tenta negociar UDP de
 *    verdade com esse servidor local pra tunelar UDP genuino atraves de qualquer
 *    protocolo — sem isso, esse UDP era recusado ("comando nao suportado") mesmo com
 *    o motor novo funcionando certo. Se [onUdpAssociateRequest] for null (protocolo
 *    nao sabe fazer UDP — caso do SSH puro, exceto o jeitinho de porta 443), o pedido
 *    e recusado normalmente.
 *
 * [totalRx]/[totalTx] contam o trafego real que passa por aqui (CONNECT + UDP
 * ASSOCIATE somados) — usado pelos *TunnelManager pra alimentar as estatisticas na
 * tela.
 */
class Socks5Server(
    private val port: Int,
    private val onConnectRequest: suspend (host: String, port: Int) -> Pair<InputStream, OutputStream>?,
    private val onUdpAssociateRequest: UdpAssociateOpener? = null,
    private val protectDatagramSocket: ((DatagramSocket) -> Boolean)? = null,
    private val dns1: String = "8.8.8.8",
    private val dns2: String = "8.8.4.4"
) {
    private var serverSocket: ServerSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var running = false

    // CORRECAO: cada onConnectRequest() pode abrir uma conexao real NOVA (WebSocket ou
    // TCP direto) com um protect() proprio — o navegador abre dezenas dessas ao mesmo
    // tempo (varias abas/recursos de uma pagina), sem limite nenhum antes disso. Achado
    // real: SSH (que so precisa de UM protect(), reaproveitando a mesma conexao pra
    // varios canais) gerou trafego de verdade, enquanto VLESS/VMess/Shadowsocks (que
    // abrem uma conexao NOVA por destino) sempre falham em protect(). Suspeita forte:
    // protect() do Android comeca a falhar sob rajada de chamadas simultaneas — esse
    // limite tambem deve ajudar com a lentidao/travamento geral (menos sockets
    // disputando recursos ao mesmo tempo).
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
            val input = client.getInputStream()
            val output = client.getOutputStream()

            // --- Handshake de metodos (RFC 1928 secao 3) ---
            val ver = input.read()
            if (ver != 0x05) { client.close(); return }
            val nMethods = input.read()
            val methods = ByteArray(nMethods)
            readFully(input, methods)
            // Sempre responde "sem autenticacao" (0x00) — servidor local, uso interno.
            output.write(byteArrayOf(0x05, 0x00))
            output.flush()

            // --- Request (RFC 1928 secao 4) ---
            val reqVer = input.read()
            val cmd = input.read()
            input.read() // RSV, reservado, ignorado
            val atyp = input.read()
            if (reqVer != 0x05) { client.close(); return }

            val (destHost, destPort) = readAddressAndPort(input, atyp) ?: run {
                output.write(byteArrayOf(0x05, 0x08, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                client.close()
                return
            }

            // LOG: registrando cada pedido de conexao pra depuracao
            // android.util.Log.d("Socks5Server", "Pedido: ${if(cmd==0x01) "CONNECT" else "UDP"} -> $destHost:$destPort")

            when (cmd) {
                0x01 -> {
                    // CORRECAO: tambem interceptamos pedidos CONNECT (TCP) para a porta 53.
                    // Isso acontece se o sistema ou o motor nativo tentarem DNS-over-TCP
                    // para um DNS que nao alcancam (ex: DNS da operadora pelo tunel).
                    if (destPort == 53) {
                        handleDnsOverTcp(client, destHost)
                    } else {
                        handleConnect(client, input, output, destHost, destPort)
                    }
                }
                0x03 -> handleUdpAssociate(client, output)
                else -> {
                    // BIND (0x02) e qualquer outro: nao suportado.
                    output.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                    client.close()
                }
            }
        } catch (e: Exception) {
            runCatching { client.close() }
        }
    }

    private fun readAddressAndPort(input: InputStream, atyp: Int): Pair<String, Int>? {
        val destHost = when (atyp) {
            0x01 -> { // IPv4
                val addr = ByteArray(4)
                readFully(input, addr)
                addr.joinToString(".") { (it.toInt() and 0xFF).toString() }
            }
            0x03 -> { // domain name
                val len = input.read()
                val nameBytes = ByteArray(len)
                readFully(input, nameBytes)
                String(nameBytes, Charsets.US_ASCII)
            }
            0x04 -> { // IPv6 — le mas nao testamos a fundo, uso raro nesse cenario
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

    private suspend fun handleConnect(client: Socket, input: InputStream, output: OutputStream, destHost: String, destPort: Int) {
        // So deixa um numero limitado de conexoes reais sendo abertas ao mesmo
        // tempo — ver comentario no connectSemaphore acima.
        val remote = connectSemaphore.withPermit { onConnectRequest(destHost, destPort) }
        if (remote == null) {
            // 0x05 = falha na conexao com o host de destino
            output.write(byteArrayOf(0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            client.close()
            return
        }

        // Sucesso — devolve endereco/porta "de bind" generico (nao usado de verdade
        // pelo cliente SOCKS na pratica para CONNECT).
        output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
        output.flush()

        val (remoteIn, remoteOut) = remote
        relay(input, remoteOut, output, remoteIn, client)
    }

    /**
     * Intercepta uma conexao TCP destinada a porta 53 (DNS-over-TCP) e redireciona
     * para o DNS manual configurado, através do túnel.
     */
    private suspend fun handleDnsOverTcp(client: Socket, originalDest: String) {
        val targetDns = dns1 // Redireciona sempre pro DNS primário manual
        
        val remote = connectSemaphore.withPermit { onConnectRequest(targetDns, 53) }
        if (remote == null) {
            client.getOutputStream().write(byteArrayOf(0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            client.close()
            return
        }

        client.getOutputStream().write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
        client.getOutputStream().flush()

        val (remoteIn, remoteOut) = remote
        relay(client.getInputStream(), remoteOut, client.getOutputStream(), remoteIn, client)
    }

    /**
     * UDP ASSOCIATE (RFC 1928, secao "UDP associate"): abre um socket UDP local
     * novo (a "relay") e devolve o endereco/porta dele pro cliente — a partir dai o
     * cliente manda datagramas SOCKS5-encapsulados pra essa porta, e a gente
     * encaminha o payload de cada um pro destino real de verdade atraves de
     * [onUdpAssociateRequest], devolvendo qualquer resposta no mesmo formato.
     *
     * A conexao TCP de controle (o [client] que fez o pedido) tem que continuar
     * aberta durante toda a associacao — quando ela fechar, a relay UDP fecha junto
     * (e assim que o motor nativo sinaliza "acabei de usar esse destino").
     */
    private suspend fun handleUdpAssociate(client: Socket, output: OutputStream) {
        val opener = onUdpAssociateRequest
        if (opener == null) {
            // Esse protocolo nao sabe fazer UDP nenhum — recusa educadamente.
            output.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            client.close()
            return
        }

        val relaySocket = try {
            DatagramSocket(InetSocketAddress("127.0.0.1", 0))
        } catch (e: Exception) {
            output.write(byteArrayOf(0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            client.close()
            return
        }

        val relayPort = relaySocket.localPort
        // Resposta: BND.ADDR/BND.PORT = onde o cliente deve mandar os datagramas.
        val response = ByteArray(10)
        response[0] = 0x05; response[1] = 0x00; response[2] = 0x00; response[3] = 0x01
        val loopback = InetAddress.getByName("127.0.0.1").address
        System.arraycopy(loopback, 0, response, 4, 4)
        response[8] = ((relayPort shr 8) and 0xFF).toByte()
        response[9] = (relayPort and 0xFF).toByte()
        output.write(response)
        output.flush()

        // Uma associacao pode falar com VARIOS destinos diferentes ao longo do tempo
        // (o motor nativo reaproveita a mesma relay UDP pra qualquer coisa que o
        // app do celular tentar acessar) — uma sessao de backend por destino,
        // criada sob demanda na primeira vez que aparece.
        val sessions = ConcurrentHashMap<String, UdpBackendSession>()
        var clientPeer: InetSocketAddress? = null

        // Enquanto a relay estiver viva, fica recebendo datagramas SOCKS5-UDP do
        // cliente (o motor nativo) e encaminhando pro backend certo.
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

                    // CORRECAO CRITICA (ver comentario no construtor): porta 53
                    // (DNS) SEMPRE tenta resolucao direta primeiro, ignorando por
                    // completo qual protocolo esta ativo — nao depende do backend
                    // (SSH/VLESS/VMess/Shadowsocks/Trojan) saber tunelar UDP
                    // genuino. Sem isso, com SSH (que so cobre porta 443), TODA
                    // consulta DNS falhava — dava a impressao de "sem internet"
                    // mesmo com o tunel de pe.
                    if (fragDestPort == 53) {
                        scope.launch(Dispatchers.IO) {
                            // CORRECAO: Ignoramos o DNS que o sistema pediu (fragDestHost)
                            // e forcamos a resolucao atraves dos DNS manuais configurados.
                            // Isso e essencial quando o DNS da operadora e inalcancavel 
                            // a partir do servidor remoto.

                            // Tenta primeiro via UDP protegido (se for um dos nossos DNS)
                            var respPayload = if (protectDatagramSocket != null && (dns1 == "8.8.8.8" || dns1 == "1.1.1.1")) {
                                withTimeoutOrNull(1500.milliseconds) { resolveDnsDirectly(dns1, payload) }
                            } else null

                            // Se falhou ou nao e um DNS publico comum, tenta via TCP pelo tunel
                            if (respPayload == null) {
                                respPayload = withTimeoutOrNull(4000.milliseconds) { 
                                    resolveDnsViaTcp(dns1, payload) 
                                }
                            }

                            // Fallback pro DNS secundario
                            if (respPayload == null) {
                                respPayload = withTimeoutOrNull(4000.milliseconds) { 
                                    resolveDnsViaTcp(dns2, payload) 
                                }
                            }

                            if (respPayload != null) {
                                // android.util.Log.d("Socks5Server", "DNS resolvido para $fragDestHost via $dns1")
                                totalRx.addAndGet(respPayload.size.toLong())
                                // Devolve pro sistema fingindo que veio do DNS original que ele pediu
                                val wrapped = buildUdpResponse(fragDestHost, fragDestPort, respPayload)
                                val peer = clientPeer
                                if (peer != null) {
                                    runCatching { relaySocket.send(DatagramPacket(wrapped, wrapped.size, peer)) }
                                }
                            } else {
                                Log.w("Socks5Server", "Falha total ao resolver DNS (pediu $fragDestHost, tentou $dns1/$dns2)")
                            }
                        }
                        continue
                    }

                    var session = sessions[key]
                    if (session == null) {
                        // onIncoming: quando o backend trouxer uma resposta de volta,
                        // embrulha no mesmo formato SOCKS5-UDP e manda pro cliente.
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
                // relay fechada ou erro — cai pro finally
            } finally {
                sessions.values.forEach { runCatching { it.close() } }
                sessions.clear()
                runCatching { relaySocket.close() }
            }
        }

        // A conexao TCP de controle nao manda mais nada depois do request — so
        // fica aberta pra sinalizar "a associacao ainda esta em uso". Quando ela
        // fechar (o motor nativo derrubou ou o app desconectou), a leitura abaixo
        // retorna -1 e a gente encerra tudo.
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
            // conexao de controle caiu — normal ao desconectar
        } finally {
            receiveJob.cancel()
            runCatching { relaySocket.close() }
            runCatching { client.close() }
        }
    }

    /**
     * Resolve uma consulta DNS direto, através de um socket UDP protegido de
     * verdade (não pelo protocolo ativo) — igual o motor antigo fazia. Se
     * [protectDatagramSocket] não estiver disponível ou a proteção falhar, retorna
     * null (o chamador simplesmente não responde esse pacote, como qualquer UDP não
     * suportado).
     */
    private suspend fun resolveDnsDirectly(dnsServerHost: String, dnsQuery: ByteArray): ByteArray? {
        val protect = protectDatagramSocket ?: return null
        return try {
            val socket = DatagramSocket()
            if (!protect(socket)) {
                socket.close()
                return null
            }
            socket.soTimeout = 5000
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

    /**
     * Resolve uma consulta DNS via TCP através do tunel (callback onConnectRequest).
     * Útil quando o UDP está quebrado ou bloqueado na rede real.
     */
    private suspend fun resolveDnsViaTcp(dnsServerHost: String, dnsQuery: ByteArray): ByteArray? {
        val remote = connectSemaphore.withPermit { onConnectRequest(dnsServerHost, 53) } ?: return null
        val (remoteIn, remoteOut) = remote
        return try {
            // DNS-over-TCP (RFC 1035 secao 4.2.2): prefixa com 2 bytes de tamanho
            val len = dnsQuery.size
            remoteOut.write((len shr 8) and 0xFF)
            remoteOut.write(len and 0xFF)
            remoteOut.write(dnsQuery)
            remoteOut.flush()

            val respLen1 = remoteIn.read()
            val respLen2 = remoteIn.read()
            if (respLen1 < 0 || respLen2 < 0) return null
            val respLen = (respLen1 shl 8) or respLen2
            
            if (respLen > 4096) return null // sanidade

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

    /** [RSV 2][FRAG 1][ATYP 1][ADDR][PORT 2][DATA] — RFC 1928, formato do datagrama UDP. */
    private fun parseUdpRequest(buffer: ByteArray, length: Int): Triple<String, Int, ByteArray>? {
        if (length < 4) return null
        // RSV (2 bytes, ignorado), FRAG (1 byte — fragmentacao nao suportada, so
        // aceitamos FRAG=0, senao descarta).
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

    /** Embrulha um payload de resposta no mesmo formato SOCKS5-UDP, com ATYP conforme o destino original. */
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
        // RSV(2)=0, FRAG(1)=0 ja ficam 0 por padrao no ByteArray novo
        System.arraycopy(addressBytes, 0, header, 4, addressBytes.size)
        header[4 + addressBytes.size] = ((srcPort shr 8) and 0xFF).toByte()
        header[4 + addressBytes.size + 1] = (srcPort and 0xFF).toByte()
        return header + payload
    }

    private suspend fun relay(
        clientIn: InputStream, remoteOut: OutputStream,
        clientOut: OutputStream, remoteIn: InputStream,
        client: Socket
    ) = coroutineScope {
        // CORRECAO CRITICA: se um lado da conexao fechar, o outro deve fechar
        // imediatamente. Antes usavamos joinAll(), o que fazia com que, se um
        // lado travasse (comum em SSH), o canal ficasse aberto ocupando slot no
        // servidor ate estourar o timeout — o que derrubava a internet apos 
        // algumas dezenas de conexoes do navegador.
        val job1 = launch(Dispatchers.IO) { 
            try { pipe(clientIn, remoteOut, totalTx) } 
            finally { this@coroutineScope.cancel() } 
        }
        val job2 = launch(Dispatchers.IO) { 
            try { pipe(remoteIn, clientOut, totalRx) } 
            finally { this@coroutineScope.cancel() } 
        }
        
        try {
            joinAll(job1, job2)
        } catch (e: CancellationException) {
            // normal
        } finally {
            runCatching { client.close() }
            runCatching { clientIn.close() }
            runCatching { remoteOut.close() }
            runCatching { clientOut.close() }
            runCatching { remoteIn.close() }
        }
    }

    private suspend fun CoroutineScope.pipe(from: InputStream, to: OutputStream, counter: AtomicLong) {
        val buffer = ByteArray(16384)
        try {
            while (isActive) {
                val n = withContext(Dispatchers.IO) { from.read(buffer) }
                if (n <= 0) break
                withContext(Dispatchers.IO) {
                    to.write(buffer, 0, n)
                    // to.flush() // Removido: deixa o OS/SSH lib gerenciar o buffer
                }
                counter.addAndGet(n.toLong())
            }
        } catch (e: Exception) {
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