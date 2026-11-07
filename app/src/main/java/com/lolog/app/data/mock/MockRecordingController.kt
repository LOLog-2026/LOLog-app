package com.lolog.app.data.mock

import com.lolog.app.data.model.RecordingPart
import com.lolog.app.data.model.Sentence
import com.lolog.app.data.repo.LologRepository
import com.lolog.app.recording.RecordingController
import com.lolog.app.recording.RecordingState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * 화면 시험용 가짜 녹음. 마이크·STT 없이 시간만 흐르고, 끝내면 목업 대사를 한 묶음으로 올려요.
 * 실제 녹음은 지원 님 RealRecordingController(recording/)가 해요. 둘 다 RecordingController 약속만 지켜요.
 */
class MockRecordingController(private val repository: LologRepository) : RecordingController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    override val state: StateFlow<RecordingState> = _state.asStateFlow()

    private var ticker: Job? = null
    private var startedAt = ""
    private var elapsedSec = 0L

    @Synchronized
    override fun start() {
        val now = _state.value
        if (now is RecordingState.Recording || now is RecordingState.Uploading) return
        startedAt = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString()
        elapsedSec = 0
        _state.value = RecordingState.Recording(0)
        ticker = scope.launch {
            while (isActive) {
                delay(1_000)
                elapsedSec++
                if (elapsedSec >= MAX_SEC) { stop(); break }
                val old = _state.value
                if (old is RecordingState.Recording) _state.value = old.copy(elapsedSec = elapsedSec)
            }
        }
    }

    @Synchronized
    override fun stop() {
        if (_state.value !is RecordingState.Recording) return
        ticker?.cancel()
        _state.value = RecordingState.Uploading
        scope.launch {
            // 2.5초마다 한 문장이 들어온 것처럼 만들어요
            val count = (elapsedSec / 2.5).toInt().coerceAtMost(MockData.liveSentences.size)
            val sentences = (0 until count).map { i ->
                Sentence(i * 2_500L + 200, i * 2_500L + 2_300, MockData.liveSentences[i])
            }
            val part = RecordingPart(
                clientRecordingId = UUID.randomUUID().toString(),
                partNo = 1, isLast = true, startedAt = startedAt,
                durationSec = elapsedSec.toInt(), sentences = sentences,
            )
            _state.value = try {
                RecordingState.Uploaded(repository.uploadRecording(part).recordingId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RecordingState.UploadFailed("대화 텍스트를 올리지 못했어요. 인터넷 연결을 확인해 주세요")
            }
        }
    }

    private companion object {
        const val MAX_SEC = 3L * 60 * 60 // 최대 3시간
    }
}
