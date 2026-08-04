package com.autombot.client.panel

/**
 * Cliente HTTP para comunicacao com o painel AutomBot Core.
 *
 * Responsavel por:
 *  - Autoconfig: baixar todas as configs de conexao disponiveis para o usuario (SPEC.md secao 3)
 *  - Provisionamento: registrar o device id + senha gerados localmente (SPEC.md secao 4)
 *  - Sincronizacao continua: manter o app atualizado com mudancas no painel
 *
 * TODO: implementar com OkHttp, autenticacao por token + assinatura do payload
 */
class PanelWebhookClient(private val panelBaseUrl: String) {

    suspend fun fetchAutoConfig(): Result<Unit> {
        TODO("GET/POST para o painel, retornar todas as configs de conexao do usuario")
    }

    suspend fun provisionDevice(deviceId: String, password: String): Result<Unit> {
        TODO("POST deviceId+password para o painel validar e registrar o usuario")
    }
}
