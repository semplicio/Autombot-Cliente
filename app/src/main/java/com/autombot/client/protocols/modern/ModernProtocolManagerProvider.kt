package com.autombot.client.protocols.modern

import android.content.Context

/** Mantém um único manager/processo moderno por processo do aplicativo. */
object ModernProtocolManagerProvider {
    @Volatile
    private var instance: ModernProtocolTunnelManager? = null

    fun get(context: Context): ModernProtocolTunnelManager {
        return instance ?: synchronized(this) {
            instance ?: ModernProtocolTunnelManager(context.applicationContext).also { instance = it }
        }
    }
}
