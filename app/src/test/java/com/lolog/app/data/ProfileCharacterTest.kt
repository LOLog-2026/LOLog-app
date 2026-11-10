package com.lolog.app.data

import com.lolog.app.data.mock.MockFriends
import com.lolog.app.data.model.ErrorCodes
import com.lolog.app.data.model.ProfileCharacterStatus
import com.lolog.app.data.remote.ApiMapper.toModel
import com.lolog.app.data.remote.LologJson
import com.lolog.app.data.remote.ProfileResponse
import com.lolog.app.data.repo.LologException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 프로필 캐릭터 만들기(characters.md 3장)와 내 회원 ID */
class ProfileCharacterTest {
    private fun code(block: () -> Unit): String = try { block(); "" } catch (e: LologException) { e.code }

    @Test
    fun 프로필의_userId를_읽어_내_캐릭터에_쓴다() {
        val p = LologJson.decodeFromString(
            ProfileResponse.serializer(),
            """{"userId":42,"nickname":"준","friendCode":"A7K3QX2M","character":{"status":"PROCESSING","thumbnailUrl":"u","updatedAt":"t"}}""",
        ).toModel()
        assertEquals("42", p.userId)
        assertEquals(ProfileCharacterStatus.PROCESSING, p.character?.status)
        assertEquals("u", p.character?.thumbnailUrl)
        // 예전 서버(userId 없음)면 null
        assertNull(LologJson.decodeFromString(ProfileResponse.serializer(), """{"nickname":"준"}""").toModel().userId)
    }

    @Test
    fun 만드는_중에는_또_못_올리고_다시_만드는_동안_예전_그림을_쓴다() {
        val m = MockFriends()
        assertEquals(ProfileCharacterStatus.PROCESSING, m.uploadCharacter(1000).status)
        assertEquals(ErrorCodes.CHARACTER_IN_PROGRESS, code { m.uploadCharacter(1000) })
        assertTrue(m.finishCharacter(failed = false, thumbnailUrl = "old"))
        val again = m.uploadCharacter(1000)
        assertEquals(ProfileCharacterStatus.PROCESSING, again.status)
        assertEquals("old", again.thumbnailUrl)
    }

    @Test
    fun 하루_3번까지고_실패는_세지_않는다() {
        var now = 0L
        val m = MockFriends(nowMs = { now })
        m.uploadCharacter(1); m.finishCharacter(failed = true, thumbnailUrl = "x") // 실패: 안 셈
        repeat(3) { m.uploadCharacter(1); m.finishCharacter(failed = false, thumbnailUrl = "x") }
        assertEquals(ErrorCodes.CHARACTER_LIMIT, code { m.uploadCharacter(1) })
        now += MockFriends.DAY_MS
        assertEquals(ProfileCharacterStatus.PROCESSING, m.uploadCharacter(1).status)
    }

    @Test
    fun 실패하면_FAILED이고_예전_그림은_남는다_지우면_없다() {
        val m = MockFriends()
        m.uploadCharacter(1); m.finishCharacter(failed = false, thumbnailUrl = "old")
        m.uploadCharacter(1); m.finishCharacter(failed = true, thumbnailUrl = "new")
        assertEquals(ProfileCharacterStatus.FAILED, m.profile().character?.status)
        assertEquals("old", m.profile().character?.thumbnailUrl)
        assertEquals(ErrorCodes.PAYLOAD_TOO_LARGE, code { m.uploadCharacter(MockFriends.PHOTO_MAX_BYTES + 1) })
        m.deleteCharacter()
        assertNull(m.profile().character)
    }
}
