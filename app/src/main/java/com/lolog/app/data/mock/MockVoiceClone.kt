package com.lolog.app.data.mock

import com.lolog.app.data.model.*
import com.lolog.app.data.repo.LologException

/**
 * 내 목소리 목업(LOLog-docs PR #70 병합 전). 서버가 아직 없어 앱 목업에서만 동작해요.
 *  - 처음엔 null(만든 적 없음). 녹음을 올리면 PROCESSING, 약 5초 뒤 READY(previewUrl은 기본 화자 young-f-01의 목소리를 빌려 씀)
 *  - 목업 전용: 녹음 파일이 72KB(96kbps로 약 6초)보다 작으면 5초 뒤 FAILED(실패 칸을 보려고)
 *  - 만드는 중에 또 올리면 409 VOICE_CLONE_IN_PROGRESS, 4MB를 넘으면 413 PAYLOAD_TOO_LARGE
 *  - 친구 쇼츠 동의만 바꾸기: 없으면 404 VOICE_CLONE_NOT_FOUND
 *  - delete하면 null
 * 안드로이드 없이 테스트하려고 따로 떼었어요. [nowMs]는 테스트에서 시간을 돌리려고 받아요.
 */
internal class MockVoiceClone(private val nowMs: () -> Long = System::currentTimeMillis) {
    private var clone: VoiceClone? = null
    private var startedAt = 0L
    private var willFail = false

    fun get(): VoiceClone? {
        // 시간이 지나면 다 됨. 목업은 따로 일꾼 없이 볼 때 셈해요
        val c = clone
        if (c?.status == VoiceCloneStatus.PROCESSING && nowMs() - startedAt >= MAKING_MS) {
            clone = if (willFail) c.copy(status = VoiceCloneStatus.FAILED, failReason = "목업: 녹음이 짧아요")
            else c.copy(status = VoiceCloneStatus.READY, previewUrl = MockCharacters.voice(BORROWED_VOICE))
        }
        return clone
    }

    fun upload(bytes: Long, text: String, shareWithFriends: Boolean) {
        if (get()?.status == VoiceCloneStatus.PROCESSING) throw LologException(ErrorCodes.VOICE_CLONE_IN_PROGRESS, "내 목소리를 만드는 중이에요")
        if (bytes <= 0) throw LologException(ErrorCodes.INVALID_REQUEST, "녹음이 없어요")
        if (bytes > MAX_BYTES) throw LologException(ErrorCodes.PAYLOAD_TOO_LARGE, "녹음이 너무 커요")
        if (text.isBlank()) throw LologException(ErrorCodes.INVALID_REQUEST, "읽은 문장이 없어요")
        willFail = bytes < FAIL_BELOW_BYTES
        // 다시 녹음하면 미리 듣기는 새로 만들어요
        clone = VoiceClone(VoiceCloneStatus.PROCESSING, shareWithFriends = shareWithFriends)
        startedAt = nowMs()
    }

    fun setShare(shareWithFriends: Boolean) {
        val c = get() ?: throw LologException(ErrorCodes.VOICE_CLONE_NOT_FOUND, "내 목소리가 없어요")
        clone = c.copy(shareWithFriends = shareWithFriends)
    }

    fun delete() {
        clone = null
    }

    /** 내 목소리를 고를 수 있는지(목소리 고르기 저장) */
    fun ready(): Boolean = get()?.status == VoiceCloneStatus.READY

    companion object {
        const val MAKING_MS = 5_000L
        const val MAX_BYTES = 4L * 1024 * 1024
        /** 목업 전용: 이보다 작은 녹음은 FAILED */
        const val FAIL_BELOW_BYTES = 72_000L
        private const val BORROWED_VOICE = "young-f-01"
    }
}
