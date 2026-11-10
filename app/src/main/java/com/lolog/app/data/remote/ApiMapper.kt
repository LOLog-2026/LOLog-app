package com.lolog.app.data.remote

import com.lolog.app.data.model.*
import kotlinx.serialization.json.JsonObject
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

    fun recordingStatus(raw: String?) = RECORDING_STATUS[raw?.trim()?.uppercase()] ?: RecordingStatus.UNKNOWN
    fun comicStatus(raw: String?) = COMIC_STATUS[raw?.trim()?.uppercase()] ?: ComicStatus.UNKNOWN
    fun cardKind(raw: String?) = CARD_KIND[raw?.trim()?.uppercase()] ?: CARD_KIND[raw?.trim()] ?: CardKind.OTHER
    fun providerPath(p: SocialProvider) = PROVIDER.getValue(p)
    fun provider(raw: String) = PROVIDER.entries.firstOrNull { it.value.equals(raw.trim(), true) }?.key

    // ── 인증 ──

    fun MeDto.toModel() = Account(email, linkedProviders.mapNotNull(::provider).toSet())
    fun providers(raw: List<String>) = raw.mapNotNull(::provider).toSet()

    // ── 녹음 ──

    fun RecordingPart.toDto() = UploadRecordingRequest(
        clientRecordingId, partNo, isLast, startedAt, durationSec.toLong(),
        sentences.map { com.lolog.app.data.remote.Sentence(it.startMs, it.endMs, it.text) },
    )

    fun UploadRecordingResponse.toModel() = RecordingAccepted(recordingId, recordingStatus(status))
    fun RecordingStatusDto.toModel() = RecordingProgress(recordingStatus(status), cardCount)

    // ── 카드 ──

    fun SpeakerDto.toModel() = Speaker(key, name)
    fun LineDto.toModel() = Line(id, speaker, text)

    fun CardSummaryDto.toModel() = CardSummary(
        id = id, kind = cardKind(kind), summary = summary, recordedAt = recordedAt,
        comicCount = comicCount, createdAt = createdAt, place = place, lineCount = lineCount,
    )

    fun CardPageDto.toModel() = CardPage(cards.map { it.toModel() }, nextCursor?.takeIf { it.isNotBlank() })

    fun CardDetailDto.toModel() = CardDetail(
        id = id, kind = cardKind(kind), summary = summary, place = place,
        startMs = startMs, endMs = endMs,
        speakers = speakers.map { it.toModel() },
        lines = lines.map { it.toModel() },
        comics = comics.map { ComicRef(it.id, comicStatus(it.status)) },
        createdAt = createdAt, recordedAt = recordedAt, comicRemaining = comicRemaining,
        recordingId = recordingId,
    )

    /**
     * PATCH /api/cards/{id} 본문(CardUpdateRequest). 바꾼 것만 넣어요.
     *  - speakers: [{ key, name }] 바꾼 화자만. name이 null이면 이름 지움 → JSON에 "name": null 을 꼭 넣어요
     *  - lines: [{ id, speaker?, text? }] 바꾼 대사만, 그 안에서도 바꾼 칸만
     *  - place: 바꿨을 때만. 지웠으면 "place": null
     * Json 설정(explicitNulls=false)이 null을 빼 버려서 DTO 대신 직접 만들어요.
     */
    fun CardEdit.patchBody(): JsonObject = buildJsonObject {
        speakers?.let { list ->
            put("speakers", buildJsonArray {
                list.forEach { sp -> add(buildJsonObject { put("key", sp.key); put("name", sp.name) /* null이면 JSON null */ }) }
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
}
