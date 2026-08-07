package com.autombot.client.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import com.autombot.client.ui.theme.AutomBotColors as C

enum class MainTab(val label: String) {
    Dashboard("Início"),
    Connections("Conexões"),
    Plan("Planos"),
    More("Mais")
}

@Composable
fun BottomNavBar(selected: MainTab, onSelect: (MainTab) -> Unit, showPlan: Boolean = true) {
    val tabs = if (showPlan) listOf(MainTab.Dashboard, MainTab.Connections, MainTab.Plan, MainTab.More)
    else listOf(MainTab.Dashboard, MainTab.Connections, MainTab.More)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(C.Surface.copy(alpha = 0.98f))
            .border(width = 1.dp, color = C.Line.copy(alpha = 0.7f))
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceAround
    ) {
        val iconFor = mapOf(
            MainTab.Dashboard to Icons.Default.Home,
            MainTab.Connections to Icons.Default.Link,
            MainTab.Plan to Icons.Default.Payments,
            MainTab.More to Icons.Default.MoreHoriz
        )
        tabs.forEach { tab -> NavItem(tab, iconFor.getValue(tab), selected, onSelect) }
    }
}

@Composable
private fun NavItem(
    tab: MainTab,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: MainTab,
    onSelect: (MainTab) -> Unit
) {
    val active = tab == selected
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) C.Primary.copy(alpha = 0.11f) else androidx.compose.ui.graphics.Color.Transparent)
            .clickable { onSelect(tab) }
            .padding(horizontal = 13.dp, vertical = 4.dp)
    ) {
        Icon(icon, contentDescription = tab.label, tint = if (active) C.PrimaryLight else C.TextDim, modifier = Modifier.size(20.dp))
        Text(
            tab.label,
            color = if (active) C.PrimaryLight else C.TextDim,
            fontSize = 10.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}
