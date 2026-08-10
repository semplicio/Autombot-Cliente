package com.autombot.client.protocols.modern

import org.json.JSONArray
import org.json.JSONObject

/** Monta apenas a configuração mínima necessária: Mixed local -> protocolo remoto. */
object SingBoxConfigFactory {
    private const val BOOTSTRAP_DNS_TAG = "bootstrap-dns"
    private const val BOOTSTRAP_DNS_IP = "8.8.8.8"

    fun build(config: ModernProtocolConfig, localPort: Int): JSONObject {
        val outbound = when (config.type) {
            ModernProtocolType.HYSTERIA2 -> hysteria2Outbound(config)
            ModernProtocolType.TUIC -> tuicOutbound(config)
        }

        return JSONObject().apply {
            put("log", JSONObject().apply {
                // INFO gera uma linha para praticamente cada socket TCP/UDP que
                // atravessa o mixed inbound. Como cada linha entra no AppLog/StateFlow,
                // rajadas do navegador causavam centenas de alocações e recomposições
                // enquanto o túnel estava transferindo dados. WARN mantém avisos e
                // erros úteis sem colocar a telemetria de cada conexão no caminho quente.
                put("level", "warn")
                put("timestamp", true)
            })
            put("dns", bootstrapDns())
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

    /**
     * O CLI standalone do sing-box no Android não recebe a integração de DNS da
     * aplicação gráfica oficial. Sem um resolver explícito ele pode cair no
     * resolver libc/local (por exemplo ::1:53), criando uma dependência circular
     * assim que o TUN do AutomBot sobe.
     *
     * O bootstrap usa um IP literal, então a resolução do host remoto não depende
     * de outra consulta de nomes. O socket do core sai pelo UID do próprio app,
     * excluído do VpnService. DNS originado pelos aplicativos continua passando
     * normalmente pelo proxy/túnel; este servidor resolve apenas nomes usados pelo
     * próprio sing-box, como o endereço do servidor Hysteria2/TUIC.
     */
    private fun bootstrapDns(): JSONObject = JSONObject().apply {
        put("servers", JSONArray().put(JSONObject().apply {
            put("type", "udp")
            put("tag", BOOTSTRAP_DNS_TAG)
            put("server", BOOTSTRAP_DNS_IP)
            put("server_port", 53)
        }))
    }

    private fun hysteria2Outbound(config: ModernProtocolConfig): JSONObject = JSONObject().apply {
        put("type", "hysteria2")
        put("tag", "proxy")
        put("server", config.server)
        put("server_port", config.port)
        put("password", config.hysteria2AuthPassword())
        put("domain_resolver", BOOTSTRAP_DNS_TAG)
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
        put("domain_resolver", BOOTSTRAP_DNS_TAG)
        put("tls", tls(config, config.alpn.takeIf { it.isNotBlank() }))
    }

    private fun tls(config: ModernProtocolConfig, alpn: String?): JSONObject = JSONObject().apply {
        put("enabled", true)
        put("server_name", config.tlsServerName.ifBlank { config.server })
        put("insecure", config.insecure)
        if (!alpn.isNullOrBlank()) put("alpn", JSONArray().put(alpn))
    }
}
