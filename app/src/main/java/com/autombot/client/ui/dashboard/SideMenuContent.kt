package com.autombot.client.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autombot.client.ui.components.AutomBotMark
import com.autombot.client.ui.components.AutomBotWordmark
import com.autombot.client.ui.theme.AutomBotColors as C

@Composable
fun SideMenuContent(
    trialCountdown: String?,
    showPlan: Boolean,
    onDashboard: () -> Unit,
    onConnections: () -> Unit,
    onPlan: () -> Unit,
    onDevices: () -> Unit,
    onSettings: () -> Unit,
    onSupport: () -> Unit,
    onLogcat: () -> Unit,
    onLogout: () -> Unit
) {
    ModalDrawerSheet(
        drawerContainerColor = C.Surface,
        modifier = Modifier.width(310.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize().background(C.Surface).padding(vertical = 20.dp)) {
            Row(modifier = Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                AutomBotMark(size = 48.dp)
                Spacer(Modifier.width(11.dp))
                Column {
                    AutomBotWordmark(compact = true)
                    Text(
                        if (trialCountdown != null) "Teste grátis • $trialCountdown" else "Modo manual",
                        color = if (trialCountdown != null) C.PrimaryLight else C.AccentLight,
                        fontSize = 10.sp
                    )
                }
            }
            Spacer(Modifier.size(16.dp))
            HorizontalDivider(color = C.Line)
            Spacer(Modifier.size(8.dp))

            DrawerItem(Icons.Default.Home, "Dashboard", onDashboard, C.PrimaryLight)
            DrawerItem(Icons.Default.Link, "Conexões", onConnections, C.AccentLight)
            if (showPlan) DrawerItem(Icons.Default.Payments, "Planos", onPlan, C.PrimaryLight)
            DrawerItem(Icons.Default.Devices, "Dispositivos", onDevices, C.Warning)
            DrawerItem(Icons.Default.Settings, "Configurações", onSettings, C.TextDim)
            DrawerItem(Icons.Default.Description, "Logs", onLogcat, C.TextDim)
            DrawerItem(Icons.Default.SupportAgent, "Suporte", onSupport, C.Green)

            Spacer(Modifier.weight(1f))
            HorizontalDivider(color = C.Line)
            DrawerItem(Icons.Default.Logout, "Sair", onLogout, C.Red)
        }
    }
}

@Composable
private fun DrawerItem(icon: ImageVector, label: String, onClick: () -> Unit, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(17.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(label, color = if (color == C.Red) C.Red else C.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}
