package com.lolog.app.recording.audio

import java.io.File
import java.io.FileOutputStream

/**
 * 인코더가 내놓은 AAC 조각을 10분 단위 파일로 받아 적는다.
 * 압축은 끊지 않고 이어서 하고, 조각 9375개마다 받아 적는 파일만 바꾼다. 이어 붙이면 한 파일과 같다.
 *
 * @param onFileDone 파일 하나를 다 썼을 때(시간 기록까지 끝난 뒤) 부른다. 올리기를 깨울 때 쓴다.
 */
class AudioFileWriter(
    private val dir: File,
    private val onFileDone: (seq: Int) -> Unit = {},
) {
    private var seq = 0
    private var frames = 0
    private var out: FileOutputStream? = null
    private var framesSinceSync = 0

    /** 지금까지 쓴 조각 수(모든 파일 합). 녹음 시작부터의 음성 길이 = 이 값 × 64ms */
    var totalFrames = 0L
        private set

    @Synchronized
    fun writeFrame(payload: ByteArray, size: Int = payload.size) {
        val stream = out ?: openNext()
        stream.write(Adts.header(size))
        stream.write(payload, 0, size)
        frames++
        totalFrames++
        // 앱이 죽는 것은 운영체제가 쓴 내용을 지켜 준다. 폰이 꺼질 때를 위해 가끔 디스크에 확정한다.
        if (++framesSinceSync >= SYNC_EVERY_FRAMES) {
            stream.fd.sync()
            framesSinceSync = 0
        }
        if (frames >= AudioFiles.FRAMES_PER_FILE) finishCurrent()
    }

    /** 쓰던 파일을 마감한다. 조각이 없으면 파일을 만들지 않는다. */
    @Synchronized
    fun close() {
        finishCurrent()
    }

    private fun openNext(): FileOutputStream {
        seq++
        frames = 0
        framesSinceSync = 0
        return FileOutputStream(AudioFiles.aac(dir, seq), false).also { out = it }
    }

    private fun finishCurrent() {
        val stream = out ?: return
        stream.fd.sync()
        stream.close()
        out = null
        AudioFiles.markDone(dir, seq, frames)
        onFileDone(seq)
    }

    private companion object {
        const val SYNC_EVERY_FRAMES = 160 // 약 10초
    }
}
