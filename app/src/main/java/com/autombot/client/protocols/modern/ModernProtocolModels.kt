package com.autombot.client.protocols.modern

import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

/** Protocolos modernos atendidos pelo núcleo sing-box nesta primeira etapa. */
enum class ModernProtocolType(val id: String, val displayName: String) {
    HYSTERIA2("hysteria2", "Hysteria2"),
    TUIC("tuic", "TUIC");

    companion object {
        fun fromId(id: String): ModernProtocolType? = entries.firstOrNull { it.id == id.lowercase() }
    }
}

data class ModernProtocolConfig(
    val type: ModernProtocolType,
    val connectionName: String,
    val server: String,
    val port: Int,
    val username: String = "",
    val uuid: String = "",
    val password: String,
    val tlsServerName: String = server,
    val insecure: Boolean = false,
    val obfsPassword: String? = null,
    val congestionControl: String = "bbr",
    val alpn: String = "h3"
) {
    init {
        require(connectionName.isNotBlank()) { "Nome da conexão não pode ficar vazio" }
        require(server.isNotBlank()) { "Servidor não pode ficar vazio" }
        require(port in 1..65535) { "Porta inválida: $port" }
        require(password.isNotBlank()) { "Senha não pode ficar vazia" }
        if (type == ModernProtocolType.TUIC) {
            require(uuid.isNotBlank()) { "UUID do TUIC não pode ficar vazio" }
            require(congestionControl in setOf("bbr", "cubic", "new_reno")) {
                "Controle de congestionamento TUIC inválido: $congestionControl"
            }
        }
    }

    /**
     * O servidor Hysteria2 oficial em modo userpass autentica usando a combinação
     * <usuario>:<senha> como senha efetiva. O sing-box espera essa combinação no
     * campo password, em vez de ter campos user/password separados.
     */
    fun hysteria2AuthPassword(): String =
        if (username.isBlank()) password else "$username:$password"

    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type.id)
        put("connection_name", connectionName)
        put("server", server)
        put("port", port)
        put("username", username)
        put("uuid", uuid)
        put("password", password)
        put("tls_server_name", tlsServerName)
        put("insecure", insecure)
        put("obfs_password", obfsPassword ?: JSONObject.NULL)
        put("congestion_control", congestionControl)
        put("alpn", alpn)
    }
}

fun modernProtocolConfigFromJson(json: JSONObject): ModernProtocolConfig {
    val type = ModernProtocolType.fromId(json.getString("type"))
        ?: throw IllegalArgumentException("Protocolo moderno desconhecido: ${json.optString("type")}")
    val server = json.getString("server")
    return ModernProtocolConfig(
        type = type,
        connectionName = json.getString("connection_name"),
        server = server,
        port = json.getInt("port"),
        username = json.optString("username"),
        uuid = json.optString("uuid"),
        password = json.getString("password"),
        tlsServerName = json.optString("tls_server_name").ifBlank { server },
        insecure = json.optBoolean("insecure", false),
        obfsPassword = json.optString("obfs_password").takeIf { it.isNotBlank() && it != "null" },
        congestionControl = json.optString("congestion_control", "bbr").ifBlank { "bbr" },
        alpn = json.optString("alpn", "h3").ifBlank { "h3" }
    )
}

/** Importa os links emitidos pelo AutomBot Core e por clientes compatíveis. */
fun parseModernProtocolUri(rawUri: String, fallbackName: String? = null): ModernProtocolConfig {
    val text = rawUri.trim()
    require(text.isNotEmpty()) { "Cole um link de conexão" }

    val uri = URI(text)
    val scheme = uri.scheme?.lowercase() ?: throw IllegalArgumentException("Link sem protocolo")
    val type = when (scheme) {
        "hysteria2", "hy2" -> ModernProtocolType.HYSTERIA2
        "tuic" -> ModernProtocolType.TUIC
        else -> throw IllegalArgumentException("Link $scheme:// não é Hysteria2/TUIC")
    }

    val server = uri.host ?: throw IllegalArgumentException("Servidor ausente no link")
    val port = uri.port.takeIf { it > 0 }
        ?: throw IllegalArgumentException("Porta ausente no link")
    val query = parseQuery(uri.rawQuery)
    val userInfoParts = uri.rawUserInfo.orEmpty().split(":", limit = 2)
    val firstCredential = decode(userInfoParts.getOrElse(0) { "" })
    val secondCredential = decode(userInfoParts.getOrElse(1) { "" })
    val fragmentName = decode(uri.rawFragment.orEmpty()).takeIf { it.isNotBlank() }
    val connectionName = fragmentName ?: fallbackName ?: "${type.displayName} $server"

    return when (type) {
        ModernProtocolType.HYSTERIA2 -> {
            require(firstCredential.isNotBlank()) { "Usuário Hysteria2 ausente no link" }
            require(secondCredential.isNotBlank()) { "Senha Hysteria2 ausente no link" }
            val obfsType = query["obfs"]?.lowercase()
            ModernProtocolConfig(
                type = type,
                connectionName = connectionName,
                server = server,
                port = port,
                username = firstCredential,
                password = secondCredential,
                tlsServerName = query["sni"].orEmpty().ifBlank { server },
                insecure = query["insecure"].isTrueLike(),
                obfsPassword = if (obfsType == "salamander") query["obfs-password"]?.takeIf { it.isNotBlank() } else null
            )
        }

        ModernProtocolType.TUIC -> {
            require(firstCredential.isNotBlank()) { "UUID TUIC ausente no link" }
            require(secondCredential.isNotBlank()) { "Senha TUIC ausente no link" }
            val congestion = query["congestion_control"].orEmpty().ifBlank { "bbr" }.lowercase()
            ModernProtocolConfig(
                type = type,
                connectionName = connectionName,
                server = server,
                port = port,
                uuid = firstCredential,
                password = secondCredential,
                tlsServerName = query["sni"].orEmpty().ifBlank { server },
                insecure = (query["allow_insecure"] ?: query["insecure"]).isTrueLike(),
                congestionControl = congestion,
                alpn = query["alpn"].orEmpty().ifBlank { "h3" }
            )
        }
    }
}

private fun parseQuery(rawQuery: String?): Map<String, String> {
    if (rawQuery.isNullOrBlank()) return emptyMap()
    return rawQuery.split("&")
        .mapNotNull { item ->
            if (item.isBlank()) return@mapNotNull null
            val pair = item.split("=", limit = 2)
            decode(pair[0]).lowercase() to decode(pair.getOrElse(1) { "" })
        }
        .toMap()
}

private fun decode(value: String): String = URLDecoder.decode(value, Charsets.UTF_8.name())

private fun String?.isTrueLike(): Boolean = when (this?.trim()?.lowercase()) {
    "1", "true", "yes", "on" -> true
    else -> false
}
