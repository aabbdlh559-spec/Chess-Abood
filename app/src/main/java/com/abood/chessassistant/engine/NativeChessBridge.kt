package com.abood.chessassistant.engine

object NativeChessBridge {
    val isNativeAvailable: Boolean = try {
        System.loadLibrary("abood_jni")
        true
    } catch (_: Throwable) {
        false
    }

    external fun analyzeFenNative(fen: String, maxDepth: Int, moveTimeMs: Int): String
    external fun stopSearchNative()
}
