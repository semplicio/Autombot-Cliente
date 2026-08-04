package com.autombot.client.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Paleta AutomBot Connect — identidade visual roxa/violeta do rebrand,
 * baseada no mockup enviado pelo usuário (logo do robô + gradiente violeta).
 */
object AutomBotColors {
    val Background = Color(0xFF120E1B)
    val Surface = Color(0xFF1C1730)
    val SurfaceAlt = Color(0xFF241D3D)
    val Line = Color(0xFF352C54)

    val Text = Color(0xFFEDEAF7)
    val TextDim = Color(0xFF9C94B8)

    // Acento primário: violeta vibrante (botões principais, elementos ativos)
    val Primary = Color(0xFF8B5CF6)
    val PrimaryDim = Color(0xFF6D28D9)

    // Acento secundário: azul (usado em cards do fluxo "não tenho domínio", dados/stats)
    val Accent = Color(0xFF4F8CFF)

    val Green = Color(0xFF22C55E)
    val Red = Color(0xFFEF4444)

    // Texto sobre botão preenchido com Primary
    val OnPrimary = Color(0xFFFFFFFF)
}
