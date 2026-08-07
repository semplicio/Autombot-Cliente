package com.autombot.client.ui.manual

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import com.autombot.client.ui.components.AutomBotCard
import com.autombot.client.ui.components.AutomBotTopBar
import com.autombot.client.ui.components.protocolVisual

data class ProtocolOption(val id: String, val displayName: String, val implemented: Boolean)

val ManualProtocolOptions = listOf(
    ProtocolOption("ssh", "SSH", implemented = true),
    ProtocolOption("vmess", "VMess / V2Ray", implemented = true),
    ProtocolOption("wireguard", "WireGuard / VPN", implemented = true),
    ProtocolOption("shadowsocks", "Shadowsocks", implemented = true),
    ProtocolOption("vless", "VLESS", implemented = true),
    ProtocolOption("trojan", "Trojan", implemented = true),
    ProtocolOption("openvpn", "OpenVPN", implemented = true),
    ProtocolOption("socks5", "SOCKS5", implemented = false)
)

/**
 * Tela 17 do mockup: selecao de protocolo. So o WireGuard tem driver de verdade
 * (ver pasta protocols/wireguard) — os demais abrem a mesma tela de config manual (18),
 * mas o "teste de conexao" (19) deixa claro que ainda nao ha suporte real.
 */
@Composable
fun ProtocolSelectScreen(onBack: () -> Unit, onSelect: (ProtocolOption) -> Unit) {
    Column(modifier = Modifier.fillMaxSize().background(C.Background)) {
        AutomBotTopBar("Selecionar protocolo", onBack, "Configuração manual")

        LazyColumn(
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(ManualProtocolOptions, key = { it.id }) { option ->
                val (protocolIcon, protocolColor) = protocolVisual(option.id)
                AutomBotCard(modifier = Modifier.fillMaxWidth(), padding = 12.dp, onClick = { onSelect(option) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(protocolColor.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) { Icon(protocolIcon, contentDescription = null, tint = protocolColor, modifier = Modifier.size(21.dp)) }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(option.displayName, color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(if (option.implemented) "Disponível" else "Configuração manual", color = if (option.implemented) C.Green else C.TextDim, fontSize = 10.sp)
                    }
                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = C.TextDim)
                }
                }
            }
        }
    }
}
