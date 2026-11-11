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
    /** 비밀번호 찾기 인증번호를 메일로 보내 달라고 해요. 가입 여부와 관계없이 성공해요(60초 안에 또 부르면 TOO_MANY_REQUESTS) */
    suspend fun requestPasswordResetCode(email: String)
    /** 인증번호로 새 비밀번호를 정해요. 성공해도 토큰은 받지 않아서 다시 로그인해야 해요 */
    suspend fun resetPassword(email: String, code: String, newPassword: String)
    /** 로그인한 채로 비밀번호를 바꿔요. 받은 새 토큰으로 저장한 토큰을 바꿔요 */
    suspend fun changePassword(currentPassword: String, newPassword: String)

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

    // 친구·프로필·차단 (LOLog-docs spec/friends.md)
    /** 처음 부르면 서버가 프로필을 만들어요. 닉네임을 정하기 전에는 friendCode가 null */
    suspend fun profile(): Profile
    /** 앞뒤 공백을 잘라 보내요(1~10자) */
    suspend fun updateNickname(nickname: String): Profile
    /** 프로필 캐릭터 만들기·다시 만들기. photo는 JPEG 바이트. 202면 PROCESSING (characters.md 3장) */
    suspend fun uploadProfileCharacter(photo: ByteArray): ProfileCharacter
    /** 프로필 캐릭터 지우기. 붙여 둔 카드 화자에서도 빠져요 */
    suspend fun deleteProfileCharacter()
    /**
     * 프로필 캐릭터 목소리 고르기(FR-CHAR-04, 초안). voice는 기본 화자 id, null이면 안 고름(쇼츠에서 알아서 골라요).
     * 없거나 목소리가 없는 기본 화자 id → 400 UNKNOWN_CHARACTER, 프로필 캐릭터가 없음 → 404 CHARACTER_NOT_FOUND
     */
    suspend fun setProfileCharacterVoice(voice: String?)
    /** 친구 코드는 앞뒤 공백을 빼고 대문자로 보내요. 상대가 이미 나에게 요청했으면 status가 ACCEPTED */
    suspend fun sendFriendRequest(code: String): FriendRequest
    /** 최신순 */
    suspend fun friendRequests(direction: FriendRequestDirection): List<FriendRequest>
    suspend fun acceptFriendRequest(id: Long): Friend
    /** 받은 요청 거절, 보낸 요청 취소 */
    suspend fun deleteFriendRequest(id: Long)
    /** 최근에 친구가 된 사람이 위 */
    suspend fun friends(): List<Friend>
    suspend fun removeFriend(userId: Long)
    suspend fun block(userId: Long)
    /** 최신순 */
    suspend fun blocks(): List<BlockedUser>
    suspend fun unblock(userId: Long)

    // 화자 그림 (LOLog-docs spec/characters.md)
    /** 기본 화자 24명. 썸네일 주소는 10분마다 바뀌니 캐시는 id 기준으로 해요 */
    suspend fun defaultCharacters(): List<DefaultCharacter>

    // 기기
    suspend fun registerFcmToken(token: String)
    suspend fun unregisterFcmToken(token: String)

    /** 목업에서만 쓰는 푸시 흉내. 실제 서버에서는 FCM이 대신해요. */
    val pushEvents: Flow<PushPayload>
}
