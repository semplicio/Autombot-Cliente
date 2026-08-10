package com.autombot.client.ui.openvpn

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
import com.autombot.client.protocols.openvpn.ManagedOpenVpnConnection
import com.autombot.client.protocols.openvpn.OpenVpnStatus
import com.autombot.client.protocols.openvpn.OpenVpnTunnelManager
import com.autombot.client.ui.theme.AutomBotColors as C

/**
 * Tela de OpenVPN — a conexão real roda dentro do AutomBotVpnService porque o
 * OpenVPN assume a TUN Android diretamente.
 */
@Composable
fun OpenVpnScreen(
    manager: OpenVpnTunnelManager,
    onBack: () -> Unit,
    onAddProfile: () -> Unit,
    onConnect: (config: com.autombot.client.protocols.openvpn.OpenVpnConnectionConfig) -> Unit,
    onDisconnect: () -> Unit
) {
    val connections by manager.connections.collectAsState()

    Column(modifier = Modifier.fillMaxSize().background(C.Background)) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Voltar", tint = C.Text)
            }
            Text("OpenVPN", color = C.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }

        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(C.SurfaceAlt)
                .padding(12.dp)
        ) {
            Text(
                "Importe um arquivo .ovpn (com certificados embutidos). Diferente dos outros " +
                    "protocolos, o OpenVPN assume a VPN do sistema inteira sozinho — só uma conexão " +
                    "por vez, aqui ou nos outros protocolos.",
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
                Text("Nenhuma conexão OpenVPN configurada", color = C.TextDim, fontSize = 13.sp)
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onAddProfile,
                    colors = ButtonDefaults.buttonColors(containerColor = C.Accent, contentColor = C.OnPrimary)
                ) { Text("Importar .ovpn") }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(connections, key = { it.config.connectionName }) { conn ->
                    OpenVpnConnectionCard(
                        conn = conn,
                        onToggle = {
                            if (conn.status == OpenVpnStatus.CONNECTED) {
                                // ACTION_STOP também é usado automaticamente pelo roteador
                                // dos protocolos SOCKS. Marcamos antes que este STOP veio
                                // explicitamente do botão do OpenVPN.
                                manager.requestDisconnect(conn.config.connectionName)
                                onDisconnect()
                            } else {
                                onConnect(conn.config)
                            }
                        },
                        onDelete = { manager.removeProfile(conn.config.connectionName) }
                    )
                }
                item {
                    Button(
                        onClick = onAddProfile,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = C.Accent, contentColor = C.OnPrimary)
                    ) { Text("Importar .ovpn") }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
private fun OpenVpnConnectionCard(
    conn: ManagedOpenVpnConnection,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val isBusy = conn.status == OpenVpnStatus.CONNECTING
    val (statusLabel, statusColor) = when (conn.status) {
        OpenVpnStatus.CONNECTED -> "Conectado" to C.Green
        OpenVpnStatus.CONNECTING -> "Conectando…" to C.Accent
        OpenVpnStatus.ERROR -> "Erro" to C.Red
        OpenVpnStatus.DISCONNECTED -> "Desconectado" to C.TextDim
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(C.Surface)
            .border(1.dp, if (conn.status == OpenVpnStatus.CONNECTED) C.Green.copy(alpha = 0.35f) else C.Line, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(conn.config.connectionName, color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text("Arquivo .ovpn importado", color = C.TextDim, fontSize = 12.sp)
            }
            if (isBusy) {
                CircularProgressIndicator(color = C.Accent, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
            }
            Switch(
                checked = conn.status == OpenVpnStatus.CONNECTED,
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

        if (conn.status == OpenVpnStatus.CONNECTED) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TrafficChip(label = "↓ Recebido", value = formatOpenVpnBytes(conn.rxBytes))
                TrafficChip(label = "↑ Enviado", value = formatOpenVpnBytes(conn.txBytes))
            }
        }

        if (conn.status == OpenVpnStatus.ERROR && conn.lastError != null) {
            Spacer(Modifier.height(8.dp))
            Text(conn.lastError, color = C.Red, fontSize = 11.sp)
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "Excluir",
            color = C.Red,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.clickable { showDeleteConfirm = true }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Excluir conexão?") },
            text = { Text("\"${conn.config.connectionName}\" e o arquivo .ovpn dela vão ser removidos. Essa ação não pode ser desfeita.") },
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

private fun formatOpenVpnBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
    return String.format("%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}
