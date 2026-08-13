package com.autombot.client.provisioning

import android.content.Context
import android.provider.Settings
import java.security.MessageDigest
import java.util.UUID

/**
 * Provisionamento automático da conta gerenciada.
 *
 * A identidade primária agora vem de Settings.Secure.ANDROID_ID. Em Android 8+
 * esse valor é estável para a combinação aparelho + usuário Android + chave de
 * assinatura do app, portanto normalmente permanece o mesmo após limpar dados ou
 * reinstalar o APK assinado com a mesma chave. Isso é bem diferente de um UUID
 * salvo apenas em SharedPreferences, que desaparece quando os dados do app são
 * apagados.
 *
 * Observação: nenhum identificador Android é absolutamente imutável. Factory reset,
 * troca de usuário/perfil Android ou mudança da chave de assinatura pode produzir
 * outro ANDROID_ID. O servidor continua sendo a autoridade final para impedir novo
 * trial e restaurar a conta já vinculada ao device_id.
 */
class DeviceProvisioning(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Identificador estável do aparelho para o vínculo servidor <-> cliente.
     * Usa ANDROID_ID quando disponível. O UUID persistido fica apenas como fallback
     * para aparelhos/ROMs que não entreguem um ANDROID_ID utilizável.
     */
    fun getOrCreateDeviceId(): String {
        val androidId = runCatching {
            Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()?.trim()?.lowercase()

        if (!androidId.isNullOrBlank() && androidId != LEGACY_BROKEN_ANDROID_ID) {
            return androidId
        }

        val existing = prefs.getString(KEY_FALLBACK_DEVICE_ID, null)
        if (!existing.isNullOrBlank()) return existing

        val fresh = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_FALLBACK_DEVICE_ID, fresh).apply()
        return fresh
    }

    fun generateRandomPassword(length: Int = 16): String {
        val chars = ('A'..'Z') + ('a'..'z') + ('0'..'9')
        return (1..length).map { chars.random() }.joinToString("")
    }

    /**
     * Login determinístico derivado do device_id.
     *
     * O usuário Linux do AutomBot Core precisa começar por letra e ter no máximo
     * 32 caracteres. Por isso o login não pode ser o ANDROID_ID cru em todos os
     * aparelhos (ele pode começar por número). O prefixo "d" mantém a relação 1:1:
     * o mesmo device_id sempre gera exatamente o mesmo login.
     */
    fun generateAccountUsername(deviceId: String = getOrCreateDeviceId()): String {
        val compact = deviceId.lowercase().filter { it.isLetterOrDigit() }
        val body = if (compact.isNotBlank() && compact.length <= 31) {
            compact
        } else {
            MessageDigest.getInstance("SHA-256")
                .digest(deviceId.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .take(31)
        }
        return "d$body".take(32)
    }

    companion object {
        private const val PREFS_NAME = "autombot_device_provisioning"
        private const val KEY_FALLBACK_DEVICE_ID = "fallback_device_id"
        private const val LEGACY_BROKEN_ANDROID_ID = "9774d56d682e549c"
    }
}
