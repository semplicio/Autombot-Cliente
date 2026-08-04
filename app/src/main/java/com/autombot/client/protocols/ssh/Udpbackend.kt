package com.autombot.client.protocols.ssh

/**
 * Contrato que cada protocolo (SSH/VLESS/VMess/Shadowsocks) implementa pra dar
 * suporte a UDP ASSOCIATE de verdade no Socks5Server.kt local. Ver comentário no
 * Socks5Server.kt pra entender o fluxo completo (RFC 1928, seção UDP ASSOCIATE).
 */
typealias UdpAssociateOpener =
    suspend (destHost: String, destPort: Int, onIncoming: (ByteArray) -> Unit) -> UdpBackendSession?

/** Uma "sessão" de UDP aberta através de algum protocolo, pra um destino específico. */
interface UdpBackendSession {
    /** Manda um datagrama pro destino através do protocolo. */
    suspend fun send(payload: ByteArray)

    /** Encerra a sessão (libera recursos do protocolo, ex: fecha WebSocket/socket). */
    fun close()
}