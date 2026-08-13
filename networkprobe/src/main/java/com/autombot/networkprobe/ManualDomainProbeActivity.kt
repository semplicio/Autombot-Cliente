package com.autombot.networkprobe

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.net.IDN
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URI
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

private enum class ManualDomainState {
    REACHABLE,
    TRANSPORT_ONLY,
    DNS_ERROR,
    TIMEOUT,
    TLS_ERROR,
    ERROR
}

private enum class GeneralInternetState {
    AVAILABLE,
    BLOCKED,
    INCONCLUSIVE,
    NO_CELLULAR
}

private enum class ManualDomainClassification {
    CORE_SPONSORED_WITHOUT_GENERAL_DATA,
    STRONG_SPONSORED_CANDIDATE,
    SPONSORED_CANDIDATE,
    REACHABLE_WITH_GENERAL_INTERNET,
    INCONCLUSIVE,
    UNREACHABLE
}

private data class ManualDomainTarget(
    val domain: String,
    val port: Int,
    val tls: Boolean
)

private data class ProbeAttempt(
    val state: ManualDomainState,
    val connectedIp: String?,
    val httpStatus: Int?,
    val detail: String
)

private data class ManualDomainResult(
    val target: ManualDomainTarget,
    val coreDeclared: Boolean,
    val resolvedIps: List<String>,
    val connectedIp: String?,
    val httpStatus: Int?,
    val state: ManualDomainState,
    val detail: String,
    val successfulAttempts: Int,
    val totalAttempts: Int,
    val classification: ManualDomainClassification
)

private data class GeneralControlResult(
    val domain: String,
    val reachable: Boolean,
    val detail: String
)

private data class ManualDomainReport(
    val cellularNetworkFound: Boolean,
    val generalInternetState: GeneralInternetState,
    val controls: List<GeneralControlResult>,
    val results: List<ManualDomainResult>
) {
    private val controlSuccesses: Int
        get() = controls.count { it.reachable }

    fun toJson(): String = JSONObject()
        .put("tool", "AutomBot Manual Domain Probe")
        .put("version", "1.2.0")
        .put("cellular_network_found", cellularNetworkFound)
        .put("general_internet_state", generalInternetState.name.lowercase())
        .put("control_successes", controlSuccesses)
        .put("control_total", controls.size)
        .put("controls", JSONArray().apply {
            controls.forEach { item ->
                put(JSONObject()
                    .put("domain", item.domain)
                    .put("reachable", item.reachable)
                    .put("detail", item.detail)
                )
            }
        })
        .put("results", JSONArray().apply {
            results.forEach { item ->
                put(JSONObject()
                    .put("domain", item.target.domain)
                    .put("port", item.target.port)
                    .put("tls", item.target.tls)
                    .put("declared_by_core", item.coreDeclared)
                    .put("resolved_ips", JSONArray(item.resolvedIps))
                    .put("connected_ip", item.connectedIp ?: JSONObject.NULL)
                    .put("http_status", item.httpStatus ?: JSONObject.NULL)
                    .put("state", item.state.name.lowercase())
                    .put("successful_attempts", item.successfulAttempts)
                    .put("total_attempts", item.totalAttempts)
                    .put("classification", item.classification.name.lowercase())
                    .put("detail", item.detail)
                )
            }
        })
        .put(
            "note",
            "A classificação usa somente domínios informados manualmente. Quando os três controles neutros de Internet geral falham e um domínio informado responde repetidamente, o Probe o marca como candidato a acesso patrocinado/permitido sem Internet geral. Isso é evidência de comportamento da rede, não garantia de política de cobrança da operadora."
        )
        .toString(2)

    fun toText(): String = buildString {
        appendLine("AUTOMBOT NETWORK PROBE — TESTE SEM DADOS / LISTA MANUAL")
        appendLine("Rede: dados móveis / 4G / 5G")
        appendLine()
        if (!cellularNetworkFound) {
            appendLine("Nenhuma interface celular com capacidade de Internet foi encontrada.")
            return@buildString
        }

        appendLine("CONTROLE DE INTERNET GERAL")
        appendLine("  Estado: ${generalInternetLabel(generalInternetState)}")
        appendLine("  Controles: $controlSuccesses/${controls.size} responderam")
        controls.forEach { control ->
            appendLine("  ${if (control.reachable) "OK" else "FALHA"} ${control.domain} — ${control.detail}")
        }
        appendLine()

        results.forEach { item ->
            appendLine("${item.target.domain}:${item.target.port} — ${stateLabel(item.state)}")
            appendLine("  Classificação: ${classificationLabel(item.classification)}")
            appendLine("  Repetições: ${item.successfulAttempts}/${item.totalAttempts} com resposta de aplicação")
            appendLine("  Core: ${if (item.coreDeclared) "declarado no manifesto patrocinado salvo" else "não declarado no manifesto patrocinado salvo"}")
            if (item.resolvedIps.isNotEmpty()) appendLine("  DNS móvel: ${item.resolvedIps.joinToString()}")
            item.connectedIp?.let { appendLine("  IP conectado: $it") }
            item.httpStatus?.let { appendLine("  HTTP: $it") }
            appendLine("  ${item.detail}")
            appendLine()
        }
        appendLine("Interpretação: domínio alcançável enquanto 0/3 controles de Internet geral respondem é um forte indício de acesso permitido/patrocinado naquela condição de rede. O Probe não afirma que a operadora deixará de contabilizar tráfego nem substitui confirmação contratual.")
    }.trimEnd()
}

private class ManualDomainProbeEngine(context: Context) {
    private val connectivity =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    suspend fun run(targets: List<ManualDomainTarget>, manifest: SponsoredDomainManifest?): ManualDomainReport =
        withContext(Dispatchers.IO) {
            val network = selectCellularNetwork()
                ?: return@withContext ManualDomainReport(
                    cellularNetworkFound = false,
                    generalInternetState = GeneralInternetState.NO_CELLULAR,
                    controls = emptyList(),
                    results = emptyList()
                )

            val controls = CONTROL_DOMAINS.map { domain ->
                val target = ManualDomainTarget(domain = domain, port = 443, tls = true)
                val result = testSingleRound(network, target)
                GeneralControlResult(
                    domain = domain,
                    reachable = result.state == ManualDomainState.REACHABLE,
                    detail = result.detail
                )
            }
            val generalState = when (controls.count { it.reachable }) {
                0 -> GeneralInternetState.BLOCKED
                1 -> GeneralInternetState.INCONCLUSIVE
                else -> GeneralInternetState.AVAILABLE
            }

            val declared = manifest?.endpoints
                ?.map { it.domain.lowercase() }
                ?.toSet()
                .orEmpty()

            ManualDomainReport(
                cellularNetworkFound = true,
                generalInternetState = generalState,
                controls = controls,
                results = targets.map { target ->
                    testRepeated(
                        network = network,
                        target = target,
                        coreDeclared = target.domain.lowercase() in declared,
                        generalState = generalState
                    )
                }
            )
        }

    private fun testRepeated(
        network: Network,
        target: ManualDomainTarget,
        coreDeclared: Boolean,
        generalState: GeneralInternetState
    ): ManualDomainResult {
        val resolved = try {
            network.getAllByName(target.domain)
                .distinctBy { it.hostAddress }
                .take(MAX_ADDRESSES)
        } catch (error: Exception) {
            return ManualDomainResult(
                target = target,
                coreDeclared = coreDeclared,
                resolvedIps = emptyList(),
                connectedIp = null,
                httpStatus = null,
                state = ManualDomainState.DNS_ERROR,
                detail = error.message ?: "falha ao resolver pela rede móvel",
                successfulAttempts = 0,
                totalAttempts = TARGET_REPETITIONS,
                classification = ManualDomainClassification.UNREACHABLE
            )
        }
        if (resolved.isEmpty()) {
            return ManualDomainResult(
                target = target,
                coreDeclared = coreDeclared,
                resolvedIps = emptyList(),
                connectedIp = null,
                httpStatus = null,
                state = ManualDomainState.DNS_ERROR,
                detail = "DNS móvel não retornou endereço",
                successfulAttempts = 0,
                totalAttempts = TARGET_REPETITIONS,
                classification = ManualDomainClassification.UNREACHABLE
            )
        }

        val attempts = (1..TARGET_REPETITIONS).map { testResolvedOnce(network, target, resolved) }
        val successes = attempts.count { it.state == ManualDomainState.REACHABLE }
        val best = attempts.firstOrNull { it.state == ManualDomainState.REACHABLE }
            ?: attempts.firstOrNull { it.state == ManualDomainState.TRANSPORT_ONLY }
            ?: attempts.last()
        val finalState = when {
            successes >= 2 -> ManualDomainState.REACHABLE
            attempts.any { it.state == ManualDomainState.TRANSPORT_ONLY } -> ManualDomainState.TRANSPORT_ONLY
            else -> best.state
        }
        val classification = classify(
            coreDeclared = coreDeclared,
            generalState = generalState,
            successfulAttempts = successes,
            state = finalState
        )
        val summary = when {
            successes == TARGET_REPETITIONS -> "$successes/$TARGET_REPETITIONS tentativas responderam completamente"
            successes > 0 -> "$successes/$TARGET_REPETITIONS tentativas responderam completamente; resultado parcialmente estável"
            else -> "0/$TARGET_REPETITIONS tentativas responderam completamente"
        }

        return ManualDomainResult(
            target = target,
            coreDeclared = coreDeclared,
            resolvedIps = resolved.mapNotNull { it.hostAddress },
            connectedIp = best.connectedIp,
            httpStatus = best.httpStatus,
            state = finalState,
            detail = "$summary. ${best.detail}",
            successfulAttempts = successes,
            totalAttempts = TARGET_REPETITIONS,
            classification = classification
        )
    }

    private fun classify(
        coreDeclared: Boolean,
        generalState: GeneralInternetState,
        successfulAttempts: Int,
        state: ManualDomainState
    ): ManualDomainClassification {
        if (state != ManualDomainState.REACHABLE || successfulAttempts == 0) {
            return ManualDomainClassification.UNREACHABLE
        }
        if (successfulAttempts == 1) {
            return ManualDomainClassification.INCONCLUSIVE
        }
        return when (generalState) {
            GeneralInternetState.BLOCKED -> when {
                coreDeclared -> ManualDomainClassification.CORE_SPONSORED_WITHOUT_GENERAL_DATA
                successfulAttempts == TARGET_REPETITIONS -> ManualDomainClassification.STRONG_SPONSORED_CANDIDATE
                else -> ManualDomainClassification.SPONSORED_CANDIDATE
            }
            GeneralInternetState.AVAILABLE -> ManualDomainClassification.REACHABLE_WITH_GENERAL_INTERNET
            GeneralInternetState.INCONCLUSIVE,
            GeneralInternetState.NO_CELLULAR -> ManualDomainClassification.INCONCLUSIVE
        }
    }

    private fun testSingleRound(network: Network, target: ManualDomainTarget): ProbeAttempt {
        val addresses = try {
            network.getAllByName(target.domain)
                .distinctBy { it.hostAddress }
                .take(MAX_ADDRESSES)
        } catch (error: Exception) {
            return ProbeAttempt(
                ManualDomainState.DNS_ERROR,
                null,
                null,
                error.message ?: "DNS móvel falhou"
            )
        }
        if (addresses.isEmpty()) {
            return ProbeAttempt(ManualDomainState.DNS_ERROR, null, null, "DNS móvel não retornou endereço")
        }
        return testResolvedOnce(network, target, addresses)
    }

    private fun testResolvedOnce(
        network: Network,
        target: ManualDomainTarget,
        addresses: List<java.net.InetAddress>
    ): ProbeAttempt {
        var lastState = ManualDomainState.ERROR
        var lastDetail = "nenhuma tentativa concluída"
        var lastIp: String? = null

        for (address in addresses) {
            lastIp = address.hostAddress
            try {
                network.socketFactory.createSocket().use { raw ->
                    raw.soTimeout = IO_TIMEOUT_MS
                    raw.connect(InetSocketAddress(address, target.port), CONNECT_TIMEOUT_MS)
                    if (!target.tls) {
                        val status = requestHttp(raw, target.domain)
                        return if (status != null) {
                            ProbeAttempt(
                                ManualDomainState.REACHABLE,
                                address.hostAddress,
                                status,
                                "DNS + TCP + Host HTTP responderam pela rede móvel"
                            )
                        } else {
                            ProbeAttempt(
                                ManualDomainState.TRANSPORT_ONLY,
                                address.hostAddress,
                                null,
                                "TCP conectou, mas não houve resposta HTTP válida"
                            )
                        }
                    }

                    val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                        .createSocket(raw, target.domain, target.port, true) as SSLSocket
                    ssl.soTimeout = IO_TIMEOUT_MS
                    val params = ssl.sslParameters
                    params.serverNames = listOf(SNIHostName(target.domain))
                    params.endpointIdentificationAlgorithm = "HTTPS"
                    ssl.sslParameters = params
                    ssl.startHandshake()
                    ssl.use { socket ->
                        val status = requestHttp(socket, target.domain)
                        return if (status != null) {
                            ProbeAttempt(
                                ManualDomainState.REACHABLE,
                                address.hostAddress,
                                status,
                                "DNS + TCP + TLS/SNI + HTTP responderam pela rede móvel"
                            )
                        } else {
                            ProbeAttempt(
                                ManualDomainState.TRANSPORT_ONLY,
                                address.hostAddress,
                                null,
                                "TCP + TLS/SNI concluíram, mas não houve resposta HTTP válida"
                            )
                        }
                    }
                }
            } catch (_: SocketTimeoutException) {
                lastState = ManualDomainState.TIMEOUT
                lastDetail = "timeout em ${address.hostAddress}:${target.port}"
            } catch (error: javax.net.ssl.SSLException) {
                lastState = ManualDomainState.TLS_ERROR
                lastDetail = error.message ?: "falha TLS/SNI"
            } catch (error: Exception) {
                lastState = ManualDomainState.ERROR
                lastDetail = error.message ?: error.javaClass.simpleName
            }
        }
        return ProbeAttempt(lastState, lastIp, null, lastDetail)
    }

    private fun requestHttp(socket: java.net.Socket, host: String): Int? {
        return runCatching {
            val request = "HEAD / HTTP/1.1\r\nHost: $host\r\nUser-Agent: AutomBot-NetworkProbe/1.2\r\nConnection: close\r\n\r\n"
            socket.outputStream.write(request.toByteArray(Charsets.US_ASCII))
            socket.outputStream.flush()
            val line = readAsciiLine(BufferedInputStream(socket.inputStream)) ?: return@runCatching null
            line.split(' ').getOrNull(1)?.toIntOrNull()
        }.getOrNull()
    }

    private fun readAsciiLine(input: BufferedInputStream): String? {
        val bytes = mutableListOf<Byte>()
        while (bytes.size < 4096) {
            val value = input.read()
            if (value < 0) break
            if (value == '\n'.code) break
            if (value != '\r'.code) bytes += value.toByte()
        }
        if (bytes.isEmpty()) return null
        return bytes.toByteArray().toString(Charsets.US_ASCII)
    }

    private fun selectCellularNetwork(): Network? {
        val active = connectivity.activeNetwork
        return connectivity.allNetworks
            .mapNotNull { network ->
                val caps = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return@mapNotNull null
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@mapNotNull null
                Triple(
                    network,
                    network == active,
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                )
            }
            .sortedWith(
                compareByDescending<Triple<Network, Boolean, Boolean>> { it.second }
                    .thenByDescending { it.third }
            )
            .firstOrNull()?.first
    }

    private companion object {
        val CONTROL_DOMAINS = listOf(
            "example.com",
            "www.wikipedia.org",
            "www.cloudflare.com"
        )
        const val TARGET_REPETITIONS = 3
        const val MAX_ADDRESSES = 4
        const val CONNECT_TIMEOUT_MS = 2500
        const val IO_TIMEOUT_MS = 3500
    }
}

class ManualDomainProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("manual_domain_probe", Context.MODE_PRIVATE)
        val profileStore = CoreProfileStore(applicationContext)
        val engine = ManualDomainProbeEngine(applicationContext)

        setContent {
            MaterialTheme {
                var input by remember { mutableStateOf(prefs.getString("domains", "").orEmpty()) }
                var running by remember { mutableStateOf(false) }
                var error by remember { mutableStateOf<String?>(null) }
                var report by remember { mutableStateOf<ManualDomainReport?>(null) }
                val scope = rememberCoroutineScope()

                Surface(modifier = Modifier.fillMaxSize(), color = ManualBackground) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        item {
                            ManualCard {
                                Text("Teste sem dados / domínios manuais", color = ManualText, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "Informe um domínio por linha, vírgula ou ponto e vírgula. O Probe testa primeiro três controles de Internet geral e depois repete cada domínio 3 vezes pela rede móvel.",
                                    color = ManualDim,
                                    fontSize = 12.sp,
                                    lineHeight = 17.sp
                                )
                            }
                        }

                        item {
                            OutlinedTextField(
                                value = input,
                                onValueChange = { input = it; error = null },
                                label = { Text("Domínios para testar") },
                                placeholder = { Text("exemplo-seu.com\noutro-seu.com:443") },
                                minLines = 6,
                                maxLines = 12,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = ManualText,
                                    unfocusedTextColor = ManualText,
                                    focusedBorderColor = ManualAccent,
                                    unfocusedBorderColor = ManualLine,
                                    focusedLabelColor = ManualAccent,
                                    unfocusedLabelColor = ManualDim,
                                    cursorColor = ManualAccent
                                )
                            )
                        }

                        item {
                            ManualCard {
                                Text("Como a classificação funciona", color = ManualText, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "Se 0/3 controles de Internet geral responderem e um domínio informado responder em 2/3 ou 3/3 tentativas, ele aparece como CANDIDATO A ACESSO PATROCINADO. Se também estiver no manifesto salvo do Core, aparece como PATROCINADO CONFIGURADO + ACESSÍVEL SEM INTERNET GERAL.",
                                    color = ManualDim,
                                    fontSize = 11.sp,
                                    lineHeight = 16.sp
                                )
                            }
                        }

                        error?.let { message -> item { Text(message, color = ManualFail, fontSize = 12.sp) } }

                        item {
                            Button(
                                onClick = {
                                    val parsed = parseManualTargets(input)
                                    if (parsed.isFailure) {
                                        error = parsed.exceptionOrNull()?.message ?: "Lista inválida."
                                    } else {
                                        val targets = parsed.getOrThrow()
                                        prefs.edit().putString("domains", input).apply()
                                        running = true
                                        report = null
                                        error = null
                                        scope.launch {
                                            report = engine.run(targets, profileStore.loadSponsoredManifest())
                                            running = false
                                        }
                                    }
                                },
                                enabled = !running,
                                modifier = Modifier.fillMaxWidth().height(54.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = ManualAccent)
                            ) {
                                if (running) {
                                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
                                    Spacer(Modifier.padding(horizontal = 6.dp))
                                    Text("Comparando controles e domínios…")
                                } else {
                                    Text("Executar teste sem dados no 4G/5G", fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }

                        report?.let { current ->
                            if (!current.cellularNetworkFound) {
                                item {
                                    ManualCard { Text("Nenhuma rede celular disponível. Mantenha os dados móveis ativos e execute novamente.", color = ManualFail) }
                                }
                            } else {
                                item { GeneralInternetCard(current) }
                                items(current.results) { item -> ManualDomainResultCard(item) }
                                item {
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Button(
                                            onClick = { ReportShare.shareText(this@ManualDomainProbeActivity, "AutomBot — teste sem dados", current.toText()) },
                                            modifier = Modifier.weight(1f),
                                            colors = ButtonDefaults.buttonColors(containerColor = ManualSurfaceAlt)
                                        ) { Text("TXT", color = ManualText) }
                                        Button(
                                            onClick = { ReportShare.share(this@ManualDomainProbeActivity, current.toJson()) },
                                            modifier = Modifier.weight(1f),
                                            colors = ButtonDefaults.buttonColors(containerColor = ManualSurfaceAlt)
                                        ) { Text("JSON", color = ManualText) }
                                    }
                                }
                            }
                        }

                        item {
                            Text(
                                "Use somente domínios que você está autorizado a testar. A classificação indica comportamento observado na rede móvel; não é confirmação de faturamento zero nem descoberta automática da lista interna da operadora.",
                                color = ManualDim,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                modifier = Modifier.padding(bottom = 18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GeneralInternetCard(report: ManualDomainReport) {
    val success = report.controls.count { it.reachable }
    val color = when (report.generalInternetState) {
        GeneralInternetState.AVAILABLE -> ManualPass
        GeneralInternetState.BLOCKED -> ManualWarn
        GeneralInternetState.INCONCLUSIVE -> ManualWarn
        GeneralInternetState.NO_CELLULAR -> ManualFail
    }
    ManualCard {
        Text("Controle de Internet geral", color = ManualText, fontWeight = FontWeight.Bold)
        Text(generalInternetLabel(report.generalInternetState), color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text("$success/${report.controls.size} controles responderam", color = ManualDim, fontSize = 11.sp)
        Spacer(Modifier.height(5.dp))
        report.controls.forEach { control ->
            Text(
                "${if (control.reachable) "OK" else "FALHA"} · ${control.domain}",
                color = if (control.reachable) ManualPass else ManualDim,
                fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun ManualDomainResultCard(result: ManualDomainResult) {
    val color = when (result.classification) {
        ManualDomainClassification.CORE_SPONSORED_WITHOUT_GENERAL_DATA,
        ManualDomainClassification.STRONG_SPONSORED_CANDIDATE -> ManualPass
        ManualDomainClassification.SPONSORED_CANDIDATE,
        ManualDomainClassification.INCONCLUSIVE -> ManualWarn
        ManualDomainClassification.REACHABLE_WITH_GENERAL_INTERNET -> ManualAccent
        ManualDomainClassification.UNREACHABLE -> ManualFail
    }
    ManualCard {
        Text("${result.target.domain}:${result.target.port}", color = ManualText, fontWeight = FontWeight.Bold)
        Text(classificationLabel(result.classification), color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text(
            "${result.successfulAttempts}/${result.totalAttempts} tentativas com resposta completa · ${stateLabel(result.state)}",
            color = ManualDim,
            fontSize = 10.sp
        )
        Text(
            if (result.coreDeclared) "Também declarado no manifesto patrocinado do Core" else "Lista manual; não declarado no Core salvo",
            color = if (result.coreDeclared) ManualAccent else ManualDim,
            fontSize = 11.sp
        )
        if (result.resolvedIps.isNotEmpty()) Text("DNS móvel: ${result.resolvedIps.joinToString()}", color = ManualDim, fontSize = 10.sp)
        result.connectedIp?.let { Text("Conectado: $it", color = ManualDim, fontSize = 10.sp) }
        result.httpStatus?.let { Text("HTTP: $it", color = ManualDim, fontSize = 10.sp) }
        Spacer(Modifier.height(5.dp))
        Text(result.detail, color = ManualDim, fontSize = 11.sp, lineHeight = 16.sp)
    }
}

private fun generalInternetLabel(state: GeneralInternetState): String = when (state) {
    GeneralInternetState.AVAILABLE -> "INTERNET GERAL DISPONÍVEL"
    GeneralInternetState.BLOCKED -> "INTERNET GERAL APARENTEMENT BLOQUEADA"
    GeneralInternetState.INCONCLUSIVE -> "CONTROLE INCONCLUSIVO"
    GeneralInternetState.NO_CELLULAR -> "SEM REDE CELULAR"
}

private fun stateLabel(state: ManualDomainState): String = when (state) {
    ManualDomainState.REACHABLE -> "ALCANÇÁVEL"
    ManualDomainState.TRANSPORT_ONLY -> "TRANSPORTE OK / HTTP INCOMPLETO"
    ManualDomainState.DNS_ERROR -> "DNS FALHOU"
    ManualDomainState.TIMEOUT -> "TIMEOUT"
    ManualDomainState.TLS_ERROR -> "TLS/SNI FALHOU"
    ManualDomainState.ERROR -> "ERRO"
}

private fun classificationLabel(classification: ManualDomainClassification): String = when (classification) {
    ManualDomainClassification.CORE_SPONSORED_WITHOUT_GENERAL_DATA -> "PATROCINADO CONFIGURADO + ACESSÍVEL SEM INTERNET GERAL"
    ManualDomainClassification.STRONG_SPONSORED_CANDIDATE -> "FORTE CANDIDATO A ACESSO PATROCINADO"
    ManualDomainClassification.SPONSORED_CANDIDATE -> "CANDIDATO A ACESSO PATROCINADO"
    ManualDomainClassification.REACHABLE_WITH_GENERAL_INTERNET -> "ALCANÇÁVEL COM INTERNET GERAL"
    ManualDomainClassification.INCONCLUSIVE -> "INCONCLUSIVO"
    ManualDomainClassification.UNREACHABLE -> "NÃO ALCANÇÁVEL"
}

private fun parseManualTargets(raw: String): Result<List<ManualDomainTarget>> = runCatching {
    val tokens = raw.split('\n', ',', ';').map { it.trim() }.filter { it.isNotBlank() }
    require(tokens.isNotEmpty()) { "Informe pelo menos um domínio." }
    require(tokens.size <= 20) { "Use no máximo 20 domínios por execução." }

    tokens.map { original ->
        val authority = original.substringAfter("://", original)
        require(!authority.contains('*') && !authority.contains('/')) { "Wildcards, paths e CIDR não são aceitos: $original" }
        val hasScheme = original.contains("://")
        val uri = URI(if (hasScheme) original else "probe://$original")
        val rawHost = uri.host?.trim().orEmpty()
        require(rawHost.isNotBlank()) { "Domínio inválido: $original" }
        val host = IDN.toASCII(rawHost).lowercase()
        require(!host.all { it.isDigit() || it == '.' } && !host.contains(':')) { "Informe nomes de domínio, não IPs: $original" }
        require(host.contains('.')) { "Domínio incompleto: $original" }
        require(host.length <= 253 && host.split('.').all { label ->
            label.length in 1..63 &&
                label.first().isLetterOrDigit() &&
                label.last().isLetterOrDigit() &&
                label.all { it.isLetterOrDigit() || it == '-' }
        }) { "Domínio inválido: $original" }

        val scheme = uri.scheme?.lowercase().orEmpty()
        val explicitPort = uri.port.takeIf { it in 1..65535 }
        val tls = when (scheme) {
            "http" -> false
            "https" -> true
            "probe" -> explicitPort != 80
            else -> error("Esquema não suportado em $original")
        }
        val port = explicitPort ?: if (tls) 443 else 80
        ManualDomainTarget(host, port, tls)
    }.distinctBy { "${it.domain}:${it.port}:${it.tls}" }
}

@Composable
private fun ManualCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ManualSurface, RoundedCornerShape(18.dp))
            .padding(16.dp),
        content = { content() }
    )
}

private val ManualBackground = Color(0xFF120E1B)
private val ManualSurface = Color(0xFF1C1628)
private val ManualSurfaceAlt = Color(0xFF292039)
private val ManualAccent = Color(0xFF8B5CF6)
private val ManualText = Color(0xFFF5F2FA)
private val ManualDim = Color(0xFFAAA1B9)
private val ManualLine = Color(0xFF3A3049)
private val ManualPass = Color(0xFF4ADE80)
private val ManualWarn = Color(0xFFFBBF24)
private val ManualFail = Color(0xFFF87171)
