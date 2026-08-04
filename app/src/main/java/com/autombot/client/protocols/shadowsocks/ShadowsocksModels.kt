package com.autombot.client.protocols.shadowsocks

import android.util.Base64
import org.json.JSONObject

/**
 * Config de uma conexao Shadowsocks — TCP direto (sem WebSocket), cifra AEAD. O
 * painel (AutomBot Core, modules/pacote.py) entrega isso como link "ss://" + SIP002:
 * base64(metodo:senha) + "@" + host + ":" + porta + "#" + nome.
 */
data class ShadowsocksConnectionConfig(
    val connectionName: String,
    val server: String,
    val port: Int,
    val method: String,
    val password: String
)

class ShadowsocksUriParseException(message: String) : Exception(message)

/** Metodos AEAD suportados nesta primeira versao — ver ShadowsocksCrypto.kt. */
val SUPPORTED_SS_METHODS = setOf("chacha20-ietf-poly1305", "aes-256-gcm", "aes-128-gcm")

fun parseShadowsocksUri(raw: String): ShadowsocksConnectionConfig {
    val trimmed = raw.trim()
    if (!trimmed.startsWith("ss://")) {
        throw ShadowsocksUriParseException("Link precisa começar com ss://")
    }
    val withoutScheme = trimmed.removePrefix("ss://")
    val hashIdx = withoutScheme.indexOf('#')
    val name = if (hashIdx != -1) {
        java.net.URLDecoder.decode(withoutScheme.substring(hashIdx + 1), "UTF-8")
    } else "Conexão Shadowsocks"
    val beforeHash = if (hashIdx != -1) withoutScheme.substring(0, hashIdx) else withoutScheme

    val atIdx = beforeHash.lastIndexOf('@')
    if (atIdx == -1) {
        throw ShadowsocksUriParseException("Link sem \"@\" separando usuário e servidor")
    }
    val userInfoRaw = beforeHash.substring(0, atIdx)
    val hostPort = beforeHash.substring(atIdx + 1)

    val userInfoDecoded = try {
        String(Base64.decode(userInfoRaw, Base64.URL_SAFE.or(Base64.NO_PADDING).or(Base64.NO_WRAP)), Charsets.UTF_8)
    } catch (e: Exception) {
        try {
            String(Base64.decode(userInfoRaw, Base64.DEFAULT), Charsets.UTF_8)
        } catch (e2: Exception) {
            throw ShadowsocksUriParseException("Não consegui decodificar método/senha (base64 inválido): ${e2.message}")
        }
    }

    val colonIdx = userInfoDecoded.indexOf(':')
    if (colonIdx == -1) {
        throw ShadowsocksUriParseException("Método/senha decodificados mas sem \":\" entre eles")
    }
    val method = userInfoDecoded.substring(0, colonIdx)
    val password = userInfoDecoded.substring(colonIdx + 1)

    if (method !in SUPPORTED_SS_METHODS) {
        throw ShadowsocksUriParseException(
            "Método \"$method\" não é suportado nesta versão do app (só: ${SUPPORTED_SS_METHODS.joinToString()})"
        )
    }

    val portIdx = hostPort.lastIndexOf(':')
    if (portIdx == -1) {
        throw ShadowsocksUriParseException("Link sem porta do servidor")
    }
    val server = hostPort.substring(0, portIdx)
    val port = hostPort.substring(portIdx + 1).toIntOrNull()
        ?: throw ShadowsocksUriParseException("Porta do servidor inválida")

    return ShadowsocksConnectionConfig(
        connectionName = name,
        server = server,
        port = port,
        method = method,
        password = password
    )
}

fun ShadowsocksConnectionConfig.describeTransport(): String = "TCP direto ($method)"

fun ShadowsocksConnectionConfig.toJson(): JSONObject = JSONObject().apply {
    put("connectionName", connectionName)
    put("server", server)
    put("port", port)
    put("method", method)
    put("password", password)
}

fun shadowsocksConnectionConfigFromJson(json: JSONObject): ShadowsocksConnectionConfig = ShadowsocksConnectionConfig(
    connectionName = json.optString("connectionName"),
    server = json.optString("server"),
    port = json.optInt("port", 8388),
    method = json.optString("method"),
    password = json.optString("password")
)