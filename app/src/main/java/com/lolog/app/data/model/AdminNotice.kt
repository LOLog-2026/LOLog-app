package com.lolog.app.data.model

/*
 * 관리자 공지 쓰기·고치기·지우기. 명세는 LOLog-docs spec/admin.md 4장·7장, 서버는 LOLog-api #63.
 * 공지 목록은 따로 주소가 없어서 알림함(inbox, InboxTab.NOTICE)을 같이 써요.
 */

/** POST·PATCH /api/admin/notices의 응답(Notice) */
data class AdminNotice(
    val id: Long,
    val title: String,
    val body: String,
    val createdAt: String,
    val updatedAt: String,
)

/** 공지 제목 길이(admin.md 4장: 1~100자). 본문 길이 제한은 명세에 없어요 */
const val NOTICE_TITLE_MAX = 100
