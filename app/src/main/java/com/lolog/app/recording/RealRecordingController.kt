package com.lolog.app.recording

import android.app.NotificationManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import com.lolog.app.data.remote.UploadRecordingRequest
import com.lolog.app.data.remote.UploadRecordingResponse
import com.lolog.app.notification.NotificationChannels
import com.lolog.app.recording.audio.AudioFiles
import com.lolog.app.recording.audio.AudioRecorder
import com.lolog.app.recording.audio.MediaCodecAacEncoder
import com.lolog.app.recording.audio.PcmEncoder
import com.lolog.app.recording.stt.AudioSink
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
import kotlinx.coroutines.withContext

/**
 * 녹음 서비스의 실제 구현. 화면은 RecordingController만 안다.
 * 대화 글은 로그에 남기지 않는다.
 *
 * 음성 저장(명세 audio.md)을 켜면 글자 변환에 넣는 소리를 AAC로 압축해 10분 단위 파일로 쓴다.
 * 글 묶음을 올린 다음에 그때까지 다 쓴 음성 파일을 올린다. 음성은 따로 다시 시도하고 글 묶음을 막지 않는다.
 */
class RealRecordingController(
    context: Context,
    send: suspend (UploadRecordingRequest) -> UploadRecordingResponse,
    sendAudio: suspend (recordingId: Long, file: AudioFiles.Pending) -> Unit,
    private val partIntervalMs: Long = 60 * 60 * 1000L,
    private val maxDurationMs: Long = 3 * 60 * 60 * 1000L,
    private val sourceFactory: (onSilenced: () -> Unit) -> PcmSource = { MicPcmSource(it) },
    private val encoderFactory: (onFrame: (ByteArray, Int) -> Unit) -> PcmEncoder = { MediaCodecAacEncoder(it) },
) : RecordingController {
    private val app = context.applicationContext
    private val store = RecordingStore(File(app.filesDir, "recordings"))
    private val uploader = RecordingUploader(store, send)
    private val audioUploader = AudioUploader(store, sendAudio, onWifi = { Networks.onWifi(app) })
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val uploadLock = Mutex()
    // 음성 올리기는 글 묶음과 따로 잠근다. 음성이 오래 걸려도 글 묶음은 기다리지 않는다.
    private val audioLock = Mutex()
    private var audioRetryJob: Job? = null
    @Volatile private var audioRetryMs = AUDIO_RETRY_FIRST_MS
    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)

    @Volatile private var engine: SttEngine? = null
    @Volatile private var recording: RecordingStore.OpenRecording? = null
    private var tickerJob: Job? = null
    @Volatile private var endedByLimit = false
    // 로그아웃·탈퇴로 버리는 중. 이때는 마감·업로드를 하지 않는다.
    @Volatile private var discarding = false

    override val state: StateFlow<RecordingState> = _state.asStateFlow()
    private val _audioUpload = MutableStateFlow<AudioUpload>(AudioUpload.None)
    override val audioUpload: StateFlow<AudioUpload> = _audioUpload.asStateFlow()

    init {
        // 지난번에 앱이 죽어 남은 글을 마감해서 올린다.
        scope.launch {
            store.closeAbandoned()
            uploadQuietly()
        }
        watchNetwork()
    }

    @Synchronized
    override fun start(audio: AudioSave) {
        if (engine != null) return
        // API 명세의 예처럼 초까지 늘 적는다(OffsetDateTime.toString은 0초면 초를 뺀다).
        val startedAt = OffsetDateTime.now().format(STARTED_AT)
        val saveAudio = audio != AudioSave.OFF
        val open = store.open(startedAt, audioSaved = saveAudio, audioAnyNetwork = audio == AudioSave.ANY_NETWORK)
        recording = open
        endedByLimit = false
        active = this
        RecordingService.start(app, saveAudio)
        val sink = if (saveAudio) audioSinkFor(open) else null
        val created = try {
            SttEngine(
                assets = app.assets,
                source = sourceFactory { stop() },
                onSegment = { segment ->
                    open.append(segment.startMs, segment.endMs, segment.text)
                    Log.i(TAG, "구간 ${segment.startMs}~${segment.endMs}ms, 인식 ${segment.decodeMs}ms, ${segment.text.length}자")
                },
                onFinished = { error -> onEngineFinished(error) },
                audioSink = sink,
            )
        } catch (e: RuntimeException) {
            Log.e(TAG, "입력을 열지 못했습니다", e)
            sink?.close()
            finishUpload(0, "마이크를 열지 못했어요. 다른 앱이 마이크를 쓰고 있는지 확인해 주세요.")
            return
        }
        engine = created
        created.start()
        startTicker(created, saveAudio)
    }

    // 인코더를 열지 못하면 이 녹음은 글만 남긴다. 서버에는 audioSaved=true로 가지만 음성 파일이 없을 뿐이다.
    private fun audioSinkFor(open: RecordingStore.OpenRecording): AudioSink? = try {
        AudioRecorder(open.audioWriter(), encoderFactory, onError = { Log.e(TAG, "음성 파일을 쓰지 못했습니다", it) })
    } catch (e: Exception) {
        Log.e(TAG, "음성 인코더를 열지 못했습니다", e)
        null
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
            audioRetryJob?.cancel()
            // 녹음 중이었거나 올리는 중이면 엔진이 멈추고 마감이 끝날 때까지 기다린다(버리는 중이라 재시도는 하지 않는다).
            state.first { it !is RecordingState.Recording && it !is RecordingState.Uploading }
            // 지금 보내는 음성 파일 하나는 끝까지 보낸 뒤 멈춘다.
            uploadLock.withLock { audioLock.withLock { store.deleteAll() } }
            _state.value = RecordingState.Idle
            Log.i(TAG, "로그아웃으로 남은 녹음을 지웠습니다")
        } finally {
            discarding = false
        }
    }

    override suspend fun prepareLogout() {
        // 녹음 중이면 마감해 글을 올린다. 올리지 못하면 몇 번 더 해 본 뒤 UploadFailed가 된다.
        stop()
        state.first { it !is RecordingState.Recording && it !is RecordingState.Uploading }
        // 올릴 수 있는 음성(Wi-Fi이거나 "지금 올리기")은 한 번 더 올려 본다. 다시 시도는 하지 않는다.
        audioRetryJob?.cancel()
        audioLock.withLock { sendAudioFiles(stop = { false }) }
        // 그래도 남은 글·음성은 다음에 로그인한 계정으로 올라가지 않게 지운다(audio.md "로그아웃할 때").
        discardAll()
    }

    override suspend fun hasUnsent(): Boolean = withContext(Dispatchers.IO) { store.hasUnsent() }

    private fun startTicker(current: SttEngine, audioSaved: Boolean) {
        val startedAt = SystemClock.elapsedRealtime()
        var nextPartAt = partIntervalMs
        _state.value = RecordingState.Recording(0, audioSaved = audioSaved)
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
        // 글 묶음 다음에 그때까지 다 쓴 음성 파일을 올린다.
        launchAudioUpload()
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
            // 화면은 음성 업로드를 기다리지 않는다. 글 결과를 바로 알리고 음성은 뒤에서 올린다.
            if (!discarding) launchAudioUpload()
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

    private suspend fun uploadQuietly() {
        uploadLock.withLock {
            val result = uploader.uploadPending()
            result.failures.forEach { (_, f) -> Log.w(TAG, "남은 묶음을 올리지 못했습니다: ${f.partNo}번") }
        }
        launchAudioUpload()
    }

    // audioLock을 잡은 채로 부른다. 보내는 동안 화면에 몇 번 파일을 올리는지 알린다.
    private suspend fun sendAudioFiles(stop: () -> Boolean): AudioUploader.Result = try {
        audioUploader.uploadPending(stop = stop, onSending = { _audioUpload.value = AudioUpload.Sending(it) })
    } finally {
        _audioUpload.value = AudioUpload.None
    }

    private fun launchAudioUpload() {
        scope.launch { uploadAudio() }
    }

    /**
     * 다 쓴 음성 파일을 올린다. 이미 올리는 중이면 그쪽이 마저 올리므로 그냥 돌아간다.
     * 네트워크·서버 문제면 간격을 늘려 가며 앱이 살아 있는 동안(화면에 없어도) 다시 시도한다.
     * 앱이 완전히 꺼지면 다음에 앱을 열 때 init에서 이어서 올린다.
     */
    private suspend fun uploadAudio() {
        if (discarding || !audioLock.tryLock()) return
        val result = try {
            sendAudioFiles(stop = { discarding })
        } finally {
            audioLock.unlock()
        }
        if (result.uploaded > 0) Log.i(TAG, "음성 파일 ${result.uploaded}개를 올렸습니다")
        if (result.retryLater && !discarding) {
            if (audioRetryJob?.isActive == true) return
            val wait = audioRetryMs
            audioRetryMs = (wait * 2).coerceAtMost(AUDIO_RETRY_MAX_MS)
            audioRetryJob = scope.launch {
                delay(wait)
                uploadAudio()
            }
        } else if (!result.retryLater) {
            audioRetryMs = AUDIO_RETRY_FIRST_MS
        }
    }

    // 인터넷이 다시 이어지면 남은 묶음을 올린다(FR-UP-02). 녹음 중이면 다음 묶음 때 같이 올라가므로 글은 건너뛴다.
    // Wi-Fi가 되면 기다리던 음성을 올린다. 녹음 중이어도 다 쓴 파일은 올린다.
    private fun watchNetwork() {
        val manager = app.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching {
            manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                @Volatile private var wasWifi = false

                override fun onAvailable(network: Network) {
                    if (engine != null) {
                        launchAudioUpload()
                        return
                    }
                    scope.launch { uploadQuietly() }
                }

                // 신호 세기 등으로 자주 불리므로 Wi-Fi가 새로 됐을 때만 깨운다.
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                    if (wifi && !wasWifi) launchAudioUpload()
                    wasWifi = wifi
                }

                override fun onLost(network: Network) {
                    wasWifi = false
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
        private const val AUDIO_RETRY_FIRST_MS = 30_000L
        private const val AUDIO_RETRY_MAX_MS = 15 * 60 * 1000L
        private val STARTED_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")

        // 알림의 끝내기 버튼이 찾아오는 곳.
        @Volatile var active: RealRecordingController? = null
    }
}
