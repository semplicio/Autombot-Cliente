package com.autombot.client.ui.wireguard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autombot.client.protocols.wireguard.ManagedTunnel
import com.autombot.client.protocols.wireguard.TunnelStatus
import com.autombot.client.protocols.wireguard.WireGuardManager
import com.autombot.client.ui.rememberManagedMode
import com.autombot.client.ui.theme.AutomBotColors as C
import com.wireguard.android.backend.Tunnel
import kotlinx.coroutines.launch

@Composable
fun WireGuardScreen(
    manager: WireGuardManager,
    onBack: () -> Unit,
    onRequestVpnPermission: (onGranted: () -> Unit) -> Unit,
    onPickConfigFile: (onText: (fileName: String, text: String) -> Unit) -> Unit,
    onViewLog: (String) -> Unit
) {
    val tunnels by manager.tunnels.collectAsState()
    val managedMode = rememberManagedMode()
    var showImport by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(C.Background)
    ) {
        TopBar(title = "WireGuard", onBack = onBack)

        if (tunnels.isEmpty()) {
            EmptyState(allowAdd = !managedMode, onAddClick = { showImport = true })
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(tunnels, key = { it.name }) { tunnel ->
                    TunnelCard(
                        tunnel = tunnel,
                        allowDelete = !managedMode,
                        onToggle = {
                            onRequestVpnPermission {
                                scope.launch { manager.toggle(tunnel) }
                            }
                        },
                        onRefreshStats = { manager.refreshStatistics(tunnel) },
                        onViewLog = { onViewLog(tunnel.name) },
                        onDelete = { manager.removeTunnel(tunnel.name) }
                    )
                }
                if (!managedMode) {
                    item {
                        AddTunnelRow(onClick = { showImport = true })
                    }
                }
            }
        }
    }

    if (!managedMode && showImport) {
        ImportTunnelSheet(
            onDismiss = { showImport = false },
            onPickFile = onPickConfigFile,
            onConfirm = { name, text ->
                manager.importConfig(name, text)
                showImport = false
            }
        )
    }
}

@Composable
private fun TopBar(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Default.ArrowBack, contentDescription = "Voltar", tint = C.Text)
        }
        Text(
            text = title,
            color = C.Text,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp)
        )
    }
}

@Composable
private fun EmptyState(allowAdd: Boolean, onAddClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Nenhum túnel configurado", color = C.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Importe uma configuração WireGuard (.conf) colando o texto ou selecionando o arquivo.",
            color = C.TextDim,
            fontSize = 13.sp
        )
        if (allowAdd) {
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onAddClick,
                colors = ButtonDefaults.buttonColors(containerColor = C.Primary, contentColor = C.OnPrimary)
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Adicionar túnel")
            }
        }
    }
}

@Composable
private fun AddTunnelRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(C.Surface)
            .border(1.dp, C.Line, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Add, contentDescription = null, tint = C.Primary)
        Spacer(Modifier.width(10.dp))
        Text("Adicionar túnel", color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun TunnelCard(
    tunnel: ManagedTunnel,
    allowDelete: Boolean,
    onToggle: () -> Unit,
    onRefreshStats: () -> Unit,
    onViewLog: () -> Unit,
    onDelete: () -> Unit
) {
    LaunchedEffect(tunnel.status) {
        if (tunnel.status == TunnelStatus.CONNECTED) onRefreshStats()
    }

    var showDeleteConfirm by remember { mutableStateOf(false) }

    val connected = tunnel.state == Tunnel.State.UP
    val isBusy = tunnel.status == TunnelStatus.CONNECTING || tunnel.status == TunnelStatus.DISCONNECTING
    val (statusLabel, statusColor) = when (tunnel.status) {
        TunnelStatus.CONNECTED -> "Conectado" to C.Green
        TunnelStatus.CONNECTING -> "Conectando…" to C.Primary
        TunnelStatus.DISCONNECTING -> "Desconectando…" to C.Primary
        TunnelStatus.ERROR -> "Erro" to C.Red
        TunnelStatus.DISCONNECTED -> "Desconectado" to C.TextDim
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(C.Surface)
            .border(1.dp, if (connected) C.Green.copy(alpha = 0.35f) else C.Line, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(tunnel.name, color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(tunnel.endpointLabel, color = C.TextDim, fontSize = 12.sp)
            }
            if (isBusy) {
                CircularProgressIndicator(color = C.Primary, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
            }
            // Trava o Switch enquanto esta conectando/desconectando — antes dava pra
            // clicar varias vezes seguidas sem feedback nenhum, o que disparava toggles
            // sobrepostos e deixava a conexao instavel (cai e reconecta sozinha).
            Switch(
                checked = connected,
                onCheckedChange = { onToggle() },
                enabled = !isBusy,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = C.Primary,
                    checkedTrackColor = C.Primary.copy(alpha = 0.35f),
                    uncheckedThumbColor = C.TextDim,
                    uncheckedTrackColor = C.SurfaceAlt
                )
            )
        }

        Spacer(Modifier.height(10.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(statusColor)
            )
            Spacer(Modifier.width(6.dp))
            Text(statusLabel, color = statusColor, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }

        if (connected) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatChip(label = "↓ Recebido", value = formatBytes(tunnel.rxBytes))
                StatChip(label = "↑ Enviado", value = formatBytes(tunnel.txBytes))
            }
        }

        if (tunnel.status == TunnelStatus.ERROR && tunnel.lastError != null) {
            Spacer(Modifier.height(8.dp))
            Text(tunnel.lastError, color = C.Red, fontSize = 11.sp)
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
            title = { Text("Excluir túnel?") },
            text = { Text("\"${tunnel.name}\" vai ser removido. Essa ação não pode ser desfeita.") },
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
private fun StatChip(label: String, value: String) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportTunnelSheet(
    onDismiss: () -> Unit,
    onPickFile: (onText: (String, String) -> Unit) -> Unit,
    onConfirm: (name: String, configText: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var configText by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = C.Surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Text("Novo túnel WireGuard", color = C.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Nome do túnel (opcional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = fieldColors()
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = configText,
                onValueChange = { configText = it },
                label = { Text("Cole a config (.conf) ou o JSON do painel") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp),
                colors = fieldColors()
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Aceita tanto o .conf puro quanto o JSON que o AutomBot Core retorna " +
                    "(ex: {\"cliente\":...,\"config\":...}) — a config é extraída automaticamente.",
                color = C.TextDim,
                fontSize = 10.sp
            )

            Spacer(Modifier.height(10.dp))

            OutlinedButton(
                onClick = {
                    onPickFile { fileName, text ->
                        if (name.isBlank()) name = fileName.removeSuffix(".conf")
                        configText = text
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = C.Text)
            ) {
                Icon(Icons.Default.FileUpload, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Selecionar arquivo .conf")
            }

            Spacer(Modifier.height(18.dp))

            Button(
                onClick = { onConfirm(name, configText) },
                enabled = configText.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = C.Primary, contentColor = C.OnPrimary)
            ) {
                Text("Importar e salvar")
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = C.Text,
    unfocusedTextColor = C.Text,
    focusedBorderColor = C.Primary,
    unfocusedBorderColor = C.Line,
    focusedLabelColor = C.Primary,
    unfocusedLabelColor = C.TextDim,
    cursorColor = C.Primary
)

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    val safeGroup = digitGroups.coerceIn(0, units.size - 1)
    return String.format("%.1f %s", bytes / Math.pow(1024.0, safeGroup.toDouble()), units[safeGroup])
}