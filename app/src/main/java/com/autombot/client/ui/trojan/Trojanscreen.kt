package com.autombot.client.ui.trojan

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autombot.client.protocols.trojan.ManagedTrojanConnection
import com.autombot.client.protocols.trojan.TrojanStatus
import com.autombot.client.protocols.trojan.TrojanTunnelManager
import com.autombot.client.protocols.trojan.describeTransport
import com.autombot.client.ui.rememberManagedMode
import com.autombot.client.ui.theme.AutomBotColors as C
import kotlinx.coroutines.launch

/**
 * Tela de Trojan — mesmo padrao do SSH (ver ui/ssh/SshScreen.kt): lista de perfis,
 * conectar sobe proxy SOCKS5 local. O roteamento da VPN de sistema NAO e mais
 * decidido aqui — ver MainShell em MainActivity.kt (decisao centralizada).
 */
@Composable
fun TrojanScreen(
    manager: TrojanTunnelManager,
    onBack: () -> Unit,
    onAddProfile: () -> Unit,
    onViewLog: (String) -> Unit
) {
    val connections by manager.connections.collectAsState()
    val scope = rememberCoroutineScope()
    val managedMode = rememberManagedMode()

    Column(modifier = Modifier.fillMaxSize().background(C.Background)) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Voltar", tint = C.Text)
            }
            Text("Trojan", color = C.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }

        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(C.SurfaceAlt)
                .padding(12.dp)
        ) {
            Text(
                "TCP direto sempre com TLS (obrigatório no protocolo Trojan). " +
                    "Conectar agora substitui a VPN do sistema automaticamente, igual ao WireGuard.",
                color = C.TextDim,
                fontSize = 11.sp
            )
        }
        Spacer(Modifier.height(12.dp))

        if (connections.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("Nenhuma conexão Trojan configurada", color = C.TextDim, fontSize = 13.sp)
                if (!managedMode) {
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = onAddProfile,
                        colors = ButtonDefaults.buttonColors(containerColor = C.Accent, contentColor = C.OnPrimary)
                    ) { Text("Colar link trojan://") }
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(connections, key = { it.config.connectionName }) { conn ->
                    TrojanConnectionCard(
                        conn = conn,
                        allowDelete = !managedMode,
                        onToggle = {
                            scope.launch {
                                if (conn.status == TrojanStatus.CONNECTED) manager.disconnect(conn.config.connectionName)
                                else manager.connect(conn.config.connectionName)
                            }
                        },
                        onViewLog = { onViewLog(conn.config.connectionName) },
                        onDelete = { manager.removeProfile(conn.config.connectionName) }
                    )
                }
                if (!managedMode) {
                    item {
                        Button(
                            onClick = onAddProfile,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = C.Accent, contentColor = C.OnPrimary)
                        ) { Text("Colar link trojan://") }
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun TrojanConnectionCard(
    conn: ManagedTrojanConnection,
    allowDelete: Boolean,
    onToggle: () -> Unit,
    onViewLog: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val isBusy = conn.status == TrojanStatus.CONNECTING
    val (statusLabel, statusColor) = when (conn.status) {
        TrojanStatus.CONNECTED -> "Conectado" to C.Green
        TrojanStatus.CONNECTING -> "Conectando…" to C.Accent
        TrojanStatus.ERROR -> "Erro" to C.Red
        TrojanStatus.DISCONNECTED -> "Desconectado" to C.TextDim
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(C.Surface)
            .border(1.dp, if (conn.status == TrojanStatus.CONNECTED) C.Green.copy(alpha = 0.35f) else C.Line, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(conn.config.connectionName, color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(
                    "${conn.config.server}:${conn.config.port} · ${conn.config.describeTransport()}",
                    color = C.TextDim,
                    fontSize = 12.sp
                )
            }
            if (isBusy) {
                CircularProgressIndicator(color = C.Accent, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
            }
            Switch(
                checked = conn.status == TrojanStatus.CONNECTED,
                onCheckedChange = { onToggle() },
                enabled = !isBusy,
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

        if (conn.status == TrojanStatus.CONNECTED && conn.localSocksPort != null) {
            Spacer(Modifier.height(10.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(C.SurfaceAlt)
                    .padding(10.dp)
            ) {
                Text("Proxy SOCKS5 local", color = C.TextDim, fontSize = 10.sp)
                Text("127.0.0.1:${conn.localSocksPort}", color = C.Accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TrafficChip(label = "↓ Recebido", value = formatTrojanBytes(conn.rxBytes))
                TrafficChip(label = "↑ Enviado", value = formatTrojanBytes(conn.txBytes))
            }
        }

        if (conn.status == TrojanStatus.ERROR && conn.lastError != null) {
            Spacer(Modifier.height(8.dp))
            Text(conn.lastError, color = C.Red, fontSize = 11.sp)
        }

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                "Ver log",
                color = C.TextDim,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable(onClick = onViewLog)
            )
            if (allowDelete) {
                Text(
                    "Excluir",
                    color = C.Red,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable { showDeleteConfirm = true }
                )
            }
        }
    }

    if (allowDelete && showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Excluir conexão?") },
            text = { Text("\"${conn.config.connectionName}\" vai ser removida. Essa ação não pode ser desfeita.") },
            confirmButton = {
                TextButton(onClick = { showDeleteConfirm = false; onDelete() }) {
                    Text("Excluir", color = C.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancelar") }
            },
            containerColor = C.Surface,
            titleContentColor = C.Text,
            textContentColor = C.TextDim
        )
    }
}

@Composable
private fun TrafficChip(label: String, value: String) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(C.SurfaceAlt)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(label, color = C.TextDim, fontSize = 10.sp)
        Spacer(Modifier.height(2.dp))
        Text(value, color = C.Accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

private fun formatTrojanBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
    return String.format("%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}