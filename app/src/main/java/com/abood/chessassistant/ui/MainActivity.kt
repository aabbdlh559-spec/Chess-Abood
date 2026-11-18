package com.abood.chessassistant.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.abood.chessassistant.R
import com.abood.chessassistant.data.SettingsRepository
import com.abood.chessassistant.engine.StockfishEngine
import com.abood.chessassistant.model.PlayerColorMode
import com.abood.chessassistant.model.TurnMode
import com.abood.chessassistant.service.ChessAssistantService
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var settings: SettingsRepository
    private lateinit var testEngine: StockfishEngine

    private lateinit var rootScrollView: ScrollView
    private lateinit var tvLiveStatus: TextView
    private lateinit var btnStartStop: MaterialButton
    private lateinit var tvOverlayStatus: TextView
    private lateinit var btnGrantOverlay: MaterialButton
    private lateinit var tvEngineName: TextView
    private lateinit var btnTestEngine: MaterialButton
    private lateinit var tvEngineTestOutput: TextView
    private lateinit var tvDepthLabel: TextView
    private lateinit var sliderDepth: Slider
    private lateinit var tvMoveTimeLabel: TextView
    private lateinit var sliderMoveTime: Slider
    private lateinit var btnColorCycle: MaterialButton
    private lateinit var btnTurnCycle: MaterialButton
    private lateinit var switchShowArrows: MaterialSwitch
    private lateinit var switchShowEval: MaterialSwitch
    private lateinit var switchShowGrid: MaterialSwitch
    private lateinit var tvLiveFen: TextView

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, ChessAssistantService::class.java).apply {
                action = ChessAssistantService.ACTION_START
                putExtra(ChessAssistantService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ChessAssistantService.EXTRA_RESULT_DATA, result.data)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
            Toast.makeText(this, "تم تشغيل العدسة الذكية! افتح Plato الآن", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "يجب الموافقة على التقاط الشاشة لتشغيل التحليل", Toast.LENGTH_SHORT).show()
        }
    }

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshPermissionBadges() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        bindViews()
        applySystemBarInsets()

        settings = SettingsRepository(this)
        testEngine = StockfishEngine(this)

        setupControls()
        observeServiceState()
    }

    private fun bindViews() {
        rootScrollView = findViewById(R.id.rootScrollView)
        tvLiveStatus = findViewById(R.id.tvLiveStatus)
        btnStartStop = findViewById(R.id.btnStartStop)
        tvOverlayStatus = findViewById(R.id.tvOverlayStatus)
        btnGrantOverlay = findViewById(R.id.btnGrantOverlay)
        tvEngineName = findViewById(R.id.tvEngineName)
        btnTestEngine = findViewById(R.id.btnTestEngine)
        tvEngineTestOutput = findViewById(R.id.tvEngineTestOutput)
        tvDepthLabel = findViewById(R.id.tvDepthLabel)
        sliderDepth = findViewById(R.id.sliderDepth)
        tvMoveTimeLabel = findViewById(R.id.tvMoveTimeLabel)
        sliderMoveTime = findViewById(R.id.sliderMoveTime)
        btnColorCycle = findViewById(R.id.btnColorCycle)
        btnTurnCycle = findViewById(R.id.btnTurnCycle)
        switchShowArrows = findViewById(R.id.switchShowArrows)
        switchShowEval = findViewById(R.id.switchShowEval)
        switchShowGrid = findViewById(R.id.switchShowGrid)
        tvLiveFen = findViewById(R.id.tvLiveFen)
    }

    private fun applySystemBarInsets() {
        val basePad = (16 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(rootScrollView) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                basePad + insets.left,
                basePad + insets.top,
                basePad + insets.right,
                basePad + insets.bottom
            )
            WindowInsetsCompat.CONSUMED
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionBadges()
    }

    private fun refreshPermissionBadges() {
        val hasOverlay = Settings.canDrawOverlays(this)
        tvOverlayStatus.text = if (hasOverlay) "مفعّل ✓" else "مطلوب التفعيل"
        btnGrantOverlay.isEnabled = !hasOverlay
        tvEngineName.text = testEngine.getEngineDisplayName()
    }

    private fun setupControls() {
        btnGrantOverlay.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        btnStartStop.setOnClickListener {
            if (ChessAssistantService.state.value.isRunning) {
                startService(Intent(this, ChessAssistantService::class.java).apply {
                    action = ChessAssistantService.ACTION_STOP
                })
                return@setOnClickListener
            }

            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "فعّل إذن الظهور فوق التطبيقات أولاً", Toast.LENGTH_SHORT).show()
                btnGrantOverlay.performClick()
                return@setOnClickListener
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }

            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            screenCaptureLauncher.launch(mgr.createScreenCaptureIntent())
        }

        btnTestEngine.setOnClickListener {
            tvEngineTestOutput.text = "جاري اختبار المحرك..."
            lifecycleScope.launch {
                val res = testEngine.analyze(
                    fen = "r1bqk2r/pppp1ppp/2n2n2/2b1p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR w KQkq - 4 4",
                    boardGrid = null,
                    maxDepth = 9,
                    moveTimeMs = 400
                )
                tvEngineTestOutput.text =
                    "نجح الاختبار ✓ | النقلة: ${res.bestMoveUci} (${res.formattedEval()}) | ${res.nodes} عقدة في ${res.elapsedMs}ms"
            }
        }

        sliderDepth.value = settings.searchDepth.toFloat().coerceIn(sliderDepth.valueFrom, sliderDepth.valueTo)
        tvDepthLabel.text = "عمق البحث: ${settings.searchDepth}"
        sliderDepth.addOnChangeListener { _, v, _ ->
            settings.searchDepth = v.toInt()
            tvDepthLabel.text = "عمق البحث: ${settings.searchDepth}"
        }

        sliderMoveTime.value = settings.moveTimeMs.toFloat().coerceIn(sliderMoveTime.valueFrom, sliderMoveTime.valueTo)
        tvMoveTimeLabel.text = "زمن التفكير: ${settings.moveTimeMs} مللي ثانية"
        sliderMoveTime.addOnChangeListener { _, v, _ ->
            settings.moveTimeMs = v.toInt()
            tvMoveTimeLabel.text = "زمن التفكير: ${settings.moveTimeMs} مللي ثانية"
        }

        btnColorCycle.text = "لون اللاعب: ${settings.playerColorMode.labelAr}"
        btnColorCycle.setOnClickListener {
            val next = PlayerColorMode.entries[(settings.playerColorMode.ordinal + 1) % PlayerColorMode.entries.size]
            settings.playerColorMode = next
            btnColorCycle.text = "لون اللاعب: ${next.labelAr}"
        }

        btnTurnCycle.text = "تحديد الدور: ${settings.turnMode.labelAr}"
        btnTurnCycle.setOnClickListener {
            val next = TurnMode.entries[(settings.turnMode.ordinal + 1) % TurnMode.entries.size]
            settings.turnMode = next
            btnTurnCycle.text = "تحديد الدور: ${next.labelAr}"
        }

        switchShowArrows.isChecked = settings.showArrows
        switchShowArrows.setOnCheckedChangeListener { _, checked -> settings.showArrows = checked }

        switchShowEval.isChecked = settings.showEvalBar
        switchShowEval.setOnCheckedChangeListener { _, checked -> settings.showEvalBar = checked }

        switchShowGrid.isChecked = settings.showBoardGrid
        switchShowGrid.setOnCheckedChangeListener { _, checked -> settings.showBoardGrid = checked }
    }

    private fun observeServiceState() {
        lifecycleScope.launch {
            ChessAssistantService.state.collect { st ->
                btnStartStop.text = if (st.isRunning) "إيقاف المساعد العائم" else "تشغيل المساعد العائم"
                tvLiveStatus.text = "الحالة: ${st.statusText}"
                if (st.currentFen.isNotEmpty()) {
                    tvLiveFen.text = "FEN (${st.confidencePercent}%): ${st.currentFen}"
                }
            }
        }
    }

    override fun onDestroy() {
        testEngine.shutdown()
        super.onDestroy()
    }
}
