package com.autombot.client.ui.more

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContactSupport
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.autombot.client.ui.components.AutomBotCard
import com.autombot.client.ui.components.AutomBotInfoRow
import com.autombot.client.ui.components.AutomBotTopBar
import com.autombot.client.ui.theme.AutomBotColors as C

@Composable
fun SupportScreen(onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        AutomBotTopBar("Suporte", onBack, "Central de ajuda")
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            AutomBotCard {
                AutomBotInfoRow(Icons.Default.QuestionAnswer, "FAQ", "Perguntas frequentes", C.PrimaryLight, onClick = {})
                AutomBotInfoRow(Icons.Default.MenuBook, "Tutoriais", "Aprenda a usar o aplicativo", C.AccentLight, onClick = {})
                AutomBotInfoRow(Icons.Default.ContactSupport, "Contato", "Fale com nossa equipe", C.Green, onClick = {})
                AutomBotInfoRow(Icons.Default.BugReport, "Reportar problema", "Envie os detalhes e registros", C.Red, onClick = {})
            }
            Spacer(Modifier.size(12.dp))
            AutomBotCard {
                AutomBotInfoRow(Icons.Default.Info, "Sobre", "AutomBot Connect • versão 0.1.0", C.TextDim, onClick = {})
            }
        }
    }
}
