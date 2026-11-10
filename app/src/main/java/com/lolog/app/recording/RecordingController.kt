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
 * - 음성 저장(audio.md): start(audio)로 이 녹음의 음성 저장·올리기 방식을 알려요. 녹음 중에는 바꿀 수 없어요.
 *   음성 파일은 글 묶음을 올린 뒤 녹음 서비스가 따로 올려요. 화면은 음성 업로드를 기다리지 않아요.
 * - 로그아웃(FR-AUTH-04, audio.md "로그아웃할 때"): prepareLogout()이 녹음을 마감해 글을 올리고, 올릴 수 있는 음성을 올린 뒤
 *   못 올린 글·음성을 지워요. 끝나면 토큰을 지워요.
 *   탈퇴(FR-AUTH-05): 바로 discardAll()로 올리지 않고 버려요(음성 포함). 다음에 로그인한 사람 계정으로 앞 사람의 대화 글이 올라가지 않게요.
 *   토큰 만료로 로그아웃된 경우에는 부르지 않아요(다시 로그인하면 남은 묶음이 올라가요).
 * - 실제 구현은 지원 님 RealRecordingController(PR #3). 이 파일의 모양은 그 코드에 맞췄어요.
 *   바꿀 때는 먼저 두 사람이 정하고 앱 구현 약속 문서도 같이 고쳐요(CLAUDE.md 10번).
 */
interface RecordingController {
    val state: StateFlow<RecordingState>

    /** @param audio 음성 저장 설정. 화면이 "음성도 저장" 체크와 모바일 데이터 창 결과로 정해요 */
    fun start(audio: AudioSave = AudioSave.OFF)
    fun stop()

    /**
     * 녹음 중이면 올리지 않고 멈추고, 폰에 남은 아직 안 올린 글을 모두 지워요. 끝나면 state는 Idle이에요.
     * 지금 보내는 중인 묶음 하나는 끝까지 보낼 수 있으니, 토큰을 지우기 전에 불러요.
     */
    suspend fun discardAll()

    /**
     * 로그아웃 전에 불러요. 녹음 중이면 마감해 글을 올리고, 올릴 수 있는 음성(Wi-Fi이거나 "지금 올리기")을 올린 뒤
     * 그래도 못 올린 글·음성을 지워요. 끝나면 state는 Idle이에요. 토큰을 지우기 전에 불러요.
     */
    suspend fun prepareLogout()

    /** 아직 올리지 못한 글이나 음성이 폰에 남았는지. 로그아웃 확인 창 문구를 고를 때 써요 */
    suspend fun hasUnsent(): Boolean
}

/** 녹음 하나의 음성 저장 설정(audio.md 1장) */
enum class AudioSave {
    /** "음성도 저장" 꺼짐. 음성을 파일로 쓰지도 올리지도 않아요 */
    OFF,

    /** 저장하고 Wi-Fi에서만 올려요. Wi-Fi에서 시작했거나 모바일 데이터 창에서 "Wi-Fi에서 올리기"를 골랐을 때 */
    WIFI_ONLY,

    /** 저장하고 모바일 데이터에서도 올려요. 모바일 데이터 창에서 "지금 올리기"를 골랐을 때 */
    ANY_NETWORK,
}

sealed interface RecordingState {
    data object Idle : RecordingState

    /** 녹음 중. elapsedSec는 녹음 시작부터 지난 초. audioSaved는 이 녹음에서 음성을 저장하는지 */
    data class Recording(
        val elapsedSec: Long,
        val partUpload: PartUpload = PartUpload.None,
        val audioSaved: Boolean = false,
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
