package com.lolog.app.recording

import com.lolog.app.data.remote.UploadRecordingRequest
import com.lolog.app.data.remote.UploadRecordingResponse
import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.HttpException
import retrofit2.Response

class RecordingUploaderTest {
    @get:Rule val folder = TemporaryFolder()

    private val store by lazy { RecordingStore(folder.root) }

    // 서버 대신 쓴다. 받은 요청을 적어 두고, 미리 정한 실패를 던진다.
    private val sent = mutableListOf<UploadRecordingRequest>()
    private val failWith = mutableMapOf<Pair<String, Int>, Throwable>()
    private val serverIds = mutableMapOf<String, Long>()

    private val uploader by lazy {
        RecordingUploader(store, send = { req ->
            failWith[req.clientRecordingId to req.partNo]?.let { throw it }
            sent += req
            UploadRecordingResponse(serverIds.getOrPut(req.clientRecordingId) { serverIds.size + 1L }, "RECEIVED")
        }, warn = {})
    }

    private fun recordingWithParts(parts: Int): String {
        val rec = store.open("2026-11-10T12:30:00+09:00")
        repeat(parts) { i -> rec.sealPart((i + 1) * 1000L, last = i == parts - 1) }
        return rec.clientRecordingId
    }

    private fun http(code: Int, errorCode: String) = HttpException(
        Response.error<Any>(code, """{"code":"$errorCode","message":"m","field":null}""".toResponseBody("application/json".toMediaType())),
    )

    @Test
    fun 녹음마다_묶음을_순서대로_올리고_서버_ID를_돌려준다() = runBlocking {
        val a = recordingWithParts(2)
        val result = uploader.uploadPending(first = a)
        assertEquals(listOf(1, 2), sent.map { it.partNo })
        assertEquals(1L, result.recordingIdOf(a))
        assertNull(result.failureOf(a))
        assertTrue(store.pendingParts().isEmpty())
    }

    @Test
    fun 먼저_올릴_녹음을_앞에_둔다() = runBlocking {
        val old = recordingWithParts(1)
        val now = recordingWithParts(1)
        uploader.uploadPending(first = now)
        assertEquals(listOf(now, old), sent.map { it.clientRecordingId })
    }

    @Test
    fun 네트워크_오류면_멈추고_다시_올릴_수_있게_남긴다() = runBlocking {
        val a = recordingWithParts(2)
        failWith[a to 2] = IOException("끊김")
        val result = uploader.uploadPending(first = a)
        assertEquals(listOf(1), sent.map { it.partNo })
        assertTrue(result.failureOf(a)!!.retryable)
        assertTrue(result.anyRetryable)
        assertEquals(listOf(2), store.pendingParts().map { it.request.partNo })
    }

    @Test
    fun 서버_5xx도_다시_올린다() = runBlocking {
        val a = recordingWithParts(1)
        failWith[a to 1] = http(503, "INTERNAL_ERROR")
        assertTrue(uploader.uploadPending(first = a).failureOf(a)!!.retryable)
    }

    @Test
    fun 인증_실패면_다시_시도하지_않고_멈춘다() = runBlocking {
        val a = recordingWithParts(1)
        val b = recordingWithParts(1)
        failWith[a to 1] = http(401, "UNAUTHORIZED")
        val result = uploader.uploadPending(first = a)
        assertFalse(result.failureOf(a)!!.retryable)
        // 다시 로그인해야 하므로 뒤 녹음도 지금은 올리지 않는다.
        assertTrue(sent.isEmpty())
        assertTrue(result.failureOf(b) != null)
        assertEquals(2, store.pendingParts().size)
    }

    @Test
    fun 받지_않는_묶음이_있어도_다른_녹음은_올린다() = runBlocking {
        val stuck = recordingWithParts(2)
        val next = recordingWithParts(1)
        failWith[stuck to 1] = http(400, "INVALID_REQUEST")
        val result = uploader.uploadPending(first = stuck)
        assertFalse(result.failureOf(stuck)!!.retryable)
        assertEquals(listOf(next), sent.map { it.clientRecordingId })
        assertTrue(result.recordingIdOf(next) != null)
        // 막힌 녹음은 폰에 그대로 남는다(2번 묶음은 순서 때문에 시도하지 않음).
        assertEquals(listOf(1, 2), store.pendingParts().map { it.request.partNo })
    }

    @Test
    fun 이미_끝난_녹음의_묶음은_지운다() = runBlocking {
        val a = recordingWithParts(1)
        failWith[a to 1] = http(409, "RECORDING_CLOSED")
        uploader.uploadPending(first = a)
        assertTrue(store.pendingParts().isEmpty())
    }
}
