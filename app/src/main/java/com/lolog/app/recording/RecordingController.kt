package com.lolog.app.recording

import kotlinx.coroutines.flow.StateFlow

/*
 * ── 지원(녹음 서비스) ↔ 서현(화면) 약속 ──
 * 화면은 시작·정지만 보내고, 상태는 녹음 서비스가 state로 알려 줘요.
 *
 *   Idle ─start()→ Recording(경과 초) ─stop() 또는 3시간→ Uploading ─성공→ Uploaded(recordingId)
 *                                                            └─실패→ UploadFailed(문구)
 *
 * - 녹음 중 1시간마다 올리는 묶음은 Recording.partUpload로만 알려요. 녹음은 멈추지 않아요.
 * - 올리지 못한 묶음은 폰에 남았다가 다음 업로드(또는 다음 앱 실행) 때 같은 clientRecordingId·partNo로 다시 올라가요(FR-UP-02).
 * - UploadFailed 뒤에도 다시 start()를 부를 수 있어요. 남은 묶음은 녹음 서비스가 알아서 올려요.
 * - 로그아웃(FR-AUTH-04): stop()으로 마감해 올리고 Uploading이 끝나길 기다린 뒤 discardAll()로 못 올린 글을 지워요.
 *   탈퇴(FR-AUTH-05): 바로 discardAll()로 올리지 않고 버려요. 다음에 로그인한 사람 계정으로 앞 사람의 대화 글이 올라가지 않게요.
 *   토큰 만료로 로그아웃된 경우에는 부르지 않아요(다시 로그인하면 남은 묶음이 올라가요).
 * - 실제 구현은 지원 님 RealRecordingController(PR #3). 이 파일의 모양은 그 코드에 맞췄어요.
 *   바꿀 때는 먼저 두 사람이 정하고 앱 구현 약속 문서도 같이 고쳐요(CLAUDE.md 10번).
 */
interface RecordingController {
    val state: StateFlow<RecordingState>
    fun start()
    fun stop()

    /**
     * 녹음 중이면 올리지 않고 멈추고, 폰에 남은 아직 안 올린 글을 모두 지워요. 끝나면 state는 Idle이에요.
     * 지금 보내는 중인 묶음 하나는 끝까지 보낼 수 있으니, 토큰을 지우기 전에 불러요.
     */
    suspend fun discardAll()
}

sealed interface RecordingState {
    data object Idle : RecordingState

    /** 녹음 중. elapsedSec는 녹음 시작부터 지난 초 */
    data class Recording(
        val elapsedSec: Long,
        val partUpload: PartUpload = PartUpload.None,
    ) : RecordingState

    /** 마지막 묶음을 올리는 중 */
    data object Uploading : RecordingState

    /** 다 올림. 이 recordingId로 감지 상태를 조회해요 */
    data class Uploaded(val recordingId: Long) : RecordingState

    /** 올리지 못함. message는 화면에 그대로 보여 줘도 되는 문장 */
    data class UploadFailed(val message: String) : RecordingState
}

/** 녹음 중 1시간 묶음 업로드 상태 */
sealed interface PartUpload {
    data object None : PartUpload
    data class Sending(val partNo: Int) : PartUpload
    data class Failed(val partNo: Int) : PartUpload
}
