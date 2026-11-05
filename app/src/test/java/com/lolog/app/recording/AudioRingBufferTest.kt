package com.lolog.app.recording

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import com.lolog.app.recording.stt.AudioRingBuffer
import org.junit.Test

class AudioRingBufferTest {
    private fun shorts(vararg v: Int) = ShortArray(v.size) { v[it].toShort() }

    @Test
    fun readsInOrderAcrossWrap() {
        val r = AudioRingBuffer(4)
        r.write(shorts(1, 2, 3), 3)
        val a = ShortArray(2)
        assertEquals(2, r.readBlocking(a))
        assertArrayEquals(shorts(1, 2), a)
        r.write(shorts(4, 5, 6), 3) // 끝을 넘어 앞으로 돌아간다.
        val b = ShortArray(4)
        assertEquals(4, r.readBlocking(b))
        assertArrayEquals(shorts(3, 4, 5, 6), b)
        assertEquals(0L, r.droppedSamples)
    }

    @Test
    fun countsOverwrittenSamples() {
        val r = AudioRingBuffer(4)
        r.write(shorts(1, 2, 3, 4, 5, 6), 6)
        assertEquals(2L, r.droppedSamples)
        assertEquals(2L, r.nextReadPosition)
        val a = ShortArray(4)
        r.readBlocking(a)
        assertArrayEquals(shorts(3, 4, 5, 6), a)
    }

    @Test
    fun copyRangeRefusesOverwrittenOrFuture() {
        val r = AudioRingBuffer(4)
        r.write(shorts(1, 2, 3, 4, 5, 6), 6)
        assertArrayEquals(shorts(3, 4), r.copyRange(2, 2))
        assertNull(r.copyRange(1, 2)) // 덮어써짐
        assertNull(r.copyRange(5, 2)) // 아직 없음
        assertNull(r.copyRange(-1, 1))
    }

    @Test
    fun closeReturnsRemainder() {
        val r = AudioRingBuffer(4)
        r.write(shorts(7), 1)
        r.close()
        val a = ShortArray(3)
        assertEquals(1, r.readBlocking(a))
        assertEquals(7.toShort(), a[0])
        assertEquals(0, r.readBlocking(a))
    }
}
