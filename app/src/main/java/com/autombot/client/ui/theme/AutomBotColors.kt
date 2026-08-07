package com.autombot.client.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Paleta AutomBot Connect — identidade visual roxa/violeta do rebrand,
 * baseada no mockup enviado pelo usuário (logo do robô + gradiente violeta).
 */
object AutomBotColors {
    // Base azul-preta do mockup. Os tons separados permitem criar profundidade
    // sem clarear demais a interface em telas AMOLED.
    val BackgroundTop = Color(0xFF0B1724)
    val Background = Color(0xFF07111B)
    val BackgroundBottom = Color(0xFF050B12)
    val Surface = Color(0xFF0E1B28)
    val SurfaceAlt = Color(0xFF132434)
    val SurfaceRaised = Color(0xFF172A3C)
    val Line = Color(0xFF20384B)
    val LineBright = Color(0xFF31526B)

    val Text = Color(0xFFF7FAFF)
    val TextDim = Color(0xFF91A2B5)
    val TextMuted = Color(0xFF64778A)

    // Violeta identifica o fluxo gerenciado; azul identifica o modo manual.
    val Primary = Color(0xFFA442FF)
    val PrimaryLight = Color(0xFFC06CFF)
    val PrimaryDim = Color(0xFF6E22C9)
    val Accent = Color(0xFF258CFF)
    val AccentLight = Color(0xFF55B7FF)
    val AccentDim = Color(0xFF1260B8)

    val Green = Color(0xFF32DF85)
    val Red = Color(0xFFFF5576)
    val Warning = Color(0xFFFFB547)

    // Texto sobre botão preenchido com Primary
    val OnPrimary = Color(0xFFFFFFFF)
}
