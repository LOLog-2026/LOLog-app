package com.lolog.app.recording

import android.app.NotificationManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
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
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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

    @Volatile private var engine: SttEngine? = null
    @Volatile private var recording: RecordingStore.OpenRecording? = null
    private var tickerJob: Job? = null
    @Volatile private var endedByLimit = false
    // 로그아웃·탈퇴로 버리는 중. 이때는 마감·업로드를 하지 않는다.
    @Volatile private var discarding = false

    override val state: StateFlow<RecordingState> = _state.asStateFlow()

    init {
        // 지난번에 앱이 죽어 남은 글을 마감해서 올린다.
        scope.launch {
            store.closeAbandoned()
            uploadQuietly()
        }
        watchNetwork()
    }

    @Synchronized
    override fun start() {
        if (engine != null) return
        // API 명세의 예처럼 초까지 늘 적는다(OffsetDateTime.toString은 0초면 초를 뺀다).
        val startedAt = OffsetDateTime.now().format(STARTED_AT)
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
            finishUpload(0, "마이크를 열지 못했어요. 다른 앱이 마이크를 쓰고 있는지 확인해 주세요.")
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

    override suspend fun discardAll() {
        discarding = true
        try {
            stop()
            // 녹음 중이었거나 올리는 중이면 엔진이 멈추고 마감이 끝날 때까지 기다린다(버리는 중이라 재시도는 하지 않는다).
            state.first { it !is RecordingState.Recording && it !is RecordingState.Uploading }
            uploadLock.withLock { store.deleteAll() }
            _state.value = RecordingState.Idle
            Log.i(TAG, "로그아웃으로 남은 녹음을 지웠습니다")
        } finally {
            discarding = false
        }
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

    // 녹음은 멈추지 않는다. 네트워크·서버 문제면 몇 번 더 해 보고, 그래도 안 되면 다음 묶음 때 함께 올린다.
    private suspend fun uploadWhileRecording() = uploadLock.withLock {
        val current = recording?.clientRecordingId
        val result = uploadWithRetries(current, PART_RETRY_FIRST_MS) { partNo -> setPartUpload(PartUpload.Sending(partNo)) }
        val failure = current?.let { result.failureOf(it) }
        setPartUpload(if (failure != null) PartUpload.Failed(failure.partNo) else PartUpload.None)
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
        // 글자 변환이 시작도 못 했으면(모델 파일 없음 등) 빈 녹음을 서버에 올리지 않고 알린다.
        // 글이 조금이라도 있으면 그때까지 쌓인 문장으로 마감한다(기능 명세 FR-REC-01·02 예외).
        val inputError = if (error != null && (recording?.sentenceCount ?: 0) == 0L) {
            "글자 변환을 시작하지 못했어요. 앱을 다시 켜 주세요."
        } else {
            null
        }
        finishUpload(positionMs, inputError)
    }

    // 쌓인 글을 마지막 묶음으로 마감하고 올린다. inputError가 있으면 녹음이 제대로 시작하지 못한 것이다.
    private fun finishUpload(positionMs: Long, inputError: String?) {
        tickerJob?.cancel()
        val open = recording
        recording = null
        engine = null
        _state.value = RecordingState.Uploading
        scope.launch {
            if (discarding) {
                open?.discard()
                RecordingService.stop(app)
                active = null
                _state.value = RecordingState.Idle
                return@launch
            }
            // 아무것도 안 올린 빈 녹음이면 서버에 녹음을 만들지 않는다.
            val discarded = inputError != null && open?.discardIfUnsent() == true
            if (!discarded) open?.sealPart(positionMs, last = true)
            if (endedByLimit) notifyLimitReached()
            val clientId = open?.clientRecordingId
            val result = uploadLock.withLock { uploadWithRetries(clientId, FINAL_RETRY_FIRST_MS) }
            RecordingService.stop(app)
            active = null
            val recordingId = clientId?.takeIf { !discarded }?.let { result.recordingIdOf(it) }
            val failure = clientId?.let { result.failureOf(it) }
            _state.value = when {
                inputError != null -> RecordingState.UploadFailed(inputError)
                failure != null -> RecordingState.UploadFailed(failure.message)
                recordingId != null -> RecordingState.Uploaded(recordingId)
                else -> RecordingState.UploadFailed("올릴 내용이 없어요.")
            }
        }
    }

    private suspend fun uploadWithRetries(
        first: String?,
        firstWaitMs: Long,
        onSending: (Int) -> Unit = {},
    ): RecordingUploader.Result {
        var result = uploader.uploadPending(first, onSending)
        var waitMs = firstWaitMs
        // 먼저 올릴 녹음이 있으면 그 녹음만 본다. 다른 녹음 때문에 화면이 기다리지 않게 한다.
        fun shouldRetry() = if (first != null) result.failureOf(first)?.retryable == true else result.anyRetryable
        repeat(MAX_RETRIES) {
            if (discarding || !shouldRetry()) return result
            delay(waitMs)
            waitMs *= 2
            result = uploader.uploadPending(first, onSending)
        }
        return result
    }

    private suspend fun uploadQuietly() = uploadLock.withLock {
        val result = uploader.uploadPending()
        result.failures.forEach { (_, f) -> Log.w(TAG, "남은 묶음을 올리지 못했습니다: ${f.partNo}번") }
    }

    // 인터넷이 다시 이어지면 남은 묶음을 올린다(FR-UP-02). 녹음 중이면 다음 묶음 때 같이 올라가므로 건너뛴다.
    private fun watchNetwork() {
        val manager = app.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching {
            manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (engine != null) return
                    scope.launch { uploadQuietly() }
                }
            })
        }.onFailure { Log.w(TAG, "네트워크 변화를 받지 못합니다", it) }
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
        private const val FINAL_RETRY_FIRST_MS = 2000L
        private const val PART_RETRY_FIRST_MS = 30_000L
        private const val LIMIT_NOTIFICATION_ID = 1002
        private val STARTED_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")

        // 알림의 끝내기 버튼이 찾아오는 곳.
        @Volatile var active: RealRecordingController? = null
    }
}
