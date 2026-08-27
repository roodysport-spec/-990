package com.skyline.proctor

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * يقابل trigger_alarm() + save_evidence() + كتابة تقرير CSV بالبايثون.
 */
class AlertManager(private val context: Context) {

    data class LogEntry(val time: String, val message: String)

    private val lastAlertTime = HashMap<String, Long>()
    private val exportLog = mutableListOf<LogEntry>()
    private val cooldownMs = 4000L // نفس فترة التبريد 4 ثواني بالبايثون

    private val evidenceDir: File by lazy {
        File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Evidence").apply { mkdirs() }
    }
    private val reportsDir: File by lazy {
        File(context.getExternalFilesDir(null), "Reports").apply { mkdirs() }
    }

    private val toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 90)

    /**
     * @return true إذا انطلق التنبيه فعلياً (بعد اجتياز فترة التبريد)
     */
    fun trigger(
        reasonKey: String,
        message: String,
        evidenceBitmap: Bitmap?,
        evidenceReason: String,
        onLogged: (LogEntry, Int) -> Unit
    ): Boolean {
        val now = System.currentTimeMillis()
        val last = lastAlertTime[reasonKey] ?: 0L

        if (now - last <= cooldownMs) return false
        lastAlertTime[reasonKey] = now

        val entry = logEvent(message)

        if (evidenceBitmap != null) {
            saveEvidence(evidenceBitmap, evidenceReason)
        }

        try {
            toneGenerator.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 350)
        } catch (_: Exception) {
        }

        onLogged(entry, exportLog.size)
        return true
    }

    fun logEvent(message: String): LogEntry {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val entry = LogEntry(timestamp, message)
        exportLog.add(entry)
        return entry
    }

    private fun saveEvidence(bitmap: Bitmap, reason: String) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val file = File(evidenceDir, "${reason}_$timestamp.jpg")
        try {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
        } catch (e: Exception) {
            android.util.Log.e("AlertManager", "فشل حفظ الدليل: ${e.message}")
        }
    }

    /** يقابل حفظ Reports/ExamReport_*.csv عند إيقاف المراقبة */
    fun exportReportAndClear(): File? {
        if (exportLog.isEmpty()) return null

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val file = File(reportsDir, "ExamReport_$timestamp.csv")

        try {
            file.bufferedWriter().use { writer ->
                writer.write("\uFEFFTime,Violation Event\r\n") // BOM + نفس رأس الأعمدة بالبايثون
                for (entry in exportLog) {
                    val safeMessage = entry.message.replace("\"", "\"\"")
                    writer.write("${entry.time},\"$safeMessage\"\r\n")
                }
            }
            exportLog.clear()
            return file
        } catch (e: Exception) {
            android.util.Log.e("AlertManager", "فشل حفظ التقرير: ${e.message}")
            return null
        }
    }

    fun release() {
        toneGenerator.release()
    }
}
