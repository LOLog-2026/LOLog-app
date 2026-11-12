package com.lolog.app.data

import com.lolog.app.data.mock.MockInbox
import com.lolog.app.data.model.ErrorCodes
import com.lolog.app.data.model.InboxItem
import com.lolog.app.data.model.InboxKind
import com.lolog.app.data.model.InboxTab
import com.lolog.app.data.remote.ApiMapper.toModel
import com.lolog.app.data.remote.InboxPageDto
import com.lolog.app.data.remote.LologJson
import com.lolog.app.data.repo.LologException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 알림함 응답(lolog-api #62 InboxDtos) → 앱 모델, 그리고 목업 읽음 규칙 */
class InboxTest {

    private fun page(json: String) = LologJson.decodeFromString(InboxPageDto.serializer(), json).toModel()

    private fun errorCode(block: () -> Unit): String? = try {
        block(); null
    } catch (e: LologException) {
        e.code
    }

    @Test
    fun `공지사항과 알림이 섞인 목록을 읽고 알림 data에 type을 다시 넣는다`() {
        val p = page("""
            {"items":[
              {"kind":"NOTICE","id":3,"createdAt":"2026-11-11T05:00:00Z","read":false,
               "notice":{"title":"점검","body":"첫 줄\n둘째 줄"},"notification":null},
              {"kind":"NOTIFICATION","id":9,"createdAt":"2026-11-11T04:00:00Z","read":true,"notice":null,
               "notification":{"type":"CARDS_CREATED","data":{"recordingId":"7","cardCount":"3"}}}
             ],"nextCursor":"1762837200000000.NOTIFICATION.9"}
        """.trimIndent())
        assertEquals(2, p.items.size)
        val n = p.items[0] as InboxItem.Notice
        assertEquals("첫 줄\n둘째 줄", n.body)
        assertFalse(n.read)
        val x = p.items[1] as InboxItem.Notification
        assertEquals("CARDS_CREATED", x.type)
        assertEquals(mapOf("recordingId" to "7", "cardCount" to "3", "type" to "CARDS_CREATED"), x.data)
        assertTrue(x.read)
        // cursor는 해석하지 않고 그대로 들고 있어요
        assertEquals("1762837200000000.NOTIFICATION.9", p.nextCursor)
    }

    @Test
    fun `모르는 알림 type은 버리지 않고 마지막 쪽이면 nextCursor가 null`() {
        val p = page("""
            {"items":[{"kind":"NOTIFICATION","id":1,"createdAt":"2026-11-11T04:00:00Z","read":false,
              "notification":{"type":"ROOM_INVITE","data":{"roomId":"5"}}}],"nextCursor":null}
        """.trimIndent())
        assertEquals("ROOM_INVITE", (p.items.single() as InboxItem.Notification).type)
        assertNull(p.nextCursor)
    }

    @Test
    fun `목업은 공지 2개 알림 7개이고 탭마다 걸러 준다`() {
        val m = MockInbox()
        assertEquals(9, m.page(InboxTab.ALL, null).items.size)
        assertEquals(2, m.page(InboxTab.NOTICE, null).items.size)
        val noti = m.page(InboxTab.NOTIFICATION, null).items.map { (it as InboxItem.Notification).type }
        assertEquals(
            listOf("ROOM_INVITE", "ROOM_COMMENT", "CARDS_CREATED", "COMIC_DONE", "ROOM_DISBANDED", "FRIEND_REQUEST", "PROFILE_CHARACTER_READY"),
            noti,
        )
        // 안 읽은 건 공지 하나와 방 초대, 새 카드 알림
        assertEquals(3, m.page(InboxTab.ALL, null).items.count { !it.read })
    }

    @Test
    fun `목업은 최근 것이 위이고 cursor로 다음 쪽을 준다`() {
        val m = MockInbox(pageSize = 4)
        val first = m.page(InboxTab.ALL, null)
        assertEquals(4, first.items.size)
        val times = first.items.map { java.time.OffsetDateTime.parse(it.createdAt) }
        assertEquals(times.sortedDescending(), times)
        val second = m.page(InboxTab.ALL, first.nextCursor)
        assertEquals(4, second.items.size)
        val third = m.page(InboxTab.ALL, second.nextCursor)
        assertEquals(1, third.items.size)
        assertNull(third.nextCursor)
    }

    @Test
    fun `목업 읽음이 남고 다 읽으면 hasUnread가 꺼진다`() {
        val m = MockInbox()
        assertTrue(m.hasUnread())
        m.read(InboxKind.NOTICE, 2)
        assertTrue(m.hasUnread())
        m.read(InboxKind.NOTIFICATION, 14)
        assertTrue(m.hasUnread())
        m.read(InboxKind.NOTIFICATION, 17)
        assertFalse(m.hasUnread())
        assertTrue(m.page(InboxTab.ALL, null).items.all { it.read })
    }

    @Test
    fun `목업 모두 읽음`() {
        val m = MockInbox()
        m.readAll()
        assertFalse(m.hasUnread())
    }

    @Test
    fun `목업에 없는 줄을 읽으면 종류에 맞는 404 코드`() {
        val m = MockInbox()
        assertEquals(ErrorCodes.NOTICE_NOT_FOUND, errorCode { m.read(InboxKind.NOTICE, 999) })
        assertEquals(ErrorCodes.NOTIFICATION_NOT_FOUND, errorCode { m.read(InboxKind.NOTIFICATION, 999) })
        // 알림 id를 공지 kind로 읽어도 없는 것
        assertEquals(ErrorCodes.NOTICE_NOT_FOUND, errorCode { m.read(InboxKind.NOTICE, 14) })
    }
}
