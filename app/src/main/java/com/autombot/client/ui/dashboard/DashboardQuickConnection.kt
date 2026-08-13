package com.autombot.client.ui.dashboard

/**
 * Resumo da última conexão usada no Dashboard.
 *
 * Mantém somente identidade/estado visual. Credenciais e configuração completa
 * continuam nos managers específicos de cada protocolo.
 */
data class DashboardQuickConnection(
    val protocolId: String,
    val displayName: String,
    val connectionName: String,
    val detail: String,
    val connected: Boolean,
    val busy: Boolean,
    val statusLabel: String
) {
    val key: String get() = "$protocolId::$connectionName"
}
