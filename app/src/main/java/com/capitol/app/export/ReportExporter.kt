package com.capitol.app.export

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import com.capitol.app.analysis.AnalysisReport
import com.capitol.app.analysis.Signal
import com.capitol.app.analysis.TradePlan
import com.capitol.app.data.market.Fundamentals
import com.capitol.app.data.market.PriceSeries
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** What goes into a shared report. */
data class ReportContent(
    val report: AnalysisReport,
    val series: PriceSeries,
    val plan: TradePlan? = null,
    val fundamentals: Fundamentals? = null,
)

/**
 * Renders an analysis as a shareable PNG image or multi-page PDF (light theme for
 * readability) and opens Android's share sheet.
 */
class ReportExporter(private val context: Context) {

    private val bull = Color.rgb(14, 159, 110)
    private val bear = Color.rgb(224, 36, 84)
    private val neutral = Color.rgb(216, 155, 0)
    private val ink = Color.rgb(15, 23, 42)
    private val muted = Color.rgb(85, 96, 122)
    private val accent = Color.rgb(51, 85, 255)

    private fun signalColor(s: Signal) = when { s.isBullish -> bull; s.isBearish -> bear; else -> neutral }

    /** Draws the report onto canvases supplied by [newPage]; returns the height used on the last page. */
    private fun render(c: ReportContent, width: Float, pageHeight: Float, newPage: () -> Canvas): Float {
        val m = width * 0.06f
        var canvas = newPage()
        var y = m
        val r = c.report
        fun ensure(h: Float) { if (y + h > pageHeight - m) { canvas = newPage(); y = m } }
        fun paint(size: Float, color: Int = ink, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size; this.color = color; isFakeBoldText = bold
        }
        fun text(t: String, size: Float, color: Int = ink, bold: Boolean = false, gap: Float = size * 0.35f) {
            val layout = StaticLayout.Builder.obtain(t, 0, t.length, paint(size, color, bold), (width - 2 * m).toInt())
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.15f).build()
            ensure(layout.height.toFloat())
            canvas.save(); canvas.translate(m, y); layout.draw(canvas); canvas.restore()
            y += layout.height + gap
        }
        val u = width / 100f // scale unit

        // Header
        text("${r.name} (${r.symbol})", 5.2f * u, bold = true, gap = u)
        text("${"%,.2f".format(r.lastPrice)} ${r.currency}   ${"%+.2f".format(r.changePercent)}%   ·   " +
            SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(r.generatedAt)), 3f * u, muted, gap = 2 * u)

        // Verdict pill
        ensure(10 * u)
        val pill = RectF(m, y, m + 46 * u, y + 9 * u)
        canvas.drawRoundRect(pill, 4.5f * u, 4.5f * u, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = signalColor(r.verdict); alpha = 40 })
        canvas.drawText(r.verdict.label.uppercase(), m + 3 * u, y + 6.2f * u, paint(4.2f * u, signalColor(r.verdict), true))
        canvas.drawText("score ${"%+.2f".format(r.compositeScore)} · agreement ${r.confidence}%", m + 49 * u, y + 4f * u, paint(2.7f * u, muted))
        canvas.drawText("${r.bullishCount} bullish · ${r.neutralCount} neutral · ${r.bearishCount} bearish", m + 49 * u, y + 7.6f * u, paint(2.7f * u, muted))
        y += 12 * u

        // Price chart with key levels
        val candles = c.series.candles.takeLast(160)
        if (candles.size > 2) {
            val ch = 38 * u
            ensure(ch + 4 * u)
            val left = m; val right = width - m; val top = y; val bottom = y + ch
            var lo = candles.minOf { it.low }; var hi = candles.maxOf { it.high }
            val pad = (hi - lo) * 0.08; lo -= pad; hi += pad
            val span = (hi - lo).takeIf { it > 0 } ?: 1.0
            fun px(i: Int) = left + (right - left) * i / (candles.size - 1)
            fun py(v: Double) = (top + (hi - v) / span * ch).toFloat()
            canvas.drawRect(left, top, right, bottom, Paint().apply { color = Color.rgb(244, 247, 251) })
            val labelled = mutableListOf<Float>()
            r.keyLevels.filter { it.price in lo..hi }.sortedByDescending { it.price }.forEach { lv ->
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (lv.isSupport) bull else bear; strokeWidth = 0.3f * u; pathEffect = DashPathEffect(floatArrayOf(u, u), 0f); alpha = 170 }
                val ly = py(lv.price)
                canvas.drawLine(left, ly, right, ly, p)
                // Skip labels that would overlap a neighbour's.
                if (labelled.none { abs(it - ly) < 2.6f * u }) {
                    canvas.drawText("${lv.label} ${"%,.2f".format(lv.price)}", left + u, ly - 0.6f * u, paint(2f * u, p.color))
                    labelled += ly
                }
            }
            val path = Path().apply { candles.forEachIndexed { i, k -> if (i == 0) moveTo(px(i), py(k.close)) else lineTo(px(i), py(k.close)) } }
            val up = candles.last().close >= candles.first().close
            canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.5f * u; color = if (up) bull else bear })
            y = bottom + 4 * u
        }

        // Summary
        text("What it all means", 3.6f * u, bold = true)
        text(r.summary, 2.9f * u, gap = 2.5f * u)

        // Indicators
        text("Indicators", 3.6f * u, bold = true)
        r.results.filter { it.available }.forEach { res ->
            text("${res.title} — ${res.signal.label}", 2.9f * u, signalColor(res.signal), bold = true, gap = 0.3f * u)
            text(res.headline + ". " + res.interpretation, 2.5f * u, muted, gap = 1.6f * u)
        }

        // Trade plan
        c.plan?.let { p ->
            text("Trade plan · ${p.direction.label}", 3.6f * u, bold = true)
            text(p.toPlainText(r.symbol, r.currency).lines().drop(1).joinToString("\n"), 2.8f * u, gap = 2.5f * u)
        }

        // Fundamentals
        c.fundamentals?.let { f ->
            val body = f.toPlainText().lines().drop(1).joinToString("   ·   ")
            if (body.isNotBlank()) { text("Fundamentals", 3.6f * u, bold = true); text(body, 2.6f * u, gap = 2.5f * u) }
        }

        text("Generated by Capitol · educational technical analysis, not investment advice. Data: Yahoo Finance.", 2.2f * u, muted, gap = 0f)
        return y + m
    }

    /** Renders a single tall PNG. */
    fun toPng(c: ReportContent, widthPx: Int = 1080): File {
        val maxH = widthPx * 6
        val bmp = Bitmap.createBitmap(widthPx, maxH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp).apply { drawColor(Color.WHITE) }
        var pages = 0
        val used = render(c, widthPx.toFloat(), maxH.toFloat()) { pages++; canvas }
        val h = if (pages > 1) maxH else used.toInt().coerceIn(1, maxH)
        val out = Bitmap.createBitmap(bmp, 0, 0, widthPx, h)
        return file(c, "png").apply { outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }

    /** Number of A4 pages the report needs (same layout as [toPdf], drawn to bitmaps). */
    internal fun pageCount(c: ReportContent, w: Int = 595, h: Int = 842): Int {
        var pages = 0
        render(c, w.toFloat(), h.toFloat()) { pages++; Canvas(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)) }
        return pages
    }

    /** Renders an A4 PDF, adding pages as needed. */
    fun toPdf(c: ReportContent): File {
        val doc = PdfDocument()
        val w = 595; val h = 842
        var page: PdfDocument.Page? = null
        render(c, w.toFloat(), h.toFloat()) {
            page?.let { doc.finishPage(it) }
            page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, (doc.pages.size + 1)).create())
            page!!.canvas.apply { drawColor(Color.WHITE) }
        }
        page?.let { doc.finishPage(it) }
        val f = file(c, "pdf")
        f.outputStream().use { doc.writeTo(it) }
        doc.close()
        return f
    }

    fun toText(c: ReportContent): String = buildString {
        append(c.report.toPlainText())
        c.plan?.let { appendLine(); append(it.toPlainText(c.report.symbol, c.report.currency)) }
        appendLine(); append("— shared from Capitol")
    }

    fun share(file: File, mime: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            "Share analysis").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun shareText(text: String) {
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share analysis")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun file(c: ReportContent, ext: String): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs(); listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 86_400_000) it.delete() } }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        return File(dir, "Capitol-${c.report.symbol.replace(Regex("[^A-Za-z0-9.-]"), "_")}-$stamp.$ext")
    }
}
