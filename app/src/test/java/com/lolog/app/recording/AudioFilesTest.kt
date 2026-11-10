package com.lolog.app.recording

import com.lolog.app.recording.audio.Adts
import com.lolog.app.recording.audio.AudioFileWriter
import com.lolog.app.recording.audio.AudioFiles
import com.lolog.app.recording.audio.AudioRecorder
import com.lolog.app.recording.audio.PcmEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AudioFilesTest {
    @get:Rule val folder = TemporaryFolder()

    private val payload = ByteArray(20) { it.toByte() }

    @Test
    fun ADTS_머리말은_AAC_LC_16kHz_모노이고_길이를_담는다() {
        val h = Adts.header(20)
        assertEquals(0xFF, h[0].toInt() and 0xFF)
        assertEquals(0xF1, h[1].toInt() and 0xFF)
        // profile 1(LC), 주파수 번호 8(16kHz), 채널 1
        assertEquals(1, (h[2].toInt() and 0xC0) shr 6)
        assertEquals(8, (h[2].toInt() and 0x3C) shr 2)
        assertEquals(1, ((h[2].toInt() and 1) shl 2) or ((h[3].toInt() and 0xC0) shr 6))
        val len = ((h[3].toInt() and 3) shl 11) or ((h[4].toInt() and 0xFF) shl 3) or ((h[5].toInt() and 0xE0) shr 5)
        assertEquals(27, len)
    }

    @Test
    fun 덜_쓰인_마지막_조각은_잘라_낸다() {
        val file = folder.newFile("a.aac")
        file.outputStream().use { out ->
            repeat(3) { out.write(Adts.header(payload.size)); out.write(payload) }
            out.write(Adts.header(payload.size)); out.write(payload, 0, 5) // 쓰다 죽음
        }
        assertEquals(Adts.Scan(3, 81), Adts.scan(file))
        assertEquals(3, Adts.trim(file))
        assertEquals(81, file.length())
    }

    @Test
    fun 조각_9375개마다_파일을_바꾸고_시간을_적는다() {
        val dir = folder.newFolder("rec")
        val done = mutableListOf<Int>()
        val writer = AudioFileWriter(dir) { done += it }
        repeat(AudioFiles.FRAMES_PER_FILE + 10) { writer.writeFrame(payload) }
        assertEquals(listOf(1), done)
        assertEquals(listOf(1), AudioFiles.pending(dir).map { it.seq }) // 2번은 쓰는 중이라 아직 올리지 않음
        writer.close()

        val files = AudioFiles.pending(dir)
        assertEquals(listOf(1, 2), files.map { it.seq })
        assertEquals(0L, files[0].startMs)
        assertEquals(600_000L, files[0].endMs)
        assertEquals(600_000L, files[1].startMs)
        assertEquals(600_000L + 10 * 64, files[1].endMs)
        assertEquals(AudioFiles.FRAMES_PER_FILE, Adts.scan(files[0].file).frames)
        assertEquals(10, Adts.scan(files[1].file).frames)
    }

    @Test
    fun 조각이_없으면_파일을_만들지_않는다() {
        val dir = folder.newFolder("rec")
        AudioFileWriter(dir).close()
        assertFalse(AudioFiles.any(dir))
    }

    @Test
    fun 앱이_죽어_끝맺지_못한_파일은_조각_수로_끝_시각을_센다() {
        val dir = folder.newFolder("rec")
        val writer = AudioFileWriter(dir)
        repeat(AudioFiles.FRAMES_PER_FILE + 4) { writer.writeFrame(payload) }
        // close 없이 죽음. 마지막 조각은 반만 쓰였다.
        AudioFiles.aac(dir, 2).appendBytes(Adts.header(payload.size) + payload.copyOf(3))
        AudioFiles.closeAbandoned(dir)

        val last = AudioFiles.pending(dir).last()
        assertEquals(2, last.seq)
        assertEquals(600_000L + 4 * 64, last.endMs)
        assertEquals(4L * (payload.size + Adts.HEADER_SIZE), last.file.length())
    }

    @Test
    fun 건너뛴_소리는_무음으로_채워_길이를_맞춘다() {
        val dir = folder.newFolder("rec")
        val encoder = CountingEncoder()
        val recorder = AudioRecorder(AudioFileWriter(dir), { onFrame -> encoder.also { it.onFrame = onFrame } })
        recorder.write(FloatArray(512) { 0.5f }, 512)
        recorder.skip(1536)
        recorder.write(FloatArray(512) { -2f }, 512) // 범위를 넘는 값은 자른다
        recorder.close()

        assertEquals(2560L, encoder.samples)
        assertEquals(1536, encoder.zeros)
        assertEquals(Short.MIN_VALUE + 1, encoder.min.toInt())
        // 1024샘플마다 조각 하나, 남은 512샘플은 끝낼 때 조각 하나
        assertEquals(3, Adts.scan(AudioFiles.pending(dir).single().file).frames)
    }

    @Test
    fun 인코더가_고장_나도_예외를_던지지_않고_그때까지_쓴_파일은_남긴다() {
        val dir = folder.newFolder("rec")
        val errors = mutableListOf<Throwable>()
        val encoder = CountingEncoder(failAfter = 2048)
        val recorder = AudioRecorder(AudioFileWriter(dir), { onFrame -> encoder.also { it.onFrame = onFrame } }, onError = { errors += it })
        repeat(4) { recorder.write(FloatArray(1024), 1024) }
        recorder.close()
        assertEquals(1, errors.size)
        assertEquals(2, Adts.scan(AudioFiles.pending(dir).single().file).frames)
    }

    /** 1024샘플마다 조각 하나를 내놓는 가짜 인코더 */
    private class CountingEncoder(private val failAfter: Long = Long.MAX_VALUE) : PcmEncoder {
        lateinit var onFrame: (ByteArray, Int) -> Unit
        var samples = 0L
        var zeros = 0
        var min: Short = 0
        private var buffered = 0

        override fun encode(pcm: ShortArray, count: Int) {
            if (samples + count > failAfter) error("인코더 고장")
            for (i in 0 until count) {
                if (pcm[i] == 0.toShort()) zeros++
                if (pcm[i] < min) min = pcm[i]
            }
            samples += count
            buffered += count
            while (buffered >= 1024) {
                onFrame(ByteArray(8), 8)
                buffered -= 1024
            }
        }

        override fun finish() {
            if (buffered > 0) onFrame(ByteArray(8), 8)
            buffered = 0
        }
    }
}
