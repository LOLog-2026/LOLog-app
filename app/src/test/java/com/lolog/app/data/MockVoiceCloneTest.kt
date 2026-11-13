package com.lolog.app.data

import com.lolog.app.data.mock.MockFriends
import com.lolog.app.data.mock.MockVoiceClone
import com.lolog.app.data.model.ErrorCodes
import com.lolog.app.data.model.MY_VOICE_ID
import com.lolog.app.data.model.VOICE_CLONE_SENTENCES
import com.lolog.app.data.model.VoiceCloneStatus
import com.lolog.app.data.repo.LologException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 내 목소리 만들기 목업(명세 없음, 목업만) */
class MockVoiceCloneTest {
    private var now = 0L
    private val clone = MockVoiceClone { now }

    private fun code(block: () -> Unit): String = try { block(); "" } catch (e: LologException) { e.code }
    private fun uploadAll() = VOICE_CLONE_SENTENCES.indices.forEach { clone.upload(it, 10_000) }

    @Test
    fun 처음엔_없고_만들기_시작하면_만드는_중이다가_시간이_지나면_다_된다() {
        assertEquals(VoiceCloneStatus.NONE, clone.get().status)
        uploadAll()
        clone.start()
        assertEquals(VoiceCloneStatus.MAKING, clone.get().status)
        assertNull(clone.get().voiceUrl)
        now += MockVoiceClone.MAKING_MS - 1
        assertEquals(VoiceCloneStatus.MAKING, clone.get().status)
        now += 1
        assertEquals(VoiceCloneStatus.DONE, clone.get().status)
        assertNotNull(clone.get().voiceUrl)
    }

    @Test
    fun 문장을_다_올리지_않으면_시작할_수_없다() {
        clone.upload(0, 10_000)
        assertEquals(ErrorCodes.INVALID_REQUEST, code { clone.start() })
    }

    @Test
    fun 아주_작은_파일은_소리가_작다고_한다() {
        assertEquals(ErrorCodes.VOICE_TOO_QUIET, code { clone.upload(0, MockVoiceClone.QUIET_BYTES - 1) })
        assertEquals("", code { clone.upload(0, MockVoiceClone.QUIET_BYTES) })
    }

    @Test
    fun 다_되면_내_목소리를_고를_수_있고_지우면_없음이고_voice는_null() {
        val friends = MockFriends()
        friends.uploadCharacter(1); friends.finishCharacter(failed = false, thumbnailUrl = "x")
        // 다 되기 전에는 고를 수 없어요
        assertEquals(ErrorCodes.UNKNOWN_CHARACTER, code { friends.setVoice(MY_VOICE_ID, myVoiceReady = clone.ready()) })
        uploadAll(); clone.start(); now += MockVoiceClone.MAKING_MS
        friends.setVoice(MY_VOICE_ID, myVoiceReady = clone.ready())
        assertEquals(MY_VOICE_ID, friends.profile().character?.voice)

        // MockLologRepository.deleteVoiceClone과 같은 순서
        clone.delete(); friends.clearMyVoice()
        assertEquals(VoiceCloneStatus.NONE, clone.get().status)
        assertNull(friends.profile().character?.voice)
    }

    @Test
    fun 지워도_기본_목소리를_골랐으면_그대로() {
        val friends = MockFriends()
        friends.uploadCharacter(1); friends.finishCharacter(failed = false, thumbnailUrl = "x")
        friends.setVoice("young-f-02")
        clone.delete(); friends.clearMyVoice()
        assertEquals("young-f-02", friends.profile().character?.voice)
    }
}
