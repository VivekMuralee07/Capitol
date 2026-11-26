package com.capitol.app.diagnostics

import android.content.Context
import android.os.Build
import android.util.Log
import com.capitol.app.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small on-device log of errors and crashes, so users can copy it from
 * Settings → Diagnostics and share it when reporting a problem.
 * Never records API keys or tokens — callers log messages, not request headers.
 */
object Diagnostics {
    private const val MAX_BYTES = 256 * 1024
    private var file: File? = null
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun init(context: Context) {
        file = File(context.filesDir, "diagnostics.log")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            error("CRASH", "Uncaught exception on thread ${t.name}", e)
            previous?.uncaughtException(t, e)
        }
    }

    fun info(tag: String, message: String) = write("I", tag, message, null)
    fun error(tag: String, message: String, t: Throwable? = null) = write("E", tag, message, t)

    @Synchronized
    private fun write(level: String, tag: String, message: String, t: Throwable?) {
        if (level == "E") Log.e("Capitol/$tag", message, t) else Log.i("Capitol/$tag", message)
        val f = file ?: return
        runCatching {
            val trace = t?.let { "\n" + it.stackTraceToString().lines().take(25).joinToString("\n") } ?: ""
            f.appendText("${fmt.format(Date())} $level/$tag: $message$trace\n")
            if (f.length() > MAX_BYTES) {
                val text = f.readText()
                f.writeText(text.takeLast(MAX_BYTES / 2).substringAfter('\n'))
            }
        }
    }

    /** Full report: device/app header + log lines. */
    fun report(): String = buildString {
        appendLine("Capitol ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("---")
        append(file?.takeIf { it.exists() }?.readText()?.ifBlank { null } ?: "No errors logged.")
    }

    fun clear() { file?.writeText("") }
}
