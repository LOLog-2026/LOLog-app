package com.lolog.app.data.model

/*
 * 알림함(공지사항·알림). 명세는 LOLog-docs PR #59 spec/inbox.md 1~3장·5장, 서버는 lolog-api #62.
 * 실서버는 RemoteLologRepository, 목업은 MockInbox예요. 문구는 서버에 없어서 화면(ui/screens/inbox)이 만들어요.
 */

/** 알림함 탭. query는 GET /api/inbox?tab= 값 */
enum class InboxTab(val query: String) {
    ALL("ALL"),
    NOTICE("NOTICE"),
    NOTIFICATION("NOTIFICATION"),
}

/** 한 줄 읽음 주소의 kind. POST /api/inbox/{kind}/{id}/read */
enum class InboxKind(val path: String) {
    NOTICE("notice"),
    NOTIFICATION("notification"),
}

/** 알림함 한 줄. 공지사항 아니면 알림이에요 */
sealed class InboxItem {
    abstract val id: Long
    abstract val createdAt: String
    abstract val read: Boolean
    abstract val kind: InboxKind

    /** 모두에게 같은 글. body는 줄바꿈 그대로 */
    data class Notice(
        override val id: Long,
        val title: String,
        val body: String,
        override val createdAt: String,
        override val read: Boolean,
    ) : InboxItem() {
        override val kind get() = InboxKind.NOTICE
    }

    /**
     * 받은 푸시 한 줄. type은 서버 값 그대로(모르는 값도 버리지 않아요).
     * data는 푸시에 넣은 값이에요. 서버는 type을 빼고 보내서 앱이 "type"을 다시 넣어 둬요(Notifier.payloadOf에 그대로 넘기려고)
     */
    data class Notification(
        override val id: Long,
        val type: String,
        val data: Map<String, String>,
        override val createdAt: String,
        override val read: Boolean,
    ) : InboxItem() {
        override val kind get() = InboxKind.NOTIFICATION
    }

    fun asRead(): InboxItem = when (this) {
        is Notice -> copy(read = true)
        is Notification -> copy(read = true)
    }
}

/** 한 번에 20개, 최근 것이 위. nextCursor는 서버가 준 문자열 그대로 다시 보내요(뜻을 해석하지 않아요). 마지막 쪽이면 null */
data class InboxPage(val items: List<InboxItem>, val nextCursor: String?)
