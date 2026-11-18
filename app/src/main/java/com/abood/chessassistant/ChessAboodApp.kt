package com.abood.chessassistant

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class ChessAboodApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "مساعد شطرنج عبود العائم",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "إشعار خدمة تحليل الشطرنج الذكي"
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "chess_abood_assistant_channel"
    }
}
