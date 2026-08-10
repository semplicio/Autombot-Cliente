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
 * DNS circular depois que o TUN está ativo. O nome original continua preservado no
 * TLS/SNI e, em WebSocket, também no header Host.
 */
object TrojanSingBoxConfigFactory {
    fun build(
        config: TrojanConnectionConfig,
        localPort: Int,
        serverAddress: String
    ): JSONObject {
        val tlsServerName = config.sni.ifBlank {
            config.wsHost.ifBlank { config.server }
        }

        val outbound = JSONObject().apply {
            put("type", "trojan")
            put("tag", "proxy")
            put("server", serverAddress)
            put("server_port", config.port)
            put("password", config.password)

            // Não restringe "network": o padrão do sing-box mantém TCP e UDP ativos.
            put("tls", JSONObject().apply {
                put("enabled", true)
                put("server_name", tlsServerName)
                put("insecure", config.allowInsecure)
            })

            if (config.transportType.equals("ws", ignoreCase = true)) {
                val path = config.wsPath.ifBlank { "/" }.let {
                    if (it.startsWith('/')) it else "/$it"
                }
                // O destino do sing-box é um IP pré-resolvido. Por isso o Host do
                // WebSocket precisa continuar sendo o domínio original; sem isso
                // Cloudflare/Caddy não selecionam a rota /trojan correta.
                val wsHost = config.wsHost.ifBlank {
                    config.sni.ifBlank { config.server }
                }

                put("transport", JSONObject().apply {
                    put("type", "ws")
                    put("path", path)
                    put("headers", JSONObject().put("Host", wsHost))
                })
            }
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
