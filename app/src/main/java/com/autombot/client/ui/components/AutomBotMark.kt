package com.autombot.client.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autombot.client.ui.theme.AutomBotColors as C

/**
 * Marca AutomBot desenhada nativamente em Compose. O robô substitui a marca
 * provisória de raio e continua nítido em qualquer densidade de tela.
 */
@Composable
fun AutomBotMark(size: Dp = 72.dp) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(C.Primary.copy(alpha = 0.42f), Color.Transparent),
                center = Offset(w * 0.5f, h * 0.48f),
                radius = w * 0.58f
            ),
            radius = w * 0.5f,
            center = Offset(w * 0.5f, h * 0.5f)
        )

        drawLine(
            color = C.PrimaryLight,
            start = Offset(w * 0.5f, h * 0.18f),
            end = Offset(w * 0.5f, h * 0.08f),
            strokeWidth = w * 0.045f,
            cap = StrokeCap.Round
        )
        drawCircle(C.PrimaryLight, radius = w * 0.045f, center = Offset(w * 0.5f, h * 0.065f))

        drawRoundRect(
            brush = Brush.linearGradient(
                listOf(C.PrimaryLight, C.Primary, C.PrimaryDim),
                start = Offset(w * 0.15f, h * 0.15f),
                end = Offset(w * 0.88f, h * 0.88f)
            ),
            topLeft = Offset(w * 0.1f, h * 0.18f),
            size = Size(w * 0.8f, h * 0.68f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.24f, w * 0.24f)
        )
        drawRoundRect(
            color = C.BackgroundBottom.copy(alpha = 0.96f),
            topLeft = Offset(w * 0.2f, h * 0.32f),
            size = Size(w * 0.6f, h * 0.37f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.15f, w * 0.15f)
        )

        drawRoundRect(
            color = C.Text,
            topLeft = Offset(w * 0.29f, h * 0.43f),
            size = Size(w * 0.12f, h * 0.075f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.04f, w * 0.04f)
        )
        drawRoundRect(
            color = C.Text,
            topLeft = Offset(w * 0.59f, h * 0.43f),
            size = Size(w * 0.12f, h * 0.075f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.04f, w * 0.04f)
        )

        val smile = Path().apply {
            moveTo(w * 0.39f, h * 0.58f)
            quadraticBezierTo(w * 0.5f, h * 0.66f, w * 0.61f, h * 0.58f)
        }
        drawPath(
            path = smile,
            color = C.PrimaryLight,
            style = Stroke(width = w * 0.035f, cap = StrokeCap.Round)
        )
    }
}

@Composable
fun AutomBotWordmark(compact: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "AUTOM",
            color = C.Text,
            fontSize = if (compact) 15.sp else 24.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = if (compact) 0.2.sp else 0.6.sp
        )
        Text(
            text = "BOT",
            color = C.PrimaryLight,
            fontSize = if (compact) 15.sp else 24.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = if (compact) 0.2.sp else 0.6.sp
        )
        if (!compact) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = "CONNECT",
                color = C.TextDim,
                fontSize = 8.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.6.sp
            )
        }
    }
}
