package com.autombot.client.protocols.trojan

import org.json.JSONArray
import org.json.JSONObject

/**
 * Gera a configuração sing-box usada pelo Trojan do AutomBot.
 *
 * O desenho de rede permanece o mesmo do restante do app:
 *
 *   TUN -> HEV/tun2socks -> mixed local -> sing-box -> Trojan remoto
 *
 * [serverAddress] já vem resolvido pela rede física do Android para evitar bootstrap
 * DNS circular depois que o TUN está ativo. O SNI original continua preservado no TLS.
 */
object TrojanSingBoxConfigFactory {
    fun build(
        config: TrojanConnectionConfig,
        localPort: Int,
        serverAddress: String
    ): JSONObject {
        val outbound = JSONObject().apply {
            put("type", "trojan")
            put("tag", "proxy")
            put("server", serverAddress)
            put("server_port", config.port)
            put("password", config.password)

            // Não restringe "network": o padrão do sing-box mantém TCP e UDP ativos.
            put("tls", JSONObject().apply {
                put("enabled", true)
                put("server_name", config.sni.ifBlank { config.server })
                put("insecure", config.allowInsecure)
            })
        }

        return JSONObject().apply {
            put("log", JSONObject().apply {
                put("level", "info")
                put("timestamp", true)
            })
            put("inbounds", JSONArray().put(JSONObject().apply {
                put("type", "mixed")
                put("tag", "local-mixed")
                put("listen", "127.0.0.1")
                put("listen_port", localPort)
            }))
            put("outbounds", JSONArray().put(outbound))
            put("route", JSONObject().put("final", "proxy"))
        }
    }
}
