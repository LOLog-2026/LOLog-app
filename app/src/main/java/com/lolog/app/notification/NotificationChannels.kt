package com.lolog.app.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.lolog.app.R

/**
 * 알림 채널(지원 ↔ 서현 약속). 채널 ID는 한 번 깔리면 바꾸기 어려우니 여기 값만 써요.
 *  - recording     : 녹음 중 상단 알림 (지원, 녹음 서비스)
 *  - recording_end : 3시간이 되어 녹음이 끝났다는 알림 (지원)
 *  - push          : FCM 푸시 — 새 카드, 만화 완성·실패 (서현)
 */
object NotificationChannels {
    const val RECORDING = "recording"
    const val RECORDING_END = "recording_end"
    const val PUSH = "push"

    fun createAll(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(RECORDING, context.getString(R.string.channel_recording), NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(RECORDING_END, context.getString(R.string.channel_recording_end), NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(PUSH, context.getString(R.string.channel_push), NotificationManager.IMPORTANCE_HIGH),
            ),
        )
    }
}
