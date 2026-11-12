package com.lolog.app.ui

import com.lolog.app.data.model.InboxItem
import com.lolog.app.data.model.PushType
import com.lolog.app.notification.Notifier
import com.lolog.app.ui.screens.inbox.InboxBadge
import com.lolog.app.ui.screens.inbox.InboxLine
import com.lolog.app.ui.screens.inbox.badge
import com.lolog.app.ui.screens.inbox.inboxTime
import com.lolog.app.ui.screens.inbox.line
import com.lolog.app.ui.screens.inbox.RoomTarget
import com.lolog.app.ui.screens.inbox.roomTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** 알림함 한 줄 문구·표시·시간(문구는 앱이 정함, 명세에 없음) */
class InboxTextsTest {

    private fun noti(type: String, vararg data: Pair<String, String>) =
        InboxItem.Notification(1, type, mapOf("type" to type) + data, "2026-11-11T05:00:00Z", read = false)

    @Test
    fun `알림 종류마다 제목과 작은 글`() {
        assertEquals(InboxLine("이야기 카드 3장이 생겼어요", "녹음에서 재밌는 순간을 찾았어요"), noti("CARDS_CREATED", "cardCount" to "3").line())
        assertEquals(InboxLine("4컷만화가 다 그려졌어요", "눌러서 볼 수 있어요"), noti("COMIC_DONE").line())
        assertEquals(InboxLine("4컷만화를 그리지 못했어요", "다시 그려 볼 수 있어요"), noti("COMIC_FAILED").line())
        assertEquals(InboxLine("내 캐릭터 그림이 생겼어요", "친구들이 화자 그림으로 쓸 수 있어요"), noti("PROFILE_CHARACTER_READY").line())
        assertEquals(InboxLine("내 캐릭터를 만들지 못했어요", "사진을 다시 골라 주세요"), noti("PROFILE_CHARACTER_FAILED").line())
        assertEquals(InboxLine("하은님이 친구 요청을 보냈어요", "받은 요청에서 수락할 수 있어요"), noti("FRIEND_REQUEST", "nickname" to "하은").line())
    }

    @Test
    fun `모르는 종류는 새 소식이 있어요와 빈 글, 표시는 알림이고 이동할 곳이 없다`() {
        val future = noti("FUTURE_TYPE", "roomId" to "5")
        assertEquals(InboxLine("새 소식이 있어요", ""), future.line())
        assertEquals(InboxBadge.OTHER, future.badge())
        assertNull(Notifier.payloadOf(future.data))
        assertNull(future.roomTarget())
    }

    @Test
    fun `방 알림은 방 꼬리표와 문구, 갈 곳`() {
        val invite = noti("ROOM_INVITE", "inviteId" to "1", "roomId" to "5", "roomName" to "주말 카레팟", "nickname" to "준")
        assertEquals(InboxLine("준님이 \"주말 카레팟\" 방에 초대했어요", "눌러서 수락하거나 거절해요"), invite.line())
        assertEquals(InboxBadge.ROOM, invite.badge())
        assertEquals(RoomTarget.List, invite.roomTarget())
        // 명세의 초대 푸시에는 닉네임이 없어요
        assertEquals("\"주말 카레팟\" 방에 초대받았어요", noti("ROOM_INVITE", "roomName" to "주말 카레팟").line().title)

        val comment = noti("ROOM_COMMENT", "roomId" to "1", "postId" to "3", "nickname" to "서연", "roomName" to "과팀플")
        assertEquals(InboxLine("서연님이 내 만화에 댓글을 달았어요", "과팀플"), comment.line())
        assertEquals(RoomTarget.Chat(1), comment.roomTarget())

        val gone = noti("ROOM_DISBANDED", "roomName" to "고3 동창")
        assertEquals(InboxLine("\"고3 동창\" 방이 해산됐어요", "방의 만화와 대화가 지워졌어요"), gone.line())
        assertEquals(RoomTarget.List, gone.roomTarget())
    }

    @Test
    fun `아는 종류는 data로 푸시와 같은 PushPayload가 된다`() {
        val p = Notifier.payloadOf(noti("COMIC_DONE", "comicId" to "31", "cardId" to "12").data)!!
        assertEquals(PushType.COMIC_DONE, p.type)
        assertEquals(31L, p.comicId)
    }

    @Test
    fun `공지는 제목 그대로와 본문 첫 줄`() {
        val n = InboxItem.Notice(1, "서버 점검", "\n  점검 중엔 멈춰요.  \n둘째 줄", "2026-11-11T05:00:00Z", read = true)
        assertEquals(InboxLine("서버 점검", "점검 중엔 멈춰요."), n.line())
        assertEquals(InboxBadge.NOTICE, n.badge())
    }

    @Test
    fun `표시 종류`() {
        assertEquals(InboxBadge.CARDS, noti("CARDS_CREATED").badge())
        assertEquals(InboxBadge.COMIC, noti("COMIC_FAILED").badge())
        assertEquals(InboxBadge.CHARACTER, noti("PROFILE_CHARACTER_FAILED").badge())
        assertEquals(InboxBadge.FRIEND, noti("FRIEND_REQUEST").badge())
    }

    @Test
    fun `시간은 방금 n분 전 n시간 전 어제 월일`() {
        val now = ZonedDateTime.of(2026, 11, 12, 10, 0, 0, 0, ZoneId.of("Asia/Seoul"))
        assertEquals("방금", inboxTime("2026-11-12T09:59:30+09:00", now))
        assertEquals("10분 전", inboxTime("2026-11-12T09:50:00+09:00", now))
        // 서버는 UTC로 줘요
        assertEquals("3시간 전", inboxTime("2026-11-11T22:00:00Z", now))
        assertEquals("어제", inboxTime("2026-11-11T08:00:00+09:00", now))
        assertEquals("11/10", inboxTime("2026-11-10T23:00:00+09:00", now))
        assertEquals("", inboxTime("이상한 값", now))
    }
}
