package com.abood.chessassistant.model

import android.graphics.RectF
import java.util.Locale

data class BoardRectRatio(
    val left: Float = 0.03f,
    val top: Float = 0.24f,
    val right: Float = 0.97f,
    val bottom: Float = 0.72f
) {
    fun toPixelRect(screenW: Int, screenH: Int): RectF = RectF(
        left.coerceIn(0f, 0.95f) * screenW,
        top.coerceIn(0f, 0.95f) * screenH,
        right.coerceIn(0.05f, 1f) * screenW,
        bottom.coerceIn(0.05f, 1f) * screenH
    )
}

enum class PlayerColorMode(val labelAr: String) {
    AUTO("تلقائي"),
    WHITE("أبيض"),
    BLACK("أسود")
}

enum class TurnMode(val labelAr: String) {
    AUTO("تلقائي"),
    WHITE("دور الأبيض"),
    BLACK("دور الأسود")
}

data class RecognitionResult(
    val fen: String,
    val boardGrid: Array<CharArray>,
    val isFlipped: Boolean,
    val whiteToMove: Boolean,
    val occupiedCount: Int,
    val confidencePercent: Int,
    val trackingModeLabel: String
)

data class EngineAnalysis(
    val bestMoveUci: String,
    val ponderMoveUci: String,
    val bestMoveArabic: String,
    val scoreCp: Int,
    val mateIn: Int,
    val depth: Int,
    val nodes: Long,
    val elapsedMs: Long,
    val engineLabel: String
) {
    fun formattedEval(): String {
        if (mateIn != 0) {
            return if (mateIn > 0) "مات في $mateIn" else "مات عليك ${-mateIn}"
        }
        val pawns = scoreCp / 100.0
        return if (pawns >= 0) {
            String.format(Locale.US, "+%.2f", pawns)
        } else {
            String.format(Locale.US, "%.2f", pawns)
        }
    }
}

data class AssistantState(
    val isRunning: Boolean = false,
    val isPaused: Boolean = false,
    val isArrowsVisible: Boolean = true,
    val currentFen: String = "",
    val isFlipped: Boolean = false,
    val whiteToMove: Boolean = true,
    val isUserTurn: Boolean = true,
    val confidencePercent: Int = 0,
    val trackingLabel: String = "",
    val analysis: EngineAnalysis? = null,
    val statusText: String = "جاهز للتشغيل"
)
