package com.autombot.client.ui.more

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autombot.client.protocols.modern.ModernProtocolManagerProvider
import com.autombot.client.protocols.modern.ModernProtocolStatus
import com.autombot.client.ui.components.AutomBotCard
import com.autombot.client.ui.components.AutomBotTopBar
import com.autombot.client.ui.theme.AutomBotColors as C

@Composable
fun StatisticsScreen(
    rxBytesLabel: String,
    txBytesLabel: String,
    totalLabel: String,
    downloadFraction: Float,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val modernManager = remember(context) { ModernProtocolManagerProvider.get(context) }
    val modernConnections by modernManager.connections.collectAsState()
    val activeModern = modernConnections.filter { it.status == ModernProtocolStatus.CONNECTED }
    val modernRx = activeModern.sumOf { it.rxBytes }
    val modernTx = activeModern.sumOf { it.txBytes }
    val modernTotal = modernRx + modernTx
    val modernFraction = if (modernTotal > 0L) modernRx.toFloat() / modernTotal.toFloat() else 0.5f

    Column(modifier = Modifier.fillMaxSize()) {
        AutomBotTopBar("Estatísticas", onBack, "Sessão atual")
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            AutomBotCard(modifier = Modifier.fillMaxWidth()) {
                Text("TRÁFEGO TOTAL DOS PROTOCOLOS CLÁSSICOS", color = C.TextDim, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                Text(totalLabel, color = C.Text, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(18.dp))
                Row(modifier = Modifier.fillMaxWidth().height(9.dp).clip(RoundedCornerShape(8.dp))) {
                    Box(Modifier.weight(downloadFraction.coerceAtLeast(0.01f)).fillMaxSize().background(C.Accent))
                    Box(Modifier.weight((1f - downloadFraction).coerceAtLeast(0.01f)).fillMaxSize().background(C.Primary))
                }
                Spacer(Modifier.height(16.dp))
                Row {
                    TrafficValue("Download", rxBytesLabel, C.AccentLight, Modifier.weight(1f))
                    TrafficValue("Upload", txBytesLabel, C.PrimaryLight, Modifier.weight(1f))
                }
            }

            if (activeModern.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                AutomBotCard(modifier = Modifier.fillMaxWidth()) {
                    val names = activeModern.joinToString(" + ") { it.config.type.displayName }
                    Text("TRÁFEGO $names", color = C.TextDim, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    Text(formatModernBytes(modernTotal), color = C.Text, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(18.dp))
                    Row(modifier = Modifier.fillMaxWidth().height(9.dp).clip(RoundedCornerShape(8.dp))) {
                        Box(Modifier.weight(modernFraction.coerceAtLeast(0.01f)).fillMaxSize().background(C.Accent))
                        Box(Modifier.weight((1f - modernFraction).coerceAtLeast(0.01f)).fillMaxSize().background(C.Primary))
                    }
                    Spacer(Modifier.height(16.dp))
                    Row {
                        TrafficValue("Download", formatModernBytes(modernRx), C.AccentLight, Modifier.weight(1f))
                        TrafficValue("Upload", formatModernBytes(modernTx), C.PrimaryLight, Modifier.weight(1f))
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Text(
                "Os valores representam somente a sessão real atual. Hysteria2/TUIC usam os contadores do TUN/HEV enquanto o núcleo sing-box está conectado.",
                color = C.TextDim,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun TrafficValue(label: String, value: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Column(modifier = modifier) {
        Text(label, color = C.TextDim, fontSize = 10.sp)
        Text(value, color = color, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun formatModernBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val group = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.lastIndex)
    return String.format("%.2f %s", bytes / Math.pow(1024.0, group.toDouble()), units[group])
}
