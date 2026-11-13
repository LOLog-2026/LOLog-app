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
 * FCM 푸시 알림(서현 담당, 채널 push): 새 카드, 만화 완성·실패(시안 12번), 내 캐릭터 완성·실패, 친구 요청. 문구에는 대사를 넣지 않아요.
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
            // 직접 쓴 글의 카드면 textId가 와요(texts.md 6장 푸시)
            PushType.CARDS_CREATED -> if (payload.textId != null) {
                Triple(NotificationChannels.PUSH, "새 이야기 카드 1개", "쓴 글로 카드를 만들었어요")
            } else {
                Triple(NotificationChannels.PUSH, "새 이야기 카드 ${payload.cardCount ?: payload.cardIds.size}개", "방금 녹음에서 재밌는 순간을 찾았어요")
            }
            PushType.COMIC_DONE -> Triple(NotificationChannels.PUSH, "4컷만화가 완성됐어요 🎉", cardTitle?.let { "\"$it\"를 지금 볼 수 있어요." } ?: "지금 볼 수 있어요.")
            PushType.COMIC_FAILED -> Triple(NotificationChannels.PUSH, "만화를 만들지 못했어요", "다시 그리거나 카드를 고쳐 볼 수 있어요.")
            // 실패 제목은 api-dto 푸시 표 그대로. 완성 문구는 명세에 없어서 정했어요
            PushType.PROFILE_CHARACTER_READY -> Triple(NotificationChannels.PUSH, "내 캐릭터가 완성됐어요", "이제 친구가 내 캐릭터를 만화에 쓸 수 있어요.")
            PushType.PROFILE_CHARACTER_FAILED -> Triple(NotificationChannels.PUSH, "캐릭터를 만들지 못했어요", "다른 사진으로 다시 만들어 볼 수 있어요.")
            // 문구에는 닉네임만 넣어요(friends.md 3장). 예: "민수님이 친구 요청을 보냈어요"
            PushType.FRIEND_REQUEST -> Triple(
                NotificationChannels.PUSH, "친구 요청이 왔어요",
                payload.nickname?.let { "${it}님이 친구 요청을 보냈어요" } ?: "새 친구 요청이 있어요",
            )
            // 방 푸시(rooms.md 3장). 문구는 알림함 줄(InboxTexts)과 같게 맞춰요. 댓글 내용은 넣지 않아요(FR-NOTI-03)
            PushType.ROOM_INVITE -> Triple(
                NotificationChannels.PUSH, "방에 초대받았어요",
                payload.roomName?.let { "\"$it\" 방에 초대받았어요. 눌러서 수락하거나 거절해요" } ?: "눌러서 수락하거나 거절해요",
            )
            PushType.ROOM_COMMENT -> Triple(
                NotificationChannels.PUSH, "내 만화에 댓글이 달렸어요",
                payload.nickname?.let { "${it}님이 내 만화에 댓글을 달았어요" } ?: "방에서 확인해 보세요",
            )
            // 쇼츠(video.md 2장). 문구에 대사를 넣지 않아요(FR-NOTI-03)
            PushType.VIDEO_DONE -> Triple(NotificationChannels.PUSH, "쇼츠가 완성됐어요 🎬", cardTitle?.let { "\"$it\"를 지금 볼 수 있어요." } ?: "지금 볼 수 있어요.")
            PushType.VIDEO_FAILED -> Triple(NotificationChannels.PUSH, "쇼츠를 만들지 못했어요", "카드에서 다시 만들 수 있어요.")
            PushType.ROOM_DISBANDED -> Triple(
                NotificationChannels.PUSH, payload.roomName?.let { "\"$it\" 방이 해산됐어요" } ?: "방이 해산됐어요",
                "방의 만화와 대화가 지워졌어요",
            )
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
        private const val EXTRA_TEXT = "push_text"
        private const val EXTRA_CARDS = "push_cards"
        private const val EXTRA_COMIC = "push_comic"
        private const val EXTRA_CARD = "push_card"
        private const val EXTRA_COUNT = "push_count"
        private const val EXTRA_REQUEST = "push_request"
        private const val EXTRA_NICKNAME = "push_nickname"
        private const val EXTRA_ROOM = "push_room"
        private const val EXTRA_ROOM_NAME = "push_room_name"
        private const val EXTRA_VIDEO = "push_video"

        fun Intent.putPayload(p: PushPayload) {
            putExtra(EXTRA_TYPE, p.type.name)
            p.recordingId?.let { putExtra(EXTRA_RECORDING, it) }
            p.textId?.let { putExtra(EXTRA_TEXT, it) }
            p.cardCount?.let { putExtra(EXTRA_COUNT, it) }
            putExtra(EXTRA_CARDS, p.cardIds.toLongArray())
            p.comicId?.let { putExtra(EXTRA_COMIC, it) }
            p.cardId?.let { putExtra(EXTRA_CARD, it) }
            p.requestId?.let { putExtra(EXTRA_REQUEST, it) }
            p.nickname?.let { putExtra(EXTRA_NICKNAME, it) }
            p.roomId?.let { putExtra(EXTRA_ROOM, it) }
            p.roomName?.let { putExtra(EXTRA_ROOM_NAME, it) }
            p.videoId?.let { putExtra(EXTRA_VIDEO, it) }
        }

        /**
         * FCM data(문자열 맵) → PushPayload. 키는 명세(api-dto 3장): type, recordingId(직접 쓴 글은 textId), cardCount, comicId, cardId,
         * FRIEND_REQUEST는 requestId, nickname. 방 푸시는 roomId, roomName, nickname(rooms.md 3장).
         * 카드를 지웠으면 cardId가 빈 문자열이라 null이 돼요. 모르는 type이면 null.
         */
        fun payloadOf(data: Map<String, String>): PushPayload? {
            val type = data["type"] ?: return null
            // NEW_CARDS는 11/5 옛 이름. 명세는 CARDS_CREATED
            val t = runCatching { PushType.valueOf(if (type == "NEW_CARDS") "CARDS_CREATED" else type) }.getOrNull() ?: return null
            return PushPayload(
                type = t,
                recordingId = data["recordingId"]?.toLongOrNull(),
                textId = data["textId"]?.toLongOrNull(),
                cardCount = data["cardCount"]?.toIntOrNull(),
                cardIds = data["cardIds"]?.split(",")?.mapNotNull { it.trim().toLongOrNull() }.orEmpty(),
                comicId = data["comicId"]?.toLongOrNull(),
                cardId = data["cardId"]?.toLongOrNull(),
                requestId = data["requestId"]?.toLongOrNull(),
                nickname = data["nickname"]?.ifBlank { null },
                roomId = data["roomId"]?.toLongOrNull(),
                roomName = data["roomName"]?.ifBlank { null },
                videoId = data["videoId"]?.toLongOrNull(),
            )
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
                textId = long(EXTRA_TEXT, "textId"),
                cardCount = if (hasExtra(EXTRA_COUNT)) getIntExtra(EXTRA_COUNT, 0) else getStringExtra("cardCount")?.toIntOrNull(),
                cardIds = cards,
                comicId = long(EXTRA_COMIC, "comicId"),
                cardId = long(EXTRA_CARD, "cardId"),
                requestId = long(EXTRA_REQUEST, "requestId"),
                nickname = (getStringExtra(EXTRA_NICKNAME) ?: getStringExtra("nickname"))?.ifBlank { null },
                roomId = long(EXTRA_ROOM, "roomId"),
                roomName = (getStringExtra(EXTRA_ROOM_NAME) ?: getStringExtra("roomName"))?.ifBlank { null },
                videoId = long(EXTRA_VIDEO, "videoId"),
            )
        }
    }
}
