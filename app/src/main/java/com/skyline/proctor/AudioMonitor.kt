package com.skyline.proctor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * ترجمة لـ audio_callback() بالبايثون.
 * يقيس مستوى الصوت الحي بالمايكروفون، وإذا تجاوز عتبة معينة يعتبرها
 * "أصوات وتحدث بالقاعة" ويطلق تنبيه.
 *
 * ملاحظة معايرة: عتبة الصوت هنا (VOLUME_THRESHOLD) قيمة ابتدائية تحتاج
 * ضبط فعلي حسب حساسية مايكروفون جهازك وضجيج القاعة المحيط - جرب بالواقع
 * وعدّلها إذا صارت التنبيهات كثيرة أو قليلة.
 */
class AudioMonitor(
    private val context: Context,
    private val onViolation: () -> Unit
) {
    companion object {
        private const val SAMPLE_RATE = 44100
        private const val VOLUME_THRESHOLD = 2000.0 // يحتاج معايرة حسب بيئتك الفعلية
    }

    private var audioRecord: AudioRecord? = null
    private var isRunning = false
    private var recordingThread: Thread? = null

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    fun start(): Boolean {
        if (!hasPermission()) return false

        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufferSize <= 0) return false

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBufferSize * 2
            )
        } catch (e: SecurityException) {
            return false
        }

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) return false

        isRunning = true
        audioRecord?.startRecording()

        recordingThread = thread(start = true, name = "AudioMonitorThread") {
            val buffer = ShortArray(minBufferSize)
            while (isRunning) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    val rms = calculateRms(buffer, read)
                    if (rms > VOLUME_THRESHOLD) {
                        onViolation()
                    }
                }
            }
        }
        return true
    }

    private fun calculateRms(buffer: ShortArray, length: Int): Double {
        var sum = 0.0
        for (i in 0 until length) {
            sum += (buffer[i] * buffer[i]).toDouble()
        }
        return sqrt(sum / length)
    }

    fun stop() {
        isRunning = false
        recordingThread?.join(500)
        recordingThread = null
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        audioRecord?.release()
        audioRecord = null
    }
}
