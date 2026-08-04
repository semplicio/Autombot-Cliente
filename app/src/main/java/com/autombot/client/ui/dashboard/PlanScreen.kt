package com.autombot.client.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autombot.client.ui.theme.AutomBotColors as C

/**
 * Tela 10 do mockup: "Meu Plano". As telas de planos pagos/PIX (mockup 11-14) ficam
 * para depois (decisao do usuario) — por ora so mostra o estado do teste gratis.
 */
@Composable
fun PlanScreen(trialCountdown: String?, onSeePlans: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(C.Background)
            .padding(20.dp)
    ) {
        Text("Meu Plano", color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(C.Surface)
                .border(1.dp, C.Line, RoundedCornerShape(18.dp))
                .padding(18.dp)
        ) {
            Text("Teste Grátis", color = C.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            if (trialCountdown != null) {
                Spacer(Modifier.height(4.dp))
                Text("Expira em $trialCountdown", color = C.Primary, fontSize = 13.sp)
            }
            Spacer(Modifier.height(16.dp))
            Text("Recursos incluídos", color = C.TextDim, fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))
            listOf("Acesso completo", "Todas as conexões", "Suporte básico", "1 dispositivo").forEach {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = C.Green, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(it, color = C.Text, fontSize = 13.sp)
                }
                Spacer(Modifier.height(6.dp))
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onSeePlans,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = C.Primary, contentColor = C.OnPrimary)
            ) { Text("Ver Planos") }
        }
    }
}
