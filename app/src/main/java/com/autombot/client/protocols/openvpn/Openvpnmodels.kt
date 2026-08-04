package com.autombot.client.protocols.openvpn

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Diferente dos outros protocolos, OpenVPN não tem um link curto tipo "vless://" —
 * o padrão é um arquivo .ovpn inteiro (texto), geralmente com blocos de certificado
 * embutidos (<ca>...</ca>, <cert>...</cert>, <key>...</key>, <tls-auth>...</tls-auth>
 * etc.). A gente não faz o parser desse arquivo — passamos ele INTEIRO pro binário
 * `openvpn` de verdade via `--config`, que já sabe interpretar tudo isso nativamente.
 * Só extraímos um nome amigável (do comentário "# nome:" se existir, ou pedimos pro
 * usuário) pra identificar o perfil na lista.
 */
data class OpenVpnConnectionConfig(
    val connectionName: String,
    val configFileName: String // nome do arquivo salvo em disco (não o conteudo em si)
)

class OpenVpnConfigException(message: String) : Exception(message)

/**
 * Salva o conteúdo de um .ovpn colado/importado pelo usuário num arquivo próprio do
 * app (armazenamento privado, não acessível por outros apps) e devolve a config
 * pronta pra usar.
 */
fun saveOpenVpnConfig(context: Context, connectionName: String, ovpnContent: String): OpenVpnConnectionConfig {
    if (ovpnContent.isBlank()) {
        throw OpenVpnConfigException("O arquivo .ovpn está vazio")
    }
    if (!ovpnContent.contains("remote ", ignoreCase = false) && !ovpnContent.contains("remote\t")) {
        throw OpenVpnConfigException(
            "Esse arquivo não parece ter uma linha \"remote\" (endereço do servidor) — confere se colou o .ovpn inteiro."
        )
    }
    val dir = File(context.filesDir, "openvpn_profiles").apply { mkdirs() }
    val safeName = connectionName.replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "perfil" }
    val fileName = "$safeName.ovpn"
    File(dir, fileName).writeText(ovpnContent)
    return OpenVpnConnectionConfig(connectionName = connectionName, configFileName = fileName)
}

fun OpenVpnConnectionConfig.configFile(context: Context): File =
    File(File(context.filesDir, "openvpn_profiles"), configFileName)

fun OpenVpnConnectionConfig.toJson(): JSONObject = JSONObject().apply {
    put("connectionName", connectionName)
    put("configFileName", configFileName)
}

fun openVpnConnectionConfigFromJson(json: JSONObject): OpenVpnConnectionConfig = OpenVpnConnectionConfig(
    connectionName = json.optString("connectionName"),
    configFileName = json.optString("configFileName")
)