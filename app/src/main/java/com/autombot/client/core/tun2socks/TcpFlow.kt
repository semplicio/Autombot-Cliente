package com.autombot.client.core.tun2socks

import com.autombot.client.util.AppLog
import kotlinx.coroutines.*
import java.net.Socket
import kotlin.random.Random

/**
 * Um "fluxo" = uma conexao TCP individual que o app do celular abriu (identificada
 * por origem+porta / destino+porta). Pra cada fluxo novo (pacote SYN), o motor abre
 * uma conexao real atraves do proxy SOCKS5 local (que tuneliza pelo SSH) e passa a
 * agir como se FOSSE o servidor remoto do ponto de vista do app — respondendo
 * SYN-ACK, repassando dados nos dois sentidos, controlando seq/ack.
 */
class TcpFlow(
    val srcAddr: ByteArray, // endereco do app no aparelho (dentro do TUN)
    val srcPort: Int,
    val dstAddr: ByteArray, // endereco real de destino que o app quer alcançar
    val dstPort: Int,
    private val clientInitialSeq: Long,
    private val onOutgoingPacket: (ByteArray) -> Unit,
    private val onClosed: () -> Unit
) {
    enum class State { CONNECTING, ESTABLISHED, CLOSING, CLOSED }

    @Volatile var state: State = State.CONNECTING
        private set

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Numero de sequencia que NOS usamos (agindo como "servidor") — comeca aleatorio.
    private var serverSeq: Long = Random.nextLong(0, 0xFFFFFFFFL)

    // Proximo byte que esperamos receber do app (ACK que mandamos pra ele)
    private var expectedClientSeq: Long = (clientInitialSeq + 1) and 0xFFFFFFFFL

    private var realSocket: Socket? = null

    private val label: String
        get() {
            val dest = dstAddr.joinToString(".") { (it.toInt() and 0xFF).toString() }
            return "$dest:$dstPort"
        }

    fun connectAndEstablish(socksHost: String, socksPort: Int) {
        scope.launch {
            try {
                val destHost = dstAddr.joinToString(".") { (it.toInt() and 0xFF).toString() }
                
                // Socks5Client.connect e bloqueante, roda no Dispatchers.IO
                val socket = Socks5Client.connect(socksHost, socksPort, destHost, dstPort)
                realSocket = socket
                state = State.ESTABLISHED
                
                // Manda SYN-ACK de volta pro app
                onOutgoingPacket(
                    PacketBuilder.buildTcpPacket(
                        srcAddr = dstAddr, dstAddr = srcAddr,
                        srcPort = dstPort, dstPort = srcPort,
                        seq = serverSeq, ack = expectedClientSeq,
                        flags = PacketBuilder.FLAG_SYN or PacketBuilder.FLAG_ACK,
                        payload = ByteArray(0),
                        includeMssOption = true
                    )
                )
                serverSeq = (serverSeq + 1) and 0xFFFFFFFFL

                startReader()
            } catch (e: Exception) {
                val msg = e.message ?: e.javaClass.simpleName
                if (msg.contains("Connection refused") || msg.contains("refused")) {
                    AppLog.log("VPN [$label]: o túnel SSH não está respondendo (ECONNREFUSED na porta $socksPort).", AppLog.Level.ERROR)
                }
                sendReset()
                close()
            }
        }
    }

    /** Dados que chegaram do app (via TUN) — repassa pro socket real (SOCKS5 -> SSH). */
    fun onIncomingData(payload: ByteArray, seq: Long) {
        if (state != State.ESTABLISHED) return
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    realSocket?.getOutputStream()?.write(payload)
                    realSocket?.getOutputStream()?.flush()
                }
                expectedClientSeq = (seq + payload.size) and 0xFFFFFFFFL
                // ACK simples confirmando o recebimento
                onOutgoingPacket(
                    PacketBuilder.buildTcpPacket(
                        srcAddr = dstAddr, dstAddr = srcAddr,
                        srcPort = dstPort, dstPort = srcPort,
                        seq = serverSeq, ack = expectedClientSeq,
                        flags = PacketBuilder.FLAG_ACK,
                        payload = ByteArray(0)
                    )
                )
            } catch (e: Exception) {
                close()
            }
        }
    }

    fun onClientFin(seq: Long) {
        expectedClientSeq = (seq + 1) and 0xFFFFFFFFL
        runCatching { realSocket?.shutdownOutput() }
        onOutgoingPacket(
            PacketBuilder.buildTcpPacket(
                srcAddr = dstAddr, dstAddr = srcAddr,
                srcPort = dstPort, dstPort = srcPort,
                seq = serverSeq, ack = expectedClientSeq,
                flags = PacketBuilder.FLAG_ACK,
                payload = ByteArray(0)
            )
        )
    }

    fun onClientReset() {
        close()
    }

    private fun startReader() {
        scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(16384)
            try {
                val input = realSocket?.getInputStream() ?: return@launch
                while (state == State.ESTABLISHED && isActive) {
                    val n = input.read(buffer)
                    if (n == -1) break
                    
                    val chunk = buffer.copyOf(n)
                    onOutgoingPacket(
                        PacketBuilder.buildTcpPacket(
                            srcAddr = dstAddr, dstAddr = srcAddr,
                            srcPort = dstPort, dstPort = srcPort,
                            seq = serverSeq, ack = expectedClientSeq,
                            flags = PacketBuilder.FLAG_ACK or PacketBuilder.FLAG_PSH,
                            payload = chunk
                        )
                    )
                    serverSeq = (serverSeq + n) and 0xFFFFFFFFL
                }
            } catch (e: Exception) {
                // normal
            }
            
            if (state == State.ESTABLISHED) {
                state = State.CLOSING
                onOutgoingPacket(
                    PacketBuilder.buildTcpPacket(
                        srcAddr = dstAddr, dstAddr = srcAddr,
                        srcPort = dstPort, dstPort = srcPort,
                        seq = serverSeq, ack = expectedClientSeq,
                        flags = PacketBuilder.FLAG_FIN or PacketBuilder.FLAG_ACK,
                        payload = ByteArray(0)
                    )
                )
                serverSeq = (serverSeq + 1) and 0xFFFFFFFFL
            }
            close()
        }
    }

    private fun sendReset() {
        onOutgoingPacket(
            PacketBuilder.buildTcpPacket(
                srcAddr = dstAddr, dstAddr = srcAddr,
                srcPort = dstPort, dstPort = srcPort,
                seq = serverSeq, ack = expectedClientSeq,
                flags = PacketBuilder.FLAG_RST or PacketBuilder.FLAG_ACK,
                payload = ByteArray(0)
            )
        )
    }

    fun close() {
        if (state == State.CLOSED) return
        state = State.CLOSED
        scope.cancel()
        runCatching { realSocket?.close() }
        onClosed()
    }
}