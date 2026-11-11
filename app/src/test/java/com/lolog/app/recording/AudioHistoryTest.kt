package com.lolog.app.recording

import com.lolog.app.recording.stt.AudioHistory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioHistoryTest {
    @Test
    fun copiesOnlyWhatIsLeft() {
        val h = AudioHistory(4)
        h.write(floatArrayOf(1f, 2f, 3f))
        h.write(floatArrayOf(4f, 5f, 6f)) // 1, 2는 덮어쓴다.
        assertEquals(2L, h.oldest)
        assertArrayEquals(floatArrayOf(3f, 4f, 5f, 6f), h.copyRange(0, 6), 0f)
        assertArrayEquals(floatArrayOf(4f, 5f), h.copyRange(3, 5), 0f)
        assertEquals(0, h.copyRange(6, 9).size)
    }

    @Test
    fun findsQuietestFrameCenter() {
        val h = AudioHistory(64)
        // 4샘플 프레임 8개. 6번째 프레임(20~23)이 가장 조용하다.
        val loud = FloatArray(32) { 0.5f }
        for (i in 20 until 24) loud[i] = 0.01f
        for (i in 8 until 12) loud[i] = 0.1f
        h.write(loud)
        assertEquals(22L, h.quietestPoint(0, 32, 4))
        // 찾는 범위 밖의 조용한 곳은 고르지 않는다.
        assertEquals(10L, h.quietestPoint(0, 20, 4))
    }

    @Test
    fun returnsHiWhenNoFrameFits() {
        val h = AudioHistory(16)
        h.write(FloatArray(16))
        assertEquals(10L, h.quietestPoint(8, 10, 4))
    }
}
