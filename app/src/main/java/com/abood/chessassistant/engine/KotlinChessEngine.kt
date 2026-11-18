package com.abood.chessassistant.engine

import kotlin.math.abs
import kotlin.math.min

class KotlinChessEngine {

    data class Result(
        val bestMoveUci: String,
        val ponderMoveUci: String,
        val scoreCp: Int,
        val mateIn: Int,
        val depth: Int,
        val nodes: Long,
        val elapsedMs: Long
    )

    data class LegalTransition(
        val uciMove: String,
        val resultingGrid: Array<CharArray>,
        val resultingFen: String,
        val nextWhiteToMove: Boolean
    )

    private val board = IntArray(64)
    private var side = 0 // 0=White, 1=Black
    private var rootBestMove = 0
    private var castling = 15 // 1=K, 2=Q, 4=k, 8=q
    private var epSquare = -1
    private var hash = 0L

    private val zobristPiece = Array(16) { LongArray(64) }
    private val zobristCastle = LongArray(16)
    private val zobristEp = LongArray(65)
    private var zobristSide = 0L

    private val ttSize = 1 shl 15
    private val ttMask = ttSize - 1
    private val ttKey = LongArray(ttSize)
    private val ttDepth = IntArray(ttSize) { -1 }
    private val ttScore = IntArray(ttSize)
    private val ttFlag = IntArray(ttSize)
    private val ttMove = IntArray(ttSize)

    private val killer0 = IntArray(64)
    private val killer1 = IntArray(64)
    private val history = Array(16) { IntArray(64) }

    @Volatile
    var stopRequested = false
    private var nodes = 0L
    private var startTimeMs = 0L
    private var timeLimitMs = 600L

    init {
        var seed = -7046029254386353131L
        fun nextRand(): Long {
            seed = seed xor (seed ushr 12)
            seed = seed xor (seed shl 25)
            seed = seed xor (seed ushr 27)
            return seed * 2685821657736338717L
        }
        for (p in 0 until 16) {
            for (s in 0 until 64) {
                zobristPiece[p][s] = nextRand()
            }
        }
        for (c in 0 until 16) zobristCastle[c] = nextRand()
        for (e in 0 until 65) zobristEp[e] = nextRand()
        zobristSide = nextRand()
        setFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
    }

    fun setFen(fen: String) {
        board.fill(0)
        val parts = fen.trim().split(Regex("\\s+"))
        val placement = parts.getOrElse(0) { "8/8/8/8/8/8/8/8" }
        val turn = parts.getOrElse(1) { "w" }
        val castleStr = parts.getOrElse(2) { "-" }
        val epStr = parts.getOrElse(3) { "-" }

        var rank = 7
        var file = 0
        for (c in placement) {
            if (c == '/') {
                rank--
                file = 0
            } else if (c in '1'..'8') {
                file += (c - '0')
            } else if (rank in 0..7 && file in 0..7) {
                val sq = rank * 8 + file
                board[sq] = when (c) {
                    'P' -> 1; 'N' -> 2; 'B' -> 3; 'R' -> 4; 'Q' -> 5; 'K' -> 6
                    'p' -> 9; 'n' -> 10; 'b' -> 11; 'r' -> 12; 'q' -> 13; 'k' -> 14
                    else -> 0
                }
                file++
            }
        }
        side = if (turn == "b") 1 else 0
        castling = 0
        if ('K' in castleStr) castling = castling or 1
        if ('Q' in castleStr) castling = castling or 2
        if ('k' in castleStr) castling = castling or 4
        if ('q' in castleStr) castling = castling or 8

        epSquare = if (epStr.length == 2 && epStr[0] in 'a'..'h' && epStr[1] in '1'..'8') {
            (epStr[1] - '1') * 8 + (epStr[0] - 'a')
        } else -1

        computeHash()
    }

    fun exportGrid(): Array<CharArray> {
        val grid = Array(8) { CharArray(8) { '.' } }
        for (rank in 0..7) {
            val row = 7 - rank
            for (file in 0..7) {
                val p = board[rank * 8 + file]
                grid[row][file] = when (p) {
                    1 -> 'P'; 2 -> 'N'; 3 -> 'B'; 4 -> 'R'; 5 -> 'Q'; 6 -> 'K'
                    9 -> 'p'; 10 -> 'n'; 11 -> 'b'; 12 -> 'r'; 13 -> 'q'; 14 -> 'k'
                    else -> '.'
                }
            }
        }
        return grid
    }

    fun exportFen(): String {
        val sb = StringBuilder()
        for (rank in 7 downTo 0) {
            var empty = 0
            for (file in 0..7) {
                val p = board[rank * 8 + file]
                if (p == 0) {
                    empty++
                } else {
                    if (empty > 0) {
                        sb.append(empty)
                        empty = 0
                    }
                    sb.append(
                        when (p) {
                            1 -> 'P'; 2 -> 'N'; 3 -> 'B'; 4 -> 'R'; 5 -> 'Q'; 6 -> 'K'
                            9 -> 'p'; 10 -> 'n'; 11 -> 'b'; 12 -> 'r'; 13 -> 'q'; 14 -> 'k'
                            else -> '?'
                        }
                    )
                }
            }
            if (empty > 0) sb.append(empty)
            if (rank > 0) sb.append('/')
        }
        val cs = StringBuilder()
        if ((castling and 1) != 0) cs.append('K')
        if ((castling and 2) != 0) cs.append('Q')
        if ((castling and 4) != 0) cs.append('k')
        if ((castling and 8) != 0) cs.append('q')
        val castlePart = if (cs.isEmpty()) "-" else cs.toString()
        val epPart = if (epSquare in 0..63) {
            "${('a'.code + (epSquare and 7)).toChar()}${('1'.code + (epSquare ushr 3)).toChar()}"
        } else "-"
        return "$sb ${if (side == 0) 'w' else 'b'} $castlePart $epPart 0 1"
    }

    @Synchronized
    fun isPositionValid(fen: String): Boolean {
        setFen(fen)
        var wKings = 0
        var bKings = 0
        for (sq in 0 until 64) {
            if (board[sq] == 6) wKings++
            if (board[sq] == 14) bKings++
            if ((sq < 8 || sq >= 56) && (board[sq] == 1 || board[sq] == 9)) return false
        }
        if (wKings != 1 || bKings != 1) return false
        // الخصم (الطرف الذي ليس عليه الدور) لا يمكن أن يكون ملكه تحت الكش!
        val enemySide = side xor 1
        val enemyKingSq = findKing(enemySide)
        if (enemyKingSq != -1 && isSquareAttacked(enemyKingSq, side)) return false
        return true
    }

    @Synchronized
    fun generateLegalTransitions(fen: String): List<LegalTransition> {
        setFen(fen)
        val moves = IntArray(218)
        val count = generateMoves(moves, false)
        val prevHash = hash
        val list = ArrayList<LegalTransition>(count)
        for (i in 0 until count) {
            val m = moves[i]
            val undo = makeMove(m, prevHash)
            if (undo != -1L) {
                list.add(
                    LegalTransition(
                        uciMove = moveToUci(m),
                        resultingGrid = exportGrid(),
                        resultingFen = exportFen(),
                        nextWhiteToMove = (side == 0)
                    )
                )
                unmakeMove(m, undo, prevHash)
            }
        }
        return list
    }

    private fun computeHash() {
        var h = 0L
        for (s in 0 until 64) {
            val p = board[s]
            if (p != 0) h = h xor zobristPiece[p][s]
        }
        h = h xor zobristCastle[castling]
        h = h xor zobristEp[epSquare + 1]
        if (side == 1) h = h xor zobristSide
        hash = h
    }

    private fun packMove(from: Int, to: Int, promo: Int = 0, flags: Int = 0): Int =
        from or (to shl 6) or (promo shl 12) or (flags shl 15)

    private fun moveFrom(m: Int) = m and 63
    private fun moveTo(m: Int) = (m ushr 6) and 63
    private fun movePromo(m: Int) = (m ushr 12) and 7
    private fun moveFlags(m: Int) = (m ushr 15) and 7

    private fun moveToUci(m: Int): String {
        if (m == 0) return "0000"
        val f = moveFrom(m)
        val t = moveTo(m)
        val p = movePromo(m)
        val sb = StringBuilder(5)
        sb.append(('a'.code + (f and 7)).toChar())
        sb.append(('1'.code + (f ushr 3)).toChar())
        sb.append(('a'.code + (t and 7)).toChar())
        sb.append(('1'.code + (t ushr 3)).toChar())
        if (p != 0) {
            sb.append(when (p) { 2 -> 'n'; 3 -> 'b'; 4 -> 'r'; 5 -> 'q'; else -> 'q' })
        }
        return sb.toString()
    }

    private fun findKing(s: Int): Int {
        val k = if (s == 0) 6 else 14
        for (i in 0 until 64) if (board[i] == k) return i
        return -1
    }

    private fun isSquareAttacked(sq: Int, bySide: Int): Boolean {
        val r = sq ushr 3
        val f = sq and 7
        if (bySide == 0) {
            if (r > 0 && f > 0 && board[(r - 1) * 8 + (f - 1)] == 1) return true
            if (r > 0 && f < 7 && board[(r - 1) * 8 + (f + 1)] == 1) return true
        } else {
            if (r < 7 && f > 0 && board[(r + 1) * 8 + (f - 1)] == 9) return true
            if (r < 7 && f < 7 && board[(r + 1) * 8 + (f + 1)] == 9) return true
        }
        val enemyN = if (bySide == 0) 2 else 10
        for (i in 0 until 8) {
            val nr = r + KN_DR[i]
            val nf = f + KN_DF[i]
            if (nr in 0..7 && nf in 0..7 && board[nr * 8 + nf] == enemyN) return true
        }
        val enemyK = if (bySide == 0) 6 else 14
        for (dr in -1..1) {
            for (df in -1..1) {
                if (dr == 0 && df == 0) continue
                val nr = r + dr
                val nf = f + df
                if (nr in 0..7 && nf in 0..7 && board[nr * 8 + nf] == enemyK) return true
            }
        }
        val enemyR = if (bySide == 0) 4 else 12
        val enemyQ = if (bySide == 0) 5 else 13
        for (d in 0 until 4) {
            var nr = r + ORTHO_DR[d]
            var nf = f + ORTHO_DF[d]
            while (nr in 0..7 && nf in 0..7) {
                val p = board[nr * 8 + nf]
                if (p != 0) {
                    if (p == enemyR || p == enemyQ) return true
                    break
                }
                nr += ORTHO_DR[d]
                nf += ORTHO_DF[d]
            }
        }
        val enemyB = if (bySide == 0) 3 else 11
        for (d in 0 until 4) {
            var nr = r + DIAG_DR[d]
            var nf = f + DIAG_DF[d]
            while (nr in 0..7 && nf in 0..7) {
                val p = board[nr * 8 + nf]
                if (p != 0) {
                    if (p == enemyB || p == enemyQ) return true
                    break
                }
                nr += DIAG_DR[d]
                nf += DIAG_DF[d]
            }
        }
        return false
    }

    private fun generateMoves(out: IntArray, capturesOnly: Boolean): Int {
        var count = 0
        for (sq in 0 until 64) {
            val p = board[sq]
            if (p == 0) continue
            val pc = (p ushr 3) and 1
            if (pc != side) continue
            val pt = p and 7
            val r = sq ushr 3
            val f = sq and 7

            when (pt) {
                1 -> {
                    val dir = if (side == 0) 1 else -1
                    val startR = if (side == 0) 1 else 6
                    val promoR = if (side == 0) 7 else 0
                    val nr = r + dir
                    if (!capturesOnly && nr in 0..7 && board[nr * 8 + f] == 0) {
                        val to = nr * 8 + f
                        if (nr == promoR) {
                            out[count++] = packMove(sq, to, 5)
                            out[count++] = packMove(sq, to, 4)
                            out[count++] = packMove(sq, to, 3)
                            out[count++] = packMove(sq, to, 2)
                        } else {
                            out[count++] = packMove(sq, to)
                            if (r == startR) {
                                val to2 = (r + 2 * dir) * 8 + f
                                if (board[to2] == 0) out[count++] = packMove(sq, to2, 0, 4)
                            }
                        }
                    }
                    for (df in intArrayOf(-1, 1)) {
                        val nf = f + df
                        if (nr in 0..7 && nf in 0..7) {
                            val to = nr * 8 + nf
                            val target = board[to]
                            if (target != 0 && ((target ushr 3) and 1) != side) {
                                if (nr == promoR) {
                                    out[count++] = packMove(sq, to, 5)
                                    out[count++] = packMove(sq, to, 4)
                                    out[count++] = packMove(sq, to, 3)
                                    out[count++] = packMove(sq, to, 2)
                                } else {
                                    out[count++] = packMove(sq, to)
                                }
                            } else if (to == epSquare) {
                                out[count++] = packMove(sq, to, 0, 1)
                            }
                        }
                    }
                }
                2 -> {
                    for (i in 0 until 8) {
                        val nr = r + KN_DR[i]
                        val nf = f + KN_DF[i]
                        if (nr in 0..7 && nf in 0..7) {
                            val to = nr * 8 + nf
                            val target = board[to]
                            if (target == 0) {
                                if (!capturesOnly) out[count++] = packMove(sq, to)
                            } else if (((target ushr 3) and 1) != side) {
                                out[count++] = packMove(sq, to)
                            }
                        }
                    }
                }
                6 -> {
                    for (dr in -1..1) {
                        for (df in -1..1) {
                            if (dr == 0 && df == 0) continue
                            val nr = r + dr
                            val nf = f + df
                            if (nr in 0..7 && nf in 0..7) {
                                val to = nr * 8 + nf
                                val target = board[to]
                                if (target == 0) {
                                    if (!capturesOnly) out[count++] = packMove(sq, to)
                                } else if (((target ushr 3) and 1) != side) {
                                    out[count++] = packMove(sq, to)
                                }
                            }
                        }
                    }
                    if (!capturesOnly) {
                        if (side == 0 && sq == 4) {
                            if ((castling and 1) != 0 && board[5] == 0 && board[6] == 0 && board[7] == 4 &&
                                !isSquareAttacked(4, 1) && !isSquareAttacked(5, 1) && !isSquareAttacked(6, 1)
                            ) out[count++] = packMove(4, 6, 0, 2)
                            if ((castling and 2) != 0 && board[3] == 0 && board[2] == 0 && board[1] == 0 && board[0] == 4 &&
                                !isSquareAttacked(4, 1) && !isSquareAttacked(3, 1) && !isSquareAttacked(2, 1)
                            ) out[count++] = packMove(4, 2, 0, 2)
                        } else if (side == 1 && sq == 60) {
                            if ((castling and 4) != 0 && board[61] == 0 && board[62] == 0 && board[63] == 12 &&
                                !isSquareAttacked(60, 0) && !isSquareAttacked(61, 0) && !isSquareAttacked(62, 0)
                            ) out[count++] = packMove(60, 62, 0, 2)
                            if ((castling and 8) != 0 && board[59] == 0 && board[58] == 0 && board[57] == 0 && board[56] == 12 &&
                                !isSquareAttacked(60, 0) && !isSquareAttacked(59, 0) && !isSquareAttacked(58, 0)
                            ) out[count++] = packMove(60, 58, 0, 2)
                        }
                    }
                }
                else -> {
                    val startD = if (pt == 3) 4 else 0
                    val endD = if (pt == 4) 4 else 8
                    for (d in startD until endD) {
                        var nr = r + ALL_DR[d]
                        var nf = f + ALL_DF[d]
                        while (nr in 0..7 && nf in 0..7) {
                            val to = nr * 8 + nf
                            val target = board[to]
                            if (target == 0) {
                                if (!capturesOnly) out[count++] = packMove(sq, to)
                            } else {
                                if (((target ushr 3) and 1) != side) out[count++] = packMove(sq, to)
                                break
                            }
                            nr += ALL_DR[d]
                            nf += ALL_DF[d]
                        }
                    }
                }
            }
        }
        return count
    }

    private fun makeMove(m: Int, prevHash: Long): Long {
        val from = moveFrom(m)
        val to = moveTo(m)
        val promo = movePromo(m)
        val flags = moveFlags(m)

        var captured = board[to]
        val oldCastling = castling
        val oldEp = epSquare

        var h = prevHash xor zobristCastle[castling] xor zobristEp[epSquare + 1]
        val moving = board[from]
        board[from] = 0
        h = h xor zobristPiece[moving][from]

        if (flags == 1) {
            val capSq = if (side == 0) to - 8 else to + 8
            captured = board[capSq]
            h = h xor zobristPiece[captured][capSq]
            board[capSq] = 0
        } else if (captured != 0) {
            h = h xor zobristPiece[captured][to]
        }

        val placed = if (promo != 0) (if (side == 0) promo else (promo or 8)) else moving
        board[to] = placed
        h = h xor zobristPiece[placed][to]

        if (flags == 2) {
            when (to) {
                6 -> { board[7] = 0; board[5] = 4; h = h xor zobristPiece[4][7] xor zobristPiece[4][5] }
                2 -> { board[0] = 0; board[3] = 4; h = h xor zobristPiece[4][0] xor zobristPiece[4][3] }
                62 -> { board[63] = 0; board[61] = 12; h = h xor zobristPiece[12][63] xor zobristPiece[12][61] }
                58 -> { board[56] = 0; board[59] = 12; h = h xor zobristPiece[12][56] xor zobristPiece[12][59] }
            }
        }

        if (from == 4 || to == 4) castling = castling and 3.inv()
        if (from == 60 || to == 60) castling = castling and 12.inv()
        if (from == 0 || to == 0) castling = castling and 2.inv()
        if (from == 7 || to == 7) castling = castling and 1.inv()
        if (from == 56 || to == 56) castling = castling and 8.inv()
        if (from == 63 || to == 63) castling = castling and 4.inv()

        epSquare = if (flags == 4) ((from + to) ushr 1) else -1
        h = h xor zobristCastle[castling] xor zobristEp[epSquare + 1] xor zobristSide
        hash = h

        val us = side
        side = side xor 1
        val undoPacked = captured.toLong() or (oldCastling.toLong() shl 4) or ((oldEp + 1).toLong() shl 8)

        val kingSq = findKing(us)
        if (kingSq != -1 && isSquareAttacked(kingSq, side)) {
            unmakeMove(m, undoPacked, prevHash)
            return -1L
        }
        return undoPacked
    }

    private fun unmakeMove(m: Int, undoPacked: Long, prevHash: Long) {
        side = side xor 1
        val captured = (undoPacked and 15L).toInt()
        castling = ((undoPacked ushr 4) and 15L).toInt()
        epSquare = ((undoPacked ushr 8) and 127L).toInt() - 1
        hash = prevHash

        val from = moveFrom(m)
        val to = moveTo(m)
        val promo = movePromo(m)
        val flags = moveFlags(m)

        val placed = board[to]
        board[from] = if (promo != 0) (if (side == 0) 1 else 9) else placed

        if (flags == 1) {
            board[to] = 0
            val capSq = if (side == 0) to - 8 else to + 8
            board[capSq] = captured
        } else {
            board[to] = captured
        }

        if (flags == 2) {
            when (to) {
                6 -> { board[5] = 0; board[7] = 4 }
                2 -> { board[3] = 0; board[0] = 4 }
                62 -> { board[61] = 0; board[63] = 12 }
                58 -> { board[59] = 0; board[56] = 12 }
            }
        }
    }

    private fun evaluate(): Int {
        var mgW = 0; var mgB = 0
        var egW = 0; var egB = 0
        var phase = 0

        for (sq in 0 until 64) {
            val p = board[sq]
            if (p == 0) continue
            val isBlack = (p and 8) != 0
            val pt = p and 7
            val tSq = if (!isBlack) (sq xor 56) else sq

            var mg = MG_VAL[pt]
            var eg = EG_VAL[pt]
            phase += PHASE_INC[pt]

            when (pt) {
                1 -> { mg += MG_PAWN[tSq]; eg += EG_PAWN[tSq] }
                2 -> { mg += MG_KNIGHT[tSq]; eg += EG_KNIGHT[tSq] }
                3 -> { mg += MG_BISHOP[tSq]; eg += EG_BISHOP[tSq] }
                4 -> { mg += MG_ROOK[tSq]; eg += EG_ROOK[tSq] }
                5 -> { mg += MG_QUEEN[tSq]; eg += EG_QUEEN[tSq] }
                6 -> { mg += MG_KING[tSq]; eg += EG_KING[tSq] }
            }
            if (!isBlack) { mgW += mg; egW += eg } else { mgB += mg; egB += eg }
        }

        val mgScore = if (side == 0) mgW - mgB else mgB - mgW
        val egScore = if (side == 0) egW - egB else egB - egW
        val pClamped = min(phase, 24)
        return (mgScore * pClamped + egScore * (24 - pClamped)) / 24
    }

    private fun checkTime(): Boolean {
        if ((nodes and 2047L) == 0L && timeLimitMs > 0L) {
            if (System.currentTimeMillis() - startTimeMs >= timeLimitMs) {
                stopRequested = true
            }
        }
        return stopRequested
    }

    private fun sortMoves(moves: IntArray, scores: IntArray, count: Int, ttMove: Int, ply: Int) {
        for (i in 0 until count) {
            val m = moves[i]
            val to = moveTo(m)
            val from = moveFrom(m)
            val flags = moveFlags(m)
            val promo = movePromo(m)
            scores[i] = when {
                m == ttMove -> 1_000_000
                board[to] != 0 || flags == 1 -> {
                    val vic = if (flags == 1) 1 else (board[to] and 7)
                    val att = board[from] and 7
                    100_000 + vic * 100 - att
                }
                promo != 0 -> 90_000 + promo * 100
                ply < 64 && m == killer0[ply] -> 80_000
                ply < 64 && m == killer1[ply] -> 70_000
                else -> history[board[from]][to]
            }
        }
        for (i in 1 until count) {
            val km = moves[i]
            val ks = scores[i]
            var j = i - 1
            while (j >= 0 && scores[j] < ks) {
                moves[j + 1] = moves[j]
                scores[j + 1] = scores[j]
                j--
            }
            moves[j + 1] = km
            scores[j + 1] = ks
        }
    }

    private fun quiescence(alphaIn: Int, beta: Int): Int {
        if (checkTime()) return 0
        nodes++
        var alpha = alphaIn
        val standPat = evaluate()
        if (standPat >= beta) return beta
        if (standPat > alpha) alpha = standPat

        val moves = IntArray(128)
        val scores = IntArray(128)
        val count = generateMoves(moves, true)
        sortMoves(moves, scores, count, 0, 63)

        val prevHash = hash
        for (i in 0 until count) {
            val m = moves[i]
            val undo = makeMove(m, prevHash)
            if (undo == -1L) continue
            val score = -quiescence(-beta, -alpha)
            unmakeMove(m, undo, prevHash)
            if (stopRequested) return 0
            if (score >= beta) return beta
            if (score > alpha) alpha = score
        }
        return alpha
    }

    private fun alphaBeta(depth: Int, alphaIn: Int, beta: Int, ply: Int): Int {
        if (checkTime()) return 0
        if (depth <= 0) return quiescence(alphaIn, beta)

        nodes++
        var alpha = alphaIn
        val origAlpha = alpha
        val idx = (hash and ttMask.toLong()).toInt()
        var ttBest = 0
        if (ttKey[idx] == hash) {
            ttBest = ttMove[idx]
            if (ttDepth[idx] >= depth && ply > 0) {
                val sc = ttScore[idx]
                when (ttFlag[idx]) {
                    0 -> return sc
                    1 -> if (sc <= alpha) return alpha
                    2 -> if (sc >= beta) return beta
                }
            }
        }

        val moves = IntArray(218)
        val scores = IntArray(218)
        val count = generateMoves(moves, false)
        sortMoves(moves, scores, count, ttBest, ply)

        var legalCount = 0
        var bestScore = -1_000_000
        var bestMove = 0
        val prevHash = hash

        for (i in 0 until count) {
            val m = moves[i]
            val undo = makeMove(m, prevHash)
            if (undo == -1L) continue
            legalCount++

            val score = if (legalCount == 1) {
                -alphaBeta(depth - 1, -beta, -alpha, ply + 1)
            } else {
                var s = -alphaBeta(depth - 1, -alpha - 1, -alpha, ply + 1)
                if (s > alpha && s < beta) {
                    s = -alphaBeta(depth - 1, -beta, -alpha, ply + 1)
                }
                s
            }
            unmakeMove(m, undo, prevHash)
            if (stopRequested) return 0

            if (score > bestScore) {
                bestScore = score
                bestMove = m
                if (ply == 0) rootBestMove = m
            }
            if (score > alpha) {
                alpha = score
                if (alpha >= beta) {
                    val to = moveTo(m)
                    if (board[to] == 0 && moveFlags(m) != 1 && ply < 64) {
                        killer1[ply] = killer0[ply]
                        killer0[ply] = m
                        history[board[moveFrom(m)]][to] += depth * depth
                    }
                    break
                }
            }
        }

        if (legalCount == 0) {
            val kSq = findKing(side)
            return if (kSq != -1 && isSquareAttacked(kSq, side xor 1)) -29000 + ply else 0
        }

        ttKey[idx] = hash
        ttDepth[idx] = depth
        ttScore[idx] = bestScore
        ttMove[idx] = bestMove
        ttFlag[idx] = when {
            bestScore <= origAlpha -> 1
            bestScore >= beta -> 2
            else -> 0
        }
        return bestScore
    }

    @Synchronized
    fun search(fen: String, maxDepth: Int, moveTimeMs: Int): Result {
        // فحص كتاب الافتتاحيات الفوري أولاً
        val placementAndTurn = fen.trim().split(" ").take(2).joinToString(" ")
        OPENING_BOOK[placementAndTurn]?.let { bookEntry ->
            return Result(
                bestMoveUci = bookEntry.first,
                ponderMoveUci = bookEntry.second,
                scoreCp = bookEntry.third,
                mateIn = 0,
                depth = 16,
                nodes = 1L,
                elapsedMs = 1L
            )
        }

        setFen(fen)
        stopRequested = false
        nodes = 0L
        startTimeMs = System.currentTimeMillis()
        timeLimitMs = moveTimeMs.toLong()
        killer0.fill(0)
        killer1.fill(0)
        for (r in history) r.fill(0)

        var finalBest = 0
        var finalScore = 0
        var completedDepth = 0

        val rootMoves = IntArray(218)
        val rootCount = generateMoves(rootMoves, false)
        val prevHash = hash
        for (i in 0 until rootCount) {
            val undo = makeMove(rootMoves[i], prevHash)
            if (undo != -1L) {
                finalBest = rootMoves[i]
                unmakeMove(rootMoves[i], undo, prevHash)
                break
            }
        }

        for (d in 1..maxDepth) {
            rootBestMove = 0
            val score = alphaBeta(d, -100_000, 100_000, 0)
            if (stopRequested && d > 1) break
            completedDepth = d
            if (rootBestMove != 0) {
                finalBest = rootBestMove
                finalScore = score
            }
            if (abs(finalScore) > 28000) break
        }

        var ponderUci = ""
        if (finalBest != 0) {
            val undo = makeMove(finalBest, prevHash)
            if (undo != -1L) {
                val rIdx = (hash and ttMask.toLong()).toInt()
                if (ttKey[rIdx] == hash && ttMove[rIdx] != 0) {
                    ponderUci = moveToUci(ttMove[rIdx])
                }
                unmakeMove(finalBest, undo, prevHash)
            }
        }

        val mateIn = if (abs(finalScore) > 28000) {
            val plies = 29000 - abs(finalScore)
            val movesToMate = (plies + 1) / 2
            if (finalScore > 0) movesToMate else -movesToMate
        } else 0

        val elapsed = (System.currentTimeMillis() - startTimeMs).coerceAtLeast(1L)
        return Result(
            bestMoveUci = moveToUci(finalBest),
            ponderMoveUci = ponderUci,
            scoreCp = if (mateIn != 0) (if (mateIn > 0) 29000 else -29000) else finalScore,
            mateIn = mateIn,
            depth = completedDepth,
            nodes = nodes,
            elapsedMs = elapsed
        )
    }

    companion object {
        // كتاب افتتاحيات الأساتذة للرد الفوري الدقيق
        private val OPENING_BOOK: Map<String, Triple<String, String, Int>> = mapOf(
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w" to Triple("e2e4", "e7e5", 35),
            "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b" to Triple("c7c5", "g1f3", 25),
            "rnbqkbnr/pppppppp/8/8/3P4/8/PPP1PPPP/RNBQKBNR b" to Triple("g8f6", "c2c4", 25),
            "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w" to Triple("g1f3", "b8c6", 35),
            "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w" to Triple("f1b5", "a7a6", 40),
            "r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R b" to Triple("g8f6", "d2d3", 20),
            "rnbqkbnr/pp1ppppp/8/2p5/4P3/8/PPPP1PPP/RNBQKBNR w" to Triple("g1f3", "d7d6", 35),
            "rnbqkb1r/pppppppp/5n2/8/3P4/8/PPP1PPPP/RNBQKBNR w" to Triple("c2c4", "e7e6", 35),
            "rnbqkbnr/ppp1pppp/8/3p4/3P4/8/PPP1PPPP/RNBQKBNR w" to Triple("c2c4", "e7e6", 35),
            "r1bqkbnr/pppp1ppp/2n5/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR b" to Triple("g7g6", "h5f3", 85)
        )

        private val KN_DR = intArrayOf(-2, -2, -1, -1, 1, 1, 2, 2)
        private val KN_DF = intArrayOf(-1, 1, -2, 2, -2, 2, -1, 1)
        private val ORTHO_DR = intArrayOf(-1, 1, 0, 0)
        private val ORTHO_DF = intArrayOf(0, 0, -1, 1)
        private val DIAG_DR = intArrayOf(-1, -1, 1, 1)
        private val DIAG_DF = intArrayOf(-1, 1, -1, 1)
        private val ALL_DR = intArrayOf(-1, 1, 0, 0, -1, -1, 1, 1)
        private val ALL_DF = intArrayOf(0, 0, -1, 1, -1, 1, -1, 1)

        private val MG_VAL = intArrayOf(0, 82, 337, 365, 477, 1025, 0)
        private val EG_VAL = intArrayOf(0, 94, 281, 297, 512, 936, 0)
        private val PHASE_INC = intArrayOf(0, 0, 1, 1, 2, 4, 0)

        private val MG_PAWN = intArrayOf(
              0,   0,   0,   0,   0,   0,   0,   0,
             98, 134,  61,  95,  68, 126,  34, -11,
             -6,   7,  26,  31,  65,  56,  25, -20,
            -14,  13,   6,  21,  23,  12,  17, -23,
            -27,  -2,  -5,  12,  17,   6,  10, -25,
            -26,  -4,  -4, -10,   3,   3,  33, -12,
            -35,  -1, -20, -23, -15,  24,  38, -22,
              0,   0,   0,   0,   0,   0,   0,   0
        )
        private val EG_PAWN = intArrayOf(
              0,   0,   0,   0,   0,   0,   0,   0,
            178, 173, 158, 134, 147, 132, 165, 187,
             94, 100,  85,  67,  56,  53,  82,  84,
             32,  24,  13,   5,  -2,   4,  17,  17,
             13,   9,  -3,  -7,  -7,  -8,   3,  -1,
              4,   7,  -6,   1,   0,  -5,  -1,  -8,
             13,   8,   8,  10,  13,   0,   2,  -7,
              0,   0,   0,   0,   0,   0,   0,   0
        )
        private val MG_KNIGHT = intArrayOf(
           -167, -89, -34, -49,  61, -97, -15, -107,
            -73, -41,  72,  36,  23,  62,   7,  -17,
            -47,  60,  37,  65,  84, 129,  73,   44,
             -9,  17,  19,  53,  37,  69,  18,   22,
            -13,   4,  16,  13,  28,  19,  21,   -8,
            -23,  -9,  12,  10,  19,  17,  25,  -16,
            -29, -53, -12,  -3,  -1,  18, -14,  -19,
           -105, -21, -58, -33, -17, -28, -19,  -23
        )
        private val EG_KNIGHT = intArrayOf(
            -58, -38, -13, -28, -31, -27, -63, -99,
            -25,  -8, -25,  -2,  -9, -25, -24, -52,
            -24, -20,  10,   9,  -1,  -9, -19, -41,
            -17,   3,  22,  22,  22,  11,   8, -18,
            -18,  -6,  16,  25,  16,  17,   4, -18,
            -23,  -3,  -1,  15,  10,  -3, -20, -22,
            -42, -20, -10,  -5,  -2, -20, -23, -44,
            -29, -51, -23, -15, -22, -18, -50, -64
        )
        private val MG_BISHOP = intArrayOf(
            -29,   4, -82, -37, -25, -42,   7,  -8,
            -26,  16, -18, -13,  30,  59,  18, -47,
            -16,  37,  43,  40,  35,  50,  37,  -2,
             -4,   5,  19,  50,  37,  37,   7,  -2,
             -6,  13,  13,  26,  34,  12,  10,   4,
              0,  15,  15,  15,  14,  27,  18,  10,
              4,  15,  16,   0,   7,  21,  33,   1,
            -33,  -3, -14, -21, -13, -12, -39, -21
        )
        private val EG_BISHOP = intArrayOf(
            -14, -21, -11,  -8,  -7,  -9, -17, -24,
             -8,  -4,   7, -12,  -3, -13,  -4, -14,
              2,  -8,   0,  -1,  -2,   6,   0,   4,
             -3,   9,  12,   9,  14,  10,   3,   2,
             -6,   3,  13,  19,   7,  10,  -3,  -9,
            -12,  -3,   8,  10,  13,   3,  -7, -15,
            -14, -18,  -7,  -1,   4,  -9, -15, -27,
            -23,  -9, -23,  -5,  -9, -16,  -5, -17
        )
        private val MG_ROOK = intArrayOf(
             32,  42,  32,  51,  63,   9,  31,  43,
             27,  32,  58,  62,  80,  67,  26,  44,
             -5,  19,  26,  36,  17,  45,  61,  16,
            -24, -11,   7,  26,  24,  35,  -8, -20,
            -36, -26, -12,  -1,   9,  -7,   6, -23,
            -45, -25, -16, -17,   3,   0,  -5, -33,
            -44, -16, -20,  -9,  -1,  11,  -6, -71,
            -19, -13,   1,  17,  16,   7, -37, -26
        )
        private val EG_ROOK = intArrayOf(
             13,  10,  18,  15,  12,  12,   8,   5,
             11,  13,  13,  11,  -3,   3,   8,   3,
              7,   7,   7,   5,   4,  -3,  -5,  -3,
              4,   3,  13,   1,   2,   1,  -1,   2,
              3,   5,   8,   4,  -5,  -6,  -8, -11,
             -4,   0,  -5,  -1,  -7, -12,  -8, -16,
             -6,  -6,   0,   2,  -9,  -9, -11,  -3,
             -9,   2,   3,  -1,  -5, -13,   4, -20
        )
        private val MG_QUEEN = intArrayOf(
            -28,   0,  29,  12,  59,  44,  43,  45,
            -24, -39,  -5,   1, -16,  57,  28,  54,
            -13, -17,   7,   8,  29,  56,  47,  57,
            -27, -27, -16, -16,  -1,  17,  -2,   1,
             -9, -26,  -9, -10,  -2,  -4,   3,  -3,
            -14,   2, -11,  -2,  -5,   2,  14,   5,
            -35,  -8,  11,   2,   8,  15,  -3,   1,
             -1, -18,  -9,  10, -15, -25, -31, -50
        )
        private val EG_QUEEN = intArrayOf(
             -9,  22,  22,  27,  27,  19,  10,  20,
            -17,  20,  32,  41,  58,  25,  30,   0,
            -20,   6,   9,  49,  47,  35,  19,   9,
              3,  22,  24,  45,  57,  40,  57,  36,
            -18,  28,  19,  47,  31,  34,  39,  23,
            -16, -27,  15,   6,   9,  17,  10,   5,
            -22, -23, -30, -16, -16, -23, -36, -32,
            -33, -28, -22, -43,  -5, -32, -20, -41
        )
        private val MG_KING = intArrayOf(
            -65,  23,  16, -15, -56, -34,   2,  13,
             29,  -1, -20,  -7,  -8,  -4, -38, -29,
             -9,  24,   2, -16, -20,   6,  22, -22,
            -17, -20, -12, -27, -30, -25, -14, -36,
            -49,  -1, -27, -39, -46, -44, -33, -51,
            -14, -14, -22, -46, -44, -30, -15, -27,
              1,   7,  -8, -64, -43, -16,   9,   8,
            -15,  36,  12, -54,   8, -28,  24,  14
        )
        private val EG_KING = intArrayOf(
            -74, -35, -18, -18, -11,  15,   4, -17,
            -12,  17,  14,  17,  17,  38,  23,  11,
             10,  17,  23,  15,  20,  45,  44,  13,
             -8,  22,  24,  27,  26,  33,  26,   3,
            -18,  -4,  21,  24,  27,  23,   9, -11,
            -19,  -3,  11,  21,  23,  16,   7,  -9,
            -27, -11,   4,  13,  14,   4,  -5, -17,
            -53, -34, -21, -11, -28, -14, -24, -43
        )
    }
}
