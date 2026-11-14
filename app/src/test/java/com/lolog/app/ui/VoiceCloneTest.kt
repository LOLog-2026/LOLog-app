package com.lolog.app.ui

import com.lolog.app.data.model.VoiceCloneStatus
import com.lolog.app.ui.screens.friends.MyVoiceRowKind
import com.lolog.app.ui.screens.friends.TakeCheck
import com.lolog.app.ui.screens.friends.VOICE_SAMPLE_QUIET_AMPLITUDE
import com.lolog.app.ui.screens.friends.checkTake
import com.lolog.app.ui.screens.friends.introReady
import com.lolog.app.ui.screens.friends.myVoiceRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 내 목소리 만들기 판정(시안 11/13) */
class VoiceCloneTest {
    @Test
    fun 상태에_따라_내_목소리_칸_모양이_정해진다() {
        // 만든 적 없거나 서버가 없으면(null) 만들기 칸
        assertEquals(MyVoiceRowKind.MAKE, myVoiceRow(null, recording = false))
        assertEquals(MyVoiceRowKind.MAKE, myVoiceRow(VoiceCloneStatus.FAILED, recording = false))
        assertEquals(MyVoiceRowKind.MAKING, myVoiceRow(VoiceCloneStatus.PROCESSING, recording = false))
        assertEquals(MyVoiceRowKind.READY, myVoiceRow(VoiceCloneStatus.READY, recording = false))
    }

    @Test
    fun 대화_녹음_중이면_만들기_칸만_꺼진다() {
        assertEquals(MyVoiceRowKind.DISABLED, myVoiceRow(null, recording = true))
        assertEquals(MyVoiceRowKind.DISABLED, myVoiceRow(VoiceCloneStatus.FAILED, recording = true))
        assertEquals(MyVoiceRowKind.MAKING, myVoiceRow(VoiceCloneStatus.PROCESSING, recording = true))
        assertEquals(MyVoiceRowKind.READY, myVoiceRow(VoiceCloneStatus.READY, recording = true))
    }

    @Test
    fun 필수_동의_둘을_모두_체크해야_녹음하기가_켜진다() {
        assertTrue(introReady(ownVoiceOnly = true, agreeShorts = true, shareWithFriends = false))
        assertTrue(introReady(ownVoiceOnly = true, agreeShorts = true, shareWithFriends = true))
        assertFalse(introReady(ownVoiceOnly = true, agreeShorts = false, shareWithFriends = false))
        assertFalse(introReady(ownVoiceOnly = false, agreeShorts = false, shareWithFriends = true))
    }

    @Test
    fun 짧거나_소리가_작은_녹음은_다시_읽게_한다() {
        val loud = VOICE_SAMPLE_QUIET_AMPLITUDE * 4
        assertEquals(TakeCheck.TOO_SHORT, checkTake(500, loud))
        assertEquals(TakeCheck.TOO_QUIET, checkTake(3_000, VOICE_SAMPLE_QUIET_AMPLITUDE - 1))
        assertEquals(TakeCheck.OK, checkTake(3_000, loud))
        // 짧으면서 작으면 짧은 것을 먼저 알려요
        assertEquals(TakeCheck.TOO_SHORT, checkTake(500, 0))
    }
}
