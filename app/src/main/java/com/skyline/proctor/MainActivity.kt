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

/**
 * MainActivity - المرحلة 2: كشف الوجوه + اتجاه النظر + تتبع الطلاب + التنبيهات
 *
 * هذا يقابل الآن حلقة update_frame() بالبايثون بشكل شبه كامل (فيما يخص الوجوه):
 *  - تحويل كل إطار لـ Bitmap
 *  - تمريره لـ FaceMonitor (MediaPipe + الاتجاه + التتبع)
 *  - رسم المربعات فوق الفيديو (OverlayView)
 *  - إطلاق تنبيه + حفظ دليل عند "التفات مستمر"
 *  - حفظ تقرير CSV عند إيقاف المراقبة
 *
 * كشف الممنوعات (موبايل/كتاب/لابتوب) وكشف الصوت لسا محجوزين للمرحلة 3.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var isMonitoring = false

    private var faceMonitor: FaceMonitor? = null
    private lateinit var alertManager: AlertManager

    // يمنع تراكم إطارات بالمعالجة (نفس فكرة "افحص إطار وخلص قبل ما تجيب التالي")
    private val isProcessingFrame = AtomicBoolean(false)
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

        // تحميل مكتبة OpenCV الأصلية (لازمة لحساب اتجاه الرأس)
        if (!OpenCVLoader.initDebug()) {
            android.util.Log.e("MainActivity", "فشل تحميل OpenCV")
        }

        alertManager = AlertManager(this)
        cameraExecutor = Executors.newSingleThreadExecutor()
        binding.tvLog.movementMethod = ScrollingMovementMethod()

        binding.btnStart.setOnClickListener { onStartClicked() }
        binding.btnStop.setOnClickListener { stopMonitoring() }

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

        // نفس منطق is_hall_mode بالبايثون - حالياً نثبته true (وضع القاعة الكبيرة)
        // لاحقاً نربطه بمفتاح بالواجهة زي hall_mode_switch الأصلي
        faceMonitor = FaceMonitor(this, highSensitivity = true) { statuses, count ->
            runOnUiThread { onFaceResults(statuses, count) }
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()

            isMonitoring = true
            binding.tvCameraPlaceholder.visibility = android.view.View.GONE
            binding.btnStart.isEnabled = false
            binding.btnStop.isEnabled = true
            logEvent("✅ تم بدء المراقبة بنجاح...")
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
        // إذا لسا فريم سابق قيد المعالجة، نتجاوز هذا الفريم (زي STRATEGY_KEEP_ONLY_LATEST)
        if (!isProcessingFrame.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }

        try {
            val bitmap = ImageUtils.imageProxyToBitmap(imageProxy)
            if (bitmap != null) {
                lastBitmapForEvidence = bitmap
                binding.overlayView.setSourceSize(bitmap.width, bitmap.height)
                faceMonitor?.detectAsync(bitmap, System.currentTimeMillis())
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
            OverlayView.FaceBox(
                rect = s.rect,
                label = "S${s.objectID}: ${s.direction}",
                color = color
            )
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

    private fun stopMonitoring() {
        isMonitoring = false
        cameraProvider?.unbindAll()

        faceMonitor?.close()
        faceMonitor = null

        binding.tvCameraPlaceholder.visibility = android.view.View.VISIBLE
        binding.btnStart.isEnabled = true
        binding.btnStop.isEnabled = false
        binding.tvStudentsCount.text = "الطلاب في القاعة: 0"
        binding.overlayView.updateFaces(emptyList())
        binding.overlayView.updateObjects(emptyList())
        studentsCount = 0

        logEvent("تم إيقاف المراقبة.")

        val reportFile = alertManager.exportReportAndClear()
        if (reportFile != null) {
            logEvent("📄 تم حفظ التقرير: ${reportFile.absolutePath}")
        }
    }

    /** يقابل log_event() بالبايثون */
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
        faceMonitor?.close()
        alertManager.release()
    }
}
