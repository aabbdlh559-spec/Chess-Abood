package com.abood.chessassistant.overlay

import android.content.Context
import android.graphics.*
import android.view.View
import com.abood.chessassistant.model.EngineAnalysis
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class BoardArrowOverlayView(context: Context) : View(context) {

    var boardRect: RectF = RectF()
    var isFlipped: Boolean = false
    var analysis: EngineAnalysis? = null
    var showArrow: Boolean = true
    var showEvalBar: Boolean = true
    var showBoardOutline: Boolean = true

    private val boardOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#88D4AF37")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val bestArrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E610B981")
        style = Paint.Style.FILL
    }

    private val ponderArrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#88D4AF37")
        style = Paint.Style.FILL
    }

    private val squareHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4410B981")
        style = Paint.Style.FILL
    }

    private val evalBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC141B24")
        style = Paint.Style.FILL
    }

    private val evalWhitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFF8FAFC")
        style = Paint.Style.FILL
    }

    private val evalBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD4AF37")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    fun updateOverlay(
        rect: RectF,
        flipped: Boolean,
        newAnalysis: EngineAnalysis?,
        arrowEnabled: Boolean,
        evalEnabled: Boolean,
        outlineEnabled: Boolean
    ) {
        boardRect = rect
        isFlipped = flipped
        analysis = newAnalysis
        showArrow = arrowEnabled
        showEvalBar = evalEnabled
        showBoardOutline = outlineEnabled
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (boardRect.width() < 40f || boardRect.height() < 40f) return

        if (showBoardOutline) {
            canvas.drawRect(boardRect, boardOutlinePaint)
        }

        val current = analysis ?: return
        val sqW = boardRect.width() / 8f
        val sqH = boardRect.height() / 8f

        if (showArrow && current.bestMoveUci.length >= 4 && current.bestMoveUci != "0000") {
            if (current.ponderMoveUci.length >= 4 && current.ponderMoveUci != "0000") {
                drawMoveArrow(canvas, current.ponderMoveUci, sqW, sqH, ponderArrowPaint, false)
            }
            drawMoveArrow(canvas, current.bestMoveUci, sqW, sqH, bestArrowPaint, true)
        }

        if (showEvalBar) {
            drawEvalBar(canvas, current)
        }
    }

    private fun squareCenter(uciSq: String, sqW: Float, sqH: Float): Pair<PointF, RectF>? {
        if (uciSq.length < 2) return null
        val file = uciSq[0] - 'a'
        val rank = 8 - (uciSq[1] - '0')
        if (file !in 0..7 || rank !in 0..7) return null

        val col = if (isFlipped) 7 - file else file
        val row = if (isFlipped) 7 - rank else rank

        val left = boardRect.left + col * sqW
        val top = boardRect.top + row * sqH
        val rect = RectF(left, top, left + sqW, top + sqH)
        return PointF(rect.centerX(), rect.centerY()) to rect
    }

    private fun drawMoveArrow(
        canvas: Canvas,
        uciMove: String,
        sqW: Float,
        sqH: Float,
        paint: Paint,
        highlightSquares: Boolean
    ) {
        val from = squareCenter(uciMove.substring(0, 2), sqW, sqH) ?: return
        val to = squareCenter(uciMove.substring(2, 4), sqW, sqH) ?: return

        if (highlightSquares) {
            canvas.drawRect(from.second, squareHighlightPaint)
            canvas.drawRect(to.second, squareHighlightPaint)
        }

        val start = from.first
        val end = to.first
        val angle = atan2((end.y - start.y).toDouble(), (end.x - start.x).toDouble())
        val shaftWidth = sqW * 0.16f
        val headLength = sqW * 0.36f
        val headWidth = sqW * 0.38f

        val neckX = (end.x - headLength * cos(angle)).toFloat()
        val neckY = (end.y - headLength * sin(angle)).toFloat()
        val perpAngle = angle + Math.PI / 2.0

        val dxShaft = (shaftWidth * 0.5f * cos(perpAngle)).toFloat()
        val dyShaft = (shaftWidth * 0.5f * sin(perpAngle)).toFloat()
        val dxHead = (headWidth * 0.5f * cos(perpAngle)).toFloat()
        val dyHead = (headWidth * 0.5f * sin(perpAngle)).toFloat()

        val path = Path().apply {
            moveTo(start.x + dxShaft, start.y + dyShaft)
            lineTo(neckX + dxShaft, neckY + dyShaft)
            lineTo(neckX + dxHead, neckY + dyHead)
            lineTo(end.x, end.y)
            lineTo(neckX - dxHead, neckY - dyHead)
            lineTo(neckX - dxShaft, neckY - dyShaft)
            lineTo(start.x - dxShaft, start.y - dyShaft)
            close()
        }
        canvas.drawPath(path, paint)
    }

    private fun drawEvalBar(canvas: Canvas, eval: EngineAnalysis) {
        val barWidth = 14f
        val barLeft = (boardRect.left - barWidth - 6f).coerceAtLeast(4f)
        val barRect = RectF(barLeft, boardRect.top, barLeft + barWidth, boardRect.bottom)
        canvas.drawRoundRect(barRect, 6f, 6f, evalBgPaint)

        val whiteRatio = when {
            eval.mateIn > 0 -> 1f
            eval.mateIn < 0 -> 0f
            else -> (0.5f + (eval.scoreCp / 1200f)).coerceIn(0.05f, 0.95f)
        }

        val whiteHeight = barRect.height() * whiteRatio
        val whiteRect = if (!isFlipped) {
            RectF(barRect.left, barRect.bottom - whiteHeight, barRect.right, barRect.bottom)
        } else {
            RectF(barRect.left, barRect.top, barRect.right, barRect.top + whiteHeight)
        }
        canvas.drawRoundRect(whiteRect, 6f, 6f, evalWhitePaint)
        canvas.drawRoundRect(barRect, 6f, 6f, evalBorderPaint)
    }
}
