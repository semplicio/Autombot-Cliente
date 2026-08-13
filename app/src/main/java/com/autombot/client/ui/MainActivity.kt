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
import com.autombot.client.ui.onboarding.ProgressStep
import com.autombot.client.ui.onboarding.ProgressStepsScreen
import com.autombot.client.ui.onboarding.SplashScreen
import com.autombot.client.panel.PanelException
import com.autombot.client.panel.PanelWebhookClient
import com.autombot.client.panel.SponsoredDomainSync
import com.autombot.client.panel.TrialAccount
import com.autombot.client.panel.importPanelConfigs
import com.autombot.client.provisioning.DeviceProvisioning
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
        window.statusBarColor = android.graphics.Color.rgb(7, 17, 27)
        window.navigationBarColor = android.graphics.Color.rgb(5, 11, 18)
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        com.autombot.client.util.AppLog.init(applicationContext)
        com.autombot.client.util.AppLog.log("App iniciado (onCreate) — reconstruindo a tela e os gerenciadores de conexão", com.autombot.client.util.AppLog.Level.INFO)
        wireGuardManager = WireGuardManager(applicationContext)
        sshManager = SshTunnelManager(applicationContext)
        vlessManager = com.autombot.client.protocols.vless.VlessTunnelManager(applicationContext)
        vmessManager = com.autombot.client.protocols.vmess.VmessTunnelManager(applicationContext)
        shadowsocksManager = com.autombot.client.protocols.shadowsocks.ShadowsocksTunnelManager(applicationContext)
        trojanManager = com.autombot.client.protocols.trojan.TrojanTunnelManager(applicationContext)
        openVpnManager = com.autombot.client.protocols.openvpn.OpenVpnTunnelManager(applicationContext)

        requestBatteryOptimizationExemption()

        setContent {
            AutomBotClientTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = C.Background) {
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

    override fun onStart() {
        super.onStart()
        com.autombot.client.util.AppLog.log("App em primeiro plano (onStart)", com.autombot.client.util.AppLog.Level.INFO)
    }

    override fun onResume() {
        super.onResume()
        com.autombot.client.util.AppLog.log("App interativo (onResume) — tela visível e respondendo a toques", com.autombot.client.util.AppLog.Level.INFO)
    }

    override fun onPause() {
        super.onPause()
        com.autombot.client.util.AppLog.log("App perdendo o foco (onPause)", com.autombot.client.util.AppLog.Level.INFO)
    }

    override fun onStop() {
        super.onStop()
        com.autombot.client.util.AppLog.log("App em segundo plano (onStop) — tela não visível, mas o processo (e a VPN, se ativa) continua rodando", com.autombot.client.util.AppLog.Level.INFO)
    }

    override fun onDestroy() {
        com.autombot.client.util.AppLog.log("App encerrando (onDestroy) — essa instância da tela está sendo destruída", com.autombot.client.util.AppLog.Level.INFO)
        super.onDestroy()
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

    private fun requestBatteryOptimizationExemption() {
        val powerManager = getSystemService(android.os.PowerManager::class.java)
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) return
        try {
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                android.net.Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } catch (e: Exception) {
            com.autombot.client.util.AppLog.log(
                "Não consegui abrir a tela de isenção de otimização de bateria automaticamente — " +
                    "pode precisar ajustar manualmente nas configurações do aparelho.",
                com.autombot.client.util.AppLog.Level.ERROR
            )
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
    data object PlansAvailable : Screen()
    data class PixPayment(val plan: PlanOption) : Screen()
    data class AwaitingPayment(val plan: PlanOption) : Screen()
    data class PaymentApproved(val plan: PlanOption) : Screen()
    data object Connections : Screen()
}

private const val TRIAL_DURATION_SECONDS = 2 * 60 * 60L
private const val MANAGED_CONFIG_CHECK_INTERVAL_MS = 60 * 60 * 1000L

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

    LaunchedEffect(screen) {
        com.autombot.client.util.AppLog.log(
            "Navegação: tela agora é ${screen::class.simpleName}",
            com.autombot.client.util.AppLog.Level.INFO
        )
    }
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
            val dns1 = activeSsh?.config?.dnsPrimary ?: "8.8.8.8"
            val dns2 = activeSsh?.config?.dnsSecondary ?: "8.8.4.4"
            onStartSystemVpn(activePort, dns1, dns2)
        } else {
            onStopSystemVpn()
        }
    }

    when (val current = screen) {
        is Screen.Splash -> SplashScreen(onFinished = { screen = if (appPrefs.getBoolean("onboarded", false)) Screen.Shell else Screen.Choice })
        is Screen.Choice -> ChoiceScreen(onHasDomain = { screen = Screen.DomainInput }, onNoDomain = { screen = Screen.NoDomainIntro })
        is Screen.DomainInput -> DomainInputScreen(onBack = { screen = Screen.Choice }, onConnect = { domain -> screen = Screen.Connecting(domain) })
        is Screen.Connecting -> {
            val panelClient = remember(current.domain) { PanelWebhookClient(current.domain) }
            ProgressStepsScreen(
                title = "Conectando…",
                subtitle = "Aguarde enquanto verificamos o servidor.",
                steps = listOf(
                    ProgressStep("Verificando servidor") {
                        if (!panelClient.ping()) {
                            throw PanelException("Não consegui alcançar esse domínio. Confira o endereço e tente de novo.")
                        }
                    },
                    ProgressStep("Autenticando domínio") {
                        val ok = panelClient.checkApiKeyAccepted()
                        if (!ok) {
                            throw PanelException("Esse painel não reconheceu a chave de API do app. Confira a configuração em api_keys.")
                        }
                    }
                ),
                onComplete = { screen = Screen.CreatingAccount(current.domain) },
                onCancel = { screen = Screen.DomainInput }
            )
        }
        is Screen.CreatingAccount -> {
            val panelClient = remember(current.domain) { PanelWebhookClient(current.domain) }
            val deviceProvisioning = remember { DeviceProvisioning(context) }
            var trialAccount by remember { mutableStateOf<TrialAccount?>(null) }
            var existingConfigs by remember { mutableStateOf<com.autombot.client.panel.PanelConfigsResponse?>(null) }
            var restoredExistingAccount by remember { mutableStateOf(false) }

            ProgressStepsScreen(
                title = if (restoredExistingAccount) "Restaurando sua conta" else "Preparando sua conta",
                subtitle = "Este aparelho é identificado antes de qualquer novo teste ser criado.",
                steps = listOf(
                    ProgressStep("Localizando conta do aparelho") {
                        val deviceId = deviceProvisioning.getOrCreateDeviceId()
                        val usuario = deviceProvisioning.generateAccountUsername(deviceId)

                        val existing = runCatching { panelClient.fetchConfigs(usuario) }.getOrNull()
                        if (existing != null && existing.usuario.isNotBlank()) {
                            existingConfigs = existing
                            restoredExistingAccount = true
                            trialAccount = TrialAccount(
                                usuario = existing.usuario,
                                senha = "",
                                expiraEm = existing.expiraEm.orEmpty(),
                                limiteConexoes = 1,
                                servidor = existing.servidor,
                                protocolosConfigurados = existing.protocols.keys.toList(),
                                warnings = existing.warnings
                            )
                            com.autombot.client.util.AppLog.log(
                                "Aparelho já cadastrado: restaurando conta ${existing.usuario} sem criar novo trial",
                                com.autombot.client.util.AppLog.Level.INFO
                            )
                        } else {
                            val senha = deviceProvisioning.generateRandomPassword()
                            trialAccount = panelClient.createTrial(deviceId, usuario, senha)
                            restoredExistingAccount = false
                        }
                    },
                    ProgressStep("Sincronizando dados da conta") {
                        val conta = trialAccount ?: throw PanelException("Conta não foi localizada/criada corretamente")
                        val respostaConfigs = existingConfigs ?: panelClient.fetchConfigs(conta.usuario)
                        val statusNormalizado = respostaConfigs.status.trim().lowercase()
                        val contaAtiva = statusNormalizado.isBlank() || statusNormalizado in setOf("ativo", "active", "ok")

                        if (contaAtiva) {
                            val avisos = importPanelConfigs(
                                context = context,
                                response = respostaConfigs,
                                wireGuardManager = wireGuardManager,
                                sshManager = sshManager,
                                vlessManager = vlessManager,
                                vmessManager = vmessManager,
                                shadowsocksManager = shadowsocksManager,
                                trojanManager = trojanManager,
                                openVpnManager = openVpnManager
                            )
                            avisos.forEach {
                                com.autombot.client.util.AppLog.log(it, com.autombot.client.util.AppLog.Level.ERROR)
                            }
                        } else {
                            com.autombot.client.util.AppLog.log(
                                "Conta ${conta.usuario} restaurada com status ${respostaConfigs.status}; conexões não serão liberadas até renovação",
                                com.autombot.client.util.AppLog.Level.INFO
                            )
                        }

                        val versaoInicial = if (contaAtiva) {
                            runCatching { panelClient.fetchConfigVersion(conta.usuario) }.getOrDefault("")
                        } else ""
                        val deviceId = deviceProvisioning.getOrCreateDeviceId()
                        appPrefs.edit()
                            .putString("managed_usuario", conta.usuario)
                            .putString("managed_base_url", current.domain)
                            .putString("managed_config_versao", versaoInicial)
                            .putLong("managed_config_last_check_ms", System.currentTimeMillis())
                            .putBoolean("managed_config_update_available", false)
                            .putString("managed_device_id", deviceId)
                            .putString("managed_account_status", respostaConfigs.status)
                            .putString("managed_expira_em", respostaConfigs.expiraEm.orEmpty())
                            .putBoolean("managed_account_restored", restoredExistingAccount)
                            .apply()
                    }
                ),
                onComplete = {
                    markOnboarded(managed = true)
                    if (restoredExistingAccount) {
                        trialSecondsRemaining = null
                        screen = Screen.Shell
                    } else {
                        trialSecondsRemaining = TRIAL_DURATION_SECONDS
                        screen = Screen.AccountCreated
                    }
                },
                onCancel = { screen = Screen.DomainInput }
            )
        }
        is Screen.AccountCreated -> AccountCreatedScreen(countdownLabel = formatCountdown(trialSecondsRemaining ?: 0), onGoToDashboard = { screen = Screen.Shell })
        is Screen.NoDomainIntro -> NoDomainScreen(
            onBack = { screen = Screen.Choice },
            onConfigureManually = { markOnboarded(managed = false); screen = Screen.ProtocolSelect }
        )
        is Screen.ProtocolSelect -> ProtocolSelectScreen(onBack = { screen = Screen.Shell }, onSelect = { opt -> screen = when(opt.id) { "wireguard" -> Screen.WireGuard; "ssh" -> Screen.SshConfig(); "vless" -> Screen.VlessAdd; "vmess" -> Screen.VmessAdd; "shadowsocks" -> Screen.ShadowsocksAdd; "trojan" -> Screen.TrojanAdd; "openvpn" -> Screen.OpenVpnAdd; else -> Screen.ManualConfig(opt) } })
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
                downloadFraction = if (totalRx + totalTx > 0) totalRx.toFloat() / (totalRx + totalTx).toFloat() else 0.5f,
                onBack = { screen = Screen.Shell }
            )
        }
        is Screen.Devices -> DevicesScreen(onBack = { screen = Screen.Shell })
        is Screen.Support -> SupportScreen(onBack = { screen = Screen.Shell })
        is Screen.Plan -> PlanScreen(
            trialCountdown = trialSecondsRemaining?.let { formatCountdown(it) },
            onBack = { screen = Screen.Shell },
            onSeePlans = { screen = Screen.PlansAvailable }
        )
        is Screen.PlansAvailable -> PlansAvailableScreen(
            onBack = { screen = Screen.Plan },
            onSelect = { plan -> screen = Screen.PixPayment(plan) }
        )
        is Screen.PixPayment -> PixPaymentScreen(
            plan = current.plan,
            onBack = { screen = Screen.PlansAvailable },
            onContinue = { screen = Screen.AwaitingPayment(current.plan) }
        )
        is Screen.AwaitingPayment -> AwaitingPaymentScreen(
            plan = current.plan,
            onBack = { screen = Screen.PixPayment(current.plan) },
            onVerify = { screen = Screen.PaymentApproved(current.plan) }
        )
        is Screen.PaymentApproved -> PaymentApprovedScreen(
            plan = current.plan,
            onContinue = { screen = Screen.Shell }
        )
        is Screen.Connections -> {
            val wgTunnels by wireGuardManager.tunnels.collectAsState()
            val sshConns by sshManager.connections.collectAsState()
            val vlessConns by vlessManager.connections.collectAsState()
            val vmessConns by vmessManager.connections.collectAsState()
            val ssConns by shadowsocksManager.connections.collectAsState()
            val trConns by trojanManager.connections.collectAsState()
            val ovpnConns by openVpnManager.connections.collectAsState()
            val rowList = listOf(
                ConnectionRow("ssh", "SSH", if(sshConns.any { it.status == SshStatus.CONNECTED }) "Conectado" else "Desconectado", sshConns.any { it.status == SshStatus.CONNECTED }, true),
                ConnectionRow("vless", "VLESS", if(vlessConns.any { it.status == com.autombot.client.protocols.vless.VlessStatus.CONNECTED }) "Conectado" else "Desconectado", vlessConns.any { it.status == com.autombot.client.protocols.vless.VlessStatus.CONNECTED }, true),
                ConnectionRow("vmess", "VMess / V2Ray", if(vmessConns.any { it.status == com.autombot.client.protocols.vmess.VmessStatus.CONNECTED }) "Conectado" else "Desconectado", vmessConns.any { it.status == com.autombot.client.protocols.vmess.VmessStatus.CONNECTED }, true),
                ConnectionRow("wireguard", "WireGuard", if(wgTunnels.any { it.status == TunnelStatus.CONNECTED }) "Conectado" else "Desconectado", wgTunnels.any { it.status == TunnelStatus.CONNECTED }, true),
                ConnectionRow("shadowsocks", "Shadowsocks", if(ssConns.any { it.status == com.autombot.client.protocols.shadowsocks.ShadowsocksStatus.CONNECTED }) "Conectado" else "Desconectado", ssConns.any { it.status == com.autombot.client.protocols.shadowsocks.ShadowsocksStatus.CONNECTED }, true),
                ConnectionRow("trojan", "Trojan", if(trConns.any { it.status == com.autombot.client.protocols.trojan.TrojanStatus.CONNECTED }) "Conectado" else "Desconectado", trConns.any { it.status == com.autombot.client.protocols.trojan.TrojanStatus.CONNECTED }, true),
                ConnectionRow("openvpn", "OpenVPN", if(ovpnConns.any { it.status == com.autombot.client.protocols.openvpn.OpenVpnStatus.CONNECTED }) "Conectado" else "Desconectado", ovpnConns.any { it.status == com.autombot.client.protocols.openvpn.OpenVpnStatus.CONNECTED }, true)
            )
            ConnectionsScreen(
                connections = rowList,
                onBack = { screen = Screen.Shell },
                onOpenConnection = { row -> screen = when(row.protocolId) {
                    "wireguard" -> Screen.WireGuard
                    "ssh" -> Screen.Ssh
                    "vless" -> Screen.Vless
                    "vmess" -> Screen.Vmess
                    "shadowsocks" -> Screen.Shadowsocks
                    "trojan" -> Screen.Trojan
                    "openvpn" -> Screen.OpenVpn
                    else -> Screen.Shell
                } },
                onNewConnection = { screen = Screen.ProtocolSelect }
            )
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
            onRequestVpnPermission = onRequestVpnPermission,
            onStopSystemVpn = onStopSystemVpn,
            onStartOpenVpn = onStartOpenVpn,
            onOpenSettings = { screen = Screen.Settings },
            onOpenStatistics = { screen = Screen.Statistics },
            onOpenDevices = { screen = Screen.Devices },
            onOpenSupport = { screen = Screen.Support },
            onOpenLogcat = { screen = Screen.Logs(origin = Screen.Shell) },
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
    onRequestVpnPermission: (onGranted: () -> Unit) -> Unit,
    onStopSystemVpn: () -> Unit,
    onStartOpenVpn: (connectionName: String, config: com.autombot.client.protocols.openvpn.OpenVpnConnectionConfig) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenStatistics: () -> Unit,
    onOpenDevices: () -> Unit,
    onOpenSupport: () -> Unit,
    onOpenLogcat: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenPlan: () -> Unit,
    onOpenConnections: () -> Unit
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val appPrefs = remember { context.getSharedPreferences("autombot_app", android.content.Context.MODE_PRIVATE) }
    val modernManager = remember(context) { com.autombot.client.protocols.modern.ModernProtocolManagerProvider.get(context) }

    var updateAvailable by remember {
        mutableStateOf(appPrefs.getBoolean("managed_config_update_available", false))
    }
    var applyingUpdate by remember { mutableStateOf(false) }
    var lastProtocolId by remember { mutableStateOf(appPrefs.getString("dashboard_last_protocol", "").orEmpty()) }
    var lastConnectionName by remember { mutableStateOf(appPrefs.getString("dashboard_last_connection", "").orEmpty()) }

    suspend fun applyConfigUpdate(): Boolean {
        val usuarioGerenciado = appPrefs.getString("managed_usuario", null) ?: return false
        val baseUrlGerenciada = appPrefs.getString("managed_base_url", null) ?: return false
        applyingUpdate = true
        try {
            val cliente = PanelWebhookClient(baseUrlGerenciada)
            val respostaConfigs = cliente.fetchConfigs(usuarioGerenciado)
            appPrefs.edit()
                .putString("managed_account_status", respostaConfigs.status)
                .putString("managed_expira_em", respostaConfigs.expiraEm.orEmpty())
                .apply()

            val statusNormalizado = respostaConfigs.status.trim().lowercase()
            val contaAtiva = statusNormalizado.isBlank() || statusNormalizado in setOf("ativo", "active", "ok")
            if (!contaAtiva) {
                updateAvailable = false
                appPrefs.edit().putBoolean("managed_config_update_available", false).apply()
                return false
            }

            importPanelConfigs(
                context = context,
                response = respostaConfigs,
                wireGuardManager = wireGuardManager,
                sshManager = sshManager,
                vlessManager = vlessManager,
                vmessManager = vmessManager,
                shadowsocksManager = shadowsocksManager,
                trojanManager = trojanManager,
                openVpnManager = openVpnManager
            )
            val versaoNova = runCatching { cliente.fetchConfigVersion(usuarioGerenciado) }.getOrNull()
            val editor = appPrefs.edit()
                .putLong("managed_config_last_check_ms", System.currentTimeMillis())
                .putBoolean("managed_config_update_available", false)
            if (!versaoNova.isNullOrBlank()) editor.putString("managed_config_versao", versaoNova)
            editor.apply()
            updateAvailable = false
            return true
        } catch (e: Exception) {
            com.autombot.client.util.AppLog.log("Falha ao aplicar atualização de config: ${e.message}", com.autombot.client.util.AppLog.Level.ERROR)
            return false
        } finally {
            applyingUpdate = false
        }
    }

    suspend fun checkForConfigUpdate() {
        if (!isManagedMode) return
        val usuarioGerenciado = appPrefs.getString("managed_usuario", null) ?: return
        val baseUrlGerenciada = appPrefs.getString("managed_base_url", null) ?: return
        val versaoConhecida = appPrefs.getString("managed_config_versao", "")
        val agora = System.currentTimeMillis()
        val ultimaChecagem = appPrefs.getLong("managed_config_last_check_ms", 0L)
        val decorrido = agora - ultimaChecagem
        if (ultimaChecagem > 0L && decorrido >= 0L && decorrido < MANAGED_CONFIG_CHECK_INTERVAL_MS) return

        appPrefs.edit().putLong("managed_config_last_check_ms", agora).apply()
        runCatching {
            SponsoredDomainSync.refresh(
                context = context,
                vlessManager = vlessManager,
                vmessManager = vmessManager,
                trojanManager = trojanManager
            )
        }.onFailure {
            com.autombot.client.util.AppLog.log(
                "Falha ao sincronizar domínio patrocinado: ${it.message}",
                com.autombot.client.util.AppLog.Level.ERROR
            )
        }

        runCatching {
            val cliente = PanelWebhookClient(baseUrlGerenciada)
            val configs = cliente.fetchConfigs(usuarioGerenciado)
            appPrefs.edit()
                .putString("managed_account_status", configs.status)
                .putString("managed_expira_em", configs.expiraEm.orEmpty())
                .apply()
            cliente.fetchConfigVersion(usuarioGerenciado)
        }.onSuccess { versaoAtual ->
            val existeAtualizacao = versaoAtual.isNotBlank() && versaoAtual != versaoConhecida
            updateAvailable = existeAtualizacao
            appPrefs.edit().putBoolean("managed_config_update_available", existeAtualizacao).apply()
        }
    }

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
                onLogcat = onOpenLogcat,
                onLogout = onReset
            )
        }
    ) {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { com.autombot.client.ui.components.AutomBotWordmark(compact = true) },
                    navigationIcon = { IconButton(onClick = { scope.launch { drawerState.open() } }) { Icon(Icons.Default.Menu, contentDescription = "Menu", tint = C.Text) } },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = C.BackgroundTop)
                )
            },
            bottomBar = {
                BottomNavBar(selected = MainTab.Dashboard, showPlan = isManagedMode, onSelect = { tab ->
                    when (tab) {
                        MainTab.Dashboard -> Unit
                        MainTab.Connections -> onOpenConnections()
                        MainTab.Plan -> onOpenPlan()
                        MainTab.More -> scope.launch { drawerState.open() }
                    }
                })
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
            val modernConnectionsDash by modernManager.connections.collectAsState()

            val activeCount = listOf(
                wgTunnels.any { it.status == TunnelStatus.CONNECTED },
                sshConnections.any { it.status == SshStatus.CONNECTED },
                vlessConnectionsDash.any { it.status == com.autombot.client.protocols.vless.VlessStatus.CONNECTED },
                vmessConnectionsDash.any { it.status == com.autombot.client.protocols.vmess.VmessStatus.CONNECTED },
                ssConnectionsDash.any { it.status == com.autombot.client.protocols.shadowsocks.ShadowsocksStatus.CONNECTED },
                trConnectionsDash.any { it.status == com.autombot.client.protocols.trojan.TrojanStatus.CONNECTED },
                ovpnConnections.any { it.status == com.autombot.client.protocols.openvpn.OpenVpnStatus.CONNECTED }
            ).count { it }

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

            fun rememberLast(protocol: String, name: String) {
                if (protocol.isBlank() || name.isBlank()) return
                if (lastProtocolId != protocol || lastConnectionName != name) {
                    lastProtocolId = protocol
                    lastConnectionName = name
                    appPrefs.edit()
                        .putString("dashboard_last_protocol", protocol)
                        .putString("dashboard_last_connection", name)
                        .apply()
                }
            }

            LaunchedEffect(
                wgTunnels,
                sshConnections,
                vlessConnectionsDash,
                vmessConnectionsDash,
                ssConnectionsDash,
                trConnectionsDash,
                ovpnConnections,
                modernConnectionsDash
            ) {
                val current = listOfNotNull(
                    sshConnections.firstOrNull { it.status == SshStatus.CONNECTED || it.status == SshStatus.CONNECTING }
                        ?.let { "ssh" to it.config.connectionName },
                    vlessConnectionsDash.firstOrNull { it.status == com.autombot.client.protocols.vless.VlessStatus.CONNECTED || it.status == com.autombot.client.protocols.vless.VlessStatus.CONNECTING }
                        ?.let { "vless" to it.config.connectionName },
                    vmessConnectionsDash.firstOrNull { it.status == com.autombot.client.protocols.vmess.VmessStatus.CONNECTED || it.status == com.autombot.client.protocols.vmess.VmessStatus.CONNECTING }
                        ?.let { "vmess" to it.config.connectionName },
                    ssConnectionsDash.firstOrNull { it.status == com.autombot.client.protocols.shadowsocks.ShadowsocksStatus.CONNECTED || it.status == com.autombot.client.protocols.shadowsocks.ShadowsocksStatus.CONNECTING }
                        ?.let { "shadowsocks" to it.config.connectionName },
                    trConnectionsDash.firstOrNull { it.status == com.autombot.client.protocols.trojan.TrojanStatus.CONNECTED || it.status == com.autombot.client.protocols.trojan.TrojanStatus.CONNECTING }
                        ?.let { "trojan" to it.config.connectionName },
                    wgTunnels.firstOrNull { it.status == TunnelStatus.CONNECTED || it.status == TunnelStatus.CONNECTING }
                        ?.let { "wireguard" to it.name },
                    ovpnConnections.firstOrNull { it.status == com.autombot.client.protocols.openvpn.OpenVpnStatus.CONNECTED || it.status == com.autombot.client.protocols.openvpn.OpenVpnStatus.CONNECTING }
                        ?.let { "openvpn" to it.config.connectionName },
                    modernConnectionsDash.firstOrNull { it.status == com.autombot.client.protocols.modern.ModernProtocolStatus.CONNECTED || it.status == com.autombot.client.protocols.modern.ModernProtocolStatus.CONNECTING }
                        ?.let { it.config.type.id to it.config.connectionName }
                ).firstOrNull()
                current?.let { rememberLast(it.first, it.second) }
            }

            fun stateLabel(name: String): String = when (name) {
                "CONNECTED" -> "Conectado"
                "CONNECTING" -> "Conectando…"
                "DISCONNECTING" -> "Desconectando…"
                "ERROR" -> "Erro"
                else -> "Desconectado"
            }

            val quickConnection: DashboardQuickConnection? = when (lastProtocolId) {
                "ssh" -> sshConnections.firstOrNull { it.config.connectionName == lastConnectionName }?.let {
                    DashboardQuickConnection("ssh", "SSH", it.config.connectionName, "${it.config.server}:${it.config.port}", it.status == SshStatus.CONNECTED, it.status == SshStatus.CONNECTING, stateLabel(it.status.name))
                }
                "vless" -> vlessConnectionsDash.firstOrNull { it.config.connectionName == lastConnectionName }?.let {
                    DashboardQuickConnection("vless", "VLESS", it.config.connectionName, "${it.config.server}:${it.config.port}", it.status == com.autombot.client.protocols.vless.VlessStatus.CONNECTED, it.status == com.autombot.client.protocols.vless.VlessStatus.CONNECTING, stateLabel(it.status.name))
                }
                "vmess" -> vmessConnectionsDash.firstOrNull { it.config.connectionName == lastConnectionName }?.let {
                    DashboardQuickConnection("vmess", "VMess / V2Ray", it.config.connectionName, "${it.config.server}:${it.config.port}", it.status == com.autombot.client.protocols.vmess.VmessStatus.CONNECTED, it.status == com.autombot.client.protocols.vmess.VmessStatus.CONNECTING, stateLabel(it.status.name))
                }
                "shadowsocks" -> ssConnectionsDash.firstOrNull { it.config.connectionName == lastConnectionName }?.let {
                    DashboardQuickConnection("shadowsocks", "Shadowsocks", it.config.connectionName, "${it.config.server}:${it.config.port}", it.status == com.autombot.client.protocols.shadowsocks.ShadowsocksStatus.CONNECTED, it.status == com.autombot.client.protocols.shadowsocks.ShadowsocksStatus.CONNECTING, stateLabel(it.status.name))
                }
                "trojan" -> trConnectionsDash.firstOrNull { it.config.connectionName == lastConnectionName }?.let {
                    DashboardQuickConnection("trojan", "Trojan", it.config.connectionName, "${it.config.server}:${it.config.port}", it.status == com.autombot.client.protocols.trojan.TrojanStatus.CONNECTED, it.status == com.autombot.client.protocols.trojan.TrojanStatus.CONNECTING, stateLabel(it.status.name))
                }
                "wireguard" -> wgTunnels.firstOrNull { it.name == lastConnectionName }?.let {
                    DashboardQuickConnection("wireguard", "WireGuard", it.name, it.endpointLabel, it.status == TunnelStatus.CONNECTED, it.status == TunnelStatus.CONNECTING || it.status == TunnelStatus.DISCONNECTING, stateLabel(it.status.name))
                }
                "openvpn" -> ovpnConnections.firstOrNull { it.config.connectionName == lastConnectionName }?.let {
                    DashboardQuickConnection("openvpn", "OpenVPN", it.config.connectionName, it.config.configFileName, it.status == com.autombot.client.protocols.openvpn.OpenVpnStatus.CONNECTED, it.status == com.autombot.client.protocols.openvpn.OpenVpnStatus.CONNECTING, stateLabel(it.status.name))
                }
                "hysteria2", "tuic" -> modernConnectionsDash.firstOrNull { it.config.type.id == lastProtocolId && it.config.connectionName == lastConnectionName }?.let {
                    DashboardQuickConnection(it.config.type.id, it.config.type.displayName, it.config.connectionName, "${it.config.server}:${it.config.port}", it.status == com.autombot.client.protocols.modern.ModernProtocolStatus.CONNECTED, it.status == com.autombot.client.protocols.modern.ModernProtocolStatus.CONNECTING, stateLabel(it.status.name))
                }
                else -> null
            }

            fun toggleQuick() {
                val quick = quickConnection ?: return
                when (quick.protocolId) {
                    "ssh" -> scope.launch { if (quick.connected) sshManager.disconnect(quick.connectionName) else sshManager.connect(quick.connectionName) }
                    "vless" -> scope.launch { if (quick.connected) vlessManager.disconnect(quick.connectionName) else vlessManager.connect(quick.connectionName) }
                    "vmess" -> scope.launch { if (quick.connected) vmessManager.disconnect(quick.connectionName) else vmessManager.connect(quick.connectionName) }
                    "shadowsocks" -> scope.launch { if (quick.connected) shadowsocksManager.disconnect(quick.connectionName) else shadowsocksManager.connect(quick.connectionName) }
                    "trojan" -> scope.launch { if (quick.connected) trojanManager.disconnect(quick.connectionName) else trojanManager.connect(quick.connectionName) }
                    "wireguard" -> wgTunnels.firstOrNull { it.name == quick.connectionName }?.let { tunnel ->
                        onRequestVpnPermission { scope.launch { wireGuardManager.toggle(tunnel) } }
                    }
                    "openvpn" -> ovpnConnections.firstOrNull { it.config.connectionName == quick.connectionName }?.let { conn ->
                        if (quick.connected) {
                            openVpnManager.requestDisconnect(conn.config.connectionName)
                            onStopSystemVpn()
                        } else {
                            onStartOpenVpn(conn.config.connectionName, conn.config)
                        }
                    }
                    "hysteria2", "tuic" -> com.autombot.client.protocols.modern.ModernProtocolType.fromId(quick.protocolId)?.let { type ->
                        scope.launch {
                            if (quick.connected) modernManager.disconnect(type, quick.connectionName)
                            else modernManager.connect(type, quick.connectionName)
                        }
                    }
                }
            }

            LaunchedEffect(Unit) { checkForConfigUpdate() }

            Box(modifier = Modifier.padding(padding)) {
                DashboardScreen(
                    trialCountdown = trialSecondsRemaining?.let { formatCountdown(it) },
                    activeConnections = activeCount,
                    trafficLabel = dashTrafficLabel,
                    onRenew = onOpenPlan,
                    onOpenConnections = onOpenConnections,
                    quickConnection = quickConnection,
                    onToggleQuickConnection = ::toggleQuick,
                    onOpenQuickConnection = onOpenConnections,
                    updateAvailable = updateAvailable,
                    applyingUpdate = applyingUpdate,
                    onApplyUpdate = { scope.launch { applyConfigUpdate() } }
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
