package com.lolog.app.notification

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.lolog.app.MainActivity
import com.lolog.app.R
import com.lolog.app.data.model.PushPayload
import com.lolog.app.data.model.PushType

/**
 * FCM 푸시 알림(서현 담당, 채널 push): 새 카드, 만화 완성·실패(시안 12번). 문구에는 대사를 넣지 않아요.
 * 실제 서버에서는 FCM data 메시지(api-spec.md 푸시 표: type CARDS_CREATED(recordingId, cardCount) / COMIC_DONE·COMIC_FAILED(comicId, cardId), 값은 모두 문자열)를 받아 show()를 불러요.
 * 목업에서는 MockLologRepository.pushEvents가 같은 값을 흘려보내요.
 */
class Notifier(private val context: Context) {

    fun createChannels() = NotificationChannels.createAll(context)

    fun show(payload: PushPayload, cardTitle: String? = null) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val (channel, title, body) = when (payload.type) {
            PushType.CARDS_CREATED -> Triple(NotificationChannels.PUSH, "새 이야기 카드 ${payload.cardCount ?: payload.cardIds.size}개", "방금 녹음에서 재밌는 순간을 찾았어요")
            PushType.COMIC_DONE -> Triple(NotificationChannels.PUSH, "4컷만화가 완성됐어요 🎉", cardTitle?.let { "\"$it\"를 지금 볼 수 있어요." } ?: "지금 볼 수 있어요.")
            PushType.COMIC_FAILED -> Triple(NotificationChannels.PUSH, "만화를 그리지 못했어요", "다시 그리거나 카드를 고쳐 볼 수 있어요.")
        }
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putPayload(payload)
        }
        val pi = PendingIntent.getActivity(
            context, payload.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_launcher)
            .setColor(0xFF2B4FD8.toInt())
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        NotificationManagerCompat.from(context).notify(payload.hashCode(), n)
    }

    companion object {
        private const val EXTRA_TYPE = "push_type"
        private const val EXTRA_RECORDING = "push_recording"
        private const val EXTRA_CARDS = "push_cards"
        private const val EXTRA_COMIC = "push_comic"
        private const val EXTRA_CARD = "push_card"
        private const val EXTRA_COUNT = "push_count"

        fun Intent.putPayload(p: PushPayload) {
            putExtra(EXTRA_TYPE, p.type.name)
            p.recordingId?.let { putExtra(EXTRA_RECORDING, it) }
            p.cardCount?.let { putExtra(EXTRA_COUNT, it) }
            putExtra(EXTRA_CARDS, p.cardIds.toLongArray())
            p.comicId?.let { putExtra(EXTRA_COMIC, it) }
            p.cardId?.let { putExtra(EXTRA_CARD, it) }
        }

        /** 알림을 눌러 들어온 Intent → PushPayload. FCM data(문자열 맵)도 같은 키로 읽어요. */
        fun Intent.readPayload(): PushPayload? {
            val type = getStringExtra(EXTRA_TYPE) ?: getStringExtra("type") ?: return null
            // NEW_CARDS는 11/5 옛 이름. 명세는 CARDS_CREATED
            val t = runCatching { PushType.valueOf(if (type == "NEW_CARDS") "CARDS_CREATED" else type) }.getOrNull() ?: return null
            fun long(key: String, fcmKey: String): Long? =
                if (hasExtra(key)) getLongExtra(key, -1).takeIf { it >= 0 } else getStringExtra(fcmKey)?.toLongOrNull()
            val cards = getLongArrayExtra(EXTRA_CARDS)?.toList()
                ?: getStringExtra("cardIds")?.split(",")?.mapNotNull { it.trim().toLongOrNull() }
                ?: emptyList()
            return PushPayload(
                type = t,
                recordingId = long(EXTRA_RECORDING, "recordingId"),
                cardCount = if (hasExtra(EXTRA_COUNT)) getIntExtra(EXTRA_COUNT, 0) else getStringExtra("cardCount")?.toIntOrNull(),
                cardIds = cards,
                comicId = long(EXTRA_COMIC, "comicId"),
                cardId = long(EXTRA_CARD, "cardId"),
            )
        }
    }
}
