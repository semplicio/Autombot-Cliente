package com.autombot.client.protocols.shadowsocks

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.IvParameterSpec

/**
 * Criptografia do Shadowsocks AEAD (SIP004/SIP007 — confirmado contra a especificação
 * oficial, shadowsocks.org/doc/aead.html, buscada antes de implementar). Bem menos
 * arriscado que o VMess (protocolo mais simples, sem cadeia de HMAC aninhado), mas
 * ainda envolve varias etapas de derivacao de chave — revisar aqui primeiro se a
 * conexao falhar.
 */
object ShadowsocksCrypto {

    data class CipherSpec(val keySize: Int, val saltSize: Int, val nonceSize: Int, val jcaName: String)

    // INCERTO: nome exato do algoritmo ChaCha20-Poly1305 na JCA do Android pode variar
    // por versao/fornecedor do provider — "ChaCha20-Poly1305" e o nome padrao (Java 11+/
    // Android API 28+, ou via BouncyCastle se registrado). Se der erro
    // "NoSuchAlgorithmException", esse e o primeiro lugar a revisar.
    private val SPECS = mapOf(
        "chacha20-ietf-poly1305" to CipherSpec(keySize = 32, saltSize = 32, nonceSize = 12, jcaName = "ChaCha20-Poly1305"),
        "aes-256-gcm" to CipherSpec(keySize = 32, saltSize = 32, nonceSize = 12, jcaName = "AES/GCM/NoPadding"),
        "aes-128-gcm" to CipherSpec(keySize = 16, saltSize = 16, nonceSize = 12, jcaName = "AES/GCM/NoPadding")
    )

    fun specFor(method: String): CipherSpec =
        SPECS[method] ?: throw IllegalArgumentException("Método Shadowsocks não suportado: $method")

    /** EVP_BytesToKey (OpenSSL legado, MD5) — deriva a chave mestra a partir da senha em texto. */
    fun deriveMasterKey(password: String, keySize: Int): ByteArray {
        val passwordBytes = password.toByteArray(Charsets.UTF_8)
        val result = mutableListOf<Byte>()
        var prev = ByteArray(0)
        while (result.size < keySize) {
            val md5 = MessageDigest.getInstance("MD5")
            md5.update(prev)
            md5.update(passwordBytes)
            prev = md5.digest()
            result.addAll(prev.toList())
        }
        return result.take(keySize).toByteArray()
    }

    /** HKDF-SHA1 completo (RFC 5869) — deriva a subchave da sessão a partir da chave mestra + salt. */
    fun deriveSessionSubkey(masterKey: ByteArray, salt: ByteArray, keySize: Int): ByteArray {
        val info = "ss-subkey".toByteArray(Charsets.UTF_8)
        val prk = hmacSha1(salt, masterKey) // HKDF-Extract

        // HKDF-Expand
        val hashLen = 20 // tamanho da saida do SHA-1
        val n = (keySize + hashLen - 1) / hashLen
        val okm = mutableListOf<Byte>()
        var t = ByteArray(0)
        for (i in 1..n) {
            val mac = Mac.getInstance("HmacSHA1")
            mac.init(SecretKeySpec(prk, "HmacSHA1"))
            mac.update(t)
            mac.update(info)
            mac.update(i.toByte())
            t = mac.doFinal()
            okm.addAll(t.toList())
        }
        return okm.take(keySize).toByteArray()
    }

    private fun hmacSha1(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key, "HmacSHA1"))
        return mac.doFinal(data)
    }

    fun randomSalt(size: Int): ByteArray = ByteArray(size).also { SecureRandom().nextBytes(it) }

    /**
     * Nonce de um "pedaço": inteiro sem sinal, little-endian, começando em 0,
     * incrementado em 1 a cada operação de cifra/decifra (2 por pedaço: comprimento
     * + payload) — confirmado contra a especificação oficial.
     */
    fun nonceFromCounter(counter: Long, nonceSize: Int): ByteArray {
        val nonce = ByteArray(nonceSize)
        var c = counter
        for (i in 0 until nonceSize) {
            nonce[i] = (c and 0xFF).toByte()
            c = c ushr 8
        }
        return nonce
    }

    fun aeadEncrypt(spec: CipherSpec, key: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(spec.jcaName)
        val algo = if (spec.jcaName.startsWith("AES")) "AES" else "ChaCha20"
        val params = if (spec.jcaName.startsWith("AES")) GCMParameterSpec(128, nonce) else IvParameterSpec(nonce)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, algo), params)
        return cipher.doFinal(plaintext)
    }

    fun aeadDecrypt(spec: CipherSpec, key: ByteArray, nonce: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(spec.jcaName)
        val algo = if (spec.jcaName.startsWith("AES")) "AES" else "ChaCha20"
        val params = if (spec.jcaName.startsWith("AES")) GCMParameterSpec(128, nonce) else IvParameterSpec(nonce)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, algo), params)
        return cipher.doFinal(ciphertext)
    }

    private val IPV4_REGEX = Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")

    /** Cabeçalho de endereço de destino: [1B tipo][endereço][2B porta] — mesmo formato SOCKS5/VLESS. */
    fun encodeAddressHeader(destHost: String, destPort: Int): ByteArray {
        val addressBytes = if (IPV4_REGEX.matches(destHost)) {
            val parts = destHost.split(".").map { it.toInt() }
            byteArrayOf(0x01, parts[0].toByte(), parts[1].toByte(), parts[2].toByte(), parts[3].toByte())
        } else {
            val nameBytes = destHost.toByteArray(Charsets.US_ASCII)
            val result = ByteArray(2 + nameBytes.size)
            result[0] = 0x03
            result[1] = nameBytes.size.toByte()
            System.arraycopy(nameBytes, 0, result, 2, nameBytes.size)
            result
        }
        val header = ByteArray(addressBytes.size + 2)
        System.arraycopy(addressBytes, 0, header, 0, addressBytes.size)
        header[addressBytes.size] = ((destPort shr 8) and 0xFF).toByte()
        header[addressBytes.size + 1] = (destPort and 0xFF).toByte()
        return header
    }
}