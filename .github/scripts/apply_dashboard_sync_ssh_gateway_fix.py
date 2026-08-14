from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one matching block, found {count}")
    p.write_text(text.replace(old, new, 1))


# ---------------------------------------------------------------------------
# SSH: explicit payload gateway transport (no HTTP CONNECT handshake).
# ---------------------------------------------------------------------------
models = "app/src/main/java/com/autombot/client/protocols/ssh/SshModels.kt"
replace_once(
    models,
    " * e: TCP (direto ou via proxy) -> payload cru (se ligado) -> TLS (se ligado) -> handshake SSH.\n",
    " * e: TCP (direto, proxy ou gateway) -> TLS (se ligado) -> payload cru (se ligado) -> handshake SSH.\n",
)
replace_once(
    models,
    '''enum class ProxyType(val label: String) {
    SOCKS5("SOCKS5"),
    HTTP("HTTP")
}''',
    '''enum class ProxyType(val label: String) {
    SOCKS5("SOCKS5"),
    HTTP("HTTP CONNECT"),
    /**
     * Gateway de entrada que não implementa HTTP CONNECT. O app abre TCP em
     * proxyHost:proxyPort e envia a camada Payload diretamente como os primeiros
     * bytes da sessão. server:port continuam sendo o destino SSH lógico.
     */
    PAYLOAD_GATEWAY("Gateway Payload")
}''',
)

manager = "app/src/main/java/com/autombot/client/protocols/ssh/SshTunnelManager.kt"
replace_once(
    manager,
    "import java.io.OutputStream\n",
    "import java.io.OutputStream\nimport java.io.ByteArrayOutputStream\nimport java.io.PushbackInputStream\n",
)
replace_once(
    manager,
    "import java.net.Socket\n",
    "import java.net.Socket\nimport java.net.SocketTimeoutException\n",
)
replace_once(
    manager,
    '                markError(connectionName, e.message ?: e.javaClass.simpleName)\n',
    '''                val rawMessage = e.message ?: e.javaClass.simpleName
                val userMessage = if (
                    config.useProxy &&
                    config.proxyType == ProxyType.HTTP &&
                    rawMessage.contains("Invalid Proxy", ignoreCase = true)
                ) {
                    "Proxy HTTP CONNECT recusado. Se o endpoint recebe o Payload diretamente, use 'Gateway Payload' em vez de HTTP CONNECT."
                } else rawMessage
                markError(connectionName, userMessage)
''',
)
replace_once(
    manager,
    "        private var delegate: Socket? = null\n",
    "        private var delegate: Socket? = null\n        private var delegateInput: InputStream? = null\n",
)
replace_once(
    manager,
    '''            } else if (config.useProxy) {
                val proxyPort = config.proxyPort.toIntOrNull() ?: 1080
                val proxyType = if (config.proxyType == ProxyType.SOCKS5) Proxy.Type.SOCKS else Proxy.Type.HTTP
                val proxySocket = Socket(Proxy(proxyType, InetSocketAddress(config.proxyHost, proxyPort)))
                if (!com.autombot.client.core.AutomBotVpnService.protectSocket(proxySocket)) {
                    throw java.io.IOException("Não consegui isentar a conexão de proxy SSH da VPN (protect() falhou)")
                }
                // Com proxy, quem resolve o host de destino e o proprio proxy — so
                // conectamos no endereco do proxy aqui mesmo, sem fallback de IP.
                proxySocket.connect(InetSocketAddress(host, port), effectiveTimeout)
                proxySocket
''',
    '''            } else if (config.useProxy && config.proxyType == ProxyType.PAYLOAD_GATEWAY) {
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
''',
)
replace_once(
    manager,
    '''            if (config.usePayload && config.payload.isNotBlank()) {
                val payloadText = config.payload
                    .replace("[crlf]", "\\r\\n")
                    .replace("[host]", config.server)
                    .replace("[port]", config.port)
                socket.getOutputStream().apply { write(payloadText.toByteArray(Charsets.UTF_8)); flush() }
            }

            delegate = socket
        }

        private fun tuneTransportSocket(socket: Socket) {
''',
    '''            var preparedInput: InputStream? = null
            if (config.usePayload && config.payload.isNotBlank()) {
                val payloadText = config.payload
                    .replace("[crlf]", "\\r\\n")
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
''',
)
replace_once(
    manager,
    '        override fun getInputStream() = delegate?.getInputStream() ?: throw java.io.IOException("Socket não conectado")\n',
    '        override fun getInputStream() = delegateInput ?: delegate?.getInputStream() ?: throw java.io.IOException("Socket não conectado")\n',
)

importer = "app/src/main/java/com/autombot/client/panel/panelConfigImporter.kt"
replace_once(
    importer,
    '                        proxyType = if (raw.optString("proxy_type") == "HTTP") ProxyType.HTTP else ProxyType.SOCKS5,\n',
    '''                        proxyType = when (raw.optString("proxy_type").trim().uppercase()) {
                            "HTTP", "HTTP_CONNECT", "CONNECT" -> ProxyType.HTTP
                            "PAYLOAD_GATEWAY", "GATEWAY", "RAW_HTTP", "HTTP_PAYLOAD" -> ProxyType.PAYLOAD_GATEWAY
                            else -> ProxyType.SOCKS5
                        },
''',
)

ssh_ui = "app/src/main/java/com/autombot/client/ui/manual/SshConfigScreen.kt"
replace_once(
    ssh_ui,
    '                ExpandableLayer(title = "Proxy", subtitle = "SOCKS5 ou HTTP antes do servidor SSH", enabled = useProxy, onToggle = { useProxy = it }) {\n',
    '                ExpandableLayer(title = "Proxy / Gateway", subtitle = "SOCKS5, HTTP CONNECT ou gateway que recebe Payload direto", enabled = useProxy, onToggle = { useProxy = it }) {\n',
)
replace_once(
    ssh_ui,
    '''                    LabeledField("Host do proxy", proxyHost, placeholder = "127.0.0.1") { proxyHost = it }
                    Spacer(Modifier.height(8.dp))
                    LabeledField("Porta do proxy", proxyPort, keyboardType = KeyboardType.Number) { proxyPort = it }
                    Spacer(Modifier.height(8.dp))
                    LabeledField("Usuário do proxy (opcional)", proxyUsername) { proxyUsername = it }
                    Spacer(Modifier.height(8.dp))
                    LabeledField("Senha do proxy (opcional)", proxyPassword, isPassword = true) { proxyPassword = it }
''',
    '''                    if (proxyType == ProxyType.PAYLOAD_GATEWAY) {
                        Text(
                            "Abre TCP no gateway e envia a camada Payload diretamente, sem HTTP CONNECT. Servidor/Porta continuam representando o SSH lógico.",
                            color = C.TextDim,
                            fontSize = 10.sp
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    LabeledField(
                        if (proxyType == ProxyType.PAYLOAD_GATEWAY) "Host do gateway" else "Host do proxy",
                        proxyHost,
                        placeholder = "127.0.0.1"
                    ) { proxyHost = it }
                    Spacer(Modifier.height(8.dp))
                    LabeledField(
                        if (proxyType == ProxyType.PAYLOAD_GATEWAY) "Porta do gateway" else "Porta do proxy",
                        proxyPort,
                        keyboardType = KeyboardType.Number
                    ) { proxyPort = it }
                    if (proxyType != ProxyType.PAYLOAD_GATEWAY) {
                        Spacer(Modifier.height(8.dp))
                        LabeledField("Usuário do proxy (opcional)", proxyUsername) { proxyUsername = it }
                        Spacer(Modifier.height(8.dp))
                        LabeledField("Senha do proxy (opcional)", proxyPassword, isPassword = true) { proxyPassword = it }
                    }
''',
)


# ---------------------------------------------------------------------------
# Managed config: cache-bust configs.php and prevent intermediary stale data.
# ---------------------------------------------------------------------------
panel = "app/src/main/java/com/autombot/client/panel/PanelWebhookClient.kt"
p = Path(panel)
text = p.read_text()
old_url = 'val url = "$base/api/v1/configs.php?usuario=" + URLEncoder.encode(usuario, "UTF-8")'
if text.count(old_url) != 2:
    raise SystemExit(f"{panel}: expected two configs.php URL blocks, found {text.count(old_url)}")
new_url = 'val url = "$base/api/v1/configs.php?usuario=" + URLEncoder.encode(usuario, "UTF-8") + "&_cb=${System.currentTimeMillis()}"'
text = text.replace(old_url, new_url)
old_builder = '        val builder = Request.Builder().url(url).addHeader("X-API-Key", apiKey)\n'
new_builder = '''        val builder = Request.Builder()
            .url(url)
            .addHeader("X-API-Key", apiKey)
            .header("Cache-Control", "no-cache, no-store, max-age=0")
            .header("Pragma", "no-cache")
'''
if text.count(old_builder) != 1:
    raise SystemExit(f"{panel}: request builder block not found")
p.write_text(text.replace(old_builder, new_builder, 1))


# ---------------------------------------------------------------------------
# Dashboard reconnect: resolve protocol state at click time instead of trusting a
# previously captured UI model. Managed sync: 60 s throttle, forced on resume,
# timestamp only successful checks, and expose a manual check action.
# ---------------------------------------------------------------------------
main = "app/src/main/java/com/autombot/client/ui/MainActivity.kt"
replace_once(
    main,
    "private const val MANAGED_CONFIG_CHECK_INTERVAL_MS = 60 * 60 * 1000L\n",
    "private const val MANAGED_CONFIG_CHECK_INTERVAL_MS = 60 * 1000L\n",
)
replace_once(
    main,
    "    var applyingUpdate by remember { mutableStateOf(false) }\n",
    "    var applyingUpdate by remember { mutableStateOf(false) }\n    var checkingUpdate by remember { mutableStateOf(false) }\n",
)

old_check = '''    suspend fun checkForConfigUpdate() {
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
            PanelWebhookClient(baseUrlGerenciada).fetchConfigVersion(usuarioGerenciado)
        }.onSuccess { versaoAtual ->
            val existeAtualizacao = versaoAtual.isNotBlank() && versaoAtual != versaoConhecida
            updateAvailable = existeAtualizacao
            appPrefs.edit().putBoolean("managed_config_update_available", existeAtualizacao).apply()
        }
    }
'''
new_check = '''    suspend fun checkForConfigUpdate(force: Boolean = false): Boolean {
        if (!isManagedMode) return false
        val usuarioGerenciado = appPrefs.getString("managed_usuario", null) ?: return false
        val baseUrlGerenciada = appPrefs.getString("managed_base_url", null) ?: return false
        val versaoConhecida = appPrefs.getString("managed_config_versao", "")
        val agora = System.currentTimeMillis()
        val ultimaChecagem = appPrefs.getLong("managed_config_last_check_ms", 0L)
        val decorrido = agora - ultimaChecagem
        if (!force && ultimaChecagem > 0L && decorrido >= 0L && decorrido < MANAGED_CONFIG_CHECK_INTERVAL_MS) return false

        if (force) checkingUpdate = true
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

            val versaoAtual = PanelWebhookClient(baseUrlGerenciada).fetchConfigVersion(usuarioGerenciado)
            if (versaoAtual.isBlank()) return false
            val existeAtualizacao = versaoAtual != versaoConhecida
            updateAvailable = existeAtualizacao
            appPrefs.edit()
                .putLong("managed_config_last_check_ms", System.currentTimeMillis())
                .putBoolean("managed_config_update_available", existeAtualizacao)
                .apply()
            true
        } catch (e: Exception) {
            com.autombot.client.util.AppLog.log(
                "Falha ao verificar atualização de config: ${e.message}",
                com.autombot.client.util.AppLog.Level.ERROR
            )
            false
        } finally {
            if (force) checkingUpdate = false
        }
    }

    DisposableEffect(isManagedMode) {
        val lifecycleOwner = context as? androidx.lifecycle.LifecycleOwner
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && isManagedMode) {
                scope.launch { checkForConfigUpdate(force = true) }
            }
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose { lifecycleOwner?.lifecycle?.removeObserver(observer) }
    }
'''
replace_once(main, old_check, new_check)

old_toggle = '''            fun toggleQuick() {
                val quick = quickConnection ?: return
                if (!quick.connected && !managedAccessAllowed) return
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
'''
new_toggle = '''            fun toggleQuick() {
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
'''
replace_once(main, old_toggle, new_toggle)
replace_once(main, "                    checkForConfigUpdate()\n", "                    checkForConfigUpdate(force = true)\n")
replace_once(
    main,
    '''                    updateAvailable = updateAvailable,
                    applyingUpdate = applyingUpdate,
                    onApplyUpdate = { scope.launch { applyConfigUpdate() } }
''',
    '''                    updateAvailable = updateAvailable,
                    applyingUpdate = applyingUpdate,
                    checkingUpdate = checkingUpdate,
                    onCheckUpdate = { scope.launch { checkForConfigUpdate(force = true) } },
                    onApplyUpdate = { scope.launch { applyConfigUpdate() } }
''',
)


dash = "app/src/main/java/com/autombot/client/ui/dashboard/DashboardScreen.kt"
replace_once(
    dash,
    '''    updateAvailable: Boolean = false,
    applyingUpdate: Boolean = false,
    onApplyUpdate: () -> Unit = {}
''',
    '''    updateAvailable: Boolean = false,
    applyingUpdate: Boolean = false,
    checkingUpdate: Boolean = false,
    onCheckUpdate: () -> Unit = {},
    onApplyUpdate: () -> Unit = {}
''',
)
replace_once(
    dash,
    '''        if (managedMode && updateAvailable) {
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(C.Accent.copy(alpha = 0.16f))
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.SwapVert,
                    contentDescription = null,
                    tint = C.AccentLight,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Novas configurações disponíveis",
                        color = C.Text,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "Há mudanças no painel. Atualize todos os protocolos de uma vez.",
                        color = C.TextDim,
                        fontSize = 10.sp
                    )
                }
                Spacer(Modifier.width(8.dp))
                AutomBotGradientButton(
                    text = if (applyingUpdate) "Atualizando…" else "Atualizar",
                    onClick = onApplyUpdate,
                    enabled = !applyingUpdate,
                    accent = C.AccentLight
                )
            }
        }
''',
    '''        if (managedMode && updateAvailable) {
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(C.Accent.copy(alpha = 0.16f))
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.SwapVert,
                    contentDescription = null,
                    tint = C.AccentLight,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Novas configurações disponíveis",
                        color = C.Text,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "Há mudanças no painel. Atualize todos os protocolos de uma vez.",
                        color = C.TextDim,
                        fontSize = 10.sp
                    )
                }
                Spacer(Modifier.width(8.dp))
                AutomBotGradientButton(
                    text = if (applyingUpdate) "Atualizando…" else "Atualizar",
                    onClick = onApplyUpdate,
                    enabled = !applyingUpdate,
                    accent = C.AccentLight
                )
            }
        } else if (managedMode) {
            Spacer(Modifier.height(14.dp))
            AutomBotCard(modifier = Modifier.fillMaxWidth()) {
                Text("Configurações do painel", color = C.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "O app verifica mudanças ao voltar para o primeiro plano. Você também pode conferir agora.",
                    color = C.TextDim,
                    fontSize = 10.sp
                )
                Spacer(Modifier.height(10.dp))
                AutomBotGradientButton(
                    text = if (checkingUpdate) "Verificando…" else "Verificar atualizações",
                    onClick = onCheckUpdate,
                    enabled = !checkingUpdate && !applyingUpdate,
                    modifier = Modifier.fillMaxWidth(),
                    accent = C.AccentLight
                )
            }
        }
''',
)

print("Patch applied successfully")
