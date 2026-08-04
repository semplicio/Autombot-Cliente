package com.autombot.client.protocols.wireguard

import android.content.Context
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.autombot.client.util.AppLog
import com.autombot.client.panel.PanelConfigParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import java.io.ByteArrayInputStream

/**
 * Envolve o GoBackend oficial da lib com.wireguard.android:tunnel, expondo um
 * StateFlow simples de tuneis pro resto do app (nada de callbacks manuais na UI).
 *
 * IMPORTANTE: a assinatura exata de alguns metodos do Backend/Statistics pode variar
 * conforme a versao da lib. Se o Android Studio acusar erro de "unresolved reference"
 * ou assinatura diferente em algum ponto deste arquivo, me manda o erro do build que
 * eu ajusto na hora — nao precisa mexer no resto do app.
 *
 * Os tuneis (nome + config) agora persistem em SharedPreferences — continuam la depois
 * de fechar o app (ver persistTunnels()/loadPersistedTunnels()).
 */
class WireGuardManager(context: Context) {

    private val appContext = context.applicationContext
    private val backend: Backend by lazy { GoBackend(appContext) }
    private val prefs = appContext.getSharedPreferences("autombot_wireguard", Context.MODE_PRIVATE)

    private val _tunnels = MutableStateFlow<List<ManagedTunnel>>(emptyList())
    val tunnels: StateFlow<List<ManagedTunnel>> = _tunnels

    // Um SimpleTunnel por nome de tunel, reaproveitado entre chamadas — ver correcao
    // logo abaixo em getOrCreateHandle().
    private val tunnelHandles = mutableMapOf<String, SimpleTunnel>()

    // Escopo proprio do manager, vive enquanto o app estiver aberto (mesma instancia
    // e criada uma vez em MainActivity). Usado so pro loop de estatisticas abaixo.
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        loadPersistedTunnels()
        // Antes as estatisticas so eram lidas uma vez, no instante da conexao (quando
        // o trafego ainda era 0) — por isso o app mostrava sempre "0 B". Agora atualiza
        // sozinho a cada poucos segundos, para qualquer tunel que estiver conectado,
        // independente de qual tela o usuario esta olhando.
        managerScope.launch {
            while (isActive) {
                delay(2000)
                _tunnels.value.filter { it.state == Tunnel.State.UP }.forEach { refreshStatistics(it) }
            }
        }
    }

    /**
     * Importa/atualiza um tunel a partir do texto colado/selecionado pelo usuario.
     * Aceita dois formatos:
     *  - config .conf "crua" (padrao wg-quick: [Interface]/[Peer]...)
     *  - o JSON que o AutomBot Core devolve, ex: {"cliente":"x","protocolo":"wireguard","config":"..."}
     *    (ver PanelConfigParser) — nesse caso a config real e extraida do campo "config"
     *    e, se o nome nao foi informado, usa o "cliente" do JSON.
     */
    fun importConfig(name: String, rawInput: String): Result<Unit> = runCatching {
        val payload = PanelConfigParser.tryParse(rawInput)
        val configText = payload?.config ?: rawInput
        val resolvedName = name.ifBlank { payload?.cliente ?: "Túnel WireGuard" }
        require(resolvedName.isNotBlank()) { "Nome do tunel nao pode ser vazio" }

        val config = Config.parse(ByteArrayInputStream(configText.toByteArray(Charsets.UTF_8)))
        val endpoint = config.peers.firstOrNull()?.endpoint?.orElse(null)?.toString() ?: "—"
        val managed = ManagedTunnel(name = resolvedName, config = config, configText = configText, endpointLabel = endpoint)
        _tunnels.update { current -> current.filterNot { it.name == resolvedName } + managed }
        persistTunnels()
        AppLog.log("Túnel WireGuard \"$resolvedName\" importado ($endpoint)", AppLog.Level.INFO)
    }.onFailure { e ->
        AppLog.log("Falha ao importar túnel WireGuard: ${e.message}", AppLog.Level.ERROR)
    }

    fun removeTunnel(name: String) {
        _tunnels.update { current -> current.filterNot { it.name == name } }
        persistTunnels()
    }

    private fun persistTunnels() {
        val array = JSONArray()
        _tunnels.value.forEach { tunnel ->
            array.put(org.json.JSONObject().apply {
                put("name", tunnel.name)
                put("configText", tunnel.configText)
            })
        }
        prefs.edit().putString("tunnels", array.toString()).apply()
    }

    private fun loadPersistedTunnels() {
        val raw = prefs.getString("tunnels", null) ?: return
        runCatching {
            val array = JSONArray(raw)
            val loaded = (0 until array.length()).mapNotNull { i ->
                val obj = array.getJSONObject(i)
                val name = obj.optString("name")
                val configText = obj.optString("configText")
                runCatching {
                    val config = Config.parse(ByteArrayInputStream(configText.toByteArray(Charsets.UTF_8)))
                    val endpoint = config.peers.firstOrNull()?.endpoint?.orElse(null)?.toString() ?: "—"
                    ManagedTunnel(name = name, config = config, configText = configText, endpointLabel = endpoint)
                }.getOrNull()
            }
            _tunnels.value = loaded
        }.onFailure { e ->
            AppLog.log("Falha ao carregar túneis WireGuard salvos: ${e.message}", AppLog.Level.ERROR)
        }
    }

    /**
     * Devolve sempre o MESMO objeto SimpleTunnel pra um dado nome de tunel, criando na
     * primeira vez. CORRECAO IMPORTANTE: antes, cada chamada (conectar, desconectar,
     * ler estatisticas) criava um SimpleTunnel novo. O GoBackend parece reconhecer o
     * tunel em execucao pela IDENTIDADE do objeto, nao so pelo nome — entao desconectar
     * com um objeto diferente do usado pra conectar nao desligava a VPN de verdade no
     * sistema (o app achava que tinha desconectado, mas a VPN continuava ativa), e ler
     * estatisticas com outro objeto ainda devolvia sempre 0, sem erro nenhum. Reusar o
     * mesmo objeto resolve os dois problemas.
     */
    private fun getOrCreateHandle(name: String): SimpleTunnel {
        return tunnelHandles.getOrPut(name) {
            SimpleTunnel(name) { newState -> applyBackendState(name, newState) }
        }
    }

    suspend fun toggle(managed: ManagedTunnel) {
        val target = if (managed.state == Tunnel.State.UP) Tunnel.State.DOWN else Tunnel.State.UP
        setState(managed, target)
    }

    private suspend fun setState(managed: ManagedTunnel, state: Tunnel.State, isRetry: Boolean = false) {
        markStatus(managed.name, if (state == Tunnel.State.UP) TunnelStatus.CONNECTING else TunnelStatus.DISCONNECTING)
        val simpleTunnel = getOrCreateHandle(managed.name)
        try {
            // IMPORTANTE: backend.setState(...) ja chama simpleTunnel.onStateChange(...) por
            // dentro (que cai em applyBackendState acima) — NAO chamar applyBackendState de
            // novo aqui com o valor de retorno. Isso estava aplicando o estado duas vezes por
            // toque (dois "conectado" no mesmo segundo no log) e causava instabilidade real:
            // a conexao chegava a cair e reconectar sozinha, ate estourar erro.
            backend.setState(simpleTunnel, state, managed.config)
        } catch (e: Exception) {
            // Antes so mostrava "BackendException" (nome da classe, sem detalhe nenhum)
            // porque e.message vinha nulo. BackendException do WireGuard tem um campo
            // "reason" com mais informacao sobre o que rolou — tentamos extrair isso e
            // tambem a causa (cause), pra proxima falha aparecer mais contexto no log e
            // conseguirmos diagnosticar o erro intermitente da primeira tentativa.
            // NOTA: se ".reason" nao existir/tiver outro nome nessa versao da lib, o
            // Android Studio vai acusar erro aqui — me manda a mensagem que eu ajusto.
            val reason = (e as? com.wireguard.android.backend.BackendException)?.reason

            // CORRECAO: "UNABLE_TO_START_VPN" e um erro classico de corrida — acontece
            // quando o Android ainda nao terminou de processar a permissao de VPN
            // concedida um instante antes, e a interface falha ao estabelecer na
            // primeira tentativa (mas funciona normalmente na segunda, como o usuario
            // reportou). Em vez de obrigar o usuario a tocar duas vezes, tentamos de
            // novo automaticamente, uma vez, depois de uma pequena pausa.
            if (reason?.toString() == "UNABLE_TO_START_VPN" && state == Tunnel.State.UP && !isRetry) {
                AppLog.log("WireGuard \"${managed.name}\": falha ao iniciar a VPN, tentando de novo automaticamente…", AppLog.Level.INFO)
                kotlinx.coroutines.delay(600)
                setState(managed, state, isRetry = true)
                return
            }

            val message = reason?.toString()
                ?: e.message?.takeIf { it.isNotBlank() }
                ?: e.cause?.message?.takeIf { it.isNotBlank() }
                ?: e.javaClass.simpleName
            markStatus(managed.name, TunnelStatus.ERROR, error = message)
        }
    }

    fun refreshStatistics(managed: ManagedTunnel) {
        if (managed.state != Tunnel.State.UP) return
        val simpleTunnel = getOrCreateHandle(managed.name)
        runCatching {
            val stats = backend.getStatistics(simpleTunnel)
            val rx = stats.totalRx()
            val tx = stats.totalTx()
            _tunnels.update { current ->
                current.map {
                    if (it.name == managed.name) it.copy(rxBytes = rx, txBytes = tx)
                    else it
                }
            }
        }.onFailure { e ->
            // Antes essa falha era engolida em silencio (runCatching sem onFailure) —
            // por isso o trafego ficava sempre em 0 B sem nenhuma pista do motivo.
            // Agora aparece no log pra sabermos exatamente o que esta acontecendo.
            AppLog.log(
                "Falha ao ler estatísticas de \"${managed.name}\": ${e.message ?: e.javaClass.simpleName}",
                AppLog.Level.ERROR
            )
        }
    }

    private fun applyBackendState(name: String, state: Tunnel.State) {
        val status = if (state == Tunnel.State.UP) TunnelStatus.CONNECTED else TunnelStatus.DISCONNECTED
        markStatus(name, status)
    }

    private fun markStatus(name: String, status: TunnelStatus, error: String? = null) {
        // Segunda camada de protecao contra duplicidade: se o status ja for exatamente o
        // mesmo (mesmo estado, mesmo erro), nao faz nada — nem log, nem update de estado.
        val existing = _tunnels.value.firstOrNull { it.name == name }
        if (existing != null && existing.status == status && existing.lastError == error) return

        if (status == TunnelStatus.ERROR && error != null) {
            AppLog.log("Erro no túnel WireGuard \"$name\": $error", AppLog.Level.ERROR)
        } else if (status == TunnelStatus.CONNECTED || status == TunnelStatus.DISCONNECTED) {
            AppLog.log(
                "WireGuard \"$name\" ${if (status == TunnelStatus.CONNECTED) "conectado" else "desconectado"}",
                if (status == TunnelStatus.CONNECTED) AppLog.Level.SUCCESS else AppLog.Level.INFO
            )
        }

        _tunnels.update { current ->
            current.map {
                if (it.name == name) {
                    it.copy(
                        state = if (status == TunnelStatus.CONNECTED) Tunnel.State.UP else Tunnel.State.DOWN,
                        status = status,
                        lastError = error
                    )
                } else it
            }
        }
    }
}

private class SimpleTunnel(
    private val tunnelName: String,
    private val onStateChanged: (Tunnel.State) -> Unit
) : Tunnel {
    override fun getName(): String = tunnelName
    override fun onStateChange(newState: Tunnel.State) = onStateChanged(newState)
}

enum class TunnelStatus { DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING, ERROR }

data class ManagedTunnel(
    val name: String,
    val config: Config,
    val configText: String,
    val endpointLabel: String,
    val state: Tunnel.State = Tunnel.State.DOWN,
    val status: TunnelStatus = TunnelStatus.DISCONNECTED,
    val rxBytes: Long = 0L,
    val txBytes: Long = 0L,
    val lastError: String? = null
)