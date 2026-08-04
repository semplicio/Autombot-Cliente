package com.autombot.client.ui.manual

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autombot.client.ui.theme.AutomBotColors as C
import kotlinx.coroutines.delay

/**
 * Tela 19 do mockup: teste de conexao. IMPORTANTE — como nenhum driver alem do
 * WireGuard existe ainda (ver ProtocolDriver/protocols/ *), esta tela NAO inventa
 * latencia/download/upload falsos para simular uma conexao que nao acontece de
 * verdade. Ela mostra o progresso do "teste" (visual) e termina deixando claro que
 * o protocolo ainda nao tem suporte real, em vez de fingir sucesso.
 *
 * TODO: quando o driver do protocolo existir, trocar a simulacao por uma chamada
 * real a ProtocolDriver.connect() e exibir metricas reais (latencia/throughput).
 */
@Composable
fun ConnectionTestScreen(
    config: ManualConnectionConfig,
    onCancel: () -> Unit,
    onFinished: () -> Unit
) {
    var percent by remember { mutableStateOf(0) }
    var testDone by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (percent < 100) {
            delay(35)
            percent += 2
        }
        testDone = true
    }

    Column(modifier = Modifier.fillMaxSize().background(C.Background)) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onCancel) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Voltar", tint = C.Text)
            }
            Text("Testando Conexão", color = C.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (!testDone) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(120.dp)) {
                    CircularProgressIndicator(
                        progress = percent / 100f,
                        color = C.Accent,
                        strokeWidth = 6.dp,
                        modifier = Modifier.fillMaxSize()
                    )
                    Text("$percent%", color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(20.dp))
                Text("Testando conexão com ${config.server}:${config.port}…", color = C.TextDim, fontSize = 13.sp, textAlign = TextAlign.Center)
            } else {
                Icon(Icons.Default.Info, contentDescription = null, tint = C.Accent, modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(16.dp))
                Text("Configuração salva", color = C.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "O protocolo ${config.protocolName} ainda não tem suporte de conexão real neste app — só o WireGuard conecta de verdade por enquanto. " +
                        "Sua configuração (\"${config.connectionName}\") ficou salva e vai funcionar assim que esse driver for implementado.",
                    color = C.TextDim,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = onFinished,
                    colors = ButtonDefaults.buttonColors(containerColor = C.Accent, contentColor = C.OnPrimary)
                ) { Text("Voltar para Conexões") }
            }
        }

        if (!testDone) {
            Column(modifier = Modifier.padding(20.dp)) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                    Text("Cancelar Teste")
                }
            }
        }
    }
}
