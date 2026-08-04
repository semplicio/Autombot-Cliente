package com.autombot.client.protocols.shadowsocks

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Abre uma conexao Shadowsocks pro destino pedido: socket TCP DIRETO (protegido pra
 * nao entrar em loop com a VPN de sistema — mesma correcao critica do SSH/VLESS/
 * VMess), envolve as streams com a criptografia AEAD (ShadowsocksStreams.kt) e manda
 * o endereco de destino como primeiro payload cifrado (nao existe cabecalho separado
 * como no VLESS/VMess — o proprio primeiro pedaco de dados cifrados JA E' o endereco).
 */
object ShadowsocksTransport {

    fun connect(
        config: ShadowsocksConnectionConfig,
        destHost: String,
        destPort: Int,
        protectSocket: (Socket) -> Boolean,
        timeoutMs: Int = 10_000
    ): Pair<InputStream, OutputStream> {
        val spec = ShadowsocksCrypto.specFor(config.method)
        val masterKey = ShadowsocksCrypto.deriveMasterKey(config.password, spec.keySize)

        val socket = Socket()
        // Ver comentario equivalente em VlessTransport.kt: forca o fd nativo existir
        // antes do protect(), que precisa dele pra funcionar.
        socket.bind(InetSocketAddress(0))
        val protected = protectSocket(socket)
        if (!protected) {
            throw IOException(
                "Não consegui isentar esta conexão da VPN (protect() falhou) — ela entraria em loop " +
                    "por dentro da própria VPN. Verifique se \"Bloquear conexões sem VPN\" ou VPN " +
                    "sempre ativa está desligado pra este app nas configurações do Android."
            )
        }
        try {
            socket.connect(InetSocketAddress(config.server, config.port), timeoutMs)
        } catch (e: Exception) {
            throw IOException("Não consegui conectar em ${config.server}:${config.port}: ${e.message}", e)
        }

        val out = ShadowsocksOutputStream(socket.getOutputStream(), masterKey, spec)
        val input = ShadowsocksInputStream(socket.getInputStream(), masterKey, spec)

        // Primeiro payload cifrado = endereco de destino (formato SOCKS5: tipo + endereco + porta).
        val addressHeader = ShadowsocksCrypto.encodeAddressHeader(destHost, destPort)
        out.write(addressHeader)
        out.flush()

        return input to out
    }
}
