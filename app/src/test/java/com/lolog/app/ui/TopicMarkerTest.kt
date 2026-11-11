package com.lolog.app.ui

import com.lolog.app.ui.screens.library.topicMarker
import org.junit.Assert.assertEquals
import org.junit.Test

/** 화자 고치기 팝업 제목 "○○은/는 누구랑 닮았어요?"의 조사 */
class TopicMarkerTest {
    @Test
    fun 받침이_있으면_은_없으면_는() {
        assertEquals("은", topicMarker("서연"))
        assertEquals("는", topicMarker("민지"))
        assertEquals("은", topicMarker("화자1"))
        assertEquals("는", topicMarker("화자2"))
        assertEquals("는", topicMarker(""))
    }
}
