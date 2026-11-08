package com.lolog.app.data.mock

import com.lolog.app.data.model.*
import com.lolog.app.data.repo.LologException
import com.lolog.app.data.repo.LologRepository
import com.lolog.app.data.repo.TokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.OffsetDateTime

/**
 * 백엔드 없이 화면 흐름을 다 돌려 볼 수 있는 메모리 목업이에요.
 * 앱 모델을 바로 돌려줘요(서버 DTO·변환은 거치지 않음). 상태 변화(감지·만화)는 몇 초 단위로 흉내 내요.
 *
 * 오류 화면 확인용 규칙
 *  - 이메일 가입/로그인에 taken@lolog.app → 409 ACCOUNT_EXISTS (18번)
 *  - 비밀번호가 4자 미만 → 401 INVALID_CREDENTIALS
 *  - 네이버로 시작하기 → 409 ACCOUNT_EXISTS (18번)
 *  - 설정에서 Google 연결 → 409 SOCIAL_ALREADY_LINKED (19번)
 *  - 녹음 문장이 6개 미만 → 카드 0개 (20번)
 *  - 대사 중 40자 넘는 게 있으면 만화 FAILED (21번). 카드 고치기에서 줄이면 성공
 *  - 실패한 만화는 3번에 안 세요(11/5 확정). 카드를 지워도 만화는 남고 cardDeleted = true
 */
class MockLologRepository(private val tokens: TokenStore) : LologRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    private val cardStore = MockData.cards()
    private val comicStore = MockData.comics()
    private val linked = mutableSetOf(SocialProvider.KAKAO)
    private val recordings = java.util.concurrent.ConcurrentHashMap<Long, RecordingProgress>()
    private val recordingParts = mutableMapOf<String, Long>() // clientRecordingId → recordingId
    private val recordingSentences = mutableMapOf<Long, MutableList<Sentence>>()
    private val seenParts = mutableSetOf<Pair<String, Int>>()
    private var nextId = 100L
    /** 지운 카드의 제목 (만화 목록에 "원래 카드" 제목을 남기려고) */
    private val deletedCards = mutableMapOf<Long, String>()

    override val isLoggedIn: Flow<Boolean> = tokens.loggedIn

    private val _push = MutableSharedFlow<PushPayload>(extraBufferCapacity = 8)
    override val pushEvents: Flow<PushPayload> = _push

    init {
        // 시작할 때 만드는 중인 만화가 있으면 계속 진행
        comicStore.filter { it.status.inProgress }.forEach { runComic(it.id) }
    }

    private suspend fun net(ms: Long = 350) = delay(ms)
    private fun now(): String = OffsetDateTime.now().toString()

    // ── 인증 ──
    override suspend fun signup(email: String, password: String) {
        net()
        if (email.equals("taken@lolog.app", true)) throw LologException(ErrorCodes.ACCOUNT_EXISTS, "이미 가입된 이메일이에요")
        if (!isValidPassword(password)) throw LologException(ErrorCodes.INVALID_REQUEST, "비밀번호는 8~64자, 영문과 숫자를 섞어 주세요", "password")
        tokens.save("mock-access", "mock-refresh")
    }

    override suspend fun login(email: String, password: String) {
        net()
        // 시험용: offline@… 은 인터넷 끊김, 비밀번호 4자 미만은 틀린 비밀번호
        if (email.startsWith("offline@", true)) throw LologException(ErrorCodes.NETWORK, "인터넷 연결을 확인해 주세요")
        if (password.length < 4) throw LologException(ErrorCodes.INVALID_CREDENTIALS, "이메일이나 비밀번호가 맞지 않아요")
        tokens.save("mock-access", "mock-refresh")
    }

    override suspend fun socialLogin(provider: SocialProvider, providerToken: String): Boolean {
        net()
        if (provider == SocialProvider.NAVER) throw LologException(ErrorCodes.ACCOUNT_EXISTS, "이미 가입된 이메일이에요")
        tokens.save("mock-access", "mock-refresh")
        return false
    }

    override suspend fun logout() { net(150); tokens.clear() }
    override suspend fun withdraw() { net(); lock.withLock { cardStore.clear(); comicStore.clear() }; tokens.clear() }

    override suspend fun me(): Account { net(150); return Account(MockData.EMAIL, linked.toSet()) }

    override suspend fun linkSocial(provider: SocialProvider, providerToken: String): Set<SocialProvider> {
        net()
        if (provider == SocialProvider.GOOGLE) {
            throw LologException(ErrorCodes.SOCIAL_ALREADY_LINKED, "이미 다른 LOLog 계정에 연결된 Google 계정이에요")
        }
        linked += provider
        return linked.toSet()
    }

    // ── 녹음 ──
    override suspend fun uploadRecording(part: RecordingPart): RecordingAccepted = lock.withLock {
        net(500)
        val recordingId = recordingParts.getOrPut(part.clientRecordingId) { nextId++ }
        // 같은 clientRecordingId·partNo는 한 번만 처리 (FR-UP-02)
        if (seenParts.add(part.clientRecordingId to part.partNo)) {
            recordingSentences.getOrPut(recordingId) { mutableListOf() } += part.sentences
            recordings[recordingId] = RecordingProgress(RecordingStatus.RECEIVED, 0)
            if (part.isLast) runDetection(recordingId)
        }
        RecordingAccepted(recordingId, RecordingStatus.RECEIVED)
    }

    override suspend fun recordingStatus(recordingId: Long): RecordingProgress {
        net(150)
        return recordings[recordingId] ?: throw LologException("RECORDING_NOT_FOUND", "녹음을 찾지 못했어요")
    }

    override suspend fun analyzingRecordingId(): Long? {
        net(150)
        return recordings.filterValues { it.status == RecordingStatus.RECEIVED || it.status == RecordingStatus.DETECTING }
            .keys.maxOrNull()
    }

    private fun runDetection(recordingId: Long) = scope.launch {
        delay(1_500)
        recordings[recordingId] = RecordingProgress(RecordingStatus.DETECTING, 0)
        delay(5_000)
        val created = lock.withLock {
            val sentences = recordingSentences[recordingId].orEmpty()
            if (sentences.size < 6) return@withLock emptyList()
            sentences.chunked(6).take(2).mapIndexed { i, chunk ->
                CardDetail(
                    id = nextId++,
                    kind = if (i == 0) CardKind.FUNNY else CardKind.ABSURD,
                    summary = chunk.first().text.take(20).ifBlank { "새 이야기" },
                    place = null,
                    startMs = chunk.first().startMs, endMs = chunk.last().endMs,
                    speakers = listOf(Speaker("A"), Speaker("B")),
                    lines = chunk.mapIndexed { n, s -> Line(n + 1L, if (n % 2 == 0) "A" else "B", s.text) },
                    comics = emptyList(),
                    createdAt = now(), recordedAt = now(),
                ).also { cardStore.add(0, it) }
            }
        }
        recordings[recordingId] = RecordingProgress(RecordingStatus.DONE, created.size)
        if (created.isNotEmpty()) { // 카드가 0개면 알림을 보내지 않아요 (협의 2-3)
            _push.emit(PushPayload(PushType.CARDS_CREATED, recordingId = recordingId, cardCount = created.size, cardIds = created.map { it.id }))
        }
    }

    // ── 카드 ──
    override suspend fun cards(cursor: String?, size: Int): CardPage = lock.withLock {
        net()
        val start = cursor?.toIntOrNull() ?: 0
        val page = cardStore.drop(start).take(size).map { it.toSummary() }
        val next = (start + size).takeIf { it < cardStore.size }?.toString()
        CardPage(page, next)
    }

    override suspend fun card(id: Long): CardDetail = lock.withLock {
        net()
        findCard(id).withLiveComics()
    }

    /** 고친 값 검사: 비었거나 너무 길면 400 INVALID_REQUEST, 없는 화자면 UNKNOWN_SPEAKER (field는 제안이라 목업만 채워요) */
    private fun validate(card: CardDetail, patch: CardEdit) {
        fun bad(field: String, msg: String): Nothing = throw LologException(ErrorCodes.INVALID_REQUEST, msg, field)
        if (patch.isEmpty) throw LologException(ErrorCodes.INVALID_REQUEST, "바꾼 것이 없어요")
        val keys = card.speakers.map { it.key }.toSet()
        patch.speakers?.forEach { sp ->
            if (sp.key !in keys) throw LologException(ErrorCodes.UNKNOWN_SPEAKER, "없는 화자예요")
            val n = sp.name ?: return@forEach
            val i = card.speakers.indexOfFirst { it.key == sp.key }
            if (n.isBlank() || n.length > TextLimits.SPEAKER_NAME) bad("speakers[$i].name", "이름은 ${TextLimits.SPEAKER_NAME}자까지 쓸 수 있어요")
        }
        patch.lines?.forEach { l ->
            val i = card.lines.indexOfFirst { it.id == l.id }
            if (i < 0) bad("lines", "이 카드의 대사가 아니에요")
            if (l.speaker != null && l.speaker !in keys) throw LologException(ErrorCodes.UNKNOWN_SPEAKER, "없는 화자예요")
            // 고쳐 보낸 대사에만 글자 수를 검사해요(명세)
            l.text?.let { t ->
                if (t.isBlank()) bad("lines[$i].text", "대사를 입력해 주세요")
                if (t.length > TextLimits.LINE) bad("lines[$i].text", "대사는 ${TextLimits.LINE}자까지 쓸 수 있어요")
            }
        }
        patch.place?.let { if (it.length > TextLimits.PLACE) bad("place", "장소는 ${TextLimits.PLACE}자까지 쓸 수 있어요") }
    }

    override suspend fun updateCard(id: Long, patch: CardEdit): CardDetail = lock.withLock {
        net()
        val card = findCard(id)
        if (card.withLiveComics().locked) {
            throw LologException(ErrorCodes.CARD_LOCKED, "만화를 만드는 중이에요")
        }
        validate(card, patch)
        // 바꾼 것만 왔으니 원래 카드에 덮어써요 (명세 PATCH)
        val sp = patch.speakers?.associateBy { it.key }.orEmpty()
        val ln = patch.lines?.associateBy { it.id }.orEmpty()
        val updated = card.copy(
            speakers = card.speakers.map { sp[it.key] ?: it },
            lines = card.lines.map { old -> ln[old.id]?.let { e -> old.copy(speaker = e.speaker ?: old.speaker, text = e.text ?: old.text) } ?: old },
            place = when (val p = patch.place) { null -> card.place; "" -> null; else -> p },
        )
        cardStore[cardStore.indexOf(card)] = updated
        updated.withLiveComics()
    }

    override suspend fun deleteCard(id: Long) = lock.withLock {
        net()
        val card = findCard(id)
        // 만화 그리는 중에는 지우기도 막아요 (11/5 프론트 확정)
        if (card.withLiveComics().locked) throw LologException(ErrorCodes.CARD_LOCKED, "만화를 만드는 중이에요")
        cardStore.remove(card)
        // 카드를 지워도 만화는 남겨요(FR-LIB-03). 만화에는 cardDeleted 표시
        deletedCards[id] = card.summary
        Unit
    }

    private fun findCard(id: Long) = cardStore.firstOrNull { it.id == id }
        ?: throw LologException(ErrorCodes.CARD_NOT_FOUND, "카드를 찾지 못했어요")

    private fun CardDetail.withLiveComics(): CardDetail {
        val refs = comicStore.filter { it.cardId == id }.map { ComicRef(it.id, it.status) }
        // 서버처럼 남은 횟수를 세서 내려 줘요: 실패는 빼고, 그리는 중은 셈
        return copy(comics = refs, comicRemaining = (COMIC_LIMIT_PER_CARD - refs.count { it.status != ComicStatus.FAILED }).coerceAtLeast(0))
    }

    private fun CardDetail.toSummary() = CardSummary(
        id = id, kind = kind, summary = summary, recordedAt = recordedAt,
        comicCount = comicStore.count { it.cardId == id }, createdAt = createdAt,
        place = place, speakers = speakers,
        preview = lines.getOrNull(1)?.text ?: lines.firstOrNull()?.text,
        lineCount = lines.size,
    )

    // ── 만화 ──
    override suspend fun requestComic(cardId: Long): ComicAccepted = lock.withLock {
        net()
        if (cardId in deletedCards) throw LologException(ErrorCodes.CARD_NOT_FOUND, "원래 카드가 지워졌어요")
        findCard(cardId)
        if (comicStore.any { it.cardId == cardId && it.status.inProgress }) {
            throw LologException(ErrorCodes.COMIC_IN_PROGRESS, "지금 그리는 만화가 끝나면 다시 그릴 수 있어요")
        }
        // 실패한 만화는 3번에 안 세요 (11/5 확정)
        if (comicStore.count { it.cardId == cardId && it.status != ComicStatus.FAILED } >= COMIC_LIMIT_PER_CARD) {
            throw LologException(ErrorCodes.COMIC_LIMIT, "이 카드는 3번 다 그렸어요")
        }
        val comic = ComicDetail(nextId++, cardId, ComicStatus.QUEUED, null, now())
        comicStore.add(0, comic)
        runComic(comic.id)
        ComicAccepted(comic.id, ComicStatus.QUEUED)
    }

    private fun runComic(comicId: Long) = scope.launch {
        suspend fun set(status: ComicStatus, url: String? = null, reason: String? = null) = lock.withLock {
            val i = comicStore.indexOfFirst { it.id == comicId }
            if (i >= 0) comicStore[i] = comicStore[i].copy(
                status = status, imageUrl = url, failReason = reason,
                width = if (status == ComicStatus.DONE) COMIC_WIDTH else null,
                height = if (status == ComicStatus.DONE) COMIC_HEIGHT else null,
            )
        }
        delay(3_000); set(ComicStatus.STORYBOARD)
        delay(4_000)
        val comic = lock.withLock { comicStore.firstOrNull { it.id == comicId } } ?: return@launch
        val card = lock.withLock { cardStore.firstOrNull { it.id == comic.cardId } }
        if (card != null && card.lines.any { it.text.length > 40 }) {
            set(ComicStatus.FAILED, reason = "대사가 말풍선에 들어가지 않아요")
            _push.emit(PushPayload(PushType.COMIC_FAILED, comicId = comicId, cardId = comic.cardId))
            return@launch
        }
        set(ComicStatus.DRAWING)
        delay(5_000)
        set(ComicStatus.DONE, url = "mock://comic/$comicId")
        _push.emit(PushPayload(PushType.COMIC_DONE, comicId = comicId, cardId = comic.cardId))
    }

    override suspend fun comics(cursor: String?, size: Int): ComicPage = lock.withLock {
        net()
        val start = cursor?.toIntOrNull() ?: 0
        val page = comicStore.drop(start).take(size).map { c ->
            ComicSummary(
                c.id, c.cardId, c.status, c.imageUrl, c.createdAt ?: "",
                cardStore.firstOrNull { it.id == c.cardId }?.summary ?: deletedCards[c.cardId],
                thumbnailUrl = c.imageUrl, width = c.width, height = c.height,
                cardDeleted = c.cardId in deletedCards,
            )
        }
        ComicPage(page, (start + size).takeIf { it < comicStore.size }?.toString())
    }

    override suspend fun comic(id: Long): ComicDetail = lock.withLock {
        net(150)
        val c = comicStore.firstOrNull { it.id == id } ?: throw LologException(ErrorCodes.COMIC_NOT_FOUND, "만화를 찾지 못했어요")
        c.copy(
            cardDeleted = c.cardId in deletedCards,
            cardSummary = cardStore.firstOrNull { it.id == c.cardId }?.summary ?: deletedCards[c.cardId],
        )
    }

    override suspend fun deleteComic(id: Long) = lock.withLock {
        net()
        if (!comicStore.removeAll { it.id == id }) throw LologException(ErrorCodes.COMIC_NOT_FOUND, "만화를 찾지 못했어요")
    }

    // ── 기기 ──
    override suspend fun registerFcmToken(token: String) { net(100); tokens.fcmToken = token }
    override suspend fun unregisterFcmToken(token: String) { net(100) }

    /** 목업 만화 그림에 쓸 카드 찾기 */
    fun cardForComic(comicId: Long): CardDetail? =
        comicStore.firstOrNull { it.id == comicId }?.let { c -> cardStore.firstOrNull { it.id == c.cardId } }
}
