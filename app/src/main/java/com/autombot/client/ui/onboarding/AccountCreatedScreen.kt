package com.autombot.client.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autombot.client.ui.theme.AutomBotColors as C
import com.autombot.client.ui.components.AutomBotBackground
import com.autombot.client.ui.components.AutomBotGradientButton
import com.autombot.client.ui.components.AutomBotCard
import androidx.compose.foundation.border

/**
 * Tela 06 do mockup: conta criada com sucesso, exibindo o contador do teste gratis.
 *
 * O countdown e controlado pelo AppRoot (fonte unica), nao por esta tela, para que o
 * mesmo valor continue visivel depois, no Dashboard e em "Meu Plano".
 */
@Composable
fun AccountCreatedScreen(
    countdownLabel: String,
    onGoToDashboard: () -> Unit
) {
    AutomBotBackground {
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(92.dp)
                .clip(CircleShape)
                .background(C.Green.copy(alpha = 0.12f))
                .border(1.dp, C.Green.copy(alpha = 0.45f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Check, contentDescription = null, tint = C.Green, modifier = Modifier.size(40.dp))
        }

        Spacer(Modifier.height(20.dp))
        Text("Conta criada com sucesso!", color = C.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Seu teste de 2 horas foi ativado com sucesso.",
            color = C.TextDim,
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(20.dp))
        AutomBotCard(modifier = Modifier.fillMaxWidth(), accent = C.Green, padding = 14.dp) {
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Tempo restante", color = C.TextDim, fontSize = 10.sp)
                Text(countdownLabel, color = C.Green, fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "Aproveite todos os recursos das conexões durante o período de teste.",
            color = C.TextDim,
            fontSize = 12.sp,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(28.dp))
        AutomBotGradientButton("Ir para o Dashboard", onGoToDashboard, Modifier.fillMaxWidth())
    }
    }
}
