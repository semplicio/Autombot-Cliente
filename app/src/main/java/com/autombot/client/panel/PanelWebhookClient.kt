package com.autombot.client.panel

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Cliente HTTP pro painel AutomBot Core — fluxo de modo gerenciado. */
class PanelException(message: String) : Exception(message)

/**
 * Uma rota alternativa da mesma credencial/protocolo.
 *
 * Exemplo Xray v2:
 * - direct-http80: core.example:80, WS sem TLS
 * - cdn-443: distribuição CDN:443, WS+TLS
 * - origin-tls: domínio da origem:8081, WS+TLS
 */
data class ProtocolRoute(
    val id: String,
    val label: String,
    val uri: String?,
    val host: String?,
    val port: Int?,
    val transport: String?,
    val tls: Boolean,
    val path: String?,
    val role: String?,
    val validUntil: String?,
    val priority: Int,
    val recommended: Boolean
)

data class SponsoredEndpoint(
    val domain: String,
    val tcpPort: Int,
    val udpPort: Int,
    val bootstrapIps: List<String>,
    val validUntil: String? = null
)

data class SponsoredEndpointManifest(
    val schemaVersion: Int,
    val revision: String,
    val enabled: Boolean,
    val active: SponsoredEndpoint?,
    val previous: List<SponsoredEndpoint>,
    val xrayEnabled: Boolean,
    val updatedAt: String?
) {
    fun allEndpoints(): List<SponsoredEndpoint> = listOfNotNull(active) + previous

    fun endpointForDomain(domain: String?): SponsoredEndpoint? {
        if (domain.isNullOrBlank()) return null
        return allEndpoints().firstOrNull { it.domain.equals(domain, ignoreCase = true) }
    }
}

internal fun JSONObject.toSponsoredEndpointManifest(): SponsoredEndpointManifest? {
    fun JSONArray?.strings(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).map { optString(it) }
    }

    fun JSONObject?.toEndpoint(): SponsoredEndpoint? {
        if (this == null) return null
        val domain = optString("domain").ifBlank { optString("dominio") }
        if (domain.isBlank()) return null
        return SponsoredEndpoint(
            domain = domain,
            tcpPort = optInt("tcp_port", optInt("porta_tcp", 443)).takeIf { it in 1..65535 } ?: 443,
            udpPort = optInt("udp_port", optInt("porta_udp", 443)).takeIf { it in 1..65535 } ?: 443,
            bootstrapIps = optJSONArray("bootstrap_ips").strings(),
            validUntil = optString("valid_until").takeIf { it.isNotBlank() }
        )
    }

    val active = optJSONObject("active").toEndpoint()
    val previousJson = optJSONArray("previous")
    val previous = if (previousJson == null) emptyList() else {
        (0 until previousJson.length()).mapNotNull { previousJson.optJSONObject(it).toEndpoint() }
    }
    val services = optJSONObject("services")
    return SponsoredEndpointManifest(
        schemaVersion = optInt("schema_version", 1),
        revision = optString("revision"),
        enabled = optBoolean("enabled", active != null),
        active = active,
        previous = previous,
        xrayEnabled = services?.optBoolean("xray", false) ?: false,
        updatedAt = optString("updated_at").takeIf { it.isNotBlank() }
    )
}

internal fun SponsoredEndpointManifest.toJson(): JSONObject = JSONObject().apply {
    fun endpointJson(endpoint: SponsoredEndpoint): JSONObject = JSONObject().apply {
        put("domain", endpoint.domain)
        put("tcp_port", endpoint.tcpPort)
        put("udp_port", endpoint.udpPort)
        put("bootstrap_ips", JSONArray(endpoint.bootstrapIps))
        endpoint.validUntil?.let { put("valid_until", it) }
    }

    put("schema_version", this@toJson.schemaVersion)
    put("revision", this@toJson.revision)
    put("enabled", this@toJson.enabled)
    put("active", this@toJson.active?.let(::endpointJson) ?: JSONObject.NULL)
    put("previous", JSONArray().apply {
        this@toJson.previous.forEach { put(endpointJson(it)) }
    })
    put("services", JSONObject().put("xray", this@toJson.xrayEnabled))
    this@toJson.updatedAt?.let { put("updated_at", it) }
}

data class ProtocolPackage(
    val protocol: String,
    val success: Boolean,
    val error: String? = null,
    /** Item cru inteiro devolvido pelo painel pra esse protocolo. */
    val raw: JSONObject? = null,
    /** URI legada. Continua sendo aceita quando o painel ainda não repassa rotas. */
    val uri: String? = null,
    /** Atalho: presente só pro WireGuard (.conf cru). */
    val wireGuardConf: String? = null,
    val configVersion: Int = 1,
    val preferredRoute: String? = null,
    val routes: List<ProtocolRoute> = emptyList(),
    val sponsoredEndpoint: SponsoredEndpointManifest? = null
) {
    /**
     * Escolhe a URI indicada pelo Core, sem codificar operadora/domínio no APK.
     * Se o painel ainda estiver no contrato antigo, cai na URI legada.
     */
    fun effectiveUri(): String? {
        return orderedRoutes().firstOrNull()?.uri ?: uri
    }

    /**
     * Ordem real de tentativa enviada pelo Core. A rota explicitamente preferida
     * vem primeiro; depois entram os fallbacks por prioridade (inclusive o domínio
     * patrocinado anterior durante a janela de transição).
     */
    fun orderedRoutes(): List<ProtocolRoute> {
        val utilizaveis = routes.filter { !it.uri.isNullOrBlank() }
        val preferida = preferredRoute?.let { wanted -> utilizaveis.firstOrNull { it.id == wanted } }
        return (listOfNotNull(preferida) + utilizaveis.sortedWith(
            compareBy<ProtocolRoute> { it.priority }.thenByDescending { it.recommended }
        )).distinctBy { it.id to it.uri }
    }

    fun selectedRoute(): ProtocolRoute? {
        val effective = effectiveUri() ?: return null
        return routes.firstOrNull { it.uri == effective }
    }
}

data class PanelConfigsResponse(
    val usuario: String,
    val servidor: String,
    val status: String,
    val expiraEm: String?,
    val protocols: Map<String, ProtocolPackage>,
    val warnings: List<String>,
    val sponsoredEndpoint: SponsoredEndpointManifest? = null
)

data class TrialAccount(
    val usuario: String,
    val senha: String,
    val expiraEm: String,
    val limiteConexoes: Int,
    val servidor: String?,
    val protocolosConfigurados: List<String>,
    val warnings: List<String>
)

data class PanelPromotion(
    val id: String,
    val title: String,
    val description: String,
    val mediaType: String,
    val mediaUrl: String,
    val linkUrl: String?,
    val order: Int
)

class PanelWebhookClient(
    private val panelBaseUrl: String,
    private val apiKey: String = DEFAULT_API_KEY
) {
    private val base = panelBaseUrl.trim().trimEnd('/').let {
        if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun ping(): Boolean {
        return try {
            val request = Request.Builder().url(base).head().build()
            executeRaw(request)
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun checkApiKeyAccepted(): Boolean {
        val url = "$base/api/v1/validade.php?usuario=" + URLEncoder.encode("__ping_check__", "UTF-8")
        val request = Request.Builder().url(url).addHeader("X-API-Key", apiKey).get().build()
        val response = executeRaw(request)
        return response.code != 401
    }

    suspend fun createTrial(
        deviceId: String,
        usuario: String,
        senha: String,
        validadeMinutos: Int = 120
    ): TrialAccount {
        val corpo = JSONObject().apply {
            put("device_id", deviceId)
            put("usuario", usuario)
            put("senha", senha)
            put("validade_minutos", validadeMinutos)
        }
        val json = requestJson("POST", "$base/api/v1/teste.php", corpo)
        return TrialAccount(
            usuario = json.optString("usuario"),
            senha = json.optString("senha"),
            expiraEm = json.optString("expira_em"),
            limiteConexoes = json.optInt("limite_conexoes", 1),
            servidor = json.optString("servidor").takeIf { it.isNotBlank() },
            protocolosConfigurados = json.optJSONArray("protocolos_configurados").toStringList(),
            warnings = json.optJSONArray("avisos").toStringList()
        )
    }

    /**
     * Retorna uma revisão derivada do CONTEÚDO completo de ``configs.php``.
     *
     * Antes o app dependia somente de ``configs_versao.php``. Se aquele endpoint
     * fosse atualizado apenas por uma tela específica do painel (por exemplo SSH),
     * mudanças de porta/path/host em VMess, VLESS, Shadowsocks, WireGuard, OpenVPN,
     * Hysteria2, TUIC etc. poderiam passar despercebidas e o cliente continuaria
     * usando uma configuração antiga até resetar o aplicativo.
     *
     * Agora a revisão é um SHA-256 estável do objeto ``protocolos`` mais o servidor.
     * Assim qualquer mudança efetiva entregue ao app altera a revisão, sem exigir
     * recriação de usuário e sem depender de cada módulo lembrar de incrementar um
     * contador no painel.
     */
    suspend fun fetchConfigVersion(usuario: String): String {
        val url = "$base/api/v1/configs.php?usuario=" + URLEncoder.encode(usuario, "UTF-8") + "&_cb=${System.currentTimeMillis()}"
        val json = requestJson("GET", url, null)
        val revisionPayload = JSONObject().apply {
            put("servidor", json.optString("servidor"))
            put("protocolos", json.optJSONObject("protocolos") ?: JSONObject())
            put(
                "dominio_patrocinado",
                json.optJSONObject("dominio_patrocinado")
                    ?: json.optJSONObject("sponsored_endpoint")
                    ?: JSONObject()
            )
        }
        val canonical = canonicalJson(revisionPayload)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "cfg-${digest.take(24)}"
    }

    /**
     * Busca as configs prontas. Aceita tanto o contrato legado (URI única)
     * quanto o contrato route-aware do Core. Também aceita ``pacote`` como
     * objeto aninhado caso o painel opte por repassar a resposta do Core sem
     * achatar todos os campos.
     */
    suspend fun fetchConfigs(usuario: String): PanelConfigsResponse {
        val url = "$base/api/v1/configs.php?usuario=" + URLEncoder.encode(usuario, "UTF-8") + "&_cb=${System.currentTimeMillis()}"
        val json = requestJson("GET", url, null)

        val protocolosJson = json.optJSONObject("protocolos") ?: JSONObject()
        val protocols = mutableMapOf<String, ProtocolPackage>()
        var sponsoredEndpoint = (
            json.optJSONObject("dominio_patrocinado")
                ?: json.optJSONObject("sponsored_endpoint")
            )?.toSponsoredEndpointManifest()
        protocolosJson.keys().forEach { chave ->
            val item = protocolosJson.optJSONObject(chave) ?: return@forEach
            val pacote = item.optJSONObject("pacote") ?: item
            val sucesso = item.optBoolean("sucesso", pacote.optBoolean("sucesso", false))
            val rotasJson = pacote.optJSONArray("routes") ?: pacote.optJSONArray("rotas")
            val routes = rotasJson.toProtocolRoutes()
            val preferredRoute = pacote.optString("preferred_route")
                .ifBlank { pacote.optString("rota_preferida") }
                .takeIf { it.isNotBlank() }
            val sponsored = (
                pacote.optJSONObject("sponsored_endpoint")
                    ?: item.optJSONObject("sponsored_endpoint")
                )?.toSponsoredEndpointManifest()
            if (sponsoredEndpoint == null && sponsored != null) sponsoredEndpoint = sponsored
            val packageSponsoredEndpoint = sponsored ?: sponsoredEndpoint

            protocols[chave] = ProtocolPackage(
                protocol = chave,
                success = sucesso,
                error = item.optString("erro").takeIf { it.isNotBlank() },
                raw = item,
                uri = pacote.optString("uri").takeIf { it.isNotBlank() },
                wireGuardConf = pacote.optString("config").takeIf { it.isNotBlank() },
                configVersion = pacote.optInt("config_version", pacote.optInt("versao_config", 1)),
                preferredRoute = preferredRoute,
                routes = routes,
                sponsoredEndpoint = packageSponsoredEndpoint
            )
        }

        return PanelConfigsResponse(
            usuario = json.optString("usuario"),
            servidor = json.optString("servidor"),
            status = json.optString("status"),
            expiraEm = json.optString("expira_em").takeIf { it.isNotBlank() },
            protocols = protocols,
            warnings = json.optJSONArray("avisos").toStringList(),
            sponsoredEndpoint = sponsoredEndpoint
        )
    }

    suspend fun fetchPromotions(): List<PanelPromotion> {
        var lastError: Exception? = null
        val endpoints = listOf("$base/v1/divulgacoes/publicas", "$base/api/v1/divulgacoes.php")
        for (url in endpoints) {
            try {
                val json = requestJson("GET", url, null)
                val array = json.optJSONArray("divulgacoes") ?: continue
                return (0 until array.length()).mapNotNull { index ->
                    val item = array.optJSONObject(index) ?: return@mapNotNull null
                    val mediaUrl = item.optString("midia_url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    PanelPromotion(
                        id = item.optString("id", index.toString()),
                        title = item.optString("titulo", "Divulgação"),
                        description = item.optString("descricao"),
                        mediaType = item.optString("tipo_midia", "imagem").lowercase(),
                        mediaUrl = mediaUrl,
                        linkUrl = item.optString("link_url").takeIf { it.isNotBlank() },
                        order = item.optInt("ordem", 0)
                    )
                }.sortedByDescending { it.order }
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw PanelException(lastError?.message ?: "O painel não possui o feed de divulgações")
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).map { optString(it) }
    }

    private fun JSONArray?.toProtocolRoutes(): List<ProtocolRoute> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { index ->
            val route = optJSONObject(index) ?: return@mapNotNull null
            val id = route.optString("id").ifBlank { "route-$index" }
            val uri = route.optString("uri").takeIf { it.isNotBlank() }
            val rawPort = route.optInt("port", route.optInt("porta", -1))
            ProtocolRoute(
                id = id,
                label = route.optString("label").ifBlank { route.optString("nome") }.ifBlank { id },
                uri = uri,
                host = route.optString("host").takeIf { it.isNotBlank() },
                port = rawPort.takeIf { it in 1..65535 },
                transport = route.optString("transport").ifBlank { route.optString("transporte") }.takeIf { it.isNotBlank() },
                tls = route.optBoolean("tls", false),
                path = route.optString("path").takeIf { it.isNotBlank() && it != "null" },
                role = route.optString("role").ifBlank { route.optString("papel") }.takeIf { it.isNotBlank() },
                validUntil = route.optString("valid_until").takeIf { it.isNotBlank() },
                priority = route.optInt("priority", route.optInt("prioridade", (index + 1) * 10)),
                recommended = route.optBoolean("recommended", route.optBoolean("recomendada", false))
            )
        }
    }

    /** Canonicaliza JSON com chaves ordenadas para o hash não depender da ordem. */
    private fun canonicalJson(value: Any?): String = when {
        value == null || value == JSONObject.NULL -> "null"
        value is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(
            prefix = "{",
            postfix = "}",
            separator = ","
        ) { key -> "${JSONObject.quote(key)}:${canonicalJson(value.opt(key))}" }
        value is JSONArray -> (0 until value.length()).joinToString(
            prefix = "[",
            postfix = "]",
            separator = ","
        ) { index -> canonicalJson(value.opt(index)) }
        value is String -> JSONObject.quote(value)
        value is Number || value is Boolean -> value.toString()
        else -> JSONObject.quote(value.toString())
    }

    private suspend fun requestJson(metodo: String, url: String, corpo: JSONObject?): JSONObject {
        val builder = Request.Builder()
            .url(url)
            .addHeader("X-API-Key", apiKey)
            .header("Cache-Control", "no-cache, no-store, max-age=0")
            .header("Pragma", "no-cache")
        when (metodo) {
            "GET" -> builder.get()
            "POST" -> builder.post((corpo ?: JSONObject()).toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            else -> throw IllegalArgumentException("Método não suportado: $metodo")
        }

        val response = executeRaw(builder.build())
        val corpoResposta = response.body?.string().orEmpty()
        val json = runCatching { JSONObject(corpoResposta) }.getOrNull()

        if (!response.isSuccessful) {
            val mensagemErro = json?.optString("erro")?.takeIf { it.isNotBlank() }
                ?: "O painel respondeu com erro ${response.code}"
            throw PanelException(mensagemErro)
        }
        if (json == null) {
            throw PanelException("Resposta do painel não é um JSON válido")
        }
        return json
    }

    private suspend fun executeRaw(request: Request): Response = suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cont.resumeWithException(PanelException("Falha de rede ao falar com o painel: ${e.message}"))
            }

            override fun onResponse(call: Call, response: Response) {
                cont.resume(response)
            }
        })
    }

    companion object {
        const val DEFAULT_API_KEY = "yXWpHdzagaa3LD4ZxlOxwjOyZUGv89a9"
    }
}
