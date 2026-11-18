package com.abood.chessassistant.data

import android.content.Context
import com.abood.chessassistant.model.BoardRectRatio
import com.abood.chessassistant.model.PlayerColorMode
import com.abood.chessassistant.model.TurnMode
import org.json.JSONArray
import org.json.JSONObject

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("chess_abood_prefs_v2", Context.MODE_PRIVATE)

    var searchDepth: Int
        get() = prefs.getInt("search_depth", 11)
        set(v) = prefs.edit().putInt("search_depth", v.coerceIn(4, 20)).apply()

    var moveTimeMs: Int
        get() = prefs.getInt("move_time_ms", 2000)
        set(v) = prefs.edit().putInt("move_time_ms", v.coerceIn(200, 3000)).apply()

    var scanIntervalMs: Long
        get() = prefs.getLong("scan_interval_ms", 420L)
        set(v) = prefs.edit().putLong("scan_interval_ms", v.coerceIn(250L, 2000L)).apply()

    var playerColorMode: PlayerColorMode
        get() = PlayerColorMode.entries.getOrElse(prefs.getInt("player_color_mode", 0)) { PlayerColorMode.AUTO }
        set(v) = prefs.edit().putInt("player_color_mode", v.ordinal).apply()

    var turnMode: TurnMode
        get() = TurnMode.entries.getOrElse(prefs.getInt("turn_mode", 0)) { TurnMode.AUTO }
        set(v) = prefs.edit().putInt("turn_mode", v.ordinal).apply()

    var showArrows: Boolean
        get() = prefs.getBoolean("show_arrows", true)
        set(v) = prefs.edit().putBoolean("show_arrows", v).apply()

    var showEvalBar: Boolean
        get() = prefs.getBoolean("show_eval_bar", true)
        set(v) = prefs.edit().putBoolean("show_eval_bar", v).apply()

    var showBoardGrid: Boolean
        get() = prefs.getBoolean("show_board_grid", true)
        set(v) = prefs.edit().putBoolean("show_board_grid", v).apply()

    var autoLearnPieces: Boolean
        get() = prefs.getBoolean("auto_learn_pieces", true)
        set(v) = prefs.edit().putBoolean("auto_learn_pieces", v).apply()

    var hasUserCalibrated: Boolean
        get() = prefs.getBoolean("has_user_calibrated", false)
        set(v) = prefs.edit().putBoolean("has_user_calibrated", v).apply()

    var boardRectRatio: BoardRectRatio
        get() = BoardRectRatio(
            left = prefs.getFloat("board_left", 0.03f),
            top = prefs.getFloat("board_top", 0.24f),
            right = prefs.getFloat("board_right", 0.97f),
            bottom = prefs.getFloat("board_bottom", 0.72f)
        )
        set(r) = prefs.edit()
            .putFloat("board_left", r.left)
            .putFloat("board_top", r.top)
            .putFloat("board_right", r.right)
            .putFloat("board_bottom", r.bottom)
            .apply()

    fun saveLearnedTemplates(templates: Map<Char, FloatArray>) {
        val root = JSONObject()
        for ((piece, vec) in templates) {
            val arr = JSONArray()
            for (f in vec) arr.put(f.toDouble())
            root.put(piece.toString(), arr)
        }
        prefs.edit().putString("learned_piece_templates", root.toString()).apply()
    }

    fun loadLearnedTemplates(): Map<Char, FloatArray> {
        val raw = prefs.getString("learned_piece_templates", null) ?: return emptyMap()
        return try {
            val root = JSONObject(raw)
            val result = mutableMapOf<Char, FloatArray>()
            val keys = root.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val arr = root.getJSONArray(k)
                val vec = FloatArray(arr.length()) { idx -> arr.getDouble(idx).toFloat() }
                if (k.isNotEmpty()) result[k[0]] = vec
            }
            result
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
