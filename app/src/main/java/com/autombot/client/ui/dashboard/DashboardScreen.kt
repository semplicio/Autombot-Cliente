package com.autombot.client.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autombot.client.ui.components.AutomBotCard
import com.autombot.client.ui.components.AutomBotGradientButton
import com.autombot.client.ui.theme.AutomBotColors as C
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

/** Dashboard visual do modo gerenciado, conectado aos mesmos estados reais. */
@Composable
fun DashboardScreen(
    trialCountdown: String?,
    activeConnections: Int,
    trafficLabel: String,
    onRenew: () -> Unit,
    onOpenConnections: () -> Unit
) {
    val pingResult = remember { mutableStateOf("Desconectado") }

    LaunchedEffect(activeConnections) {
        pingResult.value = if (activeConnections > 0) {
            withContext(Dispatchers.IO) {
                try {
                    val process = ProcessBuilder("ping", "-c", "1", "-W", "2", "8.8.8.8").start()
                    val reader = BufferedReader(InputStreamReader(process.inputStream))
                    var time = "Indisponível"
                    reader.forEachLine { line ->
                        if (line.contains("time=")) time = "${line.substringAfter("time=").substringBefore(" ms")} ms"
                    }
                    process.waitFor()
                    time
                } catch (_: Exception) {
                    "Indisponível"
                }
            }
        } else {
            "Desconectado"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Text("VISÃO GERAL", color = C.PrimaryLight, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
        Spacer(Modifier.height(4.dp))
        Text("Dashboard", color = C.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("Acompanhe seu plano e suas conexões", color = C.TextDim, fontSize = 12.sp)

        Spacer(Modifier.height(18.dp))

        if (trialCountdown != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(C.PrimaryDim, C.Primary.copy(alpha = 0.84f), C.SurfaceRaised)
                        )
                    )
                    .padding(18.dp)
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("PLANO ATUAL", color = C.Text.copy(alpha = 0.72f), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(5.dp))
                        Text("Teste Grátis", color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("Acesso completo por 2 horas", color = C.Text.copy(alpha = 0.78f), fontSize = 11.sp)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("EXPIRA EM", color = C.Text.copy(alpha = 0.65f), fontSize = 8.sp)
                        Text(trialCountdown, color = C.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(16.dp))
                AutomBotGradientButton(
                    text = "Renovar agora",
                    onClick = onRenew,
                    modifier = Modifier.fillMaxWidth(),
                    accent = C.PrimaryLight
                )
            }
            Spacer(Modifier.height(16.dp))
        }

        AutomBotCard(modifier = Modifier.fillMaxWidth(), onClick = onOpenConnections) {
            DashboardMetric(Icons.Default.NetworkCheck, "Conexões", "$activeConnections ativa(s)", C.Green)
            MetricDivider()
            DashboardMetric(Icons.Default.SwapVert, "Tráfego da sessão", trafficLabel, C.AccentLight)
            MetricDivider()
            DashboardMetric(Icons.Default.Speed, "Latência", pingResult.value, C.PrimaryLight)
            MetricDivider()
            DashboardMetric(Icons.Default.Devices, "Dispositivos", "1 ativo", C.Warning)
        }

        Spacer(Modifier.height(14.dp))
        AutomBotGradientButton(
            text = "Ver minhas conexões",
            onClick = onOpenConnections,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun DashboardMetric(icon: ImageVector, label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(color.copy(alpha = 0.13f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = C.TextDim, fontSize = 10.sp)
            Text(value, color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun MetricDivider() {
    Spacer(Modifier.height(7.dp))
    androidx.compose.material3.HorizontalDivider(color = C.Line.copy(alpha = 0.7f))
    Spacer(Modifier.height(7.dp))
}
