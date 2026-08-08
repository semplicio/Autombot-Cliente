package com.autombot.client.protocols.trojan

import com.autombot.client.protocols.ssh.UdpBackendSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

/**
 * UDP do Trojan em uma conexão TLS compartilhada. O transporte agora propaga falhas
 * de escrita e expõe estado fechado para que o Manager possa recriá-lo, em vez de
 * reutilizar indefinidamente uma conexão TLS que o servidor já encerrou.
 */
class TrojanUdpTransport(
    private val config: TrojanConnectionConfig,
    private val protectSocket: (Socket) -> Boolean
) {
    private var socket: SSLSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private val callbacks = ConcurrentHashMap<String, (ByteArray) -> Unit>()
    private val closed = AtomicBoolean(false)
    @Volatile private var connectError: Exception? = null

    init {
        try {
            connectAndStartReading()
        } catch (e: Exception) {
            connectError = e
            closed.set(true)
            closeResources()
        }
    }

    fun isClosed(): Boolean = closed.get() || socket?.isClosed == true

    private fun connectAndStartReading() {
        val rawSocket = Socket()
        rawSocket.bind(InetSocketAddress(0))
        if (!protectSocket(rawSocket)) {
            runCatching { rawSocket.close() }
            throw IOException("Não consegui isentar esta conexão UDP (Trojan) da VPN (protect() falhou).")
        }
        try {
            rawSocket.connect(InetSocketAddress(config.server, config.port), 10_000)
        } catch (e: Exception) {
            runCatching { rawSocket.close() }
            throw IOException("Não consegui conectar UDP Trojan em ${config.server}:${config.port}: ${e.message}", e)
        }

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
        val sslSocket = sslContext.socketFactory.createSocket(rawSocket, config.server, config.port, true) as SSLSocket
        val sni = config.sni.ifBlank { config.server }
        val params: SSLParameters = sslSocket.sslParameters
        params.serverNames = listOf(SNIHostName(sni))
        sslSocket.sslParameters = params
        sslSocket.startHandshake()

        runCatching { sslSocket.keepAlive = true }
        runCatching { sslSocket.tcpNoDelay = true }
        runCatching { sslSocket.sendBufferSize = 256 * 1024 }
        runCatching { sslSocket.receiveBufferSize = 256 * 1024 }

        val out = sslSocket.outputStream
        val inp = sslSocket.inputStream
        val header = TrojanProtocol.buildRequestHeader(
            config.password,
            config.server,
            config.port,
            TrojanProtocol.CMD_UDP_ASSOCIATE
        )
        out.write(header)
        out.flush()

        socket = sslSocket
        output = out
        input = inp
        scope.launch { receiveLoop(inp) }
    }

    private suspend fun receiveLoop(inp: InputStream) {
        try {
            while (isActive && !closed.get()) {
                val parsed = readUdpPacket(inp) ?: break
                val (srcHost, srcPort, payload) = parsed
                callbacks["$srcHost:$srcPort"]?.invoke(payload)
            }
        } catch (_: Exception) {
        } finally {
            closed.set(true)
            closeResources()
        }
    }

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
        readExact(inp, 2) ?: return null
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
        connectError?.let { throw IOException("Transporte UDP Trojan não iniciou: ${it.message}", it) }
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
        if (isClosed()) throw IOException("Transporte UDP Trojan está fechado")
        val out = output ?: throw IOException("Transporte UDP Trojan sem stream de saída")
        val framed = TrojanProtocol.encodeUdpPacket(destHost, destPort, payload)
        try {
            synchronized(this) {
                out.write(framed)
                out.flush()
            }
        } catch (e: Exception) {
            closed.set(true)
            closeResources()
            throw IOException("Falha ao enviar UDP Trojan: ${e.message}", e)
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) {
            closeResources()
            return
        }
        callbacks.clear()
        closeResources()
    }

    private fun closeResources() {
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        input = null
        output = null
        socket = null
    }
}