package com.autombot.client.ui.modern

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import com.autombot.client.core.AutomBotVpnService
import com.autombot.client.protocols.modern.ModernProtocolManagerProvider
import com.autombot.client.protocols.modern.ModernProtocolStatus
import com.autombot.client.protocols.modern.ModernProtocolType
import com.autombot.client.ui.theme.AutomBotClientTheme
import com.autombot.client.ui.theme.AutomBotColors as C

/**
 * Tela isolada da primeira fase Hysteria2/TUIC.
 *
 * Mantém o MainActivity estável enquanto validamos o novo core em aparelhos reais.
 * Depois da validação, os mesmos StateFlows podem ser agregados ao Dashboard.
 */
class ModernProtocolActivity : ComponentActivity() {
    private val manager by lazy { ModernProtocolManagerProvider.get(applicationContext) }
    private var pendingSocksPort: Int? = null
    private var startedModernVpnPort: Int? = null

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            pendingSocksPort?.let(::startSystemVpn)
        }
        pendingSocksPort = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(7, 17, 27)
        window.navigationBarColor = android.graphics.Color.rgb(5, 11, 18)

        val type = ModernProtocolType.fromId(intent.getStringExtra(EXTRA_PROTOCOL_ID).orEmpty())
        if (type == null) {
            finish()
            return
        }

        setContent {
            AutomBotClientTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = C.Background) {
                    ModernProtocolActivityContent(type)
                }
            }
        }
    }

    @Composable
    private fun ModernProtocolActivityContent(type: ModernProtocolType) {
        val connections by manager.connections.collectAsState()
        var adding by remember { mutableStateOf(false) }
        val active = connections.firstOrNull {
            it.status == ModernProtocolStatus.CONNECTED && it.localSocksPort != null
        }

        LaunchedEffect(active?.localSocksPort) {
            val port = active?.localSocksPort
            if (port != null && port != startedModernVpnPort) {
                requestAndStartSystemVpn(port)
            } else if (port == null && startedModernVpnPort != null) {
                stopSystemVpn()
            }
        }

        if (adding) {
            ModernProtocolAddScreen(
                manager = manager,
                type = type,
                onBack = { adding = false },
                onSaved = { adding = false }
            )
        } else {
            ModernProtocolScreen(
                manager = manager,
                type = type,
                onBack = { finish() },
                onAddProfile = { adding = true },
                onViewLog = {
                    Toast.makeText(
                        this,
                        "Os eventos de ${type.displayName} também aparecem em Mais > Logs.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            )
        }
    }

    private fun requestAndStartSystemVpn(port: Int) {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            pendingSocksPort = port
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            startSystemVpn(port)
        }
    }

    private fun startSystemVpn(port: Int) {
        val serviceIntent = Intent(this, AutomBotVpnService::class.java).apply {
            action = AutomBotVpnService.ACTION_START
            putExtra(AutomBotVpnService.EXTRA_SOCKS_PORT, port)
            putExtra(AutomBotVpnService.EXTRA_DNS_PRIMARY, "8.8.8.8")
            putExtra(AutomBotVpnService.EXTRA_DNS_SECONDARY, "8.8.4.4")
        }
        startService(serviceIntent)
        startedModernVpnPort = port
    }

    private fun stopSystemVpn() {
        startService(Intent(this, AutomBotVpnService::class.java).apply {
            action = AutomBotVpnService.ACTION_STOP
        })
        startedModernVpnPort = null
    }

    companion object {
        const val EXTRA_PROTOCOL_ID = "modern_protocol_id"
    }
}
