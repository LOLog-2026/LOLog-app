package com.lolog.app.data

import com.lolog.app.data.mock.MockRooms
import com.lolog.app.data.model.Friend
import com.lolog.app.data.model.RoomErrorCodes
import com.lolog.app.data.model.RoomMessage
import com.lolog.app.data.repo.LologException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        assertEquals(6, room.members.size)
        assertEquals(room.id, r.rooms().first().id)
    }

    @Test
    fun 대화를_불러오면_받지_않은_글이_0이_된다() = runBlocking {
        val r = rooms()
        assertEquals(2, r.room(team).unread)
        r.messages(team)
        assertEquals(0, r.room(team).unread)
    }
}
