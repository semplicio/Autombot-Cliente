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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
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
import com.autombot.client.ui.components.AutomBotGradientButton

/** Um passo real (não só um rótulo) — a etapa só é marcada como concluída depois que [action] retorna. */
data class ProgressStep(
    val label: String,
    val action: suspend () -> Unit
)

/**
 * Telas 04/05 do mockup ("Conectando ao Servidor" e "Criando Sua Conta"): mesmo layout,
 * anel girando + checklist de etapas.
 *
 * CORRECAO: antes disso aqui era 100% simulado (delay(650) fixo por etapa, sempre
 * "dava certo"). Agora cada etapa roda de verdade (chamada ao PanelWebhookClient) e,
 * se qualquer uma falhar, mostra a mensagem de erro com um botão "Tentar de novo" em
 * vez de fingir sucesso e seguir em frente.
 */
@Composable
fun ProgressStepsScreen(
    title: String,
    subtitle: String,
    steps: List<ProgressStep>,
    onComplete: () -> Unit,
    onCancel: () -> Unit = {}
) {
    var completedCount by remember { mutableStateOf(0) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) } // muda pra forçar o LaunchedEffect a rodar de novo no "Tentar de novo"

    LaunchedEffect(attempt) {
        completedCount = 0
        errorMessage = null
        for (i in steps.indices) {
            try {
                steps[i].action()
                completedCount = i + 1
            } catch (e: Exception) {
                errorMessage = e.message ?: "Erro inesperado (${e.javaClass.simpleName})"
                return@LaunchedEffect
            }
        }
        onComplete()
    }

    val transition = rememberInfiniteTransition(label = "spin")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), repeatMode = RepeatMode.Restart),
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
                    .background((if (errorMessage != null) C.Red else C.Primary).copy(alpha = 0.1f))
            )
            if (errorMessage == null) {
                CircularProgressIndicator(
                    color = C.PrimaryLight,
                    trackColor = C.Line,
                    strokeWidth = 3.dp,
                    modifier = Modifier
                        .size(82.dp)
                        .rotate(angle)
                )
                Box(modifier = Modifier.size(16.dp).clip(CircleShape).background(C.Primary))
            } else {
                Icon(Icons.Default.Close, contentDescription = null, tint = C.Red, modifier = Modifier.size(36.dp))
            }
        }

        Spacer(Modifier.height(24.dp))
        Text(if (errorMessage != null) "Não deu certo" else title, color = C.Text, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(errorMessage ?: subtitle, color = if (errorMessage != null) C.Red else C.TextDim, fontSize = 12.sp)

        Spacer(Modifier.height(28.dp))
        Column(
            horizontalAlignment = Alignment.Start,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 34.dp)
        ) {
            steps.forEachIndexed { index, step ->
                val failed = errorMessage != null && index == completedCount
                StepRow(label = step.label, done = index < completedCount, active = index == completedCount && errorMessage == null, failed = failed)
                if (index != steps.lastIndex) Spacer(Modifier.height(10.dp))
            }
        }

        if (errorMessage != null) {
            Spacer(Modifier.height(24.dp))
            AutomBotGradientButton(
                text = "Tentar de novo",
                onClick = { attempt++ },
                modifier = Modifier.fillMaxWidth(0.7f)
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onCancel,
                colors = ButtonDefaults.buttonColors(containerColor = C.SurfaceAlt),
                shape = RoundedCornerShape(13.dp),
                modifier = Modifier.fillMaxWidth(0.7f)
            ) {
                Text("Voltar", color = C.TextDim)
            }
        }
    }
    }
}

@Composable
private fun StepRow(label: String, done: Boolean, active: Boolean, failed: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier.size(18.dp).clip(CircleShape)
                .background(
                    when {
                        failed -> C.Red.copy(alpha = 0.2f)
                        done -> C.Primary.copy(alpha = 0.2f)
                        active -> C.Accent.copy(alpha = 0.2f)
                        else -> C.SurfaceAlt
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            when {
                failed -> Icon(Icons.Default.Close, contentDescription = null, tint = C.Red, modifier = Modifier.size(12.dp))
                done -> Icon(Icons.Default.Check, contentDescription = null, tint = C.PrimaryLight, modifier = Modifier.size(12.dp))
                else -> Box(Modifier.size(6.dp).clip(CircleShape).background(if (active) C.Accent else C.TextMuted))
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = if (done || active || failed) "$label…" else label,
            color = when {
                failed -> C.Red
                done || active -> C.Text
                else -> C.TextMuted
            },
            fontSize = 13.sp
        )
    }
}
