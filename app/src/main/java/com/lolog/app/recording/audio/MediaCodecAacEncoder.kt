package com.lolog.app.recording.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import java.nio.ByteOrder

/**
 * 안드로이드 내장 AAC 인코더. 16kHz 모노 32kbps AAC-LC(명세 audio.md 2장 "형식").
 * 내놓는 조각에는 ADTS 머리말이 없다. 머리말은 AudioFileWriter가 붙인다.
 */
class MediaCodecAacEncoder(private val onFrame: (ByteArray, Int) -> Unit) : PcmEncoder {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val info = MediaCodec.BufferInfo()
    private var out = ByteArray(1024)
    private var samplesQueued = 0L

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        } catch (e: Exception) {
            codec.release()
            throw e
        }
    }

    override fun encode(pcm: ShortArray, count: Int) {
        var offset = 0
        while (offset < count) {
            val index = codec.dequeueInputBuffer(WAIT_US)
            if (index < 0) {
                drain(untilEnd = false)
                continue
            }
            val buffer = codec.getInputBuffer(index)!!
            buffer.clear()
            buffer.order(ByteOrder.nativeOrder())
            val n = minOf(count - offset, buffer.remaining() / 2)
            buffer.asShortBuffer().put(pcm, offset, n)
            codec.queueInputBuffer(index, 0, n * 2, presentationUs(), 0)
            samplesQueued += n
            offset += n
            drain(untilEnd = false)
        }
    }

    override fun finish() {
        try {
            var index = -1
            var tries = 0
            while (index < 0) {
                check(tries++ < END_TIMEOUT_US / WAIT_US) { "인코더가 입력을 받지 않음" }
                index = codec.dequeueInputBuffer(WAIT_US)
                if (index < 0) drain(untilEnd = false)
            }
            codec.queueInputBuffer(index, 0, 0, presentationUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(untilEnd = true)
            codec.stop()
        } finally {
            codec.release()
        }
    }

    private fun presentationUs() = samplesQueued * 1_000_000L / SAMPLE_RATE

    /** 나온 조각을 넘긴다. untilEnd면 끝 표시가 나올 때까지(최대 몇 초) 기다린다. */
    private fun drain(untilEnd: Boolean) {
        var waited = 0L
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (untilEnd) WAIT_US else 0)
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!untilEnd) return
                waited += WAIT_US
                if (waited > END_TIMEOUT_US) return
                continue
            }
            if (index < 0) continue // 출력 형식 바뀜 등. 조각이 아니다.
            val buffer = codec.getOutputBuffer(index)!!
            val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
            if (!isConfig && info.size > 0) {
                if (out.size < info.size) out = ByteArray(info.size)
                buffer.position(info.offset)
                buffer.get(out, 0, info.size)
                onFrame(out, info.size)
            }
            codec.releaseOutputBuffer(index, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16000
        const val BIT_RATE = 32_000
        const val WAIT_US = 10_000L
        const val END_TIMEOUT_US = 3_000_000L
    }
}
