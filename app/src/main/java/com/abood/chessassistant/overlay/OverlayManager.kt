package com.abood.chessassistant.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.DisplayMetrics
import android.view.*
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.abood.chessassistant.R
import com.abood.chessassistant.data.SettingsRepository
import com.abood.chessassistant.model.AssistantState
import com.abood.chessassistant.model.BoardRectRatio

class OverlayManager(
    private val context: Context,
    private val settings: SettingsRepository,
    private val onTogglePause: () -> Unit,
    private val onToggleArrows: () -> Unit,
    private val onAutoDetectBoard: () -> Unit,
    private val onLearnPieces: () -> Unit,
    private val onStopService: () -> Unit
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var arrowView: BoardArrowOverlayView? = null
    private var controlRoot: View? = null
    private var calibrationView: BoardCalibrationView? = null
    private var isCollapsed = false

    private var screenW = 1080
    private var screenH = 2400

    init {
        val dm = context.resources.displayMetrics
        screenW = dm.widthPixels
        screenH = dm.heightPixels
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = windowManager.maximumWindowMetrics.bounds
                screenW = bounds.width()
                screenH = bounds.height()
            } else {
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getRealMetrics(metrics)
                screenW = metrics.widthPixels
                screenH = metrics.heightPixels
            }
        } catch (_: Exception) {
        }
    }

    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    fun show() {
        if (arrowView == null) {
            arrowView = BoardArrowOverlayView(context)
            val arrowParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            windowManager.addView(arrowView, arrowParams)
        }

        if (controlRoot == null) {
            val view = LayoutInflater.from(context).inflate(R.layout.overlay_floating_controls, null)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 20
                y = 120
            }

            val expandedPanel = view.findViewById<LinearLayout>(R.id.panelExpanded)
            val collapsedBubble = view.findViewById<TextView>(R.id.btnCollapsedBubble)

            view.findViewById<ImageButton>(R.id.btnPauseResume).setOnClickListener { onTogglePause() }
            view.findViewById<ImageButton>(R.id.btnToggleArrows).setOnClickListener { onToggleArrows() }
            view.findViewById<ImageButton>(R.id.btnAutoDetect).setOnClickListener { onAutoDetectBoard() }
            view.findViewById<ImageButton>(R.id.btnCalibrateBoard).setOnClickListener { openCalibration() }
            view.findViewById<ImageButton>(R.id.btnLearnPieces).setOnClickListener { onLearnPieces() }
            view.findViewById<ImageButton>(R.id.btnCloseAssistant).setOnClickListener { onStopService() }

            view.findViewById<ImageButton>(R.id.btnMinimize).setOnClickListener {
                isCollapsed = true
                expandedPanel.visibility = View.GONE
                collapsedBubble.visibility = View.VISIBLE
            }
            collapsedBubble.setOnClickListener {
                isCollapsed = false
                collapsedBubble.visibility = View.GONE
                expandedPanel.visibility = View.VISIBLE
            }

            var initX = 0
            var initY = 0
            var touchX = 0f
            var touchY = 0f
            val dragHandle = view.findViewById<View>(R.id.dragHandle)
            val touchListener = View.OnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initX = params.x
                        initY = params.y
                        touchX = event.rawX
                        touchY = event.rawY
                        false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initX + (event.rawX - touchX).toInt()
                        params.y = initY + (event.rawY - touchY).toInt()
                        windowManager.updateViewLayout(view, params)
                        true
                    }
                    else -> false
                }
            }
            dragHandle.setOnTouchListener(touchListener)
            collapsedBubble.setOnTouchListener { v, event ->
                if (event.action == MotionEvent.ACTION_MOVE) {
                    params.x = initX + (event.rawX - touchX).toInt()
                    params.y = initY + (event.rawY - touchY).toInt()
                    windowManager.updateViewLayout(view, params)
                    true
                } else {
                    if (event.action == MotionEvent.ACTION_DOWN) {
                        initX = params.x; initY = params.y; touchX = event.rawX; touchY = event.rawY
                    }
                    v.onTouchEvent(event)
                }
            }

            windowManager.addView(view, params)
            controlRoot = view
        }
    }

    fun openCalibration() {
        if (calibrationView != null) return
        val currentRect = settings.boardRectRatio.toPixelRect(screenW, screenH)
        val view = BoardCalibrationView(context, currentRect) { confirmedRect ->
            settings.boardRectRatio = BoardRectRatio(
                left = confirmedRect.left / screenW,
                top = confirmedRect.top / screenH,
                right = confirmedRect.right / screenW,
                bottom = confirmedRect.bottom / screenH
            )
            settings.hasUserCalibrated = true
            closeCalibration()
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        windowManager.addView(view, params)
        calibrationView = view
    }

    private fun closeCalibration() {
        calibrationView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        calibrationView = null
    }

    fun render(state: AssistantState) {
        val boardPx = settings.boardRectRatio.toPixelRect(screenW, screenH)
        val shouldShowArrows = settings.showArrows && state.isArrowsVisible && !state.isPaused && state.isUserTurn
        arrowView?.visibility = if (!state.isPaused) View.VISIBLE else View.GONE
        arrowView?.updateOverlay(
            rect = boardPx,
            flipped = state.isFlipped,
            newAnalysis = state.analysis,
            arrowEnabled = shouldShowArrows,
            evalEnabled = settings.showEvalBar && !state.isPaused,
            outlineEnabled = settings.showBoardGrid && !state.isPaused
        )

        val root = controlRoot ?: return
        val tvBest = root.findViewById<TextView>(R.id.tvFloatingBestMove)
        val tvEval = root.findViewById<TextView>(R.id.tvFloatingEval)
        val tvTurn = root.findViewById<TextView>(R.id.tvFloatingTurn)

        if (state.isPaused) {
            tvBest.text = "التحليل متوقف مؤقتاً"
            tvEval.text = "—"
            tvTurn.text = "اضغط تشغيل للاستئناف"
            return
        }

        if (!state.isUserTurn && state.analysis != null) {
            tvBest.text = "دور الخصم الآن..."
            tvEval.text = "التقييم: ${state.analysis.formattedEval()} • دقة ${state.confidencePercent}%"
        } else if (state.analysis != null) {
            tvBest.text = "العب: ${state.analysis.bestMoveArabic}"
            tvEval.text = "${state.analysis.formattedEval()} • دقة ${state.confidencePercent}%"
        } else {
            tvBest.text = state.statusText
            tvEval.text = "جاري المسح الذكي..."
        }

        val sideAr = if (state.whiteToMove) "الأبيض" else "الأسود"
        val youAr = if (!state.isFlipped) "أبيض" else "أسود"
        tvTurn.text = "الدور: $sideAr | أنت: $youAr (${state.trackingLabel})"
    }

    fun hideAll() {
        closeCalibration()
        arrowView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        controlRoot?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        arrowView = null
        controlRoot = null
    }
}
