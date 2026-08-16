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
import com.autombot.client.panel.ManagedAccountStatusClient
import com.autombot.client.panel.PanelConfigsResponse
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
private const val MANAGED_CONFIG_CHECK_INTERVAL_MS = 30 * 1000L

private data class ManagedSshCredentials(
    val username: String,
    val password: String
)

private fun PanelConfigsResponse.managedSshCredentials(): ManagedSshCredentials? {
    val profiles = protocols["ssh"]?.raw?.optJSONArray("perfis") ?: return null
    for (index in 0 until profiles.length()) {
        val profile = profiles.optJSONObject(index) ?: continue
        val username = profile.optString("usuario")
            .ifBlank { profile.optString("username") }
            .ifBlank { profile.optString("login") }
        val password = profile.optString("senha")
            .ifBlank { profile.optString("password") }
        if (username.isNotBlank() && password.isNotBlank()) {
            return ManagedSshCredentials(username = username, password = password)
        }
    }
    return null
}

private fun managedStatusAllowsConnection(status: String): Boolean {
    val normalized = status.trim().lowercase()
    return normalized.isBlank() || normalized in setOf("ativo", "active", "ok")
}

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
    val rootScope = rememberCoroutineScope()

    var screen by remember { mutableStateOf<Screen>(Screen.Splash) }

    LaunchedEffect(screen) {
        com.autombot.client.util.AppLog.log(
            "Navegação: tela agora é ${screen::class.simpleName}",
            com.autombot.client.util.AppLog.Level.INFO
        )
    }
    var trialSecondsRemaining by remember {
        val deadline = appPrefs.getLong("managed_trial_expires_at_ms", 0L)
        val remaining = if (deadline > 0L) {
            ((deadline - System.currentTimeMillis()).coerceAtLeast(0L) + 999L) / 1000L
        } else null
        mutableStateOf<Long?>(remaining)
    }
    var isManagedMode by remember { mutableStateOf(appPrefs.getBoolean("managed_mode", false)) }
    var managedUpdateAvailable by remember {
        mutableStateOf(appPrefs.getBoolean("managed_config_update_available", false))
    }
    var managedApplyingUpdate by remember { mutableStateOf(false) }
    var managedCheckingUpdate by remember { mutableStateOf(false) }
    var managedSyncInProgress by remember { mutableStateOf(false) }
    val manualConnections = remember { mutableStateListOf<ManualConnectionConfig>() }

    LaunchedEffect(trialSecondsRemaining != null, isManagedMode) {
        if (!isManagedMode || trialSecondsRemaining == null) return@LaunchedEffect
        while (true) {
            val deadline = appPrefs.getLong("managed_trial_expires_at_ms", 0L)
            if (deadline <= 0L) {
                trialSecondsRemaining = null
                break
            }
            val remaining = ((deadline - System.currentTimeMillis()).coerceAtLeast(0L) + 999L) / 1000L
            trialSecondsRemaining = remaining
            if (remaining <= 0L) {
                val usuario = appPrefs.getString("managed_usuario", "").orEmpty()
                val baseUrl = appPrefs.getString("managed_base_url", "").orEmpty()
                val deviceId = appPrefs.getString("managed_device_id", "").orEmpty()
                appPrefs.edit()
                    .putString("managed_account_status", "expirado")
                    .putBoolean("managed_trial_local_expired", true)
                    .apply()
                onStopSystemVpn()
                if (usuario.isNotBlank() && baseUrl.isNotBlank() && deviceId.isNotBlank()) {
                    runCatching { ManagedAccountStatusClient(baseUrl).expireTrial(usuario, deviceId) }
                        .onSuccess { estado ->
                            appPrefs.edit()
                                .putString("managed_account_status", estado.status)
                                .putString("managed_expira_em", estado.expiresAt.orEmpty())
                                .putBoolean("managed_trial_expiry_sent", true)
                                .apply()
                            com.autombot.client.util.AppLog.log(
                                "Teste de 2 horas encerrado e conta bloqueada no painel",
                                com.autombot.client.util.AppLog.Level.INFO
                            )
                        }
                        .onFailure {
                            com.autombot.client.util.AppLog.log(
                                "Teste encerrado localmente; falha ao bloquear no painel: ${it.message}",
                                com.autombot.client.util.AppLog.Level.ERROR
                            )
                        }
                }
                break
            }
            delay(1000)
        }
    }

    fun markOnboarded(managed: Boolean) {
        appPrefs.edit().putBoolean("onboarded", true).putBoolean("managed_mode", managed).apply()
        isManagedMode = managed
    }

    fun resetToChoice() {
        trialSecondsRemaining = null
        isManagedMode = false
        managedUpdateAvailable = false
        manualConnections.clear()
        appPrefs.edit()
            .putBoolean("onboarded", false)
            .putBoolean("managed_config_update_available", false)
            .apply()
        screen = Screen.Choice
    }

    suspend fun refreshManagedAccountStatusPrefs(): Boolean {
        if (!isManagedMode) return true
        val usuario = appPrefs.getString("managed_usuario", null) ?: return false
        val baseUrl = appPrefs.getString("managed_base_url", null) ?: return false
        return runCatching { ManagedAccountStatusClient(baseUrl).fetch(usuario) }
            .onSuccess { estado ->
                val localTrialExpired = appPrefs.getBoolean("managed_trial_local_expired", false)
                val originalTrialExpiry = appPrefs.getString("managed_trial_server_expiry", "").orEmpty()
                val expiryChanged = !estado.expiresAt.isNullOrBlank() &&
                    originalTrialExpiry.isNotBlank() && estado.expiresAt != originalTrialExpiry
                val renewedAfterTrial = localTrialExpired && estado.active && expiryChanged
                val effectiveStatus = if (localTrialExpired && !renewedAfterTrial) "expirado" else estado.status
                val editor = appPrefs.edit()
                    .putString("managed_usuario", estado.usuario)
                    .putString("managed_account_status", effectiveStatus)
                    .putString("managed_expira_em", estado.expiresAt.orEmpty())
                if (renewedAfterTrial) {
                    editor
                        .putBoolean("managed_is_trial", false)
                        .putBoolean("managed_trial_local_expired", false)
                        .putBoolean("managed_trial_expiry_sent", false)
                        .remove("managed_trial_expires_at_ms")
                    trialSecondsRemaining = null
                }
                editor.apply()
            }
            .onFailure {
                com.autombot.client.util.AppLog.log(
                    "Falha ao sincronizar validade da conta: ${it.message}",
                    com.autombot.client.util.AppLog.Level.ERROR
                )
            }.isSuccess
    }

    suspend fun performManagedConfigImport(trigger: String): Boolean {
        val usuarioGerenciado = appPrefs.getString("managed_usuario", null) ?: return false
        val baseUrlGerenciada = appPrefs.getString("managed_base_url", null) ?: return false
        val cliente = PanelWebhookClient(baseUrlGerenciada)
        val respostaConfigs = cliente.fetchConfigs(usuarioGerenciado)
        val credenciaisRemotas = respostaConfigs.managedSshCredentials()
        val senhaPersistida = appPrefs.getString("managed_senha", "").orEmpty()
        val senhaGerenciada = senhaPersistida.ifBlank { credenciaisRemotas?.password.orEmpty() }
        if (senhaGerenciada.isBlank()) {
            throw PanelException("A conta deste aparelho não possui uma senha SSH salva ou recuperável no painel.")
        }
        if (senhaPersistida.isBlank()) {
            appPrefs.edit().putString("managed_senha", senhaGerenciada).apply()
        }

        val avisos = importPanelConfigs(
            context = context,
            response = respostaConfigs,
            wireGuardManager = wireGuardManager,
            sshManager = sshManager,
            vlessManager = vlessManager,
            vmessManager = vmessManager,
            shadowsocksManager = shadowsocksManager,
            trojanManager = trojanManager,
            openVpnManager = openVpnManager,
            managedUsername = usuarioGerenciado,
            managedPassword = senhaGerenciada
        )
        avisos.forEach {
            com.autombot.client.util.AppLog.log(it, com.autombot.client.util.AppLog.Level.ERROR)
        }

        val versaoNova = cliente.fetchConfigVersion(usuarioGerenciado)
        appPrefs.edit()
            .putString("managed_config_versao", versaoNova)
            .putLong("managed_config_last_check_ms", System.currentTimeMillis())
            .putBoolean("managed_config_update_available", false)
            .apply()
        managedUpdateAvailable = false
        com.autombot.client.util.AppLog.log(
            "Configurações do painel aplicadas $trigger; os protocolos usarão os novos dados na próxima conexão",
            com.autombot.client.util.AppLog.Level.SUCCESS
        )
        return true
    }

    suspend fun applyManagedConfigUpdate(trigger: String = "manualmente"): Boolean {
        if (!isManagedMode || managedSyncInProgress) return false
        managedSyncInProgress = true
        managedApplyingUpdate = true
        return try {
            performManagedConfigImport(trigger)
        } catch (e: Exception) {
            com.autombot.client.util.AppLog.log(
                "Falha ao aplicar atualização de config: ${e.message}",
                com.autombot.client.util.AppLog.Level.ERROR
            )
            false
        } finally {
            managedApplyingUpdate = false
            managedSyncInProgress = false
        }
    }

    suspend fun checkManagedConfigUpdate(
        force: Boolean = false,
        autoApply: Boolean = true,
        visibleProgress: Boolean = false
    ): Boolean {
        if (!isManagedMode || managedSyncInProgress) return false
        val usuarioGerenciado = appPrefs.getString("managed_usuario", null) ?: return false
        val baseUrlGerenciada = appPrefs.getString("managed_base_url", null) ?: return false
        val agora = System.currentTimeMillis()
        val ultimaChecagem = appPrefs.getLong("managed_config_last_check_ms", 0L)
        val decorrido = agora - ultimaChecagem
        if (!force && ultimaChecagem > 0L && decorrido >= 0L &&
            decorrido < MANAGED_CONFIG_CHECK_INTERVAL_MS
        ) {
            return true
        }

        managedSyncInProgress = true
        if (visibleProgress) managedCheckingUpdate = true
        return try {
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

            val cliente = PanelWebhookClient(baseUrlGerenciada)
            val versaoConhecida = appPrefs.getString("managed_config_versao", "").orEmpty()
            val versaoAtual = cliente.fetchConfigVersion(usuarioGerenciado)
            if (versaoAtual.isBlank()) return false

            val existeAtualizacao = versaoAtual != versaoConhecida
            managedUpdateAvailable = existeAtualizacao
            appPrefs.edit()
                .putLong("managed_config_last_check_ms", System.currentTimeMillis())
                .putBoolean("managed_config_update_available", existeAtualizacao)
                .apply()

            if (!existeAtualizacao) return true

            com.autombot.client.util.AppLog.log(
                "Alteração detectada no painel; baixando e reconfigurando os protocolos automaticamente",
                com.autombot.client.util.AppLog.Level.INFO
            )
            if (autoApply) {
                managedApplyingUpdate = true
                performManagedConfigImport("automaticamente")
            } else {
                true
            }
        } catch (e: Exception) {
            com.autombot.client.util.AppLog.log(
                "Falha ao verificar atualização de config: ${e.message}",
                com.autombot.client.util.AppLog.Level.ERROR
            )
            false
        } finally {
            managedApplyingUpdate = false
            managedCheckingUpdate = false
            managedSyncInProgress = false
        }
    }

    LaunchedEffect(isManagedMode) {
        if (!isManagedMode) return@LaunchedEffect
        refreshManagedAccountStatusPrefs()
        checkManagedConfigUpdate(force = true, autoApply = true)
        while (true) {
            delay(MANAGED_CONFIG_CHECK_INTERVAL_MS)
            refreshManagedAccountStatusPrefs()
            checkManagedConfigUpdate(force = true, autoApply = true)
        }
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
                            val created = panelClient.createTrial(deviceId, usuario, senha, validadeMinutos = 120)
                            trialAccount = created
                            restoredExistingAccount = false
                            val deadline = System.currentTimeMillis() + TRIAL_DURATION_SECONDS * 1000L
                            appPrefs.edit()
                                .putBoolean("managed_is_trial", true)
                                .putLong("managed_trial_expires_at_ms", deadline)
                                .putString("managed_trial_server_expiry", created.expiraEm)
                                .putBoolean("managed_trial_local_expired", false)
                                .putBoolean("managed_trial_expiry_sent", false)
                                .apply()
                            trialSecondsRemaining = TRIAL_DURATION_SECONDS
                        }
                    },
                    ProgressStep("Sincronizando dados da conta") {
                        val conta = trialAccount ?: throw PanelException("Conta não foi localizada/criada corretamente")
                        val respostaConfigs = existingConfigs ?: panelClient.fetchConfigs(conta.usuario)
                        val credenciaisRemotas = respostaConfigs.managedSshCredentials()
                        val usuarioGerenciado = conta.usuario.ifBlank { credenciaisRemotas?.username.orEmpty() }
                        val senhaGerenciada = conta.senha.ifBlank { credenciaisRemotas?.password.orEmpty() }
                        if (usuarioGerenciado.isBlank() || senhaGerenciada.isBlank()) {
                            throw PanelException("Não foi possível recuperar o login e a senha vinculados a este aparelho.")
                        }

                        // As configurações pertencem à conta e continuam sendo úteis
                        // mesmo quando ela está expirada. A validade controla somente
                        // a permissão de conectar, não a visibilidade/sincronização.
                        val avisos = importPanelConfigs(
                            context = context,
                            response = respostaConfigs,
                            wireGuardManager = wireGuardManager,
                            sshManager = sshManager,
                            vlessManager = vlessManager,
                            vmessManager = vmessManager,
                            shadowsocksManager = shadowsocksManager,
                            trojanManager = trojanManager,
                            openVpnManager = openVpnManager,
                            managedUsername = usuarioGerenciado,
                            managedPassword = senhaGerenciada
                        )
                        avisos.forEach {
                            com.autombot.client.util.AppLog.log(it, com.autombot.client.util.AppLog.Level.ERROR)
                        }

                        val estadoConta = runCatching {
                            ManagedAccountStatusClient(current.domain).fetch(conta.usuario)
                        }.getOrNull()
                        val statusConta = estadoConta?.status ?: respostaConfigs.status
                        val expiraConta = estadoConta?.expiresAt ?: respostaConfigs.expiraEm
                        val contaAtiva = managedStatusAllowsConnection(statusConta)
                        if (!contaAtiva) {
                            com.autombot.client.util.AppLog.log(
                                "Conta ${conta.usuario} restaurada com status $statusConta; configs sincronizadas, conexão bloqueada até renovação",
                                com.autombot.client.util.AppLog.Level.INFO
                            )
                        }

                        val versaoInicial = runCatching {
                            panelClient.fetchConfigVersion(conta.usuario)
                        }.getOrDefault("")
                        val deviceId = deviceProvisioning.getOrCreateDeviceId()
                        appPrefs.edit()
                            .putString("managed_usuario", usuarioGerenciado)
                            .putString("managed_senha", senhaGerenciada)
                            .putString("managed_base_url", current.domain)
                            .putString("managed_config_versao", versaoInicial)
                            .putLong("managed_config_last_check_ms", System.currentTimeMillis())
                            .putBoolean("managed_config_update_available", false)
                            .putString("managed_device_id", deviceId)
                            .putString("managed_account_status", statusConta)
                            .putString("managed_expira_em", expiraConta.orEmpty())
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
        is Screen.Settings -> SettingsScreen(
            onBack = { screen = Screen.Shell },
            onLogout = ::resetToChoice,
            showManagedUpdate = isManagedMode,
            checkingUpdate = managedCheckingUpdate || managedApplyingUpdate,
            onCheckUpdates = {
                rootScope.launch {
                    refreshManagedAccountStatusPrefs()
                    checkManagedConfigUpdate(force = true, autoApply = true, visibleProgress = true)
                }
            }
        )
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
            val managedAccessAllowed = !isManagedMode || managedStatusAllowsConnection(
                appPrefs.getString("managed_account_status", "").orEmpty()
            )
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
                onNewConnection = { screen = Screen.ProtocolSelect },
                managedAccessAllowed = managedAccessAllowed
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
            updateAvailable = managedUpdateAvailable,
            applyingUpdate = managedApplyingUpdate,
            checkingUpdate = managedCheckingUpdate,
            onCheckUpdate = {
                rootScope.launch {
                    checkManagedConfigUpdate(force = true, autoApply = true)
                }
            },
            onApplyUpdate = {
                rootScope.launch {
                    applyManagedConfigUpdate(trigger = "manualmente")
                }
            },
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
    updateAvailable: Boolean,
    applyingUpdate: Boolean,
    checkingUpdate: Boolean,
    onCheckUpdate: () -> Unit,
    onApplyUpdate: () -> Unit,
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

    var lastProtocolId by remember { mutableStateOf(appPrefs.getString("dashboard_last_protocol", "").orEmpty()) }
    var lastConnectionName by remember { mutableStateOf(appPrefs.getString("dashboard_last_connection", "").orEmpty()) }
    var managedAccountUser by remember { mutableStateOf(appPrefs.getString("managed_usuario", "").orEmpty()) }
    var managedAccountStatus by remember { mutableStateOf(appPrefs.getString("managed_account_status", "").orEmpty()) }
    var managedAccountExpiry by remember { mutableStateOf(appPrefs.getString("managed_expira_em", "").orEmpty()) }

    val managedAccessAllowed = !isManagedMode || managedStatusAllowsConnection(managedAccountStatus)

    suspend fun refreshManagedAccountStatus(): Boolean {
        if (!isManagedMode) return true
        val usuarioGerenciado = appPrefs.getString("managed_usuario", null) ?: return false
        val baseUrlGerenciada = appPrefs.getString("managed_base_url", null) ?: return false

        return runCatching {
            ManagedAccountStatusClient(baseUrlGerenciada).fetch(usuarioGerenciado)
        }.onSuccess { estado ->
            managedAccountUser = estado.usuario
            managedAccountStatus = estado.status
            managedAccountExpiry = estado.expiresAt.orEmpty()
            appPrefs.edit()
                .putString("managed_usuario", estado.usuario)
                .putString("managed_account_status", estado.status)
                .putString("managed_expira_em", estado.expiresAt.orEmpty())
                .apply()
            com.autombot.client.util.AppLog.log(
                "Estado da conta atualizado: ${estado.usuario} = ${estado.status}" +
                    (estado.source?.let { " (fonte: $it)" } ?: ""),
                com.autombot.client.util.AppLog.Level.INFO
            )
        }.onFailure {
            com.autombot.client.util.AppLog.log(
                "Falha ao atualizar validade da conta: ${it.message}",
                com.autombot.client.util.AppLog.Level.ERROR
            )
        }.isSuccess
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
                val protocolId = quick.protocolId
                val connectionName = quick.connectionName

                // Resolve o estado real no instante do toque. Assim um callback que
                // tenha sido capturado antes da recomposição não repete a ação antiga.
                val currentlyConnected = when (protocolId) {
                    "ssh" -> sshManager.connections.value.firstOrNull { it.config.connectionName == connectionName }?.status == SshStatus.CONNECTED
                    "vless" -> vlessManager.connections.value.firstOrNull { it.config.connectionName == connectionName }?.status == com.autombot.client.protocols.vless.VlessStatus.CONNECTED
                    "vmess" -> vmessManager.connections.value.firstOrNull { it.config.connectionName == connectionName }?.status == com.autombot.client.protocols.vmess.VmessStatus.CONNECTED
                    "shadowsocks" -> shadowsocksManager.connections.value.firstOrNull { it.config.connectionName == connectionName }?.status == com.autombot.client.protocols.shadowsocks.ShadowsocksStatus.CONNECTED
                    "trojan" -> trojanManager.connections.value.firstOrNull { it.config.connectionName == connectionName }?.status == com.autombot.client.protocols.trojan.TrojanStatus.CONNECTED
                    "wireguard" -> wireGuardManager.tunnels.value.firstOrNull { it.name == connectionName }?.status == TunnelStatus.CONNECTED
                    "openvpn" -> openVpnManager.connections.value.firstOrNull { it.config.connectionName == connectionName }?.status == com.autombot.client.protocols.openvpn.OpenVpnStatus.CONNECTED
                    "hysteria2", "tuic" -> modernManager.connections.value.firstOrNull { it.config.type.id == protocolId && it.config.connectionName == connectionName }?.status == com.autombot.client.protocols.modern.ModernProtocolStatus.CONNECTED
                    else -> false
                }
                if (!currentlyConnected && !managedAccessAllowed) return

                when (protocolId) {
                    "ssh" -> scope.launch {
                        val current = sshManager.connections.value.firstOrNull { it.config.connectionName == connectionName } ?: return@launch
                        if (current.status == SshStatus.CONNECTED) sshManager.disconnect(connectionName)
                        else if (current.status != SshStatus.CONNECTING) sshManager.connect(connectionName)
                    }
                    "vless" -> scope.launch {
                        val current = vlessManager.connections.value.firstOrNull { it.config.connectionName == connectionName } ?: return@launch
                        if (current.status == com.autombot.client.protocols.vless.VlessStatus.CONNECTED) vlessManager.disconnect(connectionName)
                        else if (current.status != com.autombot.client.protocols.vless.VlessStatus.CONNECTING) vlessManager.connect(connectionName)
                    }
                    "vmess" -> scope.launch {
                        val current = vmessManager.connections.value.firstOrNull { it.config.connectionName == connectionName } ?: return@launch
                        if (current.status == com.autombot.client.protocols.vmess.VmessStatus.CONNECTED) vmessManager.disconnect(connectionName)
                        else if (current.status != com.autombot.client.protocols.vmess.VmessStatus.CONNECTING) vmessManager.connect(connectionName)
                    }
                    "shadowsocks" -> scope.launch {
                        val current = shadowsocksManager.connections.value.firstOrNull { it.config.connectionName == connectionName } ?: return@launch
                        if (current.status == com.autombot.client.protocols.shadowsocks.ShadowsocksStatus.CONNECTED) shadowsocksManager.disconnect(connectionName)
                        else if (current.status != com.autombot.client.protocols.shadowsocks.ShadowsocksStatus.CONNECTING) shadowsocksManager.connect(connectionName)
                    }
                    "trojan" -> scope.launch {
                        val current = trojanManager.connections.value.firstOrNull { it.config.connectionName == connectionName } ?: return@launch
                        if (current.status == com.autombot.client.protocols.trojan.TrojanStatus.CONNECTED) trojanManager.disconnect(connectionName)
                        else if (current.status != com.autombot.client.protocols.trojan.TrojanStatus.CONNECTING) trojanManager.connect(connectionName)
                    }
                    "wireguard" -> wireGuardManager.tunnels.value.firstOrNull { it.name == connectionName }?.let { tunnel ->
                        if (tunnel.status != TunnelStatus.CONNECTING && tunnel.status != TunnelStatus.DISCONNECTING) {
                            onRequestVpnPermission { scope.launch { wireGuardManager.toggle(tunnel) } }
                        }
                    }
                    "openvpn" -> openVpnManager.connections.value.firstOrNull { it.config.connectionName == connectionName }?.let { conn ->
                        if (conn.status == com.autombot.client.protocols.openvpn.OpenVpnStatus.CONNECTED) {
                            openVpnManager.requestDisconnect(conn.config.connectionName)
                            onStopSystemVpn()
                        } else if (conn.status != com.autombot.client.protocols.openvpn.OpenVpnStatus.CONNECTING) {
                            onStartOpenVpn(conn.config.connectionName, conn.config)
                        }
                    }
                    "hysteria2", "tuic" -> com.autombot.client.protocols.modern.ModernProtocolType.fromId(protocolId)?.let { type ->
                        scope.launch {
                            val current = modernManager.connections.value.firstOrNull { it.config.type == type && it.config.connectionName == connectionName } ?: return@launch
                            if (current.status == com.autombot.client.protocols.modern.ModernProtocolStatus.CONNECTED) modernManager.disconnect(type, connectionName)
                            else if (current.status != com.autombot.client.protocols.modern.ModernProtocolStatus.CONNECTING) modernManager.connect(type, connectionName)
                        }
                    }
                }
            }

            LaunchedEffect(isManagedMode) {
                if (!isManagedMode) return@LaunchedEffect
                refreshManagedAccountStatus()
                while (true) {
                    delay(MANAGED_CONFIG_CHECK_INTERVAL_MS)
                    refreshManagedAccountStatus()
                }
            }

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
                    managedAccountUser = managedAccountUser,
                    managedAccountStatus = managedAccountStatus,
                    managedAccountExpiry = managedAccountExpiry,
                    updateAvailable = updateAvailable,
                    applyingUpdate = applyingUpdate,
                    checkingUpdate = checkingUpdate,
                    onCheckUpdate = onCheckUpdate,
                    onApplyUpdate = onApplyUpdate
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
