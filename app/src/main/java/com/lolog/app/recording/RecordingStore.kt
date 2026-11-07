package com.lolog.app.recording

import com.lolog.app.data.remote.Sentence
import com.lolog.app.data.remote.UploadRecordingRequest
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 녹음 글을 폰에 쌓는다. 앱이 죽어도 글이 남도록 문장마다 디스크에 확정한다. 음성은 저장하지 않는다.
 *
 * 녹음마다 폴더 하나다.
 * - meta.json: 녹음 ID, 시작 시각, 다음 묶음 번호
 * - current.jsonl: 아직 묶지 않은 문장, 한 줄에 하나
 * - part-N.json: 올릴 묶음. 서버가 받으면 지운다.
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
    )

    @Serializable
    private data class Line(val seq: Long, val startMs: Long, val endMs: Long, val text: String)

    data class PendingPart(val recordingDir: File, val file: File, val request: UploadRecordingRequest)

    private val json = Json { ignoreUnknownKeys = true }

    /** 새 녹음을 시작한다. */
    @Synchronized
    fun open(startedAt: String): OpenRecording {
        val id = UUID.randomUUID().toString()
        val dir = File(root, id).apply { mkdirs() }
        writeMeta(dir, Meta(clientRecordingId = id, startedAt = startedAt))
        return OpenRecording(dir)
    }

    /** 앱이 죽어서 닫히지 못한 녹음을 쌓인 문장까지로 마감한다. 녹음 중이 아닐 때만 부른다. */
    @Synchronized
    fun closeAbandoned() {
        recordingDirs().forEach { dir ->
            val meta = runCatching { reconcile(dir) }.getOrNull() ?: return@forEach
            if (!meta.closed) {
                val lines = readLines(dir, meta)
                val endMs = maxOf(lines.lastOrNull()?.endMs ?: 0, meta.partStartMs)
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

    /** 서버가 받은 묶음을 지운다. 끝난 녹음이면 폴더까지 지운다. */
    @Synchronized
    fun markUploaded(part: PendingPart) {
        part.file.delete()
        val meta = readMeta(part.recordingDir)
        if (meta.closed && partFiles(part.recordingDir).isEmpty()) {
            part.recordingDir.deleteRecursively()
        }
    }

    inner class OpenRecording internal constructor(private val dir: File) {
        private var seq = readMeta(dir).sealedSeq
        private val out = FileOutputStream(File(dir, CURRENT), true)

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
    }
}
