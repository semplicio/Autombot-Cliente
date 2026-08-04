package com.autombot.client.core.tun2socks

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Cliente SOCKS5 minimo (RFC 1928), so o necessario pra CONNECT sem autenticacao —
 * o inverso do Socks5Server.kt (que e o lado servidor usado pelo SSH). Aqui o motor
 * de pacotes (Tun2SocksEngine) usa isso pra abrir, pra cada fluxo TCP capturado do
 * TUN, uma conexao atraves do proxy SOCKS5 local que o SshTunnelManager ja sobe.
 */
object Socks5Client {

    fun connect(socksHost: String, socksPort: Int, destHost: String, destPort: Int, timeoutMs: Int = 10_000): Socket {
        val socket = Socket()
        socket.connect(InetSocketAddress(socksHost, socksPort), timeoutMs)

        val out = socket.getOutputStream()
        val input = socket.getInputStream()

        // Handshake de metodos: versao 5, 1 metodo, "sem autenticacao" (0x00)
        out.write(byteArrayOf(0x05, 0x01, 0x00))
        out.flush()
        val methodReply = ByteArray(2)
        readFully(input, methodReply)
        if (methodReply[0] != 0x05.toByte() || methodReply[1] != 0x00.toByte()) {
            socket.close()
            throw IOException("Proxy SOCKS5 local recusou o handshake")
        }

        // Request CONNECT com endereco como dominio (ATYP 0x03) — mais simples e
        // funciona tanto pra hostname quanto IP em texto.
        val hostBytes = destHost.toByteArray(Charsets.US_ASCII)
        val request = ByteArray(7 + hostBytes.size)
        request[0] = 0x05; request[1] = 0x01; request[2] = 0x00; request[3] = 0x03
        request[4] = hostBytes.size.toByte()
        System.arraycopy(hostBytes, 0, request, 5, hostBytes.size)
        request[5 + hostBytes.size] = ((destPort shr 8) and 0xFF).toByte()
        request[6 + hostBytes.size] = (destPort and 0xFF).toByte()
        out.write(request)
        out.flush()

        val replyHeader = ByteArray(4)
        readFully(input, replyHeader)
        if (replyHeader[1] != 0x00.toByte()) {
            socket.close()
            throw IOException("Proxy SOCKS5 local recusou CONNECT para $destHost:$destPort (código ${replyHeader[1]})")
        }
        // Consome o endereco/porta "de bind" da resposta (tamanho varia por ATYP)
        val atyp = replyHeader[3].toInt()
        val addrLen = when (atyp) {
            0x01 -> 4
            0x03 -> input.read() + 1 // primeiro byte é o tamanho do domínio
            0x04 -> 16
            else -> 0
        }
        readFully(input, ByteArray(addrLen + 2)) // endereco + porta

        return socket
    }

    private fun readFully(input: java.io.InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val n = input.read(buffer, offset, buffer.size - offset)
            if (n == -1) throw IOException("Conexão fechada durante handshake SOCKS5")
            offset += n
        }
    }
}