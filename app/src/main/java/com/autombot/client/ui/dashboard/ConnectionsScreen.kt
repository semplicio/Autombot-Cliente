package com.autombot.client.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autombot.client.ui.theme.AutomBotColors as C

data class ConnectionRow(
    val protocolId: String,
    val displayName: String,
    val statusLabel: String,
    val connected: Boolean,
    val available: Boolean
)

/**
 * Tela 08 do mockup: lista de conexões por protocolo. Hoje so o WireGuard tem
 * integracao real (ver protocols/wireguard/ *) — os demais aparecem como "Em breve"
 * ate os drivers correspondentes serem implementados (SPEC.md secao 2/9).
 */
@Composable
fun ConnectionsScreen(
    connections: List<ConnectionRow>,
    onOpenConnection: (ConnectionRow) -> Unit,
    onNewConnection: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().background(C.Background)) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("Minhas Conexões", color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(connections, key = { it.protocolId }) { conn ->
                ConnectionRowItem(conn, onClick = { if (conn.available) onOpenConnection(conn) })
            }
            item {
                Button(
                    onClick = onNewConnection,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = C.Primary, contentColor = C.OnPrimary)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Nova Conexão")
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun ConnectionRowItem(conn: ConnectionRow, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(C.Surface)
            .border(1.dp, C.Line, RoundedCornerShape(14.dp))
            .clickable(enabled = conn.available, onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                conn.displayName,
                color = if (conn.available) C.Text else C.TextDim,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
        val statusColor = when {
            !conn.available -> C.TextDim
            conn.connected -> C.Green
            else -> C.TextDim
        }
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(statusColor)
        )
        Spacer(Modifier.width(6.dp))
        Text(conn.statusLabel, color = statusColor, fontSize = 12.sp)
    }
}
