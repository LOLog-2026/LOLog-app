package com.lolog.app.data

import com.lolog.app.data.mock.MockCharacters
import com.lolog.app.data.mock.MockData
import com.lolog.app.data.mock.MockFriends
import com.lolog.app.data.model.CardEdit
import com.lolog.app.data.model.CharacterType
import com.lolog.app.data.model.ErrorCodes
import com.lolog.app.data.model.Speaker
import com.lolog.app.data.model.SpeakerCharacter
import com.lolog.app.data.remote.ApiMapper
import com.lolog.app.data.repo.LologException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 화자 그림(LOLog-docs spec/characters.md 4장): PATCH 본문 모양과 목업 규칙을 본다. */
class SpeakerCharacterTest {

    private fun body(edit: CardEdit): List<JsonObject> =
        with(ApiMapper) { edit.patchBody() }["speakers"]!!.jsonArray.map { it.jsonObject }

    private val young = SpeakerCharacter(CharacterType.DEFAULT, "young-f-02")

    // ── patchBody ──

    @Test
    fun 그림을_붙이면_type과_id만_보내고_이름은_보내지_않는다() {
        val sp = body(CardEdit(characters = mapOf("화자2" to young.copy(label = "보내지 않음")))).single()
        assertEquals(setOf("key", "character"), sp.keys)
        val c = sp["character"]!!.jsonObject
        assertEquals(setOf("type", "id"), c.keys)
        assertEquals("DEFAULT", c["type"]!!.jsonPrimitive.content)
        assertEquals("young-f-02", c["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun 그림을_떼면_character_null을_꼭_보낸다() {
        val sp = body(CardEdit(characters = mapOf("화자1" to null))).single()
        assertEquals(setOf("key", "character"), sp.keys)
        assertEquals(JsonNull, sp["character"])
    }

    @Test
    fun 이름만_바꾸면_character를_보내지_않는다() {
        val sp = body(CardEdit(speakers = listOf(Speaker("화자1", "민수", young)))).single()
        assertEquals(setOf("key", "name"), sp.keys)
        assertEquals("민수", sp["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun 이름과_그림을_같이_바꾸면_한_화자에_합친다() {
        val list = body(
            CardEdit(
                speakers = listOf(Speaker("화자1", "서연")),
                characters = mapOf("화자1" to SpeakerCharacter(CharacterType.PROFILE, "12"), "화자2" to null),
            ),
        )
        assertEquals(listOf("화자1", "화자2"), list.map { it["key"]!!.jsonPrimitive.content })
        assertEquals(setOf("key", "name", "character"), list[0].keys)
        assertEquals("PROFILE", list[0]["character"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(setOf("key", "character"), list[1].keys)
    }

    @Test
    fun 응답의_모르는_그림_종류는_그림_없음으로_본다() {
        val sp = with(ApiMapper) {
            com.lolog.app.data.remote.SpeakerDto("A", "민지", com.lolog.app.data.remote.SpeakerCharacterDto("ALIEN", "x")).toModel()
        }
        assertNull(sp.character)
    }

    // ── 목업 규칙 ──

    private val friends = MockFriends()
    /** 카드 12: 화자 A(민지)·B(준호)·C */
    private val card12 = MockData.cards().first { it.id == 12L }.speakers

    private fun apply(speakers: List<Speaker>, edit: CardEdit) =
        MockCharacters.applySpeakers(speakers, edit, friends.friends(), friends.profile())

    private fun attach(vararg pics: Pair<String, SpeakerCharacter?>): String? = try {
        apply(card12, CardEdit(characters = mapOf(*pics)))
        null
    } catch (e: LologException) {
        e.code
    }

    @Test
    fun 같은_그림을_두_화자에_붙이면_DUPLICATE_CHARACTER() {
        assertEquals(ErrorCodes.DUPLICATE_CHARACTER, attach("A" to young, "B" to young))
        // 친구 프로필도 같은 사람이면 같은 그림
        val seoyeon = SpeakerCharacter(CharacterType.PROFILE, "12")
        assertEquals(ErrorCodes.DUPLICATE_CHARACTER, attach("A" to seoyeon, "C" to seoyeon))
    }

    @Test
    fun 그림_없는_친구와_나는_CHARACTER_NOT_READY() {
        assertEquals(ErrorCodes.CHARACTER_NOT_READY, attach("A" to SpeakerCharacter(CharacterType.PROFILE, "14"))) // 도윤
        assertEquals(ErrorCodes.CHARACTER_NOT_READY, attach("A" to SpeakerCharacter(CharacterType.PROFILE, "1"))) // 나
    }

    @Test
    fun 없는_기본_화자와_친구가_아닌_회원은_UNKNOWN_CHARACTER() {
        assertEquals(ErrorCodes.UNKNOWN_CHARACTER, attach("A" to SpeakerCharacter(CharacterType.DEFAULT, "young-f-99")))
        assertEquals(ErrorCodes.UNKNOWN_CHARACTER, attach("A" to SpeakerCharacter(CharacterType.PROFILE, "31"))) // 차단한 태오
    }

    @Test
    fun 붙이면_label과_그림을_채워_돌려주고_이름은_그대로다() {
        val card = apply(card12, CardEdit(characters = mapOf("A" to young, "B" to SpeakerCharacter(CharacterType.PROFILE, "12"))))
        val a = card.first { it.key == "A" }
        assertEquals("민지", a.name)
        assertEquals("금테 안경 단발 청년 여성", a.character?.label)
        assertNotNull(a.character?.thumbnailUrl)
        assertEquals("서연", card.first { it.key == "B" }.character?.label)

        // 떼면 null, 이름만 바꾸면 그림은 그대로
        val after = apply(card, CardEdit(speakers = listOf(Speaker("A", "지민")), characters = mapOf("B" to null)))
        assertEquals("지민", after.first { it.key == "A" }.name)
        assertEquals("young-f-02", after.first { it.key == "A" }.character?.id)
        assertNull(after.first { it.key == "B" }.character)
    }
}
