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
    /**
     * [dns]: servidor DNS a usar quando o motor nativo precisar resolver nomes —
     * passado para não depender do DNS padrão do sistema (que pode estar dentro do
     * próprio túnal, causando loop).
     */
    fun start(tunFd: Int, socksHost: String, socksPort: Int, dns: String = "8.8.8.8"): Boolean {
        val config = buildConfig(socksHost, socksPort, dns)
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

    // NOTAS sobre os campos do config YAML do hev-socks5-tunnel:
    //  - tunnel.ipv4: IP interno que o motor usa pra montar os pacotes que ele
    //    mesmo gera (respostas TCP/UDP de volta pro TUN). NAO e o IP da interface
    //    TUN configurada pelo VpnService (que e 10.0.0.1/24 em AutomBotVpnService).
    //    O valor 198.18.0.1 e um endereco reservado (RFC 2544) que nao conflita com
    //    redes reais — padrao usado pela propria documentacao do hev-socks5-tunnel.
    //  - tunnel.mtu: DEVE bater com o MTU configurado no VpnService.Builder
    //    (Builder().setMtu(1500) em AutomBotVpnService.kt) para que os dois lados
    //    concordem no tamanho maximo de pacote. Valores divergentes causam
    //    fragmentacao ou descarte silencioso de pacotes.
    //  - dns: quando o motor precisar resolver nomes por conta propria (raro, a
    //    maioria do DNS vem como pacote UDP da interface TUN), usa esse servidor
    //    em vez de herdar o DNS do sistema — que pode estar dentro do tunel, causando
    //    loop.
    private fun buildConfig(socksHost: String, socksPort: Int, dns: String = "8.8.8.8"): String = """
        tunnel:
          mtu: 1500
          ipv4: 198.18.0.1
        socks5:
          port: $socksPort
          address: $socksHost
          udp: udp
        dns:
          address: $dns
          port: 53
    """.trimIndent()

    private external fun nativeStart(configYaml: String, tunFd: Int): Boolean
    private external fun nativeStop()
    private external fun nativeGetStats(): LongArray
}