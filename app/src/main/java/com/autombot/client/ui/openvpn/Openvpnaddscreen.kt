package com.autombot.client.ui.openvpn

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
import com.autombot.client.protocols.openvpn.OpenVpnConfigException
import com.autombot.client.protocols.openvpn.OpenVpnConnectionConfig
import com.autombot.client.protocols.openvpn.saveOpenVpnConfig
import com.autombot.client.ui.theme.AutomBotColors as C

/**
 * Tela pra importar um perfil OpenVPN — cola o conteúdo do arquivo .ovpn (com
 * certificados embutidos) e dá um nome. Diferente dos outros protocolos, não tem
 * link curto pra colar — o .ovpn é o formato de configuração inteiro.
 *
 * [onPickFile] é opcional — se o app tiver um seletor de arquivo disponível (ver
 * MainActivity.kt, já usado pelo SSH pra importar certificados), essa tela pode usar
 * pra preencher o campo de texto automaticamente; sem isso, o usuário cola manual.
 */
@Composable
fun OpenVpnAddScreen(
    onBack: () -> Unit,
    onPickFile: ((onText: (fileName: String, text: String) -> Unit) -> Unit)? = null,
    onSave: (com.autombot.client.protocols.openvpn.OpenVpnConnectionConfig) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current

    Column(modifier = Modifier.fillMaxSize().background(C.Background)) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Voltar", tint = C.Text)
            }
            Text("Importar OpenVPN", color = C.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }

        Column(modifier = Modifier.padding(20.dp).weight(1f)) {
            Text("Nome da conexão", color = C.TextDim, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it; error = null },
                placeholder = { Text("Ex: Servidor Casa") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = C.Text,
                    unfocusedTextColor = C.Text,
                    focusedBorderColor = C.Accent,
                    unfocusedBorderColor = C.Line,
                    cursorColor = C.Accent
                )
            )
            Spacer(Modifier.height(16.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Conteúdo do arquivo .ovpn", color = C.TextDim, fontSize = 12.sp, modifier = Modifier.weight(1f))
                if (onPickFile != null) {
                    TextButton(onClick = {
                        onPickFile { fileName, text ->
                            content = text
                            if (name.isBlank()) name = fileName.substringBeforeLast(".")
                        }
                    }) {
                        Text("Escolher arquivo", color = C.Accent, fontSize = 12.sp)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = content,
                onValueChange = { content = it; error = null },
                placeholder = { Text("client\ndev tun\nproto udp\nremote servidor.exemplo.com 1194\n<ca>\n...\n</ca>\n...") },
                singleLine = false,
                modifier = Modifier.fillMaxWidth().weight(1f),
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
                        val config = saveOpenVpnConfig(
                            context = context,
                            connectionName = name.ifBlank { "Conexão OpenVPN" },
                            ovpnContent = content
                        )
                        onSave(config)
                    } catch (e: OpenVpnConfigException) {
                        error = e.message
                    } catch (e: Exception) {
                        error = "Arquivo inválido: ${e.message}"
                    }
                },
                enabled = content.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = C.Accent, contentColor = C.OnPrimary)
            ) {
                Text("Salvar Conexão")
            }
        }
    }
}