package com.autombot.client.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.autombot.client.core.tun2socks.NativeTun2Socks
import com.autombot.client.protocols.openvpn.OpenVpnManagementClient
import com.autombot.client.protocols.openvpn.OpenVpnTunnelManager
import com.autombot.client.ui.MainActivity
import com.autombot.client.util.AppLog

/**
 * VpnService real do AutomBot Connect.
 *
 * Etapa 1 (interface TUN + chave de VPN na barra) + motor de roteamento de pacotes
 * NATIVO (NativeTun2Socks — biblioteca C hev-socks5-tunnel via JNI, no lugar do
 * antigo Tun2SocksEngine.kt escrito em Kotlin, que acumulou bug atras de bug e foi
 * substituido — ver SPEC.md Etapa 61) ja ligados um no outro.
 *
 * !!!!! LEIA ANTES DE MEXER !!!!!
 * UDP genuino atraves do motor nativo depende do Socks5Server.kt local (usado por
 * SSH/VLESS/VMess/Shadowsocks) suportar o comando UDP ASSOCIATE do SOCKS5 — ainda
 * NAO suporta (so implementa CONNECT). Sem isso, o motor nativo tenta negociar UDP
 * com o proxy local, recebe "comando nao suportado", e esse UDP nao flui — mesmo
 * com o motor novo rodando certo. Essa e a proxima peca que falta.
 *
 * Recebe a porta do proxy SOCKS5 local (que o SshTunnelManager ja deixa pronto) via
 * extra do Intent — start com ACTION_START + EXTRA_SOCKS_PORT.
 */
class AutomBotVpnService : VpnService() {

    private var tunInterface: ParcelFileDescriptor? = null
    private var activeSocksPort: Int? = null
    private var activeDns1: String? = null
    private var activeDns2: String? = null
    private var openVpnClient: OpenVpnManagementClient? = null
    private var openVpnConnectionName: String? = null
    // CORRECAO: usuario notou (evidencia real, nao suposicao) que o WireGuard
    // continua funcionando em segundo plano perfeitamente, enquanto os outros 6
    // protocolos (que passam por ESSE servico, escrito por nos) travam assim que o
    // app sai de vista. Isso descarta a teoria de gerenciador de bateria do
    // fabricante (mataria todos igual, nao so alguns) — aponta pra diferenca real
    // no nosso proprio codigo. O WireGuard usa uma biblioteca propria, madura,
    // que quase certamente ja segura isso por dentro — o nosso servico NUNCA
    // segurava um WakeLock, o que pode deixar a CPU entrar em modo de economia
    // (mesmo com o servico em primeiro plano) assim que a tela apaga/o app sai de
    // vista, travando a entrada/saida de dados dos nossos sockets.
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    companion object {
        const val ACTION_START = "com.autombot.client.core.START_VPN"
        const val ACTION_STOP = "com.autombot.client.core.STOP_VPN"
        const val ACTION_START_OPENVPN = "com.autombot.client.core.START_OPENVPN"
        const val EXTRA_SOCKS_PORT = "socks_port"
        const val EXTRA_DNS_PRIMARY = "dns_primary"
        const val EXTRA_DNS_SECONDARY = "dns_secondary"
        const val EXTRA_OPENVPN_CONFIG_PATH = "openvpn_config_path"
        const val EXTRA_OPENVPN_CONNECTION_NAME = "openvpn_connection_name"
        private const val NOTIFICATION_CHANNEL_ID = "autombot_vpn"
        private const val NOTIFICATION_ID = 1001

        // CORRECAO CRITICA: qualquer socket que o PROPRIO app cria pra falar com o
        // servidor de verdade (ex: a conexao SSH real do SshTunnelManager) precisa
        // ser "protegido" via VpnService.protect() — senao, com a rota 0.0.0.0/0
        // ativa, essa conexao tambem seria capturada pelo nosso proprio TUN, criando
        // um loop (a VPN tentando rotear o trafego que ela mesma precisa fazer pra
        // funcionar) — e foi exatamente isso que quebrou a internet inteira no teste
        // do usuario. Guardamos a instancia ativa aqui pra qualquer parte do app
        // conseguir proteger seus sockets, mesmo sem referencia direta ao Service.
        @Volatile private var activeInstance: AutomBotVpnService? = null

        /**
         * Retorna true se protegeu (ou se nao ha VPN ativa — nesse caso nao ha nada a
         * proteger). CORRIGIDO: antes, quem chamava essas funcoes descartava o
         * resultado — se protect() falhasse de verdade (devolvendo false), ninguem
         * saberia, e o sintoma seria exatamente "conecta mas nao gera dado" sem pista
         * nenhuma do motivo. Agora, uma falha real vira log explicito.
         */
        fun protectSocket(socket: java.net.Socket): Boolean {
            val instance = activeInstance ?: return true // sem VPN ativa, nada a proteger
            val ok = instance.protect(socket)
            if (!ok) {
                AppLog.log(
                    "VPN: protect() FALHOU pra uma conexão real do app — ela pode estar entrando " +
                        "em loop pela própria VPN em vez de sair direto. Se o Android tiver " +
                        "\"Bloquear conexões sem VPN\" / VPN sempre ativa ligado pra este app, isso " +
                        "sobrepõe o protect() e é a causa mais provável.",
                    AppLog.Level.ERROR
                )
            }
            return ok
        }

        fun protectDatagramSocket(socket: java.net.DatagramSocket): Boolean {
            val instance = activeInstance ?: return true
            val ok = instance.protect(socket)
            if (!ok) {
                AppLog.log("VPN: protect() FALHOU pra um socket UDP real do app (mesmo risco de loop)", AppLog.Level.ERROR)
            }
            return ok
        }
    }

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpn()
                return START_NOT_STICKY
            }
            ACTION_START_OPENVPN -> {
                val configPath = intent.getStringExtra(EXTRA_OPENVPN_CONFIG_PATH)
                val connectionName = intent.getStringExtra(EXTRA_OPENVPN_CONNECTION_NAME)
                if (configPath != null && connectionName != null) startOpenVpn(configPath, connectionName)
            }
            else -> {
                val socksPort = intent?.getIntExtra(EXTRA_SOCKS_PORT, -1) ?: -1
                val dns1 = intent?.getStringExtra(EXTRA_DNS_PRIMARY) ?: "8.8.8.8"
                val dns2 = intent?.getStringExtra(EXTRA_DNS_SECONDARY) ?: "8.8.4.4"
                if (socksPort > 0) startVpn(socksPort, dns1, dns2)
            }
        }
        return START_STICKY
    }

    /**
     * CORRECAO: usuario relatou que, ao "limpar os apps recentes" com a VPN ativa,
     * o app volta ao estado original (sem conexão) na próxima vez que abre — como
     * se tivesse sido reiniciado do zero, em vez de continuar rodando em segundo
     * plano e só reabrir a tela por cima do que já estava rodando.
     *
     * Isso é um comportamento real e bem conhecido do Android: por padrão, alguns
     * fabricantes (Motorola incluso) tratam "limpar apps recentes" como um sinal
     * forte pra matar o PROCESSO INTEIRO — mesmo com um serviço em primeiro plano
     * rodando — a não ser que o serviço diga explicitamente "não me mate por
     * isso", sobrescrevendo esse método. Se o processo morre de verdade (não só a
     * Activity), o Service some junto, e reabrir o app cria tudo do zero — bate
     * exatamente com o relatado.
     *
     * Não sobrescrever esse método (deixar vazio, sem chamar stopSelf()) já é o
     * suficiente pra sinalizar a intenção de continuar rodando — combinado com o
     * pedido de isenção de otimização de bateria (Etapa 72) e o START_STICKY já
     * usado acima (se o sistema matar o processo mesmo assim sob pressão de
     * memória, o Android tenta reiniciar o serviço sozinho depois).
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        AppLog.log("VPN de sistema: app removido dos recentes — mantendo a conexão ativa em segundo plano", AppLog.Level.INFO)
        super.onTaskRemoved(rootIntent)
        // Deliberadamente NAO chama stopVpn()/stopSelf() aqui — e exatamente esse
        // comportamento padrao (que alguns fabricantes forcam por conta propria)
        // que estava causando o problema relatado.
    }

    private fun startVpn(socksPort: Int, dns1: String, dns2: String) {
        if (tunInterface != null) {
            if (activeDns1 != dns1 || activeDns2 != dns2) {
                AppLog.log("VPN de sistema: DNS mudou ($dns1, $dns2), reiniciando interface TUN", AppLog.Level.INFO)
                NativeTun2Socks.stop()
                runCatching { tunInterface?.close() }
                tunInterface = null
                // continua pra criar novo TUN abaixo
            } else if (activeSocksPort != socksPort) {
                AppLog.log("VPN de sistema: já ativa, reiniciando motor nativo com a nova porta ($socksPort)", AppLog.Level.INFO)
                NativeTun2Socks.stop()
                val fd = tunInterface?.fd
                if (fd != null) {
                    val restarted = NativeTun2Socks.start(fd, "127.0.0.1", socksPort, dns1)
                    if (restarted) activeSocksPort = socksPort
                }
                return
            } else {
                return
            }
        }

        AppLog.log("VPN de sistema: iniciando (DNS: $dns1, $dns2)", AppLog.Level.INFO)
        val tun = establishTunAndForeground(dns1, dns2) ?: return
        tunInterface = tun
        activeDns1 = dns1
        activeDns2 = dns2
        activeSocksPort = socksPort

        val started = NativeTun2Socks.start(tun.fd, "127.0.0.1", socksPort, dns1)
        if (!started) {
            AppLog.log("VPN de sistema: motor nativo falhou ao iniciar", AppLog.Level.ERROR)
        }
    }

    /**
     * Monta e estabelece a interface TUN (config de rede genérica: catch-all
     * 0.0.0.0/0, DNS público, exclui o próprio app do roteamento) + liga a
     * notificação de foreground — usado tanto pelo fluxo normal (SSH/VLESS/VMess/
     * Shadowsocks/Trojan, via motor nativo) quanto pelo OpenVPN (que pede a TUN
     * através da query NEED-OK:OPENTUN da Interface de Gerenciamento — ver
     * OpenVpnManagementClient.kt). Reaproveitada em vez de duplicada porque as duas
     * situações precisam exatamente da mesma config de rede.
     */
    private fun establishTunAndForeground(dns1: String = "8.8.8.8", dns2: String = "8.8.4.4"): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession("AutomBot Connect")
            .addAddress("10.0.0.1", 24)
            .addDnsServer(dns1)
            .addDnsServer(dns2)
            .addRoute("0.0.0.0", 0)
            .setMtu(1280)

        // CORRECAO: alem do protect() por socket (que deveria bastar sozinho, mas por
        // algum motivo nao esta sendo suficiente — usuario confirmou que nao e
        // configuracao do Android, ja que o WireGuard funciona normalmente com a
        // mesma API do sistema), excluir o PROPRIO app inteiro do roteamento da VPN
        // aqui na configuracao da interface. Isso e mais robusto: em vez de depender
        // de proteger cada socket individualmente na hora certa, o proprio Android
        // nunca captura NADA que saia do nosso processo, ponto — segunda camada de
        // seguranca, nao substitui o protect() (mantido), complementa.
        try {
            builder.addDisallowedApplication(packageName)
        } catch (e: Exception) {
            AppLog.log("VPN de sistema: falha ao excluir o próprio app do roteamento: ${e.message}", AppLog.Level.ERROR)
        }

        val tun = builder.establish()
        if (tun == null) {
            AppLog.log("VPN de sistema: falha ao estabelecer a interface TUN", AppLog.Level.ERROR)
            return null
        }

        // CORRECAO: startForeground() pode falhar por varios motivos (permissao de
        // notificacao, requisito de foregroundServiceType no Android 14+, etc.) — e
        // se falhar SEM try-catch, tudo que vem depois (o motor de roteamento
        // inteiro) nunca chega a rodar: a chave de VPN acende (o TUN ja foi
        // estabelecido acima) mas nenhum pacote e processado. Isso e o que
        // provavelmente aconteceu no teste do usuario (chave ativa, sem notificacao,
        // nenhum log de trafego). Agora, mesmo que a notificacao falhe, o motor
        // sempre inicia.
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            AppLog.log("VPN de sistema: falha ao mostrar notificação (${e.message}) — motor continua normalmente", AppLog.Level.ERROR)
        }

        // CORRECAO: sem isso, a CPU pode entrar em modo de economia assim que a
        // tela apaga ou o app sai de vista — mesmo com o servico em primeiro plano
        // — travando a entrada/saida de dados dos nossos sockets. WakeLock parcial
        // (so mantem a CPU acordada, NAO a tela) — liberado em stopVpn().
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(POWER_SERVICE) as android.os.PowerManager
                wakeLock = powerManager.newWakeLock(
                    android.os.PowerManager.PARTIAL_WAKE_LOCK,
                    "AutomBotConnect::VpnWakeLock"
                ).apply { setReferenceCounted(false) }
            }
            wakeLock?.acquire(10 * 60 * 60 * 1000L /* 10h, com limite de seguranca — sempre renovado enquanto a VPN estiver ativa */)
        } catch (e: Exception) {
            AppLog.log("VPN de sistema: falha ao adquirir WakeLock (${e.message})", AppLog.Level.ERROR)
        }

        return tun
    }

    /**
     * Inicia uma conexao OpenVPN de verdade — diferente do fluxo normal (que roda a
     * conexao real dentro do *TunnelManager e so precisa da porta SOCKS5 aqui), o
     * OpenVpnManagementClient PRECISA rodar aqui dentro do proprio Service, porque so
     * ele tem permissao de estabelecer a TUN (establish()) e proteger sockets
     * (protect()) de verdade — ver comentario completo em OpenVpnTunnelManager.kt.
     */
    private fun startOpenVpn(configPath: String, connectionName: String) {
        if (openVpnClient != null) {
            AppLog.log("VPN de sistema: já existe uma conexão OpenVPN ativa — desconecte antes de iniciar outra", AppLog.Level.ERROR)
            return
        }

        val config = com.autombot.client.protocols.openvpn.OpenVpnConnectionConfig(
            connectionName = connectionName,
            configFileName = java.io.File(configPath).name
        )

        openVpnConnectionName = connectionName
        val client = OpenVpnManagementClient(
            context = applicationContext,
            config = config,
            connectionName = connectionName,
            establishTun = {
                val tun = establishTunAndForeground()
                tunInterface = tun
                tun
            },
            protectFd = { fd ->
                // VpnService.protect() so tem overloads pra Socket/DatagramSocket/int
                // — pra proteger um FileDescriptor cru recebido do processo openvpn
                // (via ancillary data do socket de gerenciamento), precisamos do
                // numero inteiro do fd, que da pra pegar envolvendo ele num
                // ParcelFileDescriptor.
                runCatching {
                    val pfd = ParcelFileDescriptor.dup(fd)
                    val ok = protect(pfd.fd)
                    pfd.close() // dup() cria uma copia do fd — fecha so a copia, nao o original
                    ok
                }.getOrDefault(false)
            },
            onStateChange = { connected, error ->
                OpenVpnTunnelManager.reportStateChange(connectionName, connected, error)
            },
            onBytesUpdate = { rx, tx ->
                OpenVpnTunnelManager.reportBytes(connectionName, rx, tx)
            }
        )
        openVpnClient = client
        client.start()
    }

    private fun stopVpn() {
        AppLog.log("VPN de sistema: desligando", AppLog.Level.INFO)
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        NativeTun2Socks.stop()
        openVpnClient?.stop()
        openVpnClient = null
        openVpnConnectionName?.let { OpenVpnTunnelManager.reportStateChange(it, connected = false, error = null) }
        openVpnConnectionName = null
        runCatching { tunInterface?.close() }
        tunInterface = null
        activeSocksPort = null
        activeInstance = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "VPN AutomBot Connect",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }

        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("AutomBot Connect")
            .setContentText("VPN ativa")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    override fun onRevoke() {
        // Chamado quando o usuario revoga a permissao de VPN pelas configuracoes do
        // sistema (fora do app) — precisa desligar tudo tambem.
        stopVpn()
        super.onRevoke()
    }
}
