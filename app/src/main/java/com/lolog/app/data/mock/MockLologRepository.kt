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
 * 목업 목록 한 쪽 자르기. 저장소는 최신순(id 내림차순)으로 들고 있어서 오래된 순이면 뒤집어요.
 * cursor는 시작 위치(문자열)이고, 마지막 쪽이면 다음 cursor는 null이에요.
 */
internal fun <T> mockPage(newestFirst: List<T>, cursor: String?, size: Int, sort: SortOrder): Pair<List<T>, String?> {
    val list = if (sort == SortOrder.OLDEST) newestFirst.asReversed() else newestFirst
    val start = cursor?.toIntOrNull() ?: 0
    return list.drop(start).take(size) to (start + size).takeIf { it < list.size }?.toString()
}

/** 목업에서 늘 맞는 인증번호(가입·비밀번호 찾기) */
private const val MOCK_EMAIL_CODE = "123456"

/** 인증번호 한 종류(가입·비밀번호 찾기)의 보낸 시각과 틀린 횟수. 목업 인증번호는 늘 MOCK_EMAIL_CODE */
internal class MockCodes {
    private val sentAt = mutableMapOf<String, Long>() // 이메일 → 보낸 시각(ms)
    private val fails = mutableMapOf<String, Int>()

    fun send(email: String) {
        val key = email.lowercase()
        val sent = sentAt[key]
        val nowMs = System.currentTimeMillis()
        if (sent != null && nowMs - sent < EMAIL_CODE_RESEND_SECONDS * 1000L) {
            throw LologException(ErrorCodes.TOO_MANY_REQUESTS, "잠시 뒤에 다시 받아 주세요")
        }
        sentAt[key] = nowMs
        fails.remove(key)
    }

    fun check(email: String, code: String) {
        val key = email.lowercase()
        val sent = sentAt[key]
        // 번호를 안 받았거나 10분 지났거나 5번 틀리면 만료. 시험용으로 000000도 만료
        if (sent == null || System.currentTimeMillis() - sent > 10 * 60 * 1000L || code == "000000") {
            throw LologException(ErrorCodes.CODE_EXPIRED, "인증번호를 다시 받아 주세요")
        }
        if (code != MOCK_EMAIL_CODE) {
            val n = (fails[key] ?: 0) + 1
            fails[key] = n
            if (n >= 5) { sentAt.remove(key); throw LologException(ErrorCodes.CODE_EXPIRED, "인증번호를 다시 받아 주세요") }
            throw LologException(ErrorCodes.INVALID_CODE, "인증번호가 맞지 않아요")
        }
    }

    /** 맞게 쓴 번호는 한 번만 써요 */
    fun used(email: String) { sentAt.remove(email.lowercase()); fails.remove(email.lowercase()) }
}

/** 목업 비밀번호 바꾸기 검사. 지금 비밀번호는 로그인 목업과 같은 규칙(4자 미만이면 틀림) */
internal fun mockCheckPasswordChange(currentPassword: String, newPassword: String) {
    if (currentPassword.length < 4) throw LologException(ErrorCodes.INVALID_CREDENTIALS, "지금 비밀번호가 맞지 않아요")
    if (!isValidPassword(newPassword)) throw LologException(ErrorCodes.INVALID_REQUEST, "비밀번호는 8~64자, 영문과 숫자를 섞어 주세요", "newPassword")
}

/**
 * 백엔드 없이 화면 흐름을 다 돌려 볼 수 있는 메모리 목업이에요.
 * 앱 모델을 바로 돌려줘요(서버 DTO·변환은 거치지 않음). 상태 변화(감지·만화)는 몇 초 단위로 흉내 내요.
 *
 * 오류 화면 확인용 규칙
 *  - 이메일 가입 인증번호는 123456. 다른 번호 → 400 INVALID_CODE, 000000·5번 틀림 → 400 CODE_EXPIRED
 *  - 인증번호를 60초 안에 또 받기 → 429 TOO_MANY_REQUESTS
 *  - 비밀번호 찾기 인증번호도 123456이고 위 두 규칙이 같아요(번호와 틀린 횟수는 가입과 따로 저장)
 *  - 비밀번호 바꾸기에서 지금 비밀번호가 4자 미만 → 401 INVALID_CREDENTIALS
 *  - 내 정보(me)의 hasPassword: 이메일로 로그인했으면 true, 소셜로 로그인했으면 false
 *  - 이메일 가입/로그인에 taken@lolog.app → 409 ACCOUNT_EXISTS (18번)
 *  - 비밀번호가 4자 미만 → 401 INVALID_CREDENTIALS
 *  - 네이버로 시작하기 → 409 ACCOUNT_EXISTS (18번)
 *  - 설정에서 Google 연결 → 409 SOCIAL_ALREADY_LINKED (19번), 네이버 연결 → 연결됨
 *  - 녹음 문장이 6개 미만 → 카드 0개 (20번)
 *  - 대사 중 40자 넘는 게 있으면 만화 FAILED (21번). 카드 고치기에서 줄이면 성공
 *  - 실패한 만화는 3번에 안 세요(11/5 확정). 카드를 지워도 만화는 남고 cardDeleted = true
 *
 * 친구 규칙(MockFriends.kt, LOLog-docs spec/friends.md)
 *  - 처음 내 프로필: 닉네임 없음, 코드 null. 닉네임을 정하면 코드 A7K3QX2M이 생겨요
 *  - 친구 5명: 민수(K2PH7RTA), 서연, 민수(QW8ZN3EM), 도윤, 하은
 *  - 받은 요청 2(민수, 하은 Z9CX4WVB), 보낸 요청 1(지아 M4TR8KXH), 차단 1(태오 H6NE2PQD)
 *  - 코드 넣기
 *    J8ZN4HQW → 준호와 바로 친구(ACCEPTED) / 받은 요청을 보낸 사람의 코드 → 바로 친구(ACCEPTED)
 *    내 코드 → 400 CANNOT_FRIEND_SELF / 친구 코드 → 409 ALREADY_FRIENDS / 보낸 요청 코드 → 409 FRIEND_REQUEST_EXISTS
 *    H6NE2PQD → 409 USER_BLOCKED / 그 밖 8자 중 N으로 시작 → 새 보낸 요청(PENDING), 나머지 → 404 FRIEND_CODE_NOT_FOUND
 *    60초 안 11번째 → 429 TOO_MANY_REQUESTS / 닉네임 없이 보내기·수락 → 409 PROFILE_REQUIRED
 *  - 수락하면 친구 목록 맨 위로. 차단하면 친구·요청에서 빠지고 차단 목록 맨 위로
 *
 * 화자 그림 규칙(MockCharacters.kt, LOLog-docs spec/characters.md)
 *  - 기본 화자 24명(kid-f-01~middle-m-04). label은 명세 레포 characters.json 값 그대로,
 *    썸네일은 명세 레포의 공개 그림 주소(raw.githubusercontent.com/…/<id>_thumb.webp)
 *  - 친구 중 서연·민수(K2PH7RTA)는 프로필 캐릭터 있음, 도윤·하은·민수(QW8ZN3EM)는 없음
 *  - 내 프로필: 처음엔 캐릭터 없음(시안 15), 내 회원 ID는 "1"
 *  - 프로필 캐릭터 만들기: 사진을 올리면 PROCESSING, 4초 뒤 READY(그림은 기본 화자 young-f-02를 빌려 씀) + 푸시 PROFILE_CHARACTER_READY.
 *    1MB보다 작은 사진은 실패(FAILED, 푸시 PROFILE_CHARACTER_FAILED)로 해서 실패 화면을 볼 수 있어요.
 *    만드는 중에 또 → 409 CHARACTER_IN_PROGRESS, 하루 3번 넘게 → 409 CHARACTER_LIMIT, 10MB 넘음 → 413
 *    지우면 내 카드 화자에 붙은 내 캐릭터도 빠져요
 *  - 카드 9(고양이 줌 수업)는 처음부터 화자1에 기본 화자(young-f-01)가 붙어 있어요
 *  - 카드 고치기(PATCH)에서 그림 붙이기
 *    한 카드에서 같은 type+id를 두 화자에 → 400 DUPLICATE_CHARACTER
 *    그림 없는 친구·나의 PROFILE → 409 CHARACTER_NOT_READY
 *    목록에 없는 기본 id, 친구가 아닌 회원 ID → 400 UNKNOWN_CHARACTER
 *    성공하면 화자 character에 label(기본은 label, 친구는 닉네임)과 thumbnailUrl을 채워 돌려줘요
 */
class MockLologRepository(private val tokens: TokenStore) : LologRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    private val cardStore = MockData.cards()
    private val comicStore = MockData.comics()
    private val linked = mutableSetOf<SocialProvider>()
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
    private val signupCodes = MockCodes()
    private val resetCodes = MockCodes()
    /** 마지막 로그인 방법. me()의 hasPassword를 정해요(앱을 새로 켜서 모르면 이메일로 봐요) */
    private var loggedInWithEmail = true

    override suspend fun requestEmailCode(email: String) {
        net()
        signupCodes.send(email)
    }

    override suspend fun signup(email: String, password: String, code: String) {
        net()
        signupCodes.check(email, code)
        if (email.equals("taken@lolog.app", true)) throw LologException(ErrorCodes.ACCOUNT_EXISTS, "이미 가입된 이메일이에요")
        if (!isValidPassword(password)) throw LologException(ErrorCodes.INVALID_REQUEST, "비밀번호는 8~64자, 영문과 숫자를 섞어 주세요", "password")
        loggedInWithEmail = true
        tokens.save("mock-access", "mock-refresh")
    }

    override suspend fun login(email: String, password: String) {
        net()
        // 시험용: offline@… 은 인터넷 끊김, 비밀번호 4자 미만은 틀린 비밀번호
        if (email.startsWith("offline@", true)) throw LologException(ErrorCodes.NETWORK, "인터넷 연결을 확인해 주세요")
        if (password.length < 4) throw LologException(ErrorCodes.INVALID_CREDENTIALS, "이메일이나 비밀번호가 맞지 않아요")
        loggedInWithEmail = true
        tokens.save("mock-access", "mock-refresh")
    }

    override suspend fun socialLogin(provider: SocialProvider, providerToken: String): Boolean {
        net()
        if (provider == SocialProvider.NAVER) throw LologException(ErrorCodes.ACCOUNT_EXISTS, "이미 가입된 이메일이에요")
        loggedInWithEmail = false
        tokens.save("mock-access", "mock-refresh")
        return false
    }

    override suspend fun logout() { net(150); tokens.clear() }
    override suspend fun withdraw() { net(); lock.withLock { cardStore.clear(); comicStore.clear() }; tokens.clear() }

    override suspend fun me(): Account { net(150); return Account(MockData.EMAIL, linked.toSet(), hasPassword = loggedInWithEmail) }

    override suspend fun linkSocial(provider: SocialProvider, providerToken: String): Set<SocialProvider> {
        net()
        if (provider == SocialProvider.GOOGLE) {
            throw LologException(ErrorCodes.SOCIAL_ALREADY_LINKED, "이미 다른 LOLog 계정에 연결된 Google 계정이에요")
        }
        linked += provider
        return linked.toSet()
    }

    override suspend fun requestPasswordResetCode(email: String) {
        net()
        resetCodes.send(email)
    }

    override suspend fun resetPassword(email: String, code: String, newPassword: String) {
        net()
        resetCodes.check(email, code)
        if (!isValidPassword(newPassword)) throw LologException(ErrorCodes.INVALID_REQUEST, "비밀번호는 8~64자, 영문과 숫자를 섞어 주세요", "newPassword")
        resetCodes.used(email)
    }

    override suspend fun changePassword(currentPassword: String, newPassword: String) {
        net()
        mockCheckPasswordChange(currentPassword, newPassword)
        tokens.save("mock-access", "mock-refresh")
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
    override suspend fun cards(cursor: String?, size: Int, sort: SortOrder): CardPage = lock.withLock {
        net()
        val (page, next) = mockPage(cardStore, cursor, size, sort)
        CardPage(page.map { it.toSummary() }, next)
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
        patch.characters?.keys?.forEach { if (it !in keys) throw LologException(ErrorCodes.UNKNOWN_SPEAKER, "없는 화자예요") }
    }

    override suspend fun updateCard(id: Long, patch: CardEdit): CardDetail = lock.withLock {
        net()
        val card = findCard(id)
        if (card.withLiveComics().locked) {
            throw LologException(ErrorCodes.CARD_LOCKED, "만화를 만드는 중이에요")
        }
        validate(card, patch)
        // 바꾼 것만 왔으니 원래 카드에 덮어써요 (명세 PATCH). 이름과 그림은 따로 덮어써요
        val speakers = MockCharacters.applySpeakers(card.speakers, patch, friendBook.friends(), friendBook.profile())
        val ln = patch.lines?.associateBy { it.id }.orEmpty()
        val updated = card.copy(
            speakers = speakers,
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

    override suspend fun comics(cursor: String?, size: Int, sort: SortOrder): ComicPage = lock.withLock {
        net()
        val (items, next) = mockPage(comicStore, cursor, size, sort)
        val page = items.map { c ->
            ComicSummary(
                c.id, c.cardId, c.status, c.imageUrl, c.createdAt ?: "",
                cardStore.firstOrNull { it.id == c.cardId }?.summary ?: deletedCards[c.cardId],
                thumbnailUrl = c.imageUrl, width = c.width, height = c.height,
                cardDeleted = c.cardId in deletedCards,
            )
        }
        ComicPage(page, next)
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

    // ── 친구 ──
    private val friendBook = MockFriends()

    override suspend fun profile(): Profile = lock.withLock { net(150); friendBook.profile() }
    override suspend fun updateNickname(nickname: String): Profile = lock.withLock { net(); friendBook.updateNickname(nickname) }

    override suspend fun uploadProfileCharacter(photo: ByteArray): ProfileCharacter = lock.withLock {
        net(800)
        friendBook.uploadCharacter(photo.size).also {
            val failed = photo.size < 1024 * 1024
            scope.launch {
                delay(4_000)
                val done = lock.withLock { friendBook.finishCharacter(failed, MockCharacters.thumb("young-f-02")) }
                if (done) _push.emit(PushPayload(if (failed) PushType.PROFILE_CHARACTER_FAILED else PushType.PROFILE_CHARACTER_READY))
            }
        }
    }

    override suspend fun deleteProfileCharacter() = lock.withLock {
        net()
        friendBook.deleteCharacter()
        // 서버처럼 내 카드 화자에서 내 캐릭터를 빼요
        val me = MockFriends.MY_USER_ID.toString()
        cardStore.replaceAll { card ->
            card.copy(speakers = card.speakers.map { sp ->
                if (sp.character?.type == CharacterType.PROFILE && sp.character.id == me) sp.copy(character = null) else sp
            })
        }
    }
    override suspend fun sendFriendRequest(code: String): FriendRequest = lock.withLock { net(); friendBook.send(code) }
    override suspend fun friendRequests(direction: FriendRequestDirection): List<FriendRequest> =
        lock.withLock { net(150); friendBook.requests(direction) }
    override suspend fun acceptFriendRequest(id: Long): Friend = lock.withLock { net(); friendBook.accept(id) }
    override suspend fun deleteFriendRequest(id: Long) = lock.withLock { net(); friendBook.deleteRequest(id) }
    override suspend fun friends(): List<Friend> = lock.withLock { net(); friendBook.friends() }
    override suspend fun removeFriend(userId: Long) = lock.withLock { net(); friendBook.removeFriend(userId) }
    override suspend fun block(userId: Long) = lock.withLock { net(); friendBook.block(userId) }
    override suspend fun blocks(): List<BlockedUser> = lock.withLock { net(150); friendBook.blocks() }
    override suspend fun unblock(userId: Long) = lock.withLock { net(); friendBook.unblock(userId) }

    // ── 화자 그림 ──
    override suspend fun defaultCharacters(): List<DefaultCharacter> { net(); return MockCharacters.defaults }

    // ── 기기 ──
    override suspend fun registerFcmToken(token: String) { net(100); tokens.fcmToken = token }
    override suspend fun unregisterFcmToken(token: String) { net(100) }

    /** 목업 만화 그림에 쓸 카드 찾기 */
    fun cardForComic(comicId: Long): CardDetail? =
        comicStore.firstOrNull { it.id == comicId }?.let { c -> cardStore.firstOrNull { it.id == c.cardId } }
}
