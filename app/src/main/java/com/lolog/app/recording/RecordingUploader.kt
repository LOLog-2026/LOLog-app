package com.lolog.app.recording

import com.lolog.app.data.remote.UploadRecordingRequest
import com.lolog.app.data.remote.UploadRecordingResponse
import java.io.IOException
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException

/**
 * 쌓인 묶음을 순서대로 올린다. 재시도해도 서버는 같은 묶음을 한 번만 처리한다.
 * 네트워크 오류와 5xx는 나중에 다시 올리고, 그 밖의 4xx는 다시 보내도 같으므로 폰에 남겨 두고 알린다.
 */
class RecordingUploader(
    private val store: RecordingStore,
    private val send: suspend (UploadRecordingRequest) -> UploadRecordingResponse,
) {
    sealed interface Result {
        /** 남은 묶음을 다 올렸다. 마지막으로 받은 서버 녹음 ID가 함께 온다. */
        data class Done(val lastRecordingId: Long?) : Result
        data class Failed(val partNo: Int, val message: String, val retryable: Boolean) : Result
    }

    suspend fun uploadPending(onSending: (partNo: Int) -> Unit = {}): Result {
        var lastRecordingId: Long? = null
        for (part in store.pendingParts()) {
            onSending(part.request.partNo)
            try {
                lastRecordingId = send(part.request).recordingId
                store.markUploaded(part)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                return Result.Failed(part.request.partNo, "네트워크 오류로 올리지 못했어요.", retryable = true)
            } catch (e: HttpException) {
                val retryable = e.code() >= 500 || e.code() == 401
                return Result.Failed(part.request.partNo, "서버가 받지 못했어요 (${e.code()}).", retryable)
            }
        }
        return Result.Done(lastRecordingId)
    }
}
