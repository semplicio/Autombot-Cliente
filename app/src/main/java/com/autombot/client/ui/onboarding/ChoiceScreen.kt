package com.autombot.client.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autombot.client.ui.components.AutomBotMark
import com.autombot.client.ui.theme.AutomBotColors as C

/** Tela 02 do mockup: escolha entre fluxo por domínio (gerenciado) ou manual (profissional). */
@Composable
fun ChoiceScreen(
    onHasDomain: () -> Unit,
    onNoDomain: () -> Unit,
    onViewTutorial: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(C.Background)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        AutomBotMark(size = 64.dp)
        Spacer(Modifier.height(20.dp))
        Text(
            "Bem-vindo ao\nAutomBot Connect",
            color = C.Text,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Escolha como deseja configurar o aplicativo",
            color = C.TextDim,
            fontSize = 13.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )

        Spacer(Modifier.height(28.dp))

        ChoiceCard(
            icon = Icons.Default.Language,
            iconTint = C.Primary,
            title = "Já tenho um domínio",
            subtitle = "Conectar e importar configurações do provedor",
            onClick = onHasDomain
        )
        Spacer(Modifier.height(12.dp))
        ChoiceCard(
            icon = Icons.Default.Settings,
            iconTint = C.Accent,
            title = "Não tenho um domínio",
            subtitle = "Configurar manualmente todas as conexões",
            onClick = onNoDomain
        )

        Spacer(Modifier.height(20.dp))
        Text(
            "Precisa de ajuda? Ver tutorial",
            color = C.Primary,
            fontSize = 12.sp,
            modifier = Modifier.clickable(onClick = onViewTutorial)
        )
    }
}

@Composable
private fun ChoiceCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: androidx.compose.ui.graphics.Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(C.Surface)
            .border(1.dp, C.Line, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = iconTint)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, color = C.TextDim, fontSize = 11.sp)
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = C.TextDim)
    }
}
