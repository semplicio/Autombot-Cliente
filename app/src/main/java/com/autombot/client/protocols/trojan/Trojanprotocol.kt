package com.autombot.client.protocols.trojan

import java.security.MessageDigest

/**
 * Implementação do protocolo Trojan (ver especificação oficial, trojan-gfw.github.io/trojan/protocol).
 * Bem mais simples que VLESS/VMess: NÃO tem cifra própria — a segurança inteira vem
 * do TLS por baixo (ver TrojanTransport.kt). O papel deste arquivo é só montar o
 * cabeçalho de requisição inicial, mandado uma vez logo após o handshake TLS:
 *
 *   [56 bytes: SHA-224 da senha, em hexadecimal ASCII] [CRLF]
 *   [1 byte comando: 0x01=Connect, 0x03=UDP Associate]
 *   [1 byte tipo de endereço][endereço][2 bytes porta]
 *   [CRLF]
 *   ...dados brutos do payload a partir daqui (modo Connect) ou pacotes UDP
 *   framed (modo UDP Associate, ver [encodeUdpPacket])...
 */
object TrojanProtocol {
    const val CMD_CONNECT = 0x01
    const val CMD_UDP_ASSOCIATE = 0x03

    private val IPV4_REGEX = Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")

    /** SHA-224 da senha, em hexadecimal minúsculo — sempre 56 caracteres. */
    fun hashPassword(password: String): String {
        val digest = MessageDigest.getInstance("SHA-224").digest(password.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun buildRequestHeader(password: String, destHost: String, destPort: Int, command: Int = CMD_CONNECT): ByteArray {
        val hashHex = hashPassword(password)
        val addressBytes = encodeAddress(destHost)

        val header = ByteArray(hashHex.length + 2 + 1 + addressBytes.size + 2 + 2)
        var offset = 0
        val hashBytes = hashHex.toByteArray(Charsets.US_ASCII)
        System.arraycopy(hashBytes, 0, header, offset, hashBytes.size); offset += hashBytes.size
        header[offset++] = 0x0D; header[offset++] = 0x0A // CRLF
        header[offset++] = command.toByte()
        System.arraycopy(addressBytes, 0, header, offset, addressBytes.size); offset += addressBytes.size
        header[offset++] = ((destPort shr 8) and 0xFF).toByte()
        header[offset++] = (destPort and 0xFF).toByte()
        header[offset++] = 0x0D; header[offset++] = 0x0A // CRLF

        return header
    }

    fun encodeAddress(host: String): ByteArray {
        return if (IPV4_REGEX.matches(host)) {
            val parts = host.split(".").map { it.toInt() }
            byteArrayOf(0x01, parts[0].toByte(), parts[1].toByte(), parts[2].toByte(), parts[3].toByte())
        } else {
            val nameBytes = host.toByteArray(Charsets.US_ASCII)
            val result = ByteArray(2 + nameBytes.size)
            result[0] = 0x03
            result[1] = nameBytes.size.toByte()
            System.arraycopy(nameBytes, 0, result, 2, nameBytes.size)
            result
        }
    }

    /**
     * Framing de um pacote UDP dentro do modo UDP Associate (a mesma conexão TLS
     * carrega vários pacotes, um atrás do outro, cada um se autodescrevendo):
     * [endereço destino][porta destino][2 bytes tamanho][CRLF][payload].
     */
    fun encodeUdpPacket(destHost: String, destPort: Int, payload: ByteArray): ByteArray {
        val addressBytes = encodeAddress(destHost)
        val header = ByteArray(addressBytes.size + 2 + 2 + 2)
        var offset = 0
        System.arraycopy(addressBytes, 0, header, offset, addressBytes.size); offset += addressBytes.size
        header[offset++] = ((destPort shr 8) and 0xFF).toByte()
        header[offset++] = (destPort and 0xFF).toByte()
        header[offset++] = ((payload.size shr 8) and 0xFF).toByte()
        header[offset++] = (payload.size and 0xFF).toByte()
        header[offset++] = 0x0D; header[offset] = 0x0A // CRLF
        return header + payload
    }
}