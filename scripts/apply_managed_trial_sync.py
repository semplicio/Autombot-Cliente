from pathlib import Path
import re


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: esperado 1 trecho, encontrei {count}")
    return text.replace(old, new, 1)


def regex_once(text: str, pattern: str, replacement: str, label: str) -> str:
    updated, count = re.subn(pattern, replacement, text, count=1, flags=re.S)
    if count != 1:
        raise SystemExit(f"{label}: padrão não encontrado uma única vez ({count})")
    return updated


main_path = Path("app/src/main/java/com/autombot/client/ui/MainActivity.kt")
main = main_path.read_text()

main = replace_once(
    main,
    '    var trialSecondsRemaining by remember { mutableStateOf<Long?>(null) }\n',
    '''    var trialSecondsRemaining by remember {\n        val deadline = appPrefs.getLong("managed_trial_expires_at_ms", 0L)\n        val remaining = if (deadline > 0L) {\n            ((deadline - System.currentTimeMillis()).coerceAtLeast(0L) + 999L) / 1000L\n        } else null\n        mutableStateOf<Long?>(remaining)\n    }\n''',
    "contador persistente",
)

main = regex_once(
    main,
    r'''    LaunchedEffect\(trialSecondsRemaining != null\) \{\n        while \(trialSecondsRemaining != null && \(trialSecondsRemaining \?: 0\) > 0\) \{\n            delay\(1000\)\n            trialSecondsRemaining = \(trialSecondsRemaining \?: 0\) - 1\n        \}\n    \}\n''',
    '''    LaunchedEffect(trialSecondsRemaining != null, isManagedMode) {\n        if (!isManagedMode || trialSecondsRemaining == null) return@LaunchedEffect\n        while (true) {\n            val deadline = appPrefs.getLong("managed_trial_expires_at_ms", 0L)\n            if (deadline <= 0L) {\n                trialSecondsRemaining = null\n                break\n            }\n            val remaining = ((deadline - System.currentTimeMillis()).coerceAtLeast(0L) + 999L) / 1000L\n            trialSecondsRemaining = remaining\n            if (remaining <= 0L) {\n                val usuario = appPrefs.getString("managed_usuario", "").orEmpty()\n                val baseUrl = appPrefs.getString("managed_base_url", "").orEmpty()\n                val deviceId = appPrefs.getString("managed_device_id", "").orEmpty()\n                appPrefs.edit()\n                    .putString("managed_account_status", "expirado")\n                    .putBoolean("managed_trial_local_expired", true)\n                    .apply()\n                onStopSystemVpn()\n                if (usuario.isNotBlank() && baseUrl.isNotBlank() && deviceId.isNotBlank()) {\n                    runCatching { ManagedAccountStatusClient(baseUrl).expireTrial(usuario, deviceId) }\n                        .onSuccess { estado ->\n                            appPrefs.edit()\n                                .putString("managed_account_status", estado.status)\n                                .putString("managed_expira_em", estado.expiresAt.orEmpty())\n                                .putBoolean("managed_trial_expiry_sent", true)\n                                .apply()\n                            com.autombot.client.util.AppLog.log(\n                                "Teste de 2 horas encerrado e conta bloqueada no painel",\n                                com.autombot.client.util.AppLog.Level.INFO\n                            )\n                        }\n                        .onFailure {\n                            com.autombot.client.util.AppLog.log(\n                                "Teste encerrado localmente; falha ao bloquear no painel: ${it.message}",\n                                com.autombot.client.util.AppLog.Level.ERROR\n                            )\n                        }\n                }\n                break\n            }\n            delay(1000)\n        }\n    }\n''',
    "efeito de expiração",
)

main = replace_once(
    main,
    '    suspend fun performManagedConfigImport(trigger: String): Boolean {\n',
    '''    suspend fun refreshManagedAccountStatusPrefs(): Boolean {\n        if (!isManagedMode) return true\n        val usuario = appPrefs.getString("managed_usuario", null) ?: return false\n        val baseUrl = appPrefs.getString("managed_base_url", null) ?: return false\n        return runCatching { ManagedAccountStatusClient(baseUrl).fetch(usuario) }\n            .onSuccess { estado ->\n                val localTrialExpired = appPrefs.getBoolean("managed_trial_local_expired", false)\n                val originalTrialExpiry = appPrefs.getString("managed_trial_server_expiry", "").orEmpty()\n                val expiryChanged = !estado.expiresAt.isNullOrBlank() &&\n                    originalTrialExpiry.isNotBlank() && estado.expiresAt != originalTrialExpiry\n                val renewedAfterTrial = localTrialExpired && estado.active && expiryChanged\n                val effectiveStatus = if (localTrialExpired && !renewedAfterTrial) "expirado" else estado.status\n                val editor = appPrefs.edit()\n                    .putString("managed_usuario", estado.usuario)\n                    .putString("managed_account_status", effectiveStatus)\n                    .putString("managed_expira_em", estado.expiresAt.orEmpty())\n                if (renewedAfterTrial) {\n                    editor\n                        .putBoolean("managed_is_trial", false)\n                        .putBoolean("managed_trial_local_expired", false)\n                        .putBoolean("managed_trial_expiry_sent", false)\n                        .remove("managed_trial_expires_at_ms")\n                    trialSecondsRemaining = null\n                }\n                editor.apply()\n            }\n            .onFailure {\n                com.autombot.client.util.AppLog.log(\n                    "Falha ao sincronizar validade da conta: ${it.message}",\n                    com.autombot.client.util.AppLog.Level.ERROR\n                )\n            }.isSuccess\n    }\n\n    suspend fun performManagedConfigImport(trigger: String): Boolean {\n''',
    "helper status raiz",
)

main = replace_once(
    main,
    '''    suspend fun checkManagedConfigUpdate(\n        force: Boolean = false,\n        autoApply: Boolean = true\n    ): Boolean {\n''',
    '''    suspend fun checkManagedConfigUpdate(\n        force: Boolean = false,\n        autoApply: Boolean = true,\n        visibleProgress: Boolean = false\n    ): Boolean {\n''',
    "assinatura sync",
)
main = replace_once(main, '        managedCheckingUpdate = true\n        return try {\n', '        if (visibleProgress) managedCheckingUpdate = true\n        return try {\n', "progresso manual")

main = replace_once(
    main,
    '''        checkManagedConfigUpdate(force = true, autoApply = true)\n        while (true) {\n            delay(MANAGED_CONFIG_CHECK_INTERVAL_MS)\n            checkManagedConfigUpdate(force = true, autoApply = true)\n        }\n''',
    '''        refreshManagedAccountStatusPrefs()\n        checkManagedConfigUpdate(force = true, autoApply = true)\n        while (true) {\n            delay(MANAGED_CONFIG_CHECK_INTERVAL_MS)\n            refreshManagedAccountStatusPrefs()\n            checkManagedConfigUpdate(force = true, autoApply = true)\n        }\n''',
    "polling gerenciado",
)

main = replace_once(
    main,
    '''                            val senha = deviceProvisioning.generateRandomPassword()\n                            trialAccount = panelClient.createTrial(deviceId, usuario, senha)\n                            restoredExistingAccount = false\n''',
    '''                            val senha = deviceProvisioning.generateRandomPassword()\n                            val created = panelClient.createTrial(deviceId, usuario, senha, validadeMinutos = 120)\n                            trialAccount = created\n                            restoredExistingAccount = false\n                            val deadline = System.currentTimeMillis() + TRIAL_DURATION_SECONDS * 1000L\n                            appPrefs.edit()\n                                .putBoolean("managed_is_trial", true)\n                                .putLong("managed_trial_expires_at_ms", deadline)\n                                .putString("managed_trial_server_expiry", created.expiraEm)\n                                .putBoolean("managed_trial_local_expired", false)\n                                .putBoolean("managed_trial_expiry_sent", false)\n                                .apply()\n                            trialSecondsRemaining = TRIAL_DURATION_SECONDS\n''',
    "criação trial 2h",
)

main = replace_once(
    main,
    '        is Screen.Settings -> SettingsScreen(onBack = { screen = Screen.Shell }, onLogout = ::resetToChoice)\n',
    '''        is Screen.Settings -> SettingsScreen(\n            onBack = { screen = Screen.Shell },\n            onLogout = ::resetToChoice,\n            showManagedUpdate = isManagedMode,\n            checkingUpdate = managedCheckingUpdate || managedApplyingUpdate,\n            onCheckUpdates = {\n                rootScope.launch {\n                    refreshManagedAccountStatusPrefs()\n                    checkManagedConfigUpdate(force = true, autoApply = true, visibleProgress = true)\n                }\n            }\n        )\n''',
    "settings sync",
)

main = regex_once(
    main,
    r'''            LaunchedEffect\(Unit\) \{\n                refreshManagedAccountStatus\(\)\n            \}\n''',
    '''            LaunchedEffect(isManagedMode) {\n                if (!isManagedMode) return@LaunchedEffect\n                refreshManagedAccountStatus()\n                while (true) {\n                    delay(MANAGED_CONFIG_CHECK_INTERVAL_MS)\n                    refreshManagedAccountStatus()\n                }\n            }\n''',
    "refresh dashboard",
)

main_path.write_text(main)

# Dashboard: o card de sincronização só existe quando há atualização real.
dash_path = Path("app/src/main/java/com/autombot/client/ui/dashboard/DashboardScreen.kt")
dash = dash_path.read_text()
dash = regex_once(
    dash,
    r'''        \} else if \(managedMode\) \{\n            Spacer\(Modifier\.height\(14\.dp\)\)\n            AutomBotCard\(modifier = Modifier\.fillMaxWidth\(\)\) \{.*?\n            \}\n        \}\n\n        Spacer\(Modifier\.height\(18\.dp\)\)''',
    '''        }\n\n        if (managedMode && !accountInactive && trialCountdown == null && managedExpiry.isNotBlank()) {\n            val validity = remember(managedExpiry) { managedValidityLabels(managedExpiry) }\n            Spacer(Modifier.height(14.dp))\n            AutomBotCard(modifier = Modifier.fillMaxWidth(), accent = C.Green) {\n                Text("Plano ativo", color = C.Green, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)\n                Spacer(Modifier.height(4.dp))\n                Text(validity.first, color = C.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)\n                validity.second?.let {\n                    Text(it, color = C.TextDim, fontSize = 10.sp)\n                }\n                Spacer(Modifier.height(10.dp))\n                AutomBotGradientButton(\n                    text = "Renovar agora",\n                    onClick = onRenew,\n                    modifier = Modifier.fillMaxWidth(),\n                    accent = C.Green\n                )\n            }\n        }\n\n        Spacer(Modifier.height(18.dp))''',
    "ocultar card e validade",
)

# Formata expiração sem depender de java.time/desugaring.
dash += '''\n\nprivate fun managedValidityLabels(raw: String): Pair<String, String?> {\n    val patterns = listOf(\n        "yyyy-MM-dd HH:mm:ss",\n        "yyyy-MM-dd'T'HH:mm:ssXXX",\n        "yyyy-MM-dd'T'HH:mm:ss",\n        "yyyy-MM-dd"\n    )\n    val parsed = patterns.firstNotNullOfOrNull { pattern ->\n        runCatching {\n            java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault()).apply { isLenient = false }.parse(raw)\n        }.getOrNull()\n    }\n    if (parsed == null) return "Conta válida até $raw" to null\n    val dateLabel = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault()).format(parsed)\n    val remainingMs = (parsed.time - System.currentTimeMillis()).coerceAtLeast(0L)\n    val days = if (remainingMs == 0L) 0L else (remainingMs + 86_399_999L) / 86_400_000L\n    val remainingLabel = when (days) {\n        0L -> "Vence hoje"\n        1L -> "1 dia restante"\n        else -> "$days dias restantes"\n    }\n    return "Conta válida até $dateLabel" to remainingLabel\n}\n'''
dash_path.write_text(dash)

# Configurações: ação manual fica fora do dashboard.
settings_path = Path("app/src/main/java/com/autombot/client/ui/more/SettingsScreen.kt")
settings = settings_path.read_text()
settings = replace_once(settings, 'import androidx.compose.material.icons.filled.PowerSettingsNew\n', 'import androidx.compose.material.icons.filled.PowerSettingsNew\nimport androidx.compose.material.icons.filled.Refresh\n', "ícone refresh")
settings = replace_once(
    settings,
    'fun SettingsScreen(onBack: () -> Unit, onLogout: () -> Unit) {\n',
    '''fun SettingsScreen(\n    onBack: () -> Unit,\n    onLogout: () -> Unit,\n    showManagedUpdate: Boolean = false,\n    checkingUpdate: Boolean = false,\n    onCheckUpdates: () -> Unit = {}\n) {\n''',
    "assinatura settings",
)
settings = replace_once(
    settings,
    '''                AutomBotInfoRow(Icons.Default.Info, "Sobre o app", "AutomBot Connect • 0.1.0", C.TextDim, onClick = {})\n''',
    '''                if (showManagedUpdate) {\n                    AutomBotInfoRow(\n                        Icons.Default.Refresh,\n                        "Buscar atualizações",\n                        if (checkingUpdate) "Verificando painel…" else "Sincronizar conta e configurações agora",\n                        C.AccentLight,\n                        onClick = if (checkingUpdate) null else onCheckUpdates\n                    )\n                }\n                AutomBotInfoRow(Icons.Default.Info, "Sobre o app", "AutomBot Connect • 0.1.0", C.TextDim, onClick = {})\n''',
    "botão settings",
)
settings_path.write_text(settings)

print("Patch do ciclo gerenciado aplicado com sucesso")
