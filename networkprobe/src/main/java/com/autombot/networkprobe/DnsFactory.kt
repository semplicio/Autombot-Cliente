package com.autombot.networkprobe

import java.net.InetAddress
import okhttp3.Dns

/**
 * Adapter for OkHttp 4.x Dns, whose interface cannot be instantiated directly
 * with constructor-like lambda syntax on this Kotlin toolchain.
 */
@Suppress("FunctionName")
internal fun Dns(resolver: (String) -> List<InetAddress>): Dns =
    object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = resolver(hostname)
    }
