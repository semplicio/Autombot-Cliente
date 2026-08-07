package com.autombot.client.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autombot.client.ui.components.AutomBotCard
import com.autombot.client.ui.components.AutomBotGradientButton
import com.autombot.client.ui.components.AutomBotStatusDot
import com.autombot.client.ui.components.AutomBotTopBar
import com.autombot.client.ui.components.protocolVisual
import com.autombot.client.ui.theme.AutomBotColors as C

data class ConnectionRow(
    val protocolId: String,
    val displayName: String,
    val statusLabel: String,
    val connected: Boolean,
    val available: Boolean
)

@Composable
fun ConnectionsScreen(
    connections: List<ConnectionRow>,
    onBack: () -> Unit,
    onOpenConnection: (ConnectionRow) -> Unit,
    onNewConnection: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        AutomBotTopBar("Minhas conexões", onBack, "Minha rede")
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Text("Protocolos configurados neste dispositivo", color = C.TextDim, fontSize = 11.sp)
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(connections, key = { it.protocolId }) { conn ->
                ConnectionRowItem(conn, onClick = { if (conn.available) onOpenConnection(conn) })
            }
            item {
                AutomBotGradientButton(
                    text = "Nova conexão",
                    onClick = onNewConnection,
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = Icons.Default.Add
                )
                Spacer(Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun ConnectionRowItem(conn: ConnectionRow, onClick: () -> Unit) {
    val (icon, protocolColor) = protocolVisual(conn.protocolId)
    AutomBotCard(
        modifier = Modifier.fillMaxWidth(),
        accent = if (conn.connected) C.Green else null,
        padding = 12.dp,
        onClick = onClick
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(protocolColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = protocolColor, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(conn.displayName, color = if (conn.available) C.Text else C.TextMuted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                AutomBotStatusDot(
                    color = when {
                        !conn.available -> C.TextMuted
                        conn.connected -> C.Green
                        else -> C.TextDim
                    },
                    label = conn.statusLabel
                )
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = C.TextMuted, modifier = Modifier.size(18.dp))
        }
    }
}
