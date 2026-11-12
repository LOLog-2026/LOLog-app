package com.lolog.app.notification

import com.lolog.app.data.model.PushType
import com.lolog.app.notification.Notifier.Companion.payloadOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 서버가 보내는 FCM data(api-dto 3장, 값은 모두 문자열)를 그대로 읽는지 본다. */
class PushPayloadTest {

    @Test
    fun 새_카드_푸시를_읽는다() {
        val p = payloadOf(mapOf("type" to "CARDS_CREATED", "recordingId" to "12", "cardCount" to "3"))!!
        assertEquals(PushType.CARDS_CREATED, p.type)
        assertEquals(12L, p.recordingId)
        assertEquals(3, p.cardCount)
    }

    @Test
    fun 카드를_지운_만화_푸시는_cardId가_비어_있다() {
        val p = payloadOf(mapOf("type" to "COMIC_DONE", "comicId" to "35", "cardId" to ""))!!
        assertEquals(35L, p.comicId)
        assertNull(p.cardId)
    }

    @Test
    fun 친구_요청_푸시는_요청_ID와_닉네임을_읽는다() {
        val p = payloadOf(mapOf("type" to "FRIEND_REQUEST", "requestId" to "15", "nickname" to "민수"))!!
        assertEquals(PushType.FRIEND_REQUEST, p.type)
        assertEquals(15L, p.requestId)
        assertEquals("민수", p.nickname)
        assertNull(payloadOf(mapOf("type" to "FRIEND_REQUEST", "requestId" to "15", "nickname" to ""))!!.nickname)
    }

    @Test
    fun 방_푸시는_방_ID와_이름과_닉네임을_읽는다() {
        val invite = payloadOf(mapOf("type" to "ROOM_INVITE", "inviteId" to "3", "roomId" to "7", "roomName" to "과팀플"))!!
        assertEquals(PushType.ROOM_INVITE, invite.type)
        assertEquals(7L, invite.roomId)
        assertEquals("과팀플", invite.roomName)
        val comment = payloadOf(mapOf("type" to "ROOM_COMMENT", "roomId" to "7", "postId" to "9", "nickname" to "서연"))!!
        assertEquals(PushType.ROOM_COMMENT, comment.type)
        assertEquals(7L, comment.roomId)
        assertEquals("서연", comment.nickname)
        val disbanded = payloadOf(mapOf("type" to "ROOM_DISBANDED", "roomName" to "과팀플"))!!
        assertEquals(PushType.ROOM_DISBANDED, disbanded.type)
        assertNull(disbanded.roomId)
        assertEquals("과팀플", disbanded.roomName)
    }

    @Test
    fun 모르는_type은_무시한다() {
        assertNull(payloadOf(mapOf("type" to "SOMETHING_NEW")))
        assertNull(payloadOf(mapOf("comicId" to "1")))
    }
}
