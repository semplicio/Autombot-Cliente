package com.autombot.client.panel

import org.json.JSONException
import org.json.JSONObject

/**
 * O AutomBot Core retorna as configuracoes de conexao nesse formato, independente do
 * protocolo:
 *
 *   { "cliente": "nome", "protocolo": "wireguard", "config": "[Interface]\n..." }
 *
 * Esse parser e generico (nao especifico do WireGuard) porque o mesmo formato deve
 * valer pra SSH, V2Ray, etc. quando os drivers desses protocolos forem implementados
 * (ver SPEC.md secao 2/7). Cada driver so precisa saber interpretar o conteudo do
 * campo "config" no formato que faz sentido pra ele.
 */
object PanelConfigParser {

    data class Payload(val cliente: String?, val protocolo: String?, val config: String)

    /**
     * Tenta interpretar [rawInput] como o JSON do painel. Retorna null se nao for JSON
     * valido nesse formato — nesse caso quem chamou deve tratar [rawInput] como sendo
     * a config "crua" (ex: um .conf colado direto, sem o envelope JSON).
     */
    fun tryParse(rawInput: String): Payload? {
        val trimmed = rawInput.trim()
        if (!trimmed.startsWith("{")) return null
        return try {
            val json = JSONObject(trimmed)
            val config = if (json.has("config")) json.getString("config") else return null
            Payload(
                cliente = json.optString("cliente").takeIf { it.isNotBlank() },
                protocolo = json.optString("protocolo").takeIf { it.isNotBlank() },
                config = config
            )
        } catch (e: JSONException) {
            null
        }
    }
}