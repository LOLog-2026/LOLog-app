package com.lolog.app.data.repo

import com.lolog.app.data.model.*
import kotlinx.coroutines.flow.Flow

/** 서버 오류 {code, message}를 앱 안에서 들고 다니는 예외. field는 명세에 없는 제안(틀린 칸, 예: lines[2].text)이라 대개 null */
class LologException(val code: String, override val message: String = "", val field: String? = null) : Exception(message)

/**
 * 화면은 이 인터페이스만 봐요. 주고받는 건 모두 앱 모델(data/model)이고 서버 DTO는 안 보여요.
 * 목업(MockLologRepository)과 실제 서버(RemoteLologRepository)는 BuildConfig.USE_MOCK 하나로 바꿔요.
 * 실패하면 LologException을 던져요.
 */
interface LologRepository {
    val isLoggedIn: Flow<Boolean>

    // 인증
    /** 가입 인증번호를 메일로 보내 달라고 해요. 가입 여부와 관계없이 성공해요(60초 안에 또 부르면 TOO_MANY_REQUESTS) */
    suspend fun requestEmailCode(email: String)
    /** code는 메일로 받은 6자리 문자열(앞 0 유지) */
    suspend fun signup(email: String, password: String, code: String)
    suspend fun login(email: String, password: String)
    /** providerToken은 각 소셜 SDK에서 받은 토큰. 반환값은 isNewUser */
    suspend fun socialLogin(provider: SocialProvider, providerToken: String): Boolean
    suspend fun logout()
    suspend fun withdraw()
    suspend fun me(): Account
    suspend fun linkSocial(provider: SocialProvider, providerToken: String): Set<SocialProvider>

    // 녹음
    suspend fun uploadRecording(part: RecordingPart): RecordingAccepted
    suspend fun recordingStatus(recordingId: Long): RecordingProgress
    /** 앱을 다시 켰을 때 아직 "분석 중"인 가장 최근 녹음(GET /api/recordings?status=RECEIVED,DETECTING). 없으면 null */
    suspend fun analyzingRecordingId(): Long?

    // 카드
    /** 다음 쪽도 처음과 같은 sort로 불러요. 정렬을 바꾸면 cursor 없이 처음부터 */
    suspend fun cards(cursor: String? = null, size: Int = 20, sort: SortOrder = SortOrder.NEWEST): CardPage
    suspend fun card(id: Long): CardDetail
    suspend fun updateCard(id: Long, edit: CardEdit): CardDetail
    suspend fun deleteCard(id: Long)

    // 만화
    suspend fun requestComic(cardId: Long): ComicAccepted
    suspend fun comics(cursor: String? = null, size: Int = 20, sort: SortOrder = SortOrder.NEWEST): ComicPage
    suspend fun comic(id: Long): ComicDetail
    suspend fun deleteComic(id: Long)

    // 기기
    suspend fun registerFcmToken(token: String)
    suspend fun unregisterFcmToken(token: String)

    /** 목업에서만 쓰는 푸시 흉내. 실제 서버에서는 FCM이 대신해요. */
    val pushEvents: Flow<PushPayload>
}
