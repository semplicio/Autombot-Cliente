package com.autombot.client.protocols.vmess

import org.json.JSONArray
import org.json.JSONObject

/**
 * Gera a configuração sing-box usada pelo VMess do AutomBot.
 *
 * Mantemos o mesmo desenho de rede já usado pelo aplicativo:
 * TUN -> HEV/tun2socks -> SOCKS local -> sing-box -> VMess remoto.
 *
 * O endereço de [serverAddress] já vem resolvido pela rede física do Android para
 * evitar bootstrap DNS circular quando a VPN está ativa. TLS/SNI e Host continuam
 * usando os nomes originais do perfil.
 */
object VmessSingBoxConfigFactory {
    fun build(
        config: VmessConnectionConfig,
        localPort: Int,
        serverAddress: String
    ): JSONObject {
        val outbound = JSONObject().apply {
            put("type", "vmess")
            put("tag", "proxy")
            put("server", serverAddress)
            put("server_port", config.port)
            put("uuid", config.uuid)
            put("security", "auto")
            put("alter_id", 0)

            if (config.useTls) {
                put("tls", JSONObject().apply {
                    put("enabled", true)
                    put(
                        "server_name",
                        config.sni.ifBlank {
                            config.wsHost.ifBlank { config.server }
                        }
                    )
                    put("insecure", false)
                })
            }

            put("transport", JSONObject().apply {
                put("type", "ws")
                put("path", config.wsPath.ifBlank { "/" })
                if (config.wsHost.isNotBlank()) {
                    put("headers", JSONObject().put("Host", config.wsHost))
                }
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
