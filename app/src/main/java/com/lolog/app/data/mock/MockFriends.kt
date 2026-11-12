package com.lolog.app.data.mock

import com.lolog.app.data.model.*
import com.lolog.app.data.repo.LologException
import java.time.OffsetDateTime
import java.util.Locale

/**
 * 친구·프로필·차단 목업(LOLog-docs spec/friends.md). 규칙은 MockLologRepository 위 주석에 적어 뒀어요.
 * 안드로이드 없이 테스트하려고 따로 떼었어요. [nowMs]는 테스트에서 시간을 돌리려고 받아요.
 */
internal class MockFriends(private val nowMs: () -> Long = System::currentTimeMillis) {

    private var nickname: String? = null
    private val friendList = mutableListOf<Friend>()        // 최근에 친구가 된 사람이 위
    private val received = mutableListOf<FriendRequest>()   // 최신순
    private val sent = mutableListOf<FriendRequest>()       // 최신순
    private val blocked = mutableListOf<BlockedUser>()      // 최신순
    /** 친구를 끊은 사람(닉네임, 친구 코드). 끊은 뒤에 차단할 때 찾아요 */
    private val exFriends = mutableMapOf<Long, Pair<String, String>>()
    /** 코드 넣기 시각(ms). 1분에 10번까지 */
    private val attempts = ArrayDeque<Long>()
    private var nextId = 500L

    init {
        val now = OffsetDateTime.now()
        friendList += listOf(
            // 서연·민수(K2PH7RTA)는 프로필 캐릭터가 있어요(그림은 기본 화자 썸네일을 빌려 써요)
            Friend(11, "민수", "K2PH7RTA", "2026-11-03T20:10:00+09:00", MockCharacters.thumb("young-m-02")),
            Friend(12, "서연", "S5VB9GDT", "2026-10-28T18:00:00+09:00", MockCharacters.thumb("young-f-03")),
            Friend(13, "민수", "QW8ZN3EM", "2026-10-20T12:30:00+09:00"),
            Friend(14, "도윤", "D3RM6YKC", "2026-10-12T09:00:00+09:00"),
            Friend(15, "하은", "R4YB8CUE", "2026-10-01T21:40:00+09:00"),
        )
        received += listOf(
            FriendRequest(201, FriendRequestDirection.RECEIVED, FriendUser(21, "민수", "T8GX5JRW"), FriendRequestStatus.PENDING, now.minusMinutes(3).toString()),
            FriendRequest(202, FriendRequestDirection.RECEIVED, FriendUser(22, "하은", "Z9CX4WVB"), FriendRequestStatus.PENDING, now.minusHours(5).toString()),
            // 같은 방(과팀플 4인방) 멤버 민지님이 보낸 요청. 방 멤버 시트에서 "수락"으로 보여요
            FriendRequest(204, FriendRequestDirection.RECEIVED, FriendUser(52, "민지", "P6WQ3LMA"), FriendRequestStatus.PENDING, now.minusDays(1).toString()),
        )
        sent += FriendRequest(203, FriendRequestDirection.SENT, FriendUser(23, "지아", "M4TR8KXH"), FriendRequestStatus.PENDING, now.minusDays(2).toString())
        // 태오님은 같은 방(고등학교 동창)에 있고, 방에서 글을 숨겨요
        blocked += BlockedUser(31, "태오", BLOCKED_CODE, "2026-11-09T15:00:00+09:00", hideInRooms = true)
    }

    private fun now() = OffsetDateTime.now().toString()
    private fun fail(code: String, msg: String): Nothing = throw LologException(code, msg)

    /** 내 프로필 캐릭터. 처음에는 없어요(시안 15) */
    private var character: ProfileCharacter? = null
    /** 오늘 받은 만들기(202) 시각(ms). 실패한 것은 빼요 */
    private val uploads = mutableListOf<Long>()

    /** 내 회원 ID는 목업에서 "1" */
    fun profile() = Profile(nickname, if (nickname != null) MY_CODE else null, character = character, userId = MY_USER_ID.toString())

    /**
     * 사진을 올려 만들기(characters.md 3장). 만드는 중이면 409 CHARACTER_IN_PROGRESS, 오늘 3번 받았으면 409 CHARACTER_LIMIT,
     * 10MB 넘으면 413. 다시 만드는 동안은 예전 그림을 그대로 둬요
     */
    fun uploadCharacter(bytes: Int): ProfileCharacter {
        if (bytes > PHOTO_MAX_BYTES) fail(ErrorCodes.PAYLOAD_TOO_LARGE, "사진이 너무 큽니다")
        if (character?.status == ProfileCharacterStatus.PROCESSING) fail(ErrorCodes.CHARACTER_IN_PROGRESS, "캐릭터를 만드는 중이에요")
        val t = nowMs()
        uploads.removeAll { t - it >= DAY_MS } // 목업은 자정 대신 24시간으로 세요
        if (uploads.size >= CHARACTER_DAILY_LIMIT) fail(ErrorCodes.CHARACTER_LIMIT, "오늘은 캐릭터를 더 만들 수 없어요")
        uploads += t
        return ProfileCharacter(ProfileCharacterStatus.PROCESSING, character?.thumbnailUrl, now(), voice = character?.voice).also { character = it }
    }

    /** 워커가 그림을 보냄(목업은 몇 초 뒤). failed면 FAILED로 두고 이번 것은 세지 않아요. 만드는 중이 아니었으면 false */
    fun finishCharacter(failed: Boolean, thumbnailUrl: String): Boolean {
        val c = character ?: return false
        if (c.status != ProfileCharacterStatus.PROCESSING) return false
        character = if (failed) {
            uploads.removeLastOrNull()
            c.copy(status = ProfileCharacterStatus.FAILED, updatedAt = now())
        } else {
            // 다시 만들어도 고른 목소리는 그대로예요
            ProfileCharacter(ProfileCharacterStatus.READY, thumbnailUrl, now(), voice = c.voice)
        }
        return true
    }

    fun deleteCharacter() { character = null }

    /**
     * 목소리 고르기(FR-CHAR-04, 초안). voice null = 안 고름.
     * 프로필 캐릭터가 없으면 404 CHARACTER_NOT_FOUND, 없거나 목소리가 없는 기본 화자 id면 400 UNKNOWN_CHARACTER
     */
    fun setVoice(voice: String?) {
        val c = character ?: fail(ErrorCodes.CHARACTER_NOT_FOUND, "프로필 캐릭터가 없어요")
        if (voice != null && MockCharacters.defaults.none { it.id == voice && it.voiceUrl != null }) {
            fail(ErrorCodes.UNKNOWN_CHARACTER, "없는 목소리예요")
        }
        character = c.copy(voice = voice)
    }

    fun updateNickname(raw: String): Profile {
        val n = raw.trim()
        if (n.isEmpty() || n.length > FriendRules.NICKNAME_MAX) fail(ErrorCodes.INVALID_REQUEST, "닉네임은 1~10자예요")
        nickname = n
        return profile()
    }

    fun send(raw: String): FriendRequest {
        if (nickname == null) fail(ErrorCodes.PROFILE_REQUIRED, "닉네임을 먼저 정해 주세요")
        val code = raw.trim().uppercase(Locale.ROOT)
        if (code.length != FriendRules.CODE_LENGTH) fail(ErrorCodes.INVALID_REQUEST, "친구 코드는 8자예요")
        // 400·PROFILE_REQUIRED로 끝난 요청은 세지 않아요(명세)
        val t = nowMs()
        while (attempts.isNotEmpty() && t - attempts.first() >= 60_000) attempts.removeFirst()
        if (attempts.size >= 10) fail(ErrorCodes.TOO_MANY_REQUESTS, "잠시 뒤에 다시 넣어 주세요")
        attempts.addLast(t)

        if (code == MY_CODE) fail(ErrorCodes.CANNOT_FRIEND_SELF, "내 친구 코드예요")
        if (blocked.any { it.friendCode == code }) fail(ErrorCodes.USER_BLOCKED, "차단한 사람이에요")
        if (friendList.any { it.friendCode == code }) fail(ErrorCodes.ALREADY_FRIENDS, "이미 친구예요")
        if (sent.any { it.user.friendCode == code }) fail(ErrorCodes.FRIEND_REQUEST_EXISTS, "이미 요청을 보냈어요")
        // 상대가 이미 나에게 요청해 뒀으면 바로 친구(명세). 응답 id는 그 요청 id, direction은 SENT
        received.firstOrNull { it.user.friendCode == code }?.let { r ->
            if (friendList.size >= FriendRules.FRIEND_MAX) fail(ErrorCodes.FRIEND_LIMIT, "친구는 100명까지예요")
            received.remove(r)
            friendList.add(0, Friend(r.user.userId, r.user.nickname, r.user.friendCode, now()))
            return r.copy(direction = FriendRequestDirection.SENT, status = FriendRequestStatus.ACCEPTED)
        }
        if (code == JUNHO_CODE) {
            // 준호는 나에게 요청해 둔 사람으로 쳐요(받은 요청 목록에는 안 보여요)
            if (friendList.size >= FriendRules.FRIEND_MAX) fail(ErrorCodes.FRIEND_LIMIT, "친구는 100명까지예요")
            val user = FriendUser(41, "준호", JUNHO_CODE)
            friendList.add(0, Friend(user.userId, user.nickname, user.friendCode, now()))
            return FriendRequest(nextId++, FriendRequestDirection.SENT, user, FriendRequestStatus.ACCEPTED, now())
        }
        if (!code.startsWith("N")) fail(ErrorCodes.FRIEND_CODE_NOT_FOUND, "친구 코드를 찾지 못했어요")
        if (friendList.size >= FriendRules.FRIEND_MAX) fail(ErrorCodes.FRIEND_LIMIT, "친구는 100명까지예요")
        val req = FriendRequest(nextId++, FriendRequestDirection.SENT, FriendUser(nextId++, "민수", code), FriendRequestStatus.PENDING, now())
        sent.add(0, req)
        return req
    }

    /**
     * 같은 방 멤버에게 userId로 요청 보내기(초안). [roomNickname]은 지금 같은 방에 있는 사람의 닉네임이고, 없으면(방에 없으면)
     * 친구 코드를 모를 때와 같은 FRIEND_CODE_NOT_FOUND예요. 상대가 이미 나에게 요청했으면 바로 친구(ACCEPTED)
     */
    fun sendTo(userId: Long, roomNickname: String?): FriendRequest {
        if (nickname == null) fail(ErrorCodes.PROFILE_REQUIRED, "닉네임을 먼저 정해 주세요")
        if (userId == MY_USER_ID) fail(ErrorCodes.CANNOT_FRIEND_SELF, "나에게는 보낼 수 없어요")
        if (roomNickname == null) fail(ErrorCodes.FRIEND_CODE_NOT_FOUND, "지금 같은 방에 없는 사람이에요")
        if (blocked.any { it.userId == userId }) fail(ErrorCodes.USER_BLOCKED, "차단한 사람이에요")
        if (friendList.any { it.userId == userId }) fail(ErrorCodes.ALREADY_FRIENDS, "이미 친구예요")
        if (sent.any { it.user.userId == userId }) fail(ErrorCodes.FRIEND_REQUEST_EXISTS, "이미 요청을 보냈어요")
        if (friendList.size >= FriendRules.FRIEND_MAX) fail(ErrorCodes.FRIEND_LIMIT, "친구는 100명까지예요")
        received.firstOrNull { it.user.userId == userId }?.let { r ->
            received.remove(r)
            friendList.add(0, Friend(r.user.userId, r.user.nickname, r.user.friendCode, now()))
            return r.copy(direction = FriendRequestDirection.SENT, status = FriendRequestStatus.ACCEPTED)
        }
        // 방 멤버의 친구 코드는 목업에서 지어 줘요
        val req = FriendRequest(nextId++, FriendRequestDirection.SENT, FriendUser(userId, roomNickname, "R%07d".format(userId)), FriendRequestStatus.PENDING, now())
        sent.add(0, req)
        return req
    }

    /** 방 멤버 시트의 friendState(FRIEND·REQUESTED·RECEIVED·NONE·BLOCKED·ME) */
    fun friendState(userId: Long): String = when {
        userId == MY_USER_ID -> "ME"
        blocked.any { it.userId == userId } -> "BLOCKED"
        friendList.any { it.userId == userId } -> "FRIEND"
        sent.any { it.user.userId == userId } -> "REQUESTED"
        received.any { it.user.userId == userId } -> "RECEIVED"
        else -> "NONE"
    }

    /** 방에서 글을 숨기는 차단한 사람 */
    fun hiddenInRooms(): Set<Long> = blocked.filter { it.hideInRooms }.map { it.userId }.toSet()

    fun requests(direction: FriendRequestDirection): List<FriendRequest> =
        (if (direction == FriendRequestDirection.RECEIVED) received else sent).toList()

    fun accept(id: Long): Friend {
        if (nickname == null) fail(ErrorCodes.PROFILE_REQUIRED, "닉네임을 먼저 정해 주세요")
        val r = received.firstOrNull { it.id == id } ?: fail(ErrorCodes.FRIEND_REQUEST_NOT_FOUND, "요청을 찾지 못했어요")
        if (friendList.size >= FriendRules.FRIEND_MAX) fail(ErrorCodes.FRIEND_LIMIT, "친구는 100명까지예요")
        received.remove(r)
        val f = Friend(r.user.userId, r.user.nickname, r.user.friendCode, now())
        friendList.add(0, f)
        return f
    }

    fun deleteRequest(id: Long) {
        if (!received.removeAll { it.id == id } && !sent.removeAll { it.id == id }) {
            fail(ErrorCodes.FRIEND_REQUEST_NOT_FOUND, "요청을 찾지 못했어요")
        }
    }

    fun friends(): List<Friend> = friendList.toList()

    fun removeFriend(userId: Long) {
        friendList.firstOrNull { it.userId == userId }?.let { exFriends[userId] = it.nickname to it.friendCode }
        friendList.removeAll { it.userId == userId }
    }

    /**
     * 차단하면 친구·요청에서 빠지고 차단 목록 맨 위로(친구도 바로 차단돼요, friends.md 차단).
     * 친구, 요청이 오간 사람, 끊은 친구, 같은 방 멤버([roomNickname])를 차단할 수 있어요. hideInRooms는 방에서 그 사람 글을 숨길지.
     * 이미 차단한 사람이면 그대로 두고 hideInRooms도 바꾸지 않아요(friends.md 4장)
     */
    fun block(userId: Long, hideInRooms: Boolean = false, roomNickname: String? = null) {
        if (userId == MY_USER_ID) fail(ErrorCodes.CANNOT_FRIEND_SELF, "나는 차단할 수 없어요")
        if (blocked.any { it.userId == userId }) return
        val who = friendList.firstOrNull { it.userId == userId }?.let { it.nickname to it.friendCode }
            ?: (received + sent).firstOrNull { it.user.userId == userId }?.let { it.user.nickname to it.user.friendCode }
            ?: exFriends[userId]
            ?: roomNickname?.let { it to "R%07d".format(userId) }
        if (who == null) fail(ErrorCodes.USER_NOT_FOUND, "차단할 사람을 찾지 못했어요")
        friendList.removeAll { it.userId == userId }
        received.removeAll { it.user.userId == userId }
        sent.removeAll { it.user.userId == userId }
        blocked.add(0, BlockedUser(userId, who.first, who.second, now(), hideInRooms))
    }

    /** 방에서 그 사람 글 숨기기 바꾸기(초안). 차단한 사람이 아니면 USER_NOT_FOUND(목업) */
    fun setHideInRooms(userId: Long, hide: Boolean) {
        val i = blocked.indexOfFirst { it.userId == userId }
        if (i < 0) fail(ErrorCodes.USER_NOT_FOUND, "차단한 사람이 아니에요")
        blocked[i] = blocked[i].copy(hideInRooms = hide)
    }

    fun blocks(): List<BlockedUser> = blocked.toList()

    fun unblock(userId: Long) { blocked.removeAll { it.userId == userId } }

    companion object {
        const val MY_USER_ID = 1L
        const val PHOTO_MAX_BYTES = 10 * 1024 * 1024
        const val CHARACTER_DAILY_LIMIT = 3
        const val DAY_MS = 24 * 60 * 60 * 1000L
        const val MY_CODE = "A7K3QX2M"
        const val JUNHO_CODE = "J8ZN4HQW"
        const val BLOCKED_CODE = "H6NE2PQD"
    }
}
