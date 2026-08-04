package com.autombot.client.provisioning

import java.util.UUID

/**
 * Provisionamento automatico de usuario (ver SPEC.md secao 4).
 *
 * Fluxo:
 *  1. Gera/recupera um UUID persistente proprio do app (NAO usar Android ID/IMEI)
 *  2. Gera senha aleatoria local
 *  3. Envia (login=uuid, senha) para o painel via webhook autenticado
 *  4. Painel valida e registra o dispositivo como usuario unico
 */
class DeviceProvisioning {

    fun getOrCreateDeviceId(): String {
        // TODO: persistir em EncryptedSharedPreferences, gerar apenas na primeira execucao
        return UUID.randomUUID().toString()
    }

    fun generateRandomPassword(length: Int = 24): String {
        val chars = ('A'..'Z') + ('a'..'z') + ('0'..'9')
        return (1..length).map { chars.random() }.joinToString("")
    }
}
