package com.lolog.app.recording

import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.lolog.app.data.remote.UploadRecordingRequest
import com.lolog.app.data.remote.UploadRecordingResponse
import com.lolog.app.notification.NotificationChannels
import com.lolog.app.recording.stt.MicPcmSource
import com.lolog.app.recording.stt.PcmSource
import com.lolog.app.recording.stt.SttEngine
import java.io.File
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 녹음 서비스의 실제 구현. 화면은 RecordingController만 안다.
 * 대화 글은 로그에 남기지 않는다.
 */
class RealRecordingController(
    context: Context,
    send: suspend (UploadRecordingRequest) -> UploadRecordingResponse,
    private val partIntervalMs: Long = 60 * 60 * 1000L,
    private val maxDurationMs: Long = 3 * 60 * 60 * 1000L,
    private val sourceFactory: (onSilenced: () -> Unit) -> PcmSource = { MicPcmSource(it) },
) : RecordingController {
    private val app = context.applicationContext
    private val store = RecordingStore(File(app.filesDir, "recordings"))
    private val uploader = RecordingUploader(store, send)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val uploadLock = Mutex()
    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)

    private var engine: SttEngine? = null
    private var recording: RecordingStore.OpenRecording? = null
    private var tickerJob: Job? = null
    @Volatile private var endedByLimit = false

    override val state: StateFlow<RecordingState> = _state.asStateFlow()

    init {
        // 지난번에 앱이 죽어 남은 글을 마감해서 올린다.
        scope.launch {
            store.closeAbandoned()
            uploadQuietly()
        }
    }

    @Synchronized
    override fun start() {
        if (engine != null) return
        val startedAt = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString()
        val open = store.open(startedAt)
        recording = open
        endedByLimit = false
        active = this
        RecordingService.start(app)
        val created = try {
            SttEngine(
                assets = app.assets,
                source = sourceFactory { stop() },
                onSegment = { segment ->
                    open.append(segment.startMs, segment.endMs, segment.text)
                    Log.i(TAG, "구간 ${segment.startMs}~${segment.endMs}ms, 인식 ${segment.decodeMs}ms, ${segment.text.length}자")
                },
                onFinished = { error -> onEngineFinished(error) },
            )
        } catch (e: RuntimeException) {
            Log.e(TAG, "입력을 열지 못했습니다", e)
            finishUpload(0, e.message ?: "마이크를 열지 못했어요.")
            return
        }
        engine = created
        created.start()
        startTicker(created)
    }

    @Synchronized
    override fun stop() {
        val current = engine ?: return
        tickerJob?.cancel()
        _state.value = RecordingState.Uploading
        current.stop()
    }

    private fun startTicker(current: SttEngine) {
        val startedAt = SystemClock.elapsedRealtime()
        var nextPartAt = partIntervalMs
        _state.value = RecordingState.Recording(0)
        tickerJob = scope.launch {
            while (isActive) {
                delay(1000)
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                _state.update { old ->
                    if (old is RecordingState.Recording) old.copy(elapsedSec = elapsed / 1000) else old
                }
                if (elapsed >= maxDurationMs) {
                    endedByLimit = true
                    stop()
                    return@launch
                }
                if (elapsed >= nextPartAt) {
                    nextPartAt += partIntervalMs
                    recording?.sealPart(current.positionMs, last = false)
                    launch { uploadWhileRecording() }
                }
            }
        }
    }

    // 녹음은 멈추지 않는다. 실패하면 다음 묶음 때 함께 다시 올린다.
    private suspend fun uploadWhileRecording() = uploadLock.withLock {
        val result = uploader.uploadPending { partNo -> setPartUpload(PartUpload.Sending(partNo)) }
        setPartUpload(if (result is RecordingUploader.Result.Failed) PartUpload.Failed(result.partNo) else PartUpload.None)
    }

    private fun setPartUpload(partUpload: PartUpload) {
        _state.update { old -> if (old is RecordingState.Recording) old.copy(partUpload = partUpload) else old }
    }

    @Synchronized
    private fun onEngineFinished(error: Throwable?) {
        if (error != null) Log.e(TAG, "인식 중 오류", error)
        val finished = engine
        val positionMs = finished?.positionMs ?: 0
        val droppedMs = finished?.droppedMs ?: 0
        if (droppedMs > 0) Log.w(TAG, "인식이 밀려 ${droppedMs}ms를 잃었습니다")
        finishUpload(positionMs, null)
    }

    // 쌓인 글을 마지막 묶음으로 마감하고 올린다.
    private fun finishUpload(positionMs: Long, inputError: String?) {
        tickerJob?.cancel()
        val open = recording
        recording = null
        engine = null
        _state.value = RecordingState.Uploading
        scope.launch {
            open?.sealPart(positionMs, last = true)
            if (endedByLimit) notifyLimitReached()
            val result = uploadLock.withLock { uploadWithRetries() }
            RecordingService.stop(app)
            active = null
            _state.value = when {
                result is RecordingUploader.Result.Failed -> RecordingState.UploadFailed(result.message)
                inputError != null -> RecordingState.UploadFailed(inputError)
                result is RecordingUploader.Result.Done && result.lastRecordingId != null ->
                    RecordingState.Uploaded(result.lastRecordingId)
                else -> RecordingState.UploadFailed("올릴 내용이 없어요.")
            }
        }
    }

    private suspend fun uploadWithRetries(): RecordingUploader.Result {
        var result = uploader.uploadPending()
        var waitMs = FIRST_RETRY_MS
        repeat(MAX_RETRIES) {
            val failed = result as? RecordingUploader.Result.Failed ?: return result
            if (!failed.retryable) return result
            delay(waitMs)
            waitMs *= 2
            result = uploader.uploadPending()
        }
        return result
    }

    private suspend fun uploadQuietly() = uploadLock.withLock {
        val result = uploader.uploadPending()
        if (result is RecordingUploader.Result.Failed) {
            Log.w(TAG, "남은 묶음을 올리지 못했습니다: ${result.partNo}번")
        }
    }

    private fun notifyLimitReached() {
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.notify(
            LIMIT_NOTIFICATION_ID,
            android.app.Notification.Builder(app, NotificationChannels.RECORDING_END)
                .setContentTitle("3시간이 지나 녹음을 끝냈어요")
                .setContentText("계속하려면 다시 시작해 주세요")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .build(),
        )
    }

    companion object {
        private const val TAG = "LologRecording"
        private const val MAX_RETRIES = 3
        private const val FIRST_RETRY_MS = 2000L
        private const val LIMIT_NOTIFICATION_ID = 1002

        // 알림의 끝내기 버튼이 찾아오는 곳.
        @Volatile var active: RealRecordingController? = null
    }
}
