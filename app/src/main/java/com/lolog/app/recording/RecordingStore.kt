package com.lolog.app.recording

import com.lolog.app.data.remote.Sentence
import com.lolog.app.data.remote.UploadRecordingRequest
import com.lolog.app.recording.audio.AudioFileWriter
import com.lolog.app.recording.audio.AudioFiles
import java.io.File
import java.io.FileOutputStream
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 녹음 글을 폰에 쌓는다. 앱이 죽어도 글이 남도록 문장마다 디스크에 확정한다.
 * 음성은 음성 저장을 켠 녹음만 파일로 쓴다(명세 audio.md).
 *
 * 녹음마다 폴더 하나다.
 * - meta.json: 녹음 ID, 시작 시각, 다음 묶음 번호, 음성 저장 설정, 서버 녹음 ID, 끝난 시각
 * - current.jsonl: 아직 묶지 않은 문장, 한 줄에 하나
 * - part-N.json: 올릴 묶음. 서버가 받으면 지운다.
 * - audio-N.aac, audio-N.json: 음성 파일(AudioFiles). 서버가 받으면 지운다.
 * 묶음과 음성 파일이 모두 없어진 끝난 녹음은 폴더까지 지운다.
 */
class RecordingStore(private val root: File) {

    @Serializable
    private data class Meta(
        val clientRecordingId: String,
        val startedAt: String,
        val nextPartNo: Int = 1,
        val partStartMs: Long = 0,
        val sealedSeq: Long = 0,
        val closed: Boolean = false,
        /** 음성 저장을 켰는지. 묶음마다 같은 값을 audioSaved로 보낸다. */
        val audioSaved: Boolean = false,
        /** 모바일 데이터에서도 음성을 올리는지("지금 올리기"). false면 Wi-Fi에서만 올린다. */
        val audioAnyNetwork: Boolean = false,
        /** 첫 묶음을 올리고 받은 서버 녹음 ID. 음성은 이 ID가 있어야 올린다. */
        val recordingId: Long? = null,
        /** 녹음이 끝난 때(시작 시각 + 전체 길이, epoch ms). 못 올린 음성을 7일 뒤 지울 때 쓴다. */
        val endedAtEpochMs: Long? = null,
    )

    @Serializable
    private data class Line(val seq: Long, val startMs: Long, val endMs: Long, val text: String)

    data class PendingPart(val recordingDir: File, val file: File, val request: UploadRecordingRequest)

    /** 올릴 음성 파일이 있는 녹음 하나 */
    data class PendingAudio(
        val recordingDir: File,
        val clientRecordingId: String,
        /** null이면 첫 묶음이 아직 안 올라가 음성을 올릴 수 없다. */
        val recordingId: Long?,
        val anyNetwork: Boolean,
        val files: List<AudioFiles.Pending>,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 새 녹음을 시작한다.
     * @param audioSaved 음성 저장을 켰는지
     * @param audioAnyNetwork 모바일 데이터에서도 음성을 올리는지
     */
    @Synchronized
    fun open(startedAt: String, audioSaved: Boolean = false, audioAnyNetwork: Boolean = false): OpenRecording {
        val id = UUID.randomUUID().toString()
        val dir = File(root, id).apply { mkdirs() }
        writeMeta(
            dir,
            Meta(clientRecordingId = id, startedAt = startedAt, audioSaved = audioSaved, audioAnyNetwork = audioSaved && audioAnyNetwork),
        )
        return OpenRecording(dir)
    }

    /** 앱이 죽어서 닫히지 못한 녹음을 쌓인 문장까지로 마감한다. 녹음 중이 아닐 때만 부른다. */
    @Synchronized
    fun closeAbandoned() {
        recordingDirs().forEach { dir ->
            val meta = runCatching { reconcile(dir) }.getOrNull() ?: return@forEach
            // 쓰다 만 음성 파일은 덜 쓰인 조각을 잘라 내고 조각 수로 끝 시각을 센다.
            runCatching { AudioFiles.closeAbandoned(dir) }
            if (!meta.closed) {
                val lines = readLines(dir, meta)
                val audioEndMs = AudioFiles.pending(dir).maxOfOrNull { it.endMs } ?: 0
                val endMs = maxOf(lines.lastOrNull()?.endMs ?: 0, meta.partStartMs, audioEndMs)
                seal(dir, meta, lines, endMs, last = true)
            }
        }
    }

    /** 올릴 묶음을 오래된 순서로 돌려준다. */
    @Synchronized
    fun pendingParts(): List<PendingPart> = recordingDirs().flatMap { dir ->
        partFiles(dir).map { file ->
            PendingPart(dir, file, json.decodeFromString(UploadRecordingRequest.serializer(), file.readText()))
        }
    }

    /**
     * 서버가 받은 묶음을 지운다. 끝난 녹음이고 남은 음성도 없으면 폴더까지 지운다.
     * @param recordingId 서버가 돌려준 녹음 ID. 음성을 올릴 때 쓰려고 적어 둔다.
     */
    @Synchronized
    fun markUploaded(part: PendingPart, recordingId: Long? = null) {
        part.file.delete()
        val meta = readMeta(part.recordingDir)
        if (recordingId != null && meta.recordingId != recordingId) {
            writeMeta(part.recordingDir, meta.copy(recordingId = recordingId))
        }
        removeIfFinished(part.recordingDir)
    }

    /** 다 쓴 음성 파일이 있는 녹음을 오래된 순서로 돌려준다. 파일은 번호 순서다. */
    @Synchronized
    fun pendingAudio(): List<PendingAudio> = recordingDirs().mapNotNull { dir ->
        val files = AudioFiles.pending(dir).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val meta = runCatching { readMeta(dir) }.getOrNull() ?: return@mapNotNull null
        PendingAudio(dir, meta.clientRecordingId, meta.recordingId, meta.audioAnyNetwork, files)
    }

    /** 올렸거나(204) 서버가 받지 않아(400·404·413) 버릴 음성 파일을 지운다. */
    @Synchronized
    fun removeAudio(recordingDir: File, seq: Int) {
        AudioFiles.delete(recordingDir, seq)
        removeIfFinished(recordingDir)
    }

    /** 녹음이 끝난 지 7일이 지나도 못 올린 음성 파일을 지운다(명세 audio.md 3장). 지운 파일 수를 돌려준다. */
    @Synchronized
    fun deleteExpiredAudio(nowEpochMs: Long): Int {
        var count = 0
        recordingDirs().forEach { dir ->
            val meta = runCatching { readMeta(dir) }.getOrNull() ?: return@forEach
            val ended = meta.endedAtEpochMs ?: return@forEach
            if (nowEpochMs - ended < AUDIO_KEEP_MS) return@forEach
            count += AudioFiles.pending(dir).size
            AudioFiles.deleteAll(dir)
            removeIfFinished(dir)
        }
        return count
    }

    /** 아직 올리지 못한 글이나 음성이 폰에 남았는지. 로그아웃 확인 창 문구를 고를 때 쓴다. */
    @Synchronized
    fun hasUnsent(): Boolean = recordingDirs().any { dir ->
        partFiles(dir).isNotEmpty() || AudioFiles.any(dir) || runCatching { !readMeta(dir).closed }.getOrDefault(false)
    }

    /** 폰에 남은 녹음을 모두 지운다. 로그아웃·탈퇴 때만 부르고, 열린 녹음이 없을 때 부른다. */
    @Synchronized
    fun deleteAll() {
        root.listFiles()?.forEach { it.deleteRecursively() }
    }

    /** 폰에 남은 음성 파일만 모두 지운다. 로그아웃할 때 올리지 못한 음성을 지울 때 쓴다. */
    @Synchronized
    fun deleteAllAudio() {
        recordingDirs().forEach { dir ->
            AudioFiles.deleteAll(dir)
            removeIfFinished(dir)
        }
    }

    // 끝난 녹음이고 올릴 묶음도 음성도 없으면 폴더를 지운다.
    private fun removeIfFinished(dir: File) {
        val meta = runCatching { readMeta(dir) }.getOrNull() ?: return
        if (meta.closed && partFiles(dir).isEmpty() && !AudioFiles.any(dir)) dir.deleteRecursively()
    }

    inner class OpenRecording internal constructor(private val dir: File) {
        private var seq = readMeta(dir).sealedSeq
        private val out = FileOutputStream(File(dir, CURRENT), true)

        val clientRecordingId: String = readMeta(dir).clientRecordingId

        /** 음성 저장을 켠 녹음이면 이 녹음 폴더에 음성 파일을 쓰는 곳 */
        fun audioWriter(onFileDone: (seq: Int) -> Unit = {}): AudioFileWriter = AudioFileWriter(dir, onFileDone)

        /** 쌓은 문장 수(빈 결과 제외). 녹음이 아무것도 못 남겼는지 볼 때 쓴다. */
        val sentenceCount: Long get() = seq

        /**
         * 아무것도 올리지 않은 녹음을 없앤다. 마이크나 글자 변환이 시작도 못 했을 때만 부른다.
         * 이미 올린 묶음이 있으면 서버에 끝을 알려야 하므로 지우지 않고 false를 돌려준다.
         */
        fun discardIfUnsent(): Boolean = synchronized(this@RecordingStore) {
            val meta = readMeta(dir)
            if (meta.nextPartNo != 1 || seq > 0) return false
            out.close()
            dir.deleteRecursively()
            true
        }

        /** 올리지 않고 없앤다. 로그아웃·탈퇴로 녹음을 버릴 때 쓴다. */
        fun discard() = synchronized(this@RecordingStore) {
            out.close()
            dir.deleteRecursively()
        }

        /** 빈 결과는 쌓지 않는다. */
        fun append(startMs: Long, endMs: Long, text: String) {
            if (text.isBlank()) return
            synchronized(this@RecordingStore) {
                seq++
                out.write((json.encodeToString(Line(seq, startMs, endMs, text)) + "\n").toByteArray())
                out.fd.sync()
            }
        }

        /** 지금까지의 문장을 묶음으로 만들고 묶음 번호를 돌려준다. */
        fun sealPart(positionMs: Long, last: Boolean): Int = synchronized(this@RecordingStore) {
            val meta = readMeta(dir)
            val partNo = meta.nextPartNo
            seal(dir, meta, readLines(dir, meta), positionMs, last)
            if (last) out.close()
            partNo
        }
    }

    private fun seal(dir: File, meta: Meta, lines: List<Line>, endMs: Long, last: Boolean) {
        val request = UploadRecordingRequest(
            clientRecordingId = meta.clientRecordingId,
            partNo = meta.nextPartNo,
            isLast = last,
            startedAt = meta.startedAt,
            durationSec = (endMs - meta.partStartMs).coerceAtLeast(0) / 1000,
            sentences = lines.map { Sentence(it.startMs, it.endMs, it.text) },
            // 서버는 첫 묶음 값만 저장하지만, 녹음 중에는 못 바꾸므로 묶음마다 같은 값을 보낸다.
            audioSaved = meta.audioSaved,
        )
        // 순서가 중요하다. 묶음을 먼저 쓰고 meta를 올리면, 중간에 죽어도 reconcile이 이어서 맞춘다.
        writeAtomic(File(dir, "part-${meta.nextPartNo}.json"), json.encodeToString(request))
        writeMeta(
            dir,
            meta.copy(
                nextPartNo = meta.nextPartNo + 1,
                partStartMs = endMs,
                sealedSeq = lines.lastOrNull()?.seq ?: meta.sealedSeq,
                closed = last,
                endedAtEpochMs = if (last) endedAt(dir, meta.startedAt, endMs) else meta.endedAtEpochMs,
            ),
        )
        // 비우지 못하고 죽어도 sealedSeq로 걸러지므로 문장이 두 번 올라가지 않는다.
        FileOutputStream(File(dir, CURRENT), false).close()
    }

    // 묶음을 쓴 뒤 meta를 올리기 전에 죽었으면 meta를 맞춘다.
    private fun reconcile(dir: File): Meta {
        val meta = readMeta(dir)
        val partFile = File(dir, "part-${meta.nextPartNo}.json")
        if (!partFile.exists()) return meta
        val written = json.decodeFromString(UploadRecordingRequest.serializer(), partFile.readText())
        val lines = readLines(dir, meta)
        val fixed = meta.copy(
            nextPartNo = meta.nextPartNo + 1,
            partStartMs = meta.partStartMs + written.durationSec * 1000,
            sealedSeq = lines.getOrNull(written.sentences.size - 1)?.seq ?: meta.sealedSeq,
            closed = written.isLast,
            endedAtEpochMs = if (written.isLast) {
                endedAt(dir, meta.startedAt, meta.partStartMs + written.durationSec * 1000)
            } else {
                meta.endedAtEpochMs
            },
        )
        writeMeta(dir, fixed)
        return fixed
    }

    // 마지막 줄을 쓰다 죽었으면 그 줄만 버린다.
    private fun readLines(dir: File, meta: Meta): List<Line> {
        val file = File(dir, CURRENT)
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { runCatching { json.decodeFromString<Line>(it) }.getOrNull() }
            .filter { it.seq > meta.sealedSeq }
    }

    // 녹음이 끝난 때 = 시작 시각 + 전체 길이(명세 audio.md 5장). 시작 시각을 못 읽으면 폴더 시각을 쓴다.
    private fun endedAt(dir: File, startedAt: String, endMs: Long): Long {
        val start = runCatching { OffsetDateTime.parse(startedAt).toInstant().toEpochMilli() }.getOrNull()
        return if (start != null) start + endMs else dir.lastModified()
    }

    private fun recordingDirs(): List<File> =
        (root.listFiles { f -> f.isDirectory && File(f, META).exists() } ?: emptyArray())
            .sortedBy { it.lastModified() }

    private fun partFiles(dir: File): List<File> =
        (dir.listFiles { f -> f.name.matches(PART_NAME) } ?: emptyArray())
            .sortedBy { PART_NAME.find(it.name)!!.groupValues[1].toInt() }

    private fun readMeta(dir: File): Meta = json.decodeFromString(File(dir, META).readText())

    private fun writeMeta(dir: File, meta: Meta) = writeAtomic(File(dir, META), json.encodeToString(meta))

    private fun writeAtomic(file: File, text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use {
            it.write(text.toByteArray())
            it.fd.sync()
        }
        check(tmp.renameTo(file)) { "저장 실패: ${file.name}" }
    }

    private companion object {
        const val META = "meta.json"
        const val CURRENT = "current.jsonl"
        val PART_NAME = Regex("""part-(\d+)\.json""")
        const val AUDIO_KEEP_MS = 7 * 24 * 60 * 60 * 1000L
    }
}
