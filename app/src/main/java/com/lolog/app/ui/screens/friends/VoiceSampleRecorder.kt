package com.lolog.app.ui.screens.friends

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File

/*
 * 내 목소리 만들기 문장 녹음 도우미. 대화 녹음(recording/)과 따로, 화면 안에서만 써요.
 *  - MediaRecorder로 AAC(m4a) 한 파일. 파일은 앱 cacheDir/voice_clone 안에만 둬요
 *  - 녹음 파일은 만들기를 시작했거나 화면을 나가면 지워요(VoiceCloneViewModel)
 */

/** 문장 하나 최대 길이. 넘으면 저절로 끝나요(임시, 팀 확인 필요) */
const val VOICE_SAMPLE_MAX_MS = 15_000L

/** 이보다 짧으면 "조금 더 길게 읽어 주세요" */
const val VOICE_SAMPLE_MIN_MS = 1_000L

/** 녹음 중 가장 큰 소리(maxAmplitude, 0~32767)가 이보다 작으면 "소리가 작아요"(임시 값) */
const val VOICE_SAMPLE_QUIET_AMPLITUDE = 1_500

/** 문장 녹음 판정 */
enum class TakeCheck { TOO_SHORT, TOO_QUIET, OK }

/** 녹음 길이와 가장 큰 소리로 판정해요. 짧은 것을 먼저 봐요 */
internal fun checkTake(durationMs: Long, peakAmplitude: Int): TakeCheck = when {
    durationMs < VOICE_SAMPLE_MIN_MS -> TakeCheck.TOO_SHORT
    peakAmplitude < VOICE_SAMPLE_QUIET_AMPLITUDE -> TakeCheck.TOO_QUIET
    else -> TakeCheck.OK
}

/** 녹음 파일을 두는 곳 */
internal fun voiceCloneDir(context: Context) = File(context.cacheDir, "voice_clone")

internal class VoiceSampleRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null

    /** 녹음 시작. 실패하면 false */
    fun start(file: File): Boolean {
        stop()
        file.parentFile?.mkdirs()
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(44_100)
            r.setAudioEncodingBitRate(96_000)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            true
        } catch (e: Exception) {
            Log.w("LologVoiceClone", "녹음을 시작하지 못했어요", e)
            r.release()
            false
        }
    }

    /** 지난번 부른 뒤로 가장 큰 소리(0~32767) */
    fun amplitude(): Int = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)

    /** 녹음 끝. 너무 짧아 파일이 안 생기면 false */
    fun stop(): Boolean {
        val r = recorder ?: return false
        recorder = null
        val ok = runCatching { r.stop() }.isSuccess
        r.release()
        return ok
    }
}
