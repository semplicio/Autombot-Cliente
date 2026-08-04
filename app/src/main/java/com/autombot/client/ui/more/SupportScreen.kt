package com.autombot.client.ui.more

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autombot.client.ui.theme.AutomBotColors as C

/**
 * Tela 24 do mockup: Suporte.
 *
 * Itens de navegação (FAQ, Tutoriais, Contato, Reportar Problema, Sobre).
 * Sem backend de suporte ainda, cada item é só estrutural.
 */
@Composable
fun SupportScreen(onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().background(C.Background)) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Voltar", tint = C.Text)
            }
            Text("Suporte", color = C.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }

        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            SupportActionRow(title = "FAQ", subtitle = "Perguntas frequentes", onClick = {})
            SupportActionRow(title = "Tutoriais", subtitle = "Aprenda a usar o app", onClick = {})
            SupportActionRow(title = "Contato", subtitle = "Fale conosco", onClick = {})
            SupportActionRow(title = "Reportar Problema", subtitle = "Envie um relatório de erro", onClick = {})
            SupportActionRow(title = "Sobre", subtitle = "Informações do aplicativo", onClick = {})
        }
    }
}

@Composable
private fun SupportActionRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(C.Surface)
            .border(1.dp, C.Line, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(14.dp)
    ) {
        Column {
            Text(title, color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = C.TextDim, fontSize = 11.sp)
        }
    }
}
