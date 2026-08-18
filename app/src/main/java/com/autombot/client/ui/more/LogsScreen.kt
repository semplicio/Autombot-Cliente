package com.autombot.client.ui.more

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autombot.client.ui.components.AutomBotCard
import com.autombot.client.ui.components.AutomBotTopBar
import com.autombot.client.ui.theme.AutomBotColors as C
import com.autombot.client.util.AppLog

@Composable
fun LogsScreen(filterName: String? = null, onBack: () -> Unit) {
    val allEntries by AppLog.entries.collectAsState()
    val entries = if (filterName != null) allEntries.filter { it.message.contains("\"$filterName\"") } else allEntries
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxSize()) {
        AutomBotTopBar(
            title = if (filterName != null) "Logs • $filterName" else "Logs",
            onBack = onBack,
            eyebrow = "Monitoramento",
            actions = {
                IconButton(onClick = { copyLogs(context, entries, filterName) }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copiar logs", tint = C.TextDim)
                }
                IconButton(onClick = { clearLogs(context, entries, filterName) }) {
                    Icon(Icons.Default.CleaningServices, contentDescription = "Limpar logs", tint = C.TextDim)
                }
            }
        )

        if (entries.isEmpty()) {
            Column(modifier = Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.weight(1f))
                Text("Nenhum evento registrado", color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text("Os eventos reais da conexão aparecerão aqui.", color = C.TextDim, fontSize = 11.sp)
                Spacer(Modifier.weight(1f))
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp)) {
                items(entries) { entry ->
                    val color = when (entry.level) {
                        AppLog.Level.SUCCESS -> C.Green
                        AppLog.Level.ERROR -> C.Red
                        AppLog.Level.INFO -> C.AccentLight
                    }
                    AutomBotCard(modifier = Modifier.fillMaxWidth().padding(bottom = 9.dp), padding = 12.dp, accent = color) {
                        Row(verticalAlignment = Alignment.Top) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(entry.message, color = C.Text, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                Text(AppLog.formatTimestamp(entry), color = color, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun copyLogs(context: Context, entries: List<AppLog.Entry>, filterName: String?) {
    if (entries.isEmpty()) {
        Toast.makeText(context, "Nenhum log para copiar", Toast.LENGTH_SHORT).show()
        return
    }
    val report = buildString {
        appendLine(if (filterName != null) "AutomBot Connect — Logs de \"$filterName\"" else "AutomBot Connect — Logs")
        appendLine()
        entries.forEach { appendLine("[${AppLog.formatTimestamp(it)}] ${it.level}: ${it.message}") }
    }
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Logs AutomBot Connect", report))
    Toast.makeText(context, "Logs copiados", Toast.LENGTH_SHORT).show()
}

private fun clearLogs(context: Context, entries: List<AppLog.Entry>, filterName: String?) {
    if (entries.isEmpty()) {
        Toast.makeText(context, "Nenhum log para limpar", Toast.LENGTH_SHORT).show()
        return
    }
    AppLog.clear(filterName)
    Toast.makeText(
        context,
        if (filterName != null) "Logs desta conexão limpos" else "Logs limpos",
        Toast.LENGTH_SHORT
    ).show()
}
