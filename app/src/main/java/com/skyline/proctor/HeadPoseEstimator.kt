package com.skyline.proctor

import org.opencv.calib3d.Calib3d
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfPoint2f
import org.opencv.core.MatOfPoint3f
import org.opencv.core.Point
import org.opencv.core.Point3

/**
 * ترجمة لجزء حساب اتجاه الرأس من update_frame() بالبايثون:
 * نفس النقاط المستخدمة [33, 263, 1, 61, 291, 199]، نفس معادلة الكاميرا،
 * ونفس تحويل الزوايا لتحديد الاتجاه (يمين/يسار/أعلى/أسفل/للأمام).
 *
 * ملاحظة: لازم تحميل مكتبة OpenCV الأصلية مرة وحدة عند بدء التطبيق
 * عبر OpenCVLoader.initDebug() (تنضاف بـ MainActivity).
 */
object HeadPoseEstimator {

    // فهارس نفس المعالم المستخدمة بالبايثون (landmark indices)
    val LANDMARK_INDICES = intArrayOf(33, 263, 1, 61, 291, 199)
    private const val NOSE_TIP_INDEX = 1 // idx == 1 بالبايثون

    data class Landmark(val x: Float, val y: Float, val z: Float)

    data class HeadPoseResult(
        val pitch: Double,       // x_angle
        val yaw: Double,         // y_angle
        val direction: String,   // يسار / يمين / أعلى / أسفل / للأمام
        val noseX: Double,
        val noseY: Double
    )

    /**
     * @param allLandmarks كل معالم الوجه (468 نقطة) بإحداثيات نسبية 0..1
     * @param imgW, imgH أبعاد الصورة الفعلية بالبكسل
     */
    fun estimate(allLandmarks: List<Landmark>, imgW: Int, imgH: Int): HeadPoseResult? {
        if (allLandmarks.size < 300) return null // تأكيد إن كل المعالم موجودة

        val face2d = mutableListOf<Point>()
        val face3d = mutableListOf<Point3>()
        var noseX = 0.0
        var noseY = 0.0

        for (idx in LANDMARK_INDICES) {
            val lm = allLandmarks[idx]
            val x = (lm.x * imgW)
            val y = (lm.y * imgH)
            if (idx == NOSE_TIP_INDEX) {
                noseX = x.toDouble()
                noseY = y.toDouble()
            }
            face2d.add(Point(x.toDouble(), y.toDouble()))
            face3d.add(Point3(x.toDouble(), y.toDouble(), lm.z.toDouble()))
        }

        val focalLength = 1.2 * imgW
        val camMatrix = Mat(3, 3, org.opencv.core.CvType.CV_64F)
        camMatrix.put(0, 0, focalLength, 0.0, imgW / 2.0)
        camMatrix.put(1, 0, 0.0, focalLength, imgH / 2.0)
        camMatrix.put(2, 0, 0.0, 0.0, 1.0)

        val distMatrix = MatOfDouble(0.0, 0.0, 0.0, 0.0)

        val objectPoints = MatOfPoint3f()
        objectPoints.fromList(face3d)
        val imagePoints = MatOfPoint2f()
        imagePoints.fromList(face2d)

        val rvec = Mat()
        val tvec = Mat()

        val success = Calib3d.solvePnP(objectPoints, imagePoints, camMatrix, distMatrix, rvec, tvec)
        if (!success) return null

        val rmat = Mat()
        Calib3d.Rodrigues(rvec, rmat)

        // RQDecomp3x3 - أوبن سي في أندرويد يوفرها مباشرة بنفس اسم البايثون
        val mtxR = Mat()
        val mtxQ = Mat()
        val angles = Calib3d.RQDecomp3x3(rmat, mtxR, mtxQ)
        // angles هنا List<Double> بترتيب [x, y, z] مثل البايثون تماماً

        val xAngle = angles[0] * 360.0 // Pitch
        val yAngle = angles[1] * 360.0 // Yaw

        val direction = when {
            yAngle < -12 -> "يسار"
            yAngle > 12 -> "يمين"
            xAngle < -12 -> "أسفل"
            xAngle > 18 -> "أعلى"
            else -> "للأمام"
        }

        return HeadPoseResult(xAngle, yAngle, direction, noseX, noseY)
    }
}
