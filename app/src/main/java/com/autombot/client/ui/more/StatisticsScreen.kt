package com.autombot.client.ui.more

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autombot.client.ui.theme.AutomBotColors as C

/**
 * Tela 22 do mockup: Estatisticas. Mostra apenas o trafego real da sessao atual
 * (somado dos tuneis WireGuard ativos). NAO mostra grafico de historico "hoje/7
 * dias/30 dias" porque isso exigiria armazenamento persistente que ainda nao existe
 * — ver TODO abaixo.
 *
 * TODO: quando houver persistencia de trafego (SPEC.md — pendente), reativar o
 * grafico historico do mockup (tela 22 original).
 */
@Composable
fun StatisticsScreen(rxBytesLabel: String, txBytesLabel: String, totalLabel: String, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().background(C.Background)) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Voltar", tint = C.Text)
            }
            Text("Estatísticas", color = C.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }

        Column(modifier = Modifier.padding(20.dp)) {
            Text("Sessão atual", color = C.TextDim, fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(C.Surface)
                    .border(1.dp, C.Line, RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                Text("Tráfego total", color = C.TextDim, fontSize = 11.sp)
                Text(totalLabel, color = C.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Row {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("↓ Download", color = C.TextDim, fontSize = 11.sp)
                        Text(rxBytesLabel, color = C.Accent, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("↑ Upload", color = C.TextDim, fontSize = 11.sp)
                        Text(txBytesLabel, color = C.Primary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "Histórico por período (hoje / 7 dias / 30 dias) entra quando o app tiver armazenamento persistente de tráfego.",
                color = C.TextDim,
                fontSize = 11.sp
            )
        }
    }
}