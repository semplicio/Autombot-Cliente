package com.autombot.networkprobe

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal const val EXTRA_USE_SAVED_CORE_PROFILE = "use_saved_core_profile"

data class CoreProtocolProfile(
    val id: String,
    val type: String,
    val host: String,
    val ports: List<Int>,
    val transport: String,
    val tls: Boolean,
    val path: String?,
    val sni: String?
)

data class CoreProfileSnapshot(
    val managerUrl: String,
    val profileName: String,
    val profileVersion: String,
    val generatedAt: String,
    val publicIp: String?,
    val protocols: List<CoreProtocolProfile>,
    val rawProfileJson: String,
    val savedAtMs: Long
) {
    fun toPreset(): CoreProbePreset? {
        if (protocols.isEmpty() && publicIp.isNullOrBlank()) return null

        val preferred = protocols.firstOrNull {
            it.type in setOf("vless", "vmess", "trojan") && it.host.isNotBlank()
        } ?: protocols.firstOrNull { it.host.isNotBlank() }

        val host = preferred?.host?.takeIf { it.isNotBlank() }
            ?: publicIp?.takeIf { it.isNotBlank() }
            ?: return null

        // Um mesmo perfil pode ter CDN para Xray e IP/domínio direto para SSH/UDP.
        // A tela principal trabalha com um host por execução, então nunca mistura
        // portas pertencentes a endpoints diferentes no mesmo teste.
        val sameHostProtocols = protocols.filter { it.host.equals(host, ignoreCase = true) }
        val tcpPorts = LinkedHashSet<Int>()
        val udpPorts = LinkedHashSet<Int>()
        sameHostProtocols.forEach { protocol ->
            protocol.ports.filter { it in 1..65535 }.forEach { port ->
                when (protocol.transport.lowercase()) {
                    "udp" -> udpPorts += port
                    else -> tcpPorts += port
                }
            }
        }

        val preferredTcp = when {
            preferred != null && preferred.transport.lowercase() != "udp" && preferred.ports.isNotEmpty() -> preferred.ports.first()
            443 in tcpPorts -> 443
            tcpPorts.isNotEmpty() -> tcpPorts.first()
            else -> 443
        }
        val preferredUdp = when {
            443 in udpPorts -> 443
            udpPorts.isNotEmpty() -> udpPorts.first()
            else -> 443
        }

        val path = sameHostProtocols.firstOrNull {
            it.transport.equals("websocket", ignoreCase = true) && !it.path.isNullOrBlank()
        }?.path ?: "/"

        return CoreProbePreset(
            profileName = profileName,
            profileVersion = profileVersion,
            host = host,
            tcpPort = preferredTcp,
            udpPort = preferredUdp,
            webSocketPath = path,
            extraTcpPorts = tcpPorts.filter { it != preferredTcp }.take(8),
            extraUdpPorts = udpPorts.filter { it != preferredUdp }.take(8),
            protocolCount = protocols.size
        )
    }

    companion object {
        fun fromServerJson(managerUrl: String, rawJson: String, savedAtMs: Long = System.currentTimeMillis()): CoreProfileSnapshot {
            val root = JSONObject(rawJson)
            val protocolsJson = root.optJSONArray("protocols") ?: JSONArray()
            val protocols = mutableListOf<CoreProtocolProfile>()
            for (index in 0 until protocolsJson.length()) {
                val item = protocolsJson.optJSONObject(index) ?: continue
                val portsArray = item.optJSONArray("ports") ?: JSONArray()
                val ports = mutableListOf<Int>()
                for (p in 0 until portsArray.length()) {
                    val port = portsArray.optInt(p, -1)
                    if (port in 1..65535 && port !in ports) ports += port
                }
                val host = item.optString("host")
                if (host.isBlank() || ports.isEmpty()) continue
                protocols += CoreProtocolProfile(
                    id = item.optString("id", item.optString("type", "unknown")),
                    type = item.optString("type", "unknown"),
                    host = host,
                    ports = ports,
                    transport = item.optString("transport", "tcp"),
                    tls = item.optBoolean("tls", false),
                    path = item.optString("path").takeIf { it.isNotBlank() && it != "null" },
                    sni = item.optString("sni").takeIf { it.isNotBlank() && it != "null" }
                )
            }

            return CoreProfileSnapshot(
                managerUrl = managerUrl,
                profileName = root.optString("profile_name", "AutomBot Core"),
                profileVersion = root.optString("profile_version", "sem-versao"),
                generatedAt = root.optString("generated_at", ""),
                publicIp = root.optString("public_ip").takeIf { it.isNotBlank() && it != "null" },
                protocols = protocols,
                rawProfileJson = rawJson,
                savedAtMs = savedAtMs
            )
        }
    }
}

data class CoreProbePreset(
    val profileName: String,
    val profileVersion: String,
    val host: String,
    val tcpPort: Int,
    val udpPort: Int,
    val webSocketPath: String,
    val extraTcpPorts: List<Int>,
    val extraUdpPorts: List<Int>,
    val protocolCount: Int
)

class CoreProfileStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveProfile(managerUrl: String, rawProfileJson: String): CoreProfileSnapshot {
        val now = System.currentTimeMillis()
        val parsed = CoreProfileSnapshot.fromServerJson(managerUrl, rawProfileJson, now)
        prefs.edit()
            .putString(KEY_MANAGER_URL, managerUrl)
            .putString(KEY_PROFILE_JSON, rawProfileJson)
            .putLong(KEY_SAVED_AT, now)
            .apply()
        return parsed
    }

    fun loadProfile(): CoreProfileSnapshot? {
        val manager = prefs.getString(KEY_MANAGER_URL, null) ?: return null
        val json = prefs.getString(KEY_PROFILE_JSON, null) ?: return null
        val savedAt = prefs.getLong(KEY_SAVED_AT, 0L)
        return runCatching { CoreProfileSnapshot.fromServerJson(manager, json, savedAt) }.getOrNull()
    }

    fun managerUrl(): String = prefs.getString(KEY_MANAGER_URL, "") ?: ""

    fun clearProfile() {
        prefs.edit()
            .remove(KEY_MANAGER_URL)
            .remove(KEY_PROFILE_JSON)
            .remove(KEY_SAVED_AT)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "automcore_probe_profile"
        const val KEY_MANAGER_URL = "manager_url"
        const val KEY_PROFILE_JSON = "profile_json"
        const val KEY_SAVED_AT = "saved_at"
    }
}

/**
 * O token administrativo nunca é persistido. Apenas o token probe:read emitido pelo
 * Core fica salvo, cifrado com uma chave AES mantida no Android Keystore.
 */
class SecureProbeTokenStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(token: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString(KEY_CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    fun load(): String? {
        val encryptedB64 = prefs.getString(KEY_CIPHERTEXT, null) ?: return null
        val ivB64 = prefs.getString(KEY_IV, null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val iv = Base64.decode(ivB64, Base64.NO_WRAP)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            val encrypted = Base64.decode(encryptedB64, Base64.NO_WRAP)
            cipher.doFinal(encrypted).toString(Charsets.UTF_8)
        }.getOrNull()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private companion object {
        const val PREFS_NAME = "automcore_probe_secret"
        const val KEY_CIPHERTEXT = "probe_token_ciphertext"
        const val KEY_IV = "probe_token_iv"
        const val KEY_ALIAS = "AutomBotNetworkProbeScopedToken"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
