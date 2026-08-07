package com.autombot.client.protocols.ssh

import com.autombot.client.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
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
 * [endereço IP][porta em ordem de rede] + dados brutos do datagrama UDP. O
 * endereço faz parte de TODOS os pacotes, nos dois sentidos; o servidor oficial
 * rejeita mensagens sem ele antes mesmo de procurar o conid.
 *
 * O tamanho PacketProto e o conid são little-endian. IP e porta são copiados de
 * BAddr/sockaddr pelo cliente oficial e, portanto, permanecem em ordem de rede.
 *
 * IPv4 e IPv6 usam o mesmo framing, diferenciados por FLAG_IPV6. DNS (porta 53)
 * não passa por aqui — já é resolvido pelo caminho dedicado do Socks5Server.
 */
class UdpGwClient(
    private val channelIn: InputStream,
    private val channelOut: OutputStream,
    private val scope: CoroutineScope
) {
    companion object {
        private const val FLAG_KEEPALIVE = 0x01
        private const val FLAG_REBIND = 0x02
        private const val FLAG_IPV6 = 0x08
        private const val KEEPALIVE_INTERVAL_MS = 15_000L
    }

    private val nextConid = AtomicInteger(0)
    private val onIncomingByConid = ConcurrentHashMap<Int, (ByteArray) -> Unit>()
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
        runCatching { channelIn.close() }
        runCatching { channelOut.close() }
    }

    /** Permite ao gerenciador recriar o canal se o udpgw remoto o encerrar. */
    fun isClosed(): Boolean = closed

    /** Abre um conid exclusivo para esta sessão SOCKS5 UDP. */
    fun openSession(destHost: String, destPort: Int, onIncoming: (ByteArray) -> Unit): UdpBackendSession? {
        if (closed) return null
        val encodedAddress = runCatching { encodeAddress(destHost, destPort) }
            .onFailure {
                AppLog.log("Gateway UDP: endereço inválido $destHost:$destPort (${it.message})", AppLog.Level.ERROR)
            }
            .getOrNull() ?: return null

        val conid = allocateConid()
        onIncomingByConid[conid] = onIncoming
        val firstPacket = AtomicBoolean(true)
        val sessionClosed = AtomicBoolean(false)

        return object : UdpBackendSession {
            override suspend fun send(payload: ByteArray) {
                if (sessionClosed.get() || closed) return
                val flags = encodedAddress.flags or if (firstPacket.getAndSet(false)) FLAG_REBIND else 0
                runCatching { sendPacket(conid, encodedAddress.bytes, flags, payload) }
                    .onFailure {
                        AppLog.log("Gateway UDP: falha ao enviar para $destHost:$destPort (${it.message})", AppLog.Level.ERROR)
                    }
            }

            override fun close() {
                if (sessionClosed.compareAndSet(false, true)) {
                    onIncomingByConid.remove(conid)
                }
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

    private suspend fun sendPacket(conid: Int, addrBytes: ByteArray, flags: Int, payload: ByteArray) {
        val udpgwPayload = ByteArray(3 + addrBytes.size + payload.size)
        udpgwPayload[0] = flags.toByte()
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

    private data class EncodedAddress(val bytes: ByteArray, val flags: Int)

    /** Endereço + porta exatamente como BAddr/sockaddr aparecem no protocolo oficial. */
    private fun encodeAddress(host: String, port: Int): EncodedAddress {
        require(port in 1..65535) { "porta fora do intervalo" }
        val addresses = InetAddress.getAllByName(host)
        val address = addresses.firstOrNull { it.address.size == 4 } ?: addresses.firstOrNull()
            ?: throw IOException("não foi possível resolver o endereço")
        val raw = address.address
        require(raw.size == 4 || raw.size == 16) { "família de endereço não suportada" }
        val bytes = ByteArray(raw.size + 2)
        System.arraycopy(raw, 0, bytes, 0, raw.size)
        bytes[raw.size] = ((port shr 8) and 0xFF).toByte()
        bytes[raw.size + 1] = (port and 0xFF).toByte()
        return EncodedAddress(bytes, if (raw.size == 16) FLAG_IPV6 else 0)
    }

    private suspend fun writeFramed(payload: ByteArray) = withContext(Dispatchers.IO) {
        require(payload.size <= 0xFFFF) { "datagrama excede o limite do PacketProto" }
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
                val payload = readExact(len) ?: break
                if (len < 3) continue // já consumiu o frame; pacote inválido é ignorado sem dessincronizar o stream

                val flags = payload[0].toInt() and 0xFF
                if (flags and FLAG_KEEPALIVE != 0) continue // resposta de keepalive — sem dado real, ignora

                val conid = (payload[1].toInt() and 0xFF) or ((payload[2].toInt() and 0xFF) shl 8)
                val addressSize = if (flags and FLAG_IPV6 != 0) 16 + 2 else 4 + 2
                val dataOffset = 3 + addressSize
                if (payload.size >= dataOffset) {
                    val data = payload.copyOfRange(dataOffset, payload.size)
                    runCatching { onIncomingByConid[conid]?.invoke(data) }
                }
            }
        } catch (e: Exception) {
            if (!closed) {
                AppLog.log("Gateway UDP: canal encerrado (${e.javaClass.simpleName}: ${e.message})", AppLog.Level.ERROR)
            }
        } finally {
            closed = true
            onIncomingByConid.clear()
            runCatching { channelIn.close() }
            runCatching { channelOut.close() }
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
