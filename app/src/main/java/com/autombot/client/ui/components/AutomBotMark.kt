package com.autombot.client.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.autombot.client.ui.theme.AutomBotColors as C

/**
 * Marca do AutomBot Connect: circulo com gradiente violeta -> azul e um raio ao centro.
 * Usado no splash e na tela de escolha inicial (ver mockup, telas 01-02).
 */
@Composable
fun AutomBotMark(size: Dp = 72.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    colors = listOf(C.Primary, C.Accent),
                    start = Offset(0f, 0f),
                    end = Offset(1f, 1f)
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Bolt,
            contentDescription = null,
            tint = C.OnPrimary,
            modifier = Modifier.size(size * 0.5f)
        )
    }
}
