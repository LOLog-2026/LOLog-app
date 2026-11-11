package com.lolog.app.data

import com.lolog.app.data.mock.MockCharacters
import com.lolog.app.data.mock.MockFriends
import com.lolog.app.data.model.ErrorCodes
import com.lolog.app.data.remote.ApiMapper
import com.lolog.app.data.remote.ApiMapper.toModel
import com.lolog.app.data.remote.DefaultCharacterListDto
import com.lolog.app.data.remote.LologJson
import com.lolog.app.data.remote.ProfileResponse
import com.lolog.app.data.repo.LologException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 기본 화자 목소리(FR-CHAR-03)와 프로필 캐릭터 목소리(FR-CHAR-04). docs PR #59 초안 */
class CharacterVoiceTest {
    private fun code(block: () -> Unit): String = try { block(); "" } catch (e: LologException) { e.code }

    @Test
    fun 기본_화자의_voiceUrl을_읽고_없거나_빈칸이면_null() {
        val list = LologJson.decodeFromString(
            DefaultCharacterListDto.serializer(),
            """{"characters":[{"id":"young-f-02","label":"a","voiceUrl":"v"},{"id":"kid-f-01","label":"b"},{"id":"kid-f-02","label":"c","voiceUrl":""}]}""",
        ).characters.map { it.toModel() }
        assertEquals("v", list[0].voiceUrl)
        assertNull(list[1].voiceUrl)
        assertNull(list[2].voiceUrl)
    }

    @Test
    fun 프로필_캐릭터의_voice를_읽는다() {
        val p = LologJson.decodeFromString(
            ProfileResponse.serializer(),
            """{"userId":1,"character":{"status":"READY","thumbnailUrl":"u","updatedAt":"t","voice":"young-f-02"}}""",
        ).toModel()
        assertEquals("young-f-02", p.character?.voice)
        val none = LologJson.decodeFromString(
            ProfileResponse.serializer(), """{"character":{"status":"READY","updatedAt":"t"}}""",
        ).toModel()
        assertNull(none.character?.voice)
    }

    @Test
    fun 저장_본문은_안_고르면_voice_null을_꼭_넣는다() {
        assertEquals("""{"voice":"young-f-02"}""", ApiMapper.voiceBody("young-f-02").toString())
        assertEquals("""{"voice":null}""", ApiMapper.voiceBody(null).toString())
        assertEquals("""{"voice":null}""", ApiMapper.voiceBody(" ").toString())
    }

    @Test
    fun 목업은_기본_화자_일부에만_목소리가_있다() {
        val withVoice = MockCharacters.defaults.filter { it.voiceUrl != null }
        assertTrue(withVoice.isNotEmpty())
        assertTrue(withVoice.size < MockCharacters.defaults.size)
    }

    @Test
    fun 캐릭터가_없으면_404이고_목소리_없는_id는_400() {
        val m = MockFriends()
        assertEquals(ErrorCodes.CHARACTER_NOT_FOUND, code { m.setVoice("young-f-02") })
        m.uploadCharacter(1); m.finishCharacter(failed = false, thumbnailUrl = "x")
        assertEquals(ErrorCodes.UNKNOWN_CHARACTER, code { m.setVoice("nobody") })
        val silent = MockCharacters.defaults.first { it.voiceUrl == null }.id
        assertEquals(ErrorCodes.UNKNOWN_CHARACTER, code { m.setVoice(silent) })
    }

    @Test
    fun 저장하면_voice가_바뀌고_다시_만들어도_남고_지우면_없다() {
        val m = MockFriends()
        m.uploadCharacter(1); m.finishCharacter(failed = false, thumbnailUrl = "x")
        m.setVoice("young-f-02")
        assertEquals("young-f-02", m.profile().character?.voice)
        m.uploadCharacter(1); m.finishCharacter(failed = false, thumbnailUrl = "y")
        assertEquals("young-f-02", m.profile().character?.voice)
        m.setVoice(null)
        assertNull(m.profile().character?.voice)
        m.setVoice("young-f-02")
        m.deleteCharacter()
        assertNull(m.profile().character)
    }
}
