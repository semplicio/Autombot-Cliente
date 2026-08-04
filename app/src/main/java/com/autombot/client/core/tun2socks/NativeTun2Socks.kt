package com.autombot.client.core.tun2socks

/**
 * Ponte pro motor de tun2socks NATIVO (biblioteca C de terceiros hev-socks5-tunnel,
 * https://github.com/heiher/hev-socks5-tunnel), no lugar do Tun2SocksEngine.kt
 * (motor escrito do zero em Kotlin, mantido no projeto só como referência histórica
 * — não é mais usado pela VPN de sistema).
 *
 * Motivo da troca: o motor próprio em Kotlin foi acumulando bug atrás de bug ao
 * longo do desenvolvimento (checksum, opção MSS do TCP, protect() com fd inválido,
 * bytes de endereço IP com sinal errado, UDP genuíno nunca chegando a existir de
 * verdade) — cada um encontrado só depois de testar em aparelho real. A biblioteca
 * nativa usada aqui é a mesma classe de motor usada por apps de produção (NekoBox,
 * Matsuri e outros clientes VLESS/VMess/Shadowsocks populares), já testada por uma
 * comunidade inteira, com TCP e UDP genuíno completos (IPv4/IPv6).
 *
 * IMPORTANTE — pré-requisito antes de compilar: a biblioteca nativa precisa ser
 * baixada e pré-compilada manualmente uma vez. Ver
 * app/src/main/cpp/README.md pros passos exatos.
 */
object NativeTun2Socks {
    init {
        System.loadLibrary("autombot_tun2socks")
    }

    /**
     * Inicia o túnel — roda numa thread nativa separada (não bloqueia quem chama).
     * [tunFd] deve vir de um VpnService.Builder().establish() já estabelecido (o
     * mesmo file descriptor que qualquer motor de VPN precisa).
     * [socksHost]/[socksPort] apontam pro proxy SOCKS5 local que cada protocolo
     * (SSH/VLESS/VMess/Shadowsocks) já expõe — igual ao Tun2SocksEngine antigo usava.
     */
    fun start(tunFd: Int, socksHost: String, socksPort: Int): Boolean {
        val config = buildConfig(socksHost, socksPort)
        return nativeStart(config, tunFd)
    }

    /** Para o túnel. Bloqueia até a thread nativa terminar de verdade — chamar fora da main thread. */
    fun stop() = nativeStop()

    /**
     * [0] = bytes transmitidos, [1] = bytes recebidos.
     * ATENCAO: a assinatura real de hev_socks5_tunnel_stats() ainda precisa ser
     * conferida contra o cabeçalho da biblioteca (ver app/src/main/cpp/README.md,
     * passo 4) — se for diferente do suposto, isso pode retornar valores errados
     * até a ponte JNI ser ajustada.
     */
    fun stats(): LongArray = nativeGetStats()

    // CORRECAO (comparado ao Tun2SocksEngine.kt antigo): endereco/porta do proxy
    // SOCKS5 sao interpolados direto numa string, sem escapar — como quem gera esse
    // config sempre somos nos mesmos (nunca dado vindo de fora/usuario), nao ha risco
    // de injecao aqui, mas vale lembrar se um dia isso mudar.
    // CORRECAO: esse valor precisa bater EXATAMENTE com o MTU configurado na
    // interface TUN de verdade (Builder().setMtu(1500) em AutomBotVpnService.kt) —
    // antes estava em 8500 (valor arbitrario, nunca confirmado contra documentacao
    // real do hev-socks5-tunnel, ver aviso na Etapa 61 do SPEC.md), divergente do
    // MTU real da interface. Se os dois nao baterem, o motor pode montar pacotes
    // maiores do que a interface aceita de verdade, ou processar errado o que
    // recebe dela — pode ser parte do motivo da conexao "cair" ao tentar navegar.
    private fun buildConfig(socksHost: String, socksPort: Int): String = """
        tunnel:
          mtu: 1500
          ipv4: 198.18.0.1
        socks5:
          port: $socksPort
          address: $socksHost
          udp: udp
    """.trimIndent()

    private external fun nativeStart(configYaml: String, tunFd: Int): Boolean
    private external fun nativeStop()
    private external fun nativeGetStats(): LongArray
}