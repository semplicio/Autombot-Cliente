package com.autombot.client.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autombot.client.ui.components.AutomBotCard
import com.autombot.client.ui.components.AutomBotGradientButton
import com.autombot.client.ui.components.AutomBotTopBar
import com.autombot.client.ui.theme.AutomBotColors as C

data class PlanOption(val id: String, val name: String, val price: String, val cadence: String, val days: Int)

val AvailablePlans = listOf(
    PlanOption("monthly", "Mensal", "R$ 29,90", "por mês", 30),
    PlanOption("annual", "Anual", "R$ 299,90", "por ano", 365)
)

@Composable
fun PlansAvailableScreen(onBack: () -> Unit, onSelect: (PlanOption) -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        AutomBotTopBar("Planos disponíveis", onBack, "Escolha seu acesso")
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            AvailablePlans.forEachIndexed { index, plan ->
                val accent = if (index == 0) C.Primary else C.Accent
                Column(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                        .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.88f), C.SurfaceRaised, C.Surface)))
                        .padding(18.dp)
                ) {
                    Row(verticalAlignment = Alignment.Top) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(plan.name, color = C.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Text(plan.price, color = C.Text, fontSize = 25.sp, fontWeight = FontWeight.ExtraBold)
                        }
                        Text(plan.cadence, color = C.Text.copy(alpha = 0.7f), fontSize = 10.sp)
                    }
                    Spacer(Modifier.height(10.dp))
                    listOf("Todos os protocolos", "Sem limite de conexões", "Suporte prioritário").forEach {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = C.Green, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(7.dp))
                            Text(it, color = C.Text, fontSize = 11.sp)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    AutomBotGradientButton("Escolher ${plan.name}", { onSelect(plan) }, Modifier.fillMaxWidth(), accent = accent)
                }
            }
        }
    }
}

@Composable
fun PixPaymentScreen(plan: PlanOption, onBack: () -> Unit, onContinue: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        AutomBotTopBar("Pagamento", onBack, "PIX • protótipo visual")
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Plano ${plan.name}", color = C.TextDim, fontSize = 12.sp)
            Text(plan.price, color = C.Text, fontSize = 25.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(18.dp))
            Text("Escaneie o QR Code para pagar", color = C.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            DemoQrCode()
            Spacer(Modifier.height(12.dp))
            Text("QR ilustrativo — nenhuma cobrança será realizada", color = C.Warning, fontSize = 10.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            AutomBotCard(modifier = Modifier.fillMaxWidth(), padding = 12.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("000201 • CÓDIGO PIX DE DEMONSTRAÇÃO", color = C.TextDim, fontSize = 10.sp, modifier = Modifier.weight(1f))
                    Icon(Icons.Default.ContentCopy, contentDescription = null, tint = C.PrimaryLight, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
            AutomBotGradientButton("Continuar demonstração", onContinue, Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun AwaitingPaymentScreen(plan: PlanOption, onBack: () -> Unit, onVerify: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        AutomBotTopBar("Aguardando pagamento", onBack, "PIX • protótipo visual")
        Column(
            modifier = Modifier.fillMaxSize().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(modifier = Modifier.size(96.dp).clip(CircleShape).background(C.Warning.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
                Text("29:45", color = C.Warning, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(22.dp))
            Text("Aguardando confirmação", color = C.Text, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text("Plano ${plan.name} • ${plan.price}", color = C.TextDim, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            Text("Após o pagamento, a confirmação pode levar alguns instantes.", color = C.TextDim, fontSize = 12.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            AutomBotGradientButton("Visualizar aprovação", onVerify, Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun PaymentApprovedScreen(plan: PlanOption, onContinue: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(modifier = Modifier.size(96.dp).clip(CircleShape).background(C.Green.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Check, contentDescription = null, tint = C.Green, modifier = Modifier.size(48.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text("Pagamento aprovado!", color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("Demonstração visual concluída", color = C.TextDim, fontSize = 12.sp)
        Spacer(Modifier.height(20.dp))
        AutomBotCard(modifier = Modifier.fillMaxWidth(), accent = C.Green) {
            Text("Plano ${plan.name}", color = C.TextDim, fontSize = 10.sp)
            Text(plan.price, color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Validade simulada: ${plan.days} dias", color = C.Green, fontSize = 11.sp)
        }
        Spacer(Modifier.height(24.dp))
        AutomBotGradientButton("Continuar", onContinue, Modifier.fillMaxWidth())
    }
}

@Composable
private fun DemoQrCode() {
    Box(modifier = Modifier.size(188.dp).clip(RoundedCornerShape(14.dp)).background(Color.White).padding(12.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cells = 25
            val cell = size.width / cells
            for (y in 0 until cells) {
                for (x in 0 until cells) {
                    val finder = (x < 7 && y < 7) || (x >= cells - 7 && y < 7) || (x < 7 && y >= cells - 7)
                    val finderPixel = finder && (x % 6 == 0 || y % 6 == 0 || (x % 6 in 2..4 && y % 6 in 2..4))
                    val dataPixel = !finder && ((x * 17 + y * 11 + x * y) % 7 < 3)
                    if (finderPixel || dataPixel) {
                        drawRect(Color.Black, topLeft = androidx.compose.ui.geometry.Offset(x * cell, y * cell), size = androidx.compose.ui.geometry.Size(cell, cell))
                    }
                }
            }
        }
    }
}
