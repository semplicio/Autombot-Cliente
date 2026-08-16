from pathlib import Path

p = Path("app/src/main/java/com/autombot/client/ui/MainActivity.kt")
s = p.read_text()

anchor = '''private fun managedStatusAllowsConnection(status: String): Boolean {
    val normalized = status.trim().lowercase()
    return normalized.isBlank() || normalized in setOf("ativo", "active", "ok")
}
'''
helper = anchor + '''
private fun managedExpiryMillis(raw: String): Long? {
    if (raw.isBlank()) return null
    val patterns = listOf(
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd"
    )
    return patterns.firstNotNullOfOrNull { pattern ->
        runCatching {
            java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault()).apply {
                isLenient = false
            }.parse(raw)?.time
        }.getOrNull()
    }
}

private fun managedTrialWasRenewed(originalExpiry: String, currentExpiry: String?): Boolean {
    if (originalExpiry.isBlank() || currentExpiry.isNullOrBlank()) return false
    val originalMs = managedExpiryMillis(originalExpiry)
    val currentMs = managedExpiryMillis(currentExpiry)
    return if (originalMs != null && currentMs != null) {
        currentMs > originalMs + 60_000L
    } else {
        currentExpiry.trim() != originalExpiry.trim()
    }
}
'''
if s.count(anchor) != 1:
    raise SystemExit("anchor helper não encontrado de forma única")
s = s.replace(anchor, helper, 1)

old_root = '''                val localTrialExpired = appPrefs.getBoolean("managed_trial_local_expired", false)
                val originalTrialExpiry = appPrefs.getString("managed_trial_server_expiry", "").orEmpty()
                val expiryChanged = !estado.expiresAt.isNullOrBlank() &&
                    originalTrialExpiry.isNotBlank() && estado.expiresAt != originalTrialExpiry
                val renewedAfterTrial = localTrialExpired && estado.active && expiryChanged
                val effectiveStatus = if (localTrialExpired && !renewedAfterTrial) "expirado" else estado.status
'''
new_root = '''                val isTrial = appPrefs.getBoolean("managed_is_trial", false)
                val localTrialExpired = appPrefs.getBoolean("managed_trial_local_expired", false)
                val originalTrialExpiry = appPrefs.getString("managed_trial_server_expiry", "").orEmpty()
                val renewedTrial = isTrial && estado.active &&
                    managedTrialWasRenewed(originalTrialExpiry, estado.expiresAt)
                val effectiveStatus = if (localTrialExpired && !renewedTrial) "expirado" else estado.status
'''
if s.count(old_root) != 2:
    raise SystemExit(f"esperava 2 blocos de status, achei {s.count(old_root)}")
s = s.replace(old_root, new_root)
s = s.replace('if (renewedAfterTrial) {', 'if (renewedTrial) {')

p.write_text(s)
print("Renovação antecipada do trial passa a cancelar o prazo local de 2 horas")
