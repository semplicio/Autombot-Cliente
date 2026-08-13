package com.autombot.networkprobe

import java.net.Socket
import javax.net.SocketFactory
import javax.net.ssl.SSLSocketFactory

/**
 * Compatibilidade para o retorno Java de SSLSocketFactory.getDefault().
 *
 * A API estática herdada declara SocketFactory como tipo de retorno, então o
 * compilador Kotlin não enxerga diretamente o overload TLS
 * createSocket(Socket, String, Int, Boolean). Quando o receiver está tipado
 * como SocketFactory, este overload de extensão faz o cast seguro para
 * SSLSocketFactory e delega para a implementação TLS real.
 */
internal fun SocketFactory.createSocket(
    raw: Socket,
    peerHost: String,
    peerPort: Int,
    autoClose: Boolean
): Socket {
    val sslFactory = this as? SSLSocketFactory
        ?: error("SocketFactory padrão não é uma SSLSocketFactory")
    return sslFactory.createSocket(raw, peerHost, peerPort, autoClose)
}
