package com.autombot.client.panel

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class PanelException(message: String) : Exception(message)

data class ProtocolPackage(
    val protocol: String,
    val success: Boolean,
    val error: String? = null,
    val raw: JSONObject? = null,
    val uri: String? = null,
    val wireGuardConf: String? = null
)

data class PanelConfigsResponse(
    val usuario: String,
    val servidor: String,
    val status: String,
    val expiraEm: String?,
    val protocols: Map<String, ProtocolPackage>,
    val warnings: List<String>
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

    suspend fun ping(): Boolean = try {
        executeRaw(Request.Builder().url(base).head().build())
        true
    } catch (_: Exception) { false }

    suspend fun checkApiKeyAccepted(): Boolean {
        val url = "$base/api/v1/validade.php?usuario=" + URLEncoder.encode("__ping_check__", "UTF-8")
        val response = executeRaw(Request.Builder().url(url).addHeader("X-API-Key", apiKey).get().build())
        return response.code != 401
    }

    suspend fun createTrial(deviceId: String, usuario: String, senha: String, validadeMinutos: Int = 120): TrialAccount {
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

    suspend fun fetchConfigVersion(usuario: String): String {
        val url = "$base/api/v1/configs_versao.php?usuario=" + URLEncoder.encode(usuario, "UTF-8")
        return requestJson("GET", url, null).optString("versao")
    }

    suspend fun fetchConfigs(usuario: String): PanelConfigsResponse {
        val url = "$base/api/v1/configs.php?usuario=" + URLEncoder.encode(usuario, "UTF-8")
        val json = requestJson("GET", url, null)
        val protocolosJson = json.optJSONObject("protocolos") ?: JSONObject()
        val protocols = mutableMapOf<String, ProtocolPackage>()
        protocolosJson.keys().forEach { chave ->
            val item = protocolosJson.optJSONObject(chave) ?: return@forEach
            protocols[chave] = ProtocolPackage(
                protocol = chave,
                success = item.optBoolean("sucesso", false),
                error = item.optString("erro").takeIf { it.isNotBlank() },
                raw = item,
                uri = item.optString("uri").takeIf { it.isNotBlank() },
                wireGuardConf = item.optString("config").takeIf { it.isNotBlank() }
            )
        }
        return PanelConfigsResponse(
            usuario = json.optString("usuario"),
            servidor = json.optString("servidor"),
            status = json.optString("status"),
            expiraEm = json.optString("expira_em").takeIf { it.isNotBlank() },
            protocols = protocols,
            warnings = json.optJSONArray("avisos").toStringList()
        )
    }

    /**
     * Carrega o quadro de divulgações. Primeiro tenta o endpoint do AutomBot Core;
     * a segunda rota deixa o cliente compatível com painéis PHP que espelhem o
     * mesmo JSON em /api/v1/divulgacoes.php.
     */
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

    private fun org.json.JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).map { optString(it) }
    }

    private suspend fun requestJson(metodo: String, url: String, corpo: JSONObject?): JSONObject {
        val builder = Request.Builder().url(url).addHeader("X-API-Key", apiKey)
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
                ?: json?.optString("detail")?.takeIf { it.isNotBlank() }
                ?: "O painel respondeu com erro ${response.code}"
            throw PanelException(mensagemErro)
        }
        if (json == null) throw PanelException("Resposta do painel não é um JSON válido")
        return json
    }

    private suspend fun executeRaw(request: Request): Response = suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cont.resumeWithException(PanelException("Falha de rede ao falar com o painel: ${e.message}"))
            }
            override fun onResponse(call: Call, response: Response) { cont.resume(response) }
        })
    }

    companion object {
        const val DEFAULT_API_KEY = "yXWpHdzagaa3LD4ZxlOxwjOyZUGv89a9"
    }
}
