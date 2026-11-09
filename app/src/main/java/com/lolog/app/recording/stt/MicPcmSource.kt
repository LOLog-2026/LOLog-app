package com.lolog.app.recording.stt

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * 마이크 입력. 전화 등으로 다른 앱에 마이크를 뺏기면 onSilenced를 부른다.
 * 권한은 화면이 미리 받아 둔다.
 */
@SuppressLint("MissingPermission")
class MicPcmSource(private val onSilenced: () -> Unit) : PcmSource {
    private val record: AudioRecord
    private val handler = Handler(Looper.getMainLooper())
    private var callback: AudioManager.AudioRecordingCallback? = null
    @Volatile private var stopped = false

    init {
        val minBytes = AudioRecord.getMinBufferSize(
            SttEngine.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minBytes > 0) { "16kHz 모노 녹음을 지원하지 않음: $minBytes" }
        record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SttEngine.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBytes, SttEngine.SAMPLE_RATE),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            error("마이크를 열 수 없음")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            callback = object : AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                    val mine = configs.firstOrNull { it.clientAudioSessionId == record.audioSessionId }
                    if (mine?.isClientSilenced == true) onSilenced()
                }
            }.also { record.registerAudioRecordingCallback({ handler.post(it) }, it) }
        }
        record.startRecording()
    }

    override fun read(buffer: ShortArray): Int = if (stopped) 0 else record.read(buffer, 0, buffer.size)

    @Synchronized
    override fun stop() {
        if (stopped) return
        stopped = true
        record.stop()
    }

    override fun release() {
        stop()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            callback?.let { record.unregisterAudioRecordingCallback(it) }
        }
        record.release()
    }
}
