package com.autombot.client.core.tun2socks

/**
 * Monta pacotes IPv4+TCP crus (20 bytes de cabecalho IP + 20 bytes de cabecalho TCP,
 * sem opcoes) — usado pra gerar os pacotes que "respondemos" de volta pro app,
 * simulando ser o servidor remoto.
 */
object PacketBuilder {
    const val FLAG_FIN = 0x01
    const val FLAG_SYN = 0x02
    const val FLAG_RST = 0x04
    const val FLAG_PSH = 0x08
    const val FLAG_ACK = 0x10

    fun buildTcpPacket(
        srcAddr: ByteArray,
        dstAddr: ByteArray,
        srcPort: Int,
        dstPort: Int,
        seq: Long,
        ack: Long,
        flags: Int,
        payload: ByteArray,
        window: Int = 65535,
        includeMssOption: Boolean = false
    ): ByteArray {
        // CORRECAO: sem a opcao MSS no SYN-ACK, o Android pode assumir o tamanho
        // minimo padrao (536 bytes) pra cada pacote, em vez do tamanho real que cabe
        // na interface TUN (MTU 1500 - 40 bytes de cabecalho = 1460). Isso nao
        // impede a conexao de abrir, mas pode deixar a navegacao real inviavel —
        // um dos candidatos a explicar "conecta mas nao carrega nada".
        val optionsLen = if (includeMssOption) 4 else 0
        val headerLen = 20 + optionsLen
        val tcpHeader = ByteArray(headerLen)
        tcpHeader[0] = (srcPort shr 8).toByte(); tcpHeader[1] = srcPort.toByte()
        tcpHeader[2] = (dstPort shr 8).toByte(); tcpHeader[3] = dstPort.toByte()
        writeUInt32(tcpHeader, 4, seq)
        writeUInt32(tcpHeader, 8, ack)
        tcpHeader[12] = ((headerLen / 4) shl 4).toByte() // data offset em palavras de 4 bytes
        tcpHeader[13] = flags.toByte()
        tcpHeader[14] = (window shr 8).toByte(); tcpHeader[15] = window.toByte()
        // [16],[17] = checksum, calculado depois
        // [18],[19] = urgent pointer, sempre 0
        if (includeMssOption) {
            val mss = 1460 // MTU 1500 - 20 (IP) - 20 (TCP)
            tcpHeader[20] = 0x02 // kind: MSS
            tcpHeader[21] = 0x04 // tamanho da opcao (4 bytes)
            tcpHeader[22] = (mss shr 8).toByte()
            tcpHeader[23] = mss.toByte()
        }

        val tcpSegment = ByteArray(headerLen + payload.size)
        System.arraycopy(tcpHeader, 0, tcpSegment, 0, headerLen)
        System.arraycopy(payload, 0, tcpSegment, headerLen, payload.size)

        val checksum = Checksums.computeTcp(srcAddr, dstAddr, tcpSegment, tcpSegment.size)
        tcpSegment[16] = (checksum shr 8).toByte()
        tcpSegment[17] = checksum.toByte()

        return buildIpv4Packet(srcAddr, dstAddr, protocol = 6, payload = tcpSegment)
    }

    fun buildUdpPacket(srcAddr: ByteArray, dstAddr: ByteArray, srcPort: Int, dstPort: Int, payload: ByteArray): ByteArray {
        val udpLength = 8 + payload.size
        val udpSegment = ByteArray(udpLength)
        udpSegment[0] = (srcPort shr 8).toByte(); udpSegment[1] = srcPort.toByte()
        udpSegment[2] = (dstPort shr 8).toByte(); udpSegment[3] = dstPort.toByte()
        udpSegment[4] = (udpLength shr 8).toByte(); udpSegment[5] = udpLength.toByte()
        // [6],[7] = checksum, calculado depois
        System.arraycopy(payload, 0, udpSegment, 8, payload.size)

        val checksum = Checksums.computeL4(srcAddr, dstAddr, protocol = 17, udpSegment, udpLength)
        udpSegment[6] = (checksum shr 8).toByte()
        udpSegment[7] = checksum.toByte()

        return buildIpv4Packet(srcAddr, dstAddr, protocol = 17, payload = udpSegment)
    }

    private fun buildIpv4Packet(srcAddr: ByteArray, dstAddr: ByteArray, protocol: Int, payload: ByteArray): ByteArray {
        val totalLength = 20 + payload.size
        val packet = ByteArray(totalLength)
        packet[0] = (4 shl 4 or 5).toByte() // versao 4, IHL 5 (20 bytes, sem opcoes)
        packet[1] = 0 // DSCP/ECN
        packet[2] = (totalLength shr 8).toByte(); packet[3] = totalLength.toByte()
        packet[4] = 0; packet[5] = 0 // identification
        packet[6] = 0x40.toByte(); packet[7] = 0 // flags: don't fragment
        packet[8] = 64 // TTL
        packet[9] = protocol.toByte()
        // [10],[11] = checksum do cabecalho IP, calculado depois
        System.arraycopy(srcAddr, 0, packet, 12, 4)
        System.arraycopy(dstAddr, 0, packet, 16, 4)

        val ipChecksum = Checksums.compute(packet, 0, 20)
        packet[10] = (ipChecksum shr 8).toByte()
        packet[11] = ipChecksum.toByte()

        System.arraycopy(payload, 0, packet, 20, payload.size)
        return packet
    }

    private fun writeUInt32(buffer: ByteArray, offset: Int, value: Long) {
        buffer[offset] = (value shr 24).toByte()
        buffer[offset + 1] = (value shr 16).toByte()
        buffer[offset + 2] = (value shr 8).toByte()
        buffer[offset + 3] = value.toByte()
    }
}