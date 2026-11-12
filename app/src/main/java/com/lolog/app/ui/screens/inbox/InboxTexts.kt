package com.lolog.app.ui.screens.inbox

import com.lolog.app.data.model.InboxItem
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZonedDateTime

/*
 * 알림함 한 줄의 글자. 서버는 문구를 주지 않아서(inbox.md 1장) 앱이 type과 data로 만들어요. 대사는 넣지 않아요(FR-NOTI-03).
 * 문구는 명세에 없어서 프론트가 정했어요. 바꿀 때는 이 파일만 고쳐요.
 */

/** 굵은 제목 한 줄 + 작은 글 한 줄 */
data class InboxLine(val title: String, val sub: String)

/** 줄 왼쪽 작은 둥근 네모 표시. 색은 InboxScreen에서 정해요 */
enum class InboxBadge(val label: String) {
    NOTICE("공지"),
    CARDS("카드"),
    COMIC("만화"),
    CHARACTER("캐릭터"),
    FRIEND("친구"),
    ROOM("방"),
    OTHER("알림"),
}

/** 방 알림(ROOM_INVITE·ROOM_COMMENT·ROOM_DISBANDED)을 눌렀을 때 갈 곳. 푸시 이동(notification/)과 따로 ui에서 정해요 */
sealed interface RoomTarget {
    /** 방 목록(받은 초대가 맨 위) */
    data object List : RoomTarget
    /** 그 방의 대화 탭 */
    data class Chat(val roomId: Long) : RoomTarget
}

/** 방 알림이면 갈 곳, 아니면 null(rooms.md 3장 푸시 표의 앱 동작) */
fun InboxItem.Notification.roomTarget(): RoomTarget? = when (type) {
    "ROOM_INVITE", "ROOM_DISBANDED" -> RoomTarget.List
    "ROOM_COMMENT" -> data["roomId"]?.toLongOrNull()?.let { RoomTarget.Chat(it) } ?: RoomTarget.List
    else -> null
}

fun InboxItem.badge(): InboxBadge = when (this) {
    is InboxItem.Notice -> InboxBadge.NOTICE
    is InboxItem.Notification -> when (type) {
        "CARDS_CREATED" -> InboxBadge.CARDS
        "COMIC_DONE", "COMIC_FAILED", "VIDEO_DONE", "VIDEO_FAILED" -> InboxBadge.COMIC
        "PROFILE_CHARACTER_READY", "PROFILE_CHARACTER_FAILED" -> InboxBadge.CHARACTER
        "FRIEND_REQUEST" -> InboxBadge.FRIEND
        "ROOM_INVITE", "ROOM_COMMENT", "ROOM_DISBANDED" -> InboxBadge.ROOM
        else -> InboxBadge.OTHER
    }
}

fun InboxItem.line(): InboxLine = when (this) {
    // 공지: 제목 그대로 / 본문 첫 줄
    is InboxItem.Notice -> InboxLine(title, body.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty())
    is InboxItem.Notification -> when (type) {
        "CARDS_CREATED" -> InboxLine(
            data["cardCount"]?.toIntOrNull()?.let { "이야기 카드 ${it}장이 생겼어요" } ?: "이야기 카드가 생겼어요",
            if (data.containsKey("textId")) "쓴 글로 카드를 만들었어요" else "녹음에서 재밌는 순간을 찾았어요",
        )
        "COMIC_DONE" -> InboxLine("4컷만화가 다 그려졌어요", "눌러서 볼 수 있어요")
        "COMIC_FAILED" -> InboxLine("4컷만화를 그리지 못했어요", "다시 그려 볼 수 있어요")
        "VIDEO_DONE" -> InboxLine("쇼츠가 완성됐어요", "눌러서 볼 수 있어요")
        "VIDEO_FAILED" -> InboxLine("쇼츠를 만들지 못했어요", "카드에서 다시 만들 수 있어요")
        "PROFILE_CHARACTER_READY" -> InboxLine("내 캐릭터 그림이 생겼어요", "친구들이 화자 그림으로 쓸 수 있어요")
        "PROFILE_CHARACTER_FAILED" -> InboxLine("내 캐릭터를 만들지 못했어요", "사진을 다시 골라 주세요")
        "FRIEND_REQUEST" -> InboxLine(
            data["nickname"]?.takeIf { it.isNotBlank() }?.let { "${it}님이 친구 요청을 보냈어요" } ?: "친구 요청이 왔어요",
            "받은 요청에서 수락할 수 있어요",
        )
        // 방 알림(rooms.md 3장). 초대 푸시에는 초대한 사람 닉네임이 없어서, 오면 쓰고 없으면 방 이름만 써요
        "ROOM_INVITE" -> InboxLine(
            data["nickname"]?.takeIf { it.isNotBlank() }?.let { "${it}님이 \"${roomName()}\" 방에 초대했어요" }
                ?: "\"${roomName()}\" 방에 초대받았어요",
            "눌러서 수락하거나 거절해요",
        )
        "ROOM_COMMENT" -> InboxLine(
            data["nickname"]?.takeIf { it.isNotBlank() }?.let { "${it}님이 내 만화에 댓글을 달았어요" } ?: "내 만화에 댓글이 달렸어요",
            data["roomName"].orEmpty(),
        )
        "ROOM_DISBANDED" -> InboxLine("\"${roomName()}\" 방이 해산됐어요", "방의 만화와 대화가 지워졌어요")
        // 앞으로 생길 종류. 목록에서 빼지 않아요
        else -> InboxLine("새 소식이 있어요", "")
    }
}

private fun InboxItem.Notification.roomName() = data["roomName"].orEmpty()

/** 오른쪽 시간: "방금" · "n분 전" · "n시간 전" · "어제" · "11/10". [now]는 테스트에서 시각을 고정하려고 받아요 */
fun inboxTime(iso: String, now: ZonedDateTime = ZonedDateTime.now()): String {
    val t = runCatching { OffsetDateTime.parse(iso) }.getOrNull()?.atZoneSameInstant(now.zone) ?: return ""
    val sec = Duration.between(t, now).seconds.coerceAtLeast(0)
    return when {
        sec < 60 -> "방금"
        sec < 3600 -> "${sec / 60}분 전"
        sec < 86_400 -> "${sec / 3600}시간 전"
        t.toLocalDate() == now.toLocalDate().minusDays(1) -> "어제"
        else -> "${t.monthValue}/${t.dayOfMonth}"
    }
}
