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
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.DirectConnection
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.json.JSONArray
import java.io.File
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.net.SocketFactory
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket

/**
 * Nucleo real da conexao SSH (sshj + servidor SOCKS5 proprio).
 *
 * IMPORTANTE — leia antes de mexer:
 *  1) Verificacao de host key: por enquanto usa PromiscuousVerifier (aceita qualquer
 *     host key, sem checar). Isso e inseguro contra man-in-the-middle — aceitavel pra
 *     validar que a conexao funciona, mas precisa de verificacao de verdade
 *     (known_hosts ou pin da chave do servidor) antes de qualquer uso real. Ver
 *     SPEC.md secao 22 para o TODO detalhado.
 *  2) O metodo de abrir canal direct-tcpip (`openDirectChannel`) e a parte de MAIOR
 *     risco de erro de compilacao neste arquivo. Se o Android Studio acusar erro ali,
 *     me manda a mensagem exata que eu ajusto na hora. O resto do arquivo usa
 *     SocketFactory (API padrao do javax.net.*, nao especifica do sshj) para os modos
 *     proxy/payload/TLS, que e bem mais solido.
 *  3) WEBSOCKET ainda NAO esta implemetado — retorna erro claro em vez de tentar
 *     algo fragil sem testar.
 *  4) "Desativar TCP delay" (config.disableTcpDelay) ainda NAO esta ligado a nada —
 *     e um TODO.
 *  5) Isso conecta de verdade e sobe um proxy SOCKS5 LOCAL funcional — mas ainda NAO
 *     roteia o trafego de todo o aparelho por ele (precisa de VpnService + tun2socks,
 *     projeto a parte — ver SPEC.md secao 22).
 *  6) Os PERFIS (nao o status de conexao) agora persistem em SharedPreferences — ver
 *     persistProfiles()/loadPersistedProfiles() abaixo. Continuam no aparelho mesmo
 *     depois de fechar o app.
 *  7) O Android vem com um provedor "BC" (BouncyCastle) proprio e CAPADO — sem X25519
 *     e outros algoritmos que o sshj precisa pra negociar a conexao com muitos
 *     servidores SSH modernos. Por isso o companion object abaixo troca esse "BC" pelo
 *     BouncyCastle de verdade assim que a classe e carregada. Sem isso, a conexao
 *     falha com "no such algorithm: X25519 for provider BC" mesmo com a config certa.
 */
class SshTunnelManager(context: Context) {

    companion object {
        // 192 canais por conexão/gerenciador é um teto de segurança, não um limitador
        // de banda. Os logs reais mostraram rajadas acima de 100 solicitações, então
        // 64 era baixo demais para navegadores e serviços Android modernos.
        private const val REGULAR_CHANNEL_LIMIT = 191
        private const val UDP_GATEWAY_CHANNEL_LIMIT = 1
        private const val TOTAL_CHANNEL_LIMIT = REGULAR_CHANNEL_LIMIT + UDP_GATEWAY_CHANNEL_LIMIT
        private const val REGULAR_CHANNEL_OPEN_LIMIT = 32
        private const val CHANNEL_PERMIT_WAIT_TIMEOUT_MS = 20_000L

        init {
            java.security.Security.removeProvider("BC")
            java.security.Security.insertProviderAt(BouncyCastleProvider(), 1)
        }
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("autombot_ssh", Context.MODE_PRIVATE)

    private val _connections = MutableStateFlow<List<ManagedSshConnection>>(emptyList())
    val connections: StateFlow<List<ManagedSshConnection>> = _connections

    private val activeClients = ConcurrentHashMap<String, SSHClient>()
    private val activeSocksServers = ConcurrentHashMap<String, Socks5Server>()
    private val activeSlowDnsClients = ConcurrentHashMap<String, com.autombot.client.protocols.slowdns.SlowDnsClient>()
    private val activeUdpGwClients = ConcurrentHashMap<String, UdpGwClient>()
    private val udpGwCreationMutex = Mutex()
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // V6: mantém um teto de segurança alto para canais vivos, mas separa a pressão de
    // abertura. Antes, um pedido adquiria o permit vitalício e só depois entrava num
    // pool de 16 threads. Em rajadas, dezenas de pedidos ficavam parados na fila do
    // executor segurando permits sem sequer terem começado a abrir o direct-tcpip.
    // Isso fazia o app dizer "todos os canais ocupados" mesmo com bem menos leases
    // realmente ativos. Agora no máximo 32 aberturas regulares entram nessa fase.
    private val regularChannelSemaphore = Semaphore(REGULAR_CHANNEL_LIMIT)
    private val udpGatewayChannelSemaphore = Semaphore(UDP_GATEWAY_CHANNEL_LIMIT)
    private val regularChannelOpeningSemaphore = Semaphore(REGULAR_CHANNEL_OPEN_LIMIT)
    private val activeChannelLeases = ConcurrentHashMap<SSHClient, MutableSet<DirectChannelLease>>()
    private val waitingChannelRequests = AtomicInteger(0)
    private val openingChannelRequests = AtomicInteger(0)
    @Volatile private var lastChannelStats = ""
    private val lastChannelStatsLogAt = AtomicLong(0L)

    // O pool regular acompanha o limite de aberturas em voo. O gateway UDP usa um
    // executor dedicado para continuar conseguindo reconectar mesmo durante uma
    // rajada TCP grande.
    private val channelOpenExecutor = java.util.concurrent.Executors.newFixedThreadPool(REGULAR_CHANNEL_OPEN_LIMIT)
    private val udpGatewayOpenExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

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
                logChannelStatsIfChanged()
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

        cleanupConnection(connectionName)
        markStatus(connectionName, SshStatus.CONNECTING)
        AppLog.log("SSH \"$connectionName\": iniciando conexão (${config.describeLayers()})", AppLog.Level.INFO)

        withContext(Dispatchers.IO) {
            var connectingClient: SSHClient? = null
            try {
                val client = SSHClient()
                connectingClient = client
                client.addHostKeyVerifier(PromiscuousVerifier())

                val port = config.port.toIntOrNull() ?: 22
                val connectTimeoutMs = (config.connectionTimeoutSeconds.toIntOrNull() ?: 10)
                    .coerceIn(5, 60) * 1000
                client.connectTimeout = connectTimeoutMs
                client.timeout = 30_000
                client.connection.timeoutMs = 15_000

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
                    dns2 = if (config.dnsForwardingEnabled) config.dnsSecondary else "8.8.4.4",
                    logPrefix = "SSH \"$connectionName\""
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
                cleanupConnection(connectionName)
                connectingClient?.let { failedClient ->
                    closeChannelsForClient(failedClient)
                    runCatching { failedClient.disconnect() }
                }
                markError(connectionName, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    suspend fun disconnect(connectionName: String) {
        cleanupConnection(connectionName)
        markStatus(connectionName, SshStatus.DISCONNECTED)
        AppLog.log("SSH \"$connectionName\" desconectado", AppLog.Level.INFO)
    }

    private suspend fun cleanupConnection(connectionName: String) {
        activeSocksServers.remove(connectionName)?.stop()
        activeSlowDnsClients.remove(connectionName)?.stop()
        activeUdpGwClients.remove(connectionName)?.stop()
        val client = activeClients.remove(connectionName)
        if (client != null) {
            closeChannelsForClient(client)
            withContext(Dispatchers.IO) { runCatching { client.disconnect() } }
        }
        logChannelStatsIfChanged(force = true)
    }

    /**
     * Abre um canal direct-tcpip pelo SSH ate (destHost, destPort).
     * O permit do semaforo permanece adquirido durante toda a vida do canal e so e
     * devolvido quando os streams forem fechados.
     */
    private suspend fun openDirectChannel(
        client: SSHClient,
        destHost: String,
        destPort: Int,
        reservedForUdpGateway: Boolean = false
    ): Pair<InputStream, OutputStream>? {
        if (!client.isConnected || !client.isAuthenticated) {
            val name = activeClients.entries.firstOrNull { it.value == client }?.key
            if (name != null) markError(name, "Conexão SSH perdida (cliente desconectado)")
            return null
        }

        val semaphore = if (reservedForUdpGateway) udpGatewayChannelSemaphore else regularChannelSemaphore
        val openingSemaphore = if (reservedForUdpGateway) null else regularChannelOpeningSemaphore
        val executor = if (reservedForUdpGateway) udpGatewayOpenExecutor else channelOpenExecutor

        var openingPermitAcquired = false
        var permitAcquired = false
        var permitTransferred = false
        var openingCounted = false
        var openedChannel: DirectConnection? = null
        var channelFuture: Future<DirectConnection>? = null
        val abandoned = AtomicBoolean(false)
        val openedByTask = AtomicReference<DirectConnection?>(null)

        return try {
            waitingChannelRequests.incrementAndGet()
            try {
                if (openingSemaphore != null) {
                    val gotOpeningSlot = withTimeoutOrNull(CHANNEL_PERMIT_WAIT_TIMEOUT_MS) {
                        openingSemaphore.acquire()
                        true
                    } ?: false
                    if (!gotOpeningSlot) {
                        throw IOException(
                            "Fila de abertura SSH ocupada há ${CHANNEL_PERMIT_WAIT_TIMEOUT_MS / 1000}s (${channelStatsText()})"
                        )
                    }
                    openingPermitAcquired = true
                }

                val gotPermit = withTimeoutOrNull(CHANNEL_PERMIT_WAIT_TIMEOUT_MS) {
                    semaphore.acquire()
                    true
                } ?: false
                if (!gotPermit) {
                    throw IOException(
                        "Todos os canais SSH estão ocupados há ${CHANNEL_PERMIT_WAIT_TIMEOUT_MS / 1000}s (${channelStatsText()})"
                    )
                }
                permitAcquired = true
            } finally {
                waitingChannelRequests.decrementAndGet()
            }

            if (!client.isConnected || !client.isAuthenticated) {
                throw IOException("Conexão SSH encerrada enquanto aguardava vaga para o canal")
            }

            openingChannelRequests.incrementAndGet()
            openingCounted = true

            val future = executor.submit(Callable {
                val channel = client.newDirectConnection(destHost, destPort)
                openedByTask.set(channel)
                if (abandoned.get()) {
                    runCatching { channel.close() }
                    throw IOException("Abertura do canal terminou depois do timeout")
                }
                channel
            })
            channelFuture = future
            val channel = try {
                runInterruptible(Dispatchers.IO) {
                    future.get(10, TimeUnit.SECONDS)
                }
            } catch (e: TimeoutException) {
                abandoned.set(true)
                future.cancel(true)
                runCatching { openedByTask.getAndSet(null)?.close() }
                throw IOException("Timeout ao abrir canal SSH pra $destHost:$destPort")
            } catch (e: Exception) {
                throw e.cause ?: e
            }
            openedByTask.compareAndSet(channel, null)
            openedChannel = channel

            if (!client.isConnected || !client.isAuthenticated ||
                activeClients.values.none { it === client }
            ) {
                throw IOException("Conexao SSH encerrada durante a abertura do canal")
            }

            lateinit var lease: DirectChannelLease
            lease = DirectChannelLease(channel) {
                activeChannelLeases[client]?.let { leases ->
                    leases.remove(lease)
                    if (leases.isEmpty()) activeChannelLeases.remove(client, leases)
                }
                semaphore.release()
                logChannelStatsIfChanged()
            }
            activeChannelLeases.computeIfAbsent(client) { ConcurrentHashMap.newKeySet() }.add(lease)
            permitTransferred = true

            if (activeClients.values.none { it === client }) {
                lease.close()
                throw IOException("Conexao SSH removida durante o registro do canal")
            }
            logChannelStatsIfChanged()
            lease.inputStream to lease.outputStream
        } catch (e: CancellationException) {
            abandoned.set(true)
            channelFuture?.cancel(true)
            runCatching { openedByTask.getAndSet(null)?.close() }
            throw e
        } catch (e: Exception) {
            val detail = "${e.javaClass.simpleName}: ${e.message}"
            AppLog.log(
                "SSH: falha ao abrir canal para $destHost:$destPort ($detail)",
                AppLog.Level.ERROR
            )

            if (e is TransportException || e.message?.contains("Socket closed") == true) {
                val name = activeClients.entries.firstOrNull { it.value == client }?.key
                if (name != null) markError(name, "Túnel SSH caiu: ${e.message}")
            }
            null
        } finally {
            if (openingCounted) openingChannelRequests.decrementAndGet()
            if (openingPermitAcquired) openingSemaphore?.release()
            if (permitAcquired && !permitTransferred) {
                abandoned.set(true)
                channelFuture?.cancel(true)
                runCatching { openedByTask.getAndSet(null)?.close() }
                runCatching { openedChannel?.close() }
                semaphore.release()
            }
        }
    }

    private fun closeChannelsForClient(client: SSHClient) {
        val leases = activeChannelLeases.remove(client)?.toList().orEmpty()
        leases.forEach { it.close() }
        if (leases.isNotEmpty()) {
            AppLog.log("SSH: ${leases.size} canal(is) ativo(s) fechado(s) durante a desconexão", AppLog.Level.INFO)
        }
    }

    private fun channelStatsText(): String {
        val active = activeChannelLeases.values.sumOf { it.size }
        return "ativos $active/$TOTAL_CHANNEL_LIMIT, abrindo ${openingChannelRequests.get()}, aguardando ${waitingChannelRequests.get()}"
    }

    private fun logChannelStatsIfChanged(force: Boolean = false) {
        val stats = channelStatsText()
        val now = android.os.SystemClock.elapsedRealtime()
        val enoughTimePassed = now - lastChannelStatsLogAt.get() >= 5_000L
        if (force || (stats != lastChannelStats && enoughTimePassed)) {
            lastChannelStats = stats
            lastChannelStatsLogAt.set(now)
            AppLog.log("SSH canais: $stats", AppLog.Level.INFO)
        }
    }

    /**
     * Mantem separado o EOF de escrita do fechamento total do canal.
     */
    private class DirectChannelLease(
        private val channel: DirectConnection,
        private val onClosed: () -> Unit
    ) {
        private val closed = AtomicBoolean(false)
        private val outputClosed = AtomicBoolean(false)

        val inputStream: InputStream = object : FilterInputStream(channel.inputStream) {
            override fun close() = closeChannel()
        }

        val outputStream: OutputStream = object : FilterOutputStream(channel.outputStream) {
            override fun close() = closeOutput()
        }

        fun close() = closeChannel()

        private fun closeOutput() {
            if (!closed.get() && outputClosed.compareAndSet(false, true)) {
                runCatching { channel.outputStream.close() }
            }
        }

        private fun closeChannel() {
            if (closed.compareAndSet(false, true)) {
                onClosed()
                runCatching { channel.close() }
            }
        }
    }

    /**
     * Abre (ou reaproveita, se já tiver uma pra essa conexão) o UdpGwClient
     * compartilhado. Uma única conexão TCP com o servidor udpgw atende todos os
     * destinos UDP dessa conexão SSH, multiplexados por conid.
     */
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
            val existing = activeUdpGwClients[connectionName]
            if (existing != null && !existing.isClosed()) {
                existing
            } else run {
                if (existing != null) {
                    activeUdpGwClients.remove(connectionName)
                    existing.stop()
                    AppLog.log(
                        "SSH \"$connectionName\": gateway UDP anterior encerrou; reconectando",
                        AppLog.Level.INFO
                    )
                }
                val streams = openDirectChannel(client, gwHost, gwPort, reservedForUdpGateway = true)
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
    ): UdpBackendSession? = null

    /**
     * SocketFactory composto usado pelo SSH para conexão direta, proxy, payload,
     * TLS, SlowDNS e WebSocket.
     */
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

    private class ComposedSocket(
        private val config: SshConnectionConfig,
        private val slowDnsLocalPort: Int? = null
    ) : Socket() {

        companion object {
            fun connectPreferringIPv4(host: String, port: Int, timeoutMs: Int): Socket {
                val addresses = try {
                    java.net.InetAddress.getAllByName(host)
                        .sortedBy { if (it is java.net.Inet4Address) 0 else 1 }
                } catch (e: Exception) {
                    throw java.io.IOException("Falha ao resolver $host: ${e.message}")
                }
                if (addresses.isEmpty()) throw java.io.IOException("Não foi possível resolver $host")

                val perAddressTimeout = (timeoutMs / addresses.size).coerceAtLeast(3000)
                var lastError: Exception? = null
                for (address in addresses) {
                    try {
                        val socket = Socket()
                        socket.bind(InetSocketAddress(0))
                        if (!com.autombot.client.core.AutomBotVpnService.protectSocket(socket)) {
                            throw java.io.IOException("Não consegui isentar a conexão SSH da VPN (protect() falhou)")
                        }
                        socket.connect(InetSocketAddress(address, port), perAddressTimeout)
                        return socket
                    } catch (e: Exception) {
                        lastError = e
                    }
                }
                throw lastError ?: java.io.IOException("Não foi possível conectar a $host:$port")
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
                val proxyType = if (config.proxyType == ProxyType.SOCKS5) Proxy.Type.SOCKS else Proxy.Type.HTTP
                val proxySocket = Socket(Proxy(proxyType, InetSocketAddress(config.proxyHost, proxyPort)))
                if (!com.autombot.client.core.AutomBotVpnService.protectSocket(proxySocket)) {
                    throw java.io.IOException("Não consegui isentar a conexão de proxy SSH da VPN (protect() falhou)")
                }
                proxySocket.connect(InetSocketAddress(host, port), effectiveTimeout)
                proxySocket
            } else {
                connectPreferringIPv4(host, port, effectiveTimeout)
            }

            tuneTransportSocket(socket)

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

        private fun tuneTransportSocket(socket: Socket) {
            runCatching { socket.tcpNoDelay = true }
            runCatching { socket.keepAlive = true }
            runCatching { socket.sendBufferSize = 256 * 1024 }
            runCatching { socket.receiveBufferSize = 256 * 1024 }
        }

        override fun getInputStream() = delegate?.getInputStream() ?: throw java.io.IOException("Socket não conectado")
        override fun getOutputStream() = delegate?.getOutputStream() ?: throw java.io.IOException("Socket não conectado")
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