package com.autombot.client.protocols.trojan

import java.net.URI
import java.net.URLDecoder

/**
 * Config de uma conexao Trojan — TCP direto SEMPRE com TLS (nao e opcional, o
 * protocolo Trojan inteiro depende de rodar dentro de um TLS legitimo pra se
 * disfarcar de trafego HTTPS comum contra DPI). Formato do link (trojan://), igual
 * ao usado pelo resto do ecossistema (Xray/Trojan-Go):
 * "trojan://senha@host:porta?sni=xxx&allowInsecure=0#nome"
 */
data class TrojanConnectionConfig(
    val connectionName: String,
    val password: String,
    val server: String,
    val port: Int,
    val sni: String = "",
    val allowInsecure: Boolean = false
)

class TrojanUriParseException(message: String) : Exception(message)

fun parseTrojanUri(raw: String): TrojanConnectionConfig {
    val trimmed = raw.trim()
    if (!trimmed.startsWith("trojan://")) {
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
            if (idx == -1) pair to "" else {
                val key = pair.substring(0, idx)
                val value = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                key to value
            }
        }

    val name = uri.fragment?.takeIf { it.isNotBlank() } ?: "Conexão Trojan"

    return TrojanConnectionConfig(
        connectionName = name,
        password = password,
        server = server,
        port = port,
        sni = params["sni"]?.takeIf { it.isNotBlank() } ?: server,
        allowInsecure = params["allowInsecure"] == "1" || params["allowInsecure"] == "true"
    )
}

fun TrojanConnectionConfig.describeTransport(): String = "TCP + TLS"

fun TrojanConnectionConfig.toJson(): org.json.JSONObject = org.json.JSONObject().apply {
    put("connectionName", connectionName)
    put("password", password)
    put("server", server)
    put("port", port)
    put("sni", sni)
    put("allowInsecure", allowInsecure)
}

fun trojanConnectionConfigFromJson(json: org.json.JSONObject): TrojanConnectionConfig = TrojanConnectionConfig(
    connectionName = json.optString("connectionName"),
    password = json.optString("password"),
    server = json.optString("server"),
    port = json.optInt("port", 443),
    sni = json.optString("sni"),
    allowInsecure = json.optBoolean("allowInsecure")
)