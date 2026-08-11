package com.autombot.networkprobe

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

class ProxyAnalyzerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val engine = ProxyAnalyzerEngine(applicationContext)
        setContent {
            MaterialTheme {
                ProxyAnalyzerScreen(
                    engine = engine,
                    onShare = { report -> ReportShare.share(this, report.toJson()) }
                )
            }
        }
    }
}

@Composable
private fun ProxyAnalyzerScreen(
    engine: ProxyAnalyzerEngine,
    onShare: (ProxyAnalyzerReport) -> Unit
) {
    var proxyHost by remember { mutableStateOf("") }
    var proxyPort by remember { mutableStateOf("8080") }
    var kind by remember { mutableStateOf(ProxyKind.AUTO) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var targetHost by remember { mutableStateOf("core.infinitenet.net") }
    var targetPort by remember { mutableStateOf("443") }
    var running by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var report by remember { mutableStateOf<ProxyAnalyzerReport?>(null) }
    val scope = rememberCoroutineScope()

    Surface(modifier = Modifier.fillMaxSize(), color = ProxyBackground) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ProxySurface, RoundedCornerShape(18.dp))
                        .padding(18.dp)
                ) {
                    Text(
                        "AutomBot Proxy Analyzer",
                        color = ProxyText,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Testa um proxy informado por você na rede atual e indica quais tipos de transporte ele consegue encaminhar.",
                        color = ProxyDim,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                }
            }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ProxySurface, RoundedCornerShape(18.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Proxy para testar", color = ProxyText, fontWeight = FontWeight.SemiBold)
                    ProxyField(proxyHost, { proxyHost = it; error = null }, "Domínio / IP do proxy")
                    ProxyField(
                        proxyPort,
                        { proxyPort = it.filter(Char::isDigit); error = null },
                        "Porta do proxy",
                        KeyboardType.Number
                    )

                    Text("Tipo", color = ProxyDim, fontSize = 11.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ProxyKind.values().forEach { option ->
                            Button(
                                onClick = { kind = option },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(11.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (kind == option) ProxyAccent else ProxySurfaceAlt
                                )
                            ) {
                                Text(
                                    when (option) {
                                        ProxyKind.AUTO -> "Auto"
                                        ProxyKind.HTTP -> "HTTP"
                                        ProxyKind.SOCKS5 -> "SOCKS5"
                                    },
                                    fontSize = 11.sp,
                                    color = ProxyText
                                )
                            }
                        }
                    }

                    ProxyField(username, { username = it }, "Usuário (opcional)")
                    ProxyField(password, { password = it }, "Senha (opcional)")
                }
            }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ProxySurface, RoundedCornerShape(18.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Destino de validação", color = ProxyText, fontWeight = FontWeight.SemiBold)
                    Text(
                        "O proxy será testado tentando alcançar um endpoint da sua infraestrutura.",
                        color = ProxyDim,
                        fontSize = 11.sp
                    )
                    ProxyField(targetHost, { targetHost = it; error = null }, "Host de destino")
                    ProxyField(
                        targetPort,
                        { targetPort = it.filter(Char::isDigit); error = null },
                        "Porta de destino",
                        KeyboardType.Number
                    )
                }
            }

            error?.let { message ->
                item { Text(message, color = ProxyFail, fontSize = 12.sp) }
            }

            item {
                Button(
                    onClick = {
                        val proxyPortNumber = proxyPort.toIntOrNull()
                        val targetPortNumber = targetPort.toIntOrNull()
                        when {
                            proxyHost.isBlank() -> error = "Informe o domínio ou IP do proxy."
                            proxyPortNumber == null || proxyPortNumber !in 1..65535 -> error = "Porta do proxy inválida."
                            targetHost.isBlank() -> error = "Informe o host de destino."
                            targetPortNumber == null || targetPortNumber !in 1..65535 -> error = "Porta de destino inválida."
                            else -> {
                                error = null
                                running = true
                                report = null
                                scope.launch {
                                    report = engine.analyze(
                                        ProxyAnalyzerConfig(
                                            proxyHost = proxyHost.trim(),
                                            proxyPort = proxyPortNumber,
                                            kind = kind,
                                            username = username,
                                            password = password,
                                            targetHost = targetHost.trim(),
                                            targetPort = targetPortNumber
                                        )
                                    )
                                    running = false
                                }
                            }
                        }
                    },
                    enabled = !running,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ProxyAccent)
                ) {
                    if (running) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                        Spacer(Modifier.size(10.dp))
                        Text("Analisando proxy…")
                    } else {
                        Text("Analisar proxy", fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            report?.let { current ->
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(ProxySurface, RoundedCornerShape(18.dp))
                            .padding(16.dp)
                    ) {
                        Text("Resultado", color = ProxyText, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(5.dp))
                        Text(
                            "Rede: ${current.networkLabel}\nProxy: ${current.config.proxyHost}:${current.config.proxyPort}",
                            color = ProxyDim,
                            fontSize = 11.sp,
                            lineHeight = 17.sp
                        )
                    }
                }

                items(current.results) { result ->
                    ProxyResultCard(result)
                }

                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(ProxySurface, RoundedCornerShape(18.dp))
                            .padding(16.dp)
                    ) {
                        Text("Compatibilidade", color = ProxyText, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(7.dp))
                        if (current.capabilities.isEmpty()) {
                            Text("Nenhuma capacidade de proxy foi confirmada.", color = ProxyDim, fontSize = 11.sp)
                        } else {
                            current.capabilities.forEach {
                                Text("• $it", color = ProxyDim, fontSize = 11.sp, lineHeight = 17.sp)
                                Spacer(Modifier.height(4.dp))
                            }
                        }
                    }
                }

                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(ProxyAccent.copy(alpha = 0.12f), RoundedCornerShape(18.dp))
                            .padding(16.dp)
                    ) {
                        Text("Diagnóstico", color = ProxyAccent, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        Spacer(Modifier.height(5.dp))
                        Text(current.recommendation, color = ProxyText, fontSize = 13.sp, lineHeight = 19.sp)
                    }
                }

                item {
                    Button(
                        onClick = { onShare(current) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ProxySurfaceAlt)
                    ) {
                        Text("Compartilhar relatório do proxy", color = ProxyText)
                    }
                }
            }

            item {
                Text(
                    "O analisador testa somente o proxy e o destino informados. Ele não procura proxies abertos, não varre redes de terceiros e não busca exceções de cobrança/zero-rating.",
                    color = ProxyDim,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(bottom = 18.dp)
                )
            }
        }
    }
}

@Composable
private fun ProxyField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        shape = RoundedCornerShape(13.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = ProxyText,
            unfocusedTextColor = ProxyText,
            focusedBorderColor = ProxyAccent,
            unfocusedBorderColor = ProxyLine,
            focusedLabelColor = ProxyAccent,
            unfocusedLabelColor = ProxyDim,
            cursorColor = ProxyAccent
        )
    )
}

@Composable
private fun ProxyResultCard(result: ProbeResult) {
    val statusColor = when (result.status) {
        ProbeStatus.PASS -> ProxyPass
        ProbeStatus.WARN -> ProxyWarn
        ProbeStatus.FAIL -> ProxyFail
    }
    val statusLabel = when (result.status) {
        ProbeStatus.PASS -> "OK"
        ProbeStatus.WARN -> "PARCIAL"
        ProbeStatus.FAIL -> "FALHA"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ProxySurface, RoundedCornerShape(16.dp))
            .padding(15.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .padding(top = 5.dp)
                .size(9.dp)
                .background(statusColor, CircleShape)
        )
        Spacer(Modifier.size(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(result.name, color = ProxyText, fontWeight = FontWeight.SemiBold)
                Text(statusLabel, color = statusColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(4.dp))
            Text(result.detail, color = ProxyDim, fontSize = 11.sp, lineHeight = 16.sp)
        }
    }
}

private val ProxyBackground = Color(0xFF120E1B)
private val ProxySurface = Color(0xFF1C1628)
private val ProxySurfaceAlt = Color(0xFF292039)
private val ProxyAccent = Color(0xFF8B5CF6)
private val ProxyText = Color(0xFFF5F2FA)
private val ProxyDim = Color(0xFFAAA1B9)
private val ProxyLine = Color(0xFF3A3049)
private val ProxyPass = Color(0xFF4ADE80)
private val ProxyWarn = Color(0xFFFBBF24)
private val ProxyFail = Color(0xFFF87171)
