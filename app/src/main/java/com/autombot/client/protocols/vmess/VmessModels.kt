package com.autombot.client.protocols.vmess

import android.util.Base64
import org.json.JSONObject

/**
 * Config de uma conexao VMess (WebSocket, com ou sem TLS). O painel entrega isso
 * como link "vmess://" + um JSON em base64 (ver modules/pacote.py do AutomBot Core):
 * {"v":"2","ps":nome,"add":host,"port":porta,"id":uuid,"aid":"0","net":"ws",
 *  "type":"none","host":host,"path":caminho,"tls":"tls"|"","sni":host}
 */
data class VmessConnectionConfig(
    val connectionName: String,
    val uuid: String,
    val server: String,
    val port: Int,
    val wsPath: String = "/",
    val wsHost: String = "",
    val useTls: Boolean = false,
    val sni: String = ""
)

class VmessUriParseException(message: String) : Exception(message)

fun parseVmessUri(raw: String): VmessConnectionConfig {
    val trimmed = raw.trim()
    if (!trimmed.startsWith("vmess://")) {
        throw VmessUriParseException("Link precisa começar com vmess://")
    }
    val b64 = trimmed.removePrefix("vmess://")
    val jsonText = try {
        String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
    } catch (e: Exception) {
        throw VmessUriParseException("Não consegui decodificar o link (base64 inválido): ${e.message}")
    }
    val json = try {
        JSONObject(jsonText)
    } catch (e: Exception) {
        throw VmessUriParseException("Conteúdo do link não é um JSON válido: ${e.message}")
    }

    val net = json.optString("net", "ws")
    if (net != "ws") {
        throw VmessUriParseException(
            "Este link usa transporte \"$net\" — só WebSocket (\"ws\") é suportado nesta versão do app."
        )
    }

    return VmessConnectionConfig(
        connectionName = json.optString("ps").takeIf { it.isNotBlank() } ?: "Conexão VMess",
        uuid = json.optString("id"),
        server = json.optString("add"),
        port = json.optString("port").toIntOrNull() ?: 443,
        wsPath = json.optString("path").takeIf { it.isNotBlank() } ?: "/",
        wsHost = json.optString("host"),
        useTls = json.optString("tls") == "tls",
        sni = json.optString("sni").takeIf { it.isNotBlank() } ?: json.optString("host")
    )
}

fun VmessConnectionConfig.describeTransport(): String =
    "WebSocket" + if (useTls) " + TLS" else ""

fun VmessConnectionConfig.toJson(): JSONObject = JSONObject().apply {
    put("connectionName", connectionName)
    put("uuid", uuid)
    put("server", server)
    put("port", port)
    put("wsPath", wsPath)
    put("wsHost", wsHost)
    put("useTls", useTls)
    put("sni", sni)
}

fun vmessConnectionConfigFromJson(json: JSONObject): VmessConnectionConfig = VmessConnectionConfig(
    connectionName = json.optString("connectionName"),
    uuid = json.optString("uuid"),
    server = json.optString("server"),
    port = json.optInt("port", 443),
    wsPath = json.optString("wsPath", "/"),
    wsHost = json.optString("wsHost"),
    useTls = json.optBoolean("useTls"),
    sni = json.optString("sni")
)