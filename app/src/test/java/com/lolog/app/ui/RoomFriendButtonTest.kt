package com.lolog.app.ui

import com.lolog.app.ui.screens.rooms.friendButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 방 멤버 시트 친구 버튼: friendState마다 글자와 켜짐(rooms.md "방에서 친구 요청") */
class RoomFriendButtonTest {

    @Test
    fun friendState마다_버튼_글자가_다르다() {
        assertEquals("친구", friendButton("FRIEND")?.label)
        assertEquals("요청 보냄", friendButton("REQUESTED")?.label)
        assertEquals("수락", friendButton("RECEIVED")?.label)
        assertEquals("친구 추가", friendButton("NONE")?.label)
    }

    @Test
    fun 친구와_요청_보냄은_꺼지고_수락은_코랄() {
        assertFalse(friendButton("FRIEND")!!.enabled)
        assertFalse(friendButton("REQUESTED")!!.enabled)
        assertTrue(friendButton("RECEIVED")!!.accent)
        assertTrue(friendButton("NONE")!!.enabled)
    }

    @Test
    fun 차단한_사람과_나는_버튼이_없다() {
        assertNull(friendButton("BLOCKED"))
        assertNull(friendButton("ME"))
        assertNull(friendButton(null))
    }
}
