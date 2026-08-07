package com.autombot.client.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autombot.client.ui.theme.AutomBotColors as C

@Composable
fun AutomBotBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(C.BackgroundTop, C.Background, C.BackgroundBottom)
                )
            ),
        content = content
    )
}

@Composable
fun AutomBotCard(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    padding: Dp = 16.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(18.dp)
    val clickableModifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Column(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    colors = listOf(C.SurfaceRaised.copy(alpha = 0.94f), C.Surface.copy(alpha = 0.98f))
                )
            )
            .border(1.dp, accent?.copy(alpha = 0.48f) ?: C.Line, shape)
            .then(clickableModifier)
            .padding(padding),
        content = content
    )
}

@Composable
fun AutomBotTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    eyebrow: String? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Voltar", tint = C.Text)
            }
        } else {
            Spacer(Modifier.width(10.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            if (eyebrow != null) {
                Text(
                    eyebrow.uppercase(),
                    color = C.PrimaryLight,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.1.sp
                )
            }
            Text(title, color = C.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        actions()
    }
}

@Composable
fun AutomBotGradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = C.Primary,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null
) {
    val shape = RoundedCornerShape(13.dp)
    val colors = if (accent == C.Accent) {
        listOf(C.Accent, C.AccentDim)
    } else {
        listOf(C.PrimaryLight, C.PrimaryDim)
    }
    Row(
        modifier = modifier
            .height(48.dp)
            .clip(shape)
            .background(
                if (enabled) Brush.horizontalGradient(colors)
                else Brush.horizontalGradient(listOf(C.SurfaceAlt, C.SurfaceAlt))
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (leadingIcon != null) {
            Icon(leadingIcon, contentDescription = null, tint = if (enabled) C.OnPrimary else C.TextMuted, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text,
            color = if (enabled) C.OnPrimary else C.TextMuted,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun AutomBotStatusDot(color: Color, label: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(7.dp))
        Text(label, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun AutomBotInfoRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    color: Color = C.Primary,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(color.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = C.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            if (subtitle != null) Text(subtitle, color = C.TextDim, fontSize = 10.sp)
        }
        if (trailing != null) trailing() else if (onClick != null) {
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = C.TextMuted, modifier = Modifier.size(18.dp))
        }
    }
}

fun protocolVisual(id: String): Pair<ImageVector, Color> = when (id.lowercase()) {
    "ssh" -> Icons.Default.Terminal to Color(0xFF8A7DFF)
    "wireguard", "vpn" -> Icons.Default.Security to Color(0xFF25C6DA)
    "vless" -> Icons.Default.VpnKey to Color(0xFFB14CFF)
    "vmess", "v2ray", "xray" -> Icons.Default.Hub to Color(0xFF5B7CFF)
    "shadowsocks" -> Icons.Default.Send to Color(0xFF55D38A)
    "trojan" -> Icons.Default.Lock to Color(0xFFFF637D)
    "openvpn" -> Icons.Default.Key to Color(0xFFFFB547)
    "socks5" -> Icons.Default.Public to Color(0xFFE355FF)
    "domain" -> Icons.Default.Language to C.Primary
    else -> Icons.Default.Cloud to C.Accent
}
