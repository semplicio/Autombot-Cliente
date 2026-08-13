package com.autombot.networkprobe

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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.IDN
import java.net.InetAddress
import java.util.concurrent.TimeUnit

data class SecurityTrailsLookupReport(
    val query: String,
    val kind: String,
    val resolvedIps: List<String>,
    val reverseDomainCount: Int?,
    val reverseDomains: List<String>,
    val providerHints: List<String>,
    val domainData: JSONObject?,
    val ipWhoisData: List<JSONObject>,
    val reverseSearchData: JSONObject?,
    val statsData: JSONObject?
) {
    fun toJson(): String = JSONObject()
        .put("tool", "AutomBot SecurityTrails Lookup")
        .put("version", "1.4.0")
        .put("query", query)
        .put("kind", kind)
        .put("resolved_ips", JSONArray(resolvedIps))
        .put("reverse_domain_count", reverseDomainCount ?: JSONObject.NULL)
        .put("reverse_domains", JSONArray(reverseDomains))
        .put("provider_hints", JSONArray(providerHints))
        .put("domain_data", domainData ?: JSONObject.NULL)
        .put("ip_whois", JSONArray(ipWhoisData))
        .put("reverse_search", reverseSearchData ?: JSONObject.NULL)
        .put("stats", statsData ?: JSONObject.NULL)
        .toString(2)

    fun toText(): String = buildString {
        appendLine("AUTOMBOT — SECURITYTRAILS IP / DOMAIN LOOKUP")
        appendLine("Consulta: $query")
        appendLine("Tipo: $kind")
        if (resolvedIps.isNotEmpty()) appendLine("IPs resolvidos: ${resolvedIps.joinToString()}")
        reverseDomainCount?.let { appendLine("Domínios atuais encontrados no IP: $it") }
        if (providerHints.isNotEmpty()) appendLine("Indicadores de infraestrutura: ${providerHints.joinToString()}")
        if (reverseDomains.isNotEmpty()) {
            appendLine()
            appendLine("DOMÍNIOS ASSOCIADOS AO IP")
            reverseDomains.forEach { appendLine("- $it") }
        }
        appendLine()
        appendLine("Observação: compartilhar o mesmo IP não significa, por si só, que um domínio possa ser usado como proxy. TLS/SNI, virtual host, aplicação e regras do servidor precisam aceitar aquele hostname.")
    }.trimEnd()
}

private class SecurityTrailsClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun lookup(rawQuery: String, apiKey: String): SecurityTrailsLookupReport = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "Informe sua API key do SecurityTrails." }
        val query = rawQuery.trim()
        require(query.isNotBlank()) { "Informe um IPv4 ou domínio." }

        if (isIpv4(query)) lookupIp(query, apiKey.trim()) else lookupDomain(query, apiKey.trim())
    }

    private fun lookupIp(ip: String, apiKey: String): SecurityTrailsLookupReport {
        val filter = JSONObject().put("filter", JSONObject().put("ipv4", ip))
        val stats = runCatching { postJson("search/list/stats", apiKey, filter) }.getOrNull()
        val reverse = postJson("domains/list?include_ips=true&page=1", apiKey, filter)
        val whois = runCatching { getJson("ips/$ip/whois", apiKey) }.getOrNull()

        val domains = extractDomains(reverse).distinct().sorted()
        val total = extractLikelyTotal(stats) ?: extractLikelyTotal(reverse) ?: domains.size
        val providers = providerHints(listOfNotNull(reverse, whois))

        return SecurityTrailsLookupReport(
            query = ip,
            kind = "IPv4 / reverse IP",
            resolvedIps = listOf(ip),
            reverseDomainCount = total,
            reverseDomains = domains,
            providerHints = providers,
            domainData = null,
            ipWhoisData = listOfNotNull(whois),
            reverseSearchData = reverse,
            statsData = stats
        )
    }

    private fun lookupDomain(raw: String, apiKey: String): SecurityTrailsLookupReport {
        val domain = normalizeDomain(raw)
        val localIps = runCatching {
            InetAddress.getAllByName(domain)
                .mapNotNull { it.hostAddress }
                .distinct()
                .take(8)
        }.getOrDefault(emptyList())

        val domainData = getJson("domain/$domain", apiKey)
        val ipv4s = LinkedHashSet<String>()
        localIps.filter { isIpv4(it) }.forEach { ipv4s += it }
        extractIpv4(domainData).forEach { ipv4s += it }

        val whois = ipv4s.take(3).mapNotNull { ip ->
            runCatching { getJson("ips/$ip/whois", apiKey) }.getOrNull()
        }
        val providers = providerHints(listOf(domainData) + whois)

        return SecurityTrailsLookupReport(
            query = domain,
            kind = "Domínio / DNS atual",
            resolvedIps = (localIps + ipv4s).distinct(),
            reverseDomainCount = extractSharedIpCount(domainData),
            reverseDomains = emptyList(),
            providerHints = providers,
            domainData = domainData,
            ipWhoisData = whois,
            reverseSearchData = null,
            statsData = null
        )
    }

    private fun getJson(path: String, apiKey: String): JSONObject {
        val request = Request.Builder()
            .url("$BASE_URL$path")
            .header("APIKEY", apiKey)
            .header("Accept", "application/json")
            .get()
            .build()
        return executeJson(request)
    }

    private fun postJson(path: String, apiKey: String, payload: JSONObject): JSONObject {
        val request = Request.Builder()
            .url("$BASE_URL$path")
            .header("APIKEY", apiKey)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return executeJson(request)
    }

    private fun executeJson(request: Request): JSONObject {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching {
                    val root = JSONObject(body)
                    root.optString("message").ifBlank { root.optString("detail") }
                }.getOrNull().orEmpty()
                error(if (detail.isNotBlank()) "SecurityTrails HTTP ${response.code}: $detail" else "SecurityTrails HTTP ${response.code}: ${response.message}")
            }
            if (body.isBlank()) error("SecurityTrails respondeu sem conteúdo.")
            return JSONObject(body)
        }
    }

    private fun normalizeDomain(value: String): String {
        val raw = value.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')
            .substringBefore(':')
            .trimEnd('.')
        val ascii = IDN.toASCII(raw).lowercase()
        require(ascii.contains('.') && ascii.length <= 253) { "Domínio inválido: $value" }
        return ascii
    }

    private fun isIpv4(value: String): Boolean {
        val parts = value.split('.')
        return parts.size == 4 && parts.all { part ->
            part.isNotEmpty() && part.length <= 3 && part.all(Char::isDigit) && (part.toIntOrNull() ?: 999) in 0..255
        }
    }

    private fun extractDomains(root: JSONObject): List<String> {
        val output = LinkedHashSet<String>()
        fun visit(value: Any?, keyHint: String? = null) {
            when (value) {
                is JSONObject -> value.keys().forEach { key -> visit(value.opt(key), key) }
                is JSONArray -> for (i in 0 until value.length()) visit(value.opt(i), keyHint)
                is String -> {
                    val key = keyHint.orEmpty().lowercase()
                    if (key in setOf("hostname", "domain", "apex_domain", "apex") && looksLikeDomain(value)) {
                        output += value.lowercase().trimEnd('.')
                    }
                }
            }
        }
        visit(root)
        return output.toList()
    }

    private fun extractIpv4(root: JSONObject): List<String> {
        val output = LinkedHashSet<String>()
        fun visit(value: Any?) {
            when (value) {
                is JSONObject -> value.keys().forEach { visit(value.opt(it)) }
                is JSONArray -> for (i in 0 until value.length()) visit(value.opt(i))
                is String -> if (isIpv4(value)) output += value
            }
        }
        visit(root)
        return output.toList()
    }

    private fun extractLikelyTotal(root: JSONObject?): Int? {
        if (root == null) return null
        val preferred = listOf("total", "count", "total_count", "records_count")
        for (key in preferred) {
            if (root.has(key)) root.optInt(key, -1).takeIf { it >= 0 }?.let { return it }
        }
        val meta = root.optJSONObject("meta")
        if (meta != null) return extractLikelyTotal(meta)
        return null
    }

    private fun extractSharedIpCount(root: JSONObject): Int? {
        val textKeys = listOf("count", "domain_count", "domains", "same_ip", "records_count")
        fun visit(obj: JSONObject): Int? {
            obj.keys().forEach { key ->
                val value = obj.opt(key)
                if (value is Number && textKeys.any { key.contains(it, ignoreCase = true) }) {
                    val number = value.toInt()
                    if (number >= 0) return number
                }
                if (value is JSONObject) visit(value)?.let { return it }
            }
            return null
        }
        return visit(root)
    }

    private fun providerHints(documents: List<JSONObject>): List<String> {
        val text = documents.joinToString("\n") { it.toString().lowercase() }
        val hints = LinkedHashSet<String>()
        val providers = listOf(
            "cloudflare" to "Cloudflare",
            "akamai" to "Akamai",
            "fastly" to "Fastly",
            "cloudfront" to "Amazon CloudFront",
            "amazon" to "Amazon/AWS",
            "google" to "Google",
            "azure" to "Microsoft Azure",
            "microsoft" to "Microsoft",
            "imperva" to "Imperva",
            "incapsula" to "Imperva/Incapsula",
            "stackpath" to "StackPath"
        )
        providers.forEach { (needle, label) -> if (needle in text) hints += label }
        return hints.toList()
    }

    private fun looksLikeDomain(value: String): Boolean {
        val text = value.trim().trimEnd('.')
        if (text.length !in 3..253 || !text.contains('.')) return false
        if (isIpv4(text)) return false
        return text.split('.').all { label ->
            label.isNotBlank() && label.length <= 63 && label.all { it.isLetterOrDigit() || it == '-' }
        }
    }

    private companion object {
        const val BASE_URL = "https://api.securitytrails.com/v1/"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

class SecurityTrailsLookupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val client = SecurityTrailsClient()
        setContent {
            MaterialTheme {
                var showSniTool by remember { mutableStateOf(false) }
                if (showSniTool) {
                    SniToolScreen(
                        onBack = { showSniTool = false },
                        onShareText = { text -> ReportShare.shareText(this, "AutomBot — teste SNI", text) },
                        onShareJson = { json -> ReportShare.share(this, json) }
                    )
                } else {
                    SecurityTrailsScreen(
                        client = client,
                        onOpenSniTool = { showSniTool = true },
                        onShareText = { report -> ReportShare.shareText(this, "AutomBot — SecurityTrails Lookup", report.toText()) },
                        onShareJson = { report -> ReportShare.share(this, report.toJson()) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SecurityTrailsScreen(
    client: SecurityTrailsClient,
    onOpenSniTool: () -> Unit,
    onShareText: (SecurityTrailsLookupReport) -> Unit,
    onShareJson: (SecurityTrailsLookupReport) -> Unit
) {
    var apiKey by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var report by remember { mutableStateOf<SecurityTrailsLookupReport?>(null) }
    val scope = rememberCoroutineScope()

    Surface(modifier = Modifier.fillMaxSize(), color = LookupBackground) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                LookupCard {
                    Text("IP / Domain Intelligence", color = LookupText, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Consulta o SecurityTrails para relacionar domínio ↔ IP, contar/mostrar domínios atualmente associados a um IPv4 e exibir dados de DNS/WHOIS e indicadores de CDN/provedor.",
                        color = LookupDim,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                }
            }

            item {
                LookupCard {
                    LookupField(
                        value = query,
                        onValueChange = { query = it; error = null },
                        label = "IPv4 ou domínio"
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it; error = null },
                        label = { Text("SecurityTrails API key") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(13.dp),
                        colors = lookupFieldColors()
                    )
                    Spacer(Modifier.height(7.dp))
                    Text("A chave é usada somente nesta tela e não é salva no aparelho nem incluída nos relatórios.", color = LookupDim, fontSize = 10.sp)
                }
            }

            error?.let { message -> item { Text(message, color = LookupFail, fontSize = 12.sp) } }

            item {
                Button(
                    onClick = {
                        running = true
                        report = null
                        error = null
                        scope.launch {
                            runCatching { client.lookup(query, apiKey) }
                                .onSuccess { report = it }
                                .onFailure { error = it.message ?: it.javaClass.simpleName }
                            running = false
                        }
                    },
                    enabled = !running,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = LookupAccent)
                ) {
                    if (running) {
                        CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
                        Spacer(Modifier.padding(horizontal = 6.dp))
                        Text("Consultando…")
                    } else {
                        Text("Consultar IP / domínio", fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            item {
                Button(
                    onClick = onOpenSniTool,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = LookupSurfaceAlt)
                ) {
                    Text("Testar SNI manual (IP + hostname)", color = LookupText, fontWeight = FontWeight.SemiBold)
                }
            }

            report?.let { current ->
                item {
                    LookupCard {
                        Text(current.query, color = LookupText, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text(current.kind, color = LookupAccent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        if (current.resolvedIps.isNotEmpty()) Text("IPs: ${current.resolvedIps.joinToString()}", color = LookupDim, fontSize = 11.sp)
                        current.reverseDomainCount?.let { Text("Domínios associados: $it", color = LookupText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                        if (current.providerHints.isNotEmpty()) Text("Infra/CDN: ${current.providerHints.joinToString()}", color = LookupWarn, fontSize = 11.sp)
                    }
                }

                if (current.reverseDomains.isNotEmpty()) {
                    item { Text("Domínios encontrados nesta página", color = LookupText, fontWeight = FontWeight.SemiBold) }
                    items(current.reverseDomains.take(250)) { domain ->
                        LookupCard { Text(domain, color = LookupText, fontSize = 12.sp) }
                    }
                }

                current.domainData?.let { data ->
                    item {
                        LookupCard {
                            Text("Dados atuais do domínio", color = LookupAccent, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(6.dp))
                            Text(data.toString(2).take(16000), color = LookupDim, fontSize = 10.sp, lineHeight = 15.sp)
                        }
                    }
                }

                current.ipWhoisData.forEachIndexed { index, data ->
                    item {
                        LookupCard {
                            Text("IP WHOIS ${index + 1}", color = LookupAccent, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(6.dp))
                            Text(data.toString(2).take(12000), color = LookupDim, fontSize = 10.sp, lineHeight = 15.sp)
                        }
                    }
                }

                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { onShareText(current) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = LookupSurfaceAlt)
                        ) { Text("TXT", color = LookupText) }
                        Button(
                            onClick = { onShareJson(current) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = LookupSurfaceAlt)
                        ) { Text("JSON", color = LookupText) }
                    }
                }
            }

            item {
                Text(
                    "Importante: vários domínios no mesmo IP indicam compartilhamento de infraestrutura. Isso não garante que todos funcionem como proxy/âncora; o hostname precisa ser aceito pelo TLS/SNI, virtual host e aplicação do servidor.",
                    color = LookupDim,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(bottom = 18.dp)
                )
            }
        }
    }
}

@Composable
private fun LookupField(value: String, onValueChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(13.dp),
        colors = lookupFieldColors()
    )
}

@Composable
private fun lookupFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = LookupText,
    unfocusedTextColor = LookupText,
    focusedBorderColor = LookupAccent,
    unfocusedBorderColor = LookupLine,
    focusedLabelColor = LookupAccent,
    unfocusedLabelColor = LookupDim,
    cursorColor = LookupAccent
)

@Composable
private fun LookupCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(LookupSurface, RoundedCornerShape(18.dp))
            .padding(16.dp),
        content = { content() }
    )
}

private val LookupBackground = Color(0xFF120E1B)
private val LookupSurface = Color(0xFF1C1628)
private val LookupSurfaceAlt = Color(0xFF292039)
private val LookupAccent = Color(0xFF8B5CF6)
private val LookupText = Color(0xFFF5F2FA)
private val LookupDim = Color(0xFFAAA1B9)
private val LookupLine = Color(0xFF3A3049)
private val LookupWarn = Color(0xFFFBBF24)
private val LookupFail = Color(0xFFF87171)
