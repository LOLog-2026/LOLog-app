package com.lolog.app.ui

import com.lolog.app.data.model.CharacterType
import com.lolog.app.data.model.Speaker
import com.lolog.app.data.model.SpeakerCharacter
import com.lolog.app.data.remote.ApiMapper
import com.lolog.app.data.remote.ProfileCharacterDto
import com.lolog.app.data.remote.ProfileResponse
import com.lolog.app.ui.screens.library.SpeakerPick
import com.lolog.app.ui.screens.library.editFor
import com.lolog.app.ui.screens.library.peopleOf
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.lolog.app.ui.screens.library.speakerSavedText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 화자 고치기 팝업 결과 → PATCH 내용(바뀐 것만)과 성공 띠 문구 */
class SpeakerPickTest {
    private val young = SpeakerCharacter(CharacterType.DEFAULT, "young-f-02", "금테 안경 단발 청년 여성")
    private val seoyeon = SpeakerCharacter(CharacterType.PROFILE, "12", "서연")

    @Test
    fun 아무것도_안_바꾸고_닫으면_보내지_않는다() {
        assertNull(Speaker("A", "민지", young).editFor(SpeakerPick("민지", null, characterChanged = false)))
        // 같은 그림을 다시 골라도 안 바뀐 것
        assertNull(Speaker("A", "민지", young).editFor(SpeakerPick("민지", young.copy(label = null), characterChanged = true)))
    }

    @Test
    fun 친구를_고르면_이름과_그림을_같이_보내고_띠는_친구_이름() {
        val edit = Speaker("A", null).editFor(SpeakerPick("서연", seoyeon, characterChanged = true))!!
        assertEquals(listOf(Speaker("A", "서연")), edit.speakers)
        assertEquals(mapOf("A" to seoyeon), edit.characters)
        assertEquals("서연님 캐릭터를 붙였어요", speakerSavedText(edit))
    }

    @Test
    fun 기본_화자와_떼기의_띠() {
        assertEquals("그림을 붙였어요", speakerSavedText(Speaker("A", "민지").editFor(SpeakerPick("민지", young, true))!!))
        val off = Speaker("A", "민지", young).editFor(SpeakerPick("민지", null, true))!!
        assertNull(off.speakers)
        assertEquals(mapOf("A" to null), off.characters)
        assertEquals("그림을 뗐어요", speakerSavedText(off))
    }

    @Test
    fun 나를_고르면_내_프로필_userId로_PROFILE을_보낸다() {
        // docs #56: GET /api/me/profile의 userId(Long)를 CharacterRef id(문자열)로 써요
        val me = with(ApiMapper) {
            ProfileResponse(userId = 42, nickname = "지원", character = ProfileCharacterDto("READY", "https://x/me.png", "2026-11-11T10:00:00+09:00")).toModel()
        }
        val pick = peopleOf(me, emptyList()).first { it.isMe }.character!!
        val edit = Speaker("화자1").editFor(SpeakerPick("지원", pick, characterChanged = true))!!
        val c = with(ApiMapper) { edit.patchBody() }["speakers"]!!.jsonArray.single().jsonObject["character"]!!.jsonObject
        assertEquals("PROFILE", c["type"]!!.jsonPrimitive.content)
        assertEquals("42", c["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun userId를_안_주는_서버면_나에게_그림을_붙이지_않는다() {
        val me = with(ApiMapper) { ProfileResponse(nickname = "지원", character = ProfileCharacterDto("READY", "https://x/me.png")).toModel() }
        assertNull(peopleOf(me, emptyList()).first { it.isMe }.character)
    }
}
