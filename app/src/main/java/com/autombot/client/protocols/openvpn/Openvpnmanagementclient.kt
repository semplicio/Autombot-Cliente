package com.autombot.client.protocols.openvpn

import android.content.Context
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
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.coroutines.coroutineContext

/**
 * Controla um processo `openvpn` DE VERDADE (binário nativo, ver README.md desta
 * pasta) rodando como subprocesso, conversando com ele pela "Interface de
 * Gerenciamento" oficial do OpenVPN — um protocolo de texto, linha a linha, por um
 * socket Unix local (ver doc/android.txt no código-fonte do OpenVPN, e o app
 * ics-openvpn como referência de implementação real).
 *
 * Duas coisas exigem tratamento especial nesse protocolo: o processo NÃO TEM
 * permissão de abrir a interface TUN nem de "proteger" seus próprios sockets da
 * VPN de sistema — só o nosso app (rodando dentro de um VpnService de verdade)
 * pode fazer isso. Comunicação via TCP local (127.0.0.1) — mais compatível com
 * binários openvpn padrão que o LocalSocket/ancillary-data (que exigiria o
 * ics-openvpn compilado especificamente pra Android).
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
    // CORRIGIDO: usamos ServerSocket TCP local (127.0.0.1) em vez de LocalServerSocket
    // (que usa o namespace abstrato do Android, incompativel com o binario openvpn
    // que espera um caminho Unix real ou uma porta TCP).
    private var tcpServerSocket: ServerSocket? = null
    private var tcpClientSocket: Socket? = null
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
        runCatching { tcpClientSocket?.close() }
        runCatching { tcpServerSocket?.close() }
        runCatching { tunPfd?.close() }
        tcpClientSocket = null
        tcpServerSocket = null
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

        // CORRIGIDO: usamos ServerSocket TCP local (127.0.0.1) em vez de
        // LocalServerSocket (namespace abstrato do Android, incompativel com o
        // binario openvpn padrao). O processo openvpn recebe "--management 127.0.0.1
        // <porta> tcp" e conecta normalmente.
        val mgmtPort = ServerSocket(0).use { it.localPort }
        val tcpServer = try {
            ServerSocket(mgmtPort, 1, InetAddress.getByName("127.0.0.1"))
        } catch (e: Exception) {
            AppLog.log("OpenVPN \"$connectionName\": falha ao abrir socket de gerenciamento TCP: ${e.message}", AppLog.Level.ERROR)
            onStateChange(false, e.message)
            running = false
            return
        }
        tcpServerSocket = tcpServer
        AppLog.log("OpenVPN \"$connectionName\": socket de gerenciamento TCP em 127.0.0.1:$mgmtPort", AppLog.Level.INFO)

        val configPath = config.configFile(context).absolutePath
        AppLog.log("OpenVPN \"$connectionName\": carregando config de $configPath", AppLog.Level.INFO)

        val processArgs = listOf(
            binary.absolutePath,
            "--config", configPath,
            "--management", "127.0.0.1", mgmtPort.toString(), "tcp",
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

        AppLog.log("OpenVPN \"$connectionName\": aguardando conexão do processo no socket TCP...", AppLog.Level.INFO)
        val tcpClient = try {
            tcpServer.soTimeout = 30_000 // 30s para o processo conectar
            tcpServer.accept()
        } catch (e: Exception) {
            AppLog.log("OpenVPN \"$connectionName\": processo não conectou no socket TCP: ${e.message}", AppLog.Level.ERROR)
            onStateChange(false, e.message)
            running = false
            runCatching { proc.destroy() }
            cleanup()
            return
        }
        tcpClientSocket = tcpClient
        AppLog.log("OpenVPN \"$connectionName\": processo conectado ao socket de gerenciamento TCP", AppLog.Level.INFO)

        try {
            sendCommandRaw("hold release\n")
            sendCommandRaw("state on\n")
            sendCommandRaw("bytecount 2\n")

            val reader = BufferedReader(InputStreamReader(tcpClient.getInputStream()))
            while (running && coroutineContext.isActive) {
                val line = reader.readLine() ?: break
                handleLine(line)
            }
        } catch (e: Exception) {
            AppLog.log("OpenVPN \"$connectionName\": loop de gerenciamento encerrado — ${e.message}", AppLog.Level.INFO)
        } finally {
            running = false
            if (connected) onStateChange(false, null)
            cleanup()
        }
    }

    // handleLine nao precisa mais do client como parametro — sendCommandRaw usa
    // tcpClientSocket diretamente, e NEED-OK/OPENTUN/PROTECTFD nao passam fds
    // via ancillary data (que era especifico do LocalSocket). Neste modelo TCP,
    // o fd da TUN e o protect() sao respondidos por outros meios (ver handleNeedOk).
    private fun handleLine(line: String) {
        when {
            line.startsWith(">NEED-OK:") -> handleNeedOk(line)
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
                AppLog.log(
                    "OpenVPN \"$connectionName\": servidor pediu usuário/senha (auth-user-pass) — " +
                        "não suportado ainda nesta versão do app.",
                    AppLog.Level.ERROR
                )
            }
            else -> { /* outras linhas ignoradas */ }
        }
    }

    /**
     * [>NEED-OK:TYPE:info] — o processo openvpn esta perguntando alguma coisa que só
     * o app (dentro do VpnService) pode responder.
     *
     * NOTA: neste modelo de comunicação TCP (diferente do LocalSocket que suporta
     * ancillary data), OPENTUN e PROTECTFD não conseguem passar file descriptors
     * diretamente. A solução real seria usar o binario ics-openvpn que já tem
     * suporte a isso via JNI. Por ora, aceitamos apenas binarios que não precisam
     * do mecanismo de fd (ex: OpenVPN compilado com --enable-async-push=no e
     * suporte a mgmt-interface-exclusive) e logamos claramente quando o processo
     * pede algo que não conseguimos atender via TCP.
     */
    private fun handleNeedOk(line: String) {
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
                // NOTA: via TCP não conseguimos passar o fd como ancillary data.
                // Alguns builds do openvpn aceitam o fd como inteiro no próprio
                // texto do comando (extensao nao-padrao). Tentamos isso aqui; se nao
                // funcionar com o binario em uso, o processo vai reportar FATAL.
                AppLog.log(
                    "OpenVPN \"$connectionName\": OPENTUN solicitado — passando fd=${pfd.fd} via texto " +
                        "(requer binario com suporte a mgmt-fd-passing).",
                    AppLog.Level.INFO
                )
                sendCommandRaw("needok 'OPENTUN' ok ${pfd.fd}\n")
            }
            "PROTECTFD" -> {
                // Via TCP nao recebemos o fd por ancillary data. Logamos e cancelamos
                // — o processo provavelmente vai continuar mesmo assim, pois alguns
                // builds ignoram a falha de PROTECTFD se nao houver VPN ativa.
                AppLog.log(
                    "OpenVPN \"$connectionName\": PROTECTFD solicitado — não suportado via TCP " +
                        "(requer LocalSocket com ancillary data, como o ics-openvpn faz).",
                    AppLog.Level.ERROR
                )
                sendCommandRaw("needok 'PROTECTFD' cancel\n")
            }
            else -> {
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

    private fun handleByteCount(line: String) {
        // Formato: >BYTECOUNT:bytes_in,bytes_out
        val parts = line.removePrefix(">BYTECOUNT:").split(",")
        val rx = parts.getOrNull(0)?.toLongOrNull() ?: return
        val tx = parts.getOrNull(1)?.toLongOrNull() ?: return
        onBytesUpdate(rx, tx)
    }

    private fun sendCommandRaw(command: String) {
        val out: OutputStream = tcpClientSocket?.getOutputStream() ?: return
        runCatching {
            out.write(command.toByteArray(Charsets.UTF_8))
            out.flush()
        }
    }
}
