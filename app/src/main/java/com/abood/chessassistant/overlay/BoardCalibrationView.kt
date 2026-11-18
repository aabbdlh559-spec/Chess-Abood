package com.abood.chessassistant.overlay

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class BoardCalibrationView(
    context: Context,
    initialRect: RectF,
    private val onConfirmRect: (RectF) -> Unit
) : View(context) {

    private val frameRect = RectF(initialRect)
    private var activeMode = 0
    private var lastX = 0f
    private var lastY = 0f

    private val dimPaint = Paint().apply { color = Color.parseColor("#66000000") }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD4AF37")
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#99D4AF37")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF10B981")
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 40f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val btnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF10B981")
        style = Paint.Style.FILL
    }

    private var confirmBtnRect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)

        val sqW = frameRect.width() / 8f
        val sqH = frameRect.height() / 8f
        for (i in 1..7) {
            canvas.drawLine(
                frameRect.left + i * sqW, frameRect.top,
                frameRect.left + i * sqW, frameRect.bottom, gridPaint
            )
            canvas.drawLine(
                frameRect.left, frameRect.top + i * sqH,
                frameRect.right, frameRect.top + i * sqH, gridPaint
            )
        }
        canvas.drawRect(frameRect, borderPaint)

        canvas.drawCircle(frameRect.left, frameRect.top, 26f, handlePaint)
        canvas.drawCircle(frameRect.right, frameRect.bottom, 26f, handlePaint)

        val btnW = 360f
        val btnH = 96f
        val btnTop = (frameRect.bottom + 28f).coerceAtMost(height - btnH - 32f)
        confirmBtnRect = RectF(
            width / 2f - btnW / 2f,
            btnTop,
            width / 2f + btnW / 2f,
            btnTop + btnH
        )
        canvas.drawRoundRect(confirmBtnRect, 24f, 24f, btnPaint)
        canvas.drawText("حفظ حدود الرقعة ✓", confirmBtnRect.centerX(), confirmBtnRect.centerY() + 14f, textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (confirmBtnRect.contains(x, y)) {
                    onConfirmRect(RectF(frameRect))
                    return true
                }
                activeMode = when {
                    abs(x - frameRect.left) < 65f && abs(y - frameRect.top) < 65f -> 2
                    abs(x - frameRect.right) < 65f && abs(y - frameRect.bottom) < 65f -> 3
                    frameRect.contains(x, y) -> 1
                    else -> 0
                }
                lastX = x
                lastY = y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = x - lastX
                val dy = y - lastY
                lastX = x
                lastY = y
                when (activeMode) {
                    1 -> {
                        val newL = (frameRect.left + dx).coerceIn(0f, width - frameRect.width())
                        val newT = (frameRect.top + dy).coerceIn(0f, height - frameRect.height())
                        frameRect.offsetTo(newL, newT)
                    }
                    2 -> {
                        val size = max(180f, min(frameRect.right - (frameRect.left + dx), frameRect.bottom - (frameRect.top + dy)))
                        frameRect.left = (frameRect.right - size).coerceAtLeast(0f)
                        frameRect.top = (frameRect.bottom - size).coerceAtLeast(0f)
                    }
                    3 -> {
                        val size = max(180f, min((frameRect.right + dx) - frameRect.left, (frameRect.bottom + dy) - frameRect.top))
                        frameRect.right = (frameRect.left + size).coerceAtMost(width.toFloat())
                        frameRect.bottom = (frameRect.top + size).coerceAtMost(height.toFloat())
                    }
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                activeMode = 0
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
