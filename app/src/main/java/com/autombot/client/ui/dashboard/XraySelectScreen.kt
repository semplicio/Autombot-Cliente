package com.autombot.client.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
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

data class XrayOption(val id: String, val displayName: String)

val XrayOptions = listOf(
    XrayOption("vless", "VLESS"),
    XrayOption("vmess", "VMess"),
    XrayOption("shadowsocks", "Shadowsocks"),
    XrayOption("trojan", "Trojan")
)

@Composable
fun XraySelectScreen(onBack: () -> Unit, onSelect: (XrayOption) -> Unit) {
    Column(modifier = Modifier.fillMaxSize().background(C.Background)) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Voltar", tint = C.Text)
            }
            Text("Protocolos Xray", color = C.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }

        LazyColumn(
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(XrayOptions, key = { it.id }) { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(C.Surface)
                        .border(1.dp, C.Line, RoundedCornerShape(14.dp))
                        .clickable { onSelect(option) }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(option.displayName, color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = C.TextDim)
                }
            }
        }
    }
}
