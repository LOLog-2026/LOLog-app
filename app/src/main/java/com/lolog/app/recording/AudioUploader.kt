package com.lolog.app.recording

import android.util.Log
import com.lolog.app.recording.audio.AudioFiles
import java.io.IOException
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException

/**
 * 다 쓴 음성 파일을 녹음마다 번호 순서대로 올린다(명세 audio.md 3장, 6장).
 * 글 묶음과 따로 돈다. 음성이 실패해도 글 묶음은 막히지 않는다.
 *
 * - 첫 글 묶음이 아직 안 올라간 녹음(서버 녹음 ID 없음)은 건너뛴다.
 * - "Wi-Fi에서 올리기"였거나 Wi-Fi에서 시작한 녹음은 Wi-Fi일 때만 올린다.
 * - 204: 올렸으니 폰에서 지운다. 같은 번호를 다시 올리면 서버가 덮어쓰므로 재시도해도 안전하다.
 * - 네트워크 오류, 5xx, 그 밖의 예상 못 한 응답: 나중에 다시 올린다. 지금은 다른 파일도 못 올리니 여기서 멈춘다.
 * - 401: 토큰 갱신은 ApiClient가 이미 해 봤다. 다시 로그인해야 하니 멈춘다.
 * - 400·404·413: 다시 보내도 같다. 그 파일은 버리고 다음으로 간다.
 */
class AudioUploader(
    private val store: RecordingStore,
    private val send: suspend (recordingId: Long, file: AudioFiles.Pending) -> Unit,
    /** 지금 Wi-Fi(데이터 요금이 없는 연결)인지 */
    private val onWifi: () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
    private val warn: (String) -> Unit = { Log.w(TAG, it) },
) {
    /**
     * @property uploaded 이번에 올린 파일 수
     * @property retryLater 네트워크·서버 문제로 멈췄으면 true. 나중에 다시 부르면 된다.
     * @property waiting 조건(서버 녹음 ID, Wi-Fi)이 안 돼 아직 올리지 않은 파일이 남았는지
     */
    data class Result(val uploaded: Int, val retryLater: Boolean, val waiting: Boolean)

    /**
     * @param stop true를 돌려주면 다음 파일로 넘어가지 않고 멈춘다(로그아웃·탈퇴로 버리는 중).
     * @param onSending 파일 하나를 보내기 직전에 그 번호로 부른다. 화면의 "올리는 중" 표시에 쓴다.
     */
    suspend fun uploadPending(stop: () -> Boolean = { false }, onSending: (seq: Int) -> Unit = {}): Result {
        val expired = store.deleteExpiredAudio(now())
        if (expired > 0) warn("7일이 지나도 못 올린 음성 파일 ${expired}개를 지웠습니다")
        var uploaded = 0
        var waiting = false
        for (rec in store.pendingAudio()) {
            val recordingId = rec.recordingId
            if (recordingId == null || (!rec.anyNetwork && !onWifi())) {
                waiting = true
                continue
            }
            for (file in rec.files) {
                if (stop()) return Result(uploaded, retryLater = false, waiting = true)
                onSending(file.seq)
                when (val outcome = sendOne(recordingId, file)) {
                    Outcome.Sent -> {
                        store.removeAudio(rec.recordingDir, file.seq)
                        uploaded++
                    }
                    is Outcome.Drop -> {
                        warn("${file.seq}번 음성 파일을 서버가 받지 않아 지웁니다: ${outcome.reason}")
                        store.removeAudio(rec.recordingDir, file.seq)
                    }
                    Outcome.Stop -> return Result(uploaded, retryLater = false, waiting = true)
                    Outcome.Retry -> return Result(uploaded, retryLater = true, waiting = true)
                }
            }
        }
        return Result(uploaded, retryLater = false, waiting = waiting)
    }

    private sealed interface Outcome {
        data object Sent : Outcome
        data class Drop(val reason: String) : Outcome
        data object Stop : Outcome
        data object Retry : Outcome
    }

    private suspend fun sendOne(recordingId: Long, file: AudioFiles.Pending): Outcome = try {
        if (!file.file.exists()) {
            Outcome.Drop("파일 없음")
        } else {
            send(recordingId, file)
            Outcome.Sent
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        Outcome.Retry
    } catch (e: HttpException) {
        when (e.code()) {
            400, 404, 413 -> Outcome.Drop("${e.code()}")
            401 -> Outcome.Stop
            else -> Outcome.Retry
        }
    }

    private companion object {
        const val TAG = "LologRecording"
    }
}
