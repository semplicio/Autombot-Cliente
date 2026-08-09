package com.autombot.client.ui.modern

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autombot.client.protocols.modern.ManagedModernConnection
import com.autombot.client.protocols.modern.ModernProtocolStatus
import com.autombot.client.protocols.modern.ModernProtocolTunnelManager
import com.autombot.client.protocols.modern.ModernProtocolType
import com.autombot.client.ui.components.AutomBotGradientButton
import com.autombot.client.ui.components.AutomBotTopBar
import com.autombot.client.ui.theme.AutomBotColors as C
import kotlinx.coroutines.launch

@Composable
fun ModernProtocolScreen(
    manager: ModernProtocolTunnelManager,
    type: ModernProtocolType,
    onBack: () -> Unit,
    onAddProfile: () -> Unit,
    onViewLog: (String) -> Unit
) {
    val allConnections by manager.connections.collectAsState()
    val connections = allConnections.filter { it.config.type == type }
    val scope = rememberCoroutineScope()
    val coreAvailable = remember { manager.coreAvailable() }

    Column(modifier = Modifier.fillMaxSize().background(C.Background)) {
        AutomBotTopBar(type.displayName, onBack, "Núcleo sing-box")

        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(C.SurfaceAlt)
                .padding(12.dp)
        ) {
            Text(
                if (coreAvailable)
                    "Núcleo moderno disponível. ${type.displayName} usa QUIC/UDP e entrega um proxy SOCKS5 local ao motor HEV do AutomBot."
                else
                    "Núcleo sing-box não está dentro deste APK. Execute scripts/fetch_singbox_android_core.sh antes de compilar.",
                color = if (coreAvailable) C.TextDim else C.Red,
                fontSize = 11.sp
            )
        }

        if (connections.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("Nenhum perfil ${type.displayName} configurado", color = C.TextDim, fontSize = 13.sp)
                Spacer(Modifier.height(16.dp))
                AutomBotGradientButton(
                    text = "Importar link ${if (type == ModernProtocolType.HYSTERIA2) "hysteria2://" else "tuic://"}",
                    onClick = onAddProfile,
                    modifier = Modifier.fillMaxWidth(),
                    accent = C.Accent
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(connections, key = { it.config.connectionName }) { conn ->
                    ModernConnectionCard(
                        conn = conn,
                        onToggle = {
                            scope.launch {
                                if (conn.status == ModernProtocolStatus.CONNECTED) {
                                    manager.disconnect(conn.config.connectionName)
                                } else {
                                    manager.connect(conn.config.connectionName)
                                }
                            }
                        },
                        onViewLog = { onViewLog(conn.config.connectionName) },
                        onDelete = { manager.removeProfile(conn.config.connectionName) }
                    )
                }
                item {
                    AutomBotGradientButton(
                        text = "Importar novo link",
                        onClick = onAddProfile,
                        modifier = Modifier.fillMaxWidth(),
                        accent = C.Accent
                    )
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
fun ModernProtocolAddScreen(
    manager: ModernProtocolTunnelManager,
    type: ModernProtocolType,
    onBack: () -> Unit,
    onSaved: () -> Unit
) {
    var uri by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val expectedScheme = if (type == ModernProtocolType.HYSTERIA2) "hysteria2://" else "tuic://"

    Column(modifier = Modifier.fillMaxSize().background(C.Background)) {
        AutomBotTopBar("Adicionar ${type.displayName}", onBack, "Importar configuração")

        Column(modifier = Modifier.weight(1f).padding(horizontal = 20.dp, vertical = 16.dp)) {
            Text("Link de conexão", color = C.TextDim, fontSize = 11.sp)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = uri,
                onValueChange = { uri = it; error = null },
                modifier = Modifier.fillMaxWidth(),
                minLines = 5,
                maxLines = 8,
                placeholder = { Text("$expectedScheme…") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = C.Text,
                    unfocusedTextColor = C.Text,
                    focusedBorderColor = C.Accent,
                    unfocusedBorderColor = C.Line,
                    cursorColor = C.Accent,
                    focusedPlaceholderColor = C.TextDim,
                    unfocusedPlaceholderColor = C.TextDim
                ),
                shape = RoundedCornerShape(13.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                when (type) {
                    ModernProtocolType.HYSTERIA2 -> "Aceita usuário/senha, TLS/SNI e Salamander obfs presentes no link emitido pelo AutomBot Core."
                    ModernProtocolType.TUIC -> "Aceita UUID/senha, SNI, ALPN e controle de congestionamento presentes no link emitido pelo AutomBot Core."
                },
                color = C.TextDim,
                fontSize = 11.sp
            )
            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = C.Red, fontSize = 11.sp)
            }
        }

        Column(modifier = Modifier.padding(20.dp)) {
            AutomBotGradientButton(
                text = "Importar perfil",
                onClick = {
                    runCatching { manager.importUri(uri) }
                        .onSuccess { parsed ->
                            if (parsed.type != type) {
                                manager.removeProfile(parsed.connectionName)
                                error = "Esse link é ${parsed.type.displayName}, não ${type.displayName}."
                            } else {
                                onSaved()
                            }
                        }
                        .onFailure { error = it.message ?: "Link inválido" }
                },
                enabled = uri.trim().startsWith(expectedScheme, ignoreCase = true) ||
                    (type == ModernProtocolType.HYSTERIA2 && uri.trim().startsWith("hy2://", ignoreCase = true)),
                modifier = Modifier.fillMaxWidth(),
                accent = C.Accent
            )
        }
    }
}

@Composable
private fun ModernConnectionCard(
    conn: ManagedModernConnection,
    onToggle: () -> Unit,
    onViewLog: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val busy = conn.status == ModernProtocolStatus.CONNECTING
    val (statusLabel, statusColor) = when (conn.status) {
        ModernProtocolStatus.CONNECTED -> "Conectado" to C.Green
        ModernProtocolStatus.CONNECTING -> "Conectando…" to C.Accent
        ModernProtocolStatus.ERROR -> "Erro" to C.Red
        ModernProtocolStatus.DISCONNECTED -> "Desconectado" to C.TextDim
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(C.Surface)
            .border(1.dp, if (conn.status == ModernProtocolStatus.CONNECTED) C.Green.copy(alpha = 0.35f) else C.Line, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(conn.config.connectionName, color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text("${conn.config.server}:${conn.config.port}", color = C.TextDim, fontSize = 12.sp)
            }
            if (busy) {
                CircularProgressIndicator(color = C.Accent, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
            }
            Switch(
                checked = conn.status == ModernProtocolStatus.CONNECTED,
                onCheckedChange = { onToggle() },
                enabled = !busy,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = C.Accent,
                    checkedTrackColor = C.Accent.copy(alpha = 0.35f),
                    uncheckedThumbColor = C.TextDim,
                    uncheckedTrackColor = C.SurfaceAlt
                )
            )
        }

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(statusColor))
            Spacer(Modifier.width(6.dp))
            Text(statusLabel, color = statusColor, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }

        if (conn.status == ModernProtocolStatus.CONNECTED && conn.localSocksPort != null) {
            Spacer(Modifier.height(10.dp))
            Text("SOCKS5 local 127.0.0.1:${conn.localSocksPort}", color = C.Accent, fontSize = 11.sp)
        }
        if (conn.status == ModernProtocolStatus.ERROR && !conn.lastError.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(conn.lastError, color = C.Red, fontSize = 11.sp)
        }

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Ver log", color = C.TextDim, fontSize = 11.sp, modifier = Modifier.clickable(onClick = onViewLog))
            Text("Excluir", color = C.Red, fontSize = 11.sp, modifier = Modifier.clickable { showDeleteConfirm = true })
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Excluir conexão?") },
            text = { Text("\"${conn.config.connectionName}\" será removida.") },
            confirmButton = {
                TextButton(onClick = { showDeleteConfirm = false; onDelete() }) { Text("Excluir", color = C.Red) }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancelar") } },
            containerColor = C.Surface,
            titleContentColor = C.Text,
            textContentColor = C.TextDim
        )
    }
}
