package com.autombot.client.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autombot.client.ui.theme.AutomBotColors as C

/**
 * Tela 07 do mockup: Dashboard (modo gerenciado). Mostra o plano atual e um resumo
 * rapido de conexoes/trafego/dispositivos.
 *
 * TODO: quando o modo gerenciado (SPEC.md secao 3) estiver plugado de verdade, os
 * valores de plano/trafego virao do AutomBot Core em vez de serem calculados so a
 * partir do WireGuardManager local.
 */
@Composable
fun DashboardScreen(
    trialCountdown: String?,
    activeConnections: Int,
    trafficLabel: String,
    onRenew: () -> Unit,
    onOpenConnections: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(C.Background)
            .padding(20.dp)
    ) {
        Text("AUTOMBOT CONNECT", color = C.TextDim, fontSize = 11.sp)
        Spacer(Modifier.height(4.dp))
        Text("Dashboard", color = C.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)

        Spacer(Modifier.height(20.dp))

        if (trialCountdown != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(C.Surface)
                    .border(1.dp, C.Line, RoundedCornerShape(18.dp))
                    .padding(18.dp)
            ) {
                Text("Plano Atual", color = C.TextDim, fontSize = 11.sp)
                Spacer(Modifier.height(4.dp))
                Text("Teste Grátis", color = C.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text("Expira em $trialCountdown", color = C.Primary, fontSize = 13.sp)
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = onRenew,
                    colors = ButtonDefaults.buttonColors(containerColor = C.Primary, contentColor = C.OnPrimary),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Renovar Agora") }
            }
            Spacer(Modifier.height(16.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DashStat(label = "Conexões", value = "$activeConnections ativa(s)")
            DashStat(label = "Tráfego", value = trafficLabel)
            DashStat(label = "Dispositivos", value = "1 ativo")
        }

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onOpenConnections,
            colors = ButtonDefaults.buttonColors(containerColor = C.Surface, contentColor = C.Text),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Ver minhas conexões") }
    }
}

@Composable
private fun RowScope.DashStat(label: String, value: String) {
    Column(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(14.dp))
            .background(C.Surface)
            .border(1.dp, C.Line, RoundedCornerShape(14.dp))
            .padding(12.dp)
    ) {
        Text(label, color = C.TextDim, fontSize = 10.sp)
        Spacer(Modifier.height(4.dp))
        Text(value, color = C.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}
