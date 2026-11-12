package com.lolog.app.data

import com.lolog.app.data.mock.MockFriends
import com.lolog.app.data.mock.MockRooms
import com.lolog.app.data.model.ErrorCodes
import com.lolog.app.data.model.Friend
import com.lolog.app.data.model.RoomErrorCodes
import com.lolog.app.data.model.RoomMessage
import com.lolog.app.data.model.RoomRules
import com.lolog.app.data.repo.LologException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 친구 방 목업 규칙(MockRooms 위 주석)이 그대로 도는지 본다. */
class MockRoomsTest {

    private val myComic = MockRooms.MyComic(31, "카레에 민트초코를 넣은 이유", "mock://comic/31", listOf("카레에? 민트초코를?"))
    private val friends = (11L..16L).map { Friend(it, "친구$it", "CODE00$it", "2026-11-01T10:00:00+09:00") }

    private fun rooms() = MockRooms(
        myComic = { id -> if (id == myComic.comicId) myComic else null },
        friends = { friends },
        netMs = 0,
    )

    private fun errorCode(block: suspend () -> Unit): String? = runBlocking {
        try { block(); null } catch (e: LologException) { e.code }
    }

    /** 과팀플 4인방(목업 방 1번) */
    private val team = 1L

    @Test
    fun 같은_만화를_같은_방에_두_번_올리면_막힌다() = runBlocking {
        val r = rooms()
        val post = r.postComic(team, 31)
        assertEquals(post.id, r.posts(team).first().id) // 맨 앞에 생겨요
        assertEquals(RoomErrorCodes.ROOM_POST_EXISTS, errorCode { r.postComic(team, 31) })
        // 다른 방에는 올릴 수 있어요
        r.postComic(2, 31)
        Unit
    }

    @Test
    fun 내리면_그_만화의_좋아요와_댓글이_같이_빠진다() = runBlocking {
        val r = rooms()
        val post = r.postComic(team, 31)
        r.toggleLike(post.id)
        r.sendComment(team, post.id, "내 만화 어때")
        assertTrue(r.messages(team).any { it.postId == post.id })

        r.removePost(post.id)
        assertFalse(r.posts(team).any { it.id == post.id })
        assertFalse(r.messages(team).any { it.postId == post.id })
    }

    @Test
    fun 남의_만화는_내릴_수_없다() = runBlocking {
        val r = rooms()
        val others = r.posts(team).first { !it.author.isMe }
        assertEquals(RoomErrorCodes.ROOM_POST_NOT_MINE, errorCode { r.removePost(others.id) })
        assertTrue(r.posts(team).any { it.id == others.id })
    }

    @Test
    fun 좋아요를_다시_누르면_취소된다() = runBlocking {
        val r = rooms()
        val post = r.posts(team).first { !it.likedByMe }
        val liked = r.toggleLike(post.id)
        assertTrue(liked.likedByMe)
        assertEquals(post.likeCount + 1, liked.likeCount)
        assertTrue(r.messages(team).any { it is RoomMessage.Like && it.postId == post.id && it.member.isMe })

        val undone = r.toggleLike(post.id)
        assertFalse(undone.likedByMe)
        assertEquals(post.likeCount, undone.likeCount)
        assertFalse(r.messages(team).any { it is RoomMessage.Like && it.postId == post.id && it.member.isMe })
    }

    @Test
    fun 대화는_50줄씩_주고_커서로_그_앞_줄을_준다() = runBlocking {
        val r = rooms()
        repeat(60) { r.sendComment(team, null, "댓글 $it") }
        val all = r.messages(team)
        val first = r.messagePage(team)
        assertEquals(MockRooms.PAGE, first.messages.size)
        assertEquals(all.takeLast(MockRooms.PAGE).map { it.id }, first.messages.map { it.id })
        assertNotNull(first.nextCursor)
        val older = r.messagePage(team, first.nextCursor)
        assertEquals(all.dropLast(MockRooms.PAGE).takeLast(MockRooms.PAGE).map { it.id }, older.messages.map { it.id })
        assertTrue(older.messages.all { it.id < first.messages.first().id })
    }

    @Test
    fun 댓글을_보내면_대화에_캡션_달린_줄이_생긴다() = runBlocking {
        val r = rooms()
        val post = r.posts(team).first()
        val sent = r.sendComment(team, post.id, "  ㅋㅋㅋ 이거 뭐야  ")
        val last = r.messages(team).last() as RoomMessage.Comment
        assertEquals(sent.id, last.id)
        assertEquals("ㅋㅋㅋ 이거 뭐야", last.text)
        assertNotNull(last.post)
        assertEquals(post.id, last.post!!.id)
        assertEquals(post.commentCount + 1, r.posts(team).first { it.id == post.id }.commentCount)
    }

    @Test
    fun 방_인원은_나_포함_6명까지() = runBlocking {
        val r = rooms()
        assertEquals(RoomErrorCodes.ROOM_MEMBER_LIMIT, errorCode { r.createRoom("너무 많은 방", friends.map { it.userId }) })
        val room = r.createRoom("  새 방  ", friends.take(5).map { it.userId })
        assertEquals("새 방", room.name)
        assertEquals(6, room.headcount)
        assertEquals(room.id, r.rooms().first().id)
    }

    @Test
    fun 방을_만들면_나는_방장이고_고른_친구는_초대로_들어간다() = runBlocking {
        val r = rooms()
        val room = r.createRoom("새 방", listOf(11L, 12L))
        assertEquals(listOf(MockFriends.MY_USER_ID), room.members.map { it.userId })
        assertTrue(room.members.single().isOwner)
        assertTrue(room.isMineOwner)
        assertEquals(MockFriends.MY_USER_ID, room.ownerId)
        assertEquals(listOf(11L, 12L), room.invites.map { it.invitedUser?.userId })
    }

    @Test
    fun 받은_초대를_수락하면_멤버가_되고_초대가_사라진다() = runBlocking {
        val r = rooms()
        val invite = r.myInvites().first { it.roomName == "주말 카레팟" }
        assertFalse(r.rooms().any { it.id == invite.roomId })

        val room = r.acceptInvite(invite.id)
        assertEquals(invite.roomId, room.id)
        assertTrue(room.members.any { it.isMe })
        assertFalse(room.isMineOwner) // 방장은 초대한 준님
        assertTrue(room.members.first { it.userId == invite.invitedBy?.userId }.isOwner)
        assertTrue(r.rooms().any { it.id == room.id })
        assertFalse(r.myInvites().any { it.id == invite.id })
    }

    @Test
    fun 받은_초대를_거절하면_초대만_사라진다() = runBlocking {
        val r = rooms()
        val before = r.rooms().map { it.id }
        val invite = r.myInvites().first()
        r.declineInvite(invite.id)
        assertFalse(r.myInvites().any { it.id == invite.id })
        assertEquals(before, r.rooms().map { it.id })
    }

    @Test
    fun 차단한_사람이_있는_방의_초대는_알려_준다() = runBlocking {
        val invites = rooms().myInvites()
        assertTrue(invites.first { it.roomName == "동아리" }.hasBlockedMember)
        assertFalse(invites.first { it.roomName == "주말 카레팟" }.hasBlockedMember)
    }

    @Test
    fun 답한_초대를_다시_수락하면_막힌다() = runBlocking {
        val r = rooms()
        val (first, second) = r.myInvites()
        r.acceptInvite(first.id)
        assertEquals(RoomErrorCodes.ROOM_INVITE_NOT_FOUND, errorCode { r.acceptInvite(first.id) })
        r.declineInvite(second.id)
        assertEquals(RoomErrorCodes.ROOM_INVITE_NOT_FOUND, errorCode { r.acceptInvite(second.id) })
        assertEquals(RoomErrorCodes.ROOM_INVITE_NOT_FOUND, errorCode { r.declineInvite(second.id) })
    }

    @Test
    fun 초대는_기다리는_초대까지_합쳐_6명까지() = runBlocking {
        val r = rooms()
        // 과팀플 4인방: 멤버 4명 + 기다리는 초대 1명 → 1명만 더
        val room = r.room(team)
        assertEquals(5, room.headcount)
        assertEquals(RoomErrorCodes.ROOM_MEMBER_LIMIT, errorCode { r.invite(team, listOf(11L, 13L)) })
        r.invite(team, listOf(11L))
        assertEquals(6, r.room(team).headcount)
        assertEquals(RoomErrorCodes.ROOM_MEMBER_LIMIT, errorCode { r.invite(team, listOf(13L)) })
        // 기다리는 초대를 취소하면 자리가 생겨요
        val pending = r.room(team).invites.first()
        r.cancelInvite(team, pending.id)
        r.invite(team, listOf(13L))
        assertEquals(RoomErrorCodes.ROOM_INVITE_NOT_FOUND, errorCode { r.cancelInvite(team, pending.id) })
    }

    @Test
    fun 방장이_아니면_초대와_취소를_못_한다() = runBlocking {
        val r = rooms()
        val others = r.rooms().first { !it.isMineOwner }
        assertTrue(others.invites.isEmpty()) // 기다리는 초대는 방장에게만
        assertEquals(RoomErrorCodes.ROOM_OWNER_ONLY, errorCode { r.invite(others.id, listOf(11L)) })
        assertEquals(RoomErrorCodes.ROOM_OWNER_ONLY, errorCode { r.cancelInvite(others.id, 1L) })
    }

    @Test
    fun 방이_20개면_만들기와_수락이_막힌다() = runBlocking {
        val r = rooms()
        while (r.rooms().size < RoomRules.ROOM_MAX) r.createRoom("방", listOf(11L))
        assertEquals(RoomErrorCodes.ROOM_LIMIT, errorCode { r.createRoom("하나 더", listOf(11L)) })
        val invite = r.myInvites().first()
        assertEquals(RoomErrorCodes.ROOM_LIMIT, errorCode { r.acceptInvite(invite.id) })
        assertTrue(r.myInvites().any { it.id == invite.id }) // 막히면 초대는 그대로
    }

    @Test
    fun 대화를_불러오면_받지_않은_글이_0이_된다() = runBlocking {
        val r = rooms()
        assertEquals(2, r.room(team).unread)
        r.messages(team)
        assertEquals(0, r.room(team).unread)
    }

    // ── 방 관리·나가기 ──

    /** 고등학교 동창(목업 방 2번). 방장은 하은님 */
    private val school = 2L

    @Test
    fun 방장이_아니면_이름_바꾸기_해산_내보내기가_ROOM_OWNER_ONLY() {
        val r = rooms()
        assertEquals(RoomErrorCodes.ROOM_OWNER_ONLY, errorCode { r.rename(school, "새 이름") })
        assertEquals(RoomErrorCodes.ROOM_OWNER_ONLY, errorCode { r.disband(school) })
        assertEquals(RoomErrorCodes.ROOM_OWNER_ONLY, errorCode { r.kick(school, 14) })
    }

    @Test
    fun 방장은_이름을_앞뒤_공백_빼고_바꾼다() = runBlocking {
        val r = rooms()
        assertEquals("새 이름", r.rename(team, "  새 이름 ").name)
        assertEquals("새 이름", r.room(team).name)
        assertEquals(ErrorCodes.INVALID_REQUEST, errorCode { r.rename(team, "   ") })
    }

    @Test
    fun 방장이_새_방장_없이_나가면_NEW_OWNER_REQUIRED() {
        val r = rooms()
        assertEquals(RoomErrorCodes.NEW_OWNER_REQUIRED, errorCode { r.leave(team, null) })
    }

    @Test
    fun 넘기고_나가면_새_사람이_방장이고_내_글은_나간_멤버() = runBlocking {
        val r = rooms()
        r.leave(team, 51)
        assertFalse(r.rooms().any { it.id == team })
        val after = r.peek(team)!!
        assertEquals(51L, after.ownerId)
        assertTrue(after.members.first { it.userId == 51L }.isOwner)
        assertFalse(after.members.any { it.isMe })
    }

    @Test
    fun 혼자_남은_방장이_나가면_방이_지워진다() = runBlocking {
        val r = rooms()
        val room = r.createRoom("혼자 방", listOf(11))
        r.leave(room.id, null)
        assertFalse(r.rooms().any { it.id == room.id })
        assertNull(r.peek(room.id))
        assertEquals(RoomErrorCodes.ROOM_NOT_FOUND, errorCode { r.room(room.id) })
    }

    @Test
    fun 내보낸_사람의_말풍선은_나간_멤버가_된다() = runBlocking {
        val r = rooms()
        r.kick(team, 52) // 민지
        assertFalse(r.room(team).members.any { it.userId == 52L })
        val bubble = r.messages(team).filterIsInstance<RoomMessage.Comment>().first { it.id == 202L }
        assertTrue(bubble.author.left)
        assertEquals("나간 멤버", bubble.author.displayName)
        // 올린 만화도 나간 멤버로 남아요
        assertTrue(r.posts(team).first { it.id == 102L }.author.left)
    }

    @Test
    fun 해산하면_목록에서_빠진다() = runBlocking {
        val r = rooms()
        r.disband(team)
        assertFalse(r.rooms().any { it.id == team })
        assertEquals(RoomErrorCodes.ROOM_NOT_FOUND, errorCode { r.messages(team) })
    }

    @Test
    fun 목업_방에는_나간_멤버_숨긴_글_정지된_계정이_있다() = runBlocking {
        val r = rooms()
        val chat = r.messages(school)
        assertTrue(chat.any { it is RoomMessage.Comment && it.author.left })
        val hidden = chat.filterIsInstance<RoomMessage.Comment>().single { it.hidden }
        assertEquals("", hidden.text)
        assertNull(hidden.post)
        assertTrue(r.room(school).members.first { it.userId == 14L }.suspended)
        // 숨긴 사람이 올린 만화는 제목·그림 없이
        val hiddenPost = r.posts(school).single { it.hidden }
        assertEquals("", hiddenPost.title)
        assertNull(hiddenPost.imageUrl)
    }

    @Test
    fun 숨기기를_끄면_그_사람_글이_다시_보인다() = runBlocking {
        val hidden = mutableSetOf(MockRooms.TAEO_ID)
        val r = MockRooms(netMs = 0, hiddenAuthors = { hidden.toSet() })
        assertTrue(r.messages(school).any { it.hidden })
        hidden.clear()
        assertFalse(r.messages(school).any { it.hidden })
    }

    @Test
    fun 멤버_friendState는_친구_관계를_따른다() = runBlocking {
        val r = MockRooms(netMs = 0, friendState = { id -> if (id == 51L) "FRIEND" else null })
        val members = r.room(team).members
        assertEquals("FRIEND", members.first { it.userId == 51L }.friendState)
        assertEquals("RECEIVED", members.first { it.userId == 52L }.friendState) // 목업 값 그대로
        assertEquals("ME", members.first { it.isMe }.friendState)
    }
}

