package com.lolog.app.data.model

/*
 * 관리자 계정 관리·의견(LOLog-docs spec/admin.md 2·3·5·6·7장).
 * 서버: 계정 목록·정지·탈퇴시키기는 LOLog-auth(/auth/admin 아래), 닉네임·친구 코드와 의견은 lolog-api(/api/admin 아래, /api/feedback).
 */

/** GET /auth/admin/users 한 줄. 정지 세 칸은 지금 정지 중일 때만 차 있어요(기한이 지났으면 모두 null) */
data class AdminUser(
    val userId: Long,
    val email: String,
    val createdAt: String,
    val role: String,
    val suspendedAt: String? = null,
    /** 영구 정지면 null(정지 중인지는 [suspended]로 봐요) */
    val suspendedUntil: String? = null,
    val suspendReason: String? = null,
) {
    val isAdmin get() = role == "ADMIN"
    val suspended get() = suspendedAt != null
    val permanent get() = suspended && suspendedUntil == null
}

data class AdminUserPage(val users: List<AdminUser>, val nextCursor: String?)

/** 계정 목록 정렬(admin.md 6장, 기본은 최신순) */
enum class AdminUserSort(val label: String) { NEWEST("가입 최신순"), OLDEST("가입 오래된순"), EMAIL("이메일순") }

/** 정지 기한(admin.md 2장). 정지한 때부터 달력으로 세요 */
enum class SuspendDuration(val label: String, val months: Long?) {
    MONTH_1("1개월", 1), MONTH_3("3개월", 3), MONTH_6("6개월", 6), PERMANENT("영구", null)
}

/** GET /api/admin/users/{id}. 닉네임을 정하기 전이면 둘 다 null */
data class AdminUserProfile(val nickname: String?, val friendCode: String?)

enum class FeedbackCategory(val label: String) { BUG("오류 신고"), OPINION("의견") }

enum class FeedbackStatus(val label: String) { OPEN("처리 전"), DONE("처리함") }

/** FeedbackItem. userId가 null이면 보낸 사람이 탈퇴했어요("탈퇴한 사용자") */
data class Feedback(
    val id: Long,
    val userId: Long?,
    val category: FeedbackCategory,
    val text: String,
    val appVersion: String,
    val deviceModel: String,
    val status: FeedbackStatus,
    val createdAt: String,
)

data class FeedbackPage(val items: List<Feedback>, val nextCursor: String?)

/** 정지 이유 길이(admin.md 2장: 1~200자, 앞뒤 공백을 뺀 길이) */
const val SUSPEND_REASON_MAX = 200

/** 의견 글 길이(admin.md 7장: 1~1000자, 앞뒤 공백을 뺀 길이) */
const val FEEDBACK_TEXT_MAX = 1000

/** 의견을 지난 24시간에 보낼 수 있는 수(admin.md 5·9장) */
const val FEEDBACK_PER_DAY = 10

/** 관리자 화면에서만 쓰는 오류 코드(admin.md 7장). 나머지(FORBIDDEN, USER_NOT_FOUND 등)는 ErrorCodes에 있어요 */
object AdminErrorCodes {
    const val FEEDBACK_NOT_FOUND = "FEEDBACK_NOT_FOUND" // 404 없는 의견
}
