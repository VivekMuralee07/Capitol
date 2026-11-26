package com.capitol.app.agent

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.capitol.app.CapitolApp
import com.capitol.app.MainActivity
import com.capitol.app.R
import com.capitol.app.analysis.TechnicalAnalyzer
import com.capitol.app.data.market.ChartRange
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Background autopilot: periodically re-analyses the watchlist with the user's
 * default indicators, detects verdict changes and price-alert hits, and posts a
 * notification (optionally summarised by the Claude agent).
 */
class AutopilotWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as CapitolApp
        val c = app.container
        val settings = c.settings.settings.first()
        val prefs = applicationContext.getSharedPreferences("autopilot_state", Context.MODE_PRIVATE)
        val changes = mutableListOf<String>()

        for (item in c.watchlist.current()) {
            val report = runCatching {
                TechnicalAnalyzer.analyze(c.market.history(item.symbol, ChartRange.Y1, force = true), settings.defaultIndicators)
            }.getOrNull() ?: continue
            val prev = prefs.getString(item.symbol, null)
            if (prev != null && prev != report.verdict.name) {
                changes += "${item.symbol}: ${prev.replace('_', ' ').lowercase()} → ${report.verdict.label} (score ${"%+.2f".format(report.compositeScore)})"
            }
            prefs.edit().putString(item.symbol, report.verdict.name).apply()
            item.alertAbove?.let { if (report.lastPrice >= it) changes += "${item.symbol} crossed above your alert $it (now ${"%.2f".format(report.lastPrice)})" }
            item.alertBelow?.let { if (report.lastPrice <= it) changes += "${item.symbol} fell below your alert $it (now ${"%.2f".format(report.lastPrice)})" }
        }

        if (changes.isNotEmpty()) {
            val body = c.agent.digest("Watchlist changes since last scan:\n" + changes.joinToString("\n"))
                ?: changes.joinToString("\n")
            notify(applicationContext, "Capitol Autopilot · ${changes.size} update${if (changes.size > 1) "s" else ""}", body)
        }
        return Result.success()
    }

    companion object {
        private const val WORK = "capitol_autopilot"
        const val CHANNEL = "autopilot"

        fun schedule(context: Context, enabled: Boolean, hours: Long) {
            val wm = runCatching { WorkManager.getInstance(context) }.getOrNull() ?: return
            if (!enabled) { wm.cancelUniqueWork(WORK); return }
            val req = PeriodicWorkRequestBuilder<AutopilotWorker>(hours.coerceAtLeast(1), TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = context.getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Autopilot alerts", NotificationManager.IMPORTANCE_DEFAULT))
            }
        }

        fun notify(context: Context, title: String, body: String) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            ensureChannel(context)
            val pi = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(body.lineSequence().first())
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(title.hashCode(), n)
        }
    }
}
