package com.skyline.proctor

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult

/**
 * يقابل بالضبط الجزء "2. كشف الوجوه وتتبعها وحساب اتجاه الرأس" من update_frame() بالبايثون.
 *
 * يشتغل بوضع LIVE_STREAM (غير متزامن) وهو الوضع الموصى به من MediaPipe
 * للفيديو الحي، ويستدعي onResult مع كل نتيجة جاهزة.
 */
class FaceMonitor(
    context: Context,
    private val highSensitivity: Boolean = true,
    private val onResult: (List<StudentStatus>, Int) -> Unit
) {
    data class StudentStatus(
        val objectID: Int,
        val rect: android.graphics.RectF,
        val direction: String,
        val sustained: Boolean // مخالفة التفات مستمر (زي sustained بالبايثون)
    )

    companion object {
        // نفس LOOK_AWAY_FRAMES_THRESHOLD بالبايثون
        const val LOOK_AWAY_FRAMES_THRESHOLD = 15
    }

    private val tracker = CentroidTracker(maxDisappeared = 8, historyMaxLen = LOOK_AWAY_FRAMES_THRESHOLD)
    private var faceLandmarker: FaceLandmarker

    init {
        // نفس منطق init_ai_models(high_sensitivity) بالبايثون
        val detConf = if (highSensitivity) 0.25f else 0.5f

        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("face_landmarker.task")
            .build()

        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumFaces(40) // نفس num_faces=40 بالبايثون
            .setMinFaceDetectionConfidence(detConf)
            .setMinFacePresenceConfidence(detConf)
            .setMinTrackingConfidence(detConf)
            .setOutputFaceBlendshapes(false)
            .setOutputFacialTransformationMatrixes(false)
            .setResultListener(this::handleResult)
            .setErrorListener { e -> android.util.Log.e("FaceMonitor", "خطأ بكشف الوجه: ${e.message}") }
            .build()

        faceLandmarker = FaceLandmarker.createFromOptions(context, options)
    }

    private var lastImgW = 1
    private var lastImgH = 1

    fun detectAsync(bitmap: Bitmap, timestampMs: Long) {
        lastImgW = bitmap.width
        lastImgH = bitmap.height
        val mpImage = BitmapImageBuilder(bitmap).build()
        faceLandmarker.detectAsync(mpImage, timestampMs)
    }

    private fun handleResult(result: FaceLandmarkerResult, input: com.google.mediapipe.framework.image.MPImage) {
        val imgW = lastImgW
        val imgH = lastImgH

        if (result.faceLandmarks().isEmpty()) {
            tracker.update(emptyList())
            onResult(emptyList(), 0)
            return
        }

        // 1) حساب صندوق كل وجه (bounding box) من كل المعالم - زي البايثون بالضبط
        val rects = mutableListOf<CentroidTracker.Rect>()
        val allLandmarksPerFace = mutableListOf<List<HeadPoseEstimator.Landmark>>()

        for (faceLandmarks in result.faceLandmarks()) {
            var xMin = imgW; var yMin = imgH
            var xMax = 0; var yMax = 0
            val landmarks = ArrayList<HeadPoseEstimator.Landmark>(faceLandmarks.size)

            for (lm in faceLandmarks) {
                val x = (lm.x() * imgW).toInt()
                val y = (lm.y() * imgH).toInt()
                if (x < xMin) xMin = x
                if (y < yMin) yMin = y
                if (x > xMax) xMax = x
                if (y > yMax) yMax = y
                landmarks.add(HeadPoseEstimator.Landmark(lm.x(), lm.y(), lm.z()))
            }
            allLandmarksPerFace.add(landmarks)

            xMin = maxOf(0, xMin - 5); yMin = maxOf(0, yMin - 5)
            xMax = minOf(imgW, xMax + 5); yMax = minOf(imgH, yMax + 5)
            rects.add(CentroidTracker.Rect(xMin, yMin, xMax, yMax))
        }

        // 2) تتبع الطلاب (نفس tracker.update بالبايثون)
        val rectToId = tracker.update(rects)

        // 3) لكل وجه: نحسب اتجاه النظر، نحدّث التاريخ، ونحدد هل المخالفة "مستمرة"
        val statuses = mutableListOf<StudentStatus>()

        for (i in allLandmarksPerFace.indices) {
            val objectID = rectToId[i] ?: continue
            val pose = HeadPoseEstimator.estimate(allLandmarksPerFace[i], imgW, imgH) ?: continue

            tracker.pushDirection(objectID, pose.direction)
            val history = tracker.histories[objectID] ?: ArrayDeque()

            val sustained = history.size == LOOK_AWAY_FRAMES_THRESHOLD &&
                    history.all { it == pose.direction } &&
                    pose.direction != "للأمام"

            val r = rects[i]
            statuses.add(
                StudentStatus(
                    objectID = objectID,
                    rect = android.graphics.RectF(
                        r.startX.toFloat(), r.startY.toFloat(),
                        r.endX.toFloat(), r.endY.toFloat()
                    ),
                    direction = pose.direction,
                    sustained = sustained
                )
            )
        }

        onResult(statuses, tracker.objects.size)
    }

    fun reset() {
        tracker.reset()
    }

    fun close() {
        faceLandmarker.close()
    }
}
