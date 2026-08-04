package com.autombot.client.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Log compartilhado por todo o app — usado pela tela de Logs e alimentado pelos
 * managers/drivers reais (WireGuardManager, SshTunnelManager, Tun2SocksEngine, etc.).
 *
 * CORRECAO: antes vivia só em memória. Usuário reportou que, ao voltar pro app depois
 * de testar o navegador em segundo plano, o log tinha sumido — provável causa: o
 * Android matou o processo do app em background (comum em aparelhos com pouca
 * memória) e o log, nunca salvo em disco, se perdeu junto. Agora persiste em
 * SharedPreferences a cada novo evento, e recarrega no init().
 */
object AppLog {
    enum class Level { INFO, SUCCESS, ERROR }

    data class Entry(val timestamp: Long, val message: String, val level: Level)

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries

    private val timeFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
    private var prefs: android.content.SharedPreferences? = null

    /** Chamar uma vez, cedo (MainActivity.onCreate), antes de qualquer log(). */
    fun init(context: Context) {
        if (prefs != null) {
            // Mesma execucao do processo (Activity recriada, mas processo vivo) —
            // nao faz nada, os dados em memoria continuam intactos.
            android.util.Log.i("AppLog", "init() chamado de novo no mesmo processo — nada a recarregar")
            return
        }
        // Se chegou aqui, ou e a primeira vez, ou o PROCESSO foi reiniciado do zero
        // (ex: morto pelo sistema por falta de memoria) — nesse caso os dados em
        // memoria de TUDO (conexoes, VPN, etc.) tambem foram perdidos, nao so o log.
        android.util.Log.i("AppLog", "init() chamado num processo novo — recarregando do disco")
        prefs = context.applicationContext.getSharedPreferences("autombot_logs", Context.MODE_PRIVATE)
        loadPersisted()
    }

    fun log(message: String, level: Level = Level.INFO) {
        val tag = "AppLog"
        when (level) {
            Level.INFO -> Log.i(tag, message)
            Level.SUCCESS -> Log.i(tag, "SUCCESS: $message")
            Level.ERROR -> Log.e(tag, message)
        }
        _entries.update { current -> (listOf(Entry(System.currentTimeMillis(), message, level)) + current).take(200) }
        persist()
    }

    /** Limpa de verdade (usado pelo botão "Limpar Cache" nas Configurações). */
    fun clear() {
        _entries.value = emptyList()
        persist()
    }

    fun formatTimestamp(entry: Entry): String = timeFormat.format(Date(entry.timestamp))

    private fun persist() {
        val p = prefs ?: return
        val array = JSONArray()
        _entries.value.forEach { entry ->
            array.put(
                JSONObject().apply {
                    put("timestamp", entry.timestamp)
                    put("message", entry.message)
                    put("level", entry.level.name)
                }
            )
        }
        // CORRECAO: apply() escreve em disco de forma assincrona — se o processo for
        // encerrado pelo Android logo depois de logar um evento (o que aconteceu no
        // teste: "PROCESS ENDED" no Logcat), a escrita pode nao ter terminado ainda e
        // o dado se perde mesmo com a persistencia implementada. commit() escreve na
        // hora, de forma sincrona, antes de devolver o controle — mais lento, mas
        // garante que o evento sobrevive mesmo se o processo morrer logo em seguida.
        p.edit().putString("entries", array.toString()).commit()
    }

    private fun loadPersisted() {
        val raw = prefs?.getString("entries", null) ?: return
        runCatching {
            val array = JSONArray(raw)
            val loaded = (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                Entry(
                    timestamp = obj.getLong("timestamp"),
                    message = obj.getString("message"),
                    level = runCatching { Level.valueOf(obj.getString("level")) }.getOrDefault(Level.INFO)
                )
            }
            _entries.value = loaded
        }.onFailure { e ->
            // CORRECAO: antes essa falha era engolida em silencio — se o JSON salvo
            // estivesse corrompido ou incompleto por qualquer motivo (ex: processo
            // morto no meio de uma gravacao), o log ficava vazio sem NENHUMA pista do
            // motivo, exatamente o sintoma relatado ("o log sumiu, sem erro nenhum").
            // Agora ao menos vai pro Logcat, pra dar pra confirmar se e essa a causa.
            android.util.Log.e("AppLog", "Falha ao carregar log persistido — dado pode estar corrompido", e)
        }
    }
}