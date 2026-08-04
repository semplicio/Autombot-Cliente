package com.autombot.client.protocols.trojan

import com.autombot.client.protocols.ssh.UdpBackendSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

/**
 * UDP do Trojan (ver especificação oficial): diferente de VLESS/VMess (uma conexão
 * nova por destino), o Trojan abre UMA ÚNICA conexão TLS com comando UDP Associate
 * (0x03) e reaproveita ela pra QUALQUER destino — cada "pacote" dentro dessa mesma
 * conexão se autodescreve com endereço+porta+tamanho (ver
 * TrojanProtocol.encodeUdpPacket). Mesmo modelo de socket compartilhado que o
 * Shadowsocks usa (ver ShadowsocksUdpTransport.kt), só que aqui é uma conexão TCP+TLS
 * em vez de um socket UDP puro — o Trojan "finge" ser UDP por dentro de um túnel TCP.
 */
class TrojanUdpTransport(
    private val config: TrojanConnectionConfig,
    private val protectSocket: (Socket) -> Boolean
) {
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private val callbacks = ConcurrentHashMap<String, (ByteArray) -> Unit>()
    @Volatile private var closed = false
    @Volatile private var connectError: Exception? = null

    init {
        try {
            connectAndStartReading()
        } catch (e: Exception) {
            connectError = e
            closed = true
        }
    }

    private fun connectAndStartReading() {
        val socket = Socket()
        socket.bind(InetSocketAddress(0))
        if (!protectSocket(socket)) {
            throw java.io.IOException("Não consegui isentar esta conexão UDP (Trojan) da VPN (protect() falhou).")
        }
        socket.connect(InetSocketAddress(config.server, config.port), 10_000)

        val sslContext = SSLContext.getInstance("TLS")
        if (config.allowInsecure) {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())
        } else {
            sslContext.init(null, null, null)
        }
        val sslSocket = sslContext.socketFactory.createSocket(socket, config.server, config.port, true) as SSLSocket
        val sni = config.sni.ifBlank { config.server }
        val params: SSLParameters = sslSocket.sslParameters
        params.serverNames = listOf(SNIHostName(sni))
        sslSocket.sslParameters = params
        sslSocket.startHandshake()

        val out = sslSocket.outputStream
        val inp = sslSocket.inputStream

        // Cabecalho inicial com comando UDP Associate — endereco/porta aqui nao
        // importam muito (o destino real de cada pacote vem embutido em cada
        // datagrama depois), mas o protocolo exige um destino no cabecalho mesmo
        // assim; usamos o proprio servidor como placeholder.
        val header = TrojanProtocol.buildRequestHeader(config.password, config.server, config.port, TrojanProtocol.CMD_UDP_ASSOCIATE)
        out.write(header)
        out.flush()

        output = out
        input = inp

        scope.launch { receiveLoop(inp) }
    }

    private suspend fun receiveLoop(inp: InputStream) {
        while (!closed) {
            try {
                val parsed = readUdpPacket(inp) ?: break
                val (srcHost, srcPort, payload) = parsed
                callbacks["$srcHost:$srcPort"]?.invoke(payload)
            } catch (e: Exception) {
                break
            }
        }
        closed = true
    }

    /** Le um pacote no mesmo formato do TrojanProtocol.encodeUdpPacket. */
    private fun readUdpPacket(inp: InputStream): Triple<String, Int, ByteArray>? {
        val atyp = inp.read()
        if (atyp == -1) return null
        val host = when (atyp) {
            0x01 -> {
                val addr = readExact(inp, 4) ?: return null
                addr.joinToString(".") { (it.toInt() and 0xFF).toString() }
            }
            0x03 -> {
                val len = inp.read()
                if (len == -1) return null
                val nameBytes = readExact(inp, len) ?: return null
                String(nameBytes, Charsets.US_ASCII)
            }
            0x04 -> {
                val addr = readExact(inp, 16) ?: return null
                java.net.InetAddress.getByAddress(addr).hostAddress ?: return null
            }
            else -> return null
        }
        val portBytes = readExact(inp, 2) ?: return null
        val port = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)
        val lenBytes = readExact(inp, 2) ?: return null
        val len = ((lenBytes[0].toInt() and 0xFF) shl 8) or (lenBytes[1].toInt() and 0xFF)
        readExact(inp, 2) ?: return null // CRLF
        val payload = readExact(inp, len) ?: return null
        return Triple(host, port, payload)
    }

    private fun readExact(inp: InputStream, size: Int): ByteArray? {
        if (size == 0) return ByteArray(0)
        val buf = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val n = inp.read(buf, offset, size - offset)
            if (n == -1) return null
            offset += n
        }
        return buf
    }

    fun openSession(destHost: String, destPort: Int, onIncoming: (ByteArray) -> Unit): UdpBackendSession? {
        if (closed) return null
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
        val out = output ?: return
        val framed = TrojanProtocol.encodeUdpPacket(destHost, destPort, payload)
        synchronized(this) {
            runCatching { out.write(framed); out.flush() }
        }
    }

    fun close() {
        closed = true
        callbacks.clear()
        runCatching { input?.close() }
        runCatching { output?.close() }
    }
}