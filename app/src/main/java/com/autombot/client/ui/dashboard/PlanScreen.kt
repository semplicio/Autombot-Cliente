package com.autombot.client.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autombot.client.ui.components.AutomBotGradientButton
import com.autombot.client.ui.components.AutomBotTopBar
import com.autombot.client.ui.theme.AutomBotColors as C

@Composable
fun PlanScreen(trialCountdown: String?, onBack: () -> Unit, onSeePlans: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        AutomBotTopBar("Meu plano", onBack, "Assinatura")
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(16.dp))

        Column(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                .background(Brush.linearGradient(listOf(C.PrimaryDim, C.Primary, C.SurfaceRaised))).padding(18.dp)
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Teste Grátis", color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("2 horas de acesso completo", color = C.Text.copy(alpha = 0.72f), fontSize = 11.sp)
                }
                if (trialCountdown != null) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text("EXPIRA EM", color = C.Text.copy(alpha = 0.65f), fontSize = 8.sp)
                        Text(trialCountdown, color = C.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Text("RECURSOS INCLUÍDOS", color = C.Text.copy(alpha = 0.68f), fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            listOf("Acesso completo", "Todas as conexões", "Suporte básico", "1 dispositivo").forEach {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = C.Green, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(it, color = C.Text, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(14.dp))
            AutomBotGradientButton("Ver planos", onSeePlans, Modifier.fillMaxWidth())
        }

        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(10.dp)) {
            Icon(Icons.Default.History, contentDescription = null, tint = C.TextDim, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(9.dp))
            Text("Histórico de pagamentos", color = C.TextDim, fontSize = 12.sp)
        }
        }
    }
}
