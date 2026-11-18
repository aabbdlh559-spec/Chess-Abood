package com.abood.chessassistant.service

import android.app.Activity
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.abood.chessassistant.ChessAboodApp
import com.abood.chessassistant.capture.ScreenCaptureManager
import com.abood.chessassistant.data.SettingsRepository
import com.abood.chessassistant.engine.StockfishEngine
import com.abood.chessassistant.model.AssistantState
import com.abood.chessassistant.overlay.OverlayManager
import com.abood.chessassistant.ui.MainActivity
import com.abood.chessassistant.vision.BoardRecognizer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ChessAssistantService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var settings: SettingsRepository
    private lateinit var engine: StockfishEngine
    private lateinit var recognizer: BoardRecognizer
    private lateinit var captureManager: ScreenCaptureManager
    private lateinit var overlayManager: OverlayManager

    private var loopJob: Job? = null
    private var pendingCandidateFen: String = ""
    private var lastAnalyzedFen: String = ""
    private var autoDetectedOnce: Boolean = false
    private var screenW = 1080
    private var screenH = 2400

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this)
        engine = StockfishEngine(this)
        recognizer = BoardRecognizer(settings, engine.kotlinEngine)

        val dm = resources.displayMetrics
        screenW = dm.widthPixels
        screenH = dm.heightPixels
        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = wm.maximumWindowMetrics.bounds
                screenW = bounds.width()
                screenH = bounds.height()
            } else {
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealMetrics(metrics)
                screenW = metrics.widthPixels
                screenH = metrics.heightPixels
            }
        } catch (_: Exception) {
        }

        captureManager = ScreenCaptureManager(this) {
            stopAssistant()
        }

        overlayManager = OverlayManager(
            context = this,
            settings = settings,
            onTogglePause = { togglePause() },
            onToggleArrows = { toggleArrows() },
            onAutoDetectBoard = { runAutoDetectBoard(showToast = true) },
            onLearnPieces = { learnStartingPositionNow() },
            onStopService = { stopAssistant() }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopAssistant()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                if (resultCode != Activity.RESULT_OK || data == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }

                val notification = buildNotification()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }

                if (!captureManager.start(resultCode, data)) {
                    Toast.makeText(this, "فشل بدء التقاط الشاشة", Toast.LENGTH_SHORT).show()
                    stopSelf()
                    return START_NOT_STICKY
                }

                overlayManager.show()
                updateState(_state.value.copy(isRunning = true, isPaused = false, statusText = "جاري البحث عن رقعة الشطرنج..."))
                startAnalysisLoop()
            }
        }
        return START_STICKY
    }

    private fun startAnalysisLoop() {
        loopJob?.cancel()
        loopJob = serviceScope.launch {
            while (isActive) {
                val current = _state.value
                if (!current.isPaused) {
                    val frame = withContext(Dispatchers.Default) { captureManager.captureFrame() }
                    if (frame != null) {
                        // اكتشاف الرقعة تلقائياً عند أول إطار ناجح إذا لم يقم المستخدم بضبطها يدوياً
                        if (!autoDetectedOnce && !settings.hasUserCalibrated) {
                            val detected = withContext(Dispatchers.Default) { recognizer.autoDetectBoardRect(frame) }
                            if (detected != null) {
                                settings.boardRectRatio = detected
                                autoDetectedOnce = true
                            }
                        }

                        val rectPx = settings.boardRectRatio.toPixelRect(screenW, screenH)
                        val rec = withContext(Dispatchers.Default) {
                            recognizer.recognize(frame, rectPx)
                        }
                        frame.recycle()

                        if (rec != null && rec.occupiedCount >= 2) {
                            // تثبيت الإطارين المتتاليين لتجنب قراءة القطع أثناء حركة الانزلاق (Animation)
                            if (rec.fen != pendingCandidateFen) {
                                pendingCandidateFen = rec.fen
                            } else if (rec.fen != lastAnalyzedFen) {
                                lastAnalyzedFen = rec.fen
                                val userIsWhite = !rec.isFlipped
                                val isUserTurn = (rec.whiteToMove == userIsWhite)

                                val analysis = engine.analyze(
                                    fen = rec.fen,
                                    boardGrid = rec.boardGrid,
                                    maxDepth = settings.searchDepth,
                                    moveTimeMs = settings.moveTimeMs
                                )
                                updateState(
                                    _state.value.copy(
                                        currentFen = rec.fen,
                                        isFlipped = rec.isFlipped,
                                        whiteToMove = rec.whiteToMove,
                                        isUserTurn = isUserTurn,
                                        confidencePercent = rec.confidencePercent,
                                        trackingLabel = rec.trackingModeLabel,
                                        analysis = analysis,
                                        statusText = "تم التحليل"
                                    )
                                )
                            }
                        } else {
                            // إعادة محاولة الاكتشاف التلقائي إذا لم نجد الرقعة بعد
                            if (!settings.hasUserCalibrated) autoDetectedOnce = false
                            updateState(_state.value.copy(statusText = "وجّه العدسة نحو رقعة الشطرنج"))
                        }
                    }
                }
                delay(settings.scanIntervalMs)
            }
        }
    }

    private fun runAutoDetectBoard(showToast: Boolean) {
        serviceScope.launch {
            val frame = withContext(Dispatchers.Default) { captureManager.captureFrame() } ?: return@launch
            val detected = withContext(Dispatchers.Default) { recognizer.autoDetectBoardRect(frame) }
            frame.recycle()
            if (detected != null) {
                settings.boardRectRatio = detected
                settings.hasUserCalibrated = false
                autoDetectedOnce = true
                recognizer.resetTracking()
                lastAnalyzedFen = ""
                pendingCandidateFen = ""
                if (showToast) {
                    Toast.makeText(this@ChessAssistantService, "تم تحديد الرقعة تلقائياً بدقة ✓", Toast.LENGTH_SHORT).show()
                }
            } else if (showToast) {
                Toast.makeText(this@ChessAssistantService, "لم تُكتشف الرقعة تلقائياً، استخدم أداة القص اليدوي", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun learnStartingPositionNow() {
        serviceScope.launch {
            val frame = withContext(Dispatchers.Default) { captureManager.captureFrame() } ?: return@launch
            val rectPx = settings.boardRectRatio.toPixelRect(screenW, screenH)
            val ok = withContext(Dispatchers.Default) { recognizer.learnFromCurrentBoard(frame, rectPx) }
            frame.recycle()
            lastAnalyzedFen = ""
            pendingCandidateFen = ""
            Toast.makeText(
                this@ChessAssistantService,
                if (ok) "تم تعلّم القطع وتصفير الدور بنجاح ✓" else "ضع الرقعة في بداية المباراة ثم اضغط الكاميرا",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun togglePause() {
        val next = !_state.value.isPaused
        if (next) engine.stopCurrentSearch()
        updateState(_state.value.copy(isPaused = next))
    }

    private fun toggleArrows() {
        updateState(_state.value.copy(isArrowsVisible = !_state.value.isArrowsVisible))
    }

    private fun updateState(newState: AssistantState) {
        _state.value = newState
        overlayManager.render(newState)
    }

    private fun stopAssistant() {
        loopJob?.cancel()
        engine.shutdown()
        captureManager.release()
        overlayManager.hideAll()
        _state.value = AssistantState(isRunning = false, statusText = "متوقف")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, ChessAssistantService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, ChessAboodApp.CHANNEL_ID)
            .setContentTitle("Chess Abood يعمل في الخلفية")
            .setContentText("العدسة الذكية نشطة فوق اللعبة")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "إيقاف", stopIntent)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopAssistant()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.abood.chessassistant.START"
        const val ACTION_STOP = "com.abood.chessassistant.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val NOTIFICATION_ID = 2026

        private val _state = MutableStateFlow(AssistantState())
        val state: StateFlow<AssistantState> = _state.asStateFlow()
    }
}
