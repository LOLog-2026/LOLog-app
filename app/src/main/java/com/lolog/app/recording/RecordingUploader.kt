package com.lolog.app.recording

import android.util.Log
import com.lolog.app.data.remote.ErrorDto
import com.lolog.app.data.remote.LologJson
import com.lolog.app.data.remote.UploadRecordingRequest
import com.lolog.app.data.remote.UploadRecordingResponse
import java.io.IOException
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException

/**
 * 쌓인 묶음을 녹음마다 partNo 순서대로 올린다. 재시도해도 서버는 같은 묶음을 한 번만 처리한다.
 *
 * 실패는 API 명세 "POST /api/recordings 규칙"의 오류 표를 따른다.
 * - 네트워크 오류, 5xx: 나중에 다시 올린다. 다른 녹음도 지금은 못 올리니 여기서 멈춘다.
 * - 401: 토큰 갱신은 ApiClient가 이미 해 봤다. 다시 로그인해야 하니 여기서 멈춘다.
 * - 409 RECORDING_CLOSED: 서버가 이미 끝난 녹음으로 안다. 다시 보내도 받지 않으므로 그 묶음은 지운다.
 * - 그 밖의 4xx(400, 409 PART_OUT_OF_ORDER, 413 등): 다시 보내도 같다. 그 녹음은 폰에 남겨 두고 다음 녹음으로 넘어간다.
 *   한 녹음이 막혀도 다른 녹음은 올라가야 하기 때문이다.
 */
class RecordingUploader(
    private val store: RecordingStore,
    private val send: suspend (UploadRecordingRequest) -> UploadRecordingResponse,
    // 시험(JVM)에서는 android.util.Log를 못 써서 바꿔 끼울 수 있게 둔다.
    private val warn: (String) -> Unit = { Log.w(TAG, it) },
) {
    data class Failure(val partNo: Int, val message: String, val retryable: Boolean)

    /**
     * @property recordingIds 이번에 올린 녹음의 clientRecordingId → 서버 recordingId
     * @property failures 올리지 못한 녹음의 clientRecordingId → 실패 내용
     * @property stoppedBy 네트워크·인증 문제로 중간에 멈췄으면 그 실패. 뒤의 녹음은 시도하지 않았다.
     */
    data class Result(
        val recordingIds: Map<String, Long>,
        val failures: Map<String, Failure>,
        val stoppedBy: Failure?,
    ) {
        /** 이 녹음이 다 올라갔으면 서버 recordingId, 아니면 null */
        fun recordingIdOf(clientRecordingId: String): Long? =
            recordingIds[clientRecordingId]?.takeIf { clientRecordingId !in failures }

        /** 이 녹음을 다 올리지 못했으면 그 이유. 시도하지 못했으면 멈춘 이유 */
        fun failureOf(clientRecordingId: String): Failure? =
            failures[clientRecordingId] ?: stoppedBy?.takeIf { clientRecordingId !in recordingIds }

        val anyRetryable: Boolean get() = stoppedBy?.retryable == true
    }

    /**
     * @param first 먼저 올릴 녹음. 방금 끝낸 녹음을 앞에 두어 화면이 빨리 결과를 받게 한다.
     */
    suspend fun uploadPending(first: String? = null, onSending: (partNo: Int) -> Unit = {}): Result {
        val ids = mutableMapOf<String, Long>()
        val failures = mutableMapOf<String, Failure>()
        val byRecording = store.pendingParts().groupBy { it.request.clientRecordingId }
        val order = byRecording.keys.sortedBy { if (it == first) 0 else 1 }
        for (clientId in order) {
            for (part in byRecording.getValue(clientId)) {
                val partNo = part.request.partNo
                onSending(partNo)
                try {
                    val recordingId = send(part.request).recordingId
                    ids[clientId] = recordingId
                    // 음성은 이 서버 녹음 ID로 올리므로 적어 둔다.
                    store.markUploaded(part, recordingId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    val failure = Failure(partNo, "인터넷 연결이 불안정해서 올리지 못했어요.", retryable = true)
                    failures[clientId] = failure
                    return Result(ids, failures, stoppedBy = failure)
                } catch (e: HttpException) {
                    val code = errorCode(e)
                    when {
                        e.code() >= 500 -> {
                            val failure = Failure(partNo, "서버가 잠시 받지 못했어요. 다시 올려 볼게요.", retryable = true)
                            failures[clientId] = failure
                            return Result(ids, failures, stoppedBy = failure)
                        }
                        e.code() == 401 -> {
                            val failure = Failure(partNo, "다시 로그인한 뒤 올릴게요.", retryable = false)
                            failures[clientId] = failure
                            return Result(ids, failures, stoppedBy = failure)
                        }
                        code == "RECORDING_CLOSED" -> {
                            // 서버에는 이미 마지막 묶음까지 있다. 이 묶음은 받지 않으므로 버리고 다음으로 간다.
                            warn("이미 끝난 녹음의 ${partNo}번 묶음을 지웁니다")
                            store.markUploaded(part)
                        }
                        else -> {
                            warn("${partNo}번 묶음을 서버가 받지 않았습니다: ${e.code()} $code")
                            failures[clientId] = Failure(partNo, "서버가 받지 못했어요 (${e.code()}).", retryable = false)
                            break // 이 녹음의 뒤 묶음은 순서 때문에 못 올린다. 다음 녹음으로 간다.
                        }
                    }
                }
            }
        }
        return Result(ids, failures, stoppedBy = null)
    }

    // 오류 본문 { code, message } 에서 code만 읽는다. 대화 글은 들어 있지 않다.
    private fun errorCode(e: HttpException): String? = runCatching {
        val body = e.response()?.errorBody()?.string() ?: return null
        LologJson.decodeFromString(ErrorDto.serializer(), body).code
    }.getOrNull()

    private companion object {
        const val TAG = "LologRecording"
    }
}
