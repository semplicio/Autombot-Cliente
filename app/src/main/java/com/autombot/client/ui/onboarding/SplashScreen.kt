package com.autombot.client.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
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
import com.autombot.client.ui.theme.AutomBotColors as C
import kotlinx.coroutines.delay

/** Tela 01 do mockup: splash com marca + tagline, avança sozinha depois de um instante. */
@Composable
fun SplashScreen(onFinished: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(1400)
        onFinished()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(C.Background)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        AutomBotMark(size = 84.dp)
        Spacer(Modifier.height(20.dp))
        Text(
            "AUTOMBOT",
            color = C.Text,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
        Text(
            "CONNECT",
            color = C.Primary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 4.sp
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Conectividade avançada na palma da sua mão",
            color = C.TextDim,
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )
    }
}
