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

/**
 * Cliente HTTP pro painel AutomBot Core — fluxo "Já tenho um domínio"
 * (modo gerenciado, ver SPEC.md secao 3/4/7).
 *
 * Corresponde aos endpoints novos do painel (api/v1/teste.php e
 * api/v1/configs.php — ver arquivos entregues do lado do painel):
 *  1. createTrial(): cria a conta de teste de verdade na VPS (via automcore)
 *  2. fetchConfigs(): busca de volta a config PRONTA de cada protocolo
 *     (vmess://, vless://, trojan://, ss://, .conf do WireGuard, dados de
 *     acesso SSH) — o app so precisa importar cada uma no manager certo.
 *
 * CORRECAO: a versao anterior desse arquivo era só TODO() — nunca tinha
 * implementação nenhuma, então a tela "Já tenho um domínio" nunca fazia
 * chamada real nenhuma (só uma animação de progresso fake, ver
 * ProgressStepsScreen.kt).
 */
class PanelException(message: String) : Exception(message)

data class ProtocolPackage(
    val protocol: String,
    val success: Boolean,
    val error: String? = null,
    /** Item cru inteiro devolvido pelo painel pra esse protocolo — usar pra
     *  protocolos sem formato padronizado ainda (ex: ssh, ver ressalva no
     *  configs.php do painel). */
    val raw: JSONObject? = null,
    /** Atalho: presente quando o protocolo é baseado em URI (vmess/vless/trojan/ss). */
    val uri: String? = null,
    /** Atalho: presente só pro WireGuard (.conf cru). */
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

    /** Checagem simples de que o domínio responde — não exige API key nem conta. */
    suspend fun ping(): Boolean {
        return try {
            val request = Request.Builder().url(base).head().build()
            executeRaw(request)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Confirma que a API key do app é aceita por esse painel, sem precisar de uma
     * conta existente pra testar. Não existe (ainda) um endpoint dedicado só pra
     * isso — reaproveita validade.php com um usuário que não deve existir de
     * verdade: um 401 aqui é SEMPRE "key errada/ausente" (checado antes de olhar
     * o usuário, ver api/bootstrap.php::exigirApiKey()); qualquer outra resposta
     * (404 "conta não encontrada" incluso) já confirma que a key foi aceita.
     */
    suspend fun checkApiKeyAccepted(): Boolean {
        val url = "$base/api/v1/validade.php?usuario=" + URLEncoder.encode("__ping_check__", "UTF-8")
        val request = Request.Builder().url(url).addHeader("X-API-Key", apiKey).get().build()
        val response = executeRaw(request)
        return response.code != 401
    }

    /** POST /api/v1/teste.php — cria a conta de teste de verdade. */
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

    /** GET /api/v1/configs.php?usuario=X — busca as configs prontas de cada protocolo. */
    suspend fun fetchConfigs(usuario: String): PanelConfigsResponse {
        val url = "$base/api/v1/configs.php?usuario=" + URLEncoder.encode(usuario, "UTF-8")
        val json = requestJson("GET", url, null)

        val protocolosJson = json.optJSONObject("protocolos") ?: JSONObject()
        val protocols = mutableMapOf<String, ProtocolPackage>()
        protocolosJson.keys().forEach { chave ->
            val item = protocolosJson.optJSONObject(chave) ?: return@forEach
            val sucesso = item.optBoolean("sucesso", false)
            protocols[chave] = ProtocolPackage(
                protocol = chave,
                success = sucesso,
                error = item.optString("erro").takeIf { it.isNotBlank() },
                raw = item,
                uri = item.optString("uri").takeIf { it.isNotBlank() },
                // CORRECAO: assumi "conf" antes de ter uma resposta real do
                // automcore pra conferir — o formato de verdade usa "config".
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
        // CORRECAO: ficava só um placeholder de texto aqui — eu pedia pro
        // usuário trocar manualmente, mas nunca coloquei o valor real,
        // e cada entrega nova desse arquivo arriscava sobrescrever uma
        // edição manual feita no Android Studio. Chave real do painel
        // (vpn.infinitenet.net) direto aqui agora.
        const val DEFAULT_API_KEY = "yXWpHdzagaa3LD4ZxlOxwjOyZUGv89a9"
    }
}
