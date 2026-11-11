package com.lolog.app.data.remote

import com.lolog.app.data.model.*
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 서버 DTO ↔ 앱 모델 변환은 전부 여기서 해요.
 * 백엔드와 값이 다르면 아래 표부터 고치세요.
 */
object ApiMapper {

    // ── 값 표: 서버 문자열 → 앱 enum (대소문자 무시). 모르는 값은 UNKNOWN/OTHER ──

    private val RECORDING_STATUS = mapOf(
        "RECEIVED" to RecordingStatus.RECEIVED,
        "DETECTING" to RecordingStatus.DETECTING,
        "DONE" to RecordingStatus.DONE,
        "FAILED" to RecordingStatus.FAILED,
    )

    private val COMIC_STATUS = mapOf(
        "QUEUED" to ComicStatus.QUEUED,
        "STORYBOARD" to ComicStatus.STORYBOARD,
        "DRAWING" to ComicStatus.DRAWING,
        "DONE" to ComicStatus.DONE,
        "FAILED" to ComicStatus.FAILED,
    )

    /** 명세: kind는 한국어 값 그대로(웃김·황당·추억). 영문 코드도 받아 둬요 */
    private val CARD_KIND = mapOf(
        "웃김" to CardKind.FUNNY, "FUNNY" to CardKind.FUNNY,
        "황당" to CardKind.ABSURD, "ABSURD" to CardKind.ABSURD,
        "추억" to CardKind.MEMORY, "MEMORY" to CardKind.MEMORY,
    )

    /** 주소에 들어가는 provider 이름과 linkedProviders[] 값 */
    private val PROVIDER = mapOf(
        SocialProvider.GOOGLE to "google",
        SocialProvider.NAVER to "naver",
    )

    private val FRIEND_DIRECTION = mapOf(
        "RECEIVED" to FriendRequestDirection.RECEIVED,
        "SENT" to FriendRequestDirection.SENT,
    )

    private val FRIEND_STATUS = mapOf(
        "PENDING" to FriendRequestStatus.PENDING,
        "ACCEPTED" to FriendRequestStatus.ACCEPTED,
    )

    private val CHARACTER_TYPE = mapOf("DEFAULT" to CharacterType.DEFAULT, "PROFILE" to CharacterType.PROFILE)
    private val AGE_GROUP = mapOf("CHILD" to AgeGroup.CHILD, "YOUNG" to AgeGroup.YOUNG, "MIDDLE" to AgeGroup.MIDDLE)
    private val GENDER = mapOf("F" to Gender.F, "M" to Gender.M)
    private val PROFILE_CHARACTER_STATUS = mapOf(
        "PROCESSING" to ProfileCharacterStatus.PROCESSING,
        "READY" to ProfileCharacterStatus.READY,
        "FAILED" to ProfileCharacterStatus.FAILED,
    )

    fun recordingStatus(raw: String?) = RECORDING_STATUS[raw?.trim()?.uppercase()] ?: RecordingStatus.UNKNOWN
    fun comicStatus(raw: String?) = COMIC_STATUS[raw?.trim()?.uppercase()] ?: ComicStatus.UNKNOWN
    fun cardKind(raw: String?) = CARD_KIND[raw?.trim()?.uppercase()] ?: CARD_KIND[raw?.trim()] ?: CardKind.OTHER
    /** 모르는 방향은 받은 요청으로 봐요 */
    fun friendDirection(raw: String?) = FRIEND_DIRECTION[raw?.trim()?.uppercase()] ?: FriendRequestDirection.RECEIVED
    fun friendStatus(raw: String?) = FRIEND_STATUS[raw?.trim()?.uppercase()] ?: FriendRequestStatus.UNKNOWN
    /** 모르는 그림 종류는 null(그 화자는 그림 없음으로 봐요) */
    fun characterType(raw: String?) = CHARACTER_TYPE[raw?.trim()?.uppercase()]
    fun ageGroup(raw: String?) = AGE_GROUP[raw?.trim()?.uppercase()]
    fun gender(raw: String?) = GENDER[raw?.trim()?.uppercase()]
    fun profileCharacterStatus(raw: String?) = PROFILE_CHARACTER_STATUS[raw?.trim()?.uppercase()] ?: ProfileCharacterStatus.UNKNOWN
    fun providerPath(p: SocialProvider) = PROVIDER.getValue(p)
    fun provider(raw: String) = PROVIDER.entries.firstOrNull { it.value.equals(raw.trim(), true) }?.key

    // ── 인증 ──

    fun MeDto.toModel() = Account(email, linkedProviders.mapNotNull(::provider).toSet(), hasPassword)
    fun providers(raw: List<String>) = raw.mapNotNull(::provider).toSet()

    // ── 녹음 ──

    fun RecordingPart.toDto() = UploadRecordingRequest(
        clientRecordingId, partNo, isLast, startedAt, durationSec.toLong(),
        sentences.map { com.lolog.app.data.remote.Sentence(it.startMs, it.endMs, it.text) },
    )

    fun UploadRecordingResponse.toModel() = RecordingAccepted(recordingId, recordingStatus(status))
    fun RecordingStatusDto.toModel() = RecordingProgress(recordingStatus(status), cardCount)

    // ── 카드 ──

    fun SpeakerDto.toModel() = Speaker(key, name, character?.toModel())
    fun SpeakerCharacterDto.toModel() = characterType(type)?.let {
        SpeakerCharacter(it, id, label?.ifBlank { null }, thumbnailUrl?.ifBlank { null })
    }
    fun LineDto.toModel() = Line(id, speaker, text)

    fun CardSummaryDto.toModel() = CardSummary(
        id = id, kind = cardKind(kind), summary = summary, recordedAt = recordedAt,
        comicCount = comicCount, createdAt = createdAt, place = place, lineCount = lineCount, hasAudio = hasAudio,
    )

    /** 모르는 상태값이거나, READY인데 주소·길이가 없으면 들을 수 없으니 NONE으로 봐요 */
    fun CardAudioDto?.toModel(): CardAudio {
        val status = when (this?.status?.trim()?.uppercase()) {
            "READY" -> if (url.isNullOrBlank() || durationMs == null || durationMs <= 0) AudioStatus.NONE else AudioStatus.READY
            "PENDING" -> AudioStatus.PENDING
            else -> AudioStatus.NONE
        }
        return if (status == AudioStatus.READY) CardAudio(status, this!!.url, durationMs) else CardAudio(status)
    }

    fun CardPageDto.toModel() = CardPage(cards.map { it.toModel() }, nextCursor?.takeIf { it.isNotBlank() })

    fun CardDetailDto.toModel() = CardDetail(
        id = id, kind = cardKind(kind), summary = summary, place = place,
        startMs = startMs, endMs = endMs,
        speakers = speakers.map { it.toModel() },
        lines = lines.map { it.toModel() },
        comics = comics.map { ComicRef(it.id, comicStatus(it.status)) },
        createdAt = createdAt, recordedAt = recordedAt, comicRemaining = comicRemaining,
        recordingId = recordingId,
        audio = audio.toModel(),
    )

    /**
     * PATCH /api/cards/{id} 본문(CardUpdateRequest). 바꾼 것만 넣어요.
     *  - speakers: [{ key, name?, character? }] 바꾼 화자만. 두 칸 중 바꾼 칸만 넣어요(둘 중 하나는 꼭 있어요)
     *    name이 null이면 이름 지움 → "name": null 을 꼭 넣어요
     *    character는 붙이면 { type, id }, 떼면 "character": null 을 꼭 넣어요(빼면 "그대로"라는 뜻이에요)
     *  - lines: [{ id, speaker?, text? }] 바꾼 대사만, 그 안에서도 바꾼 칸만
     *  - place: 바꿨을 때만. 지웠으면 "place": null
     *  - 새 화자: 이름·그림이 없어도 speakers에 { "key": "화자3" }을 꼭 넣어요(있으면 같이)
     *  - removeSpeakers: ["화자2"]. 빼는 화자는 speakers에 넣지 않아요(이름·그림을 바꿨어도)
     *  key는 모두 빼기 전 기준(앱이 들고 있는 원래 key)이에요.
     * Json 설정(explicitNulls=false)이 null을 빼 버려서 DTO 대신 직접 만들어요.
     */
    fun CardEdit.patchBody(): JsonObject = buildJsonObject {
        val names = speakers?.associateBy { it.key }.orEmpty()
        val pics = characters.orEmpty()
        val removed = removeSpeakers.orEmpty().distinct()
        // 이름만 바꾼 화자, 그림만 바꾼 화자, 둘 다 바꾼 화자, 새 화자를 key 하나로 합쳐요(처음 나온 순서대로)
        val keys = (names.keys + pics.keys + newSpeakers.orEmpty()).distinct() - removed.toSet()
        if (keys.isNotEmpty()) {
            put("speakers", buildJsonArray {
                keys.forEach { key ->
                    add(buildJsonObject {
                        put("key", key)
                        if (key in names) put("name", names.getValue(key).name) // null이면 JSON null
                        if (key in pics) {
                            val c = pics[key]
                            if (c == null) put("character", JsonNull)
                            else put("character", buildJsonObject { put("type", c.type.name); put("id", c.id) })
                        }
                    })
                }
            })
        }
        lines?.let { list ->
            put("lines", buildJsonArray {
                list.forEach { l ->
                    add(buildJsonObject {
                        put("id", l.id)
                        l.speaker?.let { put("speaker", it) }
                        l.text?.let { put("text", it) }
                    })
                }
            })
        }
        place?.let { p -> put("place", p.ifBlank { null }) }
        if (removed.isNotEmpty()) put("removeSpeakers", buildJsonArray { removed.forEach { add(JsonPrimitive(it)) } })
    }

    // ── 만화 ──

    fun ComicAcceptedDto.toModel() = ComicAccepted(comicId, comicStatus(status))

    /** cardId가 null이면 카드가 지워진 만화예요 */
    fun ComicDto.toSummary() = ComicSummary(
        id, cardId ?: 0L, comicStatus(status), imageUrl, createdAt.orEmpty(), cardSummary,
        thumbnailUrl = thumbnailUrl, width = width, height = height, cardDeleted = cardId == null,
    )
    fun ComicPageDto.toModel() = ComicPage(comics.map { it.toSummary() }, nextCursor?.takeIf { it.isNotBlank() })
    fun ComicDto.toDetail(requestedId: Long) =
        ComicDetail(
            id.takeIf { it != 0L } ?: requestedId, cardId ?: 0L, comicStatus(status), imageUrl, createdAt,
            width = width, height = height, cardDeleted = cardId == null, cardSummary = cardSummary,
        )

    // ── 친구·프로필 ──

    // userId(내 회원 ID)가 없으면(예전 서버) 화자 고르기 팝업의 "나"는 그림이 없을 때처럼 동작해요(PROFILE을 보내지 않아요)
    fun ProfileResponse.toModel() = Profile(
        nickname?.ifBlank { null }, friendCode?.ifBlank { null },
        character = character?.toModel(),
        userId = userId?.toString(),
    )

    /** PATCH /api/me/profile/character/voice 본문. 안 고르면 "voice": null 을 꼭 넣어요(Json 설정이 null을 빼 버려요) */
    fun voiceBody(voice: String?): JsonObject = buildJsonObject {
        val v = voice?.trim()?.ifBlank { null }
        if (v == null) put("voice", JsonNull) else put("voice", v)
    }

    fun ProfileCharacterDto.toModel() =
        ProfileCharacter(profileCharacterStatus(status), thumbnailUrl?.ifBlank { null }, updatedAt, voice?.ifBlank { null })

    fun DefaultCharacterItem.toModel() = DefaultCharacter(
        id = id, label = label, ageGroup = ageGroup(ageGroup), gender = gender(gender),
        glasses = glasses, thumbnailUrl = thumbnailUrl?.ifBlank { null }, voiceUrl = voiceUrl?.ifBlank { null },
    )

    // DTO와 앱 모델 이름이 같아서(FriendUser) 앱 모델은 전체 이름으로 써요
    fun FriendUser.toModel() = com.lolog.app.data.model.FriendUser(userId, nickname, friendCode)

    fun FriendRequestResponse.toModel() = FriendRequest(
        id = id, direction = friendDirection(direction), user = user.toModel(),
        status = friendStatus(status), createdAt = createdAt,
    )

    fun FriendItem.toModel() = Friend(userId, nickname, friendCode, friendsSince, character?.thumbnailUrl?.ifBlank { null })

    fun BlockItem.toModel() = BlockedUser(userId, nickname?.ifBlank { null }, friendCode, blockedAt)
}
