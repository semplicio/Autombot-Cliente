package com.autombot.client.ui.manual

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Settings
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
import com.autombot.client.ui.components.AutomBotTopBar

/** Tela 16 do mockup: introdução ao modo manual/profissional. */
@Composable
fun NoDomainScreen(onBack: () -> Unit, onConfigureManually: () -> Unit) {
    AutomBotBackground {
    Column(modifier = Modifier.fillMaxSize()) {
        AutomBotTopBar("Não tenho um domínio", onBack, "Modo manual")
    Column(
        modifier = Modifier.weight(1f).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(C.Accent.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Settings, contentDescription = null, tint = C.Accent, modifier = Modifier.size(32.dp))
        }

        Spacer(Modifier.height(20.dp))
        Text("Não tenho um domínio", color = C.Text, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            "Configure manualmente cada conexão, com acesso a todos os protocolos e controle total, sem limitações.",
            color = C.TextDim,
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(20.dp))
        listOf("Configuração completa", "Todos os protocolos", "Controle total", "Sem limitações").forEach {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Icon(Icons.Default.Check, contentDescription = null, tint = C.Accent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(it, color = C.Text, fontSize = 13.sp)
            }
        }

        Spacer(Modifier.height(24.dp))
        AutomBotGradientButton("Configurar manualmente", onConfigureManually, Modifier.fillMaxWidth(), accent = C.Accent)
    }
    }
    }
}
