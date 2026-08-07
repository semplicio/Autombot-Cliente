package com.autombot.client.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autombot.client.ui.components.AutomBotMark
import com.autombot.client.ui.components.AutomBotBackground
import com.autombot.client.ui.components.AutomBotWordmark
import com.autombot.client.ui.theme.AutomBotColors as C
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.delay

/** Tela 01 do mockup: splash com marca + tagline, avança sozinha depois de um instante. */
@Composable
fun SplashScreen(onFinished: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(1400)
        onFinished()
    }

    AutomBotBackground {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            AutomBotMark(size = 104.dp)
            Spacer(Modifier.height(22.dp))
            AutomBotWordmark()
            Spacer(Modifier.height(6.dp))
            Text(
                "CONNECT",
                color = C.PrimaryLight,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 4.sp
            )
            Spacer(Modifier.height(18.dp))
            Text(
                "Conectividade avançada\nna palma da sua mão",
                color = C.TextDim,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(54.dp))
            Box(
                modifier = Modifier
                    .width(112.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(C.Line)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(0.72f)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Brush.horizontalGradient(listOf(C.Primary, C.Accent)))
                )
            }
        }
    }
}
