package com.autombot.client.ui.more

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.autombot.client.ui.components.AutomBotCard
import com.autombot.client.ui.components.AutomBotInfoRow
import com.autombot.client.ui.components.AutomBotTopBar
import com.autombot.client.ui.theme.AutomBotColors as C
import com.autombot.client.util.AppLog

@Composable
fun SettingsScreen(onBack: () -> Unit, onLogout: () -> Unit) {
    var darkTheme by remember { mutableStateOf(true) }
    var notifications by remember { mutableStateOf(true) }
    var startWithSystem by remember { mutableStateOf(false) }
    var cacheCleared by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        AutomBotTopBar("Configurações", onBack, "Preferências")
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            AutomBotCard {
                AutomBotInfoRow(Icons.Default.DarkMode, "Tema", if (darkTheme) "Escuro" else "Claro", C.PrimaryLight, trailing = {
                    SettingSwitch(darkTheme) { darkTheme = it }
                })
                AutomBotInfoRow(Icons.Default.Notifications, "Notificações", if (notifications) "Ativadas" else "Desativadas", C.AccentLight, trailing = {
                    SettingSwitch(notifications) { notifications = it }
                })
                AutomBotInfoRow(Icons.Default.PowerSettingsNew, "Iniciar com o sistema", if (startWithSystem) "Ativado" else "Desativado", C.Green, trailing = {
                    SettingSwitch(startWithSystem) { startWithSystem = it }
                })
            }

            Spacer(Modifier.size(12.dp))
            AutomBotCard {
                AutomBotInfoRow(Icons.Default.Language, "Idioma", "Português", C.AccentLight, onClick = {})
                AutomBotInfoRow(
                    Icons.Default.CleaningServices,
                    "Limpar cache",
                    if (cacheCleared) "Cache limpo" else "Limpa os registros locais",
                    C.Warning,
                    onClick = {
                        AppLog.clear()
                        AppLog.log("Log limpo pelo usuário", AppLog.Level.INFO)
                        cacheCleared = true
                    }
                )
                AutomBotInfoRow(Icons.Default.Info, "Sobre o app", "AutomBot Connect • 0.1.0", C.TextDim, onClick = {})
            }

            Spacer(Modifier.size(12.dp))
            AutomBotCard(accent = C.Red) {
                AutomBotInfoRow(Icons.Default.Logout, "Sair", "Encerrar a sessão atual", C.Red, onClick = onLogout)
            }
        }
    }
}

@Composable
private fun SettingSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = C.Text,
            checkedTrackColor = C.Primary,
            uncheckedThumbColor = C.TextDim,
            uncheckedTrackColor = C.SurfaceAlt
        )
    )
}
