package com.skyline.proctor

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.skyline.proctor.databinding.ActivityMainBinding
import org.opencv.android.OpenCVLoader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * MainActivity - المرحلة 3 (كاملة): كشف الوجوه + الاتجاه + الممنوعات + الصوت + التنبيهات
 *
 * هذا الآن يقابل update_frame() بالبايثون بشكل كامل:
 *  1) كشف الممنوعات (موبايل/كتاب/لابتوب) عبر تقسيم الصورة (ObjectMonitor)
 *  2) كشف الوجوه وتتبعها وحساب اتجاه النظر (FaceMonitor)
 *  3) كشف الصوت (AudioMonitor)
 *  4) رسم كل شي فوق الفيديو (OverlayView) + تنبيهات + أدلة + تقرير CSV
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var objectDetectionExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var isMonitoring = false

    private var faceMonitor: FaceMonitor? = null
    private var objectMonitor: ObjectMonitor? = null
    private var audioMonitor: AudioMonitor? = null
    private lateinit var alertManager: AlertManager

    private val isProcessingFrame = AtomicBoolean(false)
    private val isProcessingObjects = AtomicBoolean(false)
    private val frameCounter = AtomicInteger(0)

    // كشف الممنوعات أثقل حسابياً من كشف الوجوه (يشغّل الموديل 6 مرات لكل إطار بسبب التقسيم)
    // فنشغّله كل 8 إطارات بدل كل إطار، عشان نحافظ على سلاسة الفيديو الحي
    private val OBJECT_DETECTION_INTERVAL = 8

    private var lastBitmapForEvidence: android.graphics.Bitmap? = null
    private var violationsCount = 0
    private var studentsCount = 0

    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )

    private val permissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            startMonitoring()
        } else {
            logEvent("❌ خطأ: لازم تمنح صلاحية الكاميرا والمايكروفون عشان تشتغل المراقبة")
            Toast.makeText(this, "الصلاحيات مطلوبة لتشغيل المراقبة", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (!OpenCVLoader.initDebug()) {
            android.util.Log.e("MainActivity", "فشل تحميل OpenCV")
        }

        alertManager = AlertManager(this)
        cameraExecutor = Executors.newSingleThreadExecutor()
        objectDetectionExecutor = Executors.newSingleThreadExecutor()
        binding.tvLog.movementMethod = ScrollingMovementMethod()

        binding.btnStart.setOnClickListener { onStartClicked() }
        binding.btnStop.setOnClickListener { stopMonitoring() }

        // منع تغيير وضع القاعة أثناء المراقبة (نفس منطق تعطيل source_combo بالبايثون)
        binding.swHallMode.isEnabled = true

        logEvent("جاهز. اضغط 'بدء المراقبة' للبدء.")
    }

    private fun onStartClicked() {
        if (hasAllPermissions()) startMonitoring() else permissionLauncher.launch(requiredPermissions)
    }

    private fun hasAllPermissions(): Boolean = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun startMonitoring() {
        logEvent("جاري الاتصال بالكاميرا...")

        val isHallMode = binding.swHallMode.isChecked

        faceMonitor = FaceMonitor(this, highSensitivity = isHallMode) { statuses, count ->
            runOnUiThread { onFaceResults(statuses, count) }
        }
        objectMonitor = ObjectMonitor(this, highSensitivity = isHallMode)

        audioMonitor = AudioMonitor(this) {
            runOnUiThread { onAudioViolation() }
        }
        val audioStarted = audioMonitor?.start() ?: false
        if (!audioStarted) {
            logEvent("⚠️ تحذير: تعذر تشغيل المايكروفون")
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()

            isMonitoring = true
            binding.tvCameraPlaceholder.visibility = android.view.View.GONE
            binding.btnStart.isEnabled = false
            binding.btnStop.isEnabled = true
            binding.swHallMode.isEnabled = false
            logEvent("✅ تم بدء المراقبة بنجاح... (وضع القاعة الكبيرة: ${if (isHallMode) "مفعّل" else "معطّل"})")
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(binding.previewView.surfaceProvider)
        }

        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { it.setAnalyzer(cameraExecutor) { imageProxy -> analyzeFrame(imageProxy) } }

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            provider.unbindAll()
            provider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
        } catch (e: Exception) {
            logEvent("❌ خطأ بتشغيل الكاميرا: ${e.message}")
        }
    }

    private fun analyzeFrame(imageProxy: ImageProxy) {
        if (!isProcessingFrame.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }

        try {
            val bitmap = ImageUtils.imageProxyToBitmap(imageProxy)
            if (bitmap != null) {
                lastBitmapForEvidence = bitmap
                binding.overlayView.setSourceSize(bitmap.width, bitmap.height)

                // 1) كشف الوجوه (على كل إطار - غير متزامن عبر LIVE_STREAM)
                faceMonitor?.detectAsync(bitmap, System.currentTimeMillis())

                // 2) كشف الممنوعات (كل عدة إطارات فقط - أثقل حسابياً بسبب تقسيم الصورة)
                val count = frameCounter.incrementAndGet()
                if (count % OBJECT_DETECTION_INTERVAL == 0 && isProcessingObjects.compareAndSet(false, true)) {
                    val bitmapCopy = bitmap.copy(bitmap.config, false)
                    objectDetectionExecutor.execute {
                        try {
                            val detections = objectMonitor?.detectTiled(bitmapCopy) ?: emptyList()
                            runOnUiThread { onObjectResults(detections, bitmapCopy) }
                        } catch (e: Exception) {
                            android.util.Log.e("MainActivity", "خطأ بكشف الممنوعات: ${e.message}")
                        } finally {
                            isProcessingObjects.set(false)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "خطأ بتحليل الإطار: ${e.message}")
        } finally {
            imageProxy.close()
            isProcessingFrame.set(false)
        }
    }

    /** يقابل الجزء الثاني من update_frame() بالبايثون (رسم + تنبيهات الوجوه) */
    private fun onFaceResults(statuses: List<FaceMonitor.StudentStatus>, count: Int) {
        if (count != studentsCount) {
            studentsCount = count
            binding.tvStudentsCount.text = "الطلاب في القاعة: $studentsCount"
        }

        val boxes = statuses.map { s ->
            val color = if (s.sustained) Color.rgb(255, 165, 0) else Color.rgb(0, 255, 0)
            OverlayView.FaceBox(rect = s.rect, label = "S${s.objectID}: ${s.direction}", color = color)
        }
        binding.overlayView.updateFaces(boxes)

        for (s in statuses) {
            if (s.sustained) {
                val triggered = alertManager.trigger(
                    reasonKey = "student_${s.objectID}_${s.direction}",
                    message = "طالب ${s.objectID}: التفات مستمر نحو (${s.direction})",
                    evidenceBitmap = lastBitmapForEvidence,
                    evidenceReason = "Student_${s.objectID}_${s.direction}"
                ) { _, _ ->
                    violationsCount++
                    binding.tvViolationsCount.text = "المخالفات المسجلة: $violationsCount"
                }
                if (triggered) refreshLogView()
            }
        }
    }

    /** يقابل الجزء الأول من update_frame() بالبايثون (رسم + تنبيهات الممنوعات) */
    private fun onObjectResults(detections: List<ObjectMonitor.DetectedObject>, evidenceBitmap: android.graphics.Bitmap) {
        val boxes = detections.map { d ->
            OverlayView.ObjectBox(
                rect = d.rect,
                label = "ممنوع: ${d.category} (${(d.confidence * 100).toInt()}%)"
            )
        }
        binding.overlayView.updateObjects(boxes)

        for (d in detections) {
            val triggered = alertManager.trigger(
                reasonKey = "obj_${d.category}",
                message = "🚨 مخالفة: رصد (${d.category}) بدقة ${(d.confidence * 100).toInt()}%",
                evidenceBitmap = evidenceBitmap,
                evidenceReason = "Object_${d.category}"
            ) { _, _ ->
                violationsCount++
                binding.tvViolationsCount.text = "المخالفات المسجلة: $violationsCount"
            }
            if (triggered) refreshLogView()
        }
    }

    /** يقابل audio_callback() بالبايثون */
    private fun onAudioViolation() {
        val triggered = alertManager.trigger(
            reasonKey = "audio_violation",
            message = "🚨 مخالفة: تم رصد أصوات وتحدث في القاعة!",
            evidenceBitmap = null,
            evidenceReason = ""
        ) { _, _ ->
            violationsCount++
            binding.tvViolationsCount.text = "المخالفات المسجلة: $violationsCount"
        }
        if (triggered) refreshLogView()
    }

    private fun stopMonitoring() {
        isMonitoring = false
        cameraProvider?.unbindAll()

        faceMonitor?.close()
        faceMonitor = null
        objectMonitor?.close()
        objectMonitor = null
        audioMonitor?.stop()
        audioMonitor = null

        binding.tvCameraPlaceholder.visibility = android.view.View.VISIBLE
        binding.btnStart.isEnabled = true
        binding.btnStop.isEnabled = false
        binding.swHallMode.isEnabled = true
        binding.tvStudentsCount.text = "الطلاب في القاعة: 0"
        binding.overlayView.updateFaces(emptyList())
        binding.overlayView.updateObjects(emptyList())
        studentsCount = 0
        frameCounter.set(0)

        logEvent("تم إيقاف المراقبة.")

        val reportFile = alertManager.exportReportAndClear()
        if (reportFile != null) {
            logEvent("📄 تم حفظ التقرير: ${reportFile.absolutePath}")
        }
    }

    private fun logEvent(message: String) {
        alertManager.logEvent(message)
        refreshLogView()
    }

    private fun refreshLogView() {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        runOnUiThread {
            binding.tvLog.text = "[$timestamp]\n" + binding.tvLog.text
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        objectDetectionExecutor.shutdown()
        faceMonitor?.close()
        objectMonitor?.close()
        audioMonitor?.stop()
        alertManager.release()
    }
}
