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
    fun 모르는_type은_무시한다() {
        assertNull(payloadOf(mapOf("type" to "SOMETHING_NEW")))
        assertNull(payloadOf(mapOf("comicId" to "1")))
    }
}
