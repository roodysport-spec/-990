package com.skyline.proctor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector

/**
 * ترجمة لدالة detect_objects_tiled() بالبايثون + الجزء "1. مسح للبحث عن الممنوعات" من update_frame().
 *
 * يشتغل بوضع IMAGE (متزامن) لأننا نستدعيه يدوياً على كل مربع (tile) بالدور،
 * على عكس FaceMonitor اللي يشتغل LIVE_STREAM على الإطار كامل.
 */
class ObjectMonitor(
    context: Context,
    highSensitivity: Boolean = true
) {
    data class DetectedObject(
        val rect: RectF,
        val category: String,
        val confidence: Float
    )

    companion object {
        val ALLOWED_CATEGORIES = setOf("cell phone", "book", "laptop")
    }

    private val detector: ObjectDetector

    init {
        // نفس score_thresh بالبايثون: 0.25 بوضع الحساسية العالية، 0.4 بدونه
        val scoreThresh = if (highSensitivity) 0.25f else 0.4f

        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("efficientdet_lite0.tflite")
            .build()

        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.IMAGE)
            .setScoreThreshold(scoreThresh)
            .setMaxResults(20)
            .build()

        detector = ObjectDetector.createFromOptions(context, options)
    }

    /**
     * يقسّم الصورة الكاملة إلى مربعات متراكبة ويشغّل الكاشف على كل مربع لحاله،
     * بالضبط نفس detect_objects_tiled() بالبايثون - يرفع دقة كشف الأجسام
     * الصغيرة/البعيدة (موبايل بآخر القاعة على مسافة 10 أمتار).
     */
    fun detectTiled(
        bitmap: Bitmap,
        tileCols: Int = 3,
        tileRows: Int = 2,
        overlap: Float = 0.15f,
        iouThresh: Float = 0.4f
    ): List<DetectedObject> {
        val imgW = bitmap.width
        val imgH = bitmap.height
        val stepX = imgW / tileCols
        val stepY = imgH / tileRows
        val tileW = (stepX * (1 + overlap)).toInt()
        val tileH = (stepY * (1 + overlap)).toInt()

        val allDetections = mutableListOf<DetectedObject>()

        for (row in 0 until tileRows) {
            for (col in 0 until tileCols) {
                val x0 = maxOf(0, col * stepX - (stepX * overlap / 2).toInt())
                val y0 = maxOf(0, row * stepY - (stepY * overlap / 2).toInt())
                val x1 = minOf(imgW, x0 + tileW)
                val y1 = minOf(imgH, y0 + tileH)

                if (x1 - x0 < 10 || y1 - y0 < 10) continue

                val tileBitmap = Bitmap.createBitmap(bitmap, x0, y0, x1 - x0, y1 - y0)
                val mpImage = BitmapImageBuilder(tileBitmap).build()

                val result = try {
                    detector.detect(mpImage)
                } catch (e: Exception) {
                    android.util.Log.e("ObjectMonitor", "خطأ بكشف مربع: ${e.message}")
                    null
                }

                result?.detections()?.forEach { det ->
                    val category = det.categories().firstOrNull()?.categoryName() ?: return@forEach
                    if (category !in ALLOWED_CATEGORIES) return@forEach
                    val score = det.categories().first().score()
                    val box = det.boundingBox()

                    allDetections.add(
                        DetectedObject(
                            rect = RectF(
                                x0 + box.left, y0 + box.top,
                                x0 + box.right, y0 + box.bottom
                            ),
                            category = category,
                            confidence = score
                        )
                    )
                }
            }
        }

        return nonMaxSuppress(allDetections, iouThresh)
    }

    /** يدمج نفس الجسم لو انكشف بأكثر من مربع متراكب (نفس منطق NMS بالبايثون) */
    private fun nonMaxSuppress(detections: List<DetectedObject>, iouThresh: Float): List<DetectedObject> {
        val sorted = detections.sortedByDescending { it.confidence }
        val merged = mutableListOf<DetectedObject>()

        for (det in sorted) {
            val overlapsExisting = merged.any { m ->
                m.category == det.category && iou(det.rect, m.rect) > iouThresh
            }
            if (!overlapsExisting) merged.add(det)
        }
        return merged
    }

    private fun iou(a: RectF, b: RectF): Float {
        val interLeft = maxOf(a.left, b.left)
        val interTop = maxOf(a.top, b.top)
        val interRight = minOf(a.right, b.right)
        val interBottom = minOf(a.bottom, b.bottom)
        val interW = maxOf(0f, interRight - interLeft)
        val interH = maxOf(0f, interBottom - interTop)
        val interArea = interW * interH
        val unionArea = a.width() * a.height() + b.width() * b.height() - interArea
        return if (unionArea > 0) interArea / unionArea else 0f
    }

    fun close() {
        detector.close()
    }
}
