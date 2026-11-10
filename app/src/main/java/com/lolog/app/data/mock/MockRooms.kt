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
 *  - 방 인원은 나 포함 2~6명, 방 이름 1~20자
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
        val members: List<RoomMember>,
        var lastActivity: String,
        var lastActivityAt: String,
        var unread: Int,
        val posts: MutableList<RoomPost> = mutableListOf(),   // 최근에 올린 것이 앞
        val messages: MutableList<RoomMessage> = mutableListOf(), // 오래된 것이 앞
        /** 만화별 좋아요 누른 사람 */
        val likes: MutableMap<Long, MutableSet<Long>> = mutableMapOf(),
    ) {
        fun toRoom() = Room(id, name, members, lastActivity, lastActivityAt, unread)
    }

    private val lock = Mutex()
    private val rooms = mutableListOf<RoomData>()
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
        val book = friends().associateBy { it.userId }
        val members = listOf(ME) + ids.map { id ->
            val f = book[id] ?: fail(ErrorCodes.USER_NOT_FOUND, "친구를 찾지 못했어요")
            RoomMember(f.userId, f.nickname, f.thumbnailUrl)
        }
        val room = RoomData(nextId++, n, members, "방을 만들었어요", now(), 0)
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

    // ── 시안 20·21·24의 목업 데이터 ──────────────────────────

    private fun seed() {
        val lines = MockData.cards().associate { c -> c.id to c.lines.map { it.text } }
        val jun = RoomMember(51, "준")
        val minji = RoomMember(52, "민지")
        val seoyeon = RoomMember(12, "서연", MockCharacters.thumb("young-f-03"))
        val minsu = RoomMember(11, "민수", MockCharacters.thumb("young-m-02"))
        val doyun = RoomMember(14, "도윤")
        val haeun = RoomMember(15, "하은")

        // 과팀플 4인방(시안 21·24)
        val team = RoomData(1, "과팀플 4인방", listOf(ME, jun, minji, seoyeon), "준님이 만화를 올렸어요", ago(3), 2)
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
        val school = RoomData(2, "고등학교 동창", listOf(ME, haeun, doyun), "하은님 \"ㅋㅋㅋ 이거 나잖아\"", ago(60), 5)
        val taxi = RoomPost(111, 2, 9_011, "택시 기사님의 인생 상담", "mock://comic/9011", doyun, ago(90), 1, false, 1, lines[7].orEmpty())
        school.posts += taxi
        school.likes[111] = mutableSetOf(15L)
        school.messages += listOf(
            RoomMessage.System(211, ago(90), "도윤님이 만화를 올렸어요", 111),
            RoomMessage.Like(212, ago(70), haeun, 111),
            RoomMessage.Comment(213, ago(60), haeun, "ㅋㅋㅋ 이거 나잖아", taxi),
        )

        // 우리 가족, 동아리 MT
        val family = RoomData(3, "우리 가족", listOf(ME, minsu, haeun), "민수님이 만화를 좋아해요", ago(60 * 24), 0)
        val club = RoomData(4, "동아리 MT", listOf(ME, seoyeon, doyun, minsu), "도윤님이 만화를 올렸어요", ago(60 * 24 * 9), 0)
        val bus = RoomPost(121, 4, 9_021, "첫 MT 버스에서 부른 노래", "mock://comic/9021", doyun, ago(60 * 24 * 9), 0, false, 0, lines[11].orEmpty())
        club.posts += bus
        club.messages += RoomMessage.System(221, ago(60 * 24 * 9), "도윤님이 만화를 올렸어요", 121)

        rooms += listOf(team, school, family, club)
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
