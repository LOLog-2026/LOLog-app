package com.lolog.app.recording.stt

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * VAD 앞에서 소리 크기를 실시간으로 맞춘다. 지난 소리만 보고 정한다.
 * 계산은 LOLog-ai `experiments/261109-stt-ondevice/run_bench.py`의 agc(noise_init="first", noise_ceil_db=-50)와 같다.
 *
 * - 바닥 소음(noise)은 첫 프레임 크기에서 시작한다. 더 작은 프레임이 오면 바로 내려가고, 아니면 아주 천천히(x1.002) 오른다.
 * - 말소리 크기(level)는 -23dBFS에서 시작한다. 바닥 소음의 2배를 넘는 프레임에서만 따라간다.
 * - 이득은 목표/말소리 크기를 1~20배로 자른 뒤, 키운 바닥 소음이 -50dBFS를 넘지 않게 한 번 더 줄인다.
 * - 프레임 안에서는 이전 이득에서 새 이득까지 선형으로 바꿔 곱한다(소리가 튀지 않게).
 */
class AutoGain {
    companion object {
        private const val TARGET_DB = -23.0
        private const val MAX_GAIN = 20.0
        private const val NOISE_CEIL_DB = -50.0
        private const val VOICE_OVER_NOISE = 2.0 // 바닥 소음보다 6dB 넘게 커야 말소리로 본다.
        private const val NOISE_RISE = 1.002
        private const val EPS = 1e-8
        private val TARGET = 10.0.pow(TARGET_DB / 20)
        private val NOISE_CEIL = 10.0.pow(NOISE_CEIL_DB / 20)
    }

    private var noise = -1.0 // 음수면 아직 첫 프레임을 받지 않은 것이다.
    private var level = TARGET
    private var gain = 1.0

    /** 지금 이득. 마지막 프레임 끝에 쓴 값이다. */
    val currentGain: Double get() = gain

    /** frame을 그 자리에서 키운다. 결과는 [-1, 1]로 자른다. */
    fun apply(frame: FloatArray) {
        if (frame.isEmpty()) return
        var sum = 0.0
        for (v in frame) sum += v.toDouble() * v
        val r = sqrt(sum / frame.size) + EPS
        if (noise < 0) noise = r
        noise = if (r < noise) r else noise * NOISE_RISE
        if (r > noise * VOICE_OVER_NOISE) level = 0.9 * level + 0.1 * r
        var next = min(MAX_GAIN, max(1.0, TARGET / level))
        next = max(1.0, min(next, NOISE_CEIL / noise))

        val last = frame.size - 1
        for (i in frame.indices) {
            val g = if (last == 0) next else gain + (next - gain) * i / last
            frame[i] = (frame[i] * g).toFloat().coerceIn(-1f, 1f)
        }
        gain = next
    }
}
