package com.autombot.networkprobe

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ReportShare {
    fun share(activity: Activity, json: String) {
        val dir = File(activity.cacheDir, "shared_reports").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "autombot-network-probe-$stamp.json")
        file.writeText(json, Charsets.UTF_8)

        val uri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.fileprovider",
            file
        )

        val sendFile = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_SUBJECT, "AutomBot Network Probe")
            putExtra(Intent.EXTRA_TEXT, "Diagnóstico exportado pelo AutomBot Network Probe.")
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("AutomBot Network Probe JSON", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooser = Intent.createChooser(sendFile, "Compartilhar diagnóstico")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        activity.startActivity(chooser)
    }
}
