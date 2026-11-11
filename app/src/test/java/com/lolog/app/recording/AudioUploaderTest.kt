package com.lolog.app.recording

import com.lolog.app.recording.audio.AudioFiles
import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.HttpException
import retrofit2.Response

/** 음성 저장(audio.md 3장·6장): 폰에 쌓기, 올리기 순서, 실패 처리, 7일 지우기 */
class AudioUploaderTest {
    @get:Rule val folder = TemporaryFolder()

    private val store by lazy { RecordingStore(folder.root) }
    private val sent = mutableListOf<Triple<Long, Int, Pair<Long, Long>>>()
    private val failWith = mutableMapOf<Int, Throwable>()
    private var wifi = true
    private var now = 0L

    private val uploader by lazy {
        AudioUploader(
            store,
            send = { id, file ->
                failWith[file.seq]?.let { throw it }
                sent += Triple(id, file.seq, file.startMs to file.endMs)
            },
            onWifi = { wifi },
            now = { now },
            warn = {},
        )
    }

    private val frame = ByteArray(10)

    /** 음성 파일 files개(마지막은 조각 3개)를 쓰고 끝낸 녹음. 아직 글 묶음은 안 올렸다. */
    private fun recording(files: Int, anyNetwork: Boolean = false, startedAt: String = "2026-11-10T12:00:00+09:00"): RecordingStore.OpenRecording {
        val rec = store.open(startedAt, audioSaved = true, audioAnyNetwork = anyNetwork)
        val writer = rec.audioWriter()
        repeat((files - 1) * AudioFiles.FRAMES_PER_FILE + 3) { writer.writeFrame(frame) }
        writer.close()
        rec.sealPart((files - 1) * AudioFiles.FILE_MS + 3 * 64, last = true)
        return rec
    }

    private fun uploadText(id: Long) = store.pendingParts().forEach { store.markUploaded(it, id) }

    private fun http(code: Int) = HttpException(
        Response.error<Any>(code, """{"code":"X","message":"m","field":null}""".toResponseBody("application/json".toMediaType())),
    )

    @Test
    fun 묶음마다_같은_audioSaved를_보낸다() {
        val rec = store.open("t", audioSaved = true)
        rec.sealPart(1000, last = false)
        rec.sealPart(2000, last = true)
        assertEquals(listOf(true, true), store.pendingParts().map { it.request.audioSaved })
        store.open("t").sealPart(1000, last = true)
        assertEquals(1, store.pendingParts().count { !it.request.audioSaved })
    }

    @Test
    fun 첫_글_묶음을_올리기_전에는_음성을_올리지_않는다() = runBlocking {
        recording(files = 1)
        val result = uploader.uploadPending()
        assertTrue(sent.isEmpty())
        assertTrue(result.waiting)
    }

    @Test
    fun 글_묶음을_올린_뒤_번호_순서대로_올리고_폴더를_지운다() = runBlocking {
        recording(files = 2)
        uploadText(77)
        // 글은 다 올렸지만 음성이 남아 폴더는 남는다.
        assertEquals(1, folder.root.listFiles()!!.size)

        val result = uploader.uploadPending()
        assertEquals(listOf(77L to 1, 77L to 2), sent.map { it.first to it.second })
        assertEquals(0L to 600_000L, sent[0].third)
        assertEquals(600_000L to 600_000L + 3 * 64, sent[1].third)
        assertEquals(2, result.uploaded)
        assertFalse(result.waiting)
        assertEquals(0, folder.root.listFiles()!!.size)
    }

    @Test
    fun 보내기_직전마다_파일_번호를_알린다() = runBlocking {
        recording(files = 2)
        uploadText(1)
        val seen = mutableListOf<Int>()
        failWith[2] = IOException()
        uploader.uploadPending(onSending = { seen += it })
        // 실패한 파일도 보내 보기는 했으므로 알린다
        assertEquals(listOf(1, 2), seen)
    }

    @Test
    fun Wi_Fi에서_올리기는_Wi_Fi가_될_때까지_둔다() = runBlocking {
        recording(files = 1)
        uploadText(1)
        wifi = false
        assertTrue(uploader.uploadPending().waiting)
        assertTrue(sent.isEmpty())
        wifi = true
        assertEquals(1, uploader.uploadPending().uploaded)
    }

    @Test
    fun 지금_올리기는_모바일_데이터에서도_올린다() = runBlocking {
        recording(files = 1, anyNetwork = true)
        uploadText(1)
        wifi = false
        assertEquals(1, uploader.uploadPending().uploaded)
    }

    @Test
    fun 네트워크_오류와_5xx는_멈추고_파일을_남긴다() = runBlocking {
        recording(files = 2)
        uploadText(1)
        failWith[1] = IOException()
        val r1 = uploader.uploadPending()
        assertTrue(r1.retryLater)
        assertEquals(2, store.pendingAudio().single().files.size)

        failWith[1] = http(503)
        assertTrue(uploader.uploadPending().retryLater)

        failWith.clear()
        assertEquals(2, uploader.uploadPending().uploaded)
    }

    @Test
    fun 서버가_받지_않는_파일_400_404_413은_버리고_다음으로_간다() = runBlocking {
        recording(files = 3)
        uploadText(1)
        failWith[1] = http(400)
        failWith[2] = http(413)
        val result = uploader.uploadPending()
        assertEquals(listOf(3), sent.map { it.second })
        assertFalse(result.retryLater)
        assertEquals(0, folder.root.listFiles()!!.size)
    }

    @Test
    fun 인증_실패_401은_멈추고_다시_시도하지_않는다() = runBlocking {
        recording(files = 1)
        uploadText(1)
        failWith[1] = http(401)
        val result = uploader.uploadPending()
        assertFalse(result.retryLater)
        assertEquals(1, store.pendingAudio().single().files.size)
    }

    @Test
    fun 녹음이_끝난_지_7일이_지나면_못_올린_음성을_지운다() = runBlocking {
        // 12:00 시작, 길이 0:00:00.192 → 끝난 때 = 시작 + 192ms
        recording(files = 1, startedAt = "2026-11-10T12:00:00+09:00")
        uploadText(1)
        wifi = false
        val ended = java.time.OffsetDateTime.parse("2026-11-10T12:00:00+09:00").toInstant().toEpochMilli() + 192
        now = ended + 7 * 24 * 3600 * 1000L - 1
        uploader.uploadPending()
        assertEquals(1, store.pendingAudio().size)
        now += 1
        uploader.uploadPending()
        assertTrue(store.pendingAudio().isEmpty())
        assertEquals(0, folder.root.listFiles()!!.size)
    }

    @Test
    fun 남은_글이나_음성이_있으면_hasUnsent() = runBlocking {
        assertFalse(store.hasUnsent())
        recording(files = 1)
        assertTrue(store.hasUnsent())
        uploadText(1)
        assertTrue(store.hasUnsent()) // 음성이 남음
        uploader.uploadPending()
        assertFalse(store.hasUnsent())
    }

    @Test
    fun 로그아웃할_때_음성만_지우면_끝난_녹음_폴더도_지운다() {
        recording(files = 1)
        uploadText(1)
        store.deleteAllAudio()
        assertEquals(0, folder.root.listFiles()!!.size)
    }

    @Test
    fun 앱이_죽은_녹음은_음성을_마감하고_마지막_묶음_길이에_음성_길이를_넣는다() {
        val rec = store.open("2026-11-10T12:00:00+09:00", audioSaved = true)
        val writer = rec.audioWriter()
        repeat(100) { writer.writeFrame(frame) } // close·sealPart 없이 죽음
        store.closeAbandoned()
        val part = store.pendingParts().single()
        assertTrue(part.request.isLast)
        assertEquals(6, part.request.durationSec) // 100 × 64ms = 6.4초
        assertEquals(100 * 64L, store.pendingAudio().single().files.single().endMs)
    }
}
