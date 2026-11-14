package com.lolog.app.ui

import com.lolog.app.data.model.VOICE_CLONE_SENTENCE
import com.lolog.app.data.model.VoiceCloneStatus
import com.lolog.app.ui.screens.friends.MyVoiceRowKind
import com.lolog.app.ui.screens.friends.VOICE_CLONE_MAKING
import com.lolog.app.ui.screens.friends.VOICE_SAMPLE_MAX_MS
import com.lolog.app.ui.screens.friends.VOICE_SAMPLE_MIN_MS
import com.lolog.app.ui.screens.friends.introReady
import com.lolog.app.ui.screens.friends.longEnough
import com.lolog.app.ui.screens.friends.myVoiceRow
import com.lolog.app.ui.screens.friends.shareNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 내 목소리 만들기 판정(시안 2판, 기준 LOLog-docs PR #70 병합 전) */
class VoiceCloneTest {
    @Test
    fun 상태에_따라_내_목소리_칸_모양이_정해진다() {
        // 만든 적 없거나 서버가 없으면(null) 만들기 칸
        assertEquals(MyVoiceRowKind.MAKE, myVoiceRow(null, recording = false))
        assertEquals(MyVoiceRowKind.FAILED, myVoiceRow(VoiceCloneStatus.FAILED, recording = false))
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
    fun 다_된_줄의_작은_글은_친구_쇼츠_동의에_따른다() {
        assertEquals("친구 쇼츠에도 나와요", shareNote(true))
        assertEquals("친구 쇼츠엔 기본 목소리", shareNote(false))
    }

    @Test
    fun 필수_동의를_체크해야_녹음하기가_켜진다() {
        assertTrue(introReady(agreeKeep = true))
        assertFalse(introReady(agreeKeep = false))
    }

    @Test
    fun 오초보다_짧은_녹음은_다시_읽게_한다() {
        assertEquals(5_000L, VOICE_SAMPLE_MIN_MS)
        assertFalse(longEnough(VOICE_SAMPLE_MIN_MS - 1))
        assertTrue(longEnough(VOICE_SAMPLE_MIN_MS))
        // 저절로 끝나는 길이(명세 5~10초 권장)도 통과해요
        assertTrue(longEnough(VOICE_SAMPLE_MAX_MS))
    }

    @Test
    fun 읽을_문장은_하나이고_만드는_중_문구에_시간을_적지_않는다() {
        // 서버·ai의 RECORD_SENTENCE와 글자가 같아야 해요(띄어쓰기·문장부호까지)
        assertEquals("오늘 친구랑 카페에 갔는데, 주문을 잘못해서 둘이 한참 웃었어요. 그래도 덕분에 즐거운 하루였어요.", VOICE_CLONE_SENTENCE)
        assertFalse(VOICE_CLONE_MAKING.contains("분"))
    }
}
