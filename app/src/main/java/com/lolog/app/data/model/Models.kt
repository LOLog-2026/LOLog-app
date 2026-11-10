package com.lolog.app.data.model

/*
 * 앱 안에서 쓰는 모델(도메인 모델)이에요. 화면·목업·녹음 코드는 이것만 봐요.
 * 서버 JSON 모양(DTO)은 data/remote/ApiDtos.kt에만 있고, 둘 사이 변환은 data/remote/ApiMapper.kt 한 곳에서 해요.
 * → 백엔드 필드 이름이나 상태 값이 바뀌면 ApiDtos.kt와 ApiMapper.kt만 고치면 돼요.
 *
 * 날짜는 ISO-8601 문자열 그대로 들고 다니고(util/Dates.kt에서 표시), id는 Long이에요.
 */

// ── 공통 ──────────────────────────────────────────────

/** 오류 코드 — LOLog-docs api-dto.md "오류 코드" 표 */
object ErrorCodes {
    const val INVALID_REQUEST = "INVALID_REQUEST"       // 400 필드가 빠졌거나 타입·제한이 틀림
    const val UNKNOWN_SPEAKER = "UNKNOWN_SPEAKER"       // 400 카드 고치기: 카드에 없는 화자 키
    const val INVALID_CREDENTIALS = "INVALID_CREDENTIALS" // 401 이메일이나 비밀번호가 틀림
    const val TOKEN_EXPIRED = "TOKEN_EXPIRED"           // 401 토큰 만료 (앱은 이때만 갱신)
    const val INVALID_TOKEN = "INVALID_TOKEN"           // 401 토큰이 틀림
    const val UNAUTHORIZED = "UNAUTHORIZED"             // 401 토큰이 없거나 틀림
    const val CARD_NOT_FOUND = "CARD_NOT_FOUND"
    const val COMIC_NOT_FOUND = "COMIC_NOT_FOUND"
    const val RECORDING_NOT_FOUND = "RECORDING_NOT_FOUND"
    const val ACCOUNT_EXISTS = "ACCOUNT_EXISTS"
    const val SOCIAL_ALREADY_LINKED = "SOCIAL_ALREADY_LINKED"
    const val PART_OUT_OF_ORDER = "PART_OUT_OF_ORDER"   // 409 녹음 올리기
    const val RECORDING_CLOSED = "RECORDING_CLOSED"     // 409 녹음 올리기
    const val CARD_LOCKED = "CARD_LOCKED"               // 409 만화 만드는 중 고치기·지우기
    const val COMIC_IN_PROGRESS = "COMIC_IN_PROGRESS"   // 409 만드는 중에 또 요청
    const val COMIC_LIMIT = "COMIC_LIMIT"               // 409 한 카드 3번 (실패는 안 셈)
    const val PAYLOAD_TOO_LARGE = "PAYLOAD_TOO_LARGE"   // 413 녹음 올리기
    const val INTERNAL_ERROR = "INTERNAL_ERROR"         // 500
    const val INVALID_CODE = "INVALID_CODE"             // 400 가입 인증번호가 틀림
    const val CODE_EXPIRED = "CODE_EXPIRED"             // 400 인증번호가 없거나 10분 지남·5번 틀림
    const val TOO_MANY_REQUESTS = "TOO_MANY_REQUESTS"   // 429 같은 이메일로 60초 안에 인증번호를 또 요청
    // 친구 (LOLog-docs spec/friends.md 오류 코드)
    const val CANNOT_FRIEND_SELF = "CANNOT_FRIEND_SELF"             // 400 내 코드를 넣음, 나를 차단하려 함
    const val FRIEND_CODE_NOT_FOUND = "FRIEND_CODE_NOT_FOUND"       // 404 없는 친구 코드(상대가 나를 차단했어도 이 코드)
    const val FRIEND_REQUEST_NOT_FOUND = "FRIEND_REQUEST_NOT_FOUND" // 404 없는 요청, 내 요청이 아님
    const val USER_NOT_FOUND = "USER_NOT_FOUND"                     // 404 차단: 친구도 아니고 요청도 오간 적 없음
    const val PROFILE_REQUIRED = "PROFILE_REQUIRED"                 // 409 닉네임 없이 요청 보내기·수락
    const val ALREADY_FRIENDS = "ALREADY_FRIENDS"                   // 409 이미 친구
    const val FRIEND_REQUEST_EXISTS = "FRIEND_REQUEST_EXISTS"       // 409 이미 보낸 요청이 있음
    const val FRIEND_LIMIT = "FRIEND_LIMIT"                         // 409 나나 상대의 친구가 이미 100명
    const val USER_BLOCKED = "USER_BLOCKED"                         // 409 내가 차단한 사람에게 요청
    // 화자 그림 (LOLog-docs spec/characters.md 4장)
    const val UNKNOWN_CHARACTER = "UNKNOWN_CHARACTER"     // 400 없는 기본 화자 id, 나도 친구도 아닌 회원 ID
    const val DUPLICATE_CHARACTER = "DUPLICATE_CHARACTER" // 400 한 카드에서 같은 그림을 두 화자에 붙임
    const val CHARACTER_NOT_READY = "CHARACTER_NOT_READY" // 409 쓸 수 있는 그림이 없는 사람의 캐릭터를 붙임
    // 화자 더하기·빼기 (LOLog-docs api-dto.md CardUpdateRequest)
    const val SPEAKER_LIMIT = "SPEAKER_LIMIT"             // 409 더하고 뺀 뒤 화자가 4명을 넘음
    const val SPEAKER_HAS_LINES = "SPEAKER_HAS_LINES"     // 409 대사가 남은 화자를 빼려 함
    const val CHARACTER_IN_PROGRESS = "CHARACTER_IN_PROGRESS" // 409 프로필 캐릭터를 만드는 중에 또 올림
    const val CHARACTER_LIMIT = "CHARACTER_LIMIT"         // 409 프로필 캐릭터 만들기 하루 3번(한국 시간 자정에 다시 셈)
    const val NETWORK = "NETWORK" // 앱 내부용: 서버에 닿지 못함
    /** 옛 이름(11/5 제안). 명세는 INVALID_REQUEST라 같은 뜻으로 받아요 */
    const val VALIDATION_FAILED = "VALIDATION_FAILED"
}

/** 글자 수 제한 (11/5 프론트 확정). 명세·서버 한도와 같아요(화자 이름 10·장소 20·대사 200자) */
object TextLimits {
    const val SPEAKER_NAME = 10
    const val PLACE = 20
    const val LINE = 200
}

/** 이메일 가입 인증번호: 숫자 6자리 문자열(앞 0 유지), 같은 이메일은 60초 뒤에 다시 받을 수 있어요 */
const val EMAIL_CODE_LENGTH = 6
const val EMAIL_CODE_RESEND_SECONDS = 60

/** FR-AUTH-01 + api-dto.md: 8~64자, 영문·숫자 포함, UTF-8로 72바이트 이하(BCrypt 한도) */
const val PASSWORD_MIN = 8
const val PASSWORD_MAX = 64
const val PASSWORD_MAX_BYTES = 72
fun isValidPassword(pw: String): Boolean =
    pw.length in PASSWORD_MIN..PASSWORD_MAX &&
        pw.toByteArray(Charsets.UTF_8).size <= PASSWORD_MAX_BYTES &&
        pw.any { it in 'a'..'z' || it in 'A'..'Z' } && // isLetter()는 한글도 문자로 쳐서 영문만 봐요
        pw.any { it in '0'..'9' }

/** 소셜 로그인은 구글·네이버만(LOLog-docs #35). 카카오는 이메일 동의항목에 비즈 앱 심사가 필요해 뺐어요 */
enum class SocialProvider(val label: String) { GOOGLE("Google"), NAVER("네이버") }

/** GET /auth/me. hasPassword가 false면(소셜로만 가입) 설정에 "비밀번호 바꾸기"를 숨겨요 */
data class Account(val email: String, val linkedProviders: Set<SocialProvider>, val hasPassword: Boolean = false)

// ── 녹음 ──────────────────────────────────────────────

data class Sentence(val startMs: Long, val endMs: Long, val text: String)

/** 1시간 묶음 하나 (POST /api/recordings 로 보낼 내용) */
data class RecordingPart(
    val clientRecordingId: String,
    val partNo: Int,
    val isLast: Boolean,
    val startedAt: String,
    val durationSec: Int,
    val sentences: List<Sentence>,
)

enum class RecordingStatus { RECEIVED, DETECTING, DONE, FAILED, UNKNOWN }

data class RecordingAccepted(val recordingId: Long, val status: RecordingStatus)

/** GET /api/recordings/{id} */
data class RecordingProgress(val status: RecordingStatus, val cardCount: Int)

// ── 카드 ──────────────────────────────────────────────

/** 한 카드의 화자 수 한도(FR-EDIT-01). 더하고 뺀 뒤 1~4명이어야 해요(넘으면 409 SPEAKER_LIMIT) */
const val SPEAKER_MAX = 4

/** character는 이 화자에 붙인 그림. null이면 그림 없이 AI가 외모를 정해요 */
data class Speaker(val key: String, val name: String? = null, val character: SpeakerCharacter? = null)

/** 화자 그림 종류: 기본 화자, 회원의 프로필 캐릭터 */
enum class CharacterType { DEFAULT, PROFILE }

/**
 * 화자에 붙인 그림(명세 CharacterRef + 응답의 label·thumbnailUrl).
 * id는 기본 화자 id(kid-f-01) 또는 회원 ID 문자열("42"). label은 기본 화자 설명 또는 그 사람의 닉네임
 */
data class SpeakerCharacter(val type: CharacterType, val id: String, val label: String? = null, val thumbnailUrl: String? = null) {
    /** 같은 그림인지(type+id). label·주소는 보지 않아요 */
    fun sameAs(other: SpeakerCharacter?): Boolean = other != null && type == other.type && id == other.id

    /** 그림 캐시 키. 서명 주소가 10분마다 바뀌어서 주소 대신 이 키로 캐시해요 */
    val cacheKey: String get() = if (type == CharacterType.DEFAULT) defaultCharacterCacheKey(id) else profileCacheKey(id)
}

fun defaultCharacterCacheKey(id: String) = "default-$id"
fun profileCacheKey(userId: String) = "profile-$userId"

enum class AgeGroup(val label: String) { CHILD("어린이"), YOUNG("청년"), MIDDLE("중장년") }
enum class Gender(val label: String) { F("여"), M("남") }

/** GET /api/characters/defaults 한 항목. thumbnailUrl은 256 그림의 10분 서명 주소 */
data class DefaultCharacter(
    val id: String,
    val label: String,
    val ageGroup: AgeGroup?,
    val gender: Gender?,
    val glasses: Boolean,
    val thumbnailUrl: String?,
)

data class Line(val id: Long, val speaker: String, val text: String)

data class ComicRef(val id: Long, val status: ComicStatus)

data class CardSummary(
    val id: Long,
    val kind: CardKind,
    val summary: String,
    val recordedAt: String? = null,
    val comicCount: Int = 0,
    val createdAt: String,
    val place: String? = null,
    val speakers: List<Speaker> = emptyList(),
    val preview: String? = null,
    /** 목록에 "대사 4개"로 보여요 */
    val lineCount: Int? = null,
    /** 들을 수 있는 음성이 있으면 목록에 작은 표시(replay.md 2장) */
    val hasAudio: Boolean = false,
)

/** 카드 구간 음성의 상태(replay.md 1장) */
enum class AudioStatus {
    /** 들을 수 있음. 재생 버튼을 보여 줘요 */
    READY,

    /** 음성 저장을 켠 녹음인데 음성이 아직 안 올라옴(녹음이 끝난 지 7일 안). 안내 문구를 보여 줘요 */
    PENDING,

    /** 음성 저장을 끈 녹음이거나 7일이 지나도록 안 올라옴. 음성에 대한 것을 아무것도 안 보여 줘요 */
    NONE,
}

/** CardDetail.audio. url은 10분짜리 서명 주소, durationMs는 음성 길이(재생 바 전체 길이) */
data class CardAudio(
    val status: AudioStatus = AudioStatus.NONE,
    val url: String? = null,
    val durationMs: Long? = null,
)

data class CardPage(val cards: List<CardSummary>, val nextCursor: String?)

data class CardDetail(
    val id: Long,
    val kind: CardKind,
    val summary: String,
    val place: String? = null,
    val startMs: Long = 0,
    val endMs: Long = 0,
    val speakers: List<Speaker> = emptyList(),
    val lines: List<Line> = emptyList(),
    val comics: List<ComicRef> = emptyList(),
    val createdAt: String,
    val recordedAt: String? = null,
    /** 서버가 센 다시 그리기 남은 횟수(0~3). 명세에 없음(제안) → 없으면 아래 remaining이 계산 */
    val comicRemaining: Int? = null,
    /** 녹음 기록이 지워졌으면 null */
    val recordingId: Long? = null,
    /** 카드 구간 음성(순간 다시 듣기) */
    val audio: CardAudio = CardAudio(),
) {
    /** 서버 값이 없으면 실패를 뺀 개수로 앱이 계산 */
    val remaining: Int
        get() = comicRemaining ?: (COMIC_LIMIT_PER_CARD - comics.count { it.status != ComicStatus.FAILED }).coerceAtLeast(0)

    /** 3번 중 쓴 횟수(실패 제외) */
    val used: Int get() = COMIC_LIMIT_PER_CARD - remaining

    /** 아직 안 끝난 만화가 있으면 고치기·지우기·다시 그리기 잠금 */
    val locked: Boolean get() = comics.any { it.status.inProgress }
}

/**
 * 고친 대사 하나(api-dto LineUpdate). speaker·text는 바꾼 것만 넣어요.
 * 화자만 바꾼 대사에 원래 text를 같이 보내면, LLM이 만든 200자 넘는 대사는 서버가 400으로 거절해요.
 */
data class LineEdit(val id: Long, val speaker: String? = null, val text: String? = null)

/**
 * 카드 고치기(PATCH): 바꾼 것만 채워요(null = 안 바꿈).
 *  speakers = 이름을 바꾼 화자만(name null = 이름 지움, character는 보지 않아요)
 *  characters = 그림을 바꾼 화자만. 화자 key → 새 그림(null = 그림 떼기)
 *  lines = 고친 대사만, place = 장소를 지우면 ""
 *  newSpeakers = 새로 만드는 화자 key(다음 번호, 화자가 2명이면 "화자3"). 이름·그림을 정했으면 speakers·characters에도 들어가요
 *  removeSpeakers = 뺄 화자 key. 대사가 하나도 없는 화자만 뺄 수 있어요
 * key는 모두 빼기 전 기준이에요. 서버가 뺀 뒤 "화자1"부터 다시 매겨서 돌려줘요(api-dto CardUpdateRequest)
 */
data class CardEdit(
    val speakers: List<Speaker>? = null,
    val lines: List<LineEdit>? = null,
    val place: String? = null,
    val characters: Map<String, SpeakerCharacter?>? = null,
    val newSpeakers: List<String>? = null,
    val removeSpeakers: List<String>? = null,
) {
    val isEmpty: Boolean
        get() = speakers == null && lines == null && place == null && characters == null &&
            newSpeakers == null && removeSpeakers == null
}

// ── 만화 ──────────────────────────────────────────────

/** 설계 문서 FR-COMIC-04: 한 카드로 만화는 3번까지 (넘으면 409 COMIC_LIMIT). 실패는 안 세요 */
const val COMIC_LIMIT_PER_CARD = 3

/** 만화 그림 크기(api-dto ComicResponse, storyboard.md): 세로 4칸 PNG 한 장 800×3152 */
const val COMIC_WIDTH = 800
const val COMIC_HEIGHT = 3152

enum class ComicStatus { QUEUED, STORYBOARD, DRAWING, DONE, FAILED, UNKNOWN }

/** 앱에 보이는 4단계(FR-COMIC-03): 대기 / 만드는 중 / 완료 / 실패 */
enum class ComicStage(val label: String) { WAITING("기다리는 중"), MAKING("그리는 중"), DONE("완료"), FAILED("실패") }

val ComicStatus.stage: ComicStage
    get() = when (this) {
        ComicStatus.QUEUED, ComicStatus.UNKNOWN -> ComicStage.WAITING
        ComicStatus.STORYBOARD, ComicStatus.DRAWING -> ComicStage.MAKING
        ComicStatus.DONE -> ComicStage.DONE
        ComicStatus.FAILED -> ComicStage.FAILED
    }

val ComicStatus.inProgress: Boolean get() = stage == ComicStage.WAITING || stage == ComicStage.MAKING

data class ComicAccepted(val comicId: Long, val status: ComicStatus)

data class ComicSummary(
    val id: Long,
    val cardId: Long,
    val status: ComicStatus,
    val imageUrl: String? = null,
    val createdAt: String,
    val cardSummary: String? = null,
    /** 목록용 1컷 썸네일(320×320) */
    val thumbnailUrl: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    /** 원래 카드가 지워졌으면 true (만화는 남아요) */
    val cardDeleted: Boolean = false,
)

data class ComicPage(val comics: List<ComicSummary>, val nextCursor: String?)

data class ComicDetail(
    val id: Long,
    val cardId: Long,
    val status: ComicStatus,
    val imageUrl: String? = null,
    val createdAt: String? = null,
    /** 디버깅용. 화면에는 안 보여요 */
    val failReason: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val cardDeleted: Boolean = false,
    val cardSummary: String? = null,
) {
    /** 세로 4칸 한 장의 가로:세로 비율. 서버 크기가 없으면 명세 크기 800×3152 */
    val ratio: Float get() = (width ?: COMIC_WIDTH).toFloat() / (height ?: COMIC_HEIGHT).toFloat()
}

// ── 친구 (LOLog-docs spec/friends.md) ─────────────────

/** 닉네임 1~10자(앞뒤 공백 뺀 길이), 친구 코드 대문자·숫자 8자리, 친구는 100명까지 */
object FriendRules {
    const val NICKNAME_MAX = 10
    const val CODE_LENGTH = 8
    const val FRIEND_MAX = 100
}

/**
 * GET·PATCH /api/me/profile. 닉네임을 정하기 전에는 friendCode가 null.
 * character는 내 프로필 캐릭터(만든 적이 없으면 null).
 * userId는 화자에 내 캐릭터(PROFILE)를 붙일 때 쓰는 내 회원 ID(문자열, docs #56)
 */
data class Profile(
    val nickname: String? = null,
    val friendCode: String? = null,
    val character: ProfileCharacter? = null,
    val userId: String? = null,
)

enum class ProfileCharacterStatus { PROCESSING, READY, FAILED, UNKNOWN }

/** 내 프로필 캐릭터(ProfileCharacterResponse). thumbnailUrl은 쓸 수 있는 그림이 있을 때만 */
data class ProfileCharacter(val status: ProfileCharacterStatus, val thumbnailUrl: String?, val updatedAt: String)

/** 친구 한 명. thumbnailUrl은 친구의 프로필 캐릭터 그림(없으면 기본 사람 아이콘) */
data class Friend(
    val userId: Long,
    val nickname: String,
    val friendCode: String,
    val friendsSince: String,
    val thumbnailUrl: String? = null,
)

enum class FriendRequestDirection(val query: String) { RECEIVED("received"), SENT("sent") }

/** POST에서 상대가 이미 나에게 요청해 둔 경우는 바로 친구가 돼서 ACCEPTED */
enum class FriendRequestStatus { PENDING, ACCEPTED, UNKNOWN }

/** 요청의 상대. 닉네임을 정한 사람만 요청을 주고받아서 nickname은 늘 있어요 */
data class FriendUser(val userId: Long, val nickname: String, val friendCode: String)

data class FriendRequest(
    val id: Long,
    val direction: FriendRequestDirection,
    val user: FriendUser,
    val status: FriendRequestStatus,
    val createdAt: String,
)

/** 차단한 사람. 탈퇴 등으로 닉네임이 없으면 null */
data class BlockedUser(val userId: Long, val nickname: String?, val friendCode: String, val blockedAt: String)

// ── 푸시 (api-spec.md 푸시) ────────────────────────────

/** FCM data의 type (api-spec.md 푸시 표) */
enum class PushType { CARDS_CREATED, COMIC_DONE, COMIC_FAILED, PROFILE_CHARACTER_READY, PROFILE_CHARACTER_FAILED }

data class PushPayload(
    val type: PushType,
    val recordingId: Long? = null,
    /** CARDS_CREATED: 새로 생긴 카드 수 */
    val cardCount: Int? = null,
    /** 명세에 없음(제안): 새 카드 번호들. 오면 1개일 때 그 카드로 바로 가요 */
    val cardIds: List<Long> = emptyList(),
    val comicId: Long? = null,
    val cardId: Long? = null,
)
