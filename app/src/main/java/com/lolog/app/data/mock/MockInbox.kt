package com.lolog.app.data.mock

import com.lolog.app.data.model.*
import com.lolog.app.data.repo.LologException
import java.time.OffsetDateTime

/**
 * 알림함 목업(LOLog-docs PR #59 spec/inbox.md). 공지사항 2개(하나 안 읽음), 알림 4개(새 카드만 안 읽음).
 * 읽음·모두 읽음이 그대로 남고, hasUnread도 그에 맞게 바뀌어요. 안드로이드 없이 테스트하려고 따로 떼었어요.
 * cursor는 시작 위치(문자열)예요. 화면은 실서버처럼 뜻을 모르고 그대로 돌려보내요.
 */
internal class MockInbox(now: OffsetDateTime = OffsetDateTime.now(), private val pageSize: Int = PAGE_SIZE) {

    /** 최근 것이 위 */
    private val items: MutableList<InboxItem> = mutableListOf(
        InboxItem.Notice(
            2, "11월 12일 새벽 2~3시 서버 점검",
            "점검 중엔 카드 만들기가 잠깐 멈춰요. 녹음은 폰에 남아요.\n점검이 끝나면 자동으로 다시 만들어요.",
            now.minusMinutes(1).toString(), read = false,
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

    companion object {
        const val PAGE_SIZE = 20
    }
}
