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
            else sendMessage("Health Connect permission was not granted. Manual entry and JSON/CSV import remain available.")
        }
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
            val name = uri.lastPathSegment ?: "wearable_file"
            webView.post {
                webView.evaluateJavascript(
                    "window.onWearableFileImported(${JSONObject.quote(text)}, ${JSONObject.quote(name)});", null
                )
            }
        } catch (e: Exception) {
            sendMessage("Could not read wearable file: ${e.message}")
        }
    }

    private val saveLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
        val text = pendingSaveText
        pendingSaveText = null
        if (uri == null || text == null) return@registerForActivityResult
        try {
            contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(text) }
            sendMessage("Assessment report saved.")
        } catch (e: Exception) {
            sendMessage("Could not save report: ${e.message}")
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

    override fun onDestroy() {
        webView.removeJavascriptInterface("Android")
        webView.destroy()
        super.onDestroy()
    }

    inner class AndroidBridge {
        @JavascriptInterface fun syncHealthConnect() = runOnUiThread { startHealthSync() }
        @JavascriptInterface fun importWearableFile() = runOnUiThread {
            importLauncher.launch(arrayOf("application/json", "text/csv", "text/plain", "*/*"))
        }
        @JavascriptInterface fun saveReport(text: String) {
            pendingSaveText = text
            runOnUiThread { saveLauncher.launch("Seafarer_Fatigue_Assessment.txt") }
        }
        @JavascriptInterface fun openHealthSettings() = runOnUiThread {
            try {
                startActivity(Intent("android.health.connect.action.HEALTH_HOME_SETTINGS"))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }
        @JavascriptInterface fun openPrivacy() = runOnUiThread {
            startActivity(Intent(this@MainActivity, PrivacyActivity::class.java))
        }
        @JavascriptInterface fun platformInfo(): String = "Android offline research edition 4.0"
    }

    private fun startHealthSync() {
        val client = healthClient ?: run {
            sendMessage("Health Connect is unavailable on this device. Use JSON/CSV import or manual entry.")
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
                sendMessage("Health Connect permission check failed: ${e.message}")
            }
        }
    }

    private fun overlapHours(start: Instant, end: Instant, windowStart: Instant, windowEnd: Instant): Double {
        val s = if (start.isAfter(windowStart)) start else windowStart
        val e = if (end.isBefore(windowEnd)) end else windowEnd
        return if (e.isAfter(s)) Duration.between(s, e).toMinutes() / 60.0 else 0.0
    }

    private fun readHealthData() {
        val client = healthClient ?: return
        lifecycleScope.launch {
            try {
                val now = Instant.now()
                val start8d = now.minus(8, ChronoUnit.DAYS)
                val sleepRecords = client.readRecords(
                    ReadRecordsRequest(
                        recordType = SleepSessionRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(start8d, now),
                        ascendingOrder = false,
                        pageSize = 200
                    )
                ).records

                val cutoff24 = now.minus(24, ChronoUnit.HOURS)
                val cutoff48 = now.minus(48, ChronoUnit.HOURS)
                val cutoff7d = now.minus(7, ChronoUnit.DAYS)
                val sleep24 = sleepRecords.sumOf { overlapHours(it.startTime, it.endTime, cutoff24, now) }.coerceAtMost(16.0)
                val sleep48 = sleepRecords.sumOf { overlapHours(it.startTime, it.endTime, cutoff48, now) }.coerceAtMost(32.0)
                val sleep7Total = sleepRecords.sumOf { overlapHours(it.startTime, it.endTime, cutoff7d, now) }
                val sleep7Avg = (sleep7Total / 7.0).coerceAtMost(16.0)
                val episodes24 = sleepRecords.count { it.endTime.isAfter(cutoff24) && it.startTime.isBefore(now) }
                val latestSleep = sleepRecords.maxByOrNull { it.endTime }

                val stages24 = sleepRecords.filter { it.endTime.isAfter(cutoff24) }.flatMap { it.stages }
                val awakeTypes = setOf(
                    SleepSessionRecord.STAGE_TYPE_AWAKE,
                    SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
                    SleepSessionRecord.STAGE_TYPE_OUT_OF_BED
                )
                val sleepTypes = setOf(
                    SleepSessionRecord.STAGE_TYPE_SLEEPING,
                    SleepSessionRecord.STAGE_TYPE_LIGHT,
                    SleepSessionRecord.STAGE_TYPE_DEEP,
                    SleepSessionRecord.STAGE_TYPE_REM
                )
                val stageSleepMinutes = stages24.filter { it.stage in sleepTypes }
                    .sumOf { Duration.between(it.startTime, it.endTime).toMinutes() }
                val stageAwakeMinutes = stages24.filter { it.stage in awakeTypes }
                    .sumOf { Duration.between(it.startTime, it.endTime).toMinutes() }
                val stagedMinutes = stageSleepMinutes + stageAwakeMinutes
                val sleepEfficiency = if (stagedMinutes > 0) 100.0 * stageSleepMinutes / stagedMinutes else null
                val interruptions = if (stages24.isNotEmpty()) stages24.count { it.stage in awakeTypes } else null

                val physiologyStart = now.minus(14, ChronoUnit.DAYS)
                val restingRecords = client.readRecords(
                    ReadRecordsRequest(
                        recordType = RestingHeartRateRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(physiologyStart, now),
                        ascendingOrder = false,
                        pageSize = 200
                    )
                ).records
                val currentRhr = restingRecords.maxByOrNull { it.time }?.beatsPerMinute?.toDouble()
                val rhrBaselineValues = restingRecords.filter { it.time.isBefore(cutoff24) }.map { it.beatsPerMinute.toDouble() }
                val rhrBaseline = rhrBaselineValues.takeIf { it.isNotEmpty() }?.average()

                val hrvRecords = client.readRecords(
                    ReadRecordsRequest(
                        recordType = HeartRateVariabilityRmssdRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(physiologyStart, now),
                        ascendingOrder = false,
                        pageSize = 200
                    )
                ).records
                val currentHrv = hrvRecords.maxByOrNull { it.time }?.heartRateVariabilityMillis
                val hrvBaselineValues = hrvRecords.filter { it.time.isBefore(cutoff24) }.map { it.heartRateVariabilityMillis }
                val hrvBaseline = hrvBaselineValues.takeIf { it.isNotEmpty() }?.average()

                val payload = JSONObject().apply {
                    put("source", "Android Health Connect")
                    put("timestamp", now.toString())
                    if (sleep24 > 0) put("sleepHours", sleep24)
                    if (sleep48 > 0) put("sleep48", sleep48)
                    if (sleep7Avg > 0) put("sleep7Avg", sleep7Avg)
                    put("sleepEpisodes24", episodes24)
                    latestSleep?.let {
                        put("sleepStart", it.startTime.toString())
                        put("sleepEnd", it.endTime.toString())
                    }
                    sleepEfficiency?.let { put("sleepEfficiency", it) }
                    interruptions?.let { put("sleepInterruptions", it) }
                    currentRhr?.let { put("restingHR", it) }
                    rhrBaseline?.let { put("restingHRBaseline", it) }
                    currentHrv?.let { put("hrv", it) }
                    hrvBaseline?.let { put("hrvBaseline", it) }
                    put("note", "Wearable-estimated sleep, resting heart rate and HRV are read locally from Health Connect. Stage-derived efficiency/interruptions are supplied only when compatible stage data exists.")
                }
                webView.post { webView.evaluateJavascript("window.onHealthConnectData(${payload});", null) }
            } catch (e: Exception) {
                sendMessage("Could not read Health Connect data: ${e.message}")
            }
        }
    }

    private fun sendMessage(message: String) {
        if (!::webView.isInitialized) return
        webView.post { webView.evaluateJavascript("window.onNativeMessage(${JSONObject.quote(message)});", null) }
    }
}
