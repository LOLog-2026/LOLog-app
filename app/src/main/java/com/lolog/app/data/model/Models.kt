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

enum class SocialProvider(val label: String) { KAKAO("카카오"), GOOGLE("Google"), NAVER("네이버") }

/** GET /auth/me */
data class Account(val email: String, val linkedProviders: Set<SocialProvider>)

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

data class Speaker(val key: String, val name: String? = null)

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
 * 카드 고치기(PATCH): 바꾼 것만 채워요(null = 안 바꿈).
 *  speakers = 이름을 바꾼 화자만(name null = 이름 지움), lines = 고친 대사만, place = 장소를 지우면 ""
 */
/**
 * 고친 대사 하나(api-dto LineUpdate). speaker·text는 바꾼 것만 넣어요.
 * 화자만 바꾼 대사에 원래 text를 같이 보내면, LLM이 만든 200자 넘는 대사는 서버가 400으로 거절해요.
 */
data class LineEdit(val id: Long, val speaker: String? = null, val text: String? = null)

data class CardEdit(
    val speakers: List<Speaker>? = null,
    val lines: List<LineEdit>? = null,
    val place: String? = null,
) {
    val isEmpty: Boolean get() = speakers == null && lines == null && place == null
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

// ── 푸시 (api-spec.md 푸시) ────────────────────────────

/** FCM data의 type (api-spec.md 푸시 표) */
enum class PushType { CARDS_CREATED, COMIC_DONE, COMIC_FAILED }

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
