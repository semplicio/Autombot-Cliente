package com.autombot.client.protocols.openvpn

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.ParcelFileDescriptor
import com.autombot.client.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.FileDescriptor
import java.io.InputStreamReader
import java.io.OutputStream
import kotlin.coroutines.coroutineContext

/**
 * Controla um processo `openvpn` DE VERDADE (binário nativo, ver README.md desta
 * pasta) rodando como subprocesso, conversando com ele pela "Interface de
 * Gerenciamento" oficial do OpenVPN — um protocolo de texto, linha a linha, por um
 * socket Unix local (ver doc/android.txt no código-fonte do OpenVPN, e o app
 * ics-openvpn como referência de implementação real).
 *
 * Duas coisas exigem tratamento especial nesse protocolo (comparado a um management
 * interface comum de desktop): o processo NÃO TEM permissão de abrir a interface TUN
 * nem de "proteger" seus próprios sockets da VPN de sistema — só o nosso app
 * (rodando dentro de um VpnService de verdade) pode fazer isso. Por isso, quando o
 * openvpn precisa de qualquer uma dessas duas coisas, ele PARA e pergunta pro app via
 * uma query "NEED-OK", e a gente responde entregando o descritor de arquivo (fd)
 * certo — TUN pra abrir, ou o fd do socket dele mesmo pra proteger — como dado
 * auxiliar (ancillary data) na mesma mensagem, usando o suporte nativo do
 * [LocalSocket] do Android pra isso (é o mesmo mecanismo de baixo nível usado por
 * QUALQUER app OpenVPN real no Android, incluindo o oficial).
 *
 * ATENCAO: implementado com base na documentação oficial do protocolo e relatos reais
 * de comportamento (logs de outros apps) — nunca testado contra o binário de verdade
 * rodando. Ver README.md desta pasta pra mais contexto.
 */
class OpenVpnManagementClient(
    private val context: Context,
    private val config: OpenVpnConnectionConfig,
    private val connectionName: String,
    /** Chama VpnService.Builder()....establish() de verdade — só o Service pode fazer isso. */
    private val establishTun: () -> ParcelFileDescriptor?,
    /** Chama VpnService.protect(fd) de verdade. */
    private val protectFd: (FileDescriptor) -> Boolean,
    private val onStateChange: (connected: Boolean, error: String?) -> Unit,
    private val onBytesUpdate: (rx: Long, tx: Long) -> Unit
) {
    private var process: Process? = null
    private var serverSocket: LocalServerSocket? = null
    private var clientSocket: LocalSocket? = null
    private var tunPfd: ParcelFileDescriptor? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var running = false
    @Volatile private var connected = false

    fun start() {
        if (running) return
        running = true
        scope.launch { runManagement() }
    }

    fun stop() {
        running = false
        runCatching { sendCommandRaw("signal SIGTERM\n") }
        // Da um tempo curto pro processo desligar limpo (fecha a TUN, etc.) antes de
        // forcar — se nao responder, mata mesmo.
        scope.launch {
            kotlinx.coroutines.delay(1500)
            runCatching { process?.destroy() }
            cleanup()
        }
    }

    private fun cleanup() {
        runCatching { clientSocket?.close() }
        runCatching { serverSocket?.close() }
        runCatching { tunPfd?.close() }
        clientSocket = null
        serverSocket = null
        tunPfd = null
    }

    private suspend fun runManagement() {
        val binary = File(context.applicationInfo.nativeLibraryDir, "libopenvpn.so")
        AppLog.log("OpenVPN \"$connectionName\": usando binário em ${binary.absolutePath}", AppLog.Level.INFO)
        if (!binary.exists()) {
            AppLog.log(
                "OpenVPN \"$connectionName\": binário nativo não encontrado (${binary.absolutePath}) — " +
                    "veja protocols/openvpn/README.md pra compilar e incluir o libopenvpn.so no projeto.",
                AppLog.Level.ERROR
            )
            onStateChange(false, "Binário openvpn não encontrado no app")
            running = false
            return
        }

        // CORRECAO: Usamos um caminho real no filesystem para o socket de gerenciamento.
        // Alguns binarios de OpenVPN nao suportam o namespace abstrato do Android
        // nativamente sem o prefixo \0, que e dificil de passar via ProcessBuilder.
        val socketFile = File(context.cacheDir, "ovpn_mgmt_${connectionName.hashCode()}_${System.currentTimeMillis()}.sock")
        val socketPath = socketFile.absolutePath
        
        val server = try {
            LocalServerSocket(socketPath)
        } catch (e: Exception) {
            AppLog.log("OpenVPN \"$connectionName\": falha ao criar socket de gerenciamento ($socketPath): ${e.message}", AppLog.Level.ERROR)
            onStateChange(false, e.message)
            running = false
            return
        }
        serverSocket = server
        AppLog.log("OpenVPN \"$connectionName\": socket de gerenciamento criado em $socketPath", AppLog.Level.INFO)

        val configPath = config.configFile(context).absolutePath
        AppLog.log("OpenVPN \"$connectionName\": carregando config de $configPath", AppLog.Level.INFO)

        val processArgs = listOf(
            binary.absolutePath,
            "--config", configPath,
            "--management", socketPath, "unix",
            "--management-client",
            "--management-query-passwords",
            "--management-hold",
            "--verb", "3"
        )

        AppLog.log("OpenVPN \"$connectionName\": iniciando processo...", AppLog.Level.INFO)
        val proc = try {
            ProcessBuilder(processArgs)
                .redirectErrorStream(true)
                .directory(context.filesDir)
                .start()
        } catch (e: Exception) {
            AppLog.log("OpenVPN \"$connectionName\": falha ao iniciar o processo openvpn: ${e.message}", AppLog.Level.ERROR)
            onStateChange(false, e.message)
            running = false
            cleanup()
            runCatching { socketFile.delete() }
            return
        }
        process = proc

        // Le a saida padrao do processo (stdout+stderr combinados)
        scope.launch(Dispatchers.IO) {
            try {
                BufferedReader(InputStreamReader(proc.inputStream)).forEachLine { line ->
                    AppLog.log("OpenVPN \"$connectionName\" (processo): $line", AppLog.Level.INFO)
                }
            } catch (e: Exception) {
                AppLog.log("OpenVPN \"$connectionName\" (processo): saída encerrada — ${e.message}", AppLog.Level.INFO)
            }
        }

        AppLog.log("OpenVPN \"$connectionName\": aguardando conexão do processo no socket...", AppLog.Level.INFO)
        val client = try {
            server.accept()
        } catch (e: Exception) {
            AppLog.log("OpenVPN \"$connectionName\": processo não conectou no socket: ${e.message}", AppLog.Level.ERROR)
            onStateChange(false, e.message)
            running = false
            runCatching { proc.destroy() }
            cleanup()
            runCatching { socketFile.delete() }
            return
        }
        clientSocket = client
        AppLog.log("OpenVPN \"$connectionName\": processo conectado ao socket de gerenciamento", AppLog.Level.INFO)

        try {
            sendCommandRaw("hold release\n")
            sendCommandRaw("state on\n")
            sendCommandRaw("bytecount 2\n")

            val reader = BufferedReader(InputStreamReader(client.inputStream))
            while (running && coroutineContext.isActive) {
                val line = reader.readLine() ?: break
                handleLine(line, client)
            }
        } catch (e: Exception) {
            AppLog.log("OpenVPN \"$connectionName\": loop de gerenciamento encerrado — ${e.message}", AppLog.Level.INFO)
        } finally {
            running = false
            if (connected) onStateChange(false, null)
            cleanup()
            runCatching { socketFile.delete() }
        }
    }

    private fun handleLine(line: String, client: LocalSocket) {
        when {
            line.startsWith(">NEED-OK:") -> handleNeedOk(line, client)
            line.startsWith(">STATE:") -> handleState(line)
            line.startsWith(">BYTECOUNT:") -> handleByteCount(line)
            line.startsWith(">LOG:") -> {
                val msg = line.substringAfter(">LOG:", "")
                AppLog.log("OpenVPN \"$connectionName\": $msg", AppLog.Level.INFO)
            }
            line.startsWith(">FATAL:") -> {
                val msg = line.substringAfter(">FATAL:", "")
                AppLog.log("OpenVPN \"$connectionName\": erro fatal — $msg", AppLog.Level.ERROR)
                onStateChange(false, msg)
            }
            line.startsWith(">PASSWORD:") -> {
                // Pedido de usuario/senha (auth-user-pass) — nao suportado nesta
                // versao (perfis com login/senha externo ao .ovpn nao funcionam
                // ainda). So loga pra dar uma pista clara do motivo de travar aqui.
                AppLog.log(
                    "OpenVPN \"$connectionName\": servidor pediu usuário/senha (auth-user-pass) — " +
                        "não suportado ainda nesta versão do app.",
                    AppLog.Level.ERROR
                )
            }
            else -> {
                // outras linhas (respostas de comando, etc.) — ignoradas por ora
            }
        }
    }

    /**
     * [>NEED-OK:TYPE:info] — o processo openvpn esta perguntando alguma coisa que só
     * o app (dentro do VpnService) pode responder. Os dois tipos que exigem
     * passagem de descritor de arquivo (fd) sao tratados especialmente; os demais
     * (IFCONFIG/ROUTE/DNSSERVER/DNSDOMAIN) so recebem "ok" generico — usamos uma
     * configuracao de rede propria (0.0.0.0/0 catch-all, igual aos outros
     * protocolos) em vez de seguir a config exata que o servidor pediu.
     */
    private fun handleNeedOk(line: String, client: LocalSocket) {
        val rest = line.removePrefix(">NEED-OK:")
        val type = rest.substringBefore(':').trim()

        when (type) {
            "OPENTUN" -> {
                val pfd = establishTun()
                if (pfd == null) {
                    AppLog.log("OpenVPN \"$connectionName\": falha ao estabelecer a interface TUN", AppLog.Level.ERROR)
                    sendCommandRaw("needok 'OPENTUN' cancel\n")
                    return
                }
                tunPfd = pfd
                // O fd vai "grudado" (ancillary data) na PROXIMA escrita no socket —
                // por isso setFileDescriptorsForSend() vem ANTES do write().
                client.setFileDescriptorsForSend(arrayOf(pfd.fileDescriptor))
                sendCommandRaw("needok 'OPENTUN' ok\n")
            }
            "PROTECTFD" -> {
                // Aqui e o CONTRARIO: o openvpn que manda o fd dele PRA GENTE, como
                // dado auxiliar na mensagem que a gente acabou de ler — getAncillary
                // FileDescriptors() devolve o que veio anexado na ultima leitura.
                val received = client.ancillaryFileDescriptors
                val fd = received?.firstOrNull()
                if (fd == null) {
                    AppLog.log("OpenVPN \"$connectionName\": pedido de PROTECTFD sem descritor de arquivo anexado", AppLog.Level.ERROR)
                    sendCommandRaw("needok 'PROTECTFD' cancel\n")
                    return
                }
                val protected = protectFd(fd)
                sendCommandRaw("needok 'PROTECTFD' ${if (protected) "ok" else "cancel"}\n")
                if (!protected) {
                    AppLog.log("OpenVPN \"$connectionName\": protect() falhou no socket do próprio openvpn", AppLog.Level.ERROR)
                }
            }
            else -> {
                // IFCONFIG, ROUTE, ROUTE6, DNSSERVER, DNSDOMAIN, PERSIST_TUN_ACTION,
                // etc. — so confirma, sem seguir os valores exatos pedidos (usamos
                // config de rede propria via VpnService.Builder, igual aos outros
                // protocolos do app).
                sendCommandRaw("needok '$type' ok\n")
            }
        }
    }

    private fun handleState(line: String) {
        // Formato: >STATE:timestamp,STATE_NAME,detail,local_ip,remote_ip,...
        val parts = line.removePrefix(">STATE:").split(",")
        val stateName = parts.getOrNull(1) ?: return
        when (stateName) {
            "CONNECTED" -> {
                connected = true
                onStateChange(true, null)
                AppLog.log("OpenVPN \"$connectionName\": conectado (túnel real, dados fluindo)", AppLog.Level.SUCCESS)
            }
            "RECONNECTING", "EXITING" -> {
                if (connected) {
                    connected = false
                    val detail = parts.getOrNull(2)?.takeIf { it.isNotBlank() }
                    onStateChange(false, detail)
                }
            }
            else -> {
                // WAIT, AUTH, GET_CONFIG, ASSIGN_IP, ADD_ROUTES, RESOLVE, TCP_CONNECT
                // etc. — estagios intermediarios normais, so log informativo.
                AppLog.log("OpenVPN \"$connectionName\": $stateName", AppLog.Level.INFO)
            }
        }
    }

    private fun handleStateChange(connected: Boolean, error: String?) {
        this.connected = connected
        onStateChange(connected, error)
    }

    private fun handleByteCount(line: String) {
        // Formato: >BYTECOUNT:bytes_in,bytes_out
        val parts = line.removePrefix(">BYTECOUNT:").split(",")
        val rx = parts.getOrNull(0)?.toLongOrNull() ?: return
        val tx = parts.getOrNull(1)?.toLongOrNull() ?: return
        onBytesUpdate(rx, tx)
    }

    private fun sendCommandRaw(command: String) {
        val out: OutputStream = clientSocket?.outputStream ?: return
        runCatching {
            out.write(command.toByteArray(Charsets.UTF_8))
            out.flush()
        }
    }
}
