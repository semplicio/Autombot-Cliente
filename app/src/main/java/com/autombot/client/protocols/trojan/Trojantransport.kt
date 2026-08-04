package com.autombot.client.protocols.trojan

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

/**
 * Abre uma conexao Trojan pro destino pedido: TCP direto -> TLS -> cabecalho de
 * requisicao (TrojanProtocol.buildRequestHeader). Bem mais simples que VLESS/VMess:
 * não tem WebSocket nem cifra própria — depois do cabeçalho inicial, os dados fluem
 * crus (a criptografia inteira já é feita pelo TLS por baixo).
 */
object TrojanTransport {

    fun connect(
        config: TrojanConnectionConfig,
        destHost: String,
        destPort: Int,
        protectSocket: (Socket) -> Boolean,
        timeoutMs: Int = 10_000
    ): Pair<InputStream, OutputStream> {
        val socket = Socket()
        // Ver comentario equivalente em VlessTransport.kt: forca o fd nativo existir
        // antes do protect(), que precisa dele pra funcionar.
        socket.bind(InetSocketAddress(0))
        if (!protectSocket(socket)) {
            throw IOException(
                "Não consegui isentar esta conexão da VPN (protect() falhou) — verifique " +
                    "se \"Bloquear conexões sem VPN\" está desligado pra este app."
            )
        }
        try {
            socket.connect(InetSocketAddress(config.server, config.port), timeoutMs)
        } catch (e: Exception) {
            throw IOException("Não consegui conectar em ${config.server}:${config.port}: ${e.message}", e)
        }

        val sslContext = SSLContext.getInstance("TLS")
        if (config.allowInsecure) {
            // INCERTO/arriscado por natureza: aceita QUALQUER certificado, inclusive
            // autoassinado ou de servidor errado — só existe pra bater com painéis
            // Trojan que usam certificado autoassinado de propósito. Se o usuário não
            // marcou isso no link (allowInsecure=1), usamos a validação padrão normal.
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())
        } else {
            sslContext.init(null, null, null)
        }

        val sslSocket = sslContext.socketFactory.createSocket(socket, config.server, config.port, true) as SSLSocket
        val sni = config.sni.ifBlank { config.server }
        val params: SSLParameters = sslSocket.sslParameters
        params.serverNames = listOf(SNIHostName(sni))
        sslSocket.sslParameters = params
        sslSocket.startHandshake()

        val out = sslSocket.outputStream
        val input = sslSocket.inputStream

        val header = TrojanProtocol.buildRequestHeader(config.password, destHost, destPort, TrojanProtocol.CMD_CONNECT)
        out.write(header)
        out.flush()

        return input to out
    }
}