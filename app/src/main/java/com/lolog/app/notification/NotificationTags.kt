package com.lolog.app.notification

import android.app.NotificationManager
import android.content.Context
import com.lolog.app.data.model.BadgeKind
import com.lolog.app.data.model.PushPayload
import com.lolog.app.data.model.PushType

/**
 * 알림창 맞추기(LOLog-docs spec/inbox.md 4장, FR-NOTI-06).
 * 푸시를 띄울 때 꼬리표(tag)를 달아 두고, 그 화면을 열거나 알림함에서 누르면 같은 꼬리표 알림을 알림창에서 지워요.
 * 명세 꼬리표는 friend · room:{id} · cards · comic:{id}예요. 명세 표에 없는 것은 가장 가까운 기능에 맞췄어요(앱이 정함):
 *  - 쇼츠 완성·실패 → video:{id} (만화와 같은 컨텐츠 화면에서 지워요)
 *  - 내 캐릭터 완성·실패, 내 목소리 완성·실패 → character
 *  - 방 해산 → room (서버가 roomId를 안 보내요. 방 목록을 열면 지워요)
 * 번호가 없는 푸시는 번호 없는 꼬리표(comic, video, room)를 달아요.
 */
object NotificationTags {
    const val FRIEND = "friend"
    const val CARDS = "cards"
    const val CHARACTER = "character"
    private const val COMIC = "comic"
    private const val VIDEO = "video"
    private const val ROOM = "room"

    fun comic(id: Long) = "$COMIC:$id"
    fun video(id: Long) = "$VIDEO:$id"
    fun room(id: Long) = "$ROOM:$id"

    /** 푸시마다 달 꼬리표 */
    fun of(p: PushPayload): String = when (p.type) {
        PushType.CARDS_CREATED -> CARDS
        PushType.COMIC_DONE, PushType.COMIC_FAILED -> p.comicId?.let(::comic) ?: COMIC
        PushType.VIDEO_DONE, PushType.VIDEO_FAILED -> p.videoId?.let(::video) ?: VIDEO
        PushType.PROFILE_CHARACTER_READY, PushType.PROFILE_CHARACTER_FAILED,
        PushType.VOICE_CLONE_READY, PushType.VOICE_CLONE_FAILED -> CHARACTER
        PushType.FRIEND_REQUEST -> FRIEND
        PushType.ROOM_INVITE, PushType.ROOM_COMMENT, PushType.ROOM_DISBANDED -> p.roomId?.let(::room) ?: ROOM
    }

    /**
     * 새 소식 점 [kind]를 읽음으로 할 때 같이 지울 꼬리표인지.
     * 점이 있는 화면(목록)을 열면 그 기능의 알림을 모두 지워요. 종 버튼은 알림을 하나씩 읽어서 여기서는 안 지워요
     */
    fun belongsTo(kind: BadgeKind, tag: String): Boolean = when (kind) {
        BadgeKind.CARDS -> tag == CARDS
        BadgeKind.COMICS -> isOf(COMIC, tag) || isOf(VIDEO, tag)
        BadgeKind.PROFILE_CHARACTER -> tag == CHARACTER
        BadgeKind.FRIENDS -> tag == FRIEND
        BadgeKind.ROOMS -> isOf(ROOM, tag)
        BadgeKind.INBOX -> false
    }

    private fun isOf(prefix: String, tag: String) = tag == prefix || tag.startsWith("$prefix:")

    /** 꼬리표가 [tag]인 알림을 알림창에서 지워요 */
    fun cancel(context: Context, tag: String) = cancelWhere(context) { it == tag }

    /** 새 소식 점 [kind]의 알림을 알림창에서 모두 지워요 */
    fun cancel(context: Context, kind: BadgeKind) = cancelWhere(context) { belongsTo(kind, it) }

    /** 알림 번호는 알림마다 달라서, 지금 떠 있는 알림 중 꼬리표가 맞는 것을 찾아 지워요 */
    private fun cancelWhere(context: Context, match: (String) -> Boolean) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            nm.activeNotifications
                .filter { n -> n.tag?.let(match) == true }
                .forEach { n -> nm.cancel(n.tag, n.id) }
        }
    }
}
