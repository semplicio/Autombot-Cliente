package com.autombot.client.protocols.shadowsocks

import com.autombot.client.protocols.ssh.UdpBackendSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

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
 * conexão Shadowsocks. A instância agora expõe o estado fechado e propaga falhas de
 * envio, permitindo ao Socks5Server/Manager reconstruir o transporte em vez de
 * continuar reutilizando silenciosamente um socket morto.
 */
class ShadowsocksUdpTransport(
    private val config: ShadowsocksConnectionConfig,
    private val protectDatagramSocket: (DatagramSocket) -> Boolean
) {
    private val spec = ShadowsocksCrypto.specFor(config.method)
    private val masterKey = ShadowsocksCrypto.deriveMasterKey(config.password, spec.keySize)
    private val socket = DatagramSocket()
    private val scope = CoroutineScope(Dispatchers.IO)
    private val callbacks = ConcurrentHashMap<String, (ByteArray) -> Unit>()
    private val closed = AtomicBoolean(false)

    init {
        if (!protectDatagramSocket(socket)) {
            runCatching { socket.close() }
            closed.set(true)
            throw IOException("Não consegui isentar o socket UDP do Shadowsocks da VPN (protect() falhou).")
        }
        try {
            socket.connect(InetSocketAddress(config.server, config.port))
        } catch (e: Exception) {
            runCatching { socket.close() }
            closed.set(true)
            throw IOException("Não consegui conectar o UDP Shadowsocks em ${config.server}:${config.port}: ${e.message}", e)
        }
        scope.launch { receiveLoop() }
    }

    fun isClosed(): Boolean = closed.get() || socket.isClosed

    private suspend fun receiveLoop() {
        val buffer = ByteArray(65535)
        while (isActive && !closed.get()) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val (srcHost, srcPort, payload) = decode(buffer, packet.length) ?: continue
                callbacks["$srcHost:$srcPort"]?.invoke(payload)
            } catch (e: SocketException) {
                if (closed.get() || socket.isClosed) break
                // Uma falha estrutural do socket deve invalidar o transporte para que
                // a próxima tentativa crie outro socket, em vez de reutilizar este.
                closed.set(true)
                break
            } catch (e: Exception) {
                if (closed.get()) break
                // Pacote corrompido/tag inválida não derruba o transporte inteiro.
            }
        }
    }

    /** Registra o destino (pra saber rotear a resposta certa) e manda o payload. */
    fun openSession(destHost: String, destPort: Int, onIncoming: (ByteArray) -> Unit): UdpBackendSession? {
        if (isClosed()) return null
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
        if (isClosed()) throw IOException("Transporte UDP Shadowsocks está fechado")

        val salt = ShadowsocksCrypto.randomSalt(spec.saltSize)
        val subkey = ShadowsocksCrypto.deriveSessionSubkey(masterKey, salt, spec.keySize)
        val addressHeader = ShadowsocksCrypto.encodeAddressHeader(destHost, destPort)
        val plaintext = addressHeader + payload
        val nonce = ByteArray(spec.nonceSize)
        val ciphertext = ShadowsocksCrypto.aeadEncrypt(spec, subkey, nonce, plaintext)
        val packetData = salt + ciphertext

        try {
            socket.send(DatagramPacket(packetData, packetData.size))
        } catch (e: Exception) {
            closed.set(true)
            runCatching { socket.close() }
            throw IOException("Falha ao enviar UDP Shadowsocks: ${e.message}", e)
        }
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
        if (!closed.compareAndSet(false, true)) return
        callbacks.clear()
        runCatching { socket.close() }
    }
}
