package com.autombot.client.protocols.vless

import java.io.FilterInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Implementação mínima do protocolo VLESS (ver documentação oficial do Xray-core).
 * Formato da requisição:
 *   [1 byte versão=0x00] [16 bytes UUID] [1 byte tamanho de addons=0x00]
 *   [1 byte comando: 0x01=TCP] [2 bytes porta] [1 byte tipo de endereço]
 *   [endereço: 4B IPv4 / 1B+N domínio / 16B IPv6]
 *   ...dados brutos do payload a partir daqui, sem delimitador...
 *
 * Formato da resposta (do servidor):
 *   [1 byte versão] [1 byte tamanho de addons] [addons, ignorados]
 *   ...dados brutos a partir daqui...
 *
 * ATENCAO: implementado a partir da especificação, não testado contra um servidor
 * VLESS real ainda — se o servidor recusar a conexão ou os dados vierem corrompidos,
 * é o primeiro lugar a revisar.
 */
object VlessProtocol {
    private val IPV4_REGEX = Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")

    /** [command] 0x01 = TCP, 0x02 = UDP (ver documentação oficial do Xray-core). */
    fun buildRequestHeader(uuid: String, destHost: String, destPort: Int, command: Int = 0x01): ByteArray {
        val uuidBytes = uuidToBytes(uuid)
        val addressBytes = encodeAddress(destHost)

        val header = ByteArray(1 + 16 + 1 + 1 + 2 + addressBytes.size)
        var offset = 0
        header[offset++] = 0x00 // versao
        System.arraycopy(uuidBytes, 0, header, offset, 16); offset += 16
        header[offset++] = 0x00 // tamanho de addons (nenhum)
        header[offset++] = command.toByte()
        header[offset++] = ((destPort shr 8) and 0xFF).toByte()
        header[offset++] = (destPort and 0xFF).toByte()
        System.arraycopy(addressBytes, 0, header, offset, addressBytes.size)

        return header
    }

    private fun encodeAddress(host: String): ByteArray {
        return if (IPV4_REGEX.matches(host)) {
            val parts = host.split(".").map { it.toInt() }
            byteArrayOf(0x01, parts[0].toByte(), parts[1].toByte(), parts[2].toByte(), parts[3].toByte())
        } else {
            // Tipo dominio (0x02): 1 byte de tamanho + bytes ASCII do nome
            val nameBytes = host.toByteArray(Charsets.US_ASCII)
            val result = ByteArray(2 + nameBytes.size)
            result[0] = 0x02
            result[1] = nameBytes.size.toByte()
            System.arraycopy(nameBytes, 0, result, 2, nameBytes.size)
            result
        }
    }

    private fun uuidToBytes(uuidStr: String): ByteArray {
        val uuid = UUID.fromString(uuidStr)
        val buffer = ByteBuffer.allocate(16)
        buffer.putLong(uuid.mostSignificantBits)
        buffer.putLong(uuid.leastSignificantBits)
        return buffer.array()
    }

    /**
     * No modo UDP, cada datagrama vai [2 bytes tamanho, big-endian][dados brutos] —
     * mesmo esquema usado pelo VMess pra UDP (ver VmessStreams.kt), necessário porque
     * o servidor precisa saber onde um "pacote" termina e o próximo começa dentro do
     * mesmo fluxo de bytes (WebSocket preserva limites de mensagem no nosso lado, mas
     * o servidor pode estar recompondo tudo num fluxo bruto por dentro).
     */
    fun encodeUdpPacket(payload: ByteArray): ByteArray {
        val result = ByteArray(2 + payload.size)
        result[0] = ((payload.size shr 8) and 0xFF).toByte()
        result[1] = (payload.size and 0xFF).toByte()
        System.arraycopy(payload, 0, result, 2, payload.size)
        return result
    }
}

/**
 * Envolve o InputStream cru da conexão (WebSocket/TLS) e descarta o cabeçalho de
 * resposta VLESS (versão + addons) na primeira leitura — depois disso, passa os bytes
 * direto, sem mais processamento. Assim o Socks5Server pode tratar essa stream como
 * um socket comum, sem saber que existe um protocolo VLESS por baixo.
 */
class VlessResponseInputStream(inner: InputStream) : FilterInputStream(inner) {
    private var headerStripped = false

    @Synchronized
    private fun stripHeaderIfNeeded() {
        if (headerStripped) return
        headerStripped = true
        `in`.read() // versao — nao validamos ativamente, so consome o byte
        val addonsLength = `in`.read()
        if (addonsLength > 0) {
            var remaining = addonsLength
            val buf = ByteArray(addonsLength)
            while (remaining > 0) {
                val n = `in`.read(buf, addonsLength - remaining, remaining)
                if (n == -1) break
                remaining -= n
            }
        }
    }

    override fun read(): Int {
        stripHeaderIfNeeded()
        return super.read()
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        stripHeaderIfNeeded()
        return super.read(b, off, len)
    }
}