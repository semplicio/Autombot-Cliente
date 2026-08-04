package com.autombot.client.ui.more

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import com.autombot.client.ui.theme.AutomBotColors as C
import com.autombot.client.util.AppLog

/**
 * Tela 20 do mockup: Configuracoes.
 *
 * "Limpar Cache" e uma acao real (limpa o AppLog em memoria). Os demais toggles
 * (tema, notificacoes, iniciar com o sistema, idioma) ainda sao so estado local —
 * nao persistem entre sessoes nem tem efeito real ainda.
 *
 * TODO: persistir preferencias em DataStore/SharedPreferences e aplicar de verdade
 * (tema claro/escuro, notificacoes, etc.) quando essas features forem implementadas.
 */
@Composable
fun SettingsScreen(onBack: () -> Unit, onLogout: () -> Unit) {
    var darkTheme by remember { mutableStateOf(true) }
    var notifications by remember { mutableStateOf(true) }
    var startWithSystem by remember { mutableStateOf(false) }
    var cacheCleared by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(C.Background)) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Voltar", tint = C.Text)
            }
            Text("Configurações", color = C.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }

        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            SettingToggleRow("Tema escuro", darkTheme) { darkTheme = it }
            SettingToggleRow("Notificações", notifications) { notifications = it }
            SettingToggleRow("Iniciar com o sistema", startWithSystem) { startWithSystem = it }

            Spacer(Modifier.height(6.dp))
            SettingActionRow(
                title = "Limpar Cache",
                subtitle = if (cacheCleared) "Cache limpo" else "Logs salvos no aparelho",
                onClick = {
                    AppLog.clear()
                    AppLog.log("Log limpo pelo usuário", AppLog.Level.INFO)
                    cacheCleared = true
                }
            )
            SettingActionRow(title = "Idioma", subtitle = "Português", onClick = {})
            SettingActionRow(title = "Sobre o App", subtitle = "AutomBot Connect · 0.1.0-skeleton", onClick = {})

            Spacer(Modifier.height(16.dp))
            SettingActionRow(title = "Sair", subtitle = "Encerrar sessão atual", onClick = onLogout, danger = true)
        }
    }
}

@Composable
private fun SettingToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = C.Text, fontSize = 14.sp)
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedThumbColor = C.Primary, checkedTrackColor = C.Primary.copy(alpha = 0.35f))
        )
    }
}

@Composable
private fun SettingActionRow(title: String, subtitle: String, onClick: () -> Unit, danger: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(C.Surface)
            .border(1.dp, if (danger) C.Red.copy(alpha = 0.35f) else C.Line, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(14.dp)
    ) {
        Column {
            Text(title, color = if (danger) C.Red else C.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = C.TextDim, fontSize = 11.sp)
        }
    }
}