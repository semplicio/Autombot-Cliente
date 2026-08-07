package com.autombot.client.ui.manual

import androidx.compose.foundation.background
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
import com.autombot.client.ui.components.AutomBotGradientButton
import com.autombot.client.ui.components.AutomBotTopBar

data class ManualConnectionConfig(
    val protocolId: String,
    val protocolName: String,
    val connectionName: String,
    val server: String,
    val port: String,
    val username: String,
    val password: String
)

/**
 * Tela 18 do mockup: configuracao manual generica (servidor/porta/usuario/senha).
 * Serve para qualquer protocolo baseado nesses campos (SSH, V2Ray, Shadowsocks, VLESS,
 * Trojan, SOCKS5). WireGuard nao usa esta tela — ele tem fluxo proprio de import de
 * arquivo .conf no WireGuardScreen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualConfigScreen(
    protocol: ProtocolOption,
    onBack: () -> Unit,
    onSave: (ManualConnectionConfig) -> Unit
) {
    var name by remember { mutableStateOf("Minha Conexão ${protocol.displayName}") }
    var server by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showAdvanced by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(C.Background)) {
        AutomBotTopBar("Configurar ${protocol.displayName}", onBack, "Modo manual")

        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .weight(1f)
        ) {
            LabeledField("Nome da Conexão", name) { name = it }
            Spacer(Modifier.height(10.dp))
            LabeledField("Servidor", server, placeholder = "ssh.exemplo.com") { server = it }
            Spacer(Modifier.height(10.dp))
            LabeledField("Porta", port, placeholder = "22") { port = it }
            Spacer(Modifier.height(10.dp))
            LabeledField("Usuário", username, placeholder = "seu_usuario") { username = it }
            Spacer(Modifier.height(10.dp))
            LabeledField("Senha", password, isPassword = true) { password = it }

            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = showAdvanced,
                    onCheckedChange = { showAdvanced = it },
                    colors = SwitchDefaults.colors(checkedThumbColor = C.Primary, checkedTrackColor = C.Primary.copy(alpha = 0.35f))
                )
                Spacer(Modifier.width(8.dp))
                Text("Configurações Avançadas", color = C.TextDim, fontSize = 13.sp)
            }
            if (showAdvanced) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "SNI, payload customizado e DNS avançado entram aqui quando o driver de ${protocol.displayName} for implementado.",
                    color = C.TextDim,
                    fontSize = 11.sp
                )
            }
        }

        Column(modifier = Modifier.padding(20.dp)) {
            AutomBotGradientButton(
                text = "Salvar conexão",
                onClick = {
                    onSave(
                        ManualConnectionConfig(
                            protocolId = protocol.id,
                            protocolName = protocol.displayName,
                            connectionName = name.ifBlank { protocol.displayName },
                            server = server,
                            port = port,
                            username = username,
                            password = password
                        )
                    )
                },
                enabled = server.isNotBlank() && port.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                accent = C.Accent
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LabeledField(
    label: String,
    value: String,
    placeholder: String = "",
    isPassword: Boolean = false,
    onChange: (String) -> Unit
) {
    Column {
        Text(label, color = C.TextDim, fontSize = 11.sp)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            placeholder = { if (placeholder.isNotEmpty()) Text(placeholder) },
            singleLine = true,
            visualTransformation = if (isPassword)
                androidx.compose.ui.text.input.PasswordVisualTransformation()
            else androidx.compose.ui.text.input.VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(13.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = C.Text,
                unfocusedTextColor = C.Text,
                focusedBorderColor = C.Accent,
                unfocusedBorderColor = C.Line,
                cursorColor = C.Accent,
                focusedPlaceholderColor = C.TextDim,
                unfocusedPlaceholderColor = C.TextDim
            )
        )
    }
}
