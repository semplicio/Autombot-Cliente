package com.autombot.client.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Divider
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autombot.client.ui.components.AutomBotMark
import com.autombot.client.ui.theme.AutomBotColors as C

/** Tela 25 do mockup: menu lateral. */
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
    ModalDrawerSheet(drawerContainerColor = C.Surface) {
        Column(modifier = Modifier.fillMaxSize().padding(vertical = 20.dp)) {
            Row(modifier = Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                AutomBotMark(size = 40.dp)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("AutomBot Connect", color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (trialCountdown != null) "Teste Grátis · $trialCountdown" else "Modo manual",
                        color = C.TextDim,
                        fontSize = 11.sp
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Divider(color = C.Line)
            Spacer(Modifier.height(8.dp))

            DrawerItem("Dashboard", onDashboard)
            DrawerItem("Conexões", onConnections)
            if (showPlan) DrawerItem("Planos", onPlan)
            DrawerItem("Dispositivos", onDevices)
            DrawerItem("Configurações", onSettings)
            DrawerItem("Logcat", onLogcat)
            DrawerItem("Suporte", onSupport)

            Spacer(Modifier.weight(1f))
            Divider(color = C.Line)
            DrawerItem("Sair", onLogout, danger = true)
        }
    }
}

@Composable
private fun DrawerItem(label: String, onClick: () -> Unit, danger: Boolean = false) {
    Text(
        label,
        color = if (danger) C.Red else C.Text,
        fontSize = 14.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp)
    )
}