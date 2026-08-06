package com.autombot.client.protocols.ssh

import android.content.Context
import com.autombot.client.core.AutomBotVpnService
import com.autombot.client.util.AppLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.net.SocketFactory
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket

/**
 * Núcleo real da conexão SSH (sshj + servidor SOCKS5 próprio).
 */
class SshTunnelManager(context: Context) {

    companion object {
        init {
            java.security.Security.removeProvider("BC")
            java.security.Security.insertProviderAt(BouncyCastleProvider(), 1)
        }
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("autombot_ssh", Context.MODE_PRIVATE)

    private val _connections = MutableStateFlow<List<ManagedSshConnection>>(emptyList())
    val connections: StateFlow<List<ManagedSshConnection>> = _connections

    private val activeClients = mutableMapOf<String, SSHClient>()
    private val activeSocksServers = mutableMapOf<String, Socks5Server>()
    private val activeSlowDnsClients = mutableMapOf<String, com.autombot.client.protocols.slowdns.SlowDnsClient>()
    private val activeUdpGwClients = mutableMapOf<String, UdpGwClient>()
    private val udpGwCreationMutex = Mutex()
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val channelSemaphore = Semaphore(24)
    private val channelOpenExecutor = Executors.newCachedThreadPool()

    init {
        loadPersistedProfiles()
        managerScope.launch {
            while (isActive) {
                delay(2000)
                activeSocksServers.forEach { (name, server) ->
                    _connections.update { current ->
                        current.map {
                            if (it.config.connectionName == name)
                                it.copy(rxBytes = server.totalRx.get(), txBytes = server.totalTx.get())
                            else it
                        }
                    }
                }
            }
        }
    }

    fun saveProfile(config: SshConnectionConfig) {
        _connections.update { current ->
            val existing = current.firstOrNull { it.config.connectionName == config.connectionName }
            val managed = existing?.copy(config = config) ?: ManagedSshConnection(config = config)
            current.filterNot { it.config.connectionName == config.connectionName } + managed
        }
        persistProfiles()
    }

    fun removeProfile(connectionName: String) {
        _connections.update { current -> current.filterNot { it.config.connectionName == connectionName } }
        persistProfiles()
    }

    private fun persistProfiles() {
        val array = JSONArray()
        _connections.value.forEach { array.put(it.config.toJson()) }
        prefs.edit().putString("profiles", array.toString()).commit()
    }

    private fun loadPersistedProfiles() {
        val raw = prefs.getString("profiles", null) ?: return
        runCatching {
            val array = JSONArray(raw)
            val loaded = (0 until array.length()).map { i ->
                ManagedSshConnection(config = sshConnectionConfigFromJson(array.getJSONObject(i)))
            }
            _connections.value = loaded
        }.onFailure { e ->
            AppLog.log("Falha ao carregar perfis SSH salvos: ${e.message}", AppLog.Level.ERROR)
        }
    }

    suspend fun connect(connectionName: String) {
        val managed = _connections.value.firstOrNull { it.config.connectionName == connectionName } ?: return
        val config = managed.config

        markStatus(connectionName, SshStatus.CONNECTING)
        AppLog.log("SSH \"$connectionName\": iniciando conexão (${config.describeLayers()})", AppLog.Level.INFO)

        withContext(Dispatchers.IO) {
            try {
                val client = SSHClient()
                client.addHostKeyVerifier(PromiscuousVerifier())

                val port = config.port.toIntOrNull() ?: 22

                var slowDnsLocalPort: Int? = null
                if (config.useSlowDns) {
                    AppLog.log("SSH \"$connectionName\": subindo túnel SlowDNS antes de conectar…", AppLog.Level.INFO)
                    val slowDnsClient = com.autombot.client.protocols.slowdns.SlowDnsClient(
                        context = appContext,
                        domain = config.slowDnsDomain,
                        pubkey = config.slowDnsPubkey,
                        resolverMode = config.slowDnsResolverMode,
                        resolver = config.slowDnsResolver
                    )
                    val localPort = slowDnsClient.start()
                    if (localPort == null) {
                        markError(connectionName, "Falha ao subir o túnel SlowDNS — confira domínio/chave pública/resolvedor")
                        return@withContext
                    }
                    activeSlowDnsClients[connectionName] = slowDnsClient
                    slowDnsLocalPort = localPort
                }

                AppLog.log(
                    "SSH \"$connectionName\": [1/4] conectando em ${config.server}:$port (${config.describeLayers()})",
                    AppLog.Level.INFO
                )
                client.socketFactory = composedSocketFactory(config, slowDnsLocalPort)
                client.connect(config.server, port)
                
                client.connection.keepAlive.keepAliveInterval = 20

                AppLog.log("SSH \"$connectionName\": [2/4] handshake SSH concluído, autenticando (${config.authMethod.label})", AppLog.Level.INFO)

                when (config.authMethod) {
                    SshAuthMethod.PASSWORD -> client.authPassword(config.username, config.password)
                    SshAuthMethod.PRIVATE_KEY -> {
                        val keyFile = File.createTempFile("autombot_ssh_key", ".pem")
                        keyFile.writeText(config.privateKeyPem)
                        keyFile.setReadable(true, true)
                        try {
                            val keyProvider = client.loadKeys(keyFile.absolutePath)
                            client.authPublickey(config.username, keyProvider)
                        } finally {
                            keyFile.delete()
                        }
                    }
                }
                AppLog.log("SSH \"$connectionName\": [3/4] autenticado, subindo proxy SOCKS5 local", AppLog.Level.INFO)

                activeClients[connectionName] = client

                val socksPort = findFreePort()
                val socksServer = Socks5Server(
                    socksPort,
                    onConnectRequest = { destHost, destPort ->
                        openDirectChannel(client, destHost, destPort)
                    },
                    onUdpAssociateRequest = { destHost, destPort, onIncoming ->
                        if (config.udpForwardEnabled) {
                            openUdpOverGateway(client, connectionName, config.udpGatewayHost, config.udpGatewayPort.toIntOrNull() ?: 7300, destHost, destPort, onIncoming)
                        } else {
                            openUdpOver443Internal(client, destHost, destPort, onIncoming)
                        }
                    },
                    protectDatagramSocket = { socket -> AutomBotVpnService.protectDatagramSocket(socket) },
                    dns1 = if (config.dnsForwardingEnabled) config.dnsPrimary else "8.8.8.8",
                    dns2 = if (config.dnsForwardingEnabled) config.dnsSecondary else "8.8.4.4"
                )
                socksServer.start()
                activeSocksServers[connectionName] = socksServer

                _connections.update { current ->
                    current.map {
                        if (it.config.connectionName == connectionName)
                            it.copy(status = SshStatus.CONNECTED, localSocksPort = socksPort, lastError = null)
                        else it
                    }
                }
                AppLog.log(
                    "SSH \"$connectionName\": [4/4] conectado — proxy SOCKS5 em 127.0.0.1:$socksPort",
                    AppLog.Level.SUCCESS
                )
            } catch (e: Exception) {
                markError(connectionName, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    suspend fun disconnect(connectionName: String) {
        activeSocksServers.remove(connectionName)?.stop()
        activeSlowDnsClients.remove(connectionName)?.stop()
        activeUdpGwClients.remove(connectionName)?.stop()
        withContext(Dispatchers.IO) {
            runCatching { activeClients.remove(connectionName)?.disconnect() }
        }
        markStatus(connectionName, SshStatus.DISCONNECTED)
        AppLog.log("SSH \"$connectionName\" desconectado", AppLog.Level.INFO)
    }

    private suspend fun openDirectChannel(client: SSHClient, destHost: String, destPort: Int): Pair<InputStream, OutputStream>? {
        if (!client.isConnected || !client.isAuthenticated) {
            val name = activeClients.entries.firstOrNull { it.value == client }?.key
            if (name != null) markError(name, "Conexão SSH perdida (cliente desconectado)")
            return null
        }

        return try {
            channelSemaphore.withPermit {
                val future = channelOpenExecutor.submit(Callable {
                    client.newDirectConnection(destHost, destPort)
                })
                val channel = try {
                    withContext(Dispatchers.IO) {
                        future.get(10, TimeUnit.SECONDS)
                    }
                } catch (e: TimeoutException) {
                    future.cancel(true)
                    throw IOException("Timeout ao abrir canal SSH para $destHost:$destPort")
                } catch (e: Exception) {
                    throw e.cause ?: e
                }
                channel.inputStream to channel.outputStream
            }
        } catch (e: Exception) {
            if (e is TransportException || e.message?.contains("Socket closed") == true) {
                val name = activeClients.entries.firstOrNull { it.value == client }?.key
                if (name != null) markError(name, "Túnel SSH caiu: ${e.message}")
            }
            null
        }
    }

    private suspend fun openUdpOverGateway(
        client: SSHClient,
        connectionName: String,
        gwHost: String,
        gwPort: Int,
        destHost: String,
        destPort: Int,
        onIncoming: (ByteArray) -> Unit
    ): UdpBackendSession? {
        val gwClient = udpGwCreationMutex.withLock {
            activeUdpGwClients[connectionName] ?: run {
                val streams = openDirectChannel(client, gwHost, gwPort)
                if (streams == null) {
                    AppLog.log("SSH \"$connectionName\": falha ao conectar no gateway UDP ($gwHost:$gwPort)", AppLog.Level.ERROR)
                    return@run null
                }
                val (channelIn, channelOut) = streams
                val newClient = UdpGwClient(channelIn, channelOut, managerScope)
                newClient.start()
                activeUdpGwClients[connectionName] = newClient
                AppLog.log("SSH \"$connectionName\": gateway UDP conectado ($gwHost:$gwPort)", AppLog.Level.INFO)
                newClient
            }
        } ?: return null

        return gwClient.openSession(destHost, destPort, onIncoming)
    }

    private suspend fun openUdpOver443Internal(
        client: SSHClient,
        destHost: String,
        destPort: Int,
        onIncoming: (ByteArray) -> Unit
    ): UdpBackendSession? {
        if (destPort != 443) return null

        val streams = openDirectChannel(client, destHost, 443) ?: return null
        val (channelIn, channelOut) = streams

        val readerJob = managerScope.launch(Dispatchers.IO) {
            val buffer = ByteArray(16384)
            try {
                while (isActive) {
                    val read = channelIn.read(buffer)
                    if (read <= 0) break
                    onIncoming(buffer.copyOf(read))
                }
            } catch (e: Exception) {
            } finally {
                runCatching { channelIn.close() }
                runCatching { channelOut.close() }
            }
        }

        return object : UdpBackendSession {
            override suspend fun send(payload: ByteArray) {
                runCatching { channelOut.write(payload); channelOut.flush() }
            }
            override fun close() {
                readerJob.cancel()
                runCatching { channelIn.close() }
                runCatching { channelOut.close() }
            }
        }
    }

    private class ComposedSocket(
        private val config: SshConnectionConfig,
        private val slowDnsLocalPort: Int? = null
    ) : Socket() {

        companion object {
            fun connectPreferringIPv4(host: String, port: Int, timeoutMs: Int): Socket {
                val addresses = try {
                    InetAddress.getAllByName(host)
                        .sortedBy { if (it is java.net.Inet4Address) 0 else 1 }
                } catch (e: Exception) {
                    throw IOException("Falha ao resolver $host: ${e.message}")
                }
                if (addresses.isEmpty()) throw IOException("Não foi possível resolver $host")

                val perAddressTimeout = (timeoutMs / addresses.size).coerceAtLeast(3000)

                var lastError: Exception? = null
                for (address in addresses) {
                    try {
                        val socket = Socket()
                        socket.bind(InetSocketAddress(0))
                        if (!AutomBotVpnService.protectSocket(socket)) {
                            throw IOException("Não consegui isentar a conexão SSH da VPN (protect() falhou)")
                        }
                        socket.connect(InetSocketAddress(address, port), perAddressTimeout)
                        return socket
                    } catch (e: Exception) {
                        lastError = e
                    }
                }
                throw lastError ?: IOException("Não foi possível conectar a $host:$port")
            }
        }
        
        private var delegate: Socket? = null

        override fun connect(endpoint: java.net.SocketAddress) = connect(endpoint, 0)

        override fun connect(endpoint: java.net.SocketAddress, timeout: Int) {
            val addr = endpoint as InetSocketAddress
            val host = addr.hostString
            val port = addr.port
            val effectiveTimeout = if (timeout > 0) timeout else (config.connectionTimeoutSeconds.toIntOrNull() ?: 10) * 1000

            var socket: Socket = if (slowDnsLocalPort != null) {
                val localSocket = Socket()
                localSocket.connect(InetSocketAddress("127.0.0.1", slowDnsLocalPort), effectiveTimeout)
                localSocket
            } else if (config.useProxy) {
                val proxyPort = config.proxyPort.toIntOrNull() ?: 1080
                
                val javaProxyType = when (config.proxyType.name.uppercase()) {
                    "SOCKS", "SOCKS5" -> Proxy.Type.SOCKS
                    else -> Proxy.Type.HTTP
                }

                val proxySocket = Socket(Proxy(javaProxyType, InetSocketAddress(config.proxyHost, proxyPort)))
                if (!AutomBotVpnService.protectSocket(proxySocket)) {
                    throw IOException("Não consegui isentar a conexão de proxy SSH da VPN (protect() falhou)")
                }
                proxySocket.connect(InetSocketAddress(host, port), effectiveTimeout)
                proxySocket
            } else {
                connectPreferringIPv4(host, port, effectiveTimeout)
            }

            if (config.useSslTls) {
                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, null, null)
                val sslSocket = sslContext.socketFactory.createSocket(socket, host, port, true) as SSLSocket
                if (config.sni.isNotBlank()) {
                    val params: SSLParameters = sslSocket.sslParameters
                    params.serverNames = listOf(SNIHostName(config.sni))
                    sslSocket.sslParameters = params
                }
                sslSocket.startHandshake()
                socket = sslSocket
            }

            if (config.usePayload && config.payload.isNotBlank()) {
                val payloadText = config.payload
                    .replace("[crlf]", "\r\n")
                    .replace("[host]", config.server)
                    .replace("[port]", config.port)
                socket.getOutputStream().apply { write(payloadText.toByteArray(Charsets.UTF_8)); flush() }
            }

            delegate = socket
        }

        override fun getInputStream() = delegate?.getInputStream() ?: throw IOException("Socket não conectado")
        override fun getOutputStream() = delegate?.getOutputStream() ?: throw IOException("Socket não conectado")
        override fun isConnected(): Boolean = delegate?.isConnected ?: false
        override fun isClosed(): Boolean = delegate?.isClosed ?: false
        override fun close() { delegate?.close() }
        override fun getInetAddress() = delegate?.inetAddress
        override fun getPort(): Int = delegate?.port ?: -1
        override fun getLocalPort(): Int = delegate?.localPort ?: -1
        override fun setSoTimeout(timeout: Int) { delegate?.soTimeout = timeout }
        override fun setTcpNoDelay(on: Boolean) { delegate?.tcpNoDelay = on }
        override fun shutdownInput() { delegate?.shutdownInput() }
        override fun shutdownOutput() { delegate?.shutdownOutput() }
    }

    private fun composedSocketFactory(config: SshConnectionConfig, slowDnsLocalPort: Int? = null): SocketFactory {
        fun newSocket(): Socket = if (config.useWebSocket) WebSocketBridgeSocket(config) else ComposedSocket(config, slowDnsLocalPort)

        return object : SocketFactory() {
            override fun createSocket(): Socket = newSocket()
            override fun createSocket(host: String?, p: Int): Socket =
                newSocket().apply { connect(InetSocketAddress(host ?: config.server, p)) }
            override fun createSocket(host: String?, p: Int, localHost: InetAddress?, localPort: Int): Socket =
                createSocket(host, p)
            override fun createSocket(host: InetAddress?, p: Int): Socket =
                newSocket().apply { connect(InetSocketAddress(host, p)) }
            override fun createSocket(address: InetAddress?, p: Int, localAddress: InetAddress?, localPort: Int): Socket =
                createSocket(address, p)
        }
    }

    private fun findFreePort(): Int = ServerSocket(0).use { it.localPort }

    private fun markStatus(name: String, status: SshStatus) {
        _connections.update { current -> current.map { if (it.config.connectionName == name) it.copy(status = status) else it } }
    }

    private fun markError(name: String, message: String) {
        AppLog.log("Erro na conexão SSH \"$name\": $message", AppLog.Level.ERROR)
        _connections.update { current ->
            current.map { if (it.config.connectionName == name) it.copy(status = SshStatus.ERROR, lastError = message) else it }
        }
    }
}

enum class SshStatus { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

data class ManagedSshConnection(
    val config: SshConnectionConfig,
    val status: SshStatus = SshStatus.DISCONNECTED,
    val localSocksPort: Int? = null,
    val lastError: String? = null,
    val rxBytes: Long = 0L,
    val txBytes: Long = 0L
)
