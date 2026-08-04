package com.autombot.client.ui

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.autombot.client.core.AutomBotVpnService
import com.autombot.client.protocols.wireguard.TunnelStatus
import com.autombot.client.protocols.wireguard.WireGuardManager
import com.autombot.client.protocols.ssh.SshStatus
import com.autombot.client.protocols.ssh.SshTunnelManager
import com.autombot.client.protocols.openvpn.configFile
import com.autombot.client.protocols.shadowsocks.ShadowsocksStatus
import com.autombot.client.protocols.trojan.TrojanStatus
import com.autombot.client.protocols.vless.VlessStatus
import com.autombot.client.protocols.vmess.VmessStatus
import com.autombot.client.ui.dashboard.*
import com.autombot.client.ui.dashboard.XraySelectScreen
import com.autombot.client.ui.manual.*
import com.autombot.client.ui.openvpn.OpenVpnScreen
import com.autombot.client.ui.openvpn.OpenVpnAddScreen
import com.autombot.client.ui.ssh.SshScreen
import com.autombot.client.ui.more.DevicesScreen
import com.autombot.client.ui.more.LogsScreen
import com.autombot.client.ui.more.SettingsScreen
import com.autombot.client.ui.more.StatisticsScreen
import com.autombot.client.ui.more.SupportScreen
import com.autombot.client.ui.onboarding.AccountCreatedScreen
import com.autombot.client.ui.onboarding.ChoiceScreen
import com.autombot.client.ui.onboarding.DomainInputScreen
import com.autombot.client.ui.onboarding.ProgressStepsScreen
import com.autombot.client.ui.onboarding.SplashScreen
import com.autombot.client.ui.theme.AutomBotClientTheme
import com.autombot.client.ui.theme.AutomBotColors as C
import com.autombot.client.ui.wireguard.WireGuardScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader

class MainActivity : ComponentActivity() {

    private lateinit var wireGuardManager: WireGuardManager
    private lateinit var sshManager: SshTunnelManager
    private lateinit var vlessManager: com.autombot.client.protocols.vless.VlessTunnelManager
    private lateinit var vmessManager: com.autombot.client.protocols.vmess.VmessTunnelManager
    private lateinit var shadowsocksManager: com.autombot.client.protocols.shadowsocks.ShadowsocksTunnelManager
    private lateinit var trojanManager: com.autombot.client.protocols.trojan.TrojanTunnelManager
    private lateinit var openVpnManager: com.autombot.client.protocols.openvpn.OpenVpnTunnelManager

    private var pendingVpnGrantAction: (() -> Unit)? = null

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            pendingVpnGrantAction?.invoke()
        }
        pendingVpnGrantAction = null
    }

    private var pendingFileCallback: ((fileName: String, text: String) -> Unit)? = null

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) {
            pendingFileCallback = null
            return@registerForActivityResult
        }
        runCatching {
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "tunnel.conf"
            val text = contentResolver.openInputStream(uri)?.use { stream ->
                BufferedReader(InputStreamReader(stream)).readText()
            } ?: ""
            pendingFileCallback?.invoke(name, text)
        }
        pendingFileCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.autombot.client.util.AppLog.init(applicationContext)
        wireGuardManager = WireGuardManager(applicationContext)
        sshManager = SshTunnelManager(applicationContext)
        vlessManager = com.autombot.client.protocols.vless.VlessTunnelManager(applicationContext)
        vmessManager = com.autombot.client.protocols.vmess.VmessTunnelManager(applicationContext)
        shadowsocksManager = com.autombot.client.protocols.shadowsocks.ShadowsocksTunnelManager(applicationContext)
        trojanManager = com.autombot.client.protocols.trojan.TrojanTunnelManager(applicationContext)
        openVpnManager = com.autombot.client.protocols.openvpn.OpenVpnTunnelManager(applicationContext)

        setContent {
            AutomBotClientTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot(
                        wireGuardManager = wireGuardManager,
                        sshManager = sshManager,
                        vlessManager = vlessManager,
                        vmessManager = vmessManager,
                        shadowsocksManager = shadowsocksManager,
                        trojanManager = trojanManager,
                        openVpnManager = openVpnManager,
                        onRequestVpnPermission = ::requestVpnPermission,
                        onPickConfigFile = ::pickConfigFile,
                        onStartSystemVpn = { port, dns1, dns2 -> startSystemVpn(port, dns1, dns2) },
                        onStopSystemVpn = ::stopSystemVpn,
                        onStartOpenVpn = ::startSystemVpnForOpenVpn
                    )
                }
            }
        }
    }

    private fun requestVpnPermission(onGranted: () -> Unit) {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            pendingVpnGrantAction = onGranted
            vpnPermissionLauncher.launch(intent)
        } else {
            onGranted()
        }
    }

    private fun pickConfigFile(onText: (fileName: String, text: String) -> Unit) {
        pendingFileCallback = onText
        filePickerLauncher.launch("*/*")
    }

    private fun startSystemVpn(socksPort: Int, dns1: String = "8.8.8.8", dns2: String = "8.8.4.4") {
        requestVpnPermission {
            val intent = Intent(this, AutomBotVpnService::class.java).apply {
                action = AutomBotVpnService.ACTION_START
                putExtra(AutomBotVpnService.EXTRA_SOCKS_PORT, socksPort)
                putExtra(AutomBotVpnService.EXTRA_DNS_PRIMARY, dns1)
                putExtra(AutomBotVpnService.EXTRA_DNS_SECONDARY, dns2)
            }
            startService(intent)
        }
    }

    private fun stopSystemVpn() {
        val intent = android.content.Intent(this, com.autombot.client.core.AutomBotVpnService::class.java).apply {
            action = com.autombot.client.core.AutomBotVpnService.ACTION_STOP
        }
        startService(intent)
    }

    private fun startSystemVpnForOpenVpn(connectionName: String, config: com.autombot.client.protocols.openvpn.OpenVpnConnectionConfig) {
        openVpnManager.markConnecting(connectionName)
        requestVpnPermission {
            val configPath = config.configFile(applicationContext).absolutePath
            val intent = android.content.Intent(this, com.autombot.client.core.AutomBotVpnService::class.java).apply {
                action = com.autombot.client.core.AutomBotVpnService.ACTION_START_OPENVPN
                putExtra(com.autombot.client.core.AutomBotVpnService.EXTRA_OPENVPN_CONFIG_PATH, configPath)
                putExtra(com.autombot.client.core.AutomBotVpnService.EXTRA_OPENVPN_CONNECTION_NAME, connectionName)
            }
            startService(intent)
        }
    }
}

private sealed class Screen {
    data object Splash : Screen()
    data object Choice : Screen()
    data object DomainInput : Screen()
    data class Connecting(val domain: String) : Screen()
    data class CreatingAccount(val domain: String) : Screen()
    data object AccountCreated : Screen()
    data object Shell : Screen()
    data object WireGuard : Screen()
    data object NoDomainIntro : Screen()
    data object ProtocolSelect : Screen()
    data class ManualConfig(val protocol: ProtocolOption) : Screen()
    data class SshConfig(val editing: com.autombot.client.protocols.ssh.SshConnectionConfig? = null) : Screen()
    data object Ssh : Screen()
    data object Vless : Screen()
    data object VlessAdd : Screen()
    data object Vmess : Screen()
    data object VmessAdd : Screen()
    data object Shadowsocks : Screen()
    data object ShadowsocksAdd : Screen()
    data object Trojan : Screen()
    data object TrojanAdd : Screen()
    data object OpenVpn : Screen()
    data object OpenVpnAdd : Screen()
    data class ConnectionTest(val config: ManualConnectionConfig) : Screen()
    data object XraySelect : Screen()
    data object Settings : Screen()
    data class Logs(val filterName: String? = null, val origin: Screen = Shell) : Screen()
    data object Statistics : Screen()
    data object Devices : Screen()
    data object Support : Screen()
    data object Plan : Screen()
    data object Connections : Screen()
}

private const val TRIAL_DURATION_SECONDS = 2 * 60 * 60L

@Composable
private fun AppRoot(
    wireGuardManager: WireGuardManager,
    sshManager: SshTunnelManager,
    vlessManager: com.autombot.client.protocols.vless.VlessTunnelManager,
    vmessManager: com.autombot.client.protocols.vmess.VmessTunnelManager,
    shadowsocksManager: com.autombot.client.protocols.shadowsocks.ShadowsocksTunnelManager,
    trojanManager: com.autombot.client.protocols.trojan.TrojanTunnelManager,
    openVpnManager: com.autombot.client.protocols.openvpn.OpenVpnTunnelManager,
    onRequestVpnPermission: (onGranted: () -> Unit) -> Unit,
    onPickConfigFile: (onText: (fileName: String, text: String) -> Unit) -> Unit,
    onStartSystemVpn: (socksPort: Int, dns1: String, dns2: String) -> Unit,
    onStopSystemVpn: () -> Unit,
    onStartOpenVpn: (connectionName: String, config: com.autombot.client.protocols.openvpn.OpenVpnConnectionConfig) -> Unit
) {
    val context = LocalContext.current
    val appPrefs = remember { context.getSharedPreferences("autombot_app", android.content.Context.MODE_PRIVATE) }

    var screen by remember { mutableStateOf<Screen>(Screen.Splash) }
    var trialSecondsRemaining by remember { mutableStateOf<Long?>(null) }
    var isManagedMode by remember { mutableStateOf(appPrefs.getBoolean("managed_mode", false)) }
    val manualConnections = remember { mutableStateListOf<ManualConnectionConfig>() }

    LaunchedEffect(trialSecondsRemaining != null) {
        while (trialSecondsRemaining != null && (trialSecondsRemaining ?: 0) > 0) {
            delay(1000)
            trialSecondsRemaining = (trialSecondsRemaining ?: 0) - 1
        }
    }

    fun markOnboarded(managed: Boolean) {
        appPrefs.edit().putBoolean("onboarded", true).putBoolean("managed_mode", managed).apply()
        isManagedMode = managed
    }

    fun resetToChoice() {
        trialSecondsRemaining = null
        isManagedMode = false
        manualConnections.clear()
        appPrefs.edit().putBoolean("onboarded", false).apply()
        screen = Screen.Choice
    }

    val sshConnectionsForRouting by sshManager.connections.collectAsState()
    val vlessConnectionsForRouting by vlessManager.connections.collectAsState()
    val vmessConnectionsForRouting by vmessManager.connections.collectAsState()
    val shadowsocksConnectionsForRouting by shadowsocksManager.connections.collectAsState()
    val trojanConnectionsForRouting by trojanManager.connections.collectAsState()
    LaunchedEffect(
        sshConnectionsForRouting,
        vlessConnectionsForRouting,
        vmessConnectionsForRouting,
        shadowsocksConnectionsForRouting,
        trojanConnectionsForRouting
    ) {
        val activeSsh = sshConnectionsForRouting.firstOrNull { it.status == SshStatus.CONNECTED && it.localSocksPort != null }
        val activeVless = vlessConnectionsForRouting.firstOrNull { it.status == VlessStatus.CONNECTED && it.localSocksPort != null }
        val activeVmess = vmessConnectionsForRouting.firstOrNull { it.status == VmessStatus.CONNECTED && it.localSocksPort != null }
        val activeSs = shadowsocksConnectionsForRouting.firstOrNull { it.status == ShadowsocksStatus.CONNECTED && it.localSocksPort != null }
        val activeTrojan = trojanConnectionsForRouting.firstOrNull { it.status == TrojanStatus.CONNECTED && it.localSocksPort != null }

        val activePort = activeSsh?.localSocksPort ?: activeVless?.localSocksPort ?: activeVmess?.localSocksPort ?: activeSs?.localSocksPort ?: activeTrojan?.localSocksPort
        
        if (activePort != null) {
            // Se for SSH, tenta pegar o DNS do perfil. Outros protocolos usam o padrao por enquanto.
            val dns1 = activeSsh?.config?.dnsPrimary ?: "8.8.8.8"
            val dns2 = activeSsh?.config?.dnsSecondary ?: "8.8.4.4"
            onStartSystemVpn(activePort, dns1, dns2)
        } else {
            onStopSystemVpn()
        }
    }

    when (val current = screen) {
        is Screen.Splash -> SplashScreen(onFinished = { screen = if (appPrefs.getBoolean("onboarded", false)) Screen.Shell else Screen.Choice })
        is Screen.Choice -> ChoiceScreen(onHasDomain = { screen = Screen.DomainInput }, onNoDomain = { markOnboarded(managed = false); screen = Screen.Shell })
        is Screen.DomainInput -> DomainInputScreen(onBack = { screen = Screen.Choice }, onConnect = { domain -> screen = Screen.Connecting(domain) })
        is Screen.Connecting -> ProgressStepsScreen(title = "Conectando…", subtitle = "Aguarde...", steps = listOf("Verificando"), onComplete = { screen = Screen.CreatingAccount(current.domain) })
        is Screen.CreatingAccount -> ProgressStepsScreen(title = "Criando conta", subtitle = "Aguarde...", steps = listOf("Criando"), onComplete = { trialSecondsRemaining = TRIAL_DURATION_SECONDS; markOnboarded(managed = true); screen = Screen.AccountCreated })
        is Screen.AccountCreated -> AccountCreatedScreen(countdownLabel = formatCountdown(trialSecondsRemaining ?: 0), onGoToDashboard = { screen = Screen.Shell })
        is Screen.NoDomainIntro -> NoDomainScreen(onConfigureManually = { screen = Screen.ProtocolSelect })
        is Screen.ProtocolSelect -> ProtocolSelectScreen(onBack = { screen = Screen.Shell }, onSelect = { opt -> screen = when(opt.id) { "wireguard" -> Screen.WireGuard; "ssh" -> Screen.SshConfig(); "vless" -> Screen.VlessAdd; "vmess" -> Screen.VmessAdd; "shadowsocks" -> Screen.ShadowsocksAdd; "trojan" -> Screen.TrojanAdd; else -> Screen.ManualConfig(opt) } })
        is Screen.SshConfig -> SshConfigScreen(initialConfig = current.editing, onBack = { screen = if (current.editing != null) Screen.Ssh else Screen.ProtocolSelect }, onSave = { sshManager.saveProfile(it); screen = Screen.Ssh })
        is Screen.Ssh -> SshScreen(manager = sshManager, onBack = { screen = Screen.Shell }, onAddProfile = { screen = Screen.SshConfig() }, onEditProfile = { screen = Screen.SshConfig(it) }, onViewLog = { screen = Screen.Logs(it, Screen.Ssh) })
        is Screen.Vless -> com.autombot.client.ui.vless.VlessScreen(manager = vlessManager, onBack = { screen = Screen.Shell }, onAddProfile = { screen = Screen.VlessAdd }, onViewLog = { screen = Screen.Logs(it, Screen.Vless) })
        is Screen.VlessAdd -> com.autombot.client.ui.vless.VlessAddScreen(onBack = { screen = Screen.Vless }, onSave = { vlessManager.addProfile(it); screen = Screen.Vless })
        is Screen.Vmess -> com.autombot.client.ui.vmess.VmessScreen(manager = vmessManager, onBack = { screen = Screen.Shell }, onAddProfile = { screen = Screen.VmessAdd }, onViewLog = { screen = Screen.Logs(it, Screen.Vmess) })
        is Screen.VmessAdd -> com.autombot.client.ui.vmess.VmessAddScreen(onBack = { screen = Screen.Vmess }, onSave = { vmessManager.addProfile(it); screen = Screen.Vmess })
        is Screen.Shadowsocks -> com.autombot.client.ui.shadowsocks.ShadowsocksScreen(manager = shadowsocksManager, onBack = { screen = Screen.Shell }, onAddProfile = { screen = Screen.ShadowsocksAdd }, onViewLog = { screen = Screen.Logs(it, Screen.Shadowsocks) })
        is Screen.ShadowsocksAdd -> com.autombot.client.ui.shadowsocks.ShadowsocksAddScreen(onBack = { screen = Screen.Shadowsocks }, onSave = { shadowsocksManager.addProfile(it); screen = Screen.Shadowsocks })
        is Screen.Trojan -> com.autombot.client.ui.trojan.TrojanScreen(manager = trojanManager, onBack = { screen = Screen.Shell }, onAddProfile = { screen = Screen.TrojanAdd }, onViewLog = { screen = Screen.Logs(it, Screen.Trojan) })
        is Screen.TrojanAdd -> com.autombot.client.ui.trojan.TrojanAddScreen(onBack = { screen = Screen.Trojan }, onSave = { trojanManager.addProfile(it); screen = Screen.Trojan })
        is Screen.OpenVpn -> OpenVpnScreen(manager = openVpnManager, onBack = { screen = Screen.Shell }, onAddProfile = { screen = Screen.OpenVpnAdd }, onConnect = { onStartOpenVpn(it.connectionName, it) }, onDisconnect = onStopSystemVpn)
        is Screen.OpenVpnAdd -> OpenVpnAddScreen(onBack = { screen = Screen.OpenVpn }, onPickFile = onPickConfigFile, onSave = { openVpnManager.addProfile(it); screen = Screen.OpenVpn })
        is Screen.WireGuard -> WireGuardScreen(manager = wireGuardManager, onBack = { screen = Screen.Shell }, onRequestVpnPermission = onRequestVpnPermission, onPickConfigFile = onPickConfigFile, onViewLog = { screen = Screen.Logs(it, Screen.WireGuard) })
        is Screen.ManualConfig -> ManualConfigScreen(protocol = current.protocol, onBack = { screen = Screen.ProtocolSelect }, onSave = { manualConnections.add(it); screen = Screen.ConnectionTest(it) })
        is Screen.ConnectionTest -> ConnectionTestScreen(config = current.config, onCancel = { screen = Screen.ProtocolSelect }, onFinished = { markOnboarded(false); screen = Screen.Shell })
        is Screen.XraySelect -> XraySelectScreen(onBack = { screen = Screen.Connections }, onSelect = { opt -> screen = when(opt.id) { "vless" -> Screen.Vless; "vmess" -> Screen.Vmess; "shadowsocks" -> Screen.Shadowsocks; "trojan" -> Screen.Trojan; else -> Screen.Shell } })
        is Screen.Settings -> SettingsScreen(onBack = { screen = Screen.Shell }, onLogout = ::resetToChoice)
        is Screen.Statistics -> {
            val wgTunnels by wireGuardManager.tunnels.collectAsState()
            val sshConns by sshManager.connections.collectAsState()
            val vlessConnsStats by vlessManager.connections.collectAsState()
            val vmessConnsStats by vmessManager.connections.collectAsState()
            val ssConnsStats by shadowsocksManager.connections.collectAsState()
            val trConnsStats by trojanManager.connections.collectAsState()
            val ovpnConns by openVpnManager.connections.collectAsState()
            val totalRx = wgTunnels.sumOf { it.rxBytes } +
                sshConns.sumOf { it.rxBytes } +
                vlessConnsStats.sumOf { it.rxBytes } +
                vmessConnsStats.sumOf { it.rxBytes } +
                ssConnsStats.sumOf { it.rxBytes } +
                trConnsStats.sumOf { it.rxBytes } +
                ovpnConns.sumOf { it.rxBytes }
            val totalTx = wgTunnels.sumOf { it.txBytes } +
                sshConns.sumOf { it.txBytes } +
                vlessConnsStats.sumOf { it.txBytes } +
                vmessConnsStats.sumOf { it.txBytes } +
                ssConnsStats.sumOf { it.txBytes } +
                trConnsStats.sumOf { it.txBytes } +
                ovpnConns.sumOf { it.txBytes }
            StatisticsScreen(
                rxBytesLabel = formatBytes(totalRx),
                txBytesLabel = formatBytes(totalTx),
                totalLabel = formatBytes(totalRx + totalTx),
                onBack = { screen = Screen.Shell }
            )
        }
        is Screen.Devices -> DevicesScreen(onBack = { screen = Screen.Shell })
        is Screen.Support -> SupportScreen(onBack = { screen = Screen.Shell })
        is Screen.Plan -> PlanScreen(trialCountdown = trialSecondsRemaining?.let { formatCountdown(it) }, onSeePlans = {})
        is Screen.Connections -> {
            val wgTunnels by wireGuardManager.tunnels.collectAsState()
            val sshConns by sshManager.connections.collectAsState()
            val vlessConns by vlessManager.connections.collectAsState()
            val vmessConns by vmessManager.connections.collectAsState()
            val ssConns by shadowsocksManager.connections.collectAsState()
            val trConns by trojanManager.connections.collectAsState()
            val ovpnConns by openVpnManager.connections.collectAsState()
            val xrayConnected = vlessConns.any { it.status == com.autombot.client.protocols.vless.VlessStatus.CONNECTED } ||
                               vmessConns.any { it.status == com.autombot.client.protocols.vmess.VmessStatus.CONNECTED } ||
                               ssConns.any { it.status == com.autombot.client.protocols.shadowsocks.ShadowsocksStatus.CONNECTED } ||
                               trConns.any { it.status == com.autombot.client.protocols.trojan.TrojanStatus.CONNECTED }

            val rowList = listOf(
                ConnectionRow("wireguard", "WireGuard", if(wgTunnels.any { it.status == TunnelStatus.CONNECTED }) "Conectado" else "Desconectado", wgTunnels.any { it.status == TunnelStatus.CONNECTED }, true),
                ConnectionRow("ssh", "SSH", if(sshConns.any { it.status == SshStatus.CONNECTED }) "Conectado" else "Desconectado", sshConns.any { it.status == SshStatus.CONNECTED }, true),
                ConnectionRow("xray", "Xray (VLESS, VMess...)", if(xrayConnected) "Conectado" else "Configurar", xrayConnected, true),
                ConnectionRow("openvpn", "OpenVPN", if(ovpnConns.any { it.status == com.autombot.client.protocols.openvpn.OpenVpnStatus.CONNECTED }) "Conectado" else "Desconectado", ovpnConns.any { it.status == com.autombot.client.protocols.openvpn.OpenVpnStatus.CONNECTED }, true)
            )
            ConnectionsScreen(connections = rowList, onOpenConnection = { row -> screen = when(row.protocolId) { "wireguard" -> Screen.WireGuard; "ssh" -> Screen.Ssh; "openvpn" -> Screen.OpenVpn; "xray" -> Screen.XraySelect; else -> Screen.Shell } }, onNewConnection = { screen = Screen.ProtocolSelect })
        }
        is Screen.Logs -> LogsScreen(filterName = current.filterName, onBack = { screen = current.origin })
        is Screen.Shell -> MainShell(
            wireGuardManager = wireGuardManager,
            sshManager = sshManager,
            vlessManager = vlessManager,
            vmessManager = vmessManager,
            shadowsocksManager = shadowsocksManager,
            trojanManager = trojanManager,
            openVpnManager = openVpnManager,
            trialSecondsRemaining = trialSecondsRemaining,
            isManagedMode = isManagedMode,
            onReset = ::resetToChoice,
            onOpenSettings = { screen = Screen.Settings },
            onOpenStatistics = { screen = Screen.Statistics },
            onOpenDevices = { screen = Screen.Devices },
            onOpenSupport = { screen = Screen.Support },
            onOpenLogs = { screen = Screen.Logs(origin = Screen.Shell) },
            onOpenPlan = { screen = Screen.Plan },
            onOpenConnections = { screen = Screen.Connections }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainShell(
    wireGuardManager: WireGuardManager,
    sshManager: SshTunnelManager,
    vlessManager: com.autombot.client.protocols.vless.VlessTunnelManager,
    vmessManager: com.autombot.client.protocols.vmess.VmessTunnelManager,
    shadowsocksManager: com.autombot.client.protocols.shadowsocks.ShadowsocksTunnelManager,
    trojanManager: com.autombot.client.protocols.trojan.TrojanTunnelManager,
    openVpnManager: com.autombot.client.protocols.openvpn.OpenVpnTunnelManager,
    trialSecondsRemaining: Long?,
    isManagedMode: Boolean,
    onReset: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenStatistics: () -> Unit,
    onOpenDevices: () -> Unit,
    onOpenSupport: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenPlan: () -> Unit,
    onOpenConnections: () -> Unit
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            SideMenuContent(
                trialCountdown = trialSecondsRemaining?.let { formatCountdown(it) },
                showPlan = isManagedMode,
                onDashboard = { scope.launch { drawerState.close() } },
                onConnections = onOpenConnections,
                onPlan = onOpenPlan,
                onDevices = onOpenDevices,
                onSettings = onOpenSettings,
                onSupport = onOpenSupport,
                onLogout = onReset
            )
        }
    ) {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("AutomBot", color = C.Text, fontWeight = FontWeight.Bold) },
                    navigationIcon = { IconButton(onClick = { scope.launch { drawerState.open() } }) { Icon(Icons.Default.Menu, contentDescription = "Menu", tint = C.Text) } },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = C.Background)
                )
            },
            containerColor = C.Background
        ) { padding ->
            val wgTunnels by wireGuardManager.tunnels.collectAsState()
            val sshConnections by sshManager.connections.collectAsState()
            val vlessConnectionsDash by vlessManager.connections.collectAsState()
            val vmessConnectionsDash by vmessManager.connections.collectAsState()
            val ssConnectionsDash by shadowsocksManager.connections.collectAsState()
            val trConnectionsDash by trojanManager.connections.collectAsState()
            val ovpnConnections by openVpnManager.connections.collectAsState()

            val activeCount = listOf(
                wgTunnels.any { it.status == TunnelStatus.CONNECTED },
                sshConnections.any { it.status == SshStatus.CONNECTED },
                vlessConnectionsDash.any { it.status == com.autombot.client.protocols.vless.VlessStatus.CONNECTED },
                vmessConnectionsDash.any { it.status == com.autombot.client.protocols.vmess.VmessStatus.CONNECTED },
                ssConnectionsDash.any { it.status == com.autombot.client.protocols.shadowsocks.ShadowsocksStatus.CONNECTED },
                trConnectionsDash.any { it.status == com.autombot.client.protocols.trojan.TrojanStatus.CONNECTED },
                ovpnConnections.any { it.status == com.autombot.client.protocols.openvpn.OpenVpnStatus.CONNECTED }
            ).count { it }

            // Soma o tráfego real de TODOS os protocolos para o Dashboard
            val dashRx = wgTunnels.sumOf { it.rxBytes } +
                sshConnections.sumOf { it.rxBytes } +
                vlessConnectionsDash.sumOf { it.rxBytes } +
                vmessConnectionsDash.sumOf { it.rxBytes } +
                ssConnectionsDash.sumOf { it.rxBytes } +
                trConnectionsDash.sumOf { it.rxBytes } +
                ovpnConnections.sumOf { it.rxBytes }
            val dashTx = wgTunnels.sumOf { it.txBytes } +
                sshConnections.sumOf { it.txBytes } +
                vlessConnectionsDash.sumOf { it.txBytes } +
                vmessConnectionsDash.sumOf { it.txBytes } +
                ssConnectionsDash.sumOf { it.txBytes } +
                trConnectionsDash.sumOf { it.txBytes } +
                ovpnConnections.sumOf { it.txBytes }
            val dashTrafficLabel = if (dashRx + dashTx > 0) formatBytes(dashRx + dashTx) else "0 B"

            Box(modifier = Modifier.padding(padding)) {
                DashboardScreen(
                    trialCountdown = trialSecondsRemaining?.let { formatCountdown(it) },
                    activeConnections = activeCount,
                    trafficLabel = dashTrafficLabel,
                    onRenew = onOpenPlan,
                    onOpenConnections = onOpenConnections
                )
            }
        }
    }
}

private fun formatCountdown(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return "%02d:%02d:%02d".format(h, m, s)
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
    val pre = "KMGTPE"[exp - 1]
    return "%.2f %sB".format(bytes / Math.pow(1024.0, exp.toDouble()), pre)
}
