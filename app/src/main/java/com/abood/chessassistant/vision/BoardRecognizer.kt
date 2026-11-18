package com.abood.chessassistant.vision

import android.graphics.Bitmap
import android.graphics.RectF
import com.abood.chessassistant.data.SettingsRepository
import com.abood.chessassistant.engine.KotlinChessEngine
import com.abood.chessassistant.model.BoardRectRatio
import com.abood.chessassistant.model.PlayerColorMode
import com.abood.chessassistant.model.RecognitionResult
import com.abood.chessassistant.model.TurnMode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

class BoardRecognizer(
    private val settings: SettingsRepository,
    private val chessEngine: KotlinChessEngine
) {
    private var learnedTemplates: MutableMap<Char, FloatArray> =
        settings.loadLearnedTemplates().toMutableMap()

    // متتبع حالة المباراة الذكي
    private var trackedFen: String? = null
    private var trackedGrid: Array<CharArray>? = null
    private var trackedWhiteToMove: Boolean = true
    private var trackedUserIsWhite: Boolean? = null

    fun resetTracking() {
        trackedFen = null
        trackedGrid = null
        trackedUserIsWhite = null
    }

    /**
     * اكتشاف حدود رقعة الشطرنج (8×8) تلقائياً من لقطة الشاشة دون ضبط يدوي.
     */
    fun autoDetectBoardRect(fullScreen: Bitmap): BoardRectRatio? {
        val sw = fullScreen.width
        val sh = fullScreen.height
        if (sw < 200 || sh < 300) return null

        // تصغير سريع إلى عرض 360px لتحليل الحواف في أقل من 15 مللي ثانية
        val scale = 360f / sw
        val w = 360
        val h = (sh * scale).toInt().coerceAtLeast(200)
        val small = Bitmap.createScaledBitmap(fullScreen, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        small.recycle()

        fun colorDiff(c1: Int, c2: Int): Float {
            val dr = ((c1 shr 16) and 0xFF) - ((c2 shr 16) and 0xFF)
            val dg = ((c1 shr 8) and 0xFF) - ((c2 shr 8) and 0xFF)
            val db = (c1 and 0xFF) - (c2 and 0xFF)
            return sqrt((dr * dr + dg * dg + db * db).toFloat())
        }

        val rowEdge = FloatArray(h)
        val xStart = (w * 0.14f).toInt()
        val xEnd = (w * 0.86f).toInt()
        for (y in 1 until h - 1) {
            var sum = 0f
            for (x in xStart until xEnd) {
                sum += colorDiff(px[(y - 1) * w + x], px[(y + 1) * w + x])
            }
            rowEdge[y] = sum / (xEnd - xStart)
        }

        val colEdge = FloatArray(w)
        var bestScore = 0f
        var bestX0 = 0
        var bestY0 = 0
        var bestSize = 0

        val minSize = (w * 0.68f).toInt()
        val maxSize = (w * 0.99f).toInt()

        for (size in minSize..maxSize step 3) {
            val sq = size / 8f
            val yMin = (h * 0.08f).toInt()
            val yMax = (h - size - h * 0.06f).toInt()
            for (y0 in yMin..yMax step 3) {
                var yScore = 0f
                for (i in 0..8) {
                    val ly = (y0 + i * sq).roundToInt().coerceIn(1, h - 2)
                    val peak = max(rowEdge[ly], max(rowEdge[ly - 1], rowEdge[ly + 1]))
                    yScore += peak
                }
                if (yScore < 160f) continue

                // حساب الحواف الأفقية ضمن نطاق هذا الارتفاع المرشح
                val yMidStart = (y0 + sq * 0.25f).toInt().coerceIn(0, h - 1)
                val yMidEnd = (y0 + size - sq * 0.25f).toInt().coerceIn(yMidStart + 1, h)
                for (x in 1 until w - 1) {
                    var sum = 0f
                    for (y in yMidStart until yMidEnd step 2) {
                        sum += colorDiff(px[y * w + (x - 1)], px[y * w + (x + 1)])
                    }
                    colEdge[x] = sum / max(1, (yMidEnd - yMidStart) / 2)
                }

                for (x0 in 0..(w - size) step 2) {
                    var xScore = 0f
                    for (i in 0..8) {
                        val lx = (x0 + i * sq).roundToInt().coerceIn(1, w - 2)
                        val peak = max(colEdge[lx], max(colEdge[lx - 1], colEdge[lx + 1]))
                        xScore += peak
                    }
                    val total = yScore + xScore
                    if (total > bestScore) {
                        bestScore = total
                        bestX0 = x0
                        bestY0 = y0
                        bestSize = size
                    }
                }
            }
        }

        if (bestSize < minSize || bestScore < 340f) return null
        return BoardRectRatio(
            left = (bestX0.toFloat() / w).coerceIn(0f, 0.9f),
            top = (bestY0.toFloat() / h).coerceIn(0f, 0.9f),
            right = ((bestX0 + bestSize).toFloat() / w).coerceIn(0.1f, 1f),
            bottom = ((bestY0 + bestSize).toFloat() / h).coerceIn(0.1f, 1f)
        )
    }

    /**
     * تعلّم قوالب القطع الـ 12 من وضع البداية الحالي للرقعة.
     */
    fun learnFromCurrentBoard(fullScreen: Bitmap, boardRect: RectF): Boolean {
        val analysis = extractBoardSquares(fullScreen, boardRect) ?: return false
        val bottomWhite = (6..7).sumOf { r -> (0..7).count { c -> analysis.squares[r][c]?.isWhite == true } }
        val bottomBlack = (6..7).sumOf { r -> (0..7).count { c -> analysis.squares[r][c]?.isWhite == false } }
        val flipped = bottomBlack > bottomWhite

        val startRows = arrayOf(
            "rnbqkbnr",
            "pppppppp",
            "........",
            "........",
            "........",
            "........",
            "PPPPPPPP",
            "RNBQKBNR"
        )

        val buckets = mutableMapOf<Char, MutableList<FloatArray>>()
        for (r in 0..7) {
            for (c in 0..7) {
                val srcR = if (flipped) 7 - r else r
                val srcC = if (flipped) 7 - c else c
                val expected = startRows[r][c]
                val sq = analysis.squares[srcR][srcC]
                if (expected != '.' && sq != null) {
                    buckets.getOrPut(expected) { mutableListOf() }.add(sq.templateVec)
                }
            }
        }
        if (buckets.size < 10) return false

        for ((piece, list) in buckets) {
            val avg = FloatArray(576)
            for (v in list) {
                for (i in 0 until 576) avg[i] += v[i]
            }
            var norm = 0f
            for (i in 0 until 576) norm += avg[i] * avg[i]
            norm = sqrt(norm)
            if (norm > 1e-5f) {
                for (i in 0 until 576) avg[i] /= norm
                learnedTemplates[piece] = avg
            }
        }
        settings.saveLearnedTemplates(learnedTemplates)

        // تثبيت وضع البداية في المتتبع الذكي فوراً
        val startFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        chessEngine.setFen(startFen)
        trackedFen = startFen
        trackedGrid = chessEngine.exportGrid()
        trackedWhiteToMove = true
        trackedUserIsWhite = !flipped
        return true
    }

    /**
     * التحليل البصري الذكي للرقعة + تتبع النقلات القانونية + التحقق من صحة FEN.
     */
    fun recognize(fullScreen: Bitmap, boardRect: RectF): RecognitionResult? {
        val boardData = extractBoardSquares(fullScreen, boardRect) ?: return null
        if (boardData.occupiedCount < 2) return null

        // 1. هل الرقعة في وضع البداية القياسي (32 قطعة في الصفين العلويين والسفليين)؟
        val topTwoFull = (0..1).all { r -> (0..7).all { c -> boardData.squares[r][c] != null } }
        val botTwoFull = (6..7).all { r -> (0..7).all { c -> boardData.squares[r][c] != null } }
        val midFourEmpty = (2..5).all { r -> (0..7).all { c -> boardData.squares[r][c] == null } }

        if (topTwoFull && botTwoFull && midFourEmpty) {
            if (settings.autoLearnPieces && learnedTemplates.size < 12) {
                learnFromCurrentBoard(fullScreen, boardRect)
            }
            val botW = (6..7).sumOf { r -> (0..7).count { c -> boardData.squares[r][c]?.isWhite == true } }
            val botB = (6..7).sumOf { r -> (0..7).count { c -> boardData.squares[r][c]?.isWhite == false } }
            val flipped = when (settings.playerColorMode) {
                PlayerColorMode.WHITE -> false
                PlayerColorMode.BLACK -> true
                PlayerColorMode.AUTO -> botB > botW
            }
            val startFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
            chessEngine.setFen(startFen)
            val grid = chessEngine.exportGrid()
            trackedFen = startFen
            trackedGrid = grid
            trackedWhiteToMove = true
            trackedUserIsWhite = !flipped
            return RecognitionResult(
                fen = startFen,
                boardGrid = grid,
                isFlipped = flipped,
                whiteToMove = true,
                occupiedCount = 32,
                confidencePercent = 99,
                trackingModeLabel = "وضع البداية المؤكد"
            )
        }

        // 2. تحديد اتجاه الرقعة (هل اللاعب يلعب بالأسود أم بالأبيض؟)
        val flipped = when (settings.playerColorMode) {
            PlayerColorMode.WHITE -> false
            PlayerColorMode.BLACK -> true
            PlayerColorMode.AUTO -> {
                trackedUserIsWhite?.let { !it } ?: run {
                    val botW = (6..7).sumOf { r -> (0..7).count { c -> boardData.squares[r][c]?.isWhite == true } }
                    val botB = (6..7).sumOf { r -> (0..7).count { c -> boardData.squares[r][c]?.isWhite == false } }
                    botB > botW
                }
            }
        }
        trackedUserIsWhite = !flipped

        // بناء خريطة اللون المرصود في كل مربع (0=فارغ، 1=أبيض، 2=أسود) بالإحداثيات القياسية
        val observedColorMap = Array(8) { r ->
            IntArray(8) { c ->
                val srcR = if (flipped) 7 - r else r
                val srcC = if (flipped) 7 - c else c
                val sq = boardData.squares[srcR][srcC]
                when {
                    sq == null -> 0
                    sq.isWhite -> 1
                    else -> 2
                }
            }
        }

        // 3. محاولة مطابقة الرقعة مع نقلة قانونية (1 نقلة أو نقلتين) من الحالة السابقة المؤكدة!
        val prevFen = trackedFen
        if (prevFen != null) {
            // هل الوضع مطابق تماماً للحالة السابقة؟
            val prevGrid = trackedGrid
            if (prevGrid != null && gridMatchesColors(prevGrid, observedColorMap)) {
                val turn = resolveTurn(trackedWhiteToMove, boardData, flipped)
                val fen = rebuildFenTurn(prevFen, turn)
                return RecognitionResult(
                    fen = fen,
                    boardGrid = prevGrid,
                    isFlipped = flipped,
                    whiteToMove = turn,
                    occupiedCount = boardData.occupiedCount,
                    confidencePercent = 98,
                    trackingModeLabel = "تتبع ذكي مستقر"
                )
            }

            // البحث عن نقلة قانونية واحدة تطابق اللون ومواقع القطع الجديدة
            val transitions1 = chessEngine.generateLegalTransitions(prevFen)
            val match1 = transitions1.firstOrNull { gridMatchesColors(it.resultingGrid, observedColorMap) }
            if (match1 != null) {
                val turn = resolveTurn(match1.nextWhiteToMove, boardData, flipped)
                val finalFen = rebuildFenTurn(match1.resultingFen, turn)
                trackedFen = finalFen
                trackedGrid = match1.resultingGrid
                trackedWhiteToMove = turn
                return RecognitionResult(
                    fen = finalFen,
                    boardGrid = match1.resultingGrid,
                    isFlipped = flipped,
                    whiteToMove = turn,
                    occupiedCount = boardData.occupiedCount,
                    confidencePercent = 97,
                    trackingModeLabel = "تتبع نقلة: ${match1.uciMove}"
                )
            }

            // البحث على مستوى نقلتين (في حال لعب الخصم وردّ اللاعب بسرعة)
            for (t1 in transitions1) {
                val transitions2 = chessEngine.generateLegalTransitions(t1.resultingFen)
                val match2 = transitions2.firstOrNull { gridMatchesColors(it.resultingGrid, observedColorMap) }
                if (match2 != null) {
                    val turn = resolveTurn(match2.nextWhiteToMove, boardData, flipped)
                    val finalFen = rebuildFenTurn(match2.resultingFen, turn)
                    trackedFen = finalFen
                    trackedGrid = match2.resultingGrid
                    trackedWhiteToMove = turn
                    return RecognitionResult(
                        fen = finalFen,
                        boardGrid = match2.resultingGrid,
                        isFlipped = flipped,
                        whiteToMove = turn,
                        occupiedCount = boardData.occupiedCount,
                        confidencePercent = 94,
                        trackingModeLabel = "تتبع نقلتين"
                    )
                }
            }
        }

        // 4. القراءة البصرية المباشرة لمنتصف الدور + تصحيح القواعد القانونية للشطرنج
        val rawGrid = Array(8) { CharArray(8) { '.' } }
        var totalConf = 0f
        var countPieces = 0

        for (r in 0..7) {
            for (c in 0..7) {
                val srcR = if (flipped) 7 - r else r
                val srcC = if (flipped) 7 - c else c
                val item = boardData.squares[srcR][srcC] ?: continue
                val (ch, conf) = classifyPieceWithConfidence(item)
                rawGrid[r][c] = ch
                totalConf += conf
                countPieces++
            }
        }

        sanitizeChessLegality(rawGrid)

        val fallbackTurn = resolveTurn(!flipped, boardData, flipped)
        var finalFen = buildFenFromGrid(rawGrid, fallbackTurn)

        // التحقق من أن FEN الناتج قانوني قبل إرساله للمحرك
        if (!chessEngine.isPositionValid(finalFen)) {
            val toggledFen = buildFenFromGrid(rawGrid, !fallbackTurn)
            if (chessEngine.isPositionValid(toggledFen)) {
                finalFen = toggledFen
            } else {
                return null
            }
        }

        val actualTurn = finalFen.split(" ").getOrElse(1) { "w" } == "w"
        trackedFen = finalFen
        trackedGrid = rawGrid
        trackedWhiteToMove = actualTurn

        val avgConf = if (countPieces > 0) ((totalConf / countPieces) * 100f).toInt().coerceIn(72, 96) else 75
        return RecognitionResult(
            fen = finalFen,
            boardGrid = rawGrid,
            isFlipped = flipped,
            whiteToMove = actualTurn,
            occupiedCount = countPieces,
            confidencePercent = avgConf,
            trackingModeLabel = "مسح بصري مباشر"
        )
    }

    private fun resolveTurn(defaultTurn: Boolean, boardData: BoardSampleData, flipped: Boolean): Boolean {
        return when (settings.turnMode) {
            TurnMode.WHITE -> true
            TurnMode.BLACK -> false
            TurnMode.AUTO -> {
                // إذا وجدنا مربعين مظللين (آخر نقلة)، القطعة الموجودة على أحدهما هي آخر من تحرك!
                if (boardData.highlightedSquares.size == 2) {
                    for ((hr, hc) in boardData.highlightedSquares) {
                        val sq = boardData.squares[hr][hc]
                        if (sq != null) {
                            // إذا كانت القطعة التي تحركت للتو بيضاء، فالدور الآن للأسود والعكس!
                            return !sq.isWhite
                        }
                    }
                }
                defaultTurn
            }
        }
    }

    private fun gridMatchesColors(grid: Array<CharArray>, observedColors: Array<IntArray>): Boolean {
        var mismatches = 0
        for (r in 0..7) {
            for (c in 0..7) {
                val ch = grid[r][c]
                val expectedColor = when {
                    ch == '.' -> 0
                    ch.isUpperCase() -> 1
                    else -> 2
                }
                if (expectedColor != observedColors[r][c]) {
                    mismatches++
                    if (mismatches > 0) return false
                }
            }
        }
        return true
    }

    private fun sanitizeChessLegality(grid: Array<CharArray>) {
        // 1. منع وجود بيادق في الصف الأول أو الثامن
        for (c in 0..7) {
            if (grid[0][c] == 'P') grid[0][c] = 'Q'
            if (grid[0][c] == 'p') grid[0][c] = 'q'
            if (grid[7][c] == 'P') grid[7][c] = 'Q'
            if (grid[7][c] == 'p') grid[7][c] = 'q'
        }

        // 2. ضمان وجود ملك واحد بالضبط لكل لون
        for (isWhite in listOf(true, false)) {
            val kingChar = if (isWhite) 'K' else 'k'
            val queenChar = if (isWhite) 'Q' else 'q'
            val kings = mutableListOf<Pair<Int, Int>>()
            for (r in 0..7) {
                for (c in 0..7) {
                    if (grid[r][c] == kingChar) kings.add(r to c)
                }
            }
            if (kings.size > 1) {
                val homeR = if (isWhite) 7 else 0
                val best = kings.minByOrNull { (r, c) -> abs(r - homeR) * 2 + abs(c - 4) }!!
                for (pos in kings) {
                    if (pos != best) grid[pos.first][pos.second] = queenChar
                }
            } else if (kings.isEmpty()) {
                val homeR = if (isWhite) 7 else 0
                val candidates = mutableListOf<Pair<Int, Int>>()
                for (r in 0..7) {
                    for (c in 0..7) {
                        val ch = grid[r][c]
                        if (ch != '.' && ch.isUpperCase() == isWhite && ch.lowercaseChar() != 'p') {
                            candidates.add(r to c)
                        }
                    }
                }
                val chosen = candidates.minByOrNull { (r, c) -> abs(r - homeR) * 2 + abs(c - 4) }
                if (chosen != null) {
                    grid[chosen.first][chosen.second] = kingChar
                }
            }
        }
    }

    private fun classifyPieceWithConfidence(sq: SquareFeature): Pair<Char, Float> {
        val candidates = learnedTemplates.filterKeys { it.isUpperCase() == sq.isWhite }
        if (candidates.size >= 6) {
            var bestChar = if (sq.isWhite) 'P' else 'p'
            var bestScore = -1f
            for ((ch, tmpl) in candidates) {
                var dot = 0f
                for (i in 0 until 576) dot += sq.templateVec[i] * tmpl[i]
                if (dot > bestScore) {
                    bestScore = dot
                    bestChar = ch
                }
            }
            return bestChar to bestScore.coerceIn(0.6f, 0.99f)
        }

        val fill = sq.fillRatio
        val height = sq.heightRatio
        val asym = sq.asymX
        val topW = sq.topWidth
        val midW = sq.midWidth

        val base = when {
            asym > 0.055f && topW > 0.40f -> 'n'
            fill < 0.25f && height < 0.74f -> 'p'
            topW > 0.72f && abs(topW - midW) < 0.24f -> 'r'
            height > 0.76f && topW < 0.52f -> 'k'
            topW > 0.58f -> 'q'
            else -> 'b'
        }
        val finalCh = if (sq.isWhite) base.uppercaseChar() else base
        return finalCh to 0.80f
    }

    private data class SquareFeature(
        val isWhite: Boolean,
        val luminance: Float,
        val templateVec: FloatArray,
        val fillRatio: Float,
        val heightRatio: Float,
        val asymX: Float,
        val topWidth: Float,
        val midWidth: Float
    )

    private data class BoardSampleData(
        val squares: Array<Array<SquareFeature?>>,
        val occupiedCount: Int,
        val highlightedSquares: List<Pair<Int, Int>>
    )

    private fun extractBoardSquares(bmp: Bitmap, rect: RectF): BoardSampleData? {
        val left = rect.left.toInt().coerceIn(0, bmp.width - 16)
        val top = rect.top.toInt().coerceIn(0, bmp.height - 16)
        val w = rect.width().toInt().coerceAtMost(bmp.width - left)
        val h = rect.height().toInt().coerceAtMost(bmp.height - top)
        if (w < 64 || h < 64) return null

        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, left, top, w, h)

        val sqW = w / 8f
        val sqH = h / 8f

        // 1. حساب لون الخلفية المحلي لكل مربع من حوافه الأربعة (يمنع خداع المربعات المظللة!)
        val localBg = Array(8) { Array(8) { IntArray(3) } }
        val paritySamples = Array(2) { mutableListOf<IntArray>() }

        for (r in 0..7) {
            for (c in 0..7) {
                val x0 = (c * sqW).toInt()
                val y0 = (r * sqH).toInt()
                val sw = max(8, sqW.toInt())
                val sh = max(8, sqH.toInt())
                val rimX = max(2, (sw * 0.08f).toInt())
                val rimY = max(2, (sh * 0.08f).toInt())

                val samples = ArrayList<IntArray>(sw * 2 + sh * 2)
                for (xx in 2 until (sw - 2)) {
                    val pTop = pixels[(y0 + 1).coerceAtMost(h - 1) * w + (x0 + xx).coerceAtMost(w - 1)]
                    val pBot = pixels[(y0 + sh - 2).coerceAtMost(h - 1) * w + (x0 + xx).coerceAtMost(w - 1)]
                    samples.add(intArrayOf((pTop shr 16) and 0xFF, (pTop shr 8) and 0xFF, pTop and 0xFF))
                    samples.add(intArrayOf((pBot shr 16) and 0xFF, (pBot shr 8) and 0xFF, pBot and 0xFF))
                }
                for (yy in rimY until (sh - rimY)) {
                    val pLeft = pixels[(y0 + yy).coerceAtMost(h - 1) * w + (x0 + 1).coerceAtMost(w - 1)]
                    val pRight = pixels[(y0 + yy).coerceAtMost(h - 1) * w + (x0 + sw - 2).coerceAtMost(w - 1)]
                    samples.add(intArrayOf((pLeft shr 16) and 0xFF, (pLeft shr 8) and 0xFF, pLeft and 0xFF))
                    samples.add(intArrayOf((pRight shr 16) and 0xFF, (pRight shr 8) and 0xFF, pRight and 0xFF))
                }
                val med = medianRgb(samples)
                localBg[r][c] = med
                paritySamples[(r + c) % 2].add(med)
            }
        }

        val bg0 = medianRgb(paritySamples[0])
        val bg1 = medianRgb(paritySamples[1])
        val highlighted = mutableListOf<Pair<Int, Int>>()
        for (r in 0..7) {
            for (c in 0..7) {
                val ref = if ((r + c) % 2 == 0) bg0 else bg1
                val cur = localBg[r][c]
                val d = sqrt(
                    ((cur[0] - ref[0]) * (cur[0] - ref[0]) +
                            (cur[1] - ref[1]) * (cur[1] - ref[1]) +
                            (cur[2] - ref[2]) * (cur[2] - ref[2])).toFloat()
                )
                if (d > 26f) highlighted.add(r to c)
            }
        }

        // 2. استخراج شكل القطعة داخل كل مربع مقارنة بخلفية المربع نفسه
        val rawSquares = Array(8) { arrayOfNulls<SquareFeature>(8) }
        val allLums = mutableListOf<Float>()
        var occCount = 0

        for (r in 0..7) {
            for (c in 0..7) {
                val x0 = (c * sqW).toInt()
                val y0 = (r * sqH).toInt()
                val sw = max(8, sqW.toInt())
                val sh = max(8, sqH.toInt())
                val bg = localBg[r][c]

                // تجاهل الحواف الخارجية (10%) لتفادي أرقام وحروف الإحداثيات
                val padX = (sw * 0.10f).toInt()
                val padY = (sh * 0.10f).toInt()
                val innerW = max(4, sw - 2 * padX)
                val innerH = max(4, sh - 2 * padY)

                val mask = BooleanArray(innerW * innerH)
                val coreLums = mutableListOf<Float>()
                var fgCount = 0
                var minY = innerH
                var maxY = 0
                var minX = innerW
                var maxX = 0
                var sumX = 0f

                for (yy in 0 until innerH) {
                    val rowOffset = (y0 + padY + yy).coerceAtMost(h - 1) * w
                    for (xx in 0 until innerW) {
                        val px = pixels[rowOffset + (x0 + padX + xx).coerceAtMost(w - 1)]
                        val pr = (px shr 16) and 0xFF
                        val pg = (px shr 8) and 0xFF
                        val pb = px and 0xFF
                        val dr = pr - bg[0]
                        val dg = pg - bg[1]
                        val db = pb - bg[2]
                        val dist = sqrt((dr * dr + dg * dg + db * db).toFloat())
                        if (dist > 36f) {
                            mask[yy * innerW + xx] = true
                            fgCount++
                            if (yy < minY) minY = yy
                            if (yy > maxY) maxY = yy
                            if (xx < minX) minX = xx
                            if (xx > maxX) maxX = xx
                            sumX += xx
                            val lum = 0.299f * pr + 0.587f * pg + 0.114f * pb
                            coreLums.add(lum)
                        }
                    }
                }

                val fgRatio = fgCount.toFloat() / (innerW * innerH)
                if (fgRatio < 0.055f || coreLums.isEmpty()) continue

                coreLums.sort()
                val medLum = coreLums[coreLums.size / 2]
                allLums.add(medLum)
                occCount++

                val vec = FloatArray(576)
                var norm = 0f
                for (gy in 0 until 24) {
                    val sy = (gy * innerH / 24).coerceIn(0, innerH - 1)
                    for (gx in 0 until 24) {
                        val sx = (gx * innerW / 24).coerceIn(0, innerW - 1)
                        val v = if (mask[sy * innerW + sx]) 1f else 0f
                        vec[gy * 24 + gx] = v
                        norm += v * v
                    }
                }
                norm = sqrt(norm)
                if (norm > 1e-5f) {
                    for (i in 0 until 576) vec[i] /= norm
                }

                val boxH = max(1, maxY - minY + 1)
                val boxW = max(1, maxX - minX + 1)
                val heightRatio = boxH.toFloat() / innerH
                val comX = ((sumX / fgCount) - minX) / boxW
                val asymX = abs(comX - 0.5f)

                val topRowY = (minY + boxH * 0.18f).toInt().coerceIn(0, innerH - 1)
                var topSpan = 0
                for (xx in minX..maxX) if (mask[topRowY * innerW + xx]) topSpan++
                val topWidth = topSpan.toFloat() / boxW

                val midRowY = (minY + boxH * 0.50f).toInt().coerceIn(0, innerH - 1)
                var midSpan = 0
                for (xx in minX..maxX) if (mask[midRowY * innerW + xx]) midSpan++
                val midWidth = midSpan.toFloat() / boxW

                rawSquares[r][c] = SquareFeature(
                    isWhite = medLum > 140f,
                    luminance = medLum,
                    templateVec = vec,
                    fillRatio = fgRatio,
                    heightRatio = heightRatio,
                    asymX = asymX,
                    topWidth = topWidth,
                    midWidth = midWidth
                )
            }
        }

        // 3. تجميع تكيفي ثنائي (K-Means / Midpoint) للفصل الدقيق بين القطع البيضاء والسوداء
        if (allLums.size >= 2) {
            allLums.sort()
            val adaptiveThreshold = (allLums.first() + allLums.last()) * 0.5f
            for (r in 0..7) {
                for (c in 0..7) {
                    val sq = rawSquares[r][c] ?: continue
                    rawSquares[r][c] = sq.copy(isWhite = sq.luminance > adaptiveThreshold)
                }
            }
        }

        return BoardSampleData(rawSquares, occCount, highlighted)
    }

    private fun medianRgb(samples: List<IntArray>): IntArray {
        if (samples.isEmpty()) return intArrayOf(200, 200, 200)
        val r = samples.map { it[0] }.sorted()[samples.size / 2]
        val g = samples.map { it[1] }.sorted()[samples.size / 2]
        val b = samples.map { it[2] }.sorted()[samples.size / 2]
        return intArrayOf(r, g, b)
    }

    private fun rebuildFenTurn(fen: String, whiteToMove: Boolean): String {
        val parts = fen.split(" ").toMutableList()
        if (parts.size >= 2) {
            parts[1] = if (whiteToMove) "w" else "b"
            return parts.joinToString(" ")
        }
        return fen
    }

    private fun buildFenFromGrid(grid: Array<CharArray>, whiteToMove: Boolean): String {
        val sb = StringBuilder()
        for (r in 0..7) {
            var empty = 0
            for (c in 0..7) {
                val ch = grid[r][c]
                if (ch == '.') {
                    empty++
                } else {
                    if (empty > 0) {
                        sb.append(empty)
                        empty = 0
                    }
                    sb.append(ch)
                }
            }
            if (empty > 0) sb.append(empty)
            if (r < 7) sb.append('/')
        }

        val castles = StringBuilder()
        if (grid[7][4] == 'K' && grid[7][7] == 'R') castles.append('K')
        if (grid[7][4] == 'K' && grid[7][0] == 'R') castles.append('Q')
        if (grid[0][4] == 'k' && grid[0][7] == 'r') castles.append('k')
        if (grid[0][4] == 'k' && grid[0][0] == 'r') castles.append('q')
        val castleStr = if (castles.isEmpty()) "-" else castles.toString()

        val turnChar = if (whiteToMove) 'w' else 'b'
        return "$sb $turnChar $castleStr - 0 1"
    }
}
