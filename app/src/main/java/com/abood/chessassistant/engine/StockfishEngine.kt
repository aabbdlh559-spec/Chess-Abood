package com.abood.chessassistant.engine

import android.content.Context
import com.abood.chessassistant.model.EngineAnalysis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter

class StockfishEngine(private val context: Context) {

    private var externalProcess: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    private var activeEngineName: String = "Stockfish الرسمي"
    val kotlinEngine = KotlinChessEngine()

    fun getEngineDisplayName(): String {
        val sfFile = findOfficialStockfishBinary()
        return when {
            sfFile != null -> "Stockfish الرسمي (${sfFile.name})"
            NativeChessBridge.isNativeAvailable -> "محرك Chess Abood الأصلي (C++17 JNI)"
            else -> "محرك Chess Abood الذكي + كتاب الافتتاحيات"
        }
    }

    private fun findOfficialStockfishBinary(): File? {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val sfLib = File(nativeDir, "libstockfish.so")
        if (sfLib.exists() && sfLib.canExecute()) return sfLib
        return null
    }

    private fun ensureExternalProcess(): Boolean {
        val sfFile = findOfficialStockfishBinary() ?: return false
        if (externalProcess != null && externalProcess?.isAlive == true) return true
        return try {
            val proc = ProcessBuilder(sfFile.absolutePath)
                .redirectErrorStream(true)
                .start()
            writer = BufferedWriter(OutputStreamWriter(proc.outputStream))
            reader = BufferedReader(InputStreamReader(proc.inputStream))
            writer?.write("uci\n")
            writer?.flush()
            var line: String?
            while (reader?.readLine().also { line = it } != null) {
                val currentLine = line ?: continue
                if (currentLine.startsWith("id name ")) {
                    activeEngineName = currentLine.removePrefix("id name ").trim()
                }
                if (currentLine == "uciok") break
            }
            writer?.write("isready\n")
            writer?.flush()
            while (reader?.readLine().also { line = it } != null) {
                if (line == "readyok") break
            }
            externalProcess = proc
            true
        } catch (_: Exception) {
            externalProcess = null
            false
        }
    }

    suspend fun analyze(
        fen: String,
        boardGrid: Array<CharArray>?,
        maxDepth: Int,
        moveTimeMs: Int
    ): EngineAnalysis = withContext(Dispatchers.Default) {
        when {
            ensureExternalProcess() -> analyzeWithExternalStockfish(fen, boardGrid, maxDepth, moveTimeMs)
            NativeChessBridge.isNativeAvailable -> analyzeWithBuiltInJni(fen, boardGrid, maxDepth, moveTimeMs)
            else -> analyzeWithKotlinEngine(fen, boardGrid, maxDepth, moveTimeMs)
        }
    }

    private fun analyzeWithExternalStockfish(
        fen: String,
        boardGrid: Array<CharArray>?,
        maxDepth: Int,
        moveTimeMs: Int
    ): EngineAnalysis {
        val w = writer ?: return analyzeWithKotlinEngine(fen, boardGrid, maxDepth, moveTimeMs)
        val r = reader ?: return analyzeWithKotlinEngine(fen, boardGrid, maxDepth, moveTimeMs)
        val startMs = System.currentTimeMillis()
        var bestMove = "0000"
        var ponderMove = ""
        var scoreCp = 0
        var mateIn = 0
        var depth = 0
        var nodes = 0L

        return try {
            w.write("position fen $fen\n")
            w.write("go depth $maxDepth movetime $moveTimeMs\n")
            w.flush()
            var line: String?
            while (r.readLine().also { line = it } != null) {
                val l = line ?: continue
                if (l.startsWith("info ") && l.contains(" pv ")) {
                    val tokens = l.split(" ")
                    val dIdx = tokens.indexOf("depth")
                    if (dIdx != -1 && dIdx + 1 < tokens.size) depth = tokens[dIdx + 1].toIntOrNull() ?: depth
                    val nIdx = tokens.indexOf("nodes")
                    if (nIdx != -1 && nIdx + 1 < tokens.size) nodes = tokens[nIdx + 1].toLongOrNull() ?: nodes
                    val sIdx = tokens.indexOf("score")
                    if (sIdx != -1 && sIdx + 2 < tokens.size) {
                        if (tokens[sIdx + 1] == "cp") {
                            scoreCp = tokens[sIdx + 2].toIntOrNull() ?: 0
                            mateIn = 0
                        } else if (tokens[sIdx + 1] == "mate") {
                            mateIn = tokens[sIdx + 2].toIntOrNull() ?: 0
                            scoreCp = if (mateIn > 0) 29000 else -29000
                        }
                    }
                } else if (l.startsWith("bestmove ")) {
                    val parts = l.split(" ")
                    if (parts.size >= 2) bestMove = parts[1]
                    if (parts.size >= 4 && parts[2] == "ponder") ponderMove = parts[3]
                    break
                }
            }
            EngineAnalysis(
                bestMoveUci = bestMove,
                ponderMoveUci = ponderMove,
                bestMoveArabic = formatMoveArabic(bestMove, boardGrid),
                scoreCp = scoreCp,
                mateIn = mateIn,
                depth = depth,
                nodes = nodes,
                elapsedMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L),
                engineLabel = activeEngineName
            )
        } catch (_: Exception) {
            analyzeWithKotlinEngine(fen, boardGrid, maxDepth, moveTimeMs)
        }
    }

    private fun analyzeWithBuiltInJni(
        fen: String,
        boardGrid: Array<CharArray>?,
        maxDepth: Int,
        moveTimeMs: Int
    ): EngineAnalysis {
        return try {
            val raw = NativeChessBridge.analyzeFenNative(fen, maxDepth, moveTimeMs)
            val parts = raw.split("|")
            val best = parts.getOrElse(0) { "0000" }
            val ponder = parts.getOrElse(1) { "" }
            val cp = parts.getOrElse(2) { "0" }.toIntOrNull() ?: 0
            val mate = parts.getOrElse(3) { "0" }.toIntOrNull() ?: 0
            val depth = parts.getOrElse(4) { "1" }.toIntOrNull() ?: 1
            val nodes = parts.getOrElse(5) { "0" }.toLongOrNull() ?: 0L
            val elapsed = parts.getOrElse(6) { "1" }.toLongOrNull() ?: 1L

            EngineAnalysis(
                bestMoveUci = best,
                ponderMoveUci = ponder,
                bestMoveArabic = formatMoveArabic(best, boardGrid),
                scoreCp = cp,
                mateIn = mate,
                depth = depth,
                nodes = nodes,
                elapsedMs = elapsed,
                engineLabel = getEngineDisplayName()
            )
        } catch (_: Throwable) {
            analyzeWithKotlinEngine(fen, boardGrid, maxDepth, moveTimeMs)
        }
    }

    private fun analyzeWithKotlinEngine(
        fen: String,
        boardGrid: Array<CharArray>?,
        maxDepth: Int,
        moveTimeMs: Int
    ): EngineAnalysis {
        val res = kotlinEngine.search(fen, maxDepth.coerceAtMost(11), moveTimeMs)
        return EngineAnalysis(
            bestMoveUci = res.bestMoveUci,
            ponderMoveUci = res.ponderMoveUci,
            bestMoveArabic = formatMoveArabic(res.bestMoveUci, boardGrid),
            scoreCp = res.scoreCp,
            mateIn = res.mateIn,
            depth = res.depth,
            nodes = res.nodes,
            elapsedMs = res.elapsedMs,
            engineLabel = getEngineDisplayName()
        )
    }

    fun stopCurrentSearch() {
        kotlinEngine.stopRequested = true
        if (NativeChessBridge.isNativeAvailable) {
            try { NativeChessBridge.stopSearchNative() } catch (_: Throwable) {}
        }
        try {
            writer?.write("stop\n")
            writer?.flush()
        } catch (_: Exception) {
        }
    }

    fun shutdown() {
        stopCurrentSearch()
        try {
            writer?.write("quit\n")
            writer?.flush()
            externalProcess?.destroy()
        } catch (_: Exception) {
        }
        externalProcess = null
    }

    companion object {
        fun formatMoveArabic(uci: String, grid: Array<CharArray>?): String {
            if (uci.length < 4 || uci == "0000") return "لا توجد نقلة"
            val fromFile = uci[0] - 'a'
            val fromRank = 8 - (uci[1] - '0')
            val toSq = uci.substring(2, 4)
            val pieceChar = if (grid != null && fromRank in 0..7 && fromFile in 0..7) {
                grid[fromRank][fromFile]
            } else '.'

            val pieceName = when (pieceChar.lowercaseChar()) {
                'k' -> "ملك"
                'q' -> "وزير"
                'r' -> "رخ"
                'b' -> "فيل"
                'n' -> "حصان"
                'p' -> "بيدق"
                else -> "قطعة"
            }
            return "$pieceName ← $toSq ($uci)"
        }
    }
}
