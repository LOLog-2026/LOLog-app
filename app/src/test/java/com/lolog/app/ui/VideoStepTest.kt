package com.lolog.app.ui

import com.lolog.app.data.model.VideoStatus
import com.lolog.app.ui.screens.comic.videoStepIndex
import org.junit.Assert.assertEquals
import org.junit.Test

/** 쇼츠 만드는 화면의 단계 알약(요청 받음 › 목소리 › 그림 › 완성)이 상태에 맞게 켜지는지 본다. */
class VideoStepTest {

    @Test
    fun 상태마다_단계_번호가_정해진다() {
        assertEquals(0, videoStepIndex(VideoStatus.QUEUED))
        assertEquals(1, videoStepIndex(VideoStatus.VOICE))
        assertEquals(2, videoStepIndex(VideoStatus.DRAWING))
        assertEquals(3, videoStepIndex(VideoStatus.DONE))
    }

    @Test
    fun 실패는_마지막_단계로_본다() {
        assertEquals(3, videoStepIndex(VideoStatus.FAILED))
    }
}
