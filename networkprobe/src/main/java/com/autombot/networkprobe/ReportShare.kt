package com.autombot.networkprobe

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ReportShare {
    fun share(activity: Activity, json: String) {
        val enrichedJson = DiagnosticReportEnhancer.enrich(json)
        val readableText = DiagnosticReportEnhancer.readableShareText(enrichedJson)

        val dir = File(activity.cacheDir, "shared_reports").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val jsonFile = File(dir, "autombot-network-probe-$stamp.json")
        val textFile = File(dir, "autombot-network-probe-$stamp.txt")
        jsonFile.writeText(enrichedJson, Charsets.UTF_8)
        textFile.writeText(readableText, Charsets.UTF_8)

        val jsonUri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.fileprovider",
            jsonFile
        )
        val textUri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.fileprovider",
            textFile
        )

        val streams = arrayListOf<Uri>(textUri, jsonUri)
        val clip = ClipData.newRawUri("AutomBot Network Probe TXT", textUri).apply {
            addItem(ClipData.Item(jsonUri))
        }

        val sendFiles = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_SUBJECT, "AutomBot Network Probe")
            putExtra(Intent.EXTRA_TEXT, readableText)
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, streams)
            clipData = clip
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooser = Intent.createChooser(sendFiles, "Compartilhar diagnóstico")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        activity.startActivity(chooser)
    }

    fun shareText(activity: Activity, title: String, text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        activity.startActivity(Intent.createChooser(intent, "Compartilhar manual"))
    }
}
