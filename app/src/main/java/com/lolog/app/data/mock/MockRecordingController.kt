package com.lolog.app.data.mock

import com.lolog.app.data.model.RecordingPart
import com.lolog.app.data.model.Sentence
import com.lolog.app.data.repo.LologRepository
import com.lolog.app.recording.AudioSave
import com.lolog.app.recording.AudioUpload
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
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * 화면 시험용 가짜 녹음. 마이크·STT 없이 시간만 흐르고, 끝내면 목업 대사를 한 묶음으로 올려요.
 * 실제 녹음은 지원 님 RealRecordingController(recording/)가 해요. 둘 다 RecordingController 약속만 지켜요.
 */
class MockRecordingController(private val repository: LologRepository) : RecordingController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    override val state: StateFlow<RecordingState> = _state.asStateFlow()
    private val _audioUpload = MutableStateFlow<AudioUpload>(AudioUpload.None)
    override val audioUpload: StateFlow<AudioUpload> = _audioUpload.asStateFlow()

    private var ticker: Job? = null
    private var uploadJob: Job? = null
    private var startedAt = ""
    private var elapsedSec = 0L

    @Synchronized
    override fun start(audio: AudioSave) {
        val now = _state.value
        if (now is RecordingState.Recording || now is RecordingState.Uploading) return
        // API 명세 예처럼 늘 초까지 적어요(OffsetDateTime.toString은 0초면 초를 빼요)
        startedAt = OffsetDateTime.now().format(STARTED_AT)
        elapsedSec = 0
        // 가짜 녹음은 음성 파일을 만들지 않아요. 화면 문구만 볼 수 있게 상태에 담아요
        _state.value = RecordingState.Recording(0, audioSaved = audio != AudioSave.OFF)
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
        val recording = _state.value as? RecordingState.Recording ?: return
        ticker?.cancel()
        _state.value = RecordingState.Uploading
        uploadJob = scope.launch {
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
            // 음성 저장을 켰으면 글 다음에 음성 파일을 올리는 척해요(10분마다 한 파일, 한 파일에 3초). 화면 표시 시험용
            if (recording.audioSaved && _state.value is RecordingState.Uploaded) {
                val files = (elapsedSec / 600 + 1).toInt()
                try {
                    for (seq in 1..files) {
                        _audioUpload.value = AudioUpload.Sending(seq)
                        delay(3_000)
                    }
                } finally {
                    _audioUpload.value = AudioUpload.None
                }
            }
        }
    }

    // 가짜 녹음은 폰에 남기는 글이 없어요. 녹음 중이면 올리지 않고 멈추기만 해요.
    override suspend fun discardAll() {
        synchronized(this) {
            ticker?.cancel()
            uploadJob?.cancel()
            _state.value = RecordingState.Idle
        }
    }

    // 가짜 녹음은 폰에 남기는 글·음성이 없어요. 녹음 중이면 마감해 올린 뒤 끝나요
    override suspend fun prepareLogout() {
        stop()
        uploadJob?.join()
        discardAll()
    }

    override suspend fun hasUnsent(): Boolean = false

    private companion object {
        const val MAX_SEC = 3L * 60 * 60 // 최대 3시간
        val STARTED_AT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")
    }
}
