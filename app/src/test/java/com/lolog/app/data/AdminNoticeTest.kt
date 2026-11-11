package com.lolog.app.data

import com.lolog.app.data.mock.MockInbox
import com.lolog.app.data.model.ErrorCodes
import com.lolog.app.data.model.InboxItem
import com.lolog.app.data.model.InboxTab
import com.lolog.app.data.remote.ApiMapper.toModel
import com.lolog.app.data.remote.LologJson
import com.lolog.app.data.remote.NoticeDto
import com.lolog.app.data.remote.NoticeUpdateDto
import com.lolog.app.data.repo.LologException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 관리자 공지 쓰기·고치기·지우기(LOLog-docs spec/admin.md 4·7장). 목업은 알림함 공지에 그대로 반영돼요 */
class AdminNoticeTest {

    private fun notices(m: MockInbox) = m.page(InboxTab.NOTICE, null).items.map { it as InboxItem.Notice }

    private fun error(block: () -> Unit): LologException? = try {
        block(); null
    } catch (e: LologException) {
        e
    }

    @Test
    fun `고치기 요청은 바꾼 필드만 보낸다`() {
        assertEquals("""{"title":"새 제목"}""", LologJson.encodeToString(NoticeUpdateDto.serializer(), NoticeUpdateDto(title = "새 제목")))
        assertEquals("""{"body":"새 본문"}""", LologJson.encodeToString(NoticeUpdateDto.serializer(), NoticeUpdateDto(body = "새 본문")))
    }

    @Test
    fun `Notice 응답을 모델로 바꾼다`() {
        val n = LologJson.decodeFromString(
            NoticeDto.serializer(),
            """{"id":5,"title":"점검","body":"첫 줄\n둘째 줄","createdAt":"2026-11-12T01:00:00Z","updatedAt":"2026-11-12T02:00:00Z"}""",
        ).toModel()
        assertEquals(5L, n.id)
        assertEquals("첫 줄\n둘째 줄", n.body)
        assertEquals("2026-11-12T02:00:00Z", n.updatedAt)
    }

    @Test
    fun `목업 쓰기는 알림함 공지 탭 맨 위에 안 읽음으로 올라간다`() {
        val m = MockInbox()
        m.readAll()
        val made = m.createNotice(isAdmin = true, title = "새 공지", body = "본문")
        val top = notices(m).first()
        assertEquals(made.id, top.id)
        assertEquals("새 공지", top.title)
        assertFalse(top.read)
        // 전체 탭에서도 맨 위이고 종의 점이 켜져요
        assertEquals(made.id, m.page(InboxTab.ALL, null).items.first().id)
        assertTrue(m.hasUnread())
        // 지금 있는 공지와 id가 겹치지 않아요
        assertEquals(notices(m).size, notices(m).map { it.id }.toSet().size)
    }

    @Test
    fun `목업 고치기는 바꾼 것만 바꾸고 읽음과 자리는 그대로`() {
        val m = MockInbox()
        m.readAll()
        val before = notices(m).first { it.id == 1L }
        val t = m.updateNotice(isAdmin = true, id = 1, title = "고친 제목", body = null, now = "2026-11-12T03:00:00Z")
        assertEquals("고친 제목", t.title)
        assertEquals(before.body, t.body)
        assertEquals("2026-11-12T03:00:00Z", t.updatedAt)
        val b = m.updateNotice(isAdmin = true, id = 1, title = null, body = "고친 본문")
        assertEquals("고친 제목", b.title)
        assertEquals("고친 본문", b.body)
        val after = notices(m).first { it.id == 1L }
        assertEquals("고친 본문", after.body)
        assertTrue(after.read)
        assertEquals(1, notices(m).indexOfFirst { it.id == 1L })
    }

    @Test
    fun `목업 지우기는 알림함 목록에서 빠진다`() {
        val m = MockInbox()
        m.deleteNotice(isAdmin = true, id = 2)
        assertTrue(notices(m).none { it.id == 2L })
        assertTrue(m.page(InboxTab.ALL, null).items.none { it is InboxItem.Notice && it.id == 2L })
    }

    @Test
    fun `목업 관리자가 아니면 FORBIDDEN`() {
        val m = MockInbox()
        assertEquals(ErrorCodes.FORBIDDEN, error { m.createNotice(isAdmin = false, title = "제목", body = "본문") }?.code)
        assertEquals(ErrorCodes.FORBIDDEN, error { m.updateNotice(isAdmin = false, id = 1, title = "제목", body = null) }?.code)
        assertEquals(ErrorCodes.FORBIDDEN, error { m.deleteNotice(isAdmin = false, id = 1) }?.code)
        // 관리자 확인이 요청 값 검사보다 먼저예요
        assertEquals(ErrorCodes.FORBIDDEN, error { m.createNotice(isAdmin = false, title = "", body = "") }?.code)
        assertEquals(2, notices(m).size)
    }

    @Test
    fun `목업 제목 0자·101자와 빈 본문은 INVALID_REQUEST`() {
        val m = MockInbox()
        val empty = error { m.createNotice(isAdmin = true, title = "   ", body = "본문") }
        assertEquals(ErrorCodes.INVALID_REQUEST, empty?.code)
        assertEquals("title", empty?.field)
        val long = error { m.createNotice(isAdmin = true, title = "가".repeat(101), body = "본문") }
        assertEquals(ErrorCodes.INVALID_REQUEST, long?.code)
        assertEquals("title", long?.field)
        val noBody = error { m.createNotice(isAdmin = true, title = "제목", body = " \n ") }
        assertEquals(ErrorCodes.INVALID_REQUEST, noBody?.code)
        assertEquals("body", noBody?.field)
        assertEquals("title", error { m.updateNotice(isAdmin = true, id = 1, title = "", body = null) }?.field)
        // 100자는 돼요
        assertNull(error { m.createNotice(isAdmin = true, title = "가".repeat(100), body = "본문") })
    }

    @Test
    fun `목업 없는 id는 NOTICE_NOT_FOUND`() {
        val m = MockInbox()
        assertEquals(ErrorCodes.NOTICE_NOT_FOUND, error { m.updateNotice(isAdmin = true, id = 999, title = "제목", body = null) }?.code)
        assertEquals(ErrorCodes.NOTICE_NOT_FOUND, error { m.deleteNotice(isAdmin = true, id = 999) }?.code)
        // 알림 id(14)는 공지가 아니에요
        assertEquals(ErrorCodes.NOTICE_NOT_FOUND, error { m.deleteNotice(isAdmin = true, id = 14) }?.code)
        m.deleteNotice(isAdmin = true, id = 2)
        assertEquals(ErrorCodes.NOTICE_NOT_FOUND, error { m.deleteNotice(isAdmin = true, id = 2) }?.code)
    }
}
