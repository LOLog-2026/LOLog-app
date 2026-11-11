package com.lolog.app.data

import com.lolog.app.data.mock.MockFriends
import com.lolog.app.data.model.ErrorCodes
import com.lolog.app.data.model.FriendRequestDirection
import com.lolog.app.data.model.FriendRequestStatus
import com.lolog.app.data.repo.LologException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 친구 목업 규칙(MockLologRepository 위 주석)이 화면 확인용으로 그대로 도는지 본다. */
class MockFriendsTest {

    private fun errorCode(block: () -> Unit): String? = try {
        block(); null
    } catch (e: LologException) {
        e.code
    }

    private fun assertError(code: String, block: () -> Unit) = assertEquals(code, errorCode(block))

    /** 닉네임을 정한 상태 */
    private fun ready(clock: () -> Long = System::currentTimeMillis) = MockFriends(clock).apply { updateNickname("  지민  ") }

    @Test
    fun 처음에는_닉네임과_코드가_없고_정하면_코드가_생긴다() {
        val f = MockFriends()
        assertNull(f.profile().nickname)
        assertNull(f.profile().friendCode)
        val p = f.updateNickname("  지민  ")
        assertEquals("지민", p.nickname)
        assertEquals(MockFriends.MY_CODE, p.friendCode)
    }

    @Test
    fun 준호_코드는_바로_친구가_되고_목록_맨_위에_온다() {
        val f = ready()
        val r = f.send("j8zn4hqw") // 소문자도 대문자로 본다
        assertEquals(FriendRequestStatus.ACCEPTED, r.status)
        assertEquals("준호", r.user.nickname)
        assertEquals("준호", f.friends().first().nickname)
    }

    @Test
    fun 내_코드는_CANNOT_FRIEND_SELF() {
        assertError(ErrorCodes.CANNOT_FRIEND_SELF) { ready().send(MockFriends.MY_CODE) }
    }

    @Test
    fun 없는_코드는_FRIEND_CODE_NOT_FOUND_N으로_시작하면_새_요청() {
        val f = ready()
        assertError(ErrorCodes.FRIEND_CODE_NOT_FOUND) { f.send("XXXX2222") }
        val r = f.send("N3KD7WQA")
        assertEquals(FriendRequestStatus.PENDING, r.status)
        assertEquals("N3KD7WQA", f.requests(FriendRequestDirection.SENT).first().user.friendCode)
        // 같은 사람에게 또 보내면 이미 보냄
        assertError(ErrorCodes.FRIEND_REQUEST_EXISTS) { f.send("N3KD7WQA") }
    }

    @Test
    fun 친구_코드는_ALREADY_FRIENDS_차단한_코드는_USER_BLOCKED() {
        val f = ready()
        assertError(ErrorCodes.ALREADY_FRIENDS) { f.send("K2PH7RTA") }
        assertError(ErrorCodes.USER_BLOCKED) { f.send(MockFriends.BLOCKED_CODE) }
    }

    @Test
    fun 일분_안_열한번째는_TOO_MANY_REQUESTS이고_일분_뒤에는_다시_된다() {
        var now = 1_000_000L
        val f = ready { now }
        repeat(10) { assertError(ErrorCodes.FRIEND_CODE_NOT_FOUND) { f.send("XXXX2222") } }
        assertError(ErrorCodes.TOO_MANY_REQUESTS) { f.send("XXXX2222") }
        now += 60_000
        assertError(ErrorCodes.FRIEND_CODE_NOT_FOUND) { f.send("XXXX2222") }
    }

    @Test
    fun 여덟자가_아니면_INVALID_REQUEST이고_횟수에_안_센다() {
        val f = ready { 0L }
        repeat(20) { assertError(ErrorCodes.INVALID_REQUEST) { f.send("ABC") } }
        assertError(ErrorCodes.FRIEND_CODE_NOT_FOUND) { f.send("XXXX2222") }
    }

    @Test
    fun 닉네임_없이_보내기나_수락은_PROFILE_REQUIRED() {
        val f = MockFriends()
        assertError(ErrorCodes.PROFILE_REQUIRED) { f.send("J8ZN4HQW") }
        val id = f.requests(FriendRequestDirection.RECEIVED).first().id
        assertError(ErrorCodes.PROFILE_REQUIRED) { f.accept(id) }
    }

    @Test
    fun 닉네임이_공백뿐이거나_10자를_넘으면_INVALID_REQUEST() {
        assertError(ErrorCodes.INVALID_REQUEST) { MockFriends().updateNickname("   ") }
        assertError(ErrorCodes.INVALID_REQUEST) { MockFriends().updateNickname("가나다라마바사아자차카") }
    }

    @Test
    fun 수락하면_요청에서_빠지고_친구_맨_위로() {
        val f = ready()
        val req = f.requests(FriendRequestDirection.RECEIVED).first { it.user.nickname == "하은" }
        f.accept(req.id)
        assertFalse(f.requests(FriendRequestDirection.RECEIVED).any { it.id == req.id })
        assertEquals("Z9CX4WVB", f.friends().first().friendCode)
    }

    @Test
    fun 차단하면_친구와_요청에서_빠지고_차단_목록_맨_위로() {
        val f = ready()
        val friend = f.friends().first { it.friendCode == "K2PH7RTA" }
        f.block(friend.userId)
        assertFalse(f.friends().any { it.userId == friend.userId })
        assertEquals("K2PH7RTA", f.blocks().first().friendCode)

        val req = f.requests(FriendRequestDirection.SENT).first()
        f.block(req.user.userId)
        assertTrue(f.requests(FriendRequestDirection.SENT).none { it.user.userId == req.user.userId })
        assertEquals(req.user.friendCode, f.blocks().first().friendCode)

        // 풀어도 친구로 돌아가지 않는다
        f.unblock(friend.userId)
        assertFalse(f.blocks().any { it.userId == friend.userId })
        assertFalse(f.friends().any { it.userId == friend.userId })
    }

    @Test
    fun 친구도_요청도_아닌_사람_차단은_USER_NOT_FOUND() {
        assertError(ErrorCodes.USER_NOT_FOUND) { ready().block(9999) }
    }
}
