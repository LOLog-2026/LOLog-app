package com.lolog.app.ui

import com.lolog.app.data.model.RoomMember
import com.lolog.app.data.model.RoomMessage
import com.lolog.app.data.model.RoomPost
import com.lolog.app.ui.screens.rooms.commentsFor
import com.lolog.app.ui.screens.rooms.showOlderComments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 방 만화 댓글 창이 받아 둔 방 대화에서 그 만화 댓글만 골라 오래된 순으로 보여 주는지 본다. */
class RoomCommentsTest {

    private val me = RoomMember(1, "민지", isMe = true)
    private val minsu = RoomMember(2, "민수")

    private fun post(id: Long) = RoomPost(id, roomId = 7, comicId = id * 10, title = "만화 $id", imageUrl = null, author = minsu, postedAt = "", likeCount = 0, likedByMe = false, commentCount = 0)

    private val mint = post(100)
    private val taxi = post(200)

    @Test
    fun 이_만화_댓글만_오래된_순으로_골라진다() {
        val messages = listOf(
            RoomMessage.Comment(15, "", me, "나도 먹어 보고 싶다", mint),
            RoomMessage.System(10, "", "민수님이 만화를 올렸어요", mint.id),
            RoomMessage.Like(11, "", me, mint.id),
            RoomMessage.Comment(12, "", minsu, "민트초코 카레 실화야?", mint),
            // 다른 만화 댓글·방 전체 댓글은 빠져요
            RoomMessage.Comment(13, "", minsu, "택시 얘기", taxi),
            RoomMessage.Comment(14, "", me, "주말에 볼래?", null),
        )
        val picked = commentsFor(messages, mint.id)
        assertEquals(listOf(12L, 15L), picked.map { it.id })
    }

    @Test
    fun 댓글이_없으면_비어_있다() {
        val messages = listOf(RoomMessage.Comment(1, "", me, "방 전체 글", null))
        assertTrue(commentsFor(messages, mint.id).isEmpty())
    }

    @Test
    fun 받은_수가_댓글_수보다_적고_앞_대화가_있으면_더_보기가_보인다() {
        assertTrue(showOlderComments(loaded = 1, commentCount = 3, hasOlder = true))
    }

    @Test
    fun 다_받았거나_앞_대화가_없으면_더_보기가_없다() {
        assertFalse(showOlderComments(loaded = 3, commentCount = 3, hasOlder = true))
        assertFalse(showOlderComments(loaded = 1, commentCount = 3, hasOlder = false))
    }
}
