from pathlib import Path
import re

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

pattern = re.compile(
    r'(?P<i>^[ \t]+)val localTrialExpired = appPrefs\.getBoolean\("managed_trial_local_expired", false\)\n'
    r'(?P=i)val originalTrialExpiry = appPrefs\.getString\("managed_trial_server_expiry", ""\)\.orEmpty\(\)\n'
    r'(?P=i)val expiryChanged = !estado\.expiresAt\.isNullOrBlank\(\) &&\n'
    r'(?P=i)    originalTrialExpiry\.isNotBlank\(\) && estado\.expiresAt != originalTrialExpiry\n'
    r'(?P=i)val renewedAfterTrial = localTrialExpired && estado\.active && expiryChanged\n'
    r'(?P=i)val effectiveStatus = if \(localTrialExpired && !renewedAfterTrial\) "expirado" else estado\.status\n',
    re.M,
)

def repl(m):
    i = m.group('i')
    return (
        f'{i}val isTrial = appPrefs.getBoolean("managed_is_trial", false)\n'
        f'{i}val localTrialExpired = appPrefs.getBoolean("managed_trial_local_expired", false)\n'
        f'{i}val originalTrialExpiry = appPrefs.getString("managed_trial_server_expiry", "").orEmpty()\n'
        f'{i}val renewedTrial = isTrial && estado.active &&\n'
        f'{i}    managedTrialWasRenewed(originalTrialExpiry, estado.expiresAt)\n'
        f'{i}val effectiveStatus = if (localTrialExpired && !renewedTrial) "expirado" else estado.status\n'
    )

s, count = pattern.subn(repl, s)
if count != 2:
    raise SystemExit(f"esperava 2 blocos de status, achei {count}")
s = s.replace('if (renewedAfterTrial) {', 'if (renewedTrial) {')

p.write_text(s)
print("Renovação antecipada do trial passa a cancelar o prazo local de 2 horas")
