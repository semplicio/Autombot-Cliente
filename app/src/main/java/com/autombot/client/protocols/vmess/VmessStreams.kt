package com.autombot.client.protocols.vmess

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Corpo (Data Section) do VMess, formato "Standard" (Opt S ligado, M/P/A desligados —
 * ver VmessCrypto.kt): cada pedaço e' [2 bytes tamanho][dados cifrados com
 * AES-128-GCM, tag de 16 bytes incluida].
 */
private const val MAX_CHUNK_PLAINTEXT = 16384 - 16

class VmessOutputStream(
    private val rawOut: OutputStream,
    private val key: ByteArray,
    private val iv: ByteArray
) : OutputStream() {
    private var chunkIndex = 0
    private var closed = false

    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (closed) throw IOException("Escrita VMess já foi encerrada")
        var offset = off
        var remaining = len
        while (remaining > 0) {
            val chunkLen = minOf(remaining, MAX_CHUNK_PLAINTEXT)
            writeChunk(b, offset, chunkLen)
            offset += chunkLen
            remaining -= chunkLen
        }
    }

    private fun writeChunk(b: ByteArray, off: Int, len: Int) {
        val plaintext = b.copyOfRange(off, off + len)
        val nonce = VmessCrypto.chunkNonce(iv, chunkIndex)
        chunkIndex++
        val ciphertext = VmessCrypto.aesGcmEncrypt(key, nonce, plaintext)
        rawOut.write((ciphertext.size shr 8) and 0xFF)
        rawOut.write(ciphertext.size and 0xFF)
        rawOut.write(ciphertext)
        rawOut.flush()
    }

    override fun flush() {
        rawOut.flush()
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            // No formato Standard com AES-128-GCM, o fim da direção de dados é um
            // pedaço com plaintext vazio. O AES-GCM produz somente sua tag de 16B,
            // portanto o comprimento transmitido é 16. Isso sinaliza EOF ao Xray sem
            // fechar o WebSocket e preserva a direção servidor -> cliente.
            writeChunk(ByteArray(0), 0, 0)
        } finally {
            rawOut.close()
        }
    }
}

class VmessInputStream(
    private val rawIn: InputStream,
    private val key: ByteArray,
    private val iv: ByteArray
) : InputStream() {
    private var chunkIndex = 0
    private var buffer: ByteArray = ByteArray(0)
    private var bufferPos = 0
    private var endOfStream = false

    override fun read(): Int {
        if (!fillBufferIfNeeded()) return -1
        return buffer[bufferPos++].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (!fillBufferIfNeeded()) return -1
        val available = buffer.size - bufferPos
        val toCopy = minOf(available, len)
        System.arraycopy(buffer, bufferPos, b, off, toCopy)
        bufferPos += toCopy
        return toCopy
    }

    private fun fillBufferIfNeeded(): Boolean {
        if (bufferPos < buffer.size) return true
        if (endOfStream) return false

        val lengthBytes = readExact(2) ?: run { endOfStream = true; return false }
        val chunkLen = ((lengthBytes[0].toInt() and 0xFF) shl 8) or (lengthBytes[1].toInt() and 0xFF)
        if (chunkLen == 0) {
            endOfStream = true
            return false
        }
        val ciphertext = readExact(chunkLen) ?: throw EOFException("Conexão VMess fechada no meio de um pedaço de dados")
        val nonce = VmessCrypto.chunkNonce(iv, chunkIndex)
        chunkIndex++
        buffer = try {
            VmessCrypto.aesGcmDecrypt(key, nonce, ciphertext)
        } catch (e: Exception) {
            throw IOException("Falha ao descriptografar pedaço de dados VMess (tag GCM inválida): ${e.message}", e)
        }
        bufferPos = 0
        return buffer.isNotEmpty()
    }

    private fun readExact(size: Int): ByteArray? {
        val buf = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val n = rawIn.read(buf, offset, size - offset)
            if (n == -1) return if (offset == 0) null else throw EOFException("Conexão fechada no meio de um campo de $size bytes")
            offset += n
        }
        return buf
    }

    override fun close() {
        rawIn.close()
    }
}
