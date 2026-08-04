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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autombot.client.ui.theme.AutomBotColors as C

/** Tela 03 do mockup: input do domínio do provedor (fluxo "já tenho um domínio"). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DomainInputScreen(
    onBack: () -> Unit,
    onConnect: (domain: String) -> Unit
) {
    var domain by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(C.Background)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Voltar", tint = C.Text)
            }
        }

        Column(modifier = Modifier.padding(horizontal = 24.dp)) {
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
            Button(
                onClick = { onConnect(domain.trim()) },
                enabled = domain.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = C.Primary, contentColor = C.OnPrimary)
            ) {
                Text("Conectar")
            }

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
