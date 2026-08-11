package com.maritimefatigue.ai

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private var healthClient: HealthConnectClient? = null
    private var pendingHealthSync = false
    private var pendingSaveText: String? = null

    private val healthPermissions = setOf(
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class)
    )

    private val permissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        if (pendingHealthSync) {
            pendingHealthSync = false
            if (granted.containsAll(healthPermissions)) readHealthData()
            else sendHealthError("Health Connect permission was not granted. Manual or file import remains available.")
        }
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
            val name = uri.lastPathSegment ?: "wearable_file"
            val js = "window.onWearableFileImported(${JSONObject.quote(text)}, ${JSONObject.quote(name)});"
            webView.post { webView.evaluateJavascript(js, null) }
        } catch (e: Exception) {
            sendHealthError("Could not read wearable file: ${e.message}")
        }
    }

    private val saveLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
        val text = pendingSaveText
        pendingSaveText = null
        if (uri == null || text == null) return@registerForActivityResult
        try {
            contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(text) }
            webView.post { webView.evaluateJavascript("window.onNativeMessage('Report saved successfully.');", null) }
        } catch (e: Exception) {
            sendHealthError("Could not save report: ${e.message}")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        healthClient = if (HealthConnectClient.getSdkStatus(this) == HealthConnectClient.SDK_AVAILABLE) {
            HealthConnectClient.getOrCreate(this)
        } else null

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            settings.setSupportZoom(false)
            webChromeClient = WebChromeClient()
            webViewClient = WebViewClient()
            addJavascriptInterface(AndroidBridge(), "Android")
            loadUrl("file:///android_asset/index.html")
        }
        setContentView(webView)
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun syncHealthConnect() {
            runOnUiThread { startHealthSync() }
        }

        @JavascriptInterface
        fun importWearableFile() {
            runOnUiThread { importLauncher.launch(arrayOf("application/json", "text/csv", "text/plain", "*/*")) }
        }

        @JavascriptInterface
        fun saveReport(text: String) {
            pendingSaveText = text
            runOnUiThread { saveLauncher.launch("Seafarer_Fatigue_Assessment.txt") }
        }

        @JavascriptInterface
        fun openHealthSettings() {
            runOnUiThread {
                try {
                    startActivity(Intent("android.health.connect.action.HEALTH_HOME_SETTINGS"))
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }
            }
        }

        @JavascriptInterface
        fun platformInfo(): String = "Android offline edition 3.0"
    }

    private fun startHealthSync() {
        val client = healthClient
        if (client == null) {
            sendHealthError("Health Connect is unavailable on this device. Use wearable JSON/CSV import or manual entry.")
            return
        }
        lifecycleScope.launch {
            try {
                val granted = client.permissionController.getGrantedPermissions()
                if (granted.containsAll(healthPermissions)) readHealthData()
                else {
                    pendingHealthSync = true
                    permissionLauncher.launch(healthPermissions)
                }
            } catch (e: Exception) {
                sendHealthError("Health Connect permission check failed: ${e.message}")
            }
        }
    }

    private fun readHealthData() {
        val client = healthClient ?: return
        lifecycleScope.launch {
            try {
                val now = Instant.now()
                val sleepStart = now.minus(36, ChronoUnit.HOURS)
                val physiologyStart = now.minus(7, ChronoUnit.DAYS)

                val sleepRecords = client.readRecords(
                    ReadRecordsRequest(
                        recordType = SleepSessionRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(sleepStart, now),
                        ascendingOrder = false,
                        pageSize = 50
                    )
                ).records

                // Sum sessions ending in the last 24 hours. This captures split sleep / naps.
                val cutoff24 = now.minus(24, ChronoUnit.HOURS)
                val recentSleep = sleepRecords.filter { it.endTime.isAfter(cutoff24) }
                val sleepHours = recentSleep.sumOf {
                    Duration.between(it.startTime, it.endTime).toMinutes().toDouble() / 60.0
                }.coerceAtMost(14.0)

                val restingRecords = client.readRecords(
                    ReadRecordsRequest(
                        recordType = RestingHeartRateRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(physiologyStart, now),
                        ascendingOrder = false,
                        pageSize = 50
                    )
                ).records
                val restingHr = restingRecords.firstOrNull()?.beatsPerMinute?.toDouble()

                val hrvRecords = client.readRecords(
                    ReadRecordsRequest(
                        recordType = HeartRateVariabilityRmssdRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(physiologyStart, now),
                        ascendingOrder = false,
                        pageSize = 50
                    )
                ).records
                val hrv = hrvRecords.firstOrNull()?.heartRateVariabilityMillis

                val payload = JSONObject().apply {
                    put("source", "Android Health Connect")
                    put("timestamp", now.toString())
                    if (sleepHours > 0) put("sleepHours", sleepHours)
                    if (restingHr != null) put("restingHR", restingHr)
                    if (hrv != null) put("hrv", hrv)
                    put("note", "Sleep is calculated from Health Connect sleep sessions ending in the last 24 hours. Sleep efficiency and interruptions remain editable because not every wearable exposes consistent stage data.")
                }
                val js = "window.onHealthConnectData(${payload.toString()});"
                webView.post { webView.evaluateJavascript(js, null) }
            } catch (e: Exception) {
                sendHealthError("Could not read Health Connect data: ${e.message}")
            }
        }
    }

    private fun sendHealthError(message: String) {
        val js = "window.onNativeMessage(${JSONObject.quote(message)});"
        webView.post { webView.evaluateJavascript(js, null) }
    }
}
