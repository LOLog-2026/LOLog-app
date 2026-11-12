package com.lolog.app.data.mock

import com.lolog.app.data.model.*
import com.lolog.app.data.repo.LologException
import java.time.OffsetDateTime

/**
 * 알림함 목업(LOLog-docs PR #59 spec/inbox.md). 공지사항 2개(하나 안 읽음), 알림 4개(새 카드만 안 읽음).
 * 읽음·모두 읽음이 그대로 남고, hasUnread도 그에 맞게 바뀌어요. 안드로이드 없이 테스트하려고 따로 떼었어요.
 * cursor는 시작 위치(문자열)예요. 화면은 실서버처럼 뜻을 모르고 그대로 돌려보내요.
 * 관리자 공지 쓰기·고치기·지우기(admin.md 4장)도 여기 공지에 그대로 반영해요. 쓴 공지는 맨 위, 안 읽음이에요.
 * 확인 순서는 서버와 같이 관리자(403) → 요청 값(400) → 공지가 있는지(404)예요.
 */
internal class MockInbox(now: OffsetDateTime = OffsetDateTime.now(), private val pageSize: Int = PAGE_SIZE) {

    /** 최근 것이 위 */
    private val items: MutableList<InboxItem> = mutableListOf(
        InboxItem.Notice(
            2, "11월 12일 새벽 2~3시 서버 점검",
            "점검 중엔 카드 만들기가 잠깐 멈춰요. 녹음은 폰에 남아요.\n점검이 끝나면 자동으로 다시 만들어요.",
            now.minusMinutes(1).toString(), read = false,
        ),
        // 방 알림(rooms.md 3장 푸시). 초대 푸시에는 초대한 사람 닉네임이 명세에 없어 목업만 넣어 둬요
        InboxItem.Notification(
            17, "ROOM_INVITE",
            mapOf("type" to "ROOM_INVITE", "inviteId" to "501", "roomId" to "5", "roomName" to "주말 카레팟", "nickname" to "준"),
            now.minusMinutes(2).toString(), read = false,
        ),
        InboxItem.Notification(
            16, "ROOM_COMMENT",
            mapOf("type" to "ROOM_COMMENT", "roomId" to "1", "postId" to "103", "nickname" to "서연", "roomName" to "과팀플 4인방"),
            now.minusMinutes(5).toString(), read = true,
        ),
        // 카드 3장. 실서버 data처럼 값은 모두 문자열이고 type이 들어 있어요(앱이 다시 넣은 모양)
        InboxItem.Notification(
            14, "CARDS_CREATED", mapOf("type" to "CARDS_CREATED", "recordingId" to "7", "cardCount" to "3"),
            now.minusMinutes(10).toString(), read = false,
        ),
        InboxItem.Notification(
            13, "COMIC_DONE", mapOf("type" to "COMIC_DONE", "comicId" to "31", "cardId" to "12"),
            now.minusHours(1).toString(), read = true,
        ),
        InboxItem.Notification(
            15, "ROOM_DISBANDED", mapOf("type" to "ROOM_DISBANDED", "roomName" to "고3 동창"),
            now.minusHours(20).toString(), read = true,
        ),
        InboxItem.Notification(
            12, "FRIEND_REQUEST", mapOf("type" to "FRIEND_REQUEST", "requestId" to "202", "nickname" to "하은"),
            now.minusDays(1).toString(), read = true,
        ),
        InboxItem.Notice(
            1, "LOLog 시험판을 써 주셔서 고마워요",
            "불편한 점이나 바라는 점이 있으면 언제든 알려 주세요.\n더 재밌는 기록을 만들게요.",
            now.minusDays(2).toString(), read = true,
        ),
        InboxItem.Notification(
            11, "PROFILE_CHARACTER_READY", mapOf("type" to "PROFILE_CHARACTER_READY"),
            now.minusDays(3).toString(), read = true,
        ),
    )

    fun page(tab: InboxTab, cursor: String?): InboxPage {
        val list = when (tab) {
            InboxTab.ALL -> items
            InboxTab.NOTICE -> items.filterIsInstance<InboxItem.Notice>()
            InboxTab.NOTIFICATION -> items.filterIsInstance<InboxItem.Notification>()
        }
        val start = cursor?.toIntOrNull() ?: 0
        return InboxPage(list.drop(start).take(pageSize), (start + pageSize).takeIf { it < list.size }?.toString())
    }

    fun hasUnread() = items.any { !it.read }

    /** 없는 줄이면 서버처럼 404 */
    fun read(kind: InboxKind, id: Long) {
        val i = items.indexOfFirst { it.kind == kind && it.id == id }
        if (i < 0) {
            if (kind == InboxKind.NOTICE) throw LologException(ErrorCodes.NOTICE_NOT_FOUND, "공지사항을 찾을 수 없어요.")
            throw LologException(ErrorCodes.NOTIFICATION_NOT_FOUND, "알림을 찾을 수 없어요.")
        }
        items[i] = items[i].asRead()
    }

    fun readAll() = items.replaceAll { it.asRead() }

    // ── 관리자 공지 ──

    private var nextNoticeId = items.filterIsInstance<InboxItem.Notice>().maxOf { it.id } + 1
    /** 고친 때. 알림함 줄에는 updatedAt이 없어서 따로 들고 있어요 */
    private val noticeUpdatedAt = mutableMapOf<Long, String>()

    fun createNotice(isAdmin: Boolean, title: String, body: String, now: String = OffsetDateTime.now().toString()): AdminNotice {
        checkAdmin(isAdmin)
        checkTitle(title)
        checkBody(body)
        val n = InboxItem.Notice(nextNoticeId++, title, body, now, read = false)
        items.add(0, n)
        return n.toAdmin()
    }

    /** null인 필드는 그대로 둬요. 읽음과 목록 자리도 그대로예요 */
    fun updateNotice(isAdmin: Boolean, id: Long, title: String?, body: String?, now: String = OffsetDateTime.now().toString()): AdminNotice {
        checkAdmin(isAdmin)
        title?.let(::checkTitle)
        body?.let(::checkBody)
        val i = noticeIndex(id)
        val old = items[i] as InboxItem.Notice
        val n = old.copy(title = title ?: old.title, body = body ?: old.body)
        items[i] = n
        if (n != old) noticeUpdatedAt[id] = now
        return n.toAdmin()
    }

    fun deleteNotice(isAdmin: Boolean, id: Long) {
        checkAdmin(isAdmin)
        items.removeAt(noticeIndex(id))
        noticeUpdatedAt.remove(id)
    }

    private fun checkAdmin(isAdmin: Boolean) {
        if (!isAdmin) throw LologException(ErrorCodes.FORBIDDEN, "관리자만 할 수 있어요.")
    }

    private fun checkTitle(title: String) {
        val t = title.trim()
        if (t.isEmpty() || t.length > NOTICE_TITLE_MAX) {
            throw LologException(ErrorCodes.INVALID_REQUEST, "제목은 1~${NOTICE_TITLE_MAX}자예요.", "title")
        }
    }

    private fun checkBody(body: String) {
        if (body.isBlank()) throw LologException(ErrorCodes.INVALID_REQUEST, "본문을 적어 주세요.", "body")
    }

    private fun noticeIndex(id: Long): Int {
        val i = items.indexOfFirst { it.kind == InboxKind.NOTICE && it.id == id }
        if (i < 0) throw LologException(ErrorCodes.NOTICE_NOT_FOUND, "공지사항을 찾을 수 없어요.")
        return i
    }

    private fun InboxItem.Notice.toAdmin() = AdminNotice(id, title, body, createdAt, noticeUpdatedAt[id] ?: createdAt)

    companion object {
        const val PAGE_SIZE = 20
    }
}
