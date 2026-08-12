from pathlib import Path
import re

# Preserve the update behavior already present on main: opportunistic check,
# no continuous polling, and no automatic full config import.
main = Path("app/src/main/java/com/autombot/client/ui/MainActivity.kt")
text = main.read_text()
text = text.replace(
    "private const val MANAGED_CONFIG_CHECK_INTERVAL_MS = 15 * 60 * 1000L\n"
    "private const val SPONSORED_MANIFEST_POLL_INTERVAL_MS = 5 * 60 * 1000L",
    "private const val MANAGED_CONFIG_CHECK_INTERVAL_MS = 60 * 60 * 1000L",
)

# The incoming patch changes the existing manual applyConfigUpdate() to return
# Boolean; keep that compatible function and replace only its new polling check.
start = text.index("    suspend fun checkForConfigUpdate() {")
end = text.index("\n    ModalNavigationDrawer(", start)
replacement = '''    suspend fun checkForConfigUpdate() {
        if (!isManagedMode) return
        val usuarioGerenciado = appPrefs.getString("managed_usuario", null) ?: return
        val baseUrlGerenciada = appPrefs.getString("managed_base_url", null) ?: return
        val versaoConhecida = appPrefs.getString("managed_config_versao", "")
        val agora = System.currentTimeMillis()
        val ultimaChecagem = appPrefs.getLong("managed_config_last_check_ms", 0L)
        val decorrido = agora - ultimaChecagem
        if (ultimaChecagem > 0L && decorrido >= 0L && decorrido < MANAGED_CONFIG_CHECK_INTERVAL_MS) return

        // Uma única janela oportunista por hora. O manifesto patrocinado usa o
        // endpoint/bootstrap já armazenado, sem polling contínuo em background.
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
            PanelWebhookClient(baseUrlGerenciada).fetchConfigVersion(usuarioGerenciado)
        }.onSuccess { versaoAtual ->
            val existeAtualizacao = versaoAtual.isNotBlank() && versaoAtual != versaoConhecida
            updateAvailable = existeAtualizacao
            appPrefs.edit().putBoolean("managed_config_update_available", existeAtualizacao).apply()
        }
    }
'''
text = text[:start] + replacement + text[end:]

poll_start = text.find("            LaunchedEffect(isManagedMode) {")
if poll_start >= 0:
    marker = "\n            Box(modifier = Modifier.padding(padding))"
    poll_end = text.find(marker, poll_start)
    if poll_end < 0:
        raise RuntimeError("Não encontrei o final do polling patrocinado")
    text = text[:poll_start] + "            LaunchedEffect(Unit) { checkForConfigUpdate() }\n" + text[poll_end:]
main.write_text(text)

# OkHttp 4.12 exposes Dns as a regular interface in the resolved bytecode for
# this project; use an explicit object implementation instead of constructor-like
# SAM syntax so it compiles reliably with the current Kotlin plugin.
sync = Path("app/src/main/java/com/autombot/client/panel/SponsoredDomainSync.kt")
s = sync.read_text()
old_sync_dns = '''        val dns = Dns { requestedHost ->
            if (requestedHost.equals(endpoint.domain, ignoreCase = true) && endpoint.bootstrapIps.isNotEmpty()) {
                endpoint.bootstrapIps
                    .mapNotNull { raw -> runCatching { InetAddress.getByName(raw) }.getOrNull() }
                    .ifEmpty { Dns.SYSTEM.lookup(requestedHost) }
            } else {
                Dns.SYSTEM.lookup(requestedHost)
            }
        }
'''
new_sync_dns = '''        val dns = object : Dns {
            override fun lookup(requestedHost: String): List<InetAddress> {
                return if (requestedHost.equals(endpoint.domain, ignoreCase = true) && endpoint.bootstrapIps.isNotEmpty()) {
                    endpoint.bootstrapIps
                        .mapNotNull { raw -> runCatching { InetAddress.getByName(raw) }.getOrNull() }
                        .ifEmpty { Dns.SYSTEM.lookup(requestedHost) }
                } else {
                    Dns.SYSTEM.lookup(requestedHost)
                }
            }
        }
'''
if old_sync_dns not in s:
    raise RuntimeError("Bloco Dns esperado não encontrado em SponsoredDomainSync")
sync.write_text(s.replace(old_sync_dns, new_sync_dns))

validator = Path("app/src/main/java/com/autombot/client/panel/SponsoredRouteValidator.kt")
v = validator.read_text()
old_validator_dns = '''            val dns = Dns { requestedHost ->
                if (requestedHost.equals(host, ignoreCase = true) && bootstrapIps.isNotEmpty()) {
                    bootstrapIps.mapNotNull { raw -> runCatching { InetAddress.getByName(raw) }.getOrNull() }
                        .ifEmpty { Dns.SYSTEM.lookup(requestedHost) }
                } else {
                    Dns.SYSTEM.lookup(requestedHost)
                }
            }
'''
new_validator_dns = '''            val dns = object : Dns {
                override fun lookup(requestedHost: String): List<InetAddress> {
                    return if (requestedHost.equals(host, ignoreCase = true) && bootstrapIps.isNotEmpty()) {
                        bootstrapIps.mapNotNull { raw -> runCatching { InetAddress.getByName(raw) }.getOrNull() }
                            .ifEmpty { Dns.SYSTEM.lookup(requestedHost) }
                    } else {
                        Dns.SYSTEM.lookup(requestedHost)
                    }
                }
            }
'''
if old_validator_dns not in v:
    raise RuntimeError("Bloco Dns esperado não encontrado em SponsoredRouteValidator")
v = v.replace(old_validator_dns, new_validator_dns)

# The OkHttp API resolved by current main doesn't accept this override in the
# WebSocket listener. onOpen is the sole success signal; onFailure/onClosing and
# the coroutine timeout cover unsuccessful validation.
v, removed = re.subn(
    r'\n\s*override fun onClosed\(webSocket: WebSocket, code: Int, reason: String\) \{\s*complete\(false\)\s*\}\s*',
    '\n',
    v,
    count=1,
)
if removed != 1:
    raise RuntimeError("Não encontrei exatamente um override onClosed para adaptar")
validator.write_text(v)

# Avoid smart-cast errors on nullable complex expressions with the Kotlin
# compiler used by current main.
importer = Path("app/src/main/java/com/autombot/client/panel/panelConfigImporter.kt")
i = importer.read_text()
i = i.replace(
    "selecaoVmess.connectHost?.let { parsed.copy(server = it) } ?: parsed",
    "parsed.copy(server = selecaoVmess.connectHost ?: parsed.server)",
)
i = i.replace(
    "selecaoVless.connectHost?.let { parsed.copy(server = it) } ?: parsed",
    "parsed.copy(server = selecaoVless.connectHost ?: parsed.server)",
)
i = i.replace(
    "selecaoTrojan.connectHost?.let { parsed.copy(server = it) } ?: parsed",
    "parsed.copy(server = selecaoTrojan.connectHost ?: parsed.server)",
)
importer.write_text(i)
