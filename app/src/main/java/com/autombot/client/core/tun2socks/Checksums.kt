package com.autombot.client.core.tun2socks

/**
 * Checksum padrao usado em IPv4 e TCP (complemento de um de 16 bits, RFC 1071).
 * Usado tanto pro cabecalho IP quanto pro pseudo-cabecalho+segmento TCP.
 */
object Checksums {
    fun compute(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            val word = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        if (i < end) {
            sum += (data[i].toInt() and 0xFF) shl 8
        }
        while (sum shr 16 != 0L) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return (sum.inv() and 0xFFFF).toInt()
    }

    /** Checksum TCP inclui um pseudo-cabecalho (IPs origem/destino + protocolo + tamanho). */
    fun computeTcp(srcAddr: ByteArray, dstAddr: ByteArray, tcpSegment: ByteArray, tcpLength: Int): Int =
        computeL4(srcAddr, dstAddr, protocol = 6, tcpSegment, tcpLength)

    /** Mesma ideia do computeTcp, mas genérico — usado também pelo checksum UDP. */
    fun computeL4(srcAddr: ByteArray, dstAddr: ByteArray, protocol: Int, segment: ByteArray, length: Int): Int {
        val pseudo = ByteArray(12 + length)
        System.arraycopy(srcAddr, 0, pseudo, 0, 4)
        System.arraycopy(dstAddr, 0, pseudo, 4, 4)
        pseudo[8] = 0
        pseudo[9] = protocol.toByte()
        pseudo[10] = ((length shr 8) and 0xFF).toByte()
        pseudo[11] = (length and 0xFF).toByte()
        System.arraycopy(segment, 0, pseudo, 12, length)
        return compute(pseudo, 0, pseudo.size)
    }
}