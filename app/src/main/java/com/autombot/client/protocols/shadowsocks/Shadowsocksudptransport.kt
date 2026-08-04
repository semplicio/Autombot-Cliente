package com.autombot.client.protocols.shadowsocks

import com.autombot.client.protocols.ssh.UdpBackendSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * UDP do Shadowsocks (ver shadowsocks.org/doc/aead.html, seção UDP): diferente de
 * VLESS/VMess (uma conexão nova por destino), o Shadowsocks usa UM ÚNICO socket UDP
 * pro servidor, reaproveitado pra QUALQUER destino — cada datagrama se autodescreve
 * (o próprio pacote cifrado começa com [tipo endereço][endereço][porta] do destino
 * real, igual ao primeiro payload usado no TCP). O servidor lê isso e decide sozinho
 * pra onde mandar cada pacote.
 *
 * Cada datagrama tem seu PRÓPRIO salt aleatório (não dá pra reaproveitar/encadear
 * como no TCP, já que UDP não garante ordem nem entrega) — formato:
 * [salt aleatório][AEAD_encrypt(subkey_desse_salt, nonce=0, endereço+payload)].
 *
 * Uma instância desta classe é compartilhada entre TODOS os destinos de uma mesma
 * conexão Shadowsocks (ao contrário do padrão "uma sessão por destino" que
 * VLESS/VMess usam) — por isso o [ShadowsocksTunnelManager] guarda uma única
 * instância por conexão, criada sob demanda na primeira vez que algum UDP aparece.
 */
class ShadowsocksUdpTransport(
    private val config: ShadowsocksConnectionConfig,
    private val protectDatagramSocket: (DatagramSocket) -> Boolean
) {
    private val spec = ShadowsocksCrypto.specFor(config.method)
    private val masterKey = ShadowsocksCrypto.deriveMasterKey(config.password, spec.keySize)
    private val socket = DatagramSocket()
    private val scope = CoroutineScope(Dispatchers.IO)
    // Um callback onIncoming por destino que já mandamos alguma coisa — usado pra
    // saber qual sessao chamar quando uma resposta chega (o servidor Shadowsocks
    // manda de volta com o MESMO formato de endereco, entao a gente sabe de quem e).
    private val callbacks = ConcurrentHashMap<String, (ByteArray) -> Unit>()
    @Volatile private var closed = false

    init {
        protectDatagramSocket(socket)
        socket.connect(InetSocketAddress(config.server, config.port))
        scope.launch { receiveLoop() }
    }

    private suspend fun receiveLoop() {
        val buffer = ByteArray(65535)
        while (!closed) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val (srcHost, srcPort, payload) = decode(buffer, packet.length) ?: continue
                callbacks["$srcHost:$srcPort"]?.invoke(payload)
            } catch (e: Exception) {
                if (closed) break
                // pacote corrompido/tag invalida — descarta e continua, nao derruba
                // o socket inteiro por causa de UM pacote ruim.
            }
        }
    }

    /** Registra o destino (pra saber rotear a resposta certa) e manda o payload. */
    fun openSession(destHost: String, destPort: Int, onIncoming: (ByteArray) -> Unit): UdpBackendSession {
        callbacks["$destHost:$destPort"] = onIncoming
        val transport = this
        return object : UdpBackendSession {
            override suspend fun send(payload: ByteArray) {
                transport.send(destHost, destPort, payload)
            }
            override fun close() {
                callbacks.remove("$destHost:$destPort")
            }
        }
    }

    private fun send(destHost: String, destPort: Int, payload: ByteArray) {
        val salt = ShadowsocksCrypto.randomSalt(spec.saltSize)
        val subkey = ShadowsocksCrypto.deriveSessionSubkey(masterKey, salt, spec.keySize)
        val addressHeader = ShadowsocksCrypto.encodeAddressHeader(destHost, destPort)
        val plaintext = addressHeader + payload
        // Nonce sempre zero — cada pacote UDP usa um salt novo (aleatorio), entao
        // reusar nonce=0 e seguro (o par chave+nonce nunca se repete de verdade,
        // porque a chave/subkey muda a cada pacote junto com o salt).
        val nonce = ByteArray(spec.nonceSize)
        val ciphertext = ShadowsocksCrypto.aeadEncrypt(spec, subkey, nonce, plaintext)
        val packetData = salt + ciphertext
        runCatching { socket.send(DatagramPacket(packetData, packetData.size)) }
    }

    private fun decode(buffer: ByteArray, length: Int): Triple<String, Int, ByteArray>? {
        if (length < spec.saltSize) return null
        val salt = buffer.copyOfRange(0, spec.saltSize)
        val subkey = ShadowsocksCrypto.deriveSessionSubkey(masterKey, salt, spec.keySize)
        val nonce = ByteArray(spec.nonceSize)
        val ciphertext = buffer.copyOfRange(spec.saltSize, length)
        val plaintext = try {
            ShadowsocksCrypto.aeadDecrypt(spec, subkey, nonce, ciphertext)
        } catch (e: Exception) {
            return null
        }
        return decodeAddressHeader(plaintext)
    }

    /** Mesmo formato de [ShadowsocksCrypto.encodeAddressHeader], só que pra ler de volta. */
    private fun decodeAddressHeader(data: ByteArray): Triple<String, Int, ByteArray>? {
        if (data.isEmpty()) return null
        val atyp = data[0].toInt() and 0xFF
        var offset = 1
        val host = when (atyp) {
            0x01 -> {
                if (data.size < offset + 4) return null
                val addr = data.copyOfRange(offset, offset + 4)
                offset += 4
                addr.joinToString(".") { (it.toInt() and 0xFF).toString() }
            }
            0x03 -> {
                if (data.size < offset + 1) return null
                val len = data[offset].toInt() and 0xFF
                offset += 1
                if (data.size < offset + len) return null
                val name = String(data, offset, len, Charsets.US_ASCII)
                offset += len
                name
            }
            0x04 -> {
                if (data.size < offset + 16) return null
                val addr = data.copyOfRange(offset, offset + 16)
                offset += 16
                InetAddress.getByAddress(addr).hostAddress ?: return null
            }
            else -> return null
        }
        if (data.size < offset + 2) return null
        val port = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
        offset += 2
        val payload = data.copyOfRange(offset, data.size)
        return Triple(host, port, payload)
    }

    fun close() {
        closed = true
        callbacks.clear()
        runCatching { socket.close() }
    }
}