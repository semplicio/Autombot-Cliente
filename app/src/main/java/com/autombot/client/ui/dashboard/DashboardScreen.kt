package com.autombot.client.ui.dashboard

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.autombot.client.panel.PanelPromotion
import com.autombot.client.panel.PanelWebhookClient
import com.autombot.client.protocols.modern.ModernProtocolManagerProvider
import com.autombot.client.protocols.modern.ModernProtocolStatus
import com.autombot.client.ui.components.AutomBotCard
import com.autombot.client.ui.components.AutomBotGradientButton
import com.autombot.client.ui.components.AutomBotStatusDot
import com.autombot.client.ui.components.protocolVisual
import com.autombot.client.ui.rememberManagedMode
import com.autombot.client.ui.theme.AutomBotColors as C
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URL

/** Dashboard visual do modo gerenciado, conectado aos mesmos estados reais. */
@Composable
fun DashboardScreen(
    trialCountdown: String?,
    activeConnections: Int,
    trafficLabel: String,
    onRenew: () -> Unit,
    onOpenConnections: () -> Unit,
    quickConnection: DashboardQuickConnection? = null,
    onToggleQuickConnection: () -> Unit = {},
    onOpenQuickConnection: () -> Unit = {},
    managedAccountUser: String? = null,
    managedAccountStatus: String? = null,
    managedAccountExpiry: String? = null,
    updateAvailable: Boolean = false,
    applyingUpdate: Boolean = false,
    checkingUpdate: Boolean = false,
    onCheckUpdate: () -> Unit = {},
    onApplyUpdate: () -> Unit = {}
) {
    val context = LocalContext.current
    val managedMode = rememberManagedMode()
    val prefs = remember(context) {
        context.getSharedPreferences("autombot_app", android.content.Context.MODE_PRIVATE)
    }
    val managedBaseUrl = remember(managedMode) {
        if (managedMode) prefs.getString("managed_base_url", "").orEmpty() else ""
    }
    val managedUser = if (managedMode) {
        managedAccountUser ?: prefs.getString("managed_usuario", "").orEmpty()
    } else ""
    val managedStatus = if (managedMode) {
        managedAccountStatus ?: prefs.getString("managed_account_status", "").orEmpty()
    } else ""
    val managedExpiry = if (managedMode) {
        managedAccountExpiry ?: prefs.getString("managed_expira_em", "").orEmpty()
    } else ""
    val normalizedStatus = managedStatus.trim().lowercase()
    val accountInactive = managedMode && normalizedStatus.isNotBlank() &&
        normalizedStatus !in setOf("ativo", "active", "ok")

    var promotions by remember(managedBaseUrl) { mutableStateOf<List<PanelPromotion>>(emptyList()) }

    LaunchedEffect(managedMode, managedBaseUrl) {
        promotions = if (managedMode && managedBaseUrl.isNotBlank()) {
            withContext(Dispatchers.IO) {
                runCatching { PanelWebhookClient(managedBaseUrl).fetchPromotions() }.getOrDefault(emptyList())
            }
        } else {
            emptyList()
        }
    }

    val modernManager = remember(context) { ModernProtocolManagerProvider.get(context) }
    val modernConnections by modernManager.connections.collectAsState()
    val modernActive = modernConnections.count { it.status == ModernProtocolStatus.CONNECTED }
    val totalActiveConnections = activeConnections + modernActive
    val modernRx = modernConnections.sumOf { it.rxBytes }
    val modernTx = modernConnections.sumOf { it.txBytes }
    val modernTraffic = modernRx + modernTx

    val pingResult = remember { mutableStateOf("Desconectado") }

    LaunchedEffect(totalActiveConnections) {
        pingResult.value = if (totalActiveConnections > 0) {
            withContext(Dispatchers.IO) {
                try {
                    val process = ProcessBuilder("ping", "-c", "1", "-W", "2", "8.8.8.8").start()
                    val reader = BufferedReader(InputStreamReader(process.inputStream))
                    var time = "Indisponível"
                    reader.forEachLine { line ->
                        if (line.contains("time=")) time = "${line.substringAfter("time=").substringBefore(" ms")} ms"
                    }
                    process.waitFor()
                    time
                } catch (_: Exception) {
                    "Indisponível"
                }
            }
        } else {
            "Desconectado"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Text("VISÃO GERAL", color = C.PrimaryLight, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
        Spacer(Modifier.height(4.dp))
        Text("Dashboard", color = C.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("Acompanhe seu plano e suas conexões", color = C.TextDim, fontSize = 12.sp)

        if (accountInactive) {
            Spacer(Modifier.height(14.dp))
            AutomBotCard(modifier = Modifier.fillMaxWidth(), accent = C.Red) {
                Text(
                    if (normalizedStatus == "bloqueado") "Conta bloqueada" else "Conta expirada ou inativa",
                    color = C.Red,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                if (managedUser.isNotBlank()) {
                    Text("Conta: $managedUser", color = C.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
                if (managedExpiry.isNotBlank()) {
                    Text("Validade informada pelo servidor: $managedExpiry", color = C.TextDim, fontSize = 10.sp)
                }
                Text(
                    "As configurações continuam sincronizadas neste aparelho, mas novas conexões ficam bloqueadas até a conta voltar a ficar ativa.",
                    color = C.TextDim,
                    fontSize = 10.sp
                )
                Spacer(Modifier.height(10.dp))
                AutomBotGradientButton(
                    text = "Renovar agora",
                    onClick = onRenew,
                    modifier = Modifier.fillMaxWidth(),
                    accent = C.Red
                )
            }
        }

        // A ação de sincronização fica invisível enquanto o app está alinhado com o
        // painel. Ela só aparece depois que a checagem automática detecta uma revisão
        // de configuração diferente, evitando incentivar chamadas manuais repetidas.
        if (managedMode && updateAvailable) {
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(C.Accent.copy(alpha = 0.16f))
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.SwapVert,
                    contentDescription = null,
                    tint = C.AccentLight,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Novas configurações disponíveis",
                        color = C.Text,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "Há mudanças no painel. Atualize todos os protocolos de uma vez.",
                        color = C.TextDim,
                        fontSize = 10.sp
                    )
                }
                Spacer(Modifier.width(8.dp))
                AutomBotGradientButton(
                    text = if (applyingUpdate) "Atualizando…" else "Atualizar",
                    onClick = onApplyUpdate,
                    enabled = !applyingUpdate,
                    accent = C.AccentLight
                )
            }
        }

        if (managedMode && !accountInactive && trialCountdown == null && managedExpiry.isNotBlank()) {
            val validity = remember(managedExpiry) { managedValidityLabels(managedExpiry) }
            Spacer(Modifier.height(14.dp))
            AutomBotCard(modifier = Modifier.fillMaxWidth(), accent = C.Green) {
                Text("Plano ativo", color = C.Green, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(validity.first, color = C.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                validity.second?.let {
                    Text(it, color = C.TextDim, fontSize = 10.sp)
                }
                Spacer(Modifier.height(10.dp))
                AutomBotGradientButton(
                    text = "Renovar agora",
                    onClick = onRenew,
                    modifier = Modifier.fillMaxWidth(),
                    accent = C.Green
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        if (trialCountdown != null && !accountInactive) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(C.PrimaryDim, C.Primary.copy(alpha = 0.84f), C.SurfaceRaised)
                        )
                    )
                    .padding(18.dp)
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("PLANO ATUAL", color = C.Text.copy(alpha = 0.72f), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(5.dp))
                        Text("Teste Grátis", color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("Acesso completo por 2 horas", color = C.Text.copy(alpha = 0.78f), fontSize = 11.sp)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("EXPIRA EM", color = C.Text.copy(alpha = 0.65f), fontSize = 8.sp)
                        Text(trialCountdown, color = C.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(16.dp))
                AutomBotGradientButton(
                    text = "Renovar agora",
                    onClick = onRenew,
                    modifier = Modifier.fillMaxWidth(),
                    accent = C.PrimaryLight
                )
            }
            Spacer(Modifier.height(16.dp))
        }

        AutomBotCard(modifier = Modifier.fillMaxWidth(), onClick = onOpenConnections) {
            DashboardMetric(Icons.Default.NetworkCheck, "Conexões", "$totalActiveConnections ativa(s)", C.Green)
            MetricDivider()
            DashboardMetric(Icons.Default.SwapVert, "Tráfego da sessão", trafficLabel, C.AccentLight)
            if (modernTraffic > 0L) {
                MetricDivider()
                DashboardMetric(Icons.Default.SwapVert, "Tráfego Hysteria2 / TUIC", formatDashboardBytes(modernTraffic), C.Accent)
            }
            MetricDivider()
            DashboardMetric(Icons.Default.Speed, "Latência", pingResult.value, C.PrimaryLight)
            MetricDivider()
            DashboardMetric(Icons.Default.Devices, "Dispositivos", "1 ativo", C.Warning)
        }

        Spacer(Modifier.height(14.dp))
        AutomBotGradientButton(
            text = "Ver minhas conexões",
            onClick = onOpenConnections,
            modifier = Modifier.fillMaxWidth()
        )

        quickConnection?.let { quick ->
            Spacer(Modifier.height(14.dp))
            QuickConnectionCard(
                connection = quick,
                onToggle = onToggleQuickConnection,
                onOpen = onOpenQuickConnection,
                enabled = !accountInactive || quick.connected
            )
        }

        if (managedMode && promotions.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text("DIVULGAÇÕES", color = C.PrimaryLight, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
            Spacer(Modifier.height(10.dp))
            promotions.forEach { promotion ->
                PromotionCard(promotion)
                Spacer(Modifier.height(14.dp))
            }
        } else {
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
private fun QuickConnectionCard(
    connection: DashboardQuickConnection,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    enabled: Boolean
) {
    val (icon, protocolColor) = protocolVisual(connection.protocolId)
    AutomBotCard(
        modifier = Modifier.fillMaxWidth(),
        accent = if (connection.connected) C.Green else null,
        onClick = onOpen
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(protocolColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = protocolColor, modifier = Modifier.size(23.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(connection.displayName, color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(connection.connectionName, color = C.Text, fontSize = 12.sp)
                if (connection.detail.isNotBlank()) {
                    Text(connection.detail, color = C.TextDim, fontSize = 10.sp)
                }
                Spacer(Modifier.height(4.dp))
                AutomBotStatusDot(
                    color = when {
                        connection.connected -> C.Green
                        connection.statusLabel == "Erro" -> C.Red
                        connection.busy -> C.Accent
                        else -> C.TextDim
                    },
                    label = connection.statusLabel
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        AutomBotGradientButton(
            text = when {
                connection.busy -> connection.statusLabel
                connection.connected -> "Desconectar"
                !enabled -> "Conta inativa"
                else -> "Conectar"
            },
            onClick = onToggle,
            enabled = enabled && !connection.busy,
            modifier = Modifier.fillMaxWidth(),
            accent = if (connection.connected) C.Red else C.AccentLight
        )
    }
}

@Composable
private fun PromotionCard(promotion: PanelPromotion) {
    val uriHandler = LocalUriHandler.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(C.Surface)
            .padding(12.dp)
    ) {
        if (promotion.mediaType == "video") {
            PromotionVideo(promotion.mediaUrl)
        } else {
            PromotionImage(promotion.mediaUrl, promotion.title)
        }
        Spacer(Modifier.height(12.dp))
        Text(promotion.title, color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        if (promotion.description.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(promotion.description, color = C.TextDim, fontSize = 11.sp)
        }
        promotion.linkUrl?.let { link ->
            Spacer(Modifier.height(10.dp))
            Text(
                "Saiba mais",
                color = C.AccentLight,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { runCatching { uriHandler.openUri(link) } }
            )
        }
    }
}

@Composable
private fun PromotionImage(url: String, description: String) {
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = url) {
        value = withContext(Dispatchers.IO) {
            runCatching { URL(url).openStream().use { BitmapFactory.decodeStream(it) } }.getOrNull()
        }
    }
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 150.dp, max = 260.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(C.SurfaceAlt),
        contentAlignment = Alignment.Center
    ) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = description,
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.FillWidth
            )
        } ?: Text("Carregando imagem…", color = C.TextDim, fontSize = 11.sp, modifier = Modifier.padding(28.dp))
    }
}

@Composable
private fun PromotionVideo(url: String) {
    AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .height(210.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(C.SurfaceAlt),
        factory = { ctx ->
            VideoView(ctx).apply {
                tag = url
                setMediaController(MediaController(ctx).also { it.setAnchorView(this) })
                setVideoURI(Uri.parse(url))
                setOnPreparedListener { player -> player.isLooping = false }
            }
        },
        update = { view ->
            if (view.tag != url) {
                view.tag = url
                view.setVideoURI(Uri.parse(url))
            }
        }
    )
}

@Composable
private fun DashboardMetric(icon: ImageVector, label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(color.copy(alpha = 0.13f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = C.TextDim, fontSize = 10.sp)
            Text(value, color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun MetricDivider() {
    Spacer(Modifier.height(7.dp))
    androidx.compose.material3.HorizontalDivider(color = C.Line.copy(alpha = 0.7f))
    Spacer(Modifier.height(7.dp))
}

private fun formatDashboardBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val group = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.lastIndex)
    return String.format("%.2f %s", bytes / Math.pow(1024.0, group.toDouble()), units[group])
}


private fun managedValidityLabels(raw: String): Pair<String, String?> {
    val patterns = listOf(
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd"
    )
    val parsed = patterns.firstNotNullOfOrNull { pattern ->
        runCatching {
            java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault()).apply { isLenient = false }.parse(raw)
        }.getOrNull()
    }
    if (parsed == null) return "Conta válida até $raw" to null
    val dateLabel = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault()).format(parsed)
    val remainingMs = (parsed.time - System.currentTimeMillis()).coerceAtLeast(0L)
    val days = if (remainingMs == 0L) 0L else (remainingMs + 86_399_999L) / 86_400_000L
    val remainingLabel = when (days) {
        0L -> "Vence hoje"
        1L -> "1 dia restante"
        else -> "$days dias restantes"
    }
    return "Conta válida até $dateLabel" to remainingLabel
}
