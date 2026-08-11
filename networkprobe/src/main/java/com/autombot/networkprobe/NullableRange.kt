package com.autombot.networkprobe

/** Compatibilidade para checagens de código HTTP nullable com o toolchain Kotlin atual. */
internal operator fun IntRange.contains(value: Int?): Boolean =
    value != null && value >= first && value <= last
