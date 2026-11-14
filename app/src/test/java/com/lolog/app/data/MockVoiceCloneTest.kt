package com.lolog.app.data

import com.lolog.app.data.mock.MockFriends
import com.lolog.app.data.mock.MockVoiceClone
import com.lolog.app.data.model.ErrorCodes
import com.lolog.app.data.model.MY_VOICE_ID
import com.lolog.app.data.model.VoiceCloneStatus
import com.lolog.app.data.repo.LologException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 내 목소리 목업(LOLog-docs PR #70 병합 전) */
class MockVoiceCloneTest {
    private var now = 0L
    private val clone = MockVoiceClone { now }

    private fun code(block: () -> Unit): String = try { block(); "" } catch (e: LologException) { e.code }
    private fun upload(bytes: Long = 100_000, share: Boolean = false) = clone.upload(bytes, "읽은 문장", share)
    private fun friendsWithCharacter() = MockFriends().apply {
        uploadCharacter(1); finishCharacter(failed = false, thumbnailUrl = "x")
    }

    @Test
    fun 처음엔_없고_올리면_만드는_중이다가_시간이_지나면_다_된다() {
        assertNull(clone.get())
        upload(share = true)
        assertEquals(VoiceCloneStatus.PROCESSING, clone.get()?.status)
        assertNull(clone.get()?.previewUrl)
        assertTrue(clone.get()!!.shareWithFriends)
        now += MockVoiceClone.MAKING_MS - 1
        assertEquals(VoiceCloneStatus.PROCESSING, clone.get()?.status)
        now += 1
        assertEquals(VoiceCloneStatus.READY, clone.get()?.status)
        assertNotNull(clone.get()?.previewUrl)
    }

    @Test
    fun 만드는_중에_또_올리면_409() {
        upload()
        assertEquals(ErrorCodes.VOICE_CLONE_IN_PROGRESS, code { upload() })
    }

    @Test
    fun 다_된_뒤_다시_올리면_만드는_중으로_바뀌고_미리_듣기는_새로_만든다() {
        upload(); now += MockVoiceClone.MAKING_MS
        upload()
        assertEquals(VoiceCloneStatus.PROCESSING, clone.get()?.status)
        assertNull(clone.get()?.previewUrl)
    }

    @Test
    fun 목업_전용_짧은_녹음은_실패한다() {
        upload(bytes = MockVoiceClone.FAIL_BELOW_BYTES - 1)
        now += MockVoiceClone.MAKING_MS
        assertEquals(VoiceCloneStatus.FAILED, clone.get()?.status)
        assertNotNull(clone.get()?.failReason)
        assertFalse(clone.ready())
    }

    @Test
    fun 너무_큰_녹음과_빈_문장은_거절한다() {
        assertEquals(ErrorCodes.PAYLOAD_TOO_LARGE, code { upload(bytes = MockVoiceClone.MAX_BYTES + 1) })
        assertEquals(ErrorCodes.INVALID_REQUEST, code { clone.upload(100_000, " ", false) })
    }

    @Test
    fun 친구_쇼츠_동의만_바꿀_수_있고_없으면_404() {
        assertEquals(ErrorCodes.VOICE_CLONE_NOT_FOUND, code { clone.setShare(true) })
        upload(share = false); now += MockVoiceClone.MAKING_MS
        clone.setShare(true)
        assertTrue(clone.get()!!.shareWithFriends)
        assertEquals(VoiceCloneStatus.READY, clone.get()?.status)
    }

    @Test
    fun 다_되면_self를_고를_수_있고_지우면_null이고_voice도_null() {
        val friends = friendsWithCharacter()
        // 다 되기 전에는 고를 수 없어요
        assertEquals(ErrorCodes.VOICE_CLONE_NOT_READY, code { friends.setVoice(MY_VOICE_ID, myVoiceReady = clone.ready()) })
        upload(); now += MockVoiceClone.MAKING_MS
        friends.setVoice(MY_VOICE_ID, myVoiceReady = clone.ready())
        assertEquals("self", friends.profile().character?.voice)

        // MockLologRepository.deleteVoiceClone과 같은 순서
        clone.delete(); friends.clearMyVoice()
        assertNull(clone.get())
        assertNull(friends.profile().character?.voice)
    }

    @Test
    fun 다시_만들다_실패하면_골랐던_self는_null() {
        val friends = friendsWithCharacter()
        upload(); now += MockVoiceClone.MAKING_MS
        friends.setVoice(MY_VOICE_ID, myVoiceReady = clone.ready())
        upload(bytes = 1_000); now += MockVoiceClone.MAKING_MS
        // MockLologRepository.currentVoiceClone과 같은 순서
        if (clone.get()?.status == VoiceCloneStatus.FAILED) friends.clearMyVoice()
        assertNull(friends.profile().character?.voice)
    }

    @Test
    fun 지워도_기본_목소리를_골랐으면_그대로() {
        val friends = friendsWithCharacter()
        friends.setVoice("young-f-02")
        clone.delete(); friends.clearMyVoice()
        assertEquals("young-f-02", friends.profile().character?.voice)
    }
}
