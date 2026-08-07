package com.autombot.client.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autombot.client.ui.theme.AutomBotColors as C
import com.autombot.client.ui.components.AutomBotBackground
import com.autombot.client.ui.components.AutomBotGradientButton
import com.autombot.client.ui.components.AutomBotTopBar
import androidx.compose.material.icons.filled.Language

/** Tela 03 do mockup: input do domínio do provedor (fluxo "já tenho um domínio"). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DomainInputScreen(
    onBack: () -> Unit,
    onConnect: (domain: String) -> Unit
) {
    var domain by remember { mutableStateOf("") }

    AutomBotBackground {
        Column(modifier = Modifier.fillMaxSize()) {
            AutomBotTopBar(title = "Já tenho um domínio", onBack = onBack, eyebrow = "Configuração gerenciada")
            Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp)) {
            Box(
                modifier = Modifier.size(56.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(17.dp)).background(C.Primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Language, contentDescription = null, tint = C.PrimaryLight, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.height(20.dp))
            Text("Já tenho um domínio", color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "Digite o domínio do seu painel de provedor",
                color = C.TextDim,
                fontSize = 13.sp
            )
            Spacer(Modifier.height(24.dp))

            OutlinedTextField(
                value = domain,
                onValueChange = { domain = it },
                placeholder = { Text("https://seudominio.com") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(13.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = C.Text,
                    unfocusedTextColor = C.Text,
                    focusedBorderColor = C.Primary,
                    unfocusedBorderColor = C.Line,
                    cursorColor = C.Primary,
                    focusedPlaceholderColor = C.TextDim,
                    unfocusedPlaceholderColor = C.TextDim
                )
            )
            Spacer(Modifier.height(6.dp))
            Text("Exemplo: painel.seudominio.com", color = C.TextDim, fontSize = 11.sp)

            Spacer(Modifier.height(20.dp))
            AutomBotGradientButton(
                text = "Conectar",
                onClick = { onConnect(domain.trim()) },
                enabled = domain.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(14.dp))
            Text(
                "Como obter meu domínio?",
                color = C.Primary,
                fontSize = 12.sp,
                modifier = Modifier.clickable { /* TODO: link de ajuda */ }
            )
            }
        }
    }
}
