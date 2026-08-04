package com.autombot.client.ui.manual

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autombot.client.protocols.ssh.*
import com.autombot.client.ui.theme.AutomBotColors as C

/**
 * Tela de configuracao SSH — redesenhada a pedido do usuario: em vez de um unico
 * seletor de "modo de transporte" com combinacoes fixas, a base e so servidor+porta e
 * cada camada (Proxy, Payload, SSL/TLS, WebSocket) e um toggle independente que o
 * usuario liga e combina do jeito dele (ver SshModels.kt).
 *
 * Ainda NAO conecta de verdade como VPN de sistema — so sobe um proxy SOCKS5 local
 * (ver SshTunnelManager.kt / SshScreen.kt).
 */
@Composable
fun SshConfigScreen(
    initialConfig: SshConnectionConfig? = null,
    onBack: () -> Unit,
    onSave: (SshConnectionConfig) -> Unit
) {
    var name by remember { mutableStateOf(initialConfig?.connectionName ?: "Minha Conexão SSH") }
    var server by remember { mutableStateOf(initialConfig?.server ?: "") }
    var port by remember { mutableStateOf(initialConfig?.port ?: "22") }
    var username by remember { mutableStateOf(initialConfig?.username ?: "") }
    var authMethod by remember { mutableStateOf(initialConfig?.authMethod ?: SshAuthMethod.PASSWORD) }
    var password by remember { mutableStateOf(initialConfig?.password ?: "") }
    var privateKeyPem by remember { mutableStateOf(initialConfig?.privateKeyPem ?: "") }
    var connectionTimeout by remember { mutableStateOf(initialConfig?.connectionTimeoutSeconds ?: "10") }

    var useProxy by remember { mutableStateOf(initialConfig?.useProxy ?: false) }
    var proxyType by remember { mutableStateOf(initialConfig?.proxyType ?: ProxyType.SOCKS5) }
    var proxyHost by remember { mutableStateOf(initialConfig?.proxyHost ?: "") }
    var proxyPort by remember { mutableStateOf(initialConfig?.proxyPort ?: "") }
    var proxyUsername by remember { mutableStateOf(initialConfig?.proxyUsername ?: "") }
    var proxyPassword by remember { mutableStateOf(initialConfig?.proxyPassword ?: "") }

    var usePayload by remember { mutableStateOf(initialConfig?.usePayload ?: false) }
    var payload by remember { mutableStateOf(initialConfig?.payload ?: "") }

    var useSslTls by remember { mutableStateOf(initialConfig?.useSslTls ?: false) }
    var sni by remember { mutableStateOf(initialConfig?.sni ?: "") }

    var useWebSocket by remember { mutableStateOf(initialConfig?.useWebSocket ?: false) }
    var wsHost by remember { mutableStateOf(initialConfig?.wsHost ?: "") }
    var wsPath by remember { mutableStateOf(initialConfig?.wsPath ?: "/") }

    var useSlowDns by remember { mutableStateOf(initialConfig?.useSlowDns ?: false) }
    var slowDnsDomain by remember { mutableStateOf(initialConfig?.slowDnsDomain ?: "") }
    var slowDnsPubkey by remember { mutableStateOf(initialConfig?.slowDnsPubkey ?: "") }
    var slowDnsResolverMode by remember { mutableStateOf(initialConfig?.slowDnsResolverMode ?: SlowDnsResolverMode.UDP) }
    var slowDnsResolver by remember { mutableStateOf(initialConfig?.slowDnsResolver ?: "") }

    var compression by remember { mutableStateOf(initialConfig?.compression ?: false) }
    var disableTcpDelay by remember { mutableStateOf(initialConfig?.disableTcpDelay ?: false) }
    var dnsForwarding by remember { mutableStateOf(initialConfig?.dnsForwardingEnabled ?: false) }
    var dnsPrimary by remember { mutableStateOf(initialConfig?.dnsPrimary ?: "8.8.8.8") }
    var dnsSecondary by remember { mutableStateOf(initialConfig?.dnsSecondary ?: "8.8.4.4") }
    var udpForward by remember { mutableStateOf(initialConfig?.udpForwardEnabled ?: false) }
    var udpGatewayHost by remember { mutableStateOf(initialConfig?.udpGatewayHost ?: "127.0.0.1") }
    var udpGatewayPort by remember { mutableStateOf(initialConfig?.udpGatewayPort ?: "7300") }
    var showAdvanced by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(C.Background)) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Voltar", tint = C.Text)
            }
            Text(if (initialConfig != null) "Editar SSH" else "Configurar SSH", color = C.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { SectionLabel("Básico") }
            item { LabeledField("Nome da conexão", name) { name = it } }
            item { LabeledField("Servidor", server, placeholder = "ssh.exemplo.com") { server = it } }
            item { LabeledField("Porta", port, keyboardType = KeyboardType.Number) { port = it } }
            item { LabeledField("Usuário", username, placeholder = "seu_usuario") { username = it } }

            item {
                SectionLabel("Autenticação")
                SegmentedChoice(options = SshAuthMethod.entries, selected = authMethod, label = { it.label }, onSelect = { authMethod = it })
            }
            if (authMethod == SshAuthMethod.PASSWORD) {
                item { LabeledField("Senha", password, isPassword = true) { password = it } }
            } else {
                item {
                    LabeledField("Chave privada (PEM)", privateKeyPem, placeholder = "-----BEGIN OPENSSH PRIVATE KEY-----...", singleLine = false, height = 100.dp) { privateKeyPem = it }
                }
            }
            item { LabeledField("Tempo de conexão / timeout (segundos)", connectionTimeout, keyboardType = KeyboardType.Number) { connectionTimeout = it } }

            item { SectionLabel("Camadas de conexão") }
            item {
                Text(
                    "Ligue as camadas que quiser combinar. Sem nenhuma ligada, conecta direto no servidor.",
                    color = C.TextDim,
                    fontSize = 11.sp
                )
            }

            item {
                ExpandableLayer(title = "Proxy", subtitle = "SOCKS5 ou HTTP antes do servidor SSH", enabled = useProxy, onToggle = { useProxy = it }) {
                    SegmentedChoice(options = ProxyType.entries, selected = proxyType, label = { it.label }, onSelect = { proxyType = it })
                    Spacer(Modifier.height(8.dp))
                    LabeledField("Host do proxy", proxyHost, placeholder = "127.0.0.1") { proxyHost = it }
                    Spacer(Modifier.height(8.dp))
                    LabeledField("Porta do proxy", proxyPort, keyboardType = KeyboardType.Number) { proxyPort = it }
                    Spacer(Modifier.height(8.dp))
                    LabeledField("Usuário do proxy (opcional)", proxyUsername) { proxyUsername = it }
                    Spacer(Modifier.height(8.dp))
                    LabeledField("Senha do proxy (opcional)", proxyPassword, isPassword = true) { proxyPassword = it }
                }
            }

            item {
                ExpandableLayer(title = "Payload", subtitle = "Bytes customizados enviados antes do handshake SSH", enabled = usePayload, onToggle = { usePayload = it }) {
                    LabeledField(
                        "Payload",
                        payload,
                        placeholder = "GET / HTTP/1.1[crlf]Host: [host][crlf][crlf]",
                        singleLine = false,
                        height = 100.dp
                    ) { payload = it }
                }
            }

            item {
                ExpandableLayer(title = "SSL/TLS", subtitle = "Envolve a conexão em TLS, com SNI de fachada", enabled = useSslTls, onToggle = { useSslTls = it }) {
                    LabeledField("SNI (domínio de fachada)", sni, placeholder = "www.exemplo.com") { sni = it }
                }
            }

            item {
                ExpandableLayer(
                    title = "WebSocket",
                    subtitle = "SSH encapsulado em frames WebSocket (usa TLS junto se SSL/TLS também estiver ligado)",
                    enabled = useWebSocket,
                    onToggle = { useWebSocket = it }
                ) {
                    LabeledField("Host (cabeçalho Host)", wsHost, placeholder = "seudominio.com") { wsHost = it }
                    Spacer(Modifier.height(8.dp))
                    LabeledField("Path", wsPath, placeholder = "/") { wsPath = it }
                }
            }

            item {
                ExpandableLayer(
                    title = "SlowDNS",
                    subtitle = "Tunela a conexão inteira disfarçada de tráfego DNS comum — ignora Proxy/conexão direta quando ligado",
                    enabled = useSlowDns,
                    onToggle = { useSlowDns = it }
                ) {
                    LabeledField("Domínio do túnel", slowDnsDomain, placeholder = "t.seudominio.com") { slowDnsDomain = it }
                    Spacer(Modifier.height(8.dp))
                    LabeledField("Chave pública do servidor (hex)", slowDnsPubkey, placeholder = "gerada com \"dnstt-server -gen-key\"") { slowDnsPubkey = it }
                    Spacer(Modifier.height(8.dp))
                    SegmentedChoice(options = SlowDnsResolverMode.entries, selected = slowDnsResolverMode, label = { it.label }, onSelect = { slowDnsResolverMode = it })
                    Spacer(Modifier.height(8.dp))
                    LabeledField(
                        "Resolvedor DNS",
                        slowDnsResolver,
                        placeholder = when (slowDnsResolverMode) {
                            SlowDnsResolverMode.UDP -> "8.8.8.8:53"
                            SlowDnsResolverMode.DOH -> "https://cloudflare-dns.com/dns-query"
                            SlowDnsResolverMode.DOT -> "dns.google:853"
                        }
                    ) { slowDnsResolver = it }
                }
            }

            item {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { showAdvanced = !showAdvanced }) {
                    Text("Configurações avançadas", color = C.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Icon(Icons.Default.ExpandMore, contentDescription = null, tint = C.TextDim, modifier = Modifier.rotate(if (showAdvanced) 180f else 0f))
                }
            }

            if (showAdvanced) {
                item { ToggleRow("Compressão", "Acelera a transferência, mas usa mais memória.", compression) { compression = it } }
                item { ToggleRow("Desativar TCP delay", "Reduz latência (TCP_NODELAY), gera mais pacotes pequenos.", disableTcpDelay) { disableTcpDelay = it } }
                item {
                    SectionLabel("DNS")
                    ToggleRow("Forçar DNS customizado", "", dnsForwarding) { dnsForwarding = it }
                }
                if (dnsForwarding) {
                    item { LabeledField("DNS primário", dnsPrimary) { dnsPrimary = it } }
                    item { LabeledField("DNS secundário", dnsSecondary) { dnsSecondary = it } }
                }
                item {
                    SectionLabel("Encaminhamento UDP (badvpn/udpgw)")
                    ToggleRow("Ativar", "Necessário para apps que dependem de UDP (jogos, chamadas).", udpForward) { udpForward = it }
                }
                if (udpForward) {
                    item { LabeledField("Gateway UDP (host)", udpGatewayHost) { udpGatewayHost = it } }
                    item { LabeledField("Gateway UDP (porta)", udpGatewayPort, keyboardType = KeyboardType.Number) { udpGatewayPort = it } }
                }
            }

            item {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        onSave(
                            SshConnectionConfig(
                                connectionName = name.ifBlank { "Minha Conexão SSH" },
                                server = server,
                                port = port.ifBlank { "22" },
                                username = username,
                                authMethod = authMethod,
                                password = password,
                                privateKeyPem = privateKeyPem,
                                compression = compression,
                                disableTcpDelay = disableTcpDelay,
                                connectionTimeoutSeconds = connectionTimeout.ifBlank { "10" },
                                useProxy = useProxy,
                                proxyType = proxyType,
                                proxyHost = proxyHost,
                                proxyPort = proxyPort,
                                proxyUsername = proxyUsername,
                                proxyPassword = proxyPassword,
                                usePayload = usePayload,
                                payload = payload,
                                useSslTls = useSslTls,
                                sni = sni,
                                useWebSocket = useWebSocket,
                                wsHost = wsHost,
                                wsPath = wsPath,
                                useSlowDns = useSlowDns,
                                slowDnsDomain = slowDnsDomain,
                                slowDnsPubkey = slowDnsPubkey,
                                slowDnsResolverMode = slowDnsResolverMode,
                                slowDnsResolver = slowDnsResolver,
                                dnsForwardingEnabled = dnsForwarding,
                                dnsPrimary = dnsPrimary,
                                dnsSecondary = dnsSecondary,
                                udpForwardEnabled = udpForward,
                                udpGatewayHost = udpGatewayHost,
                                udpGatewayPort = udpGatewayPort
                            )
                        )
                    },
                    enabled = server.isNotBlank() && username.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = C.Accent, contentColor = C.OnPrimary)
                ) {
                    Text("Salvar Conexão")
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun ExpandableLayer(
    title: String,
    subtitle: String,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(C.Surface)
            .border(1.dp, if (enabled) C.Accent.copy(alpha = 0.5f) else C.Line, RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = C.TextDim, fontSize = 10.sp)
            }
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(checkedThumbColor = C.Accent, checkedTrackColor = C.Accent.copy(alpha = 0.35f))
            )
        }
        if (enabled) {
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = C.TextDim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
}

@Composable
private fun <T> SegmentedChoice(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            val active = option == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (active) C.Accent else C.SurfaceAlt)
                    .border(1.dp, if (active) C.Accent else C.Line, RoundedCornerShape(10.dp))
                    .clickable { onSelect(option) }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(label(option), color = if (active) C.OnPrimary else C.TextDim, fontSize = 11.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = C.Text, fontSize = 13.sp)
            if (subtitle.isNotEmpty()) Text(subtitle, color = C.TextDim, fontSize = 10.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedThumbColor = C.Accent, checkedTrackColor = C.Accent.copy(alpha = 0.35f)))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LabeledField(
    label: String,
    value: String,
    placeholder: String = "",
    isPassword: Boolean = false,
    singleLine: Boolean = true,
    height: Dp? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit
) {
    Column {
        Text(label, color = C.TextDim, fontSize = 11.sp)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            placeholder = { if (placeholder.isNotEmpty()) Text(placeholder) },
            singleLine = singleLine,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier.fillMaxWidth().let { if (height != null) it.height(height) else it },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = C.Text,
                unfocusedTextColor = C.Text,
                focusedBorderColor = C.Accent,
                unfocusedBorderColor = C.Line,
                cursorColor = C.Accent,
                focusedPlaceholderColor = C.TextDim,
                unfocusedPlaceholderColor = C.TextDim
            )
        )
    }
}