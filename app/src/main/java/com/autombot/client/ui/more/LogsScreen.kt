package com.autombot.client.ui.more

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autombot.client.ui.theme.AutomBotColors as C
import com.autombot.client.util.AppLog

/**
 * Tela 21 do mockup: Logs. Mostra eventos reais registrados pelo AppLog (hoje
 * alimentado pelo WireGuardManager e SshTunnelManager — conexao, desconexao, erros,
 * import de config). Nao ha eventos fake aqui: se a lista estiver vazia, e porque
 * nada aconteceu ainda.
 *
 * [filterName], quando informado, mostra so os eventos que mencionam esse nome de
 * conexao entre aspas (ex: "WireGuard \"nome\" conectado") — usado pelo botao de log
 * individual em cada card de conexao (WireGuardScreen/SshScreen), pra nao precisar
 * navegar ate Mais > Logs e procurar em meio a tudo.
 */
@Composable
fun LogsScreen(filterName: String? = null, onBack: () -> Unit) {
    val allEntries by AppLog.entries.collectAsState()
    val entries = if (filterName != null) allEntries.filter { it.message.contains("\"$filterName\"") } else allEntries
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxSize().background(C.Background)) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Voltar", tint = C.Text)
            }
            Text(
                if (filterName != null) "Logcat — $filterName" else "Logcat",
                color = C.Text,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            // Copia o relatorio de log como texto puro — pra poder colar e mandar
            // direto no chat em vez de precisar tirar print da tela.
            IconButton(
                onClick = {
                    if (entries.isEmpty()) {
                        Toast.makeText(context, "Nenhum log pra copiar", Toast.LENGTH_SHORT).show()
                    } else {
                        val report = buildString {
                            appendLine(if (filterName != null) "AutomBot Connect — Logs de \"$filterName\"" else "AutomBot Connect — Logs")
                            appendLine()
                            entries.forEach { entry ->
                                appendLine("[${AppLog.formatTimestamp(entry)}] ${entry.level}: ${entry.message}")
                            }
                        }
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Logs AutomBot Connect", report))
                        Toast.makeText(context, "Log copiado — pode colar e enviar", Toast.LENGTH_SHORT).show()
                    }
                }
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copiar relatório de log", tint = C.TextDim)
            }
        }

        if (entries.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("Nenhum evento registrado ainda", color = C.TextDim, fontSize = 13.sp)
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(entries) { entry ->
                    val color = when (entry.level) {
                        AppLog.Level.SUCCESS -> C.Green
                        AppLog.Level.ERROR -> C.Red
                        AppLog.Level.INFO -> C.TextDim
                    }
                    Column {
                        Text(entry.message, color = C.Text, fontSize = 13.sp)
                        Text(AppLog.formatTimestamp(entry), color = color, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}