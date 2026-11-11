package com.lolog.app.ui

import com.lolog.app.data.model.ErrorCodes
import com.lolog.app.data.repo.LologException
import com.lolog.app.ui.components.errorText
import com.lolog.app.ui.screens.friends.voiceSaveError
import org.junit.Assert.assertEquals
import org.junit.Test

/** 내 목소리 고르기 저장 실패 문구(FR-CHAR-04, docs PR #59 초안) */
class VoicePickTest {
    @Test
    fun 없는_목소리나_캐릭터_없음은_저장하지_못했다고_알린다() {
        assertEquals("목소리를 저장하지 못했어요", voiceSaveError(LologException(ErrorCodes.UNKNOWN_CHARACTER, "")))
        assertEquals("목소리를 저장하지 못했어요", voiceSaveError(LologException(ErrorCodes.CHARACTER_NOT_FOUND, "")))
    }

    @Test
    fun 그_밖의_오류는_공용_문구를_쓴다() {
        val e = LologException(ErrorCodes.NETWORK, "인터넷 연결을 확인해 주세요")
        assertEquals(errorText(e), voiceSaveError(e))
    }
}
