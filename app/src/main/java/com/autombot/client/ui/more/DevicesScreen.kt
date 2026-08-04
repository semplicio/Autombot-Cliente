package com.autombot.client.ui.more

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PhoneAndroid
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
 * Tela 23 do mockup: Meus Dispositivos. So mostra ESTE aparelho — o mockup original
 * lista varios dispositivos (Android, Windows, iPhone), mas isso depende do
 * provisionamento multi-dispositivo do AutomBot Core, que ainda nao existe
 * (ver provisioning/DeviceProvisioning.kt, ainda sem persistencia real).
 *
 * TODO: quando o provisionamento por dominio estiver plugado (SPEC.md secao 4),
 * essa tela passa a listar os dispositivos vindos do painel.
 */
@Composable
fun DevicesScreen(onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().background(C.Background)) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Voltar", tint = C.Text)
            }
            Text("Meus Dispositivos", color = C.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }

        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(C.Surface)
                    .border(1.dp, C.Green.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = C.Green)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("${Build.MANUFACTURER} ${Build.MODEL}", color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text("Este dispositivo · ativo agora", color = C.Green, fontSize = 11.sp)
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "O gerenciamento de múltiplos dispositivos por conta (via painel) ainda não está disponível.",
                color = C.TextDim,
                fontSize = 11.sp
            )
        }
    }
}