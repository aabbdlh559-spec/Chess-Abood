#include <iostream>
#include <vector>
#include <string>
#include <sstream>
#include <algorithm>
#include <chrono>
#include <cstring>
#include <cmath>
#include <atomic>
#include <mutex>

#ifdef BUILD_JNI_LIB
#include <jni.h>
#endif

namespace AboodEngine {

enum Piece {
    EMPTY = 0,
    wP = 1, wN = 2, wB = 3, wR = 4, wQ = 5, wK = 6,
    bP = 9, bN = 10, bB = 11, bR = 12, bQ = 13, bK = 14
};

inline int pieceType(int p) { return p & 7; }
inline int pieceColor(int p) { return p == EMPTY ? -1 : ((p & 8) ? 1 : 0); }

struct Move {
    int from = 0;
    int to = 0;
    int promo = 0;
    int flags = 0;
    int score = 0;

    bool operator==(const Move& o) const {
        return from == o.from && to == o.to && promo == o.promo;
    }

    std::string toUci() const {
        if (from == to) return "0000";
        std::string s;
        s += char('a' + (from % 8));
        s += char('1' + (from / 8));
        s += char('a' + (to % 8));
        s += char('1' + (to / 8));
        if (promo) {
            const char pc[] = {' ', ' ', 'n', 'b', 'r', 'q', ' '};
            s += pc[promo];
        }
        return s;
    }
};

struct Undo {
    int captured = EMPTY;
    int castling = 0;
    int epSquare = -1;
    int halfmove = 0;
    uint64_t hash = 0;
};

const int MG_VAL[7] = {0, 82, 337, 365, 477, 1025, 0};
const int EG_VAL[7] = {0, 94, 281, 297, 512,  936, 0};
const int PHASE_INC[7] = {0, 0, 1, 1, 2, 4, 0};

const int MG_PAWN[64] = {
      0,   0,   0,   0,   0,   0,   0,   0,
     98, 134,  61,  95,  68, 126,  34, -11,
     -6,   7,  26,  31,  65,  56,  25, -20,
    -14,  13,   6,  21,  23,  12,  17, -23,
    -27,  -2,  -5,  12,  17,   6,  10, -25,
    -26,  -4,  -4, -10,   3,   3,  33, -12,
    -35,  -1, -20, -23, -15,  24,  38, -22,
      0,   0,   0,   0,   0,   0,   0,   0
};

const int EG_PAWN[64] = {
      0,   0,   0,   0,   0,   0,   0,   0,
    178, 173, 158, 134, 147, 132, 165, 187,
     94, 100,  85,  67,  56,  53,  82,  84,
     32,  24,  13,   5,  -2,   4,  17,  17,
     13,   9,  -3,  -7,  -7,  -8,   3,  -1,
      4,   7,  -6,   1,   0,  -5,  -1,  -8,
     13,   8,   8,  10,  13,   0,   2,  -7,
      0,   0,   0,   0,   0,   0,   0,   0
};

const int MG_KNIGHT[64] = {
   -167, -89, -34, -49,  61, -97, -15, -107,
    -73, -41,  72,  36,  23,  62,   7,  -17,
    -47,  60,  37,  65,  84, 129,  73,   44,
     -9,  17,  19,  53,  37,  69,  18,   22,
    -13,   4,  16,  13,  28,  19,  21,   -8,
    -23,  -9,  12,  10,  19,  17,  25,  -16,
    -29, -53, -12,  -3,  -1,  18, -14,  -19,
   -105, -21, -58, -33, -17, -28, -19,  -23
};

const int EG_KNIGHT[64] = {
    -58, -38, -13, -28, -31, -27, -63, -99,
    -25,  -8, -25,  -2,  -9, -25, -24, -52,
    -24, -20,  10,   9,  -1,  -9, -19, -41,
    -17,   3,  22,  22,  22,  11,   8, -18,
    -18,  -6,  16,  25,  16,  17,   4, -18,
    -23,  -3,  -1,  15,  10,  -3, -20, -22,
    -42, -20, -10,  -5,  -2, -20, -23, -44,
    -29, -51, -23, -15, -22, -18, -50, -64
};

const int MG_BISHOP[64] = {
    -29,   4, -82, -37, -25, -42,   7,  -8,
    -26,  16, -18, -13,  30,  59,  18, -47,
    -16,  37,  43,  40,  35,  50,  37,  -2,
     -4,   5,  19,  50,  37,  37,   7,  -2,
     -6,  13,  13,  26,  34,  12,  10,   4,
      0,  15,  15,  15,  14,  27,  18,  10,
      4,  15,  16,   0,   7,  21,  33,   1,
    -33,  -3, -14, -21, -13, -12, -39, -21
};

const int EG_BISHOP[64] = {
    -14, -21, -11,  -8,  -7,  -9, -17, -24,
     -8,  -4,   7, -12,  -3, -13,  -4, -14,
      2,  -8,   0,  -1,  -2,   6,   0,   4,
     -3,   9,  12,   9,  14,  10,   3,   2,
     -6,   3,  13,  19,   7,  10,  -3,  -9,
    -12,  -3,   8,  10,  13,   3,  -7, -15,
    -14, -18,  -7,  -1,   4,  -9, -15, -27,
    -23,  -9, -23,  -5,  -9, -16,  -5, -17
};

const int MG_ROOK[64] = {
     32,  42,  32,  51,  63,   9,  31,  43,
     27,  32,  58,  62,  80,  67,  26,  44,
     -5,  19,  26,  36,  17,  45,  61,  16,
    -24, -11,   7,  26,  24,  35,  -8, -20,
    -36, -26, -12,  -1,   9,  -7,   6, -23,
    -45, -25, -16, -17,   3,   0,  -5, -33,
    -44, -16, -20,  -9,  -1,  11,  -6, -71,
    -19, -13,   1,  17,  16,   7, -37, -26
};

const int EG_ROOK[64] = {
     13,  10,  18,  15,  12,  12,   8,   5,
     11,  13,  13,  11,  -3,   3,   8,   3,
      7,   7,   7,   5,   4,  -3,  -5,  -3,
      4,   3,  13,   1,   2,   1,  -1,   2,
      3,   5,   8,   4,  -5,  -6,  -8, -11,
     -4,   0,  -5,  -1,  -7, -12,  -8, -16,
     -6,  -6,   0,   2,  -9,  -9, -11,  -3,
     -9,   2,   3,  -1,  -5, -13,   4, -20
};

const int MG_QUEEN[64] = {
    -28,   0,  29,  12,  59,  44,  43,  45,
    -24, -39,  -5,   1, -16,  57,  28,  54,
    -13, -17,   7,   8,  29,  56,  47,  57,
    -27, -27, -16, -16,  -1,  17,  -2,   1,
     -9, -26,  -9, -10,  -2,  -4,   3,  -3,
    -14,   2, -11,  -2,  -5,   2,  14,   5,
    -35,  -8,  11,   2,   8,  15,  -3,   1,
     -1, -18,  -9,  10, -15, -25, -31, -50
};

const int EG_QUEEN[64] = {
     -9,  22,  22,  27,  27,  19,  10,  20,
    -17,  20,  32,  41,  58,  25,  30,   0,
    -20,   6,   9,  49,  47,  35,  19,   9,
      3,  22,  24,  45,  57,  40,  57,  36,
    -18,  28,  19,  47,  31,  34,  39,  23,
    -16, -27,  15,   6,   9,  17,  10,   5,
    -22, -23, -30, -16, -16, -23, -36, -32,
    -33, -28, -22, -43,  -5, -32, -20, -41
};

const int MG_KING[64] = {
    -65,  23,  16, -15, -56, -34,   2,  13,
     29,  -1, -20,  -7,  -8,  -4, -38, -29,
     -9,  24,   2, -16, -20,   6,  22, -22,
    -17, -20, -12, -27, -30, -25, -14, -36,
    -49,  -1, -27, -39, -46, -44, -33, -51,
    -14, -14, -22, -46, -44, -30, -15, -27,
      1,   7,  -8, -64, -43, -16,   9,   8,
    -15,  36,  12, -54,   8, -28,  24,  14
};

const int EG_KING[64] = {
    -74, -35, -18, -18, -11,  15,   4, -17,
    -12,  17,  14,  17,  17,  38,  23,  11,
     10,  17,  23,  15,  20,  45,  44,  13,
     -8,  22,  24,  27,  26,  33,  26,   3,
    -18,  -4,  21,  24,  27,  23,   9, -11,
    -19,  -3,  11,  21,  23,  16,   7,  -9,
    -27, -11,   4,  13,  14,   4,  -5, -17,
    -53, -34, -21, -11, -28, -14, -24, -43
};

struct TTEntry {
    uint64_t key = 0;
    int depth = -1;
    int score = 0;
    int flag = 0;
    Move bestMove;
};

struct SearchSummary {
    std::string bestMoveUci = "0000";
    std::string ponderMoveUci = "";
    int scoreCp = 0;
    int mateIn = 0;
    int depth = 0;
    uint64_t nodes = 0;
    long long elapsedMs = 0;
};

class Engine {
public:
    int board[64];
    int side = 0;
    int castling = 15;
    int epSquare = -1;
    int halfmove = 0;
    int fullmove = 1;
    uint64_t hash = 0;

    uint64_t zobristPiece[16][64];
    uint64_t zobristCastle[16];
    uint64_t zobristEp[65];
    uint64_t zobristSide;

    std::vector<TTEntry> tt;
    Move killers[64][2];
    int history[16][64];

    std::atomic<bool> stopFlag{false};
    uint64_t nodes = 0;
    std::chrono::steady_clock::time_point startTime;
    int timeLimitMs = 0;

    Engine() : tt(1 << 17) {
        uint64_t seed = 0x9E3779B97F4A7C15ULL;
        auto nextRand = [&]() {
            seed ^= seed >> 12;
            seed ^= seed << 25;
            seed ^= seed >> 27;
            return seed * 0x2545F4914F6CDD1DULL;
        };
        for (int p = 0; p < 16; ++p)
            for (int s = 0; s < 64; ++s)
                zobristPiece[p][s] = nextRand();
        for (int c = 0; c < 16; ++c) zobristCastle[c] = nextRand();
        for (int e = 0; e < 65; ++e) zobristEp[e] = nextRand();
        zobristSide = nextRand();
        setFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1");
    }

    void computeHash() {
        hash = 0;
        for (int s = 0; s < 64; ++s) {
            if (board[s] != EMPTY) hash ^= zobristPiece[board[s]][s];
        }
        hash ^= zobristCastle[castling];
        hash ^= zobristEp[epSquare + 1];
        if (side == 1) hash ^= zobristSide;
    }

    void setFen(const std::string& fen) {
        std::memset(board, 0, sizeof(board));
        std::istringstream ss(fen);
        std::string placement, turn, castleStr, epStr;
        ss >> placement >> turn >> castleStr >> epStr >> halfmove >> fullmove;

        int rank = 7, file = 0;
        for (char c : placement) {
            if (c == '/') {
                rank--;
                file = 0;
            } else if (c >= '1' && c <= '8') {
                file += (c - '0');
            } else if (rank >= 0 && rank < 8 && file >= 0 && file < 8) {
                int sq = rank * 8 + file;
                switch (c) {
                    case 'P': board[sq] = wP; break;
                    case 'N': board[sq] = wN; break;
                    case 'B': board[sq] = wB; break;
                    case 'R': board[sq] = wR; break;
                    case 'Q': board[sq] = wQ; break;
                    case 'K': board[sq] = wK; break;
                    case 'p': board[sq] = bP; break;
                    case 'n': board[sq] = bN; break;
                    case 'b': board[sq] = bB; break;
                    case 'r': board[sq] = bR; break;
                    case 'q': board[sq] = bQ; break;
                    case 'k': board[sq] = bK; break;
                }
                file++;
            }
        }
        side = (turn == "b") ? 1 : 0;
        castling = 0;
        if (castleStr.find('K') != std::string::npos) castling |= 1;
        if (castleStr.find('Q') != std::string::npos) castling |= 2;
        if (castleStr.find('k') != std::string::npos) castling |= 4;
        if (castleStr.find('q') != std::string::npos) castling |= 8;

        if (epStr.size() == 2 && epStr[0] >= 'a' && epStr[0] <= 'h' && epStr[1] >= '1' && epStr[1] <= '8') {
            epSquare = (epStr[1] - '1') * 8 + (epStr[0] - 'a');
        } else {
            epSquare = -1;
        }
        computeHash();
    }

    int findKing(int s) const {
        int target = (s == 0) ? wK : bK;
        for (int i = 0; i < 64; ++i) if (board[i] == target) return i;
        return -1;
    }

    bool isSquareAttacked(int sq, int bySide) const {
        int r = sq / 8, f = sq % 8;
        if (bySide == 0) {
            if (r > 0 && f > 0 && board[(r - 1) * 8 + (f - 1)] == wP) return true;
            if (r > 0 && f < 7 && board[(r - 1) * 8 + (f + 1)] == wP) return true;
        } else {
            if (r < 7 && f > 0 && board[(r + 1) * 8 + (f - 1)] == bP) return true;
            if (r < 7 && f < 7 && board[(r + 1) * 8 + (f + 1)] == bP) return true;
        }
        const int knDr[8] = {-2,-2,-1,-1,1,1,2,2};
        const int knDf[8] = {-1,1,-2,2,-2,2,-1,1};
        int enemyN = (bySide == 0) ? wN : bN;
        for (int i = 0; i < 8; ++i) {
            int nr = r + knDr[i], nf = f + knDf[i];
            if (nr >= 0 && nr < 8 && nf >= 0 && nf < 8 && board[nr * 8 + nf] == enemyN) return true;
        }
        int enemyK = (bySide == 0) ? wK : bK;
        for (int dr = -1; dr <= 1; ++dr) {
            for (int df = -1; df <= 1; ++df) {
                if (dr == 0 && df == 0) continue;
                int nr = r + dr, nf = f + df;
                if (nr >= 0 && nr < 8 && nf >= 0 && nf < 8 && board[nr * 8 + nf] == enemyK) return true;
            }
        }
        const int orthoDr[4] = {-1, 1, 0, 0};
        const int orthoDf[4] = {0, 0, -1, 1};
        int enemyR = (bySide == 0) ? wR : bR;
        int enemyQ = (bySide == 0) ? wQ : bQ;
        for (int d = 0; d < 4; ++d) {
            int nr = r + orthoDr[d], nf = f + orthoDf[d];
            while (nr >= 0 && nr < 8 && nf >= 0 && nf < 8) {
                int p = board[nr * 8 + nf];
                if (p != EMPTY) {
                    if (p == enemyR || p == enemyQ) return true;
                    break;
                }
                nr += orthoDr[d]; nf += orthoDf[d];
            }
        }
        const int diagDr[4] = {-1, -1, 1, 1};
        const int diagDf[4] = {-1, 1, -1, 1};
        int enemyB = (bySide == 0) ? wB : bB;
        for (int d = 0; d < 4; ++d) {
            int nr = r + diagDr[d], nf = f + diagDf[d];
            while (nr >= 0 && nr < 8 && nf >= 0 && nf < 8) {
                int p = board[nr * 8 + nf];
                if (p != EMPTY) {
                    if (p == enemyB || p == enemyQ) return true;
                    break;
                }
                nr += diagDr[d]; nf += diagDf[d];
            }
        }
        return false;
    }

    void generatePseudoMoves(std::vector<Move>& moves, bool capturesOnly = false) const {
        moves.clear();
        for (int sq = 0; sq < 64; ++sq) {
            int p = board[sq];
            if (p == EMPTY || pieceColor(p) != side) continue;
            int pt = pieceType(p);
            int r = sq / 8, f = sq % 8;

            if (pt == 1) {
                int dir = (side == 0) ? 1 : -1;
                int startRank = (side == 0) ? 1 : 6;
                int promoRank = (side == 0) ? 7 : 0;
                int nr = r + dir;
                if (!capturesOnly && nr >= 0 && nr < 8 && board[nr * 8 + f] == EMPTY) {
                    int to = nr * 8 + f;
                    if (nr == promoRank) {
                        for (int pr : {5, 4, 3, 2}) moves.push_back({sq, to, pr, 0, 0});
                    } else {
                        moves.push_back({sq, to, 0, 0, 0});
                        if (r == startRank) {
                            int to2 = (r + 2 * dir) * 8 + f;
                            if (board[to2] == EMPTY) moves.push_back({sq, to2, 0, 4, 0});
                        }
                    }
                }
                for (int df : {-1, 1}) {
                    int nf = f + df;
                    if (nr >= 0 && nr < 8 && nf >= 0 && nf < 8) {
                        int to = nr * 8 + nf;
                        int target = board[to];
                        if (target != EMPTY && pieceColor(target) != side) {
                            if (nr == promoRank) {
                                for (int pr : {5, 4, 3, 2}) moves.push_back({sq, to, pr, 0, 0});
                            } else {
                                moves.push_back({sq, to, 0, 0, 0});
                            }
                        } else if (to == epSquare) {
                            moves.push_back({sq, to, 0, 1, 0});
                        }
                    }
                }
            } else if (pt == 2) {
                const int knDr[8] = {-2,-2,-1,-1,1,1,2,2};
                const int knDf[8] = {-1,1,-2,2,-2,2,-1,1};
                for (int i = 0; i < 8; ++i) {
                    int nr = r + knDr[i], nf = f + knDf[i];
                    if (nr >= 0 && nr < 8 && nf >= 0 && nf < 8) {
                        int to = nr * 8 + nf;
                        int target = board[to];
                        if (target == EMPTY) {
                            if (!capturesOnly) moves.push_back({sq, to, 0, 0, 0});
                        } else if (pieceColor(target) != side) {
                            moves.push_back({sq, to, 0, 0, 0});
                        }
                    }
                }
            } else if (pt == 6) {
                for (int dr = -1; dr <= 1; ++dr) {
                    for (int df = -1; df <= 1; ++df) {
                        if (dr == 0 && df == 0) continue;
                        int nr = r + dr, nf = f + df;
                        if (nr >= 0 && nr < 8 && nf >= 0 && nf < 8) {
                            int to = nr * 8 + nf;
                            int target = board[to];
                            if (target == EMPTY) {
                                if (!capturesOnly) moves.push_back({sq, to, 0, 0, 0});
                            } else if (pieceColor(target) != side) {
                                moves.push_back({sq, to, 0, 0, 0});
                            }
                        }
                    }
                }
                if (!capturesOnly) {
                    if (side == 0 && sq == 4) {
                        if ((castling & 1) && board[5] == EMPTY && board[6] == EMPTY && board[7] == wR &&
                            !isSquareAttacked(4, 1) && !isSquareAttacked(5, 1) && !isSquareAttacked(6, 1)) {
                            moves.push_back({4, 6, 0, 2, 0});
                        }
                        if ((castling & 2) && board[3] == EMPTY && board[2] == EMPTY && board[1] == EMPTY && board[0] == wR &&
                            !isSquareAttacked(4, 1) && !isSquareAttacked(3, 1) && !isSquareAttacked(2, 1)) {
                            moves.push_back({4, 2, 0, 2, 0});
                        }
                    } else if (side == 1 && sq == 60) {
                        if ((castling & 4) && board[61] == EMPTY && board[62] == EMPTY && board[63] == bR &&
                            !isSquareAttacked(60, 0) && !isSquareAttacked(61, 0) && !isSquareAttacked(62, 0)) {
                            moves.push_back({60, 62, 0, 2, 0});
                        }
                        if ((castling & 8) && board[59] == EMPTY && board[58] == EMPTY && board[57] == EMPTY && board[56] == bR &&
                            !isSquareAttacked(60, 0) && !isSquareAttacked(59, 0) && !isSquareAttacked(58, 0)) {
                            moves.push_back({60, 58, 0, 2, 0});
                        }
                    }
                }
            } else {
                const int dr[8] = {-1, 1, 0, 0, -1, -1, 1, 1};
                const int df[8] = {0, 0, -1, 1, -1, 1, -1, 1};
                int startD = (pt == 3) ? 4 : 0;
                int endD = (pt == 4) ? 4 : 8;
                for (int d = startD; d < endD; ++d) {
                    int nr = r + dr[d], nf = f + df[d];
                    while (nr >= 0 && nr < 8 && nf >= 0 && nf < 8) {
                        int to = nr * 8 + nf;
                        int target = board[to];
                        if (target == EMPTY) {
                            if (!capturesOnly) moves.push_back({sq, to, 0, 0, 0});
                        } else {
                            if (pieceColor(target) != side) moves.push_back({sq, to, 0, 0, 0});
                            break;
                        }
                        nr += dr[d]; nf += df[d];
                    }
                }
            }
        }
    }

    bool makeMove(const Move& m, Undo& u) {
        u.captured = board[m.to];
        u.castling = castling;
        u.epSquare = epSquare;
        u.halfmove = halfmove;
        u.hash = hash;

        int moving = board[m.from];
        int pt = pieceType(moving);

        hash ^= zobristCastle[castling];
        hash ^= zobristEp[epSquare + 1];

        board[m.from] = EMPTY;
        hash ^= zobristPiece[moving][m.from];

        if (m.flags == 1) {
            int capSq = (side == 0) ? (m.to - 8) : (m.to + 8);
            u.captured = board[capSq];
            hash ^= zobristPiece[board[capSq]][capSq];
            board[capSq] = EMPTY;
        } else if (u.captured != EMPTY) {
            hash ^= zobristPiece[u.captured][m.to];
        }

        int placed = moving;
        if (m.promo) {
            placed = (side == 0) ? m.promo : (m.promo | 8);
        }
        board[m.to] = placed;
        hash ^= zobristPiece[placed][m.to];

        if (m.flags == 2) {
            if (m.to == 6) { board[7] = EMPTY; board[5] = wR; hash ^= zobristPiece[wR][7] ^ zobristPiece[wR][5]; }
            else if (m.to == 2) { board[0] = EMPTY; board[3] = wR; hash ^= zobristPiece[wR][0] ^ zobristPiece[wR][3]; }
            else if (m.to == 62) { board[63] = EMPTY; board[61] = bR; hash ^= zobristPiece[bR][63] ^ zobristPiece[bR][61]; }
            else if (m.to == 58) { board[56] = EMPTY; board[59] = bR; hash ^= zobristPiece[bR][56] ^ zobristPiece[bR][59]; }
        }

        if (m.from == 4 || m.to == 4) castling &= ~3;
        if (m.from == 60 || m.to == 60) castling &= ~12;
        if (m.from == 0 || m.to == 0) castling &= ~2;
        if (m.from == 7 || m.to == 7) castling &= ~1;
        if (m.from == 56 || m.to == 56) castling &= ~8;
        if (m.from == 63 || m.to == 63) castling &= ~4;

        epSquare = (m.flags == 4) ? ((m.from + m.to) / 2) : -1;
        halfmove = (pt == 1 || u.captured != EMPTY) ? 0 : (halfmove + 1);

        hash ^= zobristCastle[castling];
        hash ^= zobristEp[epSquare + 1];
        hash ^= zobristSide;

        int us = side;
        side ^= 1;

        int kingSq = findKing(us);
        if (kingSq != -1 && isSquareAttacked(kingSq, side)) {
            unmakeMove(m, u);
            return false;
        }
        return true;
    }

    void unmakeMove(const Move& m, const Undo& u) {
        side ^= 1;
        castling = u.castling;
        epSquare = u.epSquare;
        halfmove = u.halfmove;
        hash = u.hash;

        int placed = board[m.to];
        int orig = m.promo ? ((side == 0) ? wP : bP) : placed;
        board[m.from] = orig;

        if (m.flags == 1) {
            board[m.to] = EMPTY;
            int capSq = (side == 0) ? (m.to - 8) : (m.to + 8);
            board[capSq] = u.captured;
        } else {
            board[m.to] = u.captured;
        }

        if (m.flags == 2) {
            if (m.to == 6) { board[5] = EMPTY; board[7] = wR; }
            else if (m.to == 2) { board[3] = EMPTY; board[0] = wR; }
            else if (m.to == 62) { board[61] = EMPTY; board[63] = bR; }
            else if (m.to == 58) { board[59] = EMPTY; board[56] = bR; }
        }
    }

    int evaluate() const {
        int mg[2] = {0, 0}, eg[2] = {0, 0};
        int phase = 0;

        for (int sq = 0; sq < 64; ++sq) {
            int p = board[sq];
            if (p == EMPTY) continue;
            int c = pieceColor(p);
            int pt = pieceType(p);
            int tableSq = (c == 0) ? (sq ^ 56) : sq;

            mg[c] += MG_VAL[pt];
            eg[c] += EG_VAL[pt];
            phase += PHASE_INC[pt];

            switch (pt) {
                case 1: mg[c] += MG_PAWN[tableSq]; eg[c] += EG_PAWN[tableSq]; break;
                case 2: mg[c] += MG_KNIGHT[tableSq]; eg[c] += EG_KNIGHT[tableSq]; break;
                case 3: mg[c] += MG_BISHOP[tableSq]; eg[c] += EG_BISHOP[tableSq]; break;
                case 4: mg[c] += MG_ROOK[tableSq]; eg[c] += EG_ROOK[tableSq]; break;
                case 5: mg[c] += MG_QUEEN[tableSq]; eg[c] += EG_QUEEN[tableSq]; break;
                case 6: mg[c] += MG_KING[tableSq]; eg[c] += EG_KING[tableSq]; break;
            }
        }

        int mgScore = mg[side] - mg[side ^ 1];
        int egScore = eg[side] - eg[side ^ 1];
        int clampedPhase = std::min(phase, 24);
        return (mgScore * clampedPhase + egScore * (24 - clampedPhase)) / 24;
    }

    bool checkTime() {
        if ((nodes & 2047) == 0 && timeLimitMs > 0) {
            auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now() - startTime).count();
            if (elapsed >= timeLimitMs) stopFlag.store(true, std::memory_order_relaxed);
        }
        return stopFlag.load(std::memory_order_relaxed);
    }

    void scoreMoves(std::vector<Move>& moves, const Move& ttMove, int ply) const {
        for (auto& m : moves) {
            if (m == ttMove) {
                m.score = 1000000;
            } else if (board[m.to] != EMPTY || m.flags == 1) {
                int victim = (m.flags == 1) ? 1 : pieceType(board[m.to]);
                int attacker = pieceType(board[m.from]);
                m.score = 100000 + victim * 100 - attacker;
            } else if (m.promo) {
                m.score = 90000 + m.promo * 100;
            } else if (ply < 64 && m == killers[ply][0]) {
                m.score = 80000;
            } else if (ply < 64 && m == killers[ply][1]) {
                m.score = 70000;
            } else {
                m.score = history[board[m.from]][m.to];
            }
        }
    }

    int quiescence(int alpha, int beta) {
        if (checkTime()) return 0;
        nodes++;
        int standPat = evaluate();
        if (standPat >= beta) return beta;
        if (standPat > alpha) alpha = standPat;

        std::vector<Move> moves;
        generatePseudoMoves(moves, true);
        scoreMoves(moves, Move{}, 63);
        std::sort(moves.begin(), moves.end(), [](const Move& a, const Move& b) { return a.score > b.score; });

        Undo u;
        for (const auto& m : moves) {
            if (!makeMove(m, u)) continue;
            int score = -quiescence(-beta, -alpha);
            unmakeMove(m, u);
            if (stopFlag.load(std::memory_order_relaxed)) return 0;
            if (score >= beta) return beta;
            if (score > alpha) alpha = score;
        }
        return alpha;
    }

    int alphaBeta(int depth, int alpha, int beta, int ply) {
        if (checkTime()) return 0;
        if (depth <= 0) return quiescence(alpha, beta);

        nodes++;
        int origAlpha = alpha;
        TTEntry& entry = tt[hash & (tt.size() - 1)];
        Move ttMove;
        if (entry.key == hash) {
            ttMove = entry.bestMove;
            if (entry.depth >= depth && ply > 0) {
                if (entry.flag == 0) return entry.score;
                if (entry.flag == 1 && entry.score <= alpha) return alpha;
                if (entry.flag == 2 && entry.score >= beta) return beta;
            }
        }

        std::vector<Move> moves;
        generatePseudoMoves(moves, false);
        scoreMoves(moves, ttMove, ply);
        std::sort(moves.begin(), moves.end(), [](const Move& a, const Move& b) { return a.score > b.score; });

        int legalCount = 0;
        int bestScore = -1000000;
        Move bestMove;
        Undo u;

        for (const auto& m : moves) {
            if (!makeMove(m, u)) continue;
            legalCount++;
            int score;
            if (legalCount == 1) {
                score = -alphaBeta(depth - 1, -beta, -alpha, ply + 1);
            } else {
                score = -alphaBeta(depth - 1, -alpha - 1, -alpha, ply + 1);
                if (score > alpha && score < beta) {
                    score = -alphaBeta(depth - 1, -beta, -alpha, ply + 1);
                }
            }
            unmakeMove(m, u);
            if (stopFlag.load(std::memory_order_relaxed)) return 0;

            if (score > bestScore) {
                bestScore = score;
                bestMove = m;
            }
            if (score > alpha) {
                alpha = score;
                if (alpha >= beta) {
                    if (board[m.to] == EMPTY && m.flags != 1 && ply < 64) {
                        killers[ply][1] = killers[ply][0];
                        killers[ply][0] = m;
                        history[board[m.from]][m.to] += depth * depth;
                    }
                    break;
                }
            }
        }

        if (legalCount == 0) {
            int kingSq = findKing(side);
            if (kingSq != -1 && isSquareAttacked(kingSq, side ^ 1)) {
                return -29000 + ply;
            }
            return 0;
        }

        entry.key = hash;
        entry.depth = depth;
        entry.score = bestScore;
        entry.bestMove = bestMove;
        entry.flag = (bestScore <= origAlpha) ? 1 : (bestScore >= beta ? 2 : 0);
        return bestScore;
    }

    SearchSummary search(int maxDepth, int moveTimeMs, bool emitUciLogs) {
        stopFlag.store(false, std::memory_order_relaxed);
        nodes = 0;
        startTime = std::chrono::steady_clock::now();
        timeLimitMs = moveTimeMs;
        std::memset(killers, 0, sizeof(killers));
        std::memset(history, 0, sizeof(history));

        Move finalBest;
        int finalScore = 0;
        int completedDepth = 0;

        std::vector<Move> rootMoves;
        generatePseudoMoves(rootMoves, false);
        Undo u;
        for (const auto& m : rootMoves) {
            if (makeMove(m, u)) {
                finalBest = m;
                unmakeMove(m, u);
                break;
            }
        }

        for (int d = 1; d <= maxDepth; ++d) {
            int score = alphaBeta(d, -100000, 100000, 0);
            if (stopFlag.load(std::memory_order_relaxed) && d > 1) break;

            completedDepth = d;
            TTEntry& entry = tt[hash & (tt.size() - 1)];
            if (entry.key == hash && entry.bestMove.from != entry.bestMove.to) {
                finalBest = entry.bestMove;
                finalScore = score;
            }

            if (emitUciLogs) {
                auto elapsed = std::max<long long>(1, std::chrono::duration_cast<std::chrono::milliseconds>(
                    std::chrono::steady_clock::now() - startTime).count());
                uint64_t nps = (nodes * 1000ULL) / elapsed;
                std::string scoreStr;
                if (std::abs(finalScore) > 28000) {
                    int matePlies = 29000 - std::abs(finalScore);
                    int mateMoves = (matePlies + 1) / 2;
                    scoreStr = "mate " + std::to_string(finalScore > 0 ? mateMoves : -mateMoves);
                } else {
                    scoreStr = "cp " + std::to_string(finalScore);
                }
                std::cout << "info depth " << d
                          << " score " << scoreStr
                          << " nodes " << nodes
                          << " nps " << nps
                          << " time " << elapsed
                          << " pv " << finalBest.toUci() << std::endl;
            }

            if (std::abs(finalScore) > 28000) break;
        }

        if (emitUciLogs) {
            std::cout << "bestmove " << finalBest.toUci() << std::endl;
        }

        SearchSummary summary;
        summary.bestMoveUci = finalBest.toUci();
        summary.depth = completedDepth;
        summary.nodes = nodes;
        summary.elapsedMs = std::max<long long>(1, std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - startTime).count());

        if (std::abs(finalScore) > 28000) {
            int matePlies = 29000 - std::abs(finalScore);
            int mateMoves = (matePlies + 1) / 2;
            summary.mateIn = (finalScore > 0) ? mateMoves : -mateMoves;
            summary.scoreCp = (finalScore > 0) ? 29000 : -29000;
        } else {
            summary.scoreCp = finalScore;
            summary.mateIn = 0;
        }

        if (finalBest.from != finalBest.to && makeMove(finalBest, u)) {
            TTEntry& replyEntry = tt[hash & (tt.size() - 1)];
            if (replyEntry.key == hash && replyEntry.bestMove.from != replyEntry.bestMove.to) {
                summary.ponderMoveUci = replyEntry.bestMove.toUci();
            }
            unmakeMove(finalBest, u);
        }

        return summary;
    }

    void applyUciMove(const std::string& uci) {
        if (uci.size() < 4) return;
        int from = (uci[1] - '1') * 8 + (uci[0] - 'a');
        int to = (uci[3] - '1') * 8 + (uci[2] - 'a');
        int promo = 0;
        if (uci.size() >= 5) {
            switch (uci[4]) {
                case 'n': promo = 2; break;
                case 'b': promo = 3; break;
                case 'r': promo = 4; break;
                case 'q': promo = 5; break;
            }
        }
        std::vector<Move> moves;
        generatePseudoMoves(moves, false);
        Undo u;
        for (const auto& m : moves) {
            if (m.from == from && m.to == to && m.promo == promo) {
                if (makeMove(m, u)) return;
            }
        }
    }
};

} // namespace AboodEngine

#ifdef BUILD_JNI_LIB

static AboodEngine::Engine g_jniEngine;
static std::mutex g_jniMutex;

extern "C" JNIEXPORT jstring JNICALL
Java_com_abood_chessassistant_engine_NativeChessBridge_analyzeFenNative(
        JNIEnv* env,
        jobject,
        jstring fenJStr,
        jint maxDepth,
        jint moveTimeMs) {
    std::lock_guard<std::mutex> lock(g_jniMutex);
    const char* fenChars = env->GetStringUTFChars(fenJStr, nullptr);
    std::string fen(fenChars ? fenChars : "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1");
    if (fenChars) env->ReleaseStringUTFChars(fenJStr, fenChars);

    g_jniEngine.setFen(fen);
    AboodEngine::SearchSummary res = g_jniEngine.search(
        static_cast<int>(maxDepth),
        static_cast<int>(moveTimeMs),
        false
    );

    std::ostringstream out;
    out << res.bestMoveUci << "|"
        << res.ponderMoveUci << "|"
        << res.scoreCp << "|"
        << res.mateIn << "|"
        << res.depth << "|"
        << res.nodes << "|"
        << res.elapsedMs;
    return env->NewStringUTF(out.str().c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_abood_chessassistant_engine_NativeChessBridge_stopSearchNative(
        JNIEnv*,
        jobject) {
    g_jniEngine.stopFlag.store(true, std::memory_order_relaxed);
}

#else

int main() {
    std::ios::sync_with_stdio(false);
    std::cin.tie(nullptr);

    AboodEngine::Engine engine;
    std::string line;
    while (std::getline(std::cin, line)) {
        if (line.empty()) continue;
        std::istringstream ss(line);
        std::string cmd;
        ss >> cmd;

        if (cmd == "uci") {
            std::cout << "id name Chess Abood Engine 1.0 (Stockfish-Compatible UCI)\n";
            std::cout << "id author Abdullah Al-Ariki\n";
            std::cout << "uciok\n" << std::flush;
        } else if (cmd == "isready") {
            std::cout << "readyok\n" << std::flush;
        } else if (cmd == "ucinewgame") {
            engine.setFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1");
        } else if (cmd == "position") {
            std::string sub;
            ss >> sub;
            if (sub == "startpos") {
                engine.setFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1");
            } else if (sub == "fen") {
                std::string fen, token;
                int count = 0;
                while (count < 6 && (ss >> token)) {
                    if (token == "moves") break;
                    if (!fen.empty()) fen += " ";
                    fen += token;
                    count++;
                }
                engine.setFen(fen);
                if (token == "moves") {
                    while (ss >> token) engine.applyUciMove(token);
                }
                continue;
            }
            std::string tok;
            if (ss >> tok && tok == "moves") {
                while (ss >> tok) engine.applyUciMove(tok);
            }
        } else if (cmd == "go") {
            int depth = 12;
            int movetime = 800;
            std::string tok;
            bool depthSet = false, timeSet = false;
            while (ss >> tok) {
                if (tok == "depth") { ss >> depth; depthSet = true; }
                else if (tok == "movetime") { ss >> movetime; timeSet = true; }
            }
            if (depthSet && !timeSet) movetime = 5000;
            if (timeSet && !depthSet) depth = 22;
            engine.search(depth, movetime, true);
        } else if (cmd == "stop") {
            engine.stopFlag.store(true, std::memory_order_relaxed);
        } else if (cmd == "quit") {
            break;
        }
    }
    return 0;
}

#endif
