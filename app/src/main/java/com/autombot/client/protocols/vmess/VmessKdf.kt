package com.autombot.client.protocols.vmess

import java.security.MessageDigest

/**
 * KDF (Key Derivation Function) usada pelo VMess AEAD pra derivar todas as chaves de
 * criptografia a partir do UUID do usuario.
 *
 * CORRIGIDO (ver SPEC.md secao 46): a primeira versao encadeava so a CHAVE de um
 * HMAC-SHA256 fixo a cada segmento do caminho — isso NAO e o que a especificacao
 * pede. O correto (confirmado contra a documentacao oficial do V2Fly e contra o
 * codigo-fonte real do v2ray-core, proxy/vmess/aead) e encadear a propria FUNCAO DE
 * HASH: cada segmento do caminho cria um HMAC novo que usa o HMAC anterior COMO
 * FUNCAO DE HASH de base (nao so como uma chave). Isso exige implementar o HMAC na
 * mao (RFC 2104), porque a API padrao do Java (javax.crypto.Mac) so aceita um
 * algoritmo fixo tipo "HmacSHA256", nao aceita "use este outro HMAC como se fosse a
 * funcao de hash".
 *
 * Formula oficial:
 *   KDF(key, path...):
 *       hmac_creator = HMAC(SHA256, "VMess AEAD KDF")
 *       for each p in path:
 *           hmac_creator = HMAC(hmac_creator, p)   // usa o HMAC anterior como hash de base
 *       return hmac_creator(key)                    // key = mensagem do ultimo nivel
 */
object VmessKdf {
    private val ROOT_LABEL = "VMess AEAD KDF".toByteArray(Charsets.UTF_8)

    // Block size fixo em 64 bytes em TODOS os niveis de aninhamento — mesmo quando a
    // "funcao de hash" de um nivel e outro HMAC, ela herda o block size do SHA-256 de
    // base (e assim que a implementacao em Go funciona: hash.Hash criado por
    // hmac.New() devolve o BlockSize() do hash de base, nao redefine um novo).
    private const val BLOCK_SIZE = 64

    private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    /** HMAC generico (RFC 2104), aceitando qualquer funcao de hash — inclusive outro HMAC aninhado. */
    private fun genericHmac(hashFn: (ByteArray) -> ByteArray, key: ByteArray, message: ByteArray): ByteArray {
        var k = key
        if (k.size > BLOCK_SIZE) k = hashFn(k)
        if (k.size < BLOCK_SIZE) k = k.copyOf(BLOCK_SIZE) // completa com zeros a direita
        val ipad = ByteArray(BLOCK_SIZE) { (k[it].toInt() xor 0x36).toByte() }
        val opad = ByteArray(BLOCK_SIZE) { (k[it].toInt() xor 0x5c).toByte() }
        val inner = hashFn(ipad + message)
        return hashFn(opad + inner)
    }

    fun kdf(key: ByteArray, path: List<ByteArray>): ByteArray {
        var hashFn: (ByteArray) -> ByteArray = ::sha256
        run {
            val base = hashFn
            hashFn = { msg -> genericHmac(base, ROOT_LABEL, msg) }
        }
        for (segment in path) {
            val previous = hashFn
            hashFn = { msg -> genericHmac(previous, segment, msg) }
        }
        return hashFn(key)
    }

    fun kdf16(key: ByteArray, path: List<ByteArray>): ByteArray = kdf(key, path).copyOf(16)

    fun label(vararg segments: String): List<ByteArray> = segments.map { it.toByteArray(Charsets.UTF_8) }
}