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
import java.io.ByteArrayOutputStream
import java.io.PushbackInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
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
        // Aumentado de 63 para 95 canais regulares após teste real mostrar saturação
        // em 64/64 durante navegação pesada/Speedtest. Mantém apenas 1 canal reservado
        // ao gateway UDP e preserva o executor de abertura em 16 threads para evitar
        // a regressão observada quando 32 canais eram abertos simultaneamente.
        private const val REGULAR_CHANNEL_LIMIT = 95
        private const val UDP_GATEWAY_CHANNEL_LIMIT = 1
        private const val TOTAL_CHANNEL_LIMIT = REGULAR_CHANNEL_LIMIT + UDP_GATEWAY_CHANNEL_LIMIT

        // O SSHJ usa Connection.timeoutMs também como prazo máximo para esperar um
        // SSH_MSG_CHANNEL_WINDOW_ADJUST quando a janela remota de um direct-tcpip
        // chega a zero. Quinze segundos se mostrou agressivo em carga real: canais
        // individuais atingiam flow-control enquanto o transporte principal seguia
        // saudável. Mantemos um limite finito, mas alinhado ao timeout de I/O do SSH.
        private const val SSH_CHANNEL_FLOW_CONTROL_TIMEOUT_MS = 30_000
        private const val CHANNEL_CLOSE_WORKERS = 4

        init {
            // Remove o "BC" capado do Android e registra o BouncyCastle completo no
            // lugar — precisa ser feito uma vez, antes de qualquer SSHClient() ser
            // criado. Ver aviso (7) no cabecalho do arquivo.
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

    // 96 canais no total: 95 para navegação/DNS e 1 exclusivo do badvpn-udpgw.
    // O aumento é apenas de capacidade; a concorrência de abertura continua limitada
    // pelo mesmo executor de 16 threads que já se mostrou estável nos testes anteriores.
    private val regularChannelSemaphore = Semaphore(REGULAR_CHANNEL_LIMIT)
    private val udpGatewayChannelSemaphore = Semaphore(UDP_GATEWAY_CHANNEL_LIMIT)
    private val activeChannelLeases = ConcurrentHashMap<SSHClient, MutableSet<DirectChannelLease>>()
    private val waitingChannelRequests = AtomicInteger(0)
    private val flowControlLoggedClients = ConcurrentHashMap.newKeySet<SSHClient>()
    @Volatile private var lastChannelStats = ""
    private val lastChannelStatsLogAt = AtomicLong(0L)

    // A abertura de direct-tcpip e bloqueante. Um pool limitado evita criar dezenas
    // de threads quando Chrome/Android fazem uma rajada de conexoes ao mesmo tempo,
    // mas ainda permite abrir canais em paralelo. Cada abertura continua com timeout
    // individual e fecha o canal se ele terminar depois de o pedido ser abandonado.
    private val channelOpenExecutor = java.util.concurrent.Executors.newFixedThreadPool(16)

    // channel.close() espera a confirmação SSH até Connection.timeoutMs. Como agora
    // esse timeout também precisa tolerar backpressure de janela por até 30 s, não
    // deixamos o fechamento físico ocupar as threads que copiam tráfego dos relays.
    // O permit do canal é devolvido imediatamente; o fechamento acontece aqui.
    private val channelCloseExecutor = java.util.concurrent.Executors.newFixedThreadPool(CHANNEL_CLOSE_WORKERS)

    init {
        loadPersistedProfiles()
        // Mesmo padrao do WireGuardManager: atualiza o trafego real (lido do
        // Socks5Server de cada conexao ativa) a cada poucos segundos, independente de
        // qual tela o usuario esta olhando. Antes o SSH nao mostrava trafego nenhum
        // porque essa contagem simplesmente nao existia.
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
        // commit() sincrono: garante que perfis SSH sobrevivem mesmo que o processo
        // seja encerrado logo em seguida — mesma decisao que o AppLog.kt.
        // Chamado sempre de um CoroutineScope de IO (nunca da thread de UI), entao
        // nao ha risco de ANR.
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

        // Uma reconexao deve começar limpa. Isso também recupera qualquer canal que
        // tenha sobrevivido a um cancelamento inesperado da VPN anterior.
        cleanupConnection(connectionName)
        markStatus(connectionName, SshStatus.CONNECTING)
        AppLog.log("SSH \"$connectionName\": iniciando conexão (${config.describeLayers()})", AppLog.Level.INFO)

        // TUDO daqui pra baixo e I/O de rede bloqueante (sockets, handshake SSH). Isso
        // estava rodando na thread principal por padrao (herdada de quem chama
        // connect()), o que disparava NetworkOnMainThreadException — o Android proibe
        // rede na thread de UI de proposito. withContext(Dispatchers.IO) resolve isso.
        withContext(Dispatchers.IO) {
            var connectingClient: SSHClient? = null
            try {
                val client = SSHClient()
                connectingClient = client
                client.addHostKeyVerifier(PromiscuousVerifier()) // ver aviso (1) no cabecalho do arquivo

            val port = config.port.toIntOrNull() ?: 22
            val connectTimeoutMs = (config.connectionTimeoutSeconds.toIntOrNull() ?: 10)
                .coerceIn(5, 60) * 1000
            client.connectTimeout = connectTimeoutMs
            client.timeout = 30_000
            client.connection.timeoutMs = SSH_CHANNEL_FLOW_CONTROL_TIMEOUT_MS

            // Camada SlowDNS (se ligada): substitui COMPLETAMENTE a etapa de TCP
            // direto/proxy — em vez de conectar no servidor de verdade, conecta numa
            // porta LOCAL que o dnstt-client abre, encaminhando tudo por um túnel
            // disfarçado de tráfego DNS comum. Precisa terminar de subir (e a porta
            // local começar a aceitar conexão) ANTES do handshake SSH começar — por
            // isso roda aqui, fora do ComposedSocket, que só recebe a porta pronta.
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
            
            // CORRECAO: keep-alive padrao (20s) para evitar ser banido pelo servidor
            // por excesso de pacotes heartbeat.
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
                val rawMessage = e.message ?: e.javaClass.simpleName
                val userMessage = if (
                    config.useProxy &&
                    config.proxyType == ProxyType.HTTP &&
                    rawMessage.contains("Invalid Proxy", ignoreCase = true)
                ) {
                    "Proxy HTTP CONNECT recusado. Se o endpoint recebe o Payload diretamente, use 'Gateway Payload' em vez de HTTP CONNECT."
                } else rawMessage
                markError(connectionName, userMessage)
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
            flowControlLoggedClients.remove(client)
            closeChannelsForClient(client)
            withContext(Dispatchers.IO) { runCatching { client.disconnect() } }
        }
        logChannelStatsIfChanged(force = true)
    }

    /**
     * Abre um canal direct-tcpip pelo SSH ate (destHost, destPort) — e isso que faz o
     * SOCKS5 local realmente atravessar o tunel SSH em vez de conectar direto.
     * Ver aviso (2) no cabecalho do arquivo.
     */
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
        var permitAcquired = false
        var permitTransferred = false
        var openedChannel: DirectConnection? = null
        var channelFuture: Future<DirectConnection>? = null
        val abandoned = AtomicBoolean(false)
        val openedByTask = AtomicReference<DirectConnection?>(null)
        return try {
            waitingChannelRequests.incrementAndGet()
            val gotPermit = try {
                withTimeoutOrNull(10_000L) {
                    semaphore.acquire()
                    true
                } ?: false
            } finally {
                waitingChannelRequests.decrementAndGet()
            }
            if (!gotPermit) {
                throw IOException("Todos os canais SSH estão ocupados há 10s (${channelStatsText()})")
            }
            permitAcquired = true

            if (!client.isConnected || !client.isAuthenticated) {
                throw IOException("Conexão SSH encerrada enquanto aguardava vaga para o canal")
            }

            val future = channelOpenExecutor.submit(Callable {
                val channel = client.newDirectConnection(destHost, destPort)
                openedByTask.set(channel)
                if (abandoned.get()) {
                    closeDirectChannelAsync(channel)
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
                openedByTask.getAndSet(null)?.let(::closeDirectChannelAsync)
                throw IOException("Timeout ao abrir canal SSH pra $destHost:$destPort")
            } catch (e: Exception) {
                throw e.cause ?: e
            }
            openedByTask.compareAndSet(channel, null)
            openedChannel = channel

            // A desconexao pode ter acontecido enquanto newDirectConnection estava
            // bloqueado. Nesse caso nao se entrega um canal orfao ao relay.
            if (!client.isConnected || !client.isAuthenticated ||
                activeClients.values.none { it === client }
            ) {
                throw IOException("Conexao SSH encerrada durante a abertura do canal")
            }

            logFlowControlOnce(client, channel)

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

            // Fecha tambem a corrida em que cleanupConnection removeu o conjunto
            // entre o teste acima e o registro da lease.
            if (activeClients.values.none { it === client }) {
                lease.close()
                throw IOException("Conexao SSH removida durante o registro do canal")
            }
            logChannelStatsIfChanged()
            lease.inputStream to lease.outputStream
        } catch (e: CancellationException) {
            abandoned.set(true)
            channelFuture?.cancel(true)
            openedByTask.getAndSet(null)?.let(::closeDirectChannelAsync)
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
            if (permitAcquired && !permitTransferred) {
                abandoned.set(true)
                channelFuture?.cancel(true)
                openedByTask.getAndSet(null)?.let(::closeDirectChannelAsync)
                openedChannel?.let(::closeDirectChannelAsync)
                semaphore.release()
            }
        }
    }

    private fun logFlowControlOnce(client: SSHClient, channel: DirectConnection) {
        if (!flowControlLoggedClients.add(client)) return
        AppLog.log(
            "SSH flow-control: janela local ${channel.getLocalWinSize()}B / pacote ${channel.getLocalMaxPacketSize()}B; " +
                "janela remota ${channel.getRemoteWinSize()}B / pacote ${channel.getRemoteMaxPacketSize()}B; " +
                "timeout WINDOW_ADJUST ${SSH_CHANNEL_FLOW_CONTROL_TIMEOUT_MS / 1000}s",
            AppLog.Level.INFO
        )
    }

    private fun closeDirectChannelAsync(channel: DirectConnection) {
        channelCloseExecutor.execute {
            runCatching { channel.close() }
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
        return "ativos $active/$TOTAL_CHANNEL_LIMIT, aguardando ${waitingChannelRequests.get()}"
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
     *
     * No SSHJ, fechar ChannelOutputStream envia SSH_MSG_CHANNEL_EOF e preserva a
     * direcao de entrada. A V4 interceptava esse close() e fechava o canal inteiro,
     * cortando respostas ainda em transito e impedindo a drenagem TCP correta.
     */
    private inner class DirectChannelLease(
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
                // ChannelOutputStream.close() faz o half-close correto no SSH:
                // descarrega o buffer e envia CHANNEL_EOF sem matar a volta.
                runCatching { channel.outputStream.close() }
            }
        }

        private fun closeChannel() {
            if (closed.compareAndSet(false, true)) {
                // A vaga volta imediatamente. O CHANNEL_CLOSE físico pode esperar
                // confirmação remota por até o timeout de flow-control e por isso é
                // processado fora das threads de relay.
                onClosed()
                closeDirectChannelAsync(channel)
            }
        }
    }

    /**
     * Tenta "traduzir" um pacote UDP destinado a porta 443 numa conexao TCP normal
     * pro mesmo destino, atraves de um canal direct-tcpip do SSH. Funciona porque a
     * imensa maioria dos servicos que oferecem QUIC/HTTP3 (que roda sobre UDP)
     * TAMBEM aceitam a conexao tradicional TLS-sobre-TCP na mesma porta 443 como
     * alternativa — entao o app do outro lado normalmente consegue continuar
     * funcionando, so que sem os beneficios de performance do QUIC.
     *
     * IMPORTANTE: isso NAO e tunelamento de UDP de verdade. E um jeitinho que so
     * cobre esse caso especifico (porta 443, servico com fallback TCP). UDP genuino
     * (jogos, chamadas de voz especificas, qualquer coisa que so fale UDP) nao tem
     * solucao possivel usando SSH puro — o protocolo em si nao suporta isso, e nao
     * ha nada que o cliente consiga fazer a respeito. Pra qualquer porta diferente
     * de 443, retorna null — o Socks5Server entende isso como "esse destino nao e
     * suportado" e loga uma vez so (ver Socks5Server.kt/Tun2SocksEngine.kt antigo).
     */
    /**
     * Abre (ou reaproveita, se já tiver uma pra essa conexão) o UdpGwClient
     * compartilhado — ver UdpGwClient.kt pro protocolo em si (conferido contra o
     * código-fonte oficial do badvpn). Uma única conexão TCP com o servidor udpgw
     * (alcançada por um canal direct-tcpip do SSH) atende TODOS os destinos UDP
     * dessa conexão SSH, multiplexados por conid — não é "uma conexão nova por
     * destino" como os outros protocolos.
     *
     * CORRECAO: essa função precisa ser suspend (chama openDirectChannel, que
     * também é suspend) — por isso usa Mutex (de coroutines) pra proteger a
     * criação do cliente compartilhado, não @Synchronized (que é da JVM/threads e
     * não pode ser usado numa suspend fun — travava a compilação).
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

    /**
     * CORRECAO: log real do usuario mostrou ERR_QUIC_PROTOCOL_ERROR no navegador —
     * QUIC (usado por padrao pelo Chrome em varios sites, inclusive Google) e UDP de
     * verdade, com cada pacote sendo uma unidade propria e delimitada. Essa funcao
     * pegava os bytes de cada "datagrama" e so escrevia direto num canal SSH
     * direct-tcpip (TCP, um FLUXO continuo sem limite nenhum entre pedacos) — dois
     * pacotes QUIC podiam ser fundidos num so, ou um pacote cortado ao meio, e o
     * lado que recebe (o navegador, aqui) tenta interpretar isso como QUIC valido e
     * da erro de protocolo. Funcionava por acidente pra coisas que sao TCP/TLS
     * disfarcado de UDP (por isso o comentario antigo dizia "funciona na pratica"),
     * mas quebra qualquer protocolo que seja UDP de verdade — QUIC sendo o mais
     * comum. Sem um jeito real de fazer UDP sem o Gateway UDP dedicado (badvpn-udpgw,
     * so ligado quando o usuario ativa udpForwardEnabled), a opcao mais segura e
     * RECUSAR aqui: a sessao UDP simplesmente nao abre, o pacote e descartado sem
     * resposta, e o Chrome (que ja tenta TCP em paralelo/como fallback pra todo
     * QUIC) cai pro HTTPS normal por conta propria — que ja confirmamos funcionando.
     * Preferimos um "sem resposta" limpo a um "resposta corrompida" que gera erro
     * na tela.
     */
    private suspend fun openUdpOver443Internal(
        client: SSHClient,
        destHost: String,
        destPort: Int,
        onIncoming: (ByteArray) -> Unit
    ): UdpBackendSession? = null

    /**
     * ComposedSocket: um Socket "decorador" que so faz a conexao de verdade dentro do
     * proprio connect() — em vez de depender de qual overload de SocketFactory.createSocket
     * o sshj chama. CORRECAO: descobrimos (pelo erro real "precisa de host/porta") que o
     * sshj chama o createSocket() SEM ARGUMENTOS primeiro, e só depois chama socket.connect(
     * endereco, timeout) nele — diferente do que eu tinha assumido antes (que ele chamava
     * createSocket(host, port) direto). Com esse Socket decorador, nao importa qual dos
     * dois jeitos o sshj usa: o connect() sempre recebe host/porta reais e faz a
     * composicao completa das camadas (proxy -> payload -> TLS) nesse momento.
     */
    private class ComposedSocket(
        private val config: SshConnectionConfig,
        private val slowDnsLocalPort: Int? = null
    ) : Socket() {

        companion object {
            /**
             * Resolve [host] e tenta conectar nos enderecos IPv4 primeiro, so caindo
             * pra IPv6 se nenhum IPv4 funcionar. Ver correcao explicada em connect().
             */
            fun connectPreferringIPv4(host: String, port: Int, timeoutMs: Int): Socket {
                val addresses = try {
                    java.net.InetAddress.getAllByName(host)
                        .sortedBy { if (it is java.net.Inet4Address) 0 else 1 }
                } catch (e: Exception) {
                    throw java.io.IOException("Falha ao resolver $host: ${e.message}")
                }
                if (addresses.isEmpty()) throw java.io.IOException("Não foi possível resolver $host")

                // Divide o timeout total entre os enderecos tentados (com um piso de 3s)
                // — assim, numa rede so-IPv6, nao ficamos esperando o timeout INTEIRO no
                // IPv4 (que nem tem rota) antes de tentar o IPv6 que de fato funciona.
                val perAddressTimeout = (timeoutMs / addresses.size).coerceAtLeast(3000)

                var lastError: Exception? = null
                for (address in addresses) {
                    try {
                        val socket = Socket()
                        // Ver comentario equivalente em VlessTransport.kt: forca o fd
                        // nativo existir antes do protect(), que precisa dele pra
                        // funcionar (mesmo que aqui, hoje, o protect() costume ser
                        // pulado por nao haver VPN ativa ainda nesse momento — deixamos
                        // consistente pra quando essa conexao acontecer com VPN ja ativa,
                        // ex: reconexao apos queda).
                        socket.bind(InetSocketAddress(0))
                        if (!com.autombot.client.core.AutomBotVpnService.protectSocket(socket)) {
                            throw java.io.IOException("Não consegui isentar a conexão SSH da VPN (protect() falhou)")
                        }
                        socket.connect(InetSocketAddress(address, port), perAddressTimeout)
                        return socket
                    } catch (e: Exception) {
                        lastError = e
                        // tenta o proximo endereco (ex: IPv4 falhou, tenta IPv6, ou vice-versa)
                    }
                }
                throw lastError ?: java.io.IOException("Não foi possível conectar a $host:$port")
            }
        }
        private var delegate: Socket? = null
        private var delegateInput: InputStream? = null

        override fun connect(endpoint: java.net.SocketAddress) = connect(endpoint, 0)

        override fun connect(endpoint: java.net.SocketAddress, timeout: Int) {
            val addr = endpoint as InetSocketAddress
            val host = addr.hostString
            val port = addr.port
            val effectiveTimeout = if (timeout > 0) timeout else (config.connectionTimeoutSeconds.toIntOrNull() ?: 10) * 1000

            var socket: Socket = if (slowDnsLocalPort != null) {
                // SlowDNS ligado: ignora completamente proxy/conexao direta pro
                // servidor de verdade — conecta na porta LOCAL que o dnstt-client ja
                // deixou pronta (ver SshTunnelManager.connect()). O proprio
                // dnstt-server, do lado do VPS, ja sabe pra onde encaminhar de
                // verdade; o app nao precisa (nem pode) saber disso daqui.
                val localSocket = Socket()
                localSocket.connect(InetSocketAddress("127.0.0.1", slowDnsLocalPort), effectiveTimeout)
                localSocket
            } else if (config.useProxy && config.proxyType == ProxyType.PAYLOAD_GATEWAY) {
                val gatewayHost = config.proxyHost.trim()
                val gatewayPort = config.proxyPort.toIntOrNull()
                    ?: throw java.io.IOException("Porta do Gateway Payload inválida")
                if (gatewayHost.isBlank()) {
                    throw java.io.IOException("Host do Gateway Payload não informado")
                }
                if (!config.usePayload || config.payload.isBlank()) {
                    throw java.io.IOException("Gateway Payload exige a camada Payload ativada")
                }
                AppLog.log(
                    "SSH: abrindo Gateway Payload em $gatewayHost:$gatewayPort para o SSH lógico $host:$port (sem HTTP CONNECT)",
                    AppLog.Level.INFO
                )
                connectPreferringIPv4(gatewayHost, gatewayPort, effectiveTimeout)
            } else if (config.useProxy) {
                val proxyPort = config.proxyPort.toIntOrNull() ?: 1080
                val proxyType = if (config.proxyType == ProxyType.SOCKS5) Proxy.Type.SOCKS else Proxy.Type.HTTP
                val proxySocket = Socket(Proxy(proxyType, InetSocketAddress(config.proxyHost, proxyPort)))
                if (!com.autombot.client.core.AutomBotVpnService.protectSocket(proxySocket)) {
                    throw java.io.IOException("Não consegui isentar a conexão de proxy SSH da VPN (protect() falhou)")
                }
                // SOCKS5/HTTP são proxies tradicionais. HTTP usa CONNECT e precisa
                // concluir esse handshake antes da camada Payload.
                proxySocket.connect(InetSocketAddress(host, port), effectiveTimeout)
                proxySocket
            } else {
                // CORRECAO: sem proxy, resolviamos o host e conectavamos direto no
                // primeiro endereco que o DNS devolvesse — em redes onde o IPv6 esta
                // quebrado/incompleto (comum em rede movel), isso gerava um timeout de
                // 10s tentando um endereco IPv6 que nunca respondia, mesmo o IPv4
                // funcionando normalmente. Agora tenta os enderecos IPv4 primeiro, e so
                // cai pra IPv6 se nenhum IPv4 funcionar.
                connectPreferringIPv4(host, port, effectiveTimeout)
            }

            // O socket SSH transporta todos os canais multiplexados. Nagle e buffers
            // pequenos aqui afetam a VPN inteira, por isso ajustamos antes de TLS/
            // payload. Em Socket de proxy/SlowDNS a mesma configuracao e segura.
            tuneTransportSocket(socket)

            // CORRECAO IMPORTANTE: TLS tem que vir ANTES do payload, nao depois.
            // Servidores/CDNs que recebem TLS na porta 443 (ex: CloudFront) esperam um
            // ClientHello TLS como os PRIMEIROS bytes da conexao — se a gente manda o
            // payload cru (texto tipo "GET / HTTP/1.1...") antes do handshake TLS
            // comecar, o servidor recebe lixo no lugar do ClientHello e derruba a
            // conexao na hora (exatamente o "Connection reset" reportado). A ordem
            // certa e: TCP -> TLS (se ligado) -> payload (se ligado, agora ja dentro
            // do tunel TLS, criptografado) -> handshake SSH.
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

            var preparedInput: InputStream? = null
            if (config.usePayload && config.payload.isNotBlank()) {
                val payloadText = config.payload
                    .replace("[crlf]", "\r\n")
                    .replace("[host]", config.server)
                    .replace("[port]", config.port)
                    .replace("[proxy_host]", config.proxyHost)
                    .replace("[proxy_port]", config.proxyPort)
                socket.getOutputStream().apply { write(payloadText.toByteArray(Charsets.UTF_8)); flush() }

                // Alguns gateways respondem 101/2xx antes de liberar o fluxo SSH.
                // Consome somente o cabeçalho HTTP do gateway e preserva o banner SSH
                // seguinte. Se a resposta já começar com SSH, os bytes são devolvidos.
                if (looksLikeHttpPayload(payloadText)) {
                    preparedInput = consumeOptionalHttpGatewayPreface(socket)
                }
            }

            delegate = socket
            delegateInput = preparedInput ?: socket.getInputStream()
        }

        private fun looksLikeHttpPayload(payload: String): Boolean {
            val firstLine = payload.lineSequence().firstOrNull()?.trim().orEmpty().uppercase()
            return firstLine.startsWith("GET ") ||
                firstLine.startsWith("POST ") ||
                firstLine.startsWith("HEAD ") ||
                firstLine.startsWith("CONNECT ") ||
                firstLine.startsWith("OPTIONS ")
        }

        private fun consumeOptionalHttpGatewayPreface(socket: Socket): InputStream {
            val input = PushbackInputStream(socket.getInputStream(), 8)
            val previousTimeout = runCatching { socket.soTimeout }.getOrDefault(0)
            val prefix = ByteArray(5)
            var count = 0
            var httpDetected = false
            try {
                socket.soTimeout = 1_500
                while (count < prefix.size) {
                    val read = input.read(prefix, count, prefix.size - count)
                    if (read < 0) break
                    count += read
                }
                val start = String(prefix, 0, count, Charsets.US_ASCII)
                if (count < 5 || start != "HTTP/") {
                    if (count > 0) input.unread(prefix, 0, count)
                    return input
                }
                httpDetected = true

                val header = ByteArrayOutputStream()
                header.write(prefix, 0, count)
                val marker = byteArrayOf(13, 10, 13, 10)
                var markerIndex = 0
                while (header.size() < 32 * 1024 && markerIndex < marker.size) {
                    val value = input.read()
                    if (value < 0) break
                    header.write(value)
                    markerIndex = if (value == marker[markerIndex].toInt()) {
                        markerIndex + 1
                    } else if (value == marker[0].toInt()) {
                        1
                    } else {
                        0
                    }
                }
                if (markerIndex != marker.size) {
                    throw java.io.IOException("Resposta HTTP do gateway incompleta")
                }

                val headerText = header.toString(Charsets.ISO_8859_1.name())
                val statusLine = headerText.lineSequence().firstOrNull()?.trim().orEmpty()
                val statusCode = statusLine.split(Regex("\\s+")).getOrNull(1)?.toIntOrNull()
                    ?: throw java.io.IOException("Resposta HTTP inválida do gateway: $statusLine")
                if (statusCode != 101 && statusCode !in 200..299) {
                    throw java.io.IOException("Gateway recusou o Payload: $statusLine")
                }
                AppLog.log("SSH: gateway aceitou o Payload ($statusLine)", AppLog.Level.SUCCESS)
                return input
            } catch (e: SocketTimeoutException) {
                if (httpDetected) {
                    throw java.io.IOException("Timeout aguardando o cabeçalho HTTP completo do gateway", e)
                }
                if (count > 0) input.unread(prefix, 0, count)
                return input
            } finally {
                runCatching { socket.soTimeout = previousTimeout }
            }
        }

        private fun tuneTransportSocket(socket: Socket) {
            runCatching { socket.tcpNoDelay = true }
            runCatching { socket.keepAlive = true }
            runCatching { socket.sendBufferSize = 256 * 1024 }
            runCatching { socket.receiveBufferSize = 256 * 1024 }
        }

        override fun getInputStream() = delegateInput ?: delegate?.getInputStream() ?: throw java.io.IOException("Socket não conectado")
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

    /**
     * SocketFactory que devolve sempre um ComposedSocket — funciona tanto se o sshj
     * chamar createSocket() e conectar depois, quanto se chamar createSocket(host, port)
     * direto (ver ComposedSocket acima pra entender por que essa mudanca foi necessaria).
     */
    private fun composedSocketFactory(config: SshConnectionConfig, slowDnsLocalPort: Int? = null): SocketFactory {
        // Se WebSocket estiver ligado, o transporte inteiro passa a ser a ponte
        // WebSocket (que trata TLS via "wss://" internamente) — ver WebSocketBridgeSocket.kt.
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
