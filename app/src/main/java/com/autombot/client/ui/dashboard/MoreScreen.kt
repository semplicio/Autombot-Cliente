package com.autombot.client.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autombot.client.ui.theme.AutomBotColors as C

/** Aba "Mais": agora navega de verdade para as telas 20-24 do mockup. */
@Composable
fun MoreScreen(
    onOpenSettings: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenStatistics: () -> Unit,
    onOpenDevices: () -> Unit,
    onOpenSupport: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(C.Background)
            .padding(20.dp)
    ) {
        Text("Mais", color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))

        MoreRow("Configurações", onOpenSettings)
        MoreRow("Logcat", onOpenLogs)
        MoreRow("Estatísticas", onOpenStatistics)
        MoreRow("Meus Dispositivos", onOpenDevices)
        MoreRow("Suporte", onOpenSupport)
    }
}

@Composable
private fun MoreRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(C.Surface)
            .border(1.dp, C.Line, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = C.TextDim)
    }
}