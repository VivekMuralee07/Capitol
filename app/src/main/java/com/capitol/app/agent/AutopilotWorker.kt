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
import com.capitol.app.analysis.AlertEvaluator
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

        for (item in c.watchlist.all()) {
            val series = runCatching { c.market.history(item.symbol, ChartRange.Y1, force = true) }.getOrNull() ?: continue
            if (series.cachedAt != null) continue // offline copy — don't alert on stale data
            val report = TechnicalAnalyzer.analyze(series, settings.defaultIndicators, settings.indicatorParams)
            val sym = item.symbol

            // Verdict changes
            val prev = prefs.getString(sym, null)
            if (prev != null && prev != report.verdict.name) {
                changes += "$sym: ${prev.replace('_', ' ').lowercase()} → ${report.verdict.label} (score ${"%+.2f".format(report.compositeScore)})"
            }
            prefs.edit().putString(sym, report.verdict.name).apply()

            // Price + indicator alerts, edge-triggered (notify once when a condition becomes true)
            val states = mutableMapOf<String, Pair<Boolean, String>>()
            item.alertAbove?.let { states["price>$it"] = (report.lastPrice >= it) to "$sym crossed above your alert $it (now ${"%.2f".format(report.lastPrice)})" }
            item.alertBelow?.let { states["price<$it"] = (report.lastPrice <= it) to "$sym fell below your alert $it (now ${"%.2f".format(report.lastPrice)})" }
            item.indicatorAlerts.forEach { a ->
                AlertEvaluator.state(a, series, settings.indicatorParams, report.verdict)?.let { states[a.key] = it to "$sym: ${a.description}" }
            }
            val key = { k: String -> "alert|$sym|$k" }
            val previous = states.keys.filter { prefs.contains(key(it)) }.associateWith { prefs.getBoolean(key(it), false) }
            val fired = AlertEvaluator.fired(previous, states.mapValues { it.value.first })
            fired.forEach { changes += states.getValue(it).second }
            prefs.edit().apply { states.forEach { (k, v) -> putBoolean(key(k), v.first) } }.apply()
        }

        // Earnings / ex-dividend reminders: once per event, the day before (or on the day).
        val nowS = System.currentTimeMillis() / 1000
        com.capitol.app.data.market.EventsCalendar.load(c.market, c.watchlist.all().map { it.symbol to it.name }, nowS)
            .filter { it.type != com.capitol.app.data.market.EventType.DIVIDEND && it.daysFrom(nowS) in 0L..1L }
            .forEach { e ->
                val k = "event|${e.symbol}|${e.type}|${e.epochSeconds}"
                if (!prefs.getBoolean(k, false)) {
                    changes += "${e.symbol}: ${e.type.label} ${if (e.daysFrom(nowS) == 0L) "today" else "tomorrow"} — expect higher volatility"
                    prefs.edit().putBoolean(k, true).apply()
                }
            }

        com.capitol.app.widget.WatchlistWidget.refresh(applicationContext)
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
