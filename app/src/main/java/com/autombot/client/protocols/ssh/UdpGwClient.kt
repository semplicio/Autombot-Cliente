package com.autombot.client.protocols.ssh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Cliente do protocolo badvpn-udpgw — conferido bit a bit contra o código-fonte
 * OFICIAL (github.com/ambrop72/badvpn, BSD-3-clause), arquivos
 * `protocol/udpgw_proto.h` e `protocol/packetproto.h`, buscados direto da fonte
 * (não por dedução) depois de descobrir que a implementação anterior
 * (`openUdpOverGateway`) tinha sido escrita com um protocolo inventado, citando uma
 * fonte que nem existe.
 *
 * Mantém UMA conexão persistente com o servidor udpgw (alcançada por um canal
 * direct-tcpip do SSH até a porta configurada no VPS), multiplexando VÁRIOS
 * destinos diferentes por ela — cada um identificado por um "conid" (connection id)
 * de 16 bits escolhido pelo cliente. Isso é bem diferente do padrão "uma conexão
 * nova por destino" usado pelos outros protocolos (VLESS/VMess) — aqui é uma
 * conexão só, compartilhada, igual ao Shadowsocks UDP.
 *
 * FRAMING (PacketProto): cada mensagem trocada = [2 bytes de tamanho do payload,
 * LITTLE-ENDIAN][aquela quantidade de bytes de payload].
 *
 * PAYLOAD de cada mensagem = [1 byte flags][2 bytes conid, LITTLE-ENDIAN] +
 * (SÓ na primeira mensagem de um conid novo) [4 bytes IP, LITTLE-ENDIAN][2 bytes
 * porta, LITTLE-ENDIAN] + dados brutos do datagrama UDP. Mensagens seguintes no
 * mesmo conid (nos dois sentidos) não repetem o endereço.
 *
 * ATENÇÃO: todos os campos multi-byte do protocolo são LITTLE-ENDIAN — o oposto da
 * convenção usual de rede (sockaddr, etc, que é big-endian) — confirmado
 * explicitamente no comentário do cabeçalho oficial do packetproto.h.
 *
 * Só IPv4 implementado (bate com o resto do projeto, que já assume IPv4 em todo
 * lugar). DNS (porta 53) nunca passa por aqui — já é resolvido direto e protegido
 * em Socks5Server.resolveDnsDirectly(), independente do protocolo ativo.
 */
class UdpGwClient(
    private val channelIn: InputStream,
    private val channelOut: OutputStream,
    private val scope: CoroutineScope
) {
    companion object {
        private const val FLAG_KEEPALIVE = 0x01
        // REBIND (0x02) e DNS (0x04) existem no protocolo mas não são usados por
        // nós — DNS já tem seu próprio caminho dedicado (ver comentário acima).
        private const val KEEPALIVE_INTERVAL_MS = 15_000L
    }

    private val nextConid = AtomicInteger(0)
    private val onIncomingByConid = ConcurrentHashMap<Int, (ByteArray) -> Unit>()
    private val conidByDest = ConcurrentHashMap<String, Int>()
    private val writeLock = Any()
    @Volatile private var closed = false
    private var readerJob: Job? = null
    private var keepaliveJob: Job? = null

    fun start() {
        readerJob = scope.launch(Dispatchers.IO) { readLoop() }
        keepaliveJob = scope.launch(Dispatchers.IO) {
            while (isActive && !closed) {
                delay(KEEPALIVE_INTERVAL_MS)
                runCatching { sendKeepalive() }
            }
        }
    }

    fun stop() {
        closed = true
        readerJob?.cancel()
        keepaliveJob?.cancel()
        onIncomingByConid.clear()
        conidByDest.clear()
        runCatching { channelIn.close() }
        runCatching { channelOut.close() }
    }

    /** Abre (ou reaproveita) um "conid" pra esse destino específico. */
    fun openSession(destHost: String, destPort: Int, onIncoming: (ByteArray) -> Unit): UdpBackendSession? {
        if (closed) return null
        val key = "$destHost:$destPort"
        val isNewDest = !conidByDest.containsKey(key)
        val conid = conidByDest.getOrPut(key) { allocateConid() }
        onIncomingByConid[conid] = onIncoming

        return object : UdpBackendSession {
            // O endereço só precisa ir na PRIMEIRA mensagem que estabelece esse
            // conid — controlado aqui, por instância de sessão.
            @Volatile private var addressPending = isNewDest

            override suspend fun send(payload: ByteArray) {
                val includeAddress = addressPending
                addressPending = false
                runCatching { sendPacket(conid, destHost, destPort, payload, includeAddress) }
            }
            override fun close() {
                onIncomingByConid.remove(conid)
                conidByDest.remove(key)
            }
        }
    }

    private fun allocateConid(): Int {
        var candidate: Int
        do {
            candidate = nextConid.getAndIncrement() and 0xFFFF
        } while (onIncomingByConid.containsKey(candidate))
        return candidate
    }

    private suspend fun sendPacket(conid: Int, destHost: String, destPort: Int, payload: ByteArray, includeAddress: Boolean) {
        val addrBytes = if (includeAddress) encodeIpv4AddressLE(destHost, destPort) else ByteArray(0)
        val udpgwPayload = ByteArray(3 + addrBytes.size + payload.size)
        udpgwPayload[0] = 0 // flags = 0 (tráfego normal, não é keepalive)
        udpgwPayload[1] = (conid and 0xFF).toByte()          // conid, byte baixo primeiro (LE)
        udpgwPayload[2] = ((conid shr 8) and 0xFF).toByte()  // conid, byte alto
        System.arraycopy(addrBytes, 0, udpgwPayload, 3, addrBytes.size)
        System.arraycopy(payload, 0, udpgwPayload, 3 + addrBytes.size, payload.size)
        writeFramed(udpgwPayload)
    }

    private suspend fun sendKeepalive() {
        // Header de 3 bytes só, sem endereço nem payload — conid não importa aqui.
        writeFramed(byteArrayOf(FLAG_KEEPALIVE.toByte(), 0, 0))
    }

    /** Endereço IPv4 + porta, LITTLE-ENDIAN (ver aviso na doc da classe). */
    private fun encodeIpv4AddressLE(host: String, port: Int): ByteArray {
        // InetAddress.address vem em ordem de rede (big-endian, byte mais
        // significativo primeiro) — o udpgw quer little-endian, por isso invertido.
        val addr = InetAddress.getByName(host).address
        return byteArrayOf(
            addr[3], addr[2], addr[1], addr[0],
            (port and 0xFF).toByte(),
            ((port shr 8) and 0xFF).toByte()
        )
    }

    private suspend fun writeFramed(payload: ByteArray) = withContext(Dispatchers.IO) {
        synchronized(writeLock) {
            val header = byteArrayOf(
                (payload.size and 0xFF).toByte(),
                ((payload.size shr 8) and 0xFF).toByte()
            )
            channelOut.write(header)
            channelOut.write(payload)
            channelOut.flush()
        }
    }

    private suspend fun readLoop() {
        try {
            while (!closed) {
                val lenBytes = readExact(2) ?: break
                val len = (lenBytes[0].toInt() and 0xFF) or ((lenBytes[1].toInt() and 0xFF) shl 8)
                if (len < 3) continue // pacote menor que o cabeçalho mínimo (flags+conid) — inválido, ignora
                val payload = readExact(len) ?: break

                val flags = payload[0].toInt() and 0xFF
                if (flags and FLAG_KEEPALIVE != 0) continue // resposta de keepalive — sem dado real, ignora

                val conid = (payload[1].toInt() and 0xFF) or ((payload[2].toInt() and 0xFF) shl 8)
                if (payload.size > 3) {
                    val data = payload.copyOfRange(3, payload.size)
                    onIncomingByConid[conid]?.invoke(data)
                }
            }
        } catch (e: Exception) {
            // canal fechado — normal ao desconectar
        } finally {
            closed = true
        }
    }

    private fun readExact(size: Int): ByteArray? {
        val buf = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val n = channelIn.read(buf, offset, size - offset)
            if (n == -1) return null
            offset += n
        }
        return buf
    }
}