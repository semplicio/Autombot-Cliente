package com.autombot.client.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * O modo gerenciado é ativado quando o aplicativo foi configurado por um domínio.
 * Nesse perfil as configurações pertencem ao administrador do painel e devem ser
 * tratadas como somente leitura no cliente.
 */
@Composable
fun rememberManagedMode(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        context.getSharedPreferences("autombot_app", android.content.Context.MODE_PRIVATE)
            .getBoolean("managed_mode", false)
    }
}
