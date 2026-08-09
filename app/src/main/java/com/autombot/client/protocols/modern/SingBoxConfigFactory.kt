package com.autombot.client.protocols.modern

import org.json.JSONArray
import org.json.JSONObject

/** Monta apenas a configuração mínima necessária: Mixed local -> protocolo remoto. */
object SingBoxConfigFactory {
    fun build(config: ModernProtocolConfig, localPort: Int): JSONObject {
        val outbound = when (config.type) {
            ModernProtocolType.HYSTERIA2 -> hysteria2Outbound(config)
            ModernProtocolType.TUIC -> tuicOutbound(config)
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

    private fun hysteria2Outbound(config: ModernProtocolConfig): JSONObject = JSONObject().apply {
        put("type", "hysteria2")
        put("tag", "proxy")
        put("server", config.server)
        put("server_port", config.port)
        put("password", config.hysteria2AuthPassword())
        put("tls", tls(config, null))
        config.obfsPassword?.takeIf { it.isNotBlank() }?.let { obfsPassword ->
            put("obfs", JSONObject().apply {
                put("type", "salamander")
                put("password", obfsPassword)
            })
        }
    }

    private fun tuicOutbound(config: ModernProtocolConfig): JSONObject = JSONObject().apply {
        put("type", "tuic")
        put("tag", "proxy")
        put("server", config.server)
        put("server_port", config.port)
        put("uuid", config.uuid)
        put("password", config.password)
        put("congestion_control", config.congestionControl)
        put("udp_relay_mode", "native")
        put("zero_rtt_handshake", false)
        put("heartbeat", "10s")
        put("tls", tls(config, config.alpn.takeIf { it.isNotBlank() }))
    }

    private fun tls(config: ModernProtocolConfig, alpn: String?): JSONObject = JSONObject().apply {
        put("enabled", true)
        put("server_name", config.tlsServerName.ifBlank { config.server })
        put("insecure", config.insecure)
        if (!alpn.isNullOrBlank()) put("alpn", JSONArray().put(alpn))
    }
}
