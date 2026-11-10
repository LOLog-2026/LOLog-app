package com.lolog.app.recording.audio

import com.lolog.app.recording.stt.AudioSink

/** 16비트 PCM을 받아 AAC 조각(머리말 없음)을 내놓는다. 조각은 만든 순서대로 onFrame으로 넘긴다. */
interface PcmEncoder {
    fun encode(pcm: ShortArray, count: Int)
    /** 남은 소리를 마저 압축해 내놓고 자원을 놓는다. */
    fun finish()
}

/**
 * 글자 변환이 넘긴 소리를 음성 파일로 쓴다. 인식이 건너뛴 소리는 무음으로 채운다.
 * 인코더가 고장 나도 글자 변환은 계속돼야 하므로, 오류가 나면 그 녹음의 음성 저장만 멈춘다(그때까지 쓴 파일은 남는다).
 */
class AudioRecorder(
    private val writer: AudioFileWriter,
    encoderFactory: (onFrame: (ByteArray, Int) -> Unit) -> PcmEncoder,
    private val onError: (Throwable) -> Unit = {},
) : AudioSink {
    private val encoder: PcmEncoder = encoderFactory { bytes, size -> writer.writeFrame(bytes, size) }
    private val pcm = ShortArray(CHUNK)
    @Volatile private var failed = false

    override fun write(samples: FloatArray, count: Int) = guard {
        var i = 0
        while (i < count) {
            val n = minOf(CHUNK, count - i)
            for (k in 0 until n) pcm[k] = (samples[i + k].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
            encoder.encode(pcm, n)
            i += n
        }
    }

    override fun skip(samples: Long) = guard {
        if (samples <= 0) return@guard
        val silence = ShortArray(CHUNK)
        var left = samples
        while (left > 0) {
            val n = minOf(CHUNK.toLong(), left).toInt()
            encoder.encode(silence, n)
            left -= n
        }
    }

    override fun close() {
        if (!failed) runCatching { encoder.finish() }.onFailure(onError)
        runCatching { writer.close() }.onFailure(onError)
    }

    private inline fun guard(block: () -> Unit) {
        if (failed) return
        try {
            block()
        } catch (e: Exception) {
            failed = true
            onError(e)
            runCatching { writer.close() }
        }
    }

    private companion object {
        const val CHUNK = 4096
    }
}
