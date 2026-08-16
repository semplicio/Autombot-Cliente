from pathlib import Path

path = Path("app/src/main/java/com/autombot/client/ui/MainActivity.kt")
text = path.read_text()
old = '''        }.onSuccess { estado ->
            managedAccountUser = estado.usuario
            managedAccountStatus = estado.status
            managedAccountExpiry = estado.expiresAt.orEmpty()
            appPrefs.edit()
                .putString("managed_usuario", estado.usuario)
                .putString("managed_account_status", estado.status)
                .putString("managed_expira_em", estado.expiresAt.orEmpty())
                .apply()
            com.autombot.client.util.AppLog.log(
                "Estado da conta atualizado: ${estado.usuario} = ${estado.status}" +
                    (estado.source?.let { " (fonte: $it)" } ?: ""),
                com.autombot.client.util.AppLog.Level.INFO
            )
'''
new = '''        }.onSuccess { estado ->
            val localTrialExpired = appPrefs.getBoolean("managed_trial_local_expired", false)
            val originalTrialExpiry = appPrefs.getString("managed_trial_server_expiry", "").orEmpty()
            val expiryChanged = !estado.expiresAt.isNullOrBlank() &&
                originalTrialExpiry.isNotBlank() && estado.expiresAt != originalTrialExpiry
            val renewedAfterTrial = localTrialExpired && estado.active && expiryChanged
            val effectiveStatus = if (localTrialExpired && !renewedAfterTrial) "expirado" else estado.status

            managedAccountUser = estado.usuario
            managedAccountStatus = effectiveStatus
            managedAccountExpiry = estado.expiresAt.orEmpty()

            val editor = appPrefs.edit()
                .putString("managed_usuario", estado.usuario)
                .putString("managed_account_status", effectiveStatus)
                .putString("managed_expira_em", estado.expiresAt.orEmpty())
            if (renewedAfterTrial) {
                editor
                    .putBoolean("managed_is_trial", false)
                    .putBoolean("managed_trial_local_expired", false)
                    .putBoolean("managed_trial_expiry_sent", false)
                    .remove("managed_trial_expires_at_ms")
            }
            editor.apply()
            com.autombot.client.util.AppLog.log(
                "Estado da conta atualizado: ${estado.usuario} = $effectiveStatus" +
                    (estado.source?.let { " (fonte: $it)" } ?: ""),
                com.autombot.client.util.AppLog.Level.INFO
            )
'''
if text.count(old) != 1:
    raise SystemExit(f"Trecho esperado não encontrado de forma única: {text.count(old)}")
path.write_text(text.replace(old, new, 1))
print("Bloqueio do trial preservado no estado visível do dashboard")
