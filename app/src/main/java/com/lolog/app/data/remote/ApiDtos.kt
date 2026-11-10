package com.lolog.app.data.remote

import kotlinx.serialization.Serializable

/*
 * 서버 JSON 모양(DTO) — LOLog-docs spec/api-dto.md(2026-11-06) 기준이에요.
 * 백엔드와 연결할 때 바뀌는 곳은 여기와 ApiMapper.kt뿐이에요.
 *
 *  - 필드 이름이 다르면: 그 필드에 @SerialName("서버이름")만 붙여요.
 *  - 상태·종류 값이 다르면: 여기는 String 그대로 두고 ApiMapper.kt의 표만 고쳐요.
 *  - 「명세에 없음(제안)」이라고 적은 필드는 우리가 백엔드에 부탁한 것이에요.
 *    서버가 안 보내도 기본값(null)이라 앱은 그대로 돌아가요(ignoreUnknownKeys, 기본값으로 대신 계산).
 *
 * 화면 코드는 DTO를 직접 보지 않아요(domain 모델은 data/model/Models.kt).
 */

// ── 공통 ──

/** ErrorResponse { code, message }. message는 개발자용이라 화면에 그대로 띄우지 않아요 */
@Serializable
data class ErrorDto(
    val code: String = "",
    val message: String = "",
    val field: String? = null, // 400 INVALID_REQUEST일 때 틀린 칸 경로(예: lines[2].text). 다른 오류는 null
)

// ── 인증 (/auth) ──

/** LoginRequest */
@Serializable
data class EmailAuthDto(val email: String, val password: String)

/** EmailCodeRequest. 응답은 늘 204(본문 없음) */
@Serializable
data class EmailCodeDto(val email: String)

/** SignupRequest. code는 6자리 문자열이라 앞의 0을 그대로 보내요 */
@Serializable
data class SignupDto(val email: String, val password: String, val code: String)

/** SocialLoginRequest */
@Serializable
data class SocialAuthDto(val providerToken: String)

/** RefreshRequest · LogoutRequest */
@Serializable
data class RefreshDto(val refreshToken: String)

/** TokenResponse. 소셜 로그인만 isNewUser가 붙어요 */
@Serializable
data class TokenDto(
    val accessToken: String,
    val refreshToken: String,
    val accessTokenExpiresIn: Int? = null, // 초. 지금은 1800(30분)
    val isNewUser: Boolean? = null,
)

/**
 * MeResponse. linkedProviders는 대문자 GOOGLE·NAVER.
 * hasPassword는 LOLog-docs #45 댓글에서 새로 생긴 필드예요. 서버가 아직 안 보내면 false라서
 * 설정의 "비밀번호 바꾸기" 줄이 숨어요(일부러 이렇게 둬요)
 */
@Serializable
data class MeDto(val email: String = "", val linkedProviders: List<String> = emptyList(), val hasPassword: Boolean = false)

/** PasswordResetCodeRequest. 응답은 늘 204(본문 없음) */
@Serializable
data class PasswordResetCodeDto(val email: String)

/** PasswordResetRequest. 응답은 204(본문 없음). code는 6자리 문자열(앞 0 유지) */
@Serializable
data class PasswordResetDto(val email: String, val code: String, val newPassword: String)

/** PasswordChangeRequest. 응답은 TokenResponse(새 토큰 한 벌) */
@Serializable
data class PasswordChangeDto(val currentPassword: String, val newPassword: String)

/** POST /auth/me/social/{provider} 200 */
@Serializable
data class LinkedProvidersDto(val linkedProviders: List<String> = emptyList())

// ── 녹음 (/api/recordings) ──

/** 명세 이름 Sentence. 녹음 서비스(지원)도 이 이름으로 씀 */
@Serializable
data class Sentence(val startMs: Long, val endMs: Long, val text: String)

/** 명세 이름 RecordingUploadRequest. 녹음 서비스(지원)가 쓰는 이름에 맞춤 */
@Serializable
data class UploadRecordingRequest(
    val clientRecordingId: String,
    val partNo: Int,
    val isLast: Boolean,
    val startedAt: String,
    /** 명세는 Int(0~86,400). 녹음 서비스가 Long으로 계산해서 Long으로 둠(JSON은 같음) */
    val durationSec: Long,
    val sentences: List<Sentence>,
    /** 이 녹음에서 음성 저장을 켰는지. 서버는 첫 묶음 값만 저장해요. 묶음마다 같은 값을 보내요(명세 audio.md 6장) */
    val audioSaved: Boolean = false,
)

/** 명세 이름 RecordingUploadResponse (202). 녹음 서비스(지원)가 쓰는 이름에 맞춤 */
@Serializable
data class UploadRecordingResponse(val recordingId: Long, val status: String? = null)

/** RecordingResponse — GET /api/recordings/{id} */
@Serializable
data class RecordingStatusDto(val recordingId: Long? = null, val status: String? = null, val cardCount: Int = 0)

/** RecordingListItem — GET /api/recordings 목록 한 항목 (앱을 다시 켰을 때 "분석 중" 녹음 찾기용) */
@Serializable
data class RecordingListItemDto(
    val recordingId: Long,
    val clientRecordingId: String = "",
    val status: String? = null,
    val startedAt: String = "",
    val cardCount: Int = 0,
)

@Serializable
data class RecordingPageDto(val recordings: List<RecordingListItemDto> = emptyList(), val nextCursor: String? = null)

// ── 카드 (/api/cards) ──

/** Speaker. character는 늘 오고 값은 null이거나 { type, id, label, thumbnailUrl } (명세 characters.md 4장) */
@Serializable
data class SpeakerDto(val key: String, val name: String? = null, val character: SpeakerCharacterDto? = null)

/** 화자에 붙은 그림. type DEFAULT·PROFILE, id는 기본 화자 id 또는 회원 ID 문자열. label은 닉네임이 없으면 null */
@Serializable
data class SpeakerCharacterDto(val type: String, val id: String, val label: String? = null, val thumbnailUrl: String? = null)

@Serializable
data class LineDto(val id: Long, val speaker: String, val text: String)

@Serializable
data class ComicRefDto(val id: Long, val status: String? = null)

/** CardSummary — GET /api/cards 목록 한 항목 */
@Serializable
data class CardSummaryDto(
    val id: Long,
    val kind: String? = null,
    val summary: String = "",
    val recordedAt: String? = null,
    val startMs: Long = 0,
    val endMs: Long = 0,
    val comicCount: Int = 0, // 실패한 것 빼고, 만드는 중 포함
    val createdAt: String = "",
    // 목록에 "장소 · 대사 N개"를 보여 줘요. lineCount는 명세상 필수지만 서버가 빼도 화면이 깨지지 않게 null을 받아요
    val place: String? = null,
    val lineCount: Int? = null,
)

@Serializable
data class CardPageDto(val cards: List<CardSummaryDto> = emptyList(), val nextCursor: String? = null)

/** CardDetail — GET·PATCH /api/cards/{id} */
@Serializable
data class CardDetailDto(
    val id: Long,
    val recordingId: Long? = null, // 녹음 기록이 지워졌으면 null
    val kind: String? = null,
    val summary: String = "",
    val place: String? = null,
    val recordedAt: String? = null,
    val startMs: Long = 0,
    val endMs: Long = 0,
    val speakers: List<SpeakerDto> = emptyList(),
    val lines: List<LineDto> = emptyList(),
    val comics: List<ComicRefDto> = emptyList(), // 최신순
    val createdAt: String = "",
    val comicRemaining: Int? = null, // 이 카드로 더 만들 수 있는 횟수(0~3). 명세상 필수, 없으면 앱이 comics에서 실패를 빼고 계산
)

// PATCH /api/cards/{id} 본문(CardUpdateRequest)은 "null을 보내면 지움" 규칙이 있어서
// DTO 대신 ApiMapper.patchBody()가 JsonObject로 직접 만들어요.

// ── 기본 화자 (LOLog-docs spec/characters.md 4장) ──

/** DefaultCharacterItem. ageGroup CHILD·YOUNG·MIDDLE, gender F·M. thumbnailUrl은 10분 서명 주소 */
@Serializable
data class DefaultCharacterItem(
    val id: String,
    val label: String = "",
    val ageGroup: String? = null,
    val gender: String? = null,
    val glasses: Boolean = false,
    val thumbnailUrl: String? = null,
)

/** GET /api/characters/defaults */
@Serializable
data class DefaultCharacterListDto(val characters: List<DefaultCharacterItem> = emptyList())

// ── 만화 (/api/comics) ──

/** ComicCreateResponse (202). 요청 본문은 없어요 */
@Serializable
data class ComicAcceptedDto(val comicId: Long, val status: String? = null)

/** ComicResponse — GET /api/comics/{id}, GET /api/comics 목록 한 항목 (같은 모양) */
@Serializable
data class ComicDto(
    val id: Long = 0,
    val cardId: Long? = null, // 카드를 지웠으면 null
    val status: String? = null,
    val imageUrl: String? = null, // DONE일 때만. 10분짜리 서명 주소라 저장하지 않고, 403이면 다시 조회
    val createdAt: String? = null,
    // thumbnailUrl·width·height는 명세에 없어요(제안). 서버가 안 주면 크기는 기본값으로 보고, 목록 썸네일은 imageUrl 첫 칸을 잘라 써요
    val thumbnailUrl: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val cardSummary: String? = null, // 명세상 필수. 서버가 빼도 화면이 깨지지 않게 null을 받아요
)

@Serializable
data class ComicPageDto(val comics: List<ComicDto> = emptyList(), val nextCursor: String? = null)

// ── 친구·프로필 (LOLog-docs spec/friends.md 2장) ──

/** ProfileResponse — GET·PATCH /api/me/profile. character는 내 프로필 캐릭터(만든 적이 없으면 null) */
@Serializable
data class ProfileResponse(
    val nickname: String? = null,
    val friendCode: String? = null, // 닉네임을 정하기 전에는 null
    val character: ProfileCharacterDto? = null,
)

/** ProfileCharacterResponse. status PROCESSING·READY·FAILED. thumbnailUrl은 쓸 수 있는 그림이 있을 때만(10분 서명 주소) */
@Serializable
data class ProfileCharacterDto(val status: String? = null, val thumbnailUrl: String? = null, val updatedAt: String = "")

/** ProfileUpdateRequest — PATCH /api/me/profile. 1~10자(앞뒤 공백 뺀 길이) */
@Serializable
data class ProfileUpdateRequest(val nickname: String)

/** FriendRequestCreate — POST /api/friend-requests */
@Serializable
data class FriendRequestCreate(val friendCode: String)

/** 요청의 상대 */
@Serializable
data class FriendUser(val userId: Long, val nickname: String = "", val friendCode: String = "")

/** FriendRequestResponse. direction RECEIVED·SENT, status PENDING·ACCEPTED */
@Serializable
data class FriendRequestResponse(
    val id: Long,
    val direction: String = "",
    val user: FriendUser,
    val status: String = "",
    val createdAt: String = "",
)

/** GET /api/friend-requests?direction= */
@Serializable
data class FriendRequestListResponse(val requests: List<FriendRequestResponse> = emptyList())

/** FriendItem.character: 친구에게 쓸 수 있는 그림(256, 10분 서명 주소) */
@Serializable
data class FriendCharacter(val thumbnailUrl: String? = null)

/** FriendItem — GET /api/friends 한 항목, POST /api/friend-requests/{id}/accept 응답 */
@Serializable
data class FriendItem(
    val userId: Long,
    val nickname: String = "",
    val friendCode: String = "",
    val friendsSince: String = "",
    val character: FriendCharacter? = null,
)

@Serializable
data class FriendListResponse(val friends: List<FriendItem> = emptyList())

/** POST /api/blocks 본문 { userId } */
@Serializable
data class BlockCreate(val userId: Long)

/** BlockItem. nickname은 null 가능 */
@Serializable
data class BlockItem(val userId: Long, val nickname: String? = null, val friendCode: String = "", val blockedAt: String = "")

@Serializable
data class BlockListResponse(val blocks: List<BlockItem> = emptyList())

// ── 기기 ──

/** FcmTokenRequest — PUT·DELETE /api/devices/fcm-token */
@Serializable
data class FcmTokenDto(val token: String)
