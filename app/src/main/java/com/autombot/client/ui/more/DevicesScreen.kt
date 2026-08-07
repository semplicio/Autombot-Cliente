package com.autombot.client.ui.more

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autombot.client.ui.components.AutomBotCard
import com.autombot.client.ui.components.AutomBotStatusDot
import com.autombot.client.ui.components.AutomBotTopBar
import com.autombot.client.ui.theme.AutomBotColors as C

@Composable
fun DevicesScreen(onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        AutomBotTopBar("Meus dispositivos", onBack, "Acessos autorizados")
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            AutomBotCard(modifier = Modifier.fillMaxWidth(), accent = C.Green) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = C.Green, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.width(13.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("${Build.MANUFACTURER} ${Build.MODEL}", color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text("Android ${Build.VERSION.RELEASE}", color = C.TextDim, fontSize = 10.sp)
                        AutomBotStatusDot(C.Green, "Este dispositivo • ativo agora")
                    }
                }
            }
            Spacer(Modifier.size(14.dp))
            Text(
                "Outros aparelhos aparecerão aqui quando a sincronização com o painel estiver disponível.",
                color = C.TextDim,
                fontSize = 11.sp
            )
        }
    }
}
