package com.lolog.app.ui

import com.lolog.app.data.model.RoomMember
import com.lolog.app.data.model.RoomMessage
import com.lolog.app.ui.screens.rooms.CommentSide
import com.lolog.app.ui.screens.rooms.commentSide
import com.lolog.app.ui.screens.rooms.continuesFrom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 4컷 댓글 창에서 내 댓글만 오른쪽에 놓고, 같은 사람이 1분 안에 이어 쓴 댓글은 사진·이름을 생략하는지 본다. */
class CommentSideTest {

    private val me = RoomMember(1, "민지", isMe = true)
    private val minsu = RoomMember(2, "민수")
    private val leftMember = RoomMember(3, "지호", left = true)
    private val suspended = RoomMember(4, "하늘", suspended = true)

    private fun comment(id: Long, author: RoomMember, at: String = "2026-11-13T10:00:00+09:00", hidden: Boolean = false) =
        RoomMessage.Comment(id, at, author, "댓글 $id", post = null, hidden = hidden)

    @Test
    fun 내_댓글은_오른쪽() {
        assertEquals(CommentSide.RIGHT, commentSide(comment(1, me)))
    }

    @Test
    fun 남의_댓글은_왼쪽() {
        assertEquals(CommentSide.LEFT, commentSide(comment(2, minsu)))
    }

    @Test
    fun 나간_멤버는_왼쪽() {
        assertEquals(CommentSide.LEFT, commentSide(comment(3, leftMember)))
    }

    @Test
    fun 정지된_계정은_왼쪽() {
        assertEquals(CommentSide.LEFT, commentSide(comment(4, suspended)))
    }

    @Test
    fun 숨긴_글은_왼쪽() {
        assertEquals(CommentSide.LEFT, commentSide(comment(5, minsu, hidden = true)))
    }

    @Test
    fun 같은_사람이_1분_안에_이어_쓰면_이어_쓴_댓글() {
        val first = comment(1, minsu, at = "2026-11-13T10:00:00+09:00")
        assertTrue(continuesFrom(first, comment(2, minsu, at = "2026-11-13T10:00:59+09:00")))
        assertTrue(continuesFrom(first, comment(2, minsu, at = "2026-11-13T10:01:00+09:00")))
    }

    @Test
    fun 첫_댓글_다른_사람_1분_넘김_숨긴_글_시간_모름은_따로() {
        val first = comment(1, minsu, at = "2026-11-13T10:00:00+09:00")
        assertFalse(continuesFrom(null, first))
        assertFalse(continuesFrom(first, comment(2, me, at = "2026-11-13T10:00:10+09:00")))
        assertFalse(continuesFrom(first, comment(2, minsu, at = "2026-11-13T10:01:01+09:00")))
        assertFalse(continuesFrom(first, comment(2, minsu, at = "2026-11-13T10:00:10+09:00", hidden = true)))
        assertFalse(continuesFrom(first, comment(2, minsu, at = "")))
    }
}
