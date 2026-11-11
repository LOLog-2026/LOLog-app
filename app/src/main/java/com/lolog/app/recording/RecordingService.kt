package com.lolog.app.recording

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.lolog.app.notification.NotificationChannels

/** 화면이 꺼지거나 다른 앱을 써도 녹음이 이어지게 한다. 실제 일은 RealRecordingController가 한다. */
class RecordingService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioSaved = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // 녹음이 이미 끝났는데 알림이 남아 있었으면 서비스를 바로 닫는다.
            RealRecordingController.active?.stop() ?: stopSelf()
            return START_NOT_STICKY
        }
        audioSaved = intent?.getBooleanExtra(EXTRA_AUDIO_SAVED, audioSaved) ?: audioSaved
        val notification = notification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lolog:recording")
                .apply { acquire(WAKE_LOCK_TIMEOUT_MS) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // 시스템이 서비스를 끄면 그때까지 쌓인 문장으로 녹음을 마감한다(기능 명세 FR-REC-01·02 예외).
        // 녹음이 정상으로 끝나 서비스를 닫은 경우에는 이미 멈춰 있어 아무 일도 하지 않는다.
        RealRecordingController.active?.stop()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun notification(): Notification {
        val stop = PendingIntent.getService(
            this, 0, Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, NotificationChannels.RECORDING)
            .setContentTitle("녹음 중이에요")
            // 문구는 명세 audio.md 1장 "안내 문구" 표 그대로
            .setContentText(
                if (audioSaved) "대화를 글자로 바꾸고, 음성도 저장하고 있어요." else "대화를 글자로 바꾸고 있어요. 음성은 폰 밖으로 나가지 않아요.",
            )
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "끝내기", stop).build())
            .build()
    }

    companion object {
        private const val ACTION_STOP = "com.lolog.app.recording.STOP"
        private const val EXTRA_AUDIO_SAVED = "audioSaved"
        private const val NOTIFICATION_ID = 1001
        private const val WAKE_LOCK_TIMEOUT_MS = 3 * 60 * 60 * 1000L + 10 * 60 * 1000L

        fun start(context: Context, audioSaved: Boolean) {
            context.startForegroundService(
                Intent(context, RecordingService::class.java).putExtra(EXTRA_AUDIO_SAVED, audioSaved),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RecordingService::class.java))
        }
    }
}
