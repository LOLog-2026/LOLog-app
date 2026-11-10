package com.lolog.app.recording.audio

import java.io.File
import java.io.FileOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 녹음 폴더 안의 음성 파일(명세 audio.md 2장 "10분 단위 파일").
 *
 * - audio-N.aac: N번(seq, 1부터) 음성 파일. 쓰는 중이거나 다 쓴 것
 * - audio-N.json: 다 쓴 표시와 시간({seq, startMs, endMs}). 이 파일이 있어야 올린다
 *
 * 파일 하나는 조각 9375개(= 600초 / 0.064초)로 딱 10분이다. 마지막 파일만 짧다.
 * 그래서 N번 파일의 시작은 늘 (N-1) × 10분이고, 끝은 시작 + 조각 수 × 64ms다.
 */
object AudioFiles {
    const val FRAMES_PER_FILE = 9375
    const val FILE_MS = FRAMES_PER_FILE * Adts.FRAME_MS // 600,000ms

    @Serializable
    data class Done(val seq: Int, val startMs: Long, val endMs: Long)

    /** 올릴 파일 하나 */
    data class Pending(val seq: Int, val file: File, val startMs: Long, val endMs: Long)

    private val json = Json { ignoreUnknownKeys = true }
    private val AAC_NAME = Regex("""audio-(\d+)\.aac""")

    fun aac(dir: File, seq: Int) = File(dir, "audio-$seq.aac")
    private fun doneFile(dir: File, seq: Int) = File(dir, "audio-$seq.json")

    fun startMsOf(seq: Int): Long = (seq - 1) * FILE_MS

    /** 다 쓴 파일에 시간을 적어 올릴 수 있게 한다. */
    fun markDone(dir: File, seq: Int, frames: Int) {
        val start = startMsOf(seq)
        val text = json.encodeToString(Done.serializer(), Done(seq, start, start + frames * Adts.FRAME_MS))
        val target = doneFile(dir, seq)
        val tmp = File(dir, target.name + ".tmp")
        FileOutputStream(tmp).use {
            it.write(text.toByteArray())
            it.fd.sync()
        }
        check(tmp.renameTo(target)) { "저장 실패: ${target.name}" }
    }

    /** 다 쓴(올릴) 파일을 번호 순서대로 */
    fun pending(dir: File): List<Pending> = seqs(dir).mapNotNull { seq ->
        val done = doneFile(dir, seq).takeIf { it.exists() } ?: return@mapNotNull null
        val d = runCatching { json.decodeFromString(Done.serializer(), done.readText()) }.getOrNull() ?: return@mapNotNull null
        Pending(seq, aac(dir, seq), d.startMs, d.endMs)
    }

    /** 폴더에 음성 파일이 하나라도 남았는지(쓰는 중 포함) */
    fun any(dir: File): Boolean = seqs(dir).isNotEmpty()

    /** 올렸거나 버릴 파일을 지운다. */
    fun delete(dir: File, seq: Int) {
        doneFile(dir, seq).delete()
        aac(dir, seq).delete()
    }

    fun deleteAll(dir: File) = seqs(dir).forEach { delete(dir, it) }

    /**
     * 앱이 쓰다 죽어 끝맺지 못한 파일을 마감한다. 덜 쓰인 마지막 조각을 잘라 내고, 끝 시각은 조각 수 × 64ms로 센다.
     * 조각이 하나도 없으면 지운다. 녹음 중이 아닐 때만 부른다.
     */
    fun closeAbandoned(dir: File) {
        seqs(dir).filter { !doneFile(dir, it).exists() }.forEach { seq ->
            val frames = Adts.trim(aac(dir, seq))
            if (frames > 0) markDone(dir, seq, frames) else delete(dir, seq)
        }
    }

    private fun seqs(dir: File): List<Int> =
        (dir.listFiles { f -> AAC_NAME.matches(f.name) } ?: emptyArray())
            .map { AAC_NAME.find(it.name)!!.groupValues[1].toInt() }
            .sorted()
}
