package com.lolog.app.data

import com.lolog.app.data.model.CardDetail
import com.lolog.app.data.model.CardKind
import com.lolog.app.data.model.PushType
import com.lolog.app.data.model.VIDEO_LIMIT_PER_CARD
import com.lolog.app.data.model.VideoRef
import com.lolog.app.data.model.VideoStatus
import com.lolog.app.data.remote.ApiMapper.toModel
import com.lolog.app.data.remote.CardDetailDto
import com.lolog.app.data.remote.LologJson
import com.lolog.app.data.remote.VideoAcceptedDto
import com.lolog.app.data.remote.VideoPageDto
import com.lolog.app.notification.Notifier.Companion.payloadOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 쇼츠(카드 영상, LOLog-docs video.md 2장)를 서버 모양대로 읽고 카드의 남은 횟수·잠금을 맞게 세는지 본다. */
class VideoTest {

    @Test
    fun 영상_목록을_읽고_DONE이_아니면_주소가_없다() {
        val page = LologJson.decodeFromString(
            VideoPageDto.serializer(),
            """{"videos":[
              {"id":7,"cardId":12,"cardSummary":"치킨 주문","status":"DONE","videoUrl":"v","thumbnailUrl":"t","durationMs":28400,"createdAt":"2026-11-12T00:00:00Z"},
              {"id":6,"cardId":null,"cardSummary":"지운 카드","status":"VOICE","createdAt":"2026-11-12T00:00:00Z"},
              {"id":5,"status":"SOMETHING_NEW","createdAt":"c"}],"nextCursor":"5"}""",
        ).toModel()
        assertEquals("5", page.nextCursor)
        val done = page.videos[0]
        assertEquals(VideoStatus.DONE, done.status)
        assertEquals("v", done.videoUrl)
        assertEquals(28_400L, done.durationMs)
        assertEquals("치킨 주문", done.cardSummary)
        assertNull(page.videos[1].cardId)
        assertNull(page.videos[1].videoUrl)
        assertEquals(VideoStatus.UNKNOWN, page.videos[2].status)
        assertEquals("", page.videos[2].cardSummary)
    }

    @Test
    fun 영상_요청_응답과_카드_자세히의_videos를_읽는다() {
        val accepted = LologJson.decodeFromString(VideoAcceptedDto.serializer(), """{"videoId":9,"status":"QUEUED"}""").toModel()
        assertEquals(9L, accepted.videoId)
        assertEquals(VideoStatus.QUEUED, accepted.status)
        val card = LologJson.decodeFromString(
            CardDetailDto.serializer(),
            """{"id":1,"kind":"웃김","summary":"s","createdAt":"c","videos":[{"id":9,"status":"VOICE"},{"id":8,"status":"FAILED"}]}""",
        ).toModel()
        assertEquals(listOf(VideoRef(9, VideoStatus.VOICE), VideoRef(8, VideoStatus.FAILED)), card.videos)
    }

    private fun card(vararg videos: VideoStatus) =
        CardDetail(id = 1, kind = CardKind.FUNNY, summary = "s", createdAt = "c", videos = videos.mapIndexed { i, s -> VideoRef(i.toLong(), s) })

    @Test
    fun 실패는_횟수에_안_세고_2개면_다_쓴다() {
        assertEquals(VIDEO_LIMIT_PER_CARD, card().videoRemaining)
        assertEquals(VIDEO_LIMIT_PER_CARD, card(VideoStatus.FAILED).videoRemaining)
        assertEquals(1, card(VideoStatus.DONE, VideoStatus.FAILED).videoRemaining)
        assertEquals(0, card(VideoStatus.DONE, VideoStatus.DRAWING).videoRemaining)
    }

    @Test
    fun 카드는_대기_목소리_때만_잠기고_그림_단계는_안_잠긴다() {
        assertTrue(card(VideoStatus.QUEUED).videoLocked)
        assertTrue(card(VideoStatus.VOICE).videoLocked)
        assertFalse(card(VideoStatus.DRAWING).videoLocked)
        assertTrue(card(VideoStatus.DRAWING).videoInProgress)
        assertFalse(card(VideoStatus.DONE, VideoStatus.FAILED).videoInProgress)
    }

    @Test
    fun 영상_푸시는_videoId와_cardId를_읽고_카드를_지웠으면_cardId가_없다() {
        val done = payloadOf(mapOf("type" to "VIDEO_DONE", "videoId" to "7", "cardId" to "12"))!!
        assertEquals(PushType.VIDEO_DONE, done.type)
        assertEquals(7L, done.videoId)
        assertEquals(12L, done.cardId)
        val failed = payloadOf(mapOf("type" to "VIDEO_FAILED", "videoId" to "7", "cardId" to ""))!!
        assertEquals(PushType.VIDEO_FAILED, failed.type)
        assertNull(failed.cardId)
    }
}
