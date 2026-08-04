package com.autombot.client.ui.vmess

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.autombot.client.protocols.vmess.VmessUriParseException
import com.autombot.client.protocols.vmess.parseVmessUri
import com.autombot.client.ui.theme.AutomBotColors as C

/**
 * Tela pra adicionar uma conexao VMess colando o link vmess:// que o painel entrega
 * pronto (ver modules/pacote.py do AutomBot Core) — nao precisa preencher campo por
 * campo como no SSH.
 */
@Composable
fun VmessAddScreen(onBack: () -> Unit, onSave: (com.autombot.client.protocols.vmess.VmessConnectionConfig) -> Unit) {
    var link by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize().background(C.Background)) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Voltar", tint = C.Text)
            }
            Text("Adicionar VMess", color = C.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }

        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                "Cole aqui o link que o painel te deu (começa com vmess://).",
                color = C.TextDim,
                fontSize = 12.sp
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = link,
                onValueChange = { link = it; error = null },
                placeholder = { Text("vmess://eyJ2IjoiMiIsInBzIjoi...") },
                singleLine = false,
                modifier = Modifier.fillMaxWidth().height(120.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = C.Text,
                    unfocusedTextColor = C.Text,
                    focusedBorderColor = C.Accent,
                    unfocusedBorderColor = C.Line,
                    cursorColor = C.Accent
                )
            )

            if (error != null) {
                Spacer(Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(C.Red.copy(alpha = 0.12f))
                        .padding(10.dp)
                ) {
                    Text(error!!, color = C.Red, fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    try {
                        val config = parseVmessUri(link)
                        onSave(config)
                    } catch (e: VmessUriParseException) {
                        error = e.message
                    } catch (e: Exception) {
                        error = "Link inválido: ${e.message}"
                    }
                },
                enabled = link.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = C.Accent, contentColor = C.OnPrimary)
            ) {
                Text("Salvar Conexão")
            }
        }
    }
}