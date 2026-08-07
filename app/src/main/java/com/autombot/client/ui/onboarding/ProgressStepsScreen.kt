package com.autombot.client.ui.onboarding

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autombot.client.ui.theme.AutomBotColors as C
import com.autombot.client.ui.components.AutomBotBackground
import kotlinx.coroutines.delay

/**
 * Telas 04/05 do mockup ("Conectando ao Servidor" e "Criando Sua Conta"): mesmo layout,
 * anel girando + checklist de etapas que vão marcando "concluído" com um pequeno delay entre elas.
 *
 * TODO: substituir a simulação de delay pelas chamadas reais ao PanelWebhookClient
 * quando a integração com o AutomBot Core estiver pronta (ver SPEC.md secao 3/7).
 */
@Composable
fun ProgressStepsScreen(
    title: String,
    subtitle: String,
    steps: List<String>,
    onComplete: () -> Unit
) {
    var completedCount by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        for (i in steps.indices) {
            delay(650)
            completedCount = i + 1
        }
        delay(300)
        onComplete()
    }

    val transition = rememberInfiniteTransition(label = "spin")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
        label = "angle"
    )

    AutomBotBackground {
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(90.dp)) {
            Box(
                modifier = Modifier
                    .size(74.dp)
                    .clip(CircleShape)
                    .background(C.Primary.copy(alpha = 0.1f))
            )
            CircularProgressIndicator(
                color = C.PrimaryLight,
                trackColor = C.Line,
                strokeWidth = 3.dp,
                modifier = Modifier
                    .size(82.dp)
                    .rotate(angle)
            )
            Box(modifier = Modifier.size(16.dp).clip(CircleShape).background(C.Primary))
        }

        Spacer(Modifier.height(24.dp))
        Text(title, color = C.Text, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(subtitle, color = C.TextDim, fontSize = 12.sp)

        Spacer(Modifier.height(28.dp))
        Column(
            horizontalAlignment = Alignment.Start,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 34.dp)
        ) {
            steps.forEachIndexed { index, step ->
                StepRow(label = step, done = index < completedCount, active = index == completedCount)
                if (index != steps.lastIndex) Spacer(Modifier.height(10.dp))
            }
        }
    }
    }
}

@Composable
private fun StepRow(label: String, done: Boolean, active: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier.size(18.dp).clip(CircleShape)
                .background(if (done) C.Primary.copy(alpha = 0.2f) else if (active) C.Accent.copy(alpha = 0.2f) else C.SurfaceAlt),
            contentAlignment = Alignment.Center
        ) {
            if (done) Icon(Icons.Default.Check, contentDescription = null, tint = C.PrimaryLight, modifier = Modifier.size(12.dp))
            else Box(Modifier.size(6.dp).clip(CircleShape).background(if (active) C.Accent else C.TextMuted))
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = if (done) "$label…" else if (active) "$label…" else label,
            color = if (done || active) C.Text else C.TextMuted,
            fontSize = 13.sp
        )
    }
}
