package com.autombot.client.protocols.shadowsocks

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Corpo do Shadowsocks AEAD: cada direção (envio/recebimento) tem seu PRÓPRIO salt
 * aleatório (mandado uma vez, em texto puro, no início dessa direção) e sua própria
 * subchave derivada dele — não é o mesmo salt/subchave nos dois sentidos. Depois do
 * salt, cada "pedaço" e' [2 bytes tamanho cifrado+tag][dados cifrados+tag], tamanho
 * maximo 0x3FFF (16383) por pedaço, conforme a especificação oficial.
 */
private const val MAX_CHUNK_PLAINTEXT = 0x3FFF

class ShadowsocksOutputStream(
    private val rawOut: OutputStream,
    private val masterKey: ByteArray,
    private val spec: ShadowsocksCrypto.CipherSpec
) : OutputStream() {
    private var subkey: ByteArray? = null
    private var counter = 0L

    private fun ensureInit() {
        if (subkey != null) return
        val salt = ShadowsocksCrypto.randomSalt(spec.saltSize)
        rawOut.write(salt)
        rawOut.flush()
        subkey = ShadowsocksCrypto.deriveSessionSubkey(masterKey, salt, spec.keySize)
    }

    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, len: Int) {
        ensureInit()
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
        val key = subkey!!
        val lengthPlain = byteArrayOf(((len shr 8) and 0xFF).toByte(), (len and 0xFF).toByte())
        val nonce1 = ShadowsocksCrypto.nonceFromCounter(counter, spec.nonceSize)
        counter++
        val encLength = ShadowsocksCrypto.aeadEncrypt(spec, key, nonce1, lengthPlain)

        val payloadPlain = b.copyOfRange(off, off + len)
        val nonce2 = ShadowsocksCrypto.nonceFromCounter(counter, spec.nonceSize)
        counter++
        val encPayload = ShadowsocksCrypto.aeadEncrypt(spec, key, nonce2, payloadPlain)

        rawOut.write(encLength)
        rawOut.write(encPayload)
        rawOut.flush()
    }
}

class ShadowsocksInputStream(
    private val rawIn: InputStream,
    private val masterKey: ByteArray,
    private val spec: ShadowsocksCrypto.CipherSpec
) : InputStream() {
    private var subkey: ByteArray? = null
    private var counter = 0L
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

    private fun ensureInit(): Boolean {
        if (subkey != null) return true
        val salt = readExact(spec.saltSize) ?: return false
        subkey = ShadowsocksCrypto.deriveSessionSubkey(masterKey, salt, spec.keySize)
        return true
    }

    private fun fillBufferIfNeeded(): Boolean {
        if (bufferPos < buffer.size) return true
        if (endOfStream) return false
        if (!ensureInit()) { endOfStream = true; return false }

        val key = subkey!!
        val nonce1 = ShadowsocksCrypto.nonceFromCounter(counter, spec.nonceSize)
        counter++
        val encLength = readExact(2 + 16) ?: run { endOfStream = true; return false }
        val lengthPlain = try {
            ShadowsocksCrypto.aeadDecrypt(spec, key, nonce1, encLength)
        } catch (e: Exception) {
            throw IOException("Falha ao descriptografar tamanho do pedaço (tag inválida — senha/método errados?): ${e.message}", e)
        }
        val chunkLen = ((lengthPlain[0].toInt() and 0xFF) shl 8) or (lengthPlain[1].toInt() and 0xFF)

        val nonce2 = ShadowsocksCrypto.nonceFromCounter(counter, spec.nonceSize)
        counter++
        val encPayload = readExact(chunkLen + 16) ?: throw EOFException("Conexão fechada no meio de um pedaço de dados")
        buffer = try {
            ShadowsocksCrypto.aeadDecrypt(spec, key, nonce2, encPayload)
        } catch (e: Exception) {
            throw IOException("Falha ao descriptografar dados (tag inválida): ${e.message}", e)
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
}