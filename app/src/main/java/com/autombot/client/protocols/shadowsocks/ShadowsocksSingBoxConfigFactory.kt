package com.autombot.client.protocols.shadowsocks

import org.json.JSONArray
import org.json.JSONObject

/**
 * Gera a configuração sing-box usada pelo Shadowsocks do AutomBot.
 *
 * Mantém o desenho já usado pelo aplicativo:
 * TUN -> HEV/tun2socks -> mixed SOCKS local -> sing-box -> Shadowsocks remoto.
 *
 * [serverAddress] pode ser um IP previamente resolvido pela rede física para evitar
 * bootstrap DNS circular quando a VPN já está ativa.
 */
object ShadowsocksSingBoxConfigFactory {
    fun build(
        config: ShadowsocksConnectionConfig,
        localPort: Int,
        serverAddress: String
    ): JSONObject {
        val outbound = JSONObject().apply {
            put("type", "shadowsocks")
            put("tag", "proxy")
            put("server", serverAddress)
            put("server_port", config.port)
            put("method", config.method)
            put("password", config.password)
            // TCP e UDP ficam habilitados. O mixed inbound recebe ambos do HEV.
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
