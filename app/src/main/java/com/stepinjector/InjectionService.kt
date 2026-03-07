package com.stepinjector

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.StepsRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import kotlin.random.Random

class InjectionService : Service() {

    companion object {
        const val EXTRA_STEPS      = "extra_steps"
        const val EXTRA_REALTIME   = "extra_realtime"
        const val EXTRA_VARIATION  = "extra_variation"
        const val ACTION_STOP      = "com.stepinjector.ACTION_STOP"

        private val _progress = MutableStateFlow<InjectionProgress?>(null)
        val progress: StateFlow<InjectionProgress?> = _progress
    }

    data class InjectionProgress(
        val injected: Long,
        val total: Long,
        val status: String,
        val done: Boolean = false,
        val error: Boolean = false
    )

    private val CHANNEL_ID = "injection_channel"
    private val NOTIF_ID   = 1
    private val STEPS_PER_MINUTE = 100L

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var injectionJob: Job? = null
    private var lastRecordEnd: Instant = Instant.now()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            injectionJob?.cancel()
            _progress.value = null
            stopSelf()
            return START_NOT_STICKY
        }

        val totalSteps    = intent?.getLongExtra(EXTRA_STEPS, 0) ?: 0L
        val isRealtime    = intent?.getBooleanExtra(EXTRA_REALTIME, true) ?: true
        val withVariation = intent?.getBooleanExtra(EXTRA_VARIATION, true) ?: true

        if (totalSteps <= 0) { stopSelf(); return START_NOT_STICKY }

        lastRecordEnd = Instant.now()

        ServiceCompat.startForeground(
            this, NOTIF_ID,
            buildNotification(0, totalSteps, "Iniciando..."),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        )

        injectionJob = serviceScope.launch {
            val client = HealthConnectClient.getOrCreate(this@InjectionService)
            if (isRealtime) injectRealtime(client, totalSteps, withVariation)
            else            injectInstant(client, totalSteps, withVariation)
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    // ─── Real-time injection ──────────────────────────────────────────────────

    private suspend fun injectRealtime(
        client: HealthConnectClient,
        totalSteps: Long,
        withVariation: Boolean
    ) {
        val numBatches   = (totalSteps + STEPS_PER_MINUTE - 1) / STEPS_PER_MINUTE
        val basePerBatch = totalSteps / numBatches
        var injected     = 0L

        for (i in 0 until numBatches) {
            val remaining = totalSteps - injected
            if (remaining <= 0) break

            val raw   = if (i == numBatches - 1) remaining else basePerBatch
            val steps = applyVariation(raw, remaining, withVariation)

            val error = insertRecord(client, steps)
            if (error == null) {
                injected += steps
                val pct    = ((injected.toFloat() / totalSteps) * 100).toInt()
                val status = "Inyectando... $pct%  ($injected / $totalSteps)"
                _progress.value = InjectionProgress(injected, totalSteps, status)
                updateNotification(pct, totalSteps, status)
            } else {
                val errMsg = "Error lote ${i + 1}: $error"
                _progress.value = InjectionProgress(injected, totalSteps, errMsg, error = true)
                stopSelf()
                return
            }

            if (i < numBatches - 1 && injected < totalSteps) {
                delay(60_000L)
            }
        }

        val finalMsg = "Completado: $injected pasos en Google Fit"
        _progress.value = InjectionProgress(injected, totalSteps, finalMsg, done = true)
        updateNotification(100, totalSteps, finalMsg)
        stopSelf()
    }

    // ─── Instant (historical) injection ──────────────────────────────────────

    private suspend fun injectInstant(
        client: HealthConnectClient,
        totalSteps: Long,
        withVariation: Boolean
    ) {
        val numBatches   = (totalSteps + STEPS_PER_MINUTE - 1) / STEPS_PER_MINUTE
        val basePerBatch = totalSteps / numBatches
        val now          = Instant.now()
        val records      = mutableListOf<StepsRecord>()
        var cursor       = now.minusSeconds(numBatches * 60L)
        var injected     = 0L

        for (i in 0 until numBatches) {
            val remaining = totalSteps - injected
            if (remaining <= 0) break

            val raw   = if (i == numBatches - 1) remaining else basePerBatch
            val steps = applyVariation(raw, remaining, withVariation)

            val walkSecs  = (steps * 0.6).toLong().coerceAtLeast(5L)
            val startTime = cursor
            val endTime   = (cursor.plusSeconds(walkSecs)).coerceAtMost(now)

            if (!endTime.isAfter(startTime)) { cursor = cursor.plusSeconds(2); continue }

            val zone = ZoneId.systemDefault().rules.getOffset(startTime)
            records.add(StepsRecord(
                count           = steps,
                startTime       = startTime,
                startZoneOffset = zone,
                endTime         = endTime,
                endZoneOffset   = zone
            ))

            injected += steps
            cursor    = endTime.plusSeconds(5)
        }

        try {
            client.insertRecords(records)
            val finalMsg = "Completado: $injected pasos historicos en Google Fit"
            _progress.value = InjectionProgress(injected, totalSteps, finalMsg, done = true)
            updateNotification(100, totalSteps, finalMsg)
        } catch (e: Exception) {
            val errMsg = "Error: ${e.localizedMessage}"
            _progress.value = InjectionProgress(injected, totalSteps, errMsg, error = true)
        }
        stopSelf()
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private suspend fun insertRecord(client: HealthConnectClient, stepCount: Long): String? {
        return try {
            val now          = Instant.now()
            val walkSecs     = (stepCount * 0.6).toLong().coerceAtLeast(5L)
            val naturalStart = now.minusSeconds(walkSecs)
            val startTime    = if (naturalStart.isAfter(lastRecordEnd)) naturalStart
                               else lastRecordEnd.plusSeconds(1)
            val endTime      = startTime.plusSeconds(walkSecs)
            lastRecordEnd    = endTime

            val zone = ZoneId.systemDefault().rules.getOffset(startTime)
            client.insertRecords(listOf(StepsRecord(
                count           = stepCount,
                startTime       = startTime,
                startZoneOffset = zone,
                endTime         = endTime,
                endZoneOffset   = zone
            )))
            null // null = success
        } catch (e: Exception) {
            e.javaClass.simpleName + ": " + e.message
        }
    }

    private fun applyVariation(raw: Long, remaining: Long, enabled: Boolean): Long {
        if (!enabled || raw <= 5) return raw.coerceAtMost(remaining)
        return (raw * Random.nextDouble(0.70, 1.30)).toLong().coerceIn(1, remaining)
    }

    // ─── Notification ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Inyeccion de pasos",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Progreso de la inyeccion en segundo plano" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(pct: Int, total: Long, text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, InjectionService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("Step Injector")
            .setContentText(text)
            .setProgress(100, pct, pct == 0)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_delete, "Detener", stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(pct: Int, total: Long, text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(pct, total, text))
    }
}
