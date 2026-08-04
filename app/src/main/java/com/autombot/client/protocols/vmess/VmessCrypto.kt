package com.autombot.client.protocols.vmess

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Criptografia do protocolo VMess (variante AEAD, a usada por padrão em instalações
 * modernas do Xray-core/V2Ray — a mesma que o AutomBot Core instala).
 *
 * ATUALIZAÇÃO: boa parte deste arquivo foi CONFIRMADA contra a documentação oficial
 * (v2fly.org/en_US/developer/protocols/vmess.html) e contra trechos reais do
 * código-fonte do v2ray-core (proxy/vmess/aead/encrypt.go) — não é mais só
 * reconstrução de memória. Dois erros reais foram encontrados e corrigidos nessa
 * checagem: a fórmula do KDF (ver VmessKdf.kt) e o campo de comprimento cifrado
 * (media o tamanho do texto já cifrado, devia ser do texto puro).
 *
 * Ainda assim, é a parte mais arriscada de todo o projeto — qualquer detalhe errado
 * que sobrou (um valor de opção, a ordem de um campo) faz a conexão falhar por
 * inteiro, sem sintoma parcial pra ajudar a debugar. Pontos que dependem de padrões
 * do dia a dia (não de spec formal, por isso ainda incertos) seguem marcados com
 * "// INCERTO:".
 */
object VmessCrypto {

    // Confirmado contra a documentacao oficial: CmdKey = MD5(UUID + esta string).
    private const val CMD_KEY_MAGIC = "c48619fe-8f02-49e0-b9e9-edf763e17e21"

    data class RequestHeader(
        val authId: ByteArray,
        val connectionNonce: ByteArray,
        val encryptedLength: ByteArray,
        val encryptedHeader: ByteArray,
        val requestBodyKey: ByteArray,
        val requestBodyIv: ByteArray,
        val responseHeaderByte: Int
    )

    /** cmdKey = MD5(uuidBytes + string mágica) — 16 bytes, base de todas as outras chaves. */
    fun cmdKey(uuidStr: String): ByteArray {
        val uuid = UUID.fromString(uuidStr)
        val uuidBytes = ByteArray(16)
        val buffer = java.nio.ByteBuffer.wrap(uuidBytes)
        buffer.putLong(uuid.mostSignificantBits)
        buffer.putLong(uuid.leastSignificantBits)
        val md5 = MessageDigest.getInstance("MD5")
        md5.update(uuidBytes)
        md5.update(CMD_KEY_MAGIC.toByteArray(Charsets.UTF_8))
        return md5.digest()
    }

    /**
     * Monta a requisição completa pronta pra mandar pro servidor: AuthID (16) +
     * connectionNonce (8) + comprimento cifrado (18) + cabeçalho cifrado (N+16).
     */
    fun buildRequest(uuidStr: String, destHost: String, destPort: Int, command: Int = 0x01): RequestHeader {
        val cmdKeyBytes = cmdKey(uuidStr)
        val random = SecureRandom()

        val authId = generateAuthId(cmdKeyBytes, random)
        val connectionNonce = ByteArray(8).also { random.nextBytes(it) }

        val requestBodyKey = ByteArray(16).also { random.nextBytes(it) }
        val requestBodyIv = ByteArray(16).also { random.nextBytes(it) }
        val responseHeaderByte = random.nextInt(256)

        val plainHeader = buildPlainHeader(requestBodyKey, requestBodyIv, responseHeaderByte, destHost, destPort, random, command)

        // Confirmado contra a documentacao oficial e o codigo-fonte real do
        // v2ray-core (proxy/vmess/aead/encrypt.go) — nomes e ordem exatos dos
        // segmentos usados pra derivar as chaves/nonces do comprimento e do
        // cabecalho.
        val lengthKey = VmessKdf.kdf16(cmdKeyBytes, VmessKdf.label("VMess Header AEAD Key_Length") + listOf(authId, connectionNonce))
        val lengthNonce = VmessKdf.kdf(cmdKeyBytes, VmessKdf.label("VMess Header AEAD Nonce_Length") + listOf(authId, connectionNonce)).copyOf(12)
        val payloadKey = VmessKdf.kdf16(cmdKeyBytes, VmessKdf.label("VMess Header AEAD Key") + listOf(authId, connectionNonce))
        val payloadNonce = VmessKdf.kdf(cmdKeyBytes, VmessKdf.label("VMess Header AEAD Nonce") + listOf(authId, connectionNonce)).copyOf(12)

        val encryptedHeader = aesGcmEncrypt(payloadKey, payloadNonce, plainHeader, authId)
        // CORRIGIDO: o comprimento cifrado aqui e do cabecalho em TEXTO PURO
        // (plainHeader), nao do resultado ja cifrado (encryptedHeader, que e maior
        // por causa da tag de autenticacao de 16 bytes). Confirmado direto no
        // codigo-fonte do v2ray-core (proxy/vmess/aead/encrypt.go): a variavel
        // "headerPayloadDataLen" usada pra montar esse campo vem do tamanho dos
        // dados ANTES de chamar Seal() (criptografar), nao depois.
        val lengthPlain = byteArrayOf(((plainHeader.size shr 8) and 0xFF).toByte(), (plainHeader.size and 0xFF).toByte())
        val encryptedLength = aesGcmEncrypt(lengthKey, lengthNonce, lengthPlain, authId)

        return RequestHeader(
            authId = authId,
            connectionNonce = connectionNonce,
            encryptedLength = encryptedLength,
            encryptedHeader = encryptedHeader,
            requestBodyKey = requestBodyKey,
            requestBodyIv = requestBodyIv,
            responseHeaderByte = responseHeaderByte
        )
    }

    /** [1 versao=1][16 IV do corpo][16 chave do corpo][1 byte de resposta][1 opcoes][1 padding<<4|seguranca][1 reservado=0][1 comando: 0x01=TCP/0x02=UDP][2 porta][endereco][padding][4 checksum FNV1a] */
    private fun buildPlainHeader(
        requestBodyKey: ByteArray,
        requestBodyIv: ByteArray,
        responseHeaderByte: Int,
        destHost: String,
        destPort: Int,
        random: SecureRandom,
        command: Int = 0x01
    ): ByteArray {
        val addressBytes = encodeAddress(destHost)
        val paddingLen = random.nextInt(16) // 0-15, cabe nos 4 bits altos do byte padding|seguranca
        val padding = ByteArray(paddingLen).also { random.nextBytes(it) }

        val bodyLen = 1 + 16 + 16 + 1 + 1 + 1 + 1 + 1 + 2 + addressBytes.size + paddingLen
        val body = ByteArray(bodyLen)
        var offset = 0
        body[offset++] = 0x01 // versao
        System.arraycopy(requestBodyIv, 0, body, offset, 16); offset += 16
        System.arraycopy(requestBodyKey, 0, body, offset, 16); offset += 16
        body[offset++] = responseHeaderByte.toByte()
        body[offset++] = 0x01 // opcoes: S (formato padrao/chunked) — confirmado, unico bit ligado
        body[offset++] = ((paddingLen shl 4) or 0x03).toByte() // seguranca 0x03 = AES-128-GCM — confirmado contra a spec
        body[offset++] = 0x00 // reservado
        body[offset++] = command.toByte()
        body[offset++] = ((destPort shr 8) and 0xFF).toByte()
        body[offset++] = (destPort and 0xFF).toByte()
        System.arraycopy(addressBytes, 0, body, offset, addressBytes.size); offset += addressBytes.size
        System.arraycopy(padding, 0, body, offset, paddingLen); offset += paddingLen

        val checksum = fnv1a32(body, 0, offset)
        val result = ByteArray(offset + 4)
        System.arraycopy(body, 0, result, 0, offset)
        result[offset] = ((checksum shr 24) and 0xFF).toByte()
        result[offset + 1] = ((checksum shr 16) and 0xFF).toByte()
        result[offset + 2] = ((checksum shr 8) and 0xFF).toByte()
        result[offset + 3] = (checksum and 0xFF).toByte()
        return result
    }

    private fun generateAuthId(cmdKeyBytes: ByteArray, random: SecureRandom): ByteArray {
        // Confirmado contra a documentacao oficial: 8 bytes de timestamp (Big-Endian)
        // + 4 bytes aleatorios + 4 bytes de checksum CRC32 (IEEE) dos 12 primeiros.
        val buffer = ByteArray(16)
        val timestamp = System.currentTimeMillis() / 1000
        for (i in 0 until 8) {
            buffer[7 - i] = ((timestamp shr (i * 8)) and 0xFF).toByte()
        }
        val randomPart = ByteArray(4).also { random.nextBytes(it) }
        System.arraycopy(randomPart, 0, buffer, 8, 4)
        val crc = CRC32()
        crc.update(buffer, 0, 12)
        val crcValue = crc.value.toInt()
        buffer[12] = ((crcValue shr 24) and 0xFF).toByte()
        buffer[13] = ((crcValue shr 16) and 0xFF).toByte()
        buffer[14] = ((crcValue shr 8) and 0xFF).toByte()
        buffer[15] = (crcValue and 0xFF).toByte()

        val key = VmessKdf.kdf16(cmdKeyBytes, VmessKdf.label("AES Auth ID Encryption"))
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(buffer)
    }

    private fun encodeAddress(host: String): ByteArray {
        val ipv4Regex = Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")
        return if (ipv4Regex.matches(host)) {
            val parts = host.split(".").map { it.toInt() }
            byteArrayOf(0x01, parts[0].toByte(), parts[1].toByte(), parts[2].toByte(), parts[3].toByte())
        } else {
            val nameBytes = host.toByteArray(Charsets.US_ASCII)
            val result = ByteArray(2 + nameBytes.size)
            result[0] = 0x02
            result[1] = nameBytes.size.toByte()
            System.arraycopy(nameBytes, 0, result, 2, nameBytes.size)
            result
        }
    }

    /**
     * Chave/IV de resposta no modo AEAD = SHA-256 (truncado pra 16 bytes) da chave/IV
     * de requisição — usados pra descriptografar o que o servidor manda de volta.
     * CORRIGIDO: a primeira versão usava MD5 aqui, que é a fórmula do modo antigo/
     * depreciado (confirmado contra a spec oficial: "AEAD Authentication Response" usa
     * SHA-256, "MD5 Authentication Response (Deprecated)" é que usa MD5 — como estamos
     * usando AEAD (aid=0), tem que ser SHA-256).
     */
    fun responseKey(requestBodyKey: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(requestBodyKey).copyOf(16)
    fun responseIv(requestBodyIv: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(requestBodyIv).copyOf(16)

    /** Nonce de um "pedaço" (chunk) do corpo: 2 bytes contador + 10 bytes do IV. */
    fun chunkNonce(iv: ByteArray, chunkIndex: Int): ByteArray {
        val nonce = ByteArray(12)
        nonce[0] = ((chunkIndex shr 8) and 0xFF).toByte()
        nonce[1] = (chunkIndex and 0xFF).toByte()
        System.arraycopy(iv, 2, nonce, 2, 10)
        return nonce
    }

    data class ResponseHeaderInfo(val authV: Int, val opt: Int, val cmd: Int)

    /**
     * Lê e decodifica o cabeçalho de resposta do servidor (formato AEAD) — confirmado
     * contra a spec oficial: primeiro o comprimento cifrado (18 bytes fixos), depois o
     * conteúdo cifrado (tamanho variável, decodificado a partir do comprimento).
     * Lança exceção se o AuthV não bater com o que mandamos (autenticação do servidor
     * falhou) ou se a decriptografia AEAD falhar (tag inválida = dados corrompidos ou
     * chave errada).
     */
    fun decodeResponseHeader(
        requestBodyKey: ByteArray,
        requestBodyIv: ByteArray,
        expectedResponseHeaderByte: Int,
        input: java.io.InputStream
    ): ResponseHeaderInfo {
        val respKey = responseKey(requestBodyKey)
        val respIv = responseIv(requestBodyIv)

        val lengthKey = VmessKdf.kdf16(respKey, VmessKdf.label("AEAD Resp Header Len Key"))
        val lengthNonce = VmessKdf.kdf(respIv, VmessKdf.label("AEAD Resp Header Len IV")).copyOf(12)
        val encryptedLength = readExact(input, 18)
        val lengthPlain = aesGcmDecrypt(lengthKey, lengthNonce, encryptedLength)
        val contentLength = ((lengthPlain[0].toInt() and 0xFF) shl 8) or (lengthPlain[1].toInt() and 0xFF)

        val contentKey = VmessKdf.kdf16(respKey, VmessKdf.label("AEAD Resp Header Key"))
        val contentNonce = VmessKdf.kdf(respIv, VmessKdf.label("AEAD Resp Header IV")).copyOf(12)
        val encryptedContent = readExact(input, contentLength + 16)
        val content = aesGcmDecrypt(contentKey, contentNonce, encryptedContent)

        val authV = content[0].toInt() and 0xFF
        if (authV != expectedResponseHeaderByte) {
            throw java.io.IOException("Servidor VMess respondeu com AuthV inesperado ($authV, esperava $expectedResponseHeaderByte) — autenticação falhou")
        }
        val opt = content[1].toInt() and 0xFF
        val cmd = content[2].toInt() and 0xFF
        val cmdLen = content[3].toInt() and 0xFF
        // Conteudo de comando (ex: instrucao de porta dinamica) — nao implementado,
        // so consumimos os bytes pra nao desalinhar o stream.
        if (cmdLen > 0 && content.size < 4 + cmdLen) {
            throw java.io.IOException("Cabeçalho de resposta VMess incompleto")
        }
        return ResponseHeaderInfo(authV, opt, cmd)
    }

    private fun readExact(input: java.io.InputStream, size: Int): ByteArray {
        val buf = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val n = input.read(buf, offset, size - offset)
            if (n == -1) throw java.io.EOFException("Conexão fechada enquanto esperava $size bytes (recebeu $offset)")
            offset += n
        }
        return buf
    }

    fun aesGcmEncrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray? = null): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        if (aad != null) cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    fun aesGcmDecrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray? = null): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        if (aad != null) cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    private fun fnv1a32(data: ByteArray, offset: Int, length: Int): Int {
        var hash = -0x7ee3623b // 2166136261 como Int assinado
        for (i in offset until offset + length) {
            hash = hash xor (data[i].toInt() and 0xFF)
            hash *= 0x01000193
        }
        return hash
    }
}