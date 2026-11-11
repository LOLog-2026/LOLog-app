package com.lolog.app.data

import com.lolog.app.data.mock.MockFriends
import com.lolog.app.data.mock.mockEditCard
import com.lolog.app.data.model.CardDetail
import com.lolog.app.data.model.CardEdit
import com.lolog.app.data.model.CardKind
import com.lolog.app.data.model.CharacterType
import com.lolog.app.data.model.ErrorCodes
import com.lolog.app.data.model.Line
import com.lolog.app.data.model.LineEdit
import com.lolog.app.data.model.Speaker
import com.lolog.app.data.model.SpeakerCharacter
import com.lolog.app.data.remote.ApiMapper
import com.lolog.app.data.repo.LologException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 화자 더하기·빼기(LOLog-docs api-dto.md CardUpdateRequest, FR-EDIT-01): PATCH 본문 모양과 목업 규칙을 본다. */
class SpeakerAddRemoveTest {

    private fun body(edit: CardEdit): JsonObject = with(ApiMapper) { edit.patchBody() }
    private fun JsonObject.speakers(): List<JsonObject> = this["speakers"]!!.jsonArray.map { it.jsonObject }

    // ── patchBody ──

    @Test
    fun 이름_없는_새_화자는_key만_보낸다() {
        val b = body(CardEdit(newSpeakers = listOf("화자3"), lines = listOf(LineEdit(12, speaker = "화자3"))))
        val sp = b.speakers().single()
        assertEquals(setOf("key"), sp.keys)
        assertEquals("화자3", sp["key"]!!.jsonPrimitive.content)
        assertEquals("화자3", b["lines"]!!.jsonArray.single().jsonObject["speaker"]!!.jsonPrimitive.content)
    }

    @Test
    fun 새_화자에_이름을_붙이면_한_번만_같이_보낸다() {
        val sp = body(CardEdit(speakers = listOf(Speaker("화자3", "지수")), newSpeakers = listOf("화자3"))).speakers().single()
        assertEquals(setOf("key", "name"), sp.keys)
        assertEquals("지수", sp["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun removeSpeakers를_넣고_빼는_화자는_speakers에_넣지_않는다() {
        val b = body(
            CardEdit(
                speakers = listOf(Speaker("화자1", "민수"), Speaker("화자2", "버림")),
                characters = mapOf("화자2" to SpeakerCharacter(CharacterType.DEFAULT, "young-f-02")),
                lines = listOf(LineEdit(7, speaker = "화자1")),
                removeSpeakers = listOf("화자2"),
            ),
        )
        assertEquals(listOf("화자2"), b["removeSpeakers"]!!.jsonArray.map { it.jsonPrimitive.content })
        // 기존 화자 이름 바꾸기는 그대로
        val sp = b.speakers().single()
        assertEquals("화자1", sp["key"]!!.jsonPrimitive.content)
        assertEquals("민수", sp["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun 빼기만_하면_speakers는_없다() {
        val b = body(CardEdit(removeSpeakers = listOf("화자3")))
        assertNull(b["speakers"])
        assertEquals(setOf("removeSpeakers"), b.keys)
    }

    // ── 목업 규칙 ──

    private val friends = MockFriends()

    /** 화자1·화자2·화자3, 대사 7(화자1)·8(화자2)·9(화자3)·10(화자3) */
    private fun card(speakers: Int = 3) = CardDetail(
        id = 1, kind = CardKind.FUNNY, summary = "테스트", createdAt = "2026-11-11T10:00:00+09:00",
        speakers = (1..speakers).map { Speaker("화자$it") },
        lines = listOf(Line(7, "화자1", "가"), Line(8, "화자2", "나"), Line(9, "화자3", "다"), Line(10, "화자3", "라"))
            .filter { it.speaker.removePrefix("화자").toInt() <= speakers },
    )

    private fun edit(card: CardDetail, patch: CardEdit) = mockEditCard(card, patch, friends.friends(), friends.profile())

    private fun error(card: CardDetail, patch: CardEdit): String? = try {
        edit(card, patch); null
    } catch (e: LologException) {
        e.code
    }

    @Test
    fun 대사_없는_새_화자는_INVALID_REQUEST() {
        assertEquals(ErrorCodes.INVALID_REQUEST, error(card(), CardEdit(newSpeakers = listOf("화자4"))))
    }

    @Test
    fun 다음_번호가_아닌_key는_UNKNOWN_SPEAKER() {
        assertEquals(ErrorCodes.UNKNOWN_SPEAKER, error(card(), CardEdit(newSpeakers = listOf("화자5"), lines = listOf(LineEdit(7, "화자5")))))
        assertEquals(ErrorCodes.UNKNOWN_SPEAKER, error(card(), CardEdit(removeSpeakers = listOf("화자9"))))
    }

    @Test
    fun speakers와_removeSpeakers에_같은_key는_INVALID_REQUEST() {
        val patch = CardEdit(speakers = listOf(Speaker("화자2", "민수")), lines = listOf(LineEdit(8, "화자1")), removeSpeakers = listOf("화자2"))
        assertEquals(ErrorCodes.INVALID_REQUEST, error(card(), patch))
    }

    @Test
    fun 대사가_남은_화자를_빼면_SPEAKER_HAS_LINES() {
        assertEquals(ErrorCodes.SPEAKER_HAS_LINES, error(card(), CardEdit(removeSpeakers = listOf("화자2"))))
        // 대사 둘 중 하나만 옮겨도 남아 있어요
        assertEquals(ErrorCodes.SPEAKER_HAS_LINES, error(card(), CardEdit(lines = listOf(LineEdit(9, "화자1")), removeSpeakers = listOf("화자3"))))
    }

    @Test
    fun 다섯_명이_되면_SPEAKER_LIMIT() {
        val patch = CardEdit(newSpeakers = listOf("화자5"), lines = listOf(LineEdit(7, "화자5")))
        assertEquals(ErrorCodes.SPEAKER_LIMIT, error(card(4), patch))
    }

    @Test
    fun 한_명도_안_남으면_INVALID_REQUEST() {
        val one = card(1).copy(lines = emptyList())
        assertEquals(ErrorCodes.INVALID_REQUEST, error(one, CardEdit(removeSpeakers = listOf("화자1"))))
    }

    @Test
    fun 화자2를_빼면_화자3이_화자2가_되고_대사도_따라간다() {
        val start = card().let { c -> c.copy(speakers = c.speakers.map { if (it.key == "화자3") it.copy(name = "지수") else it }) }
        val after = edit(start, CardEdit(lines = listOf(LineEdit(8, "화자1")), removeSpeakers = listOf("화자2")))
        assertEquals(listOf("화자1", "화자2"), after.speakers.map { it.key })
        assertEquals("지수", after.speakers[1].name)
        assertEquals(listOf("화자1", "화자1", "화자2", "화자2"), after.lines.map { it.speaker })
    }

    @Test
    fun 더하기와_빼기를_같이_하면_더한_뒤_빼고_다시_매긴다() {
        // 화자2의 대사를 새 화자4로 옮기고 화자2를 뺌 → 화자1·화자3·화자4 → 화자1·화자2·화자3
        val after = edit(
            card(),
            CardEdit(speakers = listOf(Speaker("화자4", "지수")), newSpeakers = listOf("화자4"), lines = listOf(LineEdit(8, "화자4")), removeSpeakers = listOf("화자2")),
        )
        assertEquals(listOf("화자1", "화자2", "화자3"), after.speakers.map { it.key })
        assertEquals("지수", after.speakers[2].name)
        assertEquals("화자3", after.lines.first { it.id == 8L }.speaker)
        assertEquals("화자2", after.lines.first { it.id == 9L }.speaker)
    }

    @Test
    fun 더하기만_하면_key를_다시_매기지_않는다() {
        val after = edit(card(2), CardEdit(newSpeakers = listOf("화자3"), lines = listOf(LineEdit(8, "화자3"))))
        assertEquals(listOf("화자1", "화자2", "화자3"), after.speakers.map { it.key })
        assertNull(after.speakers[2].name)
    }
}
