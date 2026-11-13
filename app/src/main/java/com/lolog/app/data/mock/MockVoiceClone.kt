package com.lolog.app.data.mock

import com.lolog.app.data.model.*
import com.lolog.app.data.repo.LologException

/**
 * 내 목소리 만들기 목업(시안 11/13). 명세 없음, 앱 목업에서만 동작해요.
 *  - 처음엔 NONE. 문장 5개를 다 올리고 start하면 MAKING, 약 5초 뒤 DONE(voiceUrl은 기본 화자 young-f-01의 목소리를 빌려 씀)
 *  - 문장 파일이 2KB보다 작으면 VOICE_TOO_QUIET(소리가 작아요 화면을 보려고)
 *  - delete하면 NONE
 * 안드로이드 없이 테스트하려고 따로 떼었어요. [nowMs]는 테스트에서 시간을 돌리려고 받아요.
 */
internal class MockVoiceClone(private val nowMs: () -> Long = System::currentTimeMillis) {
    private var status = VoiceCloneStatus.NONE
    private var startedAt = 0L
    private val samples = mutableSetOf<Int>()

    fun get(): VoiceClone {
        // 시간이 지나면 다 됨. 목업은 따로 일꾼 없이 볼 때 셈해요
        if (status == VoiceCloneStatus.MAKING && nowMs() - startedAt >= MAKING_MS) status = VoiceCloneStatus.DONE
        return when (status) {
            VoiceCloneStatus.DONE -> VoiceClone(status, voiceUrl = MockCharacters.voice(BORROWED_VOICE))
            else -> VoiceClone(status)
        }
    }

    fun upload(index: Int, bytes: Long) {
        if (index !in VOICE_CLONE_SENTENCES.indices) throw LologException(ErrorCodes.INVALID_REQUEST, "없는 문장이에요")
        if (bytes < QUIET_BYTES) throw LologException(ErrorCodes.VOICE_TOO_QUIET, "소리가 작아요")
        samples += index
    }

    fun start() {
        if (get().status == VoiceCloneStatus.MAKING) return
        if (samples.size < VOICE_CLONE_SENTENCES.size) throw LologException(ErrorCodes.INVALID_REQUEST, "문장을 모두 읽어 주세요")
        samples.clear()
        status = VoiceCloneStatus.MAKING
        startedAt = nowMs()
    }

    fun delete() {
        status = VoiceCloneStatus.NONE
        samples.clear()
    }

    /** 내 목소리를 고를 수 있는지(목소리 고르기 저장) */
    fun ready(): Boolean = get().status == VoiceCloneStatus.DONE

    companion object {
        const val MAKING_MS = 5_000L
        const val QUIET_BYTES = 2 * 1024L
        private const val BORROWED_VOICE = "young-f-01"
    }
}
