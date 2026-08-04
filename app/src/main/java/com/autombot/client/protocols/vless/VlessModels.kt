package com.autombot.client.protocols.vless

import java.net.URI
import java.net.URLDecoder

/**
 * Config de uma conexao VLESS — etapa 1 (WebSocket, com ou sem TLS). REALITY fica pra
 * uma proxima etapa (protocolo de handshake bem mais complexo, ver SPEC.md).
 *
 * O painel (AutomBot Core, modules/pacote.py) entrega isso pronto como link
 * "vless://uuid@host:porta?type=ws&path=...&security=tls&host=...&sni=...#nome" —
 * [fromUri] faz o parse direto desse formato, sem o usuario precisar preencher campo
 * por campo (diferente do SSH, que tem varias camadas configuraveis manualmente).
 */
data class VlessConnectionConfig(
    val connectionName: String,
    val uuid: String,
    val server: String,
    val port: Int,
    val wsPath: String = "/",
    val wsHost: String = "",
    val useTls: Boolean = false,
    val sni: String = ""
)

/** Erro ao interpretar um link vless:// colado pelo usuario. */
class VlessUriParseException(message: String) : Exception(message)

fun parseVlessUri(raw: String): VlessConnectionConfig {
    val trimmed = raw.trim()
    if (!trimmed.startsWith("vless://")) {
        throw VlessUriParseException("Link precisa começar com vless://")
    }
    val uri = try {
        URI(trimmed)
    } catch (e: Exception) {
        throw VlessUriParseException("Link vless:// inválido: ${e.message}")
    }

    val uuid = uri.userInfo ?: throw VlessUriParseException("Link sem UUID (antes do @)")
    val server = uri.host ?: throw VlessUriParseException("Link sem servidor")
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

    val type = params["type"] ?: "ws"
    if (type != "ws") {
        throw VlessUriParseException(
            "Este link usa transporte \"$type\" — só WebSocket (\"ws\") é suportado nesta versão do app."
        )
    }
    if (params["security"] == "reality") {
        throw VlessUriParseException("Este link usa REALITY — ainda não suportado nesta versão do app.")
    }

    val name = uri.fragment?.takeIf { it.isNotBlank() } ?: "Conexão VLESS"

    return VlessConnectionConfig(
        connectionName = name,
        uuid = uuid,
        server = server,
        port = port,
        wsPath = params["path"]?.takeIf { it.isNotBlank() } ?: "/",
        wsHost = params["host"] ?: "",
        useTls = params["security"] == "tls",
        sni = params["sni"] ?: params["host"] ?: ""
    )
}

/** Resumo curto pra exibir na lista de conexões. */
fun VlessConnectionConfig.describeTransport(): String =
    "WebSocket" + if (useTls) " + TLS" else ""

fun VlessConnectionConfig.toJson(): org.json.JSONObject = org.json.JSONObject().apply {
    put("connectionName", connectionName)
    put("uuid", uuid)
    put("server", server)
    put("port", port)
    put("wsPath", wsPath)
    put("wsHost", wsHost)
    put("useTls", useTls)
    put("sni", sni)
}

fun vlessConnectionConfigFromJson(json: org.json.JSONObject): VlessConnectionConfig = VlessConnectionConfig(
    connectionName = json.optString("connectionName"),
    uuid = json.optString("uuid"),
    server = json.optString("server"),
    port = json.optInt("port", 443),
    wsPath = json.optString("wsPath", "/"),
    wsHost = json.optString("wsHost"),
    useTls = json.optBoolean("useTls"),
    sni = json.optString("sni")
)