package com.autombot.client.core.tun2socks

import com.autombot.client.util.AppLog
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Uma sessao "UDP" aberta atraves de algum protocolo (ex: SSH via um jeitinho TCP,
 * ou VLESS/VMess/Shadowsocks via suporte nativo do protocolo). O motor manda
 * datagramas pra [send] conforme chegam do app, e a sessao chama [onIncoming]
 * sempre que tiver dado de volta pra entregar ao app.
 */
interface UdpFlowSession {
    fun send(payload: ByteArray)
    fun close()
}

/**
 * Implementado por cada protocolo que sabe tunelar UDP de algum jeito. Retorna null
 * se esse destino especifico nao e suportado (ex: SSH so consegue fazer o jeitinho
 * de porta 443 — qualquer outra porta UDP, ele nao tem como ajudar).
 */
typealias UdpFlowOpener = (dstHost: String, dstPort: Int, onIncoming: (ByteArray) -> Unit, onClosed: () -> Unit) -> UdpFlowSession?

/**
 * Motor de roteamento de pacotes (equivalente simplificado a um "tun2socks")
 */
class Tun2SocksEngine(
    private val tunFd: android.os.ParcelFileDescriptor,
    private val socksHost: String,
    @Volatile var socksPort: Int,
    private val protectDatagramSocket: (DatagramSocket) -> Unit = {},
    // CORRECAO: antes, QUALQUER UDP que nao fosse DNS (porta 53) era descartado em
    // silencio — sem log, sem erro, o pacote so desaparecia. Isso apagava a maior
    // parte do trafego que apps modernos usam (QUIC/HTTP3, chamadas de voz/video,
    // notificacoes push, jogos), dando a impressao de "app travado" sem pista
    // nenhuma do motivo. Agora, se algum protocolo souber tunelar aquele destino
    // especifico (via esse opener plugavel), ele tenta; senao, pelo menos loga UMA
    // vez por destino (nao inunda o log) avisando que aquele UDP foi descartado.
    @Volatile var onOpenUdpFlow: UdpFlowOpener? = null
) {
    private val input = FileInputStream(tunFd.fileDescriptor)
    private val output = FileOutputStream(tunFd.fileDescriptor)
    private val writeLock = Any()

    private val flows = ConcurrentHashMap<String, TcpFlow>()
    private val udpFlows = ConcurrentHashMap<String, UdpFlowSession>()
    private val udpUnsupportedLogged = ConcurrentHashMap.newKeySet<String>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        AppLog.log("Motor de VPN (tun2socks) iniciado", AppLog.Level.INFO)
        
        scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(32767)
            try {
                while (running && isActive) {
                    val length = input.read(buffer)
                    if (length <= 0) continue
                    handlePacket(buffer.copyOf(length), length)
                }
            } catch (e: IOException) {
                // fechado
            }
        }
    }

    fun stop() {
        running = false
        scope.cancel()
        flows.values.forEach { it.close() }
        flows.clear()
        udpFlows.values.forEach { runCatching { it.close() } }
        udpFlows.clear()
        udpUnsupportedLogged.clear()
        runCatching { input.close() }
        runCatching { output.close() }
        AppLog.log("Motor de VPN (tun2socks) parado", AppLog.Level.INFO)
    }

    private fun handlePacket(buffer: ByteArray, length: Int) {
        if (length < 20) return
        val versionAndIhl = buffer[0].toInt() and 0xFF
        val version = versionAndIhl shr 4
        if (version != 4) return
        val ihl = (versionAndIhl and 0x0F) * 4
        val protocol = buffer[9].toInt() and 0xFF

        val srcAddr = buffer.copyOfRange(12, 16)
        val dstAddr = buffer.copyOfRange(16, 20)

        when (protocol) {
            6 -> handleTcpPacket(buffer, length, ihl, srcAddr, dstAddr)
            17 -> handleUdpPacket(buffer, length, ihl, srcAddr, dstAddr)
        }
    }

    private fun handleTcpPacket(buffer: ByteArray, length: Int, ihl: Int, srcAddr: ByteArray, dstAddr: ByteArray) {
        if (ihl + 20 > length) return
        val tcpOffset = ihl
        val srcPort = ((buffer[tcpOffset].toInt() and 0xFF) shl 8) or (buffer[tcpOffset + 1].toInt() and 0xFF)
        val dstPort = ((buffer[tcpOffset + 2].toInt() and 0xFF) shl 8) or (buffer[tcpOffset + 3].toInt() and 0xFF)
        val seq = readUInt32(buffer, tcpOffset + 4)
        val dataOffsetWords = (buffer[tcpOffset + 12].toInt() and 0xFF) shr 4
        val tcpHeaderLen = dataOffsetWords * 4
        val flags = buffer[tcpOffset + 13].toInt() and 0xFF

        val payloadOffset = tcpOffset + tcpHeaderLen
        val payloadLength = (length - payloadOffset).coerceAtLeast(0)

        val flowKey = "${srcAddr.joinToString(".") { (it.toInt() and 0xFF).toString() }}:$srcPort>${dstAddr.joinToString(".") { (it.toInt() and 0xFF).toString() }}:$dstPort"

        val isSyn = flags and PacketBuilder.FLAG_SYN != 0
        val isAck = flags and PacketBuilder.FLAG_ACK != 0
        val isFin = flags and PacketBuilder.FLAG_FIN != 0
        val isRst = flags and PacketBuilder.FLAG_RST != 0

        if (isSyn && !isAck) {
            if (flows.containsKey(flowKey)) return
            val flow = TcpFlow(
                srcAddr = srcAddr, srcPort = srcPort,
                dstAddr = dstAddr, dstPort = dstPort,
                clientInitialSeq = seq,
                onOutgoingPacket = { packet -> writeToTun(packet) },
                onClosed = { flows.remove(flowKey) }
            )
            flows[flowKey] = flow
            flow.connectAndEstablish(socksHost, socksPort)
            return
        }

        val flow = flows[flowKey] ?: return

        when {
            isRst -> flow.onClientReset()
            isFin -> flow.onClientFin(seq)
            payloadLength > 0 -> {
                val payload = buffer.copyOfRange(payloadOffset, payloadOffset + payloadLength)
                flow.onIncomingData(payload, seq)
            }
        }
    }

    private fun handleUdpPacket(buffer: ByteArray, length: Int, ihl: Int, srcAddr: ByteArray, dstAddr: ByteArray) {
        val udpOffset = ihl
        if (udpOffset + 8 > length) return
        val srcPort = ((buffer[udpOffset].toInt() and 0xFF) shl 8) or (buffer[udpOffset + 1].toInt() and 0xFF)
        val dstPort = ((buffer[udpOffset + 2].toInt() and 0xFF) shl 8) or (buffer[udpOffset + 3].toInt() and 0xFF)

        val payloadOffset = udpOffset + 8
        val payloadLength = length - payloadOffset
        if (payloadLength <= 0) return
        val payload = buffer.copyOfRange(payloadOffset, payloadOffset + payloadLength)

        if (dstPort == 53) {
            handleDnsPacket(payload, srcAddr, srcPort, dstAddr, dstPort)
            return
        }

        handleNonDnsUdpPacket(payload, srcAddr, srcPort, dstAddr, dstPort)
    }

    private fun handleDnsPacket(dnsQuery: ByteArray, srcAddr: ByteArray, srcPort: Int, dstAddr: ByteArray, dstPort: Int) {
        scope.launch(Dispatchers.IO) {
            try {
                val socket = DatagramSocket()
                protectDatagramSocket(socket)
                socket.soTimeout = 5000
                val dstInet = InetAddress.getByAddress(dstAddr)
                socket.send(DatagramPacket(dnsQuery, dnsQuery.size, dstInet, dstPort))

                val respBuffer = ByteArray(1500)
                val respPacket = DatagramPacket(respBuffer, respBuffer.size)
                socket.receive(respPacket)
                socket.close()

                val respData = respBuffer.copyOf(respPacket.length)
                val packet = PacketBuilder.buildUdpPacket(
                    srcAddr = dstAddr, dstAddr = srcAddr,
                    srcPort = dstPort, dstPort = srcPort,
                    payload = respData
                )
                writeToTun(packet)
            } catch (e: Exception) {
                // DNS error
            }
        }
    }

    private fun handleNonDnsUdpPacket(payload: ByteArray, srcAddr: ByteArray, srcPort: Int, dstAddr: ByteArray, dstPort: Int) {
        val flowKey = "${srcAddr.joinToString(".") { (it.toInt() and 0xFF).toString() }}:$srcPort>${dstAddr.joinToString(".") { (it.toInt() and 0xFF).toString() }}:$dstPort"
        val existing = udpFlows[flowKey]
        if (existing != null) {
            existing.send(payload)
            return
        }

        val opener = onOpenUdpFlow
        val dstHost = dstAddr.joinToString(".") { (it.toInt() and 0xFF).toString() }
        if (opener == null) {
            logUdpUnsupportedOnce(flowKey, dstHost, dstPort)
            return
        }

        val session = opener(
            dstHost, dstPort,
            { respData ->
                val packet = PacketBuilder.buildUdpPacket(
                    srcAddr = dstAddr, dstAddr = srcAddr,
                    srcPort = dstPort, dstPort = srcPort,
                    payload = respData
                )
                writeToTun(packet)
            },
            { udpFlows.remove(flowKey) }
        )
        if (session == null) {
            logUdpUnsupportedOnce(flowKey, dstHost, dstPort)
            return
        }
        udpFlows[flowKey] = session
        session.send(payload)
    }

    private fun logUdpUnsupportedOnce(flowKey: String, dstHost: String, dstPort: Int) {
        // So loga a PRIMEIRA vez pra cada destino diferente — um app pode tentar a
        // mesma coisa varias vezes por segundo, e isso inundaria o log sem trazer
        // informacao nova.
        if (udpUnsupportedLogged.add(flowKey)) {
            AppLog.log(
                "UDP para $dstHost:$dstPort descartado — esse protocolo não sabe tunelar esse " +
                    "destino (a maioria do tráfego de apps usa UDP, então isso pode causar lentidão " +
                    "ou apps não funcionando).",
                AppLog.Level.ERROR
            )
        }
    }

    private fun writeToTun(packet: ByteArray) {
        synchronized(writeLock) {
            runCatching { output.write(packet) }
        }
    }

    private fun readUInt32(buffer: ByteArray, offset: Int): Long {
        return ((buffer[offset].toLong() and 0xFF) shl 24) or
            ((buffer[offset + 1].toLong() and 0xFF) shl 16) or
            ((buffer[offset + 2].toLong() and 0xFF) shl 8) or
            (buffer[offset + 3].toLong() and 0xFF)
    }
}