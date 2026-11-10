package com.lolog.app.recording.audio

import java.io.File
import java.io.RandomAccessFile

/**
 * AAC ADTS 머리말을 쓰고 읽는다(명세 audio.md 2장 "형식").
 * 조각(프레임)마다 7바이트 머리말을 붙이므로, 앱이 쓰다 죽어도 그때까지 쓴 조각은 그대로 들을 수 있다.
 * 16kHz AAC-LC 조각 하나는 1024샘플 = 64ms로 일정하다.
 */
object Adts {
    const val HEADER_SIZE = 7
    const val SAMPLES_PER_FRAME = 1024
    const val FRAME_MS = 64L // 1024 / 16000초

    private const val PROFILE_LC = 1 // ADTS profile = 오디오 객체 종류(AAC-LC=2) - 1
    private const val FREQ_INDEX_16K = 8
    private const val CHANNELS_MONO = 1
    private const val MAX_FRAME_LENGTH = 0x1FFF // 13비트

    /** 인코더가 준 AAC 조각 하나(머리말 없음) 앞에 붙일 머리말 */
    fun header(payloadSize: Int): ByteArray {
        val len = payloadSize + HEADER_SIZE
        require(len <= MAX_FRAME_LENGTH) { "조각이 너무 큼: $payloadSize" }
        return byteArrayOf(
            0xFF.toByte(),
            0xF1.toByte(), // MPEG-4, layer 0, CRC 없음
            ((PROFILE_LC shl 6) or (FREQ_INDEX_16K shl 2) or (CHANNELS_MONO shr 2)).toByte(),
            (((CHANNELS_MONO and 3) shl 6) or (len shr 11)).toByte(),
            ((len shr 3) and 0xFF).toByte(),
            (((len and 7) shl 5) or 0x1F).toByte(),
            0xFC.toByte(), // 버퍼 0x7FF(가변), 조각 안 블록 1개
        )
    }

    /** 파일을 처음부터 조각으로 나눠 본 결과. validBytes 뒤는 덜 쓰였거나 깨진 부분이다. */
    data class Scan(val frames: Int, val validBytes: Long)

    fun scan(file: File): Scan {
        if (!file.exists()) return Scan(0, 0)
        RandomAccessFile(file, "r").use { raf ->
            val size = raf.length()
            val head = ByteArray(HEADER_SIZE)
            var pos = 0L
            var frames = 0
            while (pos + HEADER_SIZE <= size) {
                raf.seek(pos)
                raf.readFully(head)
                val b0 = head[0].toInt() and 0xFF
                val b1 = head[1].toInt() and 0xFF
                if (b0 != 0xFF || (b1 and 0xF0) != 0xF0) break
                val len = ((head[3].toInt() and 0x03) shl 11) or
                    ((head[4].toInt() and 0xFF) shl 3) or
                    ((head[5].toInt() and 0xE0) shr 5)
                if (len < HEADER_SIZE || pos + len > size) break
                pos += len
                frames++
            }
            return Scan(frames, pos)
        }
    }

    /** 덜 쓰인 마지막 조각을 잘라 내고 남은 조각 수를 돌려준다. 서버는 끝까지 조각으로 나눠지지 않는 파일을 받지 않는다. */
    fun trim(file: File): Int {
        val scan = scan(file)
        if (file.exists() && file.length() != scan.validBytes) {
            RandomAccessFile(file, "rw").use { it.setLength(scan.validBytes) }
        }
        return scan.frames
    }
}
