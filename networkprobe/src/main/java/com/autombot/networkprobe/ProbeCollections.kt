package com.autombot.networkprobe

/**
 * Overload específico usado pelo Network Probe para evitar a ambiguidade de
 * resolução entre sumOf(Int) e sumOf(Long) neste toolchain Kotlin/Gradle.
 */
internal inline fun List<ProbeResult>.sumOf(selector: (ProbeResult) -> Int): Int {
    var total = 0
    for (item in this) {
        total += selector(item)
    }
    return total
}
