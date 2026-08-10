package com.autombot.client.protocols.trojan

import java.net.URI
import java.net.URLDecoder

/**
 * Config de uma conexão Trojan. Trojan usa TLS e pode ser transportado diretamente
 * sobre TCP ou encapsulado em WebSocket, como nos links gerados pelo AutomBot Core.
 *
 * Exemplos:
 *   trojan://senha@host:443?sni=host#nome
 *   trojan://senha@host:443?type=ws&path=/trojan&host=host&sni=host#nome
 */
data class TrojanConnectionConfig(
    val connectionName: String,
    val password: String,
    val server: String,
    val port: Int,
    val sni: String = "",
    val allowInsecure: Boolean = false,
    val transportType: String = "tcp",
    val wsPath: String = "/",
    val wsHost: String = ""
)

class TrojanUriParseException(message: String) : Exception(message)

fun parseTrojanUri(raw: String): TrojanConnectionConfig {
    val trimmed = raw.trim()
    if (!trimmed.startsWith("trojan://", ignoreCase = true)) {
        throw TrojanUriParseException("Link precisa começar com trojan://")
    }
    val uri = try {
        URI(trimmed)
    } catch (e: Exception) {
        throw TrojanUriParseException("Link trojan:// inválido: ${e.message}")
    }

    val password = uri.userInfo ?: throw TrojanUriParseException("Link sem senha (antes do @)")
    val server = uri.host ?: throw TrojanUriParseException("Link sem servidor")
    val port = if (uri.port != -1) uri.port else 443

    val params = (uri.rawQuery ?: "").split("&")
        .filter { it.isNotBlank() }
        .associate { pair ->
            val idx = pair.indexOf('=')
            val rawKey = if (idx == -1) pair else pair.substring(0, idx)
            val rawValue = if (idx == -1) "" else pair.substring(idx + 1)
            val key = URLDecoder.decode(rawKey, "UTF-8").lowercase()
            val value = URLDecoder.decode(rawValue, "UTF-8")
            key to value
        }

    val name = uri.fragment?.takeIf { it.isNotBlank() } ?: "Conexão Trojan"
    val transportType = params["type"]?.trim()?.lowercase().orEmpty().ifBlank { "tcp" }
    val wsPath = params["path"]?.takeIf { it.isNotBlank() } ?: "/"
    val wsHost = params["host"]?.trim().orEmpty()
    val insecureRaw = params["allowinsecure"]
        ?: params["allow_insecure"]
        ?: params["insecure"]

    return TrojanConnectionConfig(
        connectionName = name,
        password = password,
        server = server,
        port = port,
        sni = params["sni"]?.takeIf { it.isNotBlank() } ?: server,
        allowInsecure = insecureRaw == "1" || insecureRaw.equals("true", ignoreCase = true),
        transportType = transportType,
        wsPath = wsPath,
        wsHost = wsHost
    )
}

fun TrojanConnectionConfig.describeTransport(): String =
    if (transportType.equals("ws", ignoreCase = true)) "WebSocket + TLS" else "TCP + TLS"

fun TrojanConnectionConfig.toJson(): org.json.JSONObject = org.json.JSONObject().apply {
    put("connectionName", connectionName)
    put("password", password)
    put("server", server)
    put("port", port)
    put("sni", sni)
    put("allowInsecure", allowInsecure)
    put("transportType", transportType)
    put("wsPath", wsPath)
    put("wsHost", wsHost)
}

fun trojanConnectionConfigFromJson(json: org.json.JSONObject): TrojanConnectionConfig = TrojanConnectionConfig(
    connectionName = json.optString("connectionName"),
    password = json.optString("password"),
    server = json.optString("server"),
    port = json.optInt("port", 443),
    sni = json.optString("sni"),
    allowInsecure = json.optBoolean("allowInsecure"),
    transportType = json.optString("transportType").ifBlank { "tcp" },
    wsPath = json.optString("wsPath").ifBlank { "/" },
    wsHost = json.optString("wsHost")
)
