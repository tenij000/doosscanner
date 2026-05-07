package tenij000.versie1

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class ScannerOverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private val boxPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }

    private val textPaint = Paint().apply {
        color = Color.GREEN
        textSize = 60f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val centerFramePaint = Paint().apply {
        color = Color.GRAY
        style = Paint.Style.STROKE
        strokeWidth = 6f
        alpha = 200
    }

    private val backgroundPaint = Paint().apply {
        color = Color.BLACK
        alpha = 160 
    }

    private val targetPaint = Paint().apply {
        color = Color.CYAN
        style = Paint.Style.STROKE
        strokeWidth = 12f
    }

    private var detectedRect: RectF? = null
    private var isTargetMatch = false
    val scanFrame = RectF()
    private val transparentPath = Path()

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val size = width.coerceAtMost(height) * 0.35f 
        val centerX = width / 2f
        val centerY = height / 2f
        scanFrame.set(centerX - size, centerY - size, centerX + size, centerY + size)
        
        transparentPath.reset()
        transparentPath.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        transparentPath.addRect(scanFrame, Path.Direction.CCW)
    }

    fun updateResult(rect: RectF?, isTarget: Boolean) {
        detectedRect = rect
        isTargetMatch = isTarget
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        // Teken de donkere achtergrond met het gat in het midden
        canvas.drawPath(transparentPath, backgroundPaint)

        // Teken het grijze scan-vak
        canvas.drawRect(scanFrame, centerFramePaint)

        // Teken kader om resultaat
        detectedRect?.let { rect ->
            canvas.drawRect(rect, if (isTargetMatch) targetPaint else boxPaint)
        }
    }
}
