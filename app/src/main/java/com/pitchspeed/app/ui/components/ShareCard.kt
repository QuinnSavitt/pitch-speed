package com.pitchspeed.app.ui.components

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.FileProvider
import com.pitchspeed.app.data.PitchSession
import com.pitchspeed.app.data.SpeedUnit
import com.pitchspeed.app.data.mphToDisplay
import com.pitchspeed.app.data.unitLabel
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Renders a shareable result card directly with the Canvas APIs (rather than capturing Compose
 * UI) and hands it off to any app via the standard share sheet.
 */
fun shareSessionCard(context: Context, session: PitchSession, unit: SpeedUnit) {
    val width = 1080
    val height = 1350
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val bgPaint = Paint().apply {
        shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), 0xFF1B5E20.toInt(), 0xFF0E3510.toInt(), Shader.TileMode.CLAMP)
    }
    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

    val titlePaint = Paint().apply {
        color = 0xFFFDFCF5.toInt()
        textSize = 54f
        typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }
    canvas.drawText("PITCH SPEED", width / 2f, 150f, titlePaint)

    val namePaint = Paint(titlePaint).apply { textSize = 46f; alpha = 220 }
    canvas.drawText(session.pitcherName, width / 2f, 230f, namePaint)

    val speedPaint = Paint().apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 320f
        typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }
    val fastest = mphToDisplay(session.fastestMph, unit).roundToInt()
    canvas.drawText("$fastest", width / 2f, 680f, speedPaint)

    val unitPaint = Paint(namePaint).apply { textSize = 52f; alpha = 200 }
    canvas.drawText(unitLabel(unit).uppercase(Locale.US) + " FASTEST", width / 2f, 760f, unitPaint)

    val statPaint = Paint().apply {
        color = 0xFFFDFCF5.toInt()
        textSize = 44f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
        alpha = 230
    }
    val avg = mphToDisplay(session.averageMph, unit).roundToInt()
    canvas.drawText(
        "${session.pitches.size} pitches  •  avg $avg ${unitLabel(unit)}",
        width / 2f, 900f, statPaint
    )

    val datePaint = Paint(statPaint).apply { textSize = 34f; alpha = 170 }
    val dateStr = SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(session.dateMillis))
    canvas.drawText(dateStr, width / 2f, height - 90f, datePaint)

    val brandPaint = Paint(datePaint).apply { alpha = 140 }
    canvas.drawText("Tracked with Pitch Speed", width / 2f, height - 40f, brandPaint)

    val dir = File(context.cacheDir, "shared_cards").apply { mkdirs() }
    val file = File(dir, "pitch_${session.id}.png")
    FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }

    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share pitch speed"))
}

/**
 * Hands the rendered detection log to any app that takes plain text — mail, messages, a notes
 * app, the clipboard. Deliberately text and not a file attachment: the point is that a tester can
 * paste it straight into a reply without hunting for a download.
 */
fun shareDiagnosticsText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Pitch Speed diagnostics")
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "Export diagnostics"))
}
