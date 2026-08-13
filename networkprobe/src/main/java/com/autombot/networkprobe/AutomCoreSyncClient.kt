package com.autombot.networkprobe

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class AutomCoreBootstrapResult(
    val probeToken: String,
    val expiresAt: String,
    val profileJson: String,
    val sponsoredManifestJson: String? = null
)

class AutomCoreSyncClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun bootstrap(managerUrl: String, adminToken: String): AutomCoreBootstrapResult =
        withContext(Dispatchers.IO) {
            require(adminToken.isNotBlank()) { "Informe o token administrativo somente para o primeiro vínculo." }
            val base = normalizeBaseUrl(managerUrl)
            val payload = JSONObject()
                .put("nome", "AutomBot Network Probe")
                .toString()
                .toRequestBody(JSON_MEDIA_TYPE)

            val tokenRequest = Request.Builder()
                .url("$base/v1/probe/token")
                .header("Authorization", "Bearer ${adminToken.trim()}")
                .post(payload)
                .build()

            val tokenJson = executeJson(tokenRequest)
            val probeToken = tokenJson.optString("probe_token")
            if (probeToken.isBlank()) error("O servidor não retornou um token de diagnóstico.")
            val expiresAt = tokenJson.optString("expira_em")
            val profile = fetchProfileInternal(base, probeToken)
            val sponsored = fetchSponsoredManifestInternal(base)

            AutomCoreBootstrapResult(
                probeToken = probeToken,
                expiresAt = expiresAt,
                profileJson = profile,
                sponsoredManifestJson = sponsored
            )
        }

    suspend fun refresh(managerUrl: String, probeToken: String): String = withContext(Dispatchers.IO) {
        require(probeToken.isNotBlank()) { "Vínculo de diagnóstico não encontrado neste aparelho." }
        fetchProfileInternal(normalizeBaseUrl(managerUrl), probeToken)
    }

    suspend fun fetchSponsoredManifest(managerUrl: String): String? = withContext(Dispatchers.IO) {
        fetchSponsoredManifestInternal(normalizeBaseUrl(managerUrl))
    }

    private fun fetchProfileInternal(base: String, probeToken: String): String {
        val request = Request.Builder()
            .url("$base/v1/probe/profile")
            .header("X-AutomBot-Probe-Token", probeToken)
            .get()
            .build()
        return executeJson(request).toString()
    }

    private fun fetchSponsoredManifestInternal(base: String): String? {
        val request = Request.Builder()
            .url("$base/v1/dominio-patrocinado/manifesto")
            .get()
            .build()
        return runCatching { executeJson(request).toString() }.getOrNull()
    }

    private fun executeJson(request: Request): JSONObject {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching {
                    val root = JSONObject(body)
                    root.optString("detail").ifBlank { root.optString("mensagem") }
                }.getOrNull().orEmpty()
                error(
                    when {
                        detail.isNotBlank() -> "HTTP ${response.code}: $detail"
                        response.code == 404 -> "HTTP 404: esta plataforma ainda não expõe a API solicitada."
                        else -> "HTTP ${response.code}: ${response.message}"
                    }
                )
            }
            if (body.isBlank()) error("A plataforma respondeu sem conteúdo.")
            return JSONObject(body)
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun normalizeBaseUrl(value: String): String {
            val trimmed = value.trim().trimEnd('/')
            require(trimmed.isNotBlank()) { "Informe o domínio da plataforma." }
            return when {
                trimmed.startsWith("https://", ignoreCase = true) -> trimmed
                trimmed.startsWith("http://", ignoreCase = true) -> trimmed
                else -> "https://$trimmed"
            }
        }
    }
}
