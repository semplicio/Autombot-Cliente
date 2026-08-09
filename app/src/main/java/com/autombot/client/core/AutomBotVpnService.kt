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
import com.autombot.client.protocols.modern.ModernProtocolManagerProvider
import com.autombot.client.protocols.openvpn.OpenVpnManagementClient
import com.autombot.client.protocols.openvpn.OpenVpnTunnelManager
import com.autombot.client.ui.MainActivity
import com.autombot.client.util.AppLog

/**
 * VpnService real do AutomBot Connect.
 *
 * A interface TUN é entregue ao HEV/tun2socks e o HEV encaminha para a porta
 * SOCKS5 local publicada pelo protocolo conectado. Hysteria2/TUIC usam exatamente
 * o mesmo caminho, com o sing-box publicando esse SOCKS5 local.
 */
class AutomBotVpnService : VpnService() {

    private var tunInterface: ParcelFileDescriptor? = null
    private var activeSocksPort: Int? = null
    private var activeDns1: String? = null
    private var activeDns2: String? = null
    private var openVpnClient: OpenVpnManagementClient? = null
    private var openVpnConnectionName: String? = null
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

        @Volatile private var activeInstance: AutomBotVpnService? = null

        val instance: AutomBotVpnService?
            get() = activeInstance

        fun protectSocket(socket: java.net.Socket): Boolean {
            val instance = activeInstance ?: return true
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
                // MainActivity centraliza os protocolos clássicos e pode emitir um
                // STOP quando ela é recriada e não encontra nenhum deles ativo.
                // Hysteria2/TUIC vivem num manager de processo separado; sem esta
                // proteção, apenas reabrir a Activity poderia matar o TUN/HEV de uma
                // sessão moderna que continua válida. O próprio fluxo moderno muda
                // o status para DISCONNECTED antes de pedir STOP, então a parada
                // intencional continua funcionando normalmente.
                val modernStillActive = runCatching {
                    ModernProtocolManagerProvider.get(applicationContext).hasActiveConnection()
                }.getOrDefault(false)
                if (modernStillActive) {
                    AppLog.log(
                        "VPN de sistema: pedido de parada ignorado porque Hysteria2/TUIC continua ativo",
                        AppLog.Level.INFO
                    )
                    return START_STICKY
                }
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

    override fun onTaskRemoved(rootIntent: Intent?) {
        AppLog.log("VPN de sistema: app removido dos recentes — mantendo a conexão ativa em segundo plano", AppLog.Level.INFO)
        super.onTaskRemoved(rootIntent)
    }

    private fun startVpn(socksPort: Int, dns1: String, dns2: String) {
        if (tunInterface != null) {
            if (activeDns1 != dns1 || activeDns2 != dns2) {
                AppLog.log("VPN de sistema: DNS mudou ($dns1, $dns2), reiniciando interface TUN", AppLog.Level.INFO)
                NativeTun2Socks.stop()
                runCatching { tunInterface?.close() }
                tunInterface = null
            } else if (activeSocksPort != socksPort) {
                AppLog.log("VPN de sistema: já ativa, reiniciando motor nativo com a nova porta ($socksPort)", AppLog.Level.INFO)
                NativeTun2Socks.stop()
                val fd = tunInterface?.fd
                if (fd != null) {
                    val hevLogPath = NativeTun2Socks.logFilePath(applicationContext)
                    val restarted = NativeTun2Socks.start(fd, "127.0.0.1", socksPort, dns1, hevLogPath)
                    if (restarted) {
                        activeSocksPort = socksPort
                        NativeTun2Socks.startTailingToAppLog(hevLogPath)
                        NativeTun2Socks.startStatsLogging()
                    }
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

        val hevLogPath = NativeTun2Socks.logFilePath(applicationContext)
        val started = NativeTun2Socks.start(tun.fd, "127.0.0.1", socksPort, dns1, hevLogPath)
        if (!started) {
            AppLog.log("VPN de sistema: motor nativo falhou ao iniciar", AppLog.Level.ERROR)
        } else {
            NativeTun2Socks.startTailingToAppLog(hevLogPath)
            NativeTun2Socks.startStatsLogging()
        }
    }

    private fun establishTunAndForeground(dns1: String = "8.8.8.8", dns2: String = "8.8.4.4"): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession("AutomBot Connect")
            .addAddress("10.0.0.1", 24)
            .addDnsServer(dns1)
            .addDnsServer(dns2)
            .addRoute("0.0.0.0", 0)
            .setMtu(1500)

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

        try {
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            AppLog.log("VPN de sistema: falha ao mostrar notificação (${e.message}) — motor continua normalmente", AppLog.Level.ERROR)
        }

        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(POWER_SERVICE) as android.os.PowerManager
                wakeLock = powerManager.newWakeLock(
                    android.os.PowerManager.PARTIAL_WAKE_LOCK,
                    "AutomBotConnect::VpnWakeLock"
                ).apply { setReferenceCounted(false) }
            }
            wakeLock?.acquire(10 * 60 * 60 * 1000L)
        } catch (e: Exception) {
            AppLog.log("VPN de sistema: falha ao adquirir WakeLock (${e.message})", AppLog.Level.ERROR)
        }

        return tun
    }

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
                runCatching {
                    val pfd = ParcelFileDescriptor.dup(fd)
                    val ok = protect(pfd.fd)
                    pfd.close()
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
        NativeTun2Socks.stopTailing()
        NativeTun2Socks.stopStatsLogging()
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
        // Evita recursão stopSelf -> onDestroy -> stopVpn -> stopSelf. Nesta fase o
        // serviço já recebeu o comando de parada e basta liberar recursos restantes.
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
        NativeTun2Socks.stopTailing()
        NativeTun2Socks.stopStatsLogging()
        runCatching { NativeTun2Socks.stop() }
        runCatching { openVpnClient?.stop() }
        openVpnClient = null
        runCatching { tunInterface?.close() }
        tunInterface = null
        activeSocksPort = null
        activeInstance = null
        super.onDestroy()
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    fun protectFd(fd: Int): Boolean = protect(fd)
}
