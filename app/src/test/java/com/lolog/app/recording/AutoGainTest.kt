package com.lolog.app.recording

import com.lolog.app.recording.stt.AutoGain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class AutoGainTest {
    private val frame = 512

    private fun tone(amp: Float, start: Int) = FloatArray(frame) { amp * sin(2 * PI * 440 * (start + it) / 16000).toFloat() }
    private fun noise(amp: Float, seed: Int) = FloatArray(frame) { amp * (((seed * 31 + it * 17) % 100) / 50f - 1f) }
    private fun rms(x: FloatArray) = sqrt(x.sumOf { it.toDouble() * it } / x.size)

    @Test
    fun quietSpeechAfterQuietRoomGetsLouder() {
        val g = AutoGain()
        repeat(20) { g.apply(noise(0.0002f, it)) } // 아주 조용한 방(약 -80dBFS)
        var out = FloatArray(0)
        repeat(300) { out = tone(0.005f, it * frame).also(g::apply) } // 작은 말소리(약 -49dBFS)
        assertTrue("작은 소리는 키워야 한다: ${g.currentGain}", g.currentGain > 5.0)
        assertTrue(rms(out) > 0.005 / sqrt(2.0) * 5)
    }

    @Test
    fun neverMakesLoudSoundQuieter() {
        val g = AutoGain()
        repeat(100) {
            val f = tone(0.9f, it * frame)
            val before = f.copyOf()
            g.apply(f)
            for (i in f.indices) assertEquals(before[i], f[i], 1e-6f)
        }
        assertEquals(1.0, g.currentGain, 1e-9)
    }

    @Test
    fun gainStaysWithinLimitsAndOutputIsClipped() {
        val g = AutoGain()
        repeat(500) {
            val f = tone(0.0005f, it * frame)
            g.apply(f)
            assertTrue(g.currentGain in 1.0..20.0)
            assertTrue(f.all { v -> v in -1f..1f })
        }
        // 갑자기 큰 소리가 와도 [-1, 1]을 넘지 않는다.
        val loud = tone(0.9f, 0)
        g.apply(loud)
        assertTrue(loud.all { it in -1f..1f })
    }

    @Test
    fun loudBackgroundNoiseLimitsGain() {
        // 바닥 소음이 -40dBFS면 키운 소음이 -50dBFS를 넘지 않게 이득을 1배로 둔다.
        val g = AutoGain()
        val floor = 10.0.pow(-40.0 / 20).toFloat() * sqrt(3f) // 균등 잡음의 RMS가 -40dBFS가 되게
        repeat(300) { g.apply(noise(floor, it)) }
        assertEquals(1.0, g.currentGain, 1e-9)
    }

    @Test
    fun rampsGainInsideFrame() {
        val g = AutoGain()
        g.apply(FloatArray(frame) { 1e-4f }) // 첫 프레임: 바닥 소음 시작값
        val f = FloatArray(frame) { 0.003f }
        g.apply(f)
        // 이득이 바뀌는 프레임은 앞쪽이 덜 키워지고 뒤쪽이 더 키워진다.
        assertTrue(f.first() < f.last())
        assertEquals(0.003f, f.first(), 1e-6f)
    }
}
