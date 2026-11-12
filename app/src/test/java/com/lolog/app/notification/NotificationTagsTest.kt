package com.lolog.app.notification

import com.lolog.app.data.model.BadgeKind
import com.lolog.app.data.model.PushPayload
import com.lolog.app.data.model.PushType
import com.lolog.app.notification.Notifier.Companion.payloadOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 알림 꼬리표(inbox.md 4장 "알림창 맞추기", FR-NOTI-06)를 명세대로 다는지, 화면을 열 때 지울 꼬리표가 맞는지 본다. */
class NotificationTagsTest {

    private fun tag(vararg data: Pair<String, String>) = NotificationTags.of(payloadOf(mapOf(*data))!!)

    @Test
    fun 명세_꼬리표를_단다() {
        assertEquals("friend", tag("type" to "FRIEND_REQUEST", "requestId" to "15", "nickname" to "민수"))
        assertEquals("cards", tag("type" to "CARDS_CREATED", "recordingId" to "12", "cardCount" to "3"))
        assertEquals("comic:35", tag("type" to "COMIC_DONE", "comicId" to "35", "cardId" to "4"))
        assertEquals("comic:35", tag("type" to "COMIC_FAILED", "comicId" to "35", "cardId" to ""))
        assertEquals("room:7", tag("type" to "ROOM_INVITE", "inviteId" to "3", "roomId" to "7", "roomName" to "과팀플"))
        assertEquals("room:7", tag("type" to "ROOM_COMMENT", "roomId" to "7", "postId" to "9", "nickname" to "서연"))
    }

    @Test
    fun 명세_표에_없는_푸시는_가까운_기능_꼬리표를_단다() {
        assertEquals("video:8", tag("type" to "VIDEO_DONE", "videoId" to "8", "cardId" to "4"))
        assertEquals("video:8", tag("type" to "VIDEO_FAILED", "videoId" to "8", "cardId" to "4"))
        assertEquals("character", tag("type" to "PROFILE_CHARACTER_READY"))
        assertEquals("character", tag("type" to "PROFILE_CHARACTER_FAILED"))
        // 해산 푸시는 서버가 roomId를 안 보내요
        assertEquals("room", tag("type" to "ROOM_DISBANDED", "roomName" to "과팀플"))
        assertEquals("comic", NotificationTags.of(PushPayload(PushType.COMIC_DONE)))
    }

    @Test
    fun 점을_읽음으로_하면_그_기능_알림만_지운다() {
        assertTrue(NotificationTags.belongsTo(BadgeKind.CARDS, "cards"))
        assertTrue(NotificationTags.belongsTo(BadgeKind.COMICS, "comic:35"))
        assertTrue(NotificationTags.belongsTo(BadgeKind.COMICS, "video:8"))
        assertTrue(NotificationTags.belongsTo(BadgeKind.COMICS, "comic"))
        assertTrue(NotificationTags.belongsTo(BadgeKind.FRIENDS, "friend"))
        assertTrue(NotificationTags.belongsTo(BadgeKind.PROFILE_CHARACTER, "character"))
        assertTrue(NotificationTags.belongsTo(BadgeKind.ROOMS, "room:7"))
        assertTrue(NotificationTags.belongsTo(BadgeKind.ROOMS, "room"))

        assertFalse(NotificationTags.belongsTo(BadgeKind.CARDS, "comic:35"))
        assertFalse(NotificationTags.belongsTo(BadgeKind.COMICS, "comics"))
        assertFalse(NotificationTags.belongsTo(BadgeKind.ROOMS, "roomy"))
        assertFalse(NotificationTags.belongsTo(BadgeKind.FRIENDS, "room:7"))
        // 종 버튼은 알림을 하나씩 읽어서 한꺼번에 지우지 않아요
        assertFalse(NotificationTags.belongsTo(BadgeKind.INBOX, "friend"))
    }
}
