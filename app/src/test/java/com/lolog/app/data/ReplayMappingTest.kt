package com.lolog.app.data

import com.lolog.app.data.model.AudioStatus
import com.lolog.app.data.remote.ApiMapper.toModel
import com.lolog.app.data.remote.CardDetailDto
import com.lolog.app.data.remote.CardPageDto
import com.lolog.app.data.remote.LologJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 순간 다시 듣기(replay.md 4장): CardDetail.audio, CardSummary.hasAudio 읽기 */
class ReplayMappingTest {
    private fun detail(audio: String?) = LologJson.decodeFromString(
        CardDetailDto.serializer(),
        """{"id":1,"summary":"s","createdAt":"2026-11-10T12:00:00+09:00"${audio?.let { ",\"audio\":$it" } ?: ""}}""",
    ).toModel()

    @Test
    fun READY면_주소와_길이를_쓴다() {
        val a = detail("""{"status":"READY","url":"https://x/a.aac?sig=1","durationMs":12345}""").audio
        assertEquals(AudioStatus.READY, a.status)
        assertEquals("https://x/a.aac?sig=1", a.url)
        assertEquals(12345L, a.durationMs)
    }

    @Test
    fun PENDING과_NONE은_주소를_쓰지_않는다() {
        assertEquals(AudioStatus.PENDING, detail("""{"status":"PENDING","url":null,"durationMs":null}""").audio.status)
        val none = detail("""{"status":"NONE"}""").audio
        assertEquals(AudioStatus.NONE, none.status)
        assertNull(none.url)
    }

    @Test
    fun 예전_서버나_모르는_값이나_주소_없는_READY는_NONE() {
        assertEquals(AudioStatus.NONE, detail(null).audio.status)
        assertEquals(AudioStatus.NONE, detail("""{"status":"WHAT"}""").audio.status)
        assertEquals(AudioStatus.NONE, detail("""{"status":"READY","url":null,"durationMs":100}""").audio.status)
        assertEquals(AudioStatus.NONE, detail("""{"status":"READY","url":"u","durationMs":0}""").audio.status)
    }

    @Test
    fun 목록의_hasAudio를_읽고_없으면_false() {
        val page = LologJson.decodeFromString(
            CardPageDto.serializer(),
            """{"cards":[{"id":1,"hasAudio":true,"createdAt":"t"},{"id":2,"createdAt":"t"}],"nextCursor":null}""",
        ).toModel()
        assertTrue(page.cards[0].hasAudio)
        assertFalse(page.cards[1].hasAudio)
    }
}
