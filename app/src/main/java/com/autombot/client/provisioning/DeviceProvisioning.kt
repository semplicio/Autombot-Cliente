package com.autombot.client.provisioning

import android.content.Context
import java.util.UUID

/**
 * Provisionamento automatico de usuario (ver SPEC.md secao 4 e integração com
 * o painel AutomBot Core, api/v1/teste.php + api/v1/configs.php).
 *
 * Fluxo (modo gerenciado, "Já tenho um domínio"):
 *  1. Gera/recupera um UUID persistente proprio do app (NAO usar Android ID/IMEI,
 *     que tem restricoes de privacidade e pode mudar)
 *  2. Gera um login + senha aleatorios localmente, no formato que o painel exige
 *  3. Envia (device_id, usuario, senha) pro painel via POST /api/v1/teste.php
 *  4. Painel valida (throttle de 12h por device_id), cria a conta de verdade
 *     na VPS via automcore, devolve confirmacao
 *  5. App busca as configs prontas via GET /api/v1/configs.php?usuario=X
 *
 * CORRECAO: a versao anterior desse arquivo gerava um UUID NOVO a cada
 * chamada de getOrCreateDeviceId() — nunca persistia nada, apesar do nome e
 * do comentario dizerem o contrario. Isso quebraria o throttle de 12h do
 * painel (cada tentativa pareceria um dispositivo diferente) e faria o app
 * "esquecer" quem ele e a cada reinicio. Agora usa SharedPreferences comum
 * (nao e dado sensivel — e so um identificador local, nao senha nem token).
 */
class DeviceProvisioning(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** UUID persistente — gerado uma unica vez, na primeira chamada, e reaproveitado depois. */
    fun getOrCreateDeviceId(): String {
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (existing != null) return existing

        val fresh = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, fresh).apply()
        return fresh
    }

    fun generateRandomPassword(length: Int = 16): String {
        val chars = ('A'..'Z') + ('a'..'z') + ('0'..'9')
        return (1..length).map { chars.random() }.joinToString("")
    }

    /**
     * Gera um login valido pro formato que o painel exige (ver api/v1/teste.php):
     * so letras minusculas/numeros/underscore, comecando com letra, 3 a 32
     * caracteres — regex ^[a-z][a-z0-9_]{2,31}$. Um UUID cru NAO bate com
     * isso (tem tracos e pode comecar com numero), por isso geramos algo a
     * parte em vez de reaproveitar o device_id como login.
     */
    fun generateAccountUsername(): String {
        val chars = ('a'..'z') + ('0'..'9')
        val suffix = (1..10).map { chars.random() }.joinToString("")
        return "ac$suffix"
    }

    companion object {
        private const val PREFS_NAME = "autombot_device_provisioning"
        private const val KEY_DEVICE_ID = "device_id"
    }
}
