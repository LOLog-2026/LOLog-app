package com.lolog.app.data.mock

import com.lolog.app.data.model.*
import com.lolog.app.data.repo.LologException
import com.lolog.app.data.repo.LologRepository
import com.lolog.app.data.repo.RoomRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.OffsetDateTime

/**
 * 친구 방 목업(시안 20~24). 명세·서버가 없어서 앱 안에서만 돌아요.
 *
 * 목업 규칙
 *  - 방 인원은 나 포함 2~6명(멤버 + 답을 기다리는 초대), 방 이름 1~20자, 한 사람의 방은 20개까지
 *  - 방을 만들면 만든 사람이 방장이고, 고른 친구는 멤버가 아니라 초대로 들어가요
 *  - 방장만 초대·초대 취소를 해요(아니면 ROOM_OWNER_ONLY). 이미 멤버인 사람은 초대 못 하고, 이미 초대한 사람은 그대로 둬요
 *  - 받은 초대는 수락하면 그 방에 들어가고, 거절하면 초대만 없어져요. 없거나 이미 답한 초대는 ROOM_INVITE_NOT_FOUND
 *  - 만화는 쌓여요(덮어쓰지 않음). 한 사람이 올리는 수는 제한 없음. 같은 만화를 같은 방에 두 번 올릴 수는 없어요
 *  - 올린 사람만 내릴 수 있어요. 내리면 그 만화의 좋아요·댓글(대화 줄)도 같이 사라져요
 *  - 좋아요는 한 사람이 만화 하나에 한 번(다시 누르면 취소). 내 만화에도 할 수 있어요
 *
 * [myComic]은 내 만화(완성된 것)의 제목·그림을, [friends]는 방 만들기에서 고른 친구 이름을 찾을 때 써요.
 * [netMs]는 네트워크 흉내 대기 시간이에요(테스트에서는 0).
 */
class MockRooms(
    private val myComic: suspend (Long) -> MyComic? = { null },
    private val friends: suspend () -> List<Friend> = { emptyList() },
    private val netMs: Long = 300,
) : RoomRepository {

    /** 방에 올릴 내 만화 */
    data class MyComic(val comicId: Long, val title: String, val imageUrl: String?, val mockLines: List<String>)

    private class RoomData(
        val id: Long,
        var name: String,
        val members: MutableList<RoomMember>,
        var lastActivity: String,
        var lastActivityAt: String,
        var unread: Int,
        var ownerId: Long?,
        /** 방장이 보낸, 답을 기다리는 초대(invitedUser만 있음) */
        val invites: MutableList<RoomInvite> = mutableListOf(),
        val posts: MutableList<RoomPost> = mutableListOf(),   // 최근에 올린 것이 앞
        val messages: MutableList<RoomMessage> = mutableListOf(), // 오래된 것이 앞
        /** 만화별 좋아요 누른 사람 */
        val likes: MutableMap<Long, MutableSet<Long>> = mutableMapOf(),
    ) {
        val headcount get() = members.size + invites.size

        fun toRoom() = Room(
            id, name, members.map { it.copy(isOwner = it.userId == ownerId) }, lastActivity, lastActivityAt, unread,
            ownerId = ownerId,
            // 답을 기다리는 초대는 방장에게만 보여요
            invites = if (ownerId == ME.userId) invites.toList() else emptyList(),
        )
    }

    /** 내가 받은 초대. 수락하면 [room]이 방 목록에 들어가요 */
    private class Received(val invite: RoomInvite, val room: RoomData)

    private val lock = Mutex()
    private val rooms = mutableListOf<RoomData>()
    private val received = mutableListOf<Received>()
    private var nextId = 1_000L

    init { seed() }

    private suspend fun net() { if (netMs > 0) delay(netMs) }
    private fun fail(code: String, msg: String): Nothing = throw LologException(code, msg)
    private fun now() = OffsetDateTime.now().toString()
    private fun ago(minutes: Long) = OffsetDateTime.now().minusMinutes(minutes).toString()

    private fun findRoom(id: Long) = rooms.firstOrNull { it.id == id } ?: fail(RoomErrorCodes.ROOM_NOT_FOUND, "방을 찾지 못했어요")
    private fun findPost(postId: Long): Pair<RoomData, RoomPost> {
        for (r in rooms) r.posts.firstOrNull { it.id == postId }?.let { return r to it }
        fail(RoomErrorCodes.ROOM_POST_NOT_FOUND, "만화를 찾지 못했어요")
    }

    private fun RoomData.requireOwner() {
        if (ownerId != ME.userId) fail(RoomErrorCodes.ROOM_OWNER_ONLY, "방장만 할 수 있어요")
    }

    private fun checkRoomLimit() {
        if (rooms.size >= RoomRules.ROOM_MAX) fail(RoomErrorCodes.ROOM_LIMIT, "방은 20개까지 들어갈 수 있어요")
    }

    private fun findReceived(inviteId: Long) =
        received.firstOrNull { it.invite.id == inviteId } ?: fail(RoomErrorCodes.ROOM_INVITE_NOT_FOUND, "초대를 찾을 수 없어요")

    private fun RoomData.touch(text: String) { lastActivity = text; lastActivityAt = now() }

    private fun RoomData.replacePost(p: RoomPost) {
        val i = posts.indexOfFirst { it.id == p.id }
        if (i >= 0) posts[i] = p
    }

    override suspend fun rooms(): List<Room> = lock.withLock {
        net()
        rooms.sortedByDescending { OffsetDateTime.parse(it.lastActivityAt) }.map { it.toRoom() }
    }

    override suspend fun createRoom(name: String, memberIds: List<Long>): Room = lock.withLock {
        net()
        val n = name.trim()
        if (n.isEmpty() || n.length > RoomRules.NAME_MAX) fail(ErrorCodes.INVALID_REQUEST, "방 이름은 1~20자예요")
        val ids = memberIds.distinct().filter { it != MockFriends.MY_USER_ID }
        if (ids.size + 1 < RoomRules.MEMBER_MIN) fail(ErrorCodes.INVALID_REQUEST, "친구를 한 명 이상 골라 주세요")
        if (ids.size + 1 > RoomRules.MEMBER_MAX) fail(RoomErrorCodes.ROOM_MEMBER_LIMIT, "나를 포함해 6명까지예요")
        checkRoomLimit()
        val invitees = friendMembers(ids)
        // 만든 사람(나)이 방장. 고른 친구는 초대로 들어가요
        val room = RoomData(nextId++, n, mutableListOf(ME), "방을 만들었어요", now(), 0, ownerId = ME.userId)
        invitees.forEach { room.invites += sentInvite(room, it) }
        room.messages += RoomMessage.System(nextId++, now(), "방을 만들었어요")
        rooms.add(0, room)
        room.toRoom()
    }

    override suspend fun room(id: Long): Room = lock.withLock { net(); findRoom(id).toRoom() }

    override suspend fun posts(roomId: Long): List<RoomPost> = lock.withLock { net(); findRoom(roomId).posts.toList() }

    override suspend fun postComic(roomId: Long, comicId: Long): RoomPost = lock.withLock {
        net()
        val room = findRoom(roomId)
        if (room.posts.any { it.comicId == comicId }) fail(RoomErrorCodes.ROOM_POST_EXISTS, "이미 이 방에 올린 만화예요")
        val c = myComic(comicId) ?: fail(ErrorCodes.COMIC_NOT_FOUND, "만화를 찾지 못했어요")
        val post = RoomPost(nextId++, roomId, comicId, c.title, c.imageUrl, ME, now(), 0, false, 0, c.mockLines)
        room.posts.add(0, post)
        room.messages += RoomMessage.System(nextId++, now(), "${ME.subject} 만화를 올렸어요", post.id)
        room.touch("${ME.subject} 만화를 올렸어요")
        post
    }

    override suspend fun removePost(postId: Long) = lock.withLock {
        net()
        val (room, post) = findPost(postId)
        if (!post.author.isMe) fail(RoomErrorCodes.ROOM_POST_NOT_MINE, "올린 사람만 내릴 수 있어요")
        room.posts.remove(post)
        room.likes.remove(postId)
        room.messages.removeAll { it.postId == postId }
        Unit
    }

    override suspend fun toggleLike(postId: Long): RoomPost = lock.withLock {
        net()
        val (room, post) = findPost(postId)
        val who = room.likes.getOrPut(postId) { mutableSetOf() }
        val liked = if (ME.userId in who) {
            who.remove(ME.userId)
            // 취소하면 대화의 내 좋아요 줄도 빼요
            room.messages.removeAll { it is RoomMessage.Like && it.postId == postId && it.member.isMe }
            false
        } else {
            who.add(ME.userId)
            room.messages += RoomMessage.Like(nextId++, now(), ME, postId)
            room.touch("${ME.subject} 만화를 좋아해요")
            true
        }
        val updated = post.copy(likeCount = who.size, likedByMe = liked)
        room.replacePost(updated)
        updated
    }

    override suspend fun messages(roomId: Long): List<RoomMessage> = lock.withLock {
        net()
        val room = findRoom(roomId)
        room.unread = 0
        room.messages.toList()
    }

    override suspend fun sendComment(roomId: Long, postId: Long?, text: String): RoomMessage.Comment = lock.withLock {
        net()
        val room = findRoom(roomId)
        val t = text.trim()
        if (t.isEmpty() || t.length > RoomRules.COMMENT_MAX) fail(ErrorCodes.INVALID_REQUEST, "댓글은 1~200자예요")
        val post = postId?.let { id ->
            val p = room.posts.firstOrNull { it.id == id } ?: fail(RoomErrorCodes.ROOM_POST_NOT_FOUND, "만화를 찾지 못했어요")
            p.copy(commentCount = p.commentCount + 1).also { room.replacePost(it) }
        }
        val msg = RoomMessage.Comment(nextId++, now(), ME, t, post)
        room.messages += msg
        room.touch("나 \"$t\"")
        msg
    }

    override suspend fun leaveRoom(roomId: Long) = lock.withLock {
        net()
        rooms.remove(findRoom(roomId))
        Unit
    }

    // ── 방장과 초대 ──────────────────────────────────

    /** 친구 ID → 방 멤버 모양. 친구가 아니면 USER_NOT_FOUND */
    private suspend fun friendMembers(ids: List<Long>): List<RoomMember> {
        val book = friends().associateBy { it.userId }
        return ids.map { id ->
            val f = book[id] ?: fail(ErrorCodes.USER_NOT_FOUND, "친구를 찾지 못했어요")
            RoomMember(f.userId, f.nickname, f.thumbnailUrl)
        }
    }

    private fun sentInvite(room: RoomData, who: RoomMember) =
        RoomInvite(nextId++, room.id, room.name, invitedUser = who, invitedBy = null, hasBlockedMember = false, createdAt = now())

    override suspend fun invite(roomId: Long, userIds: List<Long>): List<RoomInvite> = lock.withLock {
        net()
        val room = findRoom(roomId)
        room.requireOwner()
        val ids = userIds.distinct()
        if (ids.isEmpty() || MockFriends.MY_USER_ID in ids) fail(ErrorCodes.INVALID_REQUEST, "초대할 친구를 한 명 이상 골라 주세요")
        val people = friendMembers(ids)
        if (people.any { p -> room.members.any { it.userId == p.userId } }) fail(ErrorCodes.INVALID_REQUEST, "이미 방에 있는 사람이에요")
        // 이미 초대한 사람은 그대로 두고, 새로 초대할 사람만 세요
        val fresh = people.filter { p -> room.invites.none { it.invitedUser?.userId == p.userId } }
        if (room.headcount + fresh.size > RoomRules.MEMBER_MAX) fail(RoomErrorCodes.ROOM_MEMBER_LIMIT, "나를 포함해 6명까지예요")
        fresh.forEach { room.invites += sentInvite(room, it) }
        room.invites.filter { inv -> inv.invitedUser?.userId?.let { it in ids } == true }
    }

    override suspend fun cancelInvite(roomId: Long, inviteId: Long) = lock.withLock {
        net()
        val room = findRoom(roomId)
        room.requireOwner()
        if (!room.invites.removeAll { it.id == inviteId }) fail(RoomErrorCodes.ROOM_INVITE_NOT_FOUND, "초대를 찾을 수 없어요")
        Unit
    }

    override suspend fun myInvites(): List<RoomInvite> = lock.withLock { net(); received.map { it.invite } }

    override suspend fun acceptInvite(inviteId: Long): Room = lock.withLock {
        net()
        val r = findReceived(inviteId)
        checkRoomLimit()
        received.remove(r)
        val room = r.room
        room.members += ME
        room.unread = 0 // 들어온 직후에는 0
        rooms.add(0, room)
        room.toRoom()
    }

    override suspend fun declineInvite(inviteId: Long) = lock.withLock {
        net()
        // 초대만 없어져요. 방장에게 알리지 않아요
        received.remove(findReceived(inviteId))
        Unit
    }

    // ── 시안 20·21·24와 방장·초대 시안의 목업 데이터 ──────────────────────────

    private fun seed() {
        val lines = MockData.cards().associate { c -> c.id to c.lines.map { it.text } }
        val jun = RoomMember(51, "준")
        val minji = RoomMember(52, "민지")
        val seoyeon = RoomMember(12, "서연", MockCharacters.thumb("young-f-03"))
        val minsu = RoomMember(11, "민수", MockCharacters.thumb("young-m-02"))
        val doyun = RoomMember(14, "도윤")
        val haeun = RoomMember(15, "하은")
        val taeo = RoomMember(31, "태오") // 내가 차단한 사람(MockFriends)

        // 과팀플 4인방(시안 21·24)
        val team = RoomData(1, "과팀플 4인방", mutableListOf(ME, jun, minji, seoyeon), "준님이 만화를 올렸어요", ago(3), 2, ownerId = ME.userId)
        // 내가 방장인 방: 하은님에게 보낸 초대가 답을 기다려요
        team.invites += RoomInvite(401, 1, "과팀플 4인방", invitedUser = haeun, invitedBy = null, hasBlockedMember = false, createdAt = ago(30))
        val mint = RoomPost(101, 1, 9_001, "카페에 민트초코를 넣은 이유", "mock://comic/9001", jun, ago(3), 3, false, 2, lines[12].orEmpty())
        team.posts += listOf(
            mint,
            RoomPost(102, 1, 9_002, "고양이가 줌 수업에 출석함", "mock://comic/9002", minji, ago(60), 5, true, 4, lines[9].orEmpty()),
            // 내 만화(보관함의 카드 10번 만화)
            RoomPost(103, 1, 28, "엘리베이터에서 교수님께 \"엄마\"라고 부름", "mock://comic/28", ME, ago(180), 2, false, 0, lines[10].orEmpty()),
            RoomPost(104, 1, 9_004, "첫 MT 버스에서 부른 노래", "mock://comic/9004", seoyeon, ago(60 * 26), 1, false, 1, lines[11].orEmpty()),
        )
        team.likes += mapOf(
            101L to mutableSetOf(52L, 12L, 51L),
            102L to mutableSetOf(1L, 51L, 12L, 52L, 99L),
            103L to mutableSetOf(51L, 52L),
            104L to mutableSetOf(52L),
        )
        team.messages += listOf(
            RoomMessage.System(201, ago(9), "준님이 만화를 올렸어요", 101),
            RoomMessage.Comment(202, ago(8), minji, "ㅋㅋㅋㅋ 꿈에서였다고?? 미쳤다", mint),
            RoomMessage.Like(203, ago(7), seoyeon, 101),
            RoomMessage.Comment(204, ago(5), jun, "진짜 꿈에서 들은 건데...", mint),
            RoomMessage.Comment(205, ago(3), ME, "이거 우리 단톡방에도 올려야 함", null),
        )

        // 고등학교 동창
        val school = RoomData(2, "고등학교 동창", mutableListOf(ME, haeun, doyun), "하은님 \"ㅋㅋㅋ 이거 나잖아\"", ago(60), 5, ownerId = haeun.userId)
        val taxi = RoomPost(111, 2, 9_011, "택시 기사님의 인생 상담", "mock://comic/9011", doyun, ago(90), 1, false, 1, lines[7].orEmpty())
        school.posts += taxi
        school.likes[111] = mutableSetOf(15L)
        school.messages += listOf(
            RoomMessage.System(211, ago(90), "도윤님이 만화를 올렸어요", 111),
            RoomMessage.Like(212, ago(70), haeun, 111),
            RoomMessage.Comment(213, ago(60), haeun, "ㅋㅋㅋ 이거 나잖아", taxi),
        )

        // 우리 가족, 동아리 MT
        val family = RoomData(3, "우리 가족", mutableListOf(ME, minsu, haeun), "민수님이 만화를 좋아해요", ago(60 * 24), 0, ownerId = minsu.userId)
        val club = RoomData(4, "동아리 MT", mutableListOf(ME, seoyeon, doyun, minsu), "도윤님이 만화를 올렸어요", ago(60 * 24 * 9), 0, ownerId = seoyeon.userId)
        val bus = RoomPost(121, 4, 9_021, "첫 MT 버스에서 부른 노래", "mock://comic/9021", doyun, ago(60 * 24 * 9), 0, false, 0, lines[11].orEmpty())
        club.posts += bus
        club.messages += RoomMessage.System(221, ago(60 * 24 * 9), "도윤님이 만화를 올렸어요", 121)

        rooms += listOf(team, school, family, club)

        // 받은 초대: 준님의 "주말 카레팟"(멤버 3명), 민지님의 "동아리"(내가 차단한 태오님이 있어요)
        val curry = RoomData(5, "주말 카레팟", mutableListOf(jun, seoyeon, doyun), "준님이 방을 만들었어요", ago(1), 0, ownerId = jun.userId)
        curry.messages += RoomMessage.System(231, ago(1), "준님이 방을 만들었어요")
        val clubRoom = RoomData(6, "동아리", mutableListOf(minji, taeo, haeun), "민지님이 방을 만들었어요", ago(60 * 5), 0, ownerId = minji.userId)
        clubRoom.messages += RoomMessage.System(241, ago(60 * 5), "민지님이 방을 만들었어요")
        received += Received(RoomInvite(501, 5, curry.name, invitedUser = null, invitedBy = jun.copy(isOwner = true), hasBlockedMember = false, createdAt = ago(1), memberCount = 3), curry)
        received += Received(RoomInvite(502, 6, clubRoom.name, invitedUser = null, invitedBy = minji.copy(isOwner = true), hasBlockedMember = true, createdAt = ago(60 * 5), memberCount = 3), clubRoom)
    }

    companion object {
        /** 나. 목업 회원 ID는 MockFriends와 같은 1 */
        val ME = RoomMember(MockFriends.MY_USER_ID, "나", isMe = true)

        /** 앱에서 쓰는 목업: 내 만화와 친구 목록은 목업 저장소에서 찾아요 */
        fun of(repo: LologRepository) = MockRooms(
            myComic = { id ->
                val c = repo.comic(id)
                if (c.status != ComicStatus.DONE) null
                else {
                    val card = runCatching { repo.card(c.cardId) }.getOrNull()
                    MyComic(id, card?.summary ?: c.cardSummary ?: "4컷만화", c.imageUrl, card?.lines?.map { it.text }.orEmpty())
                }
            },
            friends = { repo.friends() },
        )
    }
}
