package com.autombot.client.panel

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Estado administrativo da conta gerenciada, separado do status Online/Offline
 * da sessão SSH. O endpoint validade.php pode usar o AutomBot Core/VPS como
 * fonte de verdade sem obrigar o app a baixar novamente todos os protocolos.
 */
data class ManagedAccountStatus(
    val usuario: String,
    val status: String,
    val connectionStatus: String,
    val expiresAt: String?,
    val expired: Boolean,
    val blocked: Boolean,
    val source: String?
) {
    val active: Boolean
        get() = !expired && !blocked && status.trim().lowercase() in ACTIVE_STATUSES

    companion object {
        private val ACTIVE_STATUSES = setOf("ativo", "active", "ok")
    }
}

class ManagedAccountStatusClient(
    panelBaseUrl: String,
    private val apiKey: String = PanelWebhookClient.DEFAULT_API_KEY
) {
    private val base = panelBaseUrl.trim().trimEnd('/').let {
        if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun fetch(usuario: String): ManagedAccountStatus {
        val url = "$base/api/v1/validade.php?usuario=" + URLEncoder.encode(usuario, "UTF-8") +
            "&_cb=${System.currentTimeMillis()}"
        return requestStatus(url, usuario)
    }

    /**
     * Encerra um teste no painel usando o mesmo endpoint responsável pela criação
     * do trial. O painel deve tratar esta chamada como idempotente: repetir o GET
     * para uma conta já expirada/bloqueada não pode criar um novo teste.
     */
    suspend fun expireTrial(usuario: String, deviceId: String): ManagedAccountStatus {
        val url = buildString {
            append(base)
            append("/api/v1/teste.php?acao=expirar")
            append("&usuario=")
            append(URLEncoder.encode(usuario, "UTF-8"))
            append("&device_id=")
            append(URLEncoder.encode(deviceId, "UTF-8"))
            append("&_cb=")
            append(System.currentTimeMillis())
        }
        return requestStatus(url, usuario)
    }

    private suspend fun requestStatus(url: String, usuario: String): ManagedAccountStatus {
        val request = Request.Builder()
            .url(url)
            .addHeader("X-API-Key", apiKey)
            .header("Cache-Control", "no-cache, no-store, max-age=0")
            .header("Pragma", "no-cache")
            .get()
            .build()

        val response = executeRaw(request)
        val body = response.body?.string().orEmpty()
        val json = runCatching { JSONObject(body) }.getOrNull()

        if (!response.isSuccessful) {
            val error = json?.optString("erro")?.takeIf { it.isNotBlank() }
                ?: "O painel respondeu com erro ${response.code}"
            throw PanelException(error)
        }
        if (json == null) throw PanelException("Resposta de validade não é um JSON válido")
        return parseStatus(json, usuario)
    }

    private fun parseStatus(json: JSONObject, fallbackUsuario: String): ManagedAccountStatus {
        val rawStatus = json.optString("status").trim().lowercase()
        val blocked = json.optBoolean("bloqueado", false) || rawStatus == "bloqueado"
        val expired = json.optBoolean("expirado", rawStatus == "expirado")

        // Compatibilidade com o validade.php antigo, no qual status significava
        // somente Online/Offline e a validade real vinha no campo expirado.
        val normalizedStatus = when {
            blocked -> "bloqueado"
            expired -> "expirado"
            rawStatus in setOf("ativo", "active", "ok") -> "ativo"
            rawStatus in setOf("online", "offline") -> "ativo"
            rawStatus.isBlank() -> "ativo"
            else -> rawStatus
        }

        return ManagedAccountStatus(
            usuario = json.optString("usuario").ifBlank { fallbackUsuario },
            status = normalizedStatus,
            connectionStatus = json.optString("status_conexao").ifBlank {
                if (rawStatus in setOf("online", "offline")) rawStatus else ""
            },
            expiresAt = json.optString("expira_em").takeIf { it.isNotBlank() && it != "null" },
            expired = expired,
            blocked = blocked,
            source = json.optString("fonte").takeIf { it.isNotBlank() }
        )
    }

    private suspend fun executeRaw(request: Request): Response = suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cont.resumeWithException(PanelException("Falha de rede ao consultar validade: ${e.message}"))
            }

            override fun onResponse(call: Call, response: Response) {
                cont.resume(response)
            }
        })
    }
}
