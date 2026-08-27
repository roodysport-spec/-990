package com.skyline.proctor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * يقابل كل أوامر cv2.rectangle / cv2.putText بالبايثون.
 * يرسم فوق PreviewView مباشرة بدون التأثير على أداء تحليل الفيديو.
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    data class FaceBox(
        val rect: RectF,
        val label: String,
        val color: Int,
        val strokeWidth: Float = 4f
    )

    data class ObjectBox(
        val rect: RectF,
        val label: String
    )

    private var faceBoxes: List<FaceBox> = emptyList()
    private var objectBoxes: List<ObjectBox> = emptyList()

    // أبعاد إطار التحليل الفعلي (تُستخدم لتحويل الإحداثيات لمقاس الشاشة)
    private var sourceWidth: Int = 1
    private var sourceHeight: Int = 1

    private val boxPaint = Paint().apply {
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val objectPaint = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.RED
        strokeWidth = 6f
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 34f
        isAntiAlias = true
    }

    private val labelBgPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    fun setSourceSize(w: Int, h: Int) {
        sourceWidth = w
        sourceHeight = h
    }

    fun updateFaces(boxes: List<FaceBox>) {
        faceBoxes = boxes
        postInvalidate()
    }

    fun updateObjects(boxes: List<ObjectBox>) {
        objectBoxes = boxes
        postInvalidate()
    }

    private fun scaleRect(r: RectF): RectF {
        val scaleX = width.toFloat() / sourceWidth
        val scaleY = height.toFloat() / sourceHeight
        return RectF(r.left * scaleX, r.top * scaleY, r.right * scaleX, r.bottom * scaleY)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        for (box in objectBoxes) {
            val r = scaleRect(box.rect)
            canvas.drawRect(r, objectPaint)
            labelBgPaint.color = Color.RED
            canvas.drawRect(r.left, r.top - 40f, r.left + textPaint.measureText(box.label) + 16f, r.top, labelBgPaint)
            canvas.drawText(box.label, r.left + 8f, r.top - 10f, textPaint)
        }

        for (box in faceBoxes) {
            val r = scaleRect(box.rect)
            boxPaint.color = box.color
            boxPaint.strokeWidth = box.strokeWidth
            canvas.drawRect(r, boxPaint)

            labelBgPaint.color = Color.BLACK
            canvas.drawRect(r.left, r.top - 38f, r.left + textPaint.measureText(box.label) + 16f, r.top, labelBgPaint)
            textPaint.color = box.color
            canvas.drawText(box.label, r.left + 8f, r.top - 8f, textPaint)
            textPaint.color = Color.WHITE
        }
    }
}
