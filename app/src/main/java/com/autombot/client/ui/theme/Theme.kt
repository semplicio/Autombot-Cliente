package com.autombot.client.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val AutomBotColorScheme = darkColorScheme(
    primary = AutomBotColors.Primary,
    onPrimary = AutomBotColors.OnPrimary,
    secondary = AutomBotColors.Accent,
    onSecondary = AutomBotColors.OnPrimary,
    background = AutomBotColors.Background,
    onBackground = AutomBotColors.Text,
    surface = AutomBotColors.Surface,
    onSurface = AutomBotColors.Text,
    surfaceVariant = AutomBotColors.SurfaceAlt,
    onSurfaceVariant = AutomBotColors.TextDim,
    outline = AutomBotColors.Line,
    error = AutomBotColors.Red
)

@Composable
fun AutomBotClientTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AutomBotColorScheme,
        typography = MaterialTheme.typography,
        content = content
    )
}
