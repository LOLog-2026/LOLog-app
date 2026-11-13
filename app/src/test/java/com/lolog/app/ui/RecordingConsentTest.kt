package com.lolog.app.ui

import com.lolog.app.ui.screens.recording.RecordingConsent
import org.junit.Assert.assertEquals
import org.junit.Test

/** 녹음 동의 창 문구가 명세(LOLog-docs functional-spec FR-REC-10)와 같은지 본다. */
class RecordingConsentTest {

    @Test
    fun 음성_저장을_끄면_음성이_폰_밖으로_안_나간다고_알린다() {
        assertEquals(
            "함께 대화하는 사람들에게 녹음한다고 알리고 동의를 받아 주세요. 대화는 폰 안에서 글자로 바뀌어 서버에 올라가고, 음성은 폰 밖으로 나가지 않아요.",
            RecordingConsent.body(saveAudio = false),
        )
    }

    @Test
    fun 음성_저장을_켜면_보관과_삭제를_알린다() {
        assertEquals(
            "함께 대화하는 사람들에게 녹음한다고 알리고 동의를 받아 주세요. 대화는 폰 안에서 글자로 바뀌어 서버에 올라가요. 음성도 저장해서 카드에 담긴 부분만 남기고 나머지는 7일 뒤에 지워요.",
            RecordingConsent.body(saveAudio = true),
        )
    }

    @Test
    fun 제목과_버튼() {
        assertEquals("녹음하기 전에 알려 주세요", RecordingConsent.TITLE)
        assertEquals("동의받음", RecordingConsent.CONFIRM)
    }
}
