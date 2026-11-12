package com.lolog.app.data.repo

import com.lolog.app.data.model.AdminUserPage
import com.lolog.app.data.model.AdminUserProfile
import com.lolog.app.data.model.AdminUserSort
import com.lolog.app.data.model.Feedback
import com.lolog.app.data.model.FeedbackCategory
import com.lolog.app.data.model.FeedbackPage
import com.lolog.app.data.model.FeedbackStatus
import com.lolog.app.data.model.SuspendDuration

/**
 * 관리자 계정 관리·의견(LOLog-docs spec/admin.md). 공지사항은 [LologRepository]에 있어요.
 * 관리자 주소는 관리자가 아니면 403 FORBIDDEN이에요. 실패하면 LologException을 던져요.
 */
interface AdminRepository {
    /**
     * 계정 목록(GET /auth/admin/users). [q]는 이메일 일부(대소문자 무시), 탈퇴한 계정은 없어요.
     * 다음 쪽도 같은 sort·q로 불러요. cursor는 받은 nextCursor 그대로
     */
    suspend fun users(q: String? = null, sort: AdminUserSort = AdminUserSort.NEWEST, cursor: String? = null): AdminUserPage

    /** 닉네임과 친구 코드(GET /api/admin/users/{id}) */
    suspend fun userProfile(userId: Long): AdminUserProfile

    /** 정지. 이미 정지 중이면 지금부터 새 기한·이유로 바뀌어요. 관리자 대상은 403 FORBIDDEN, 없으면 404 USER_NOT_FOUND */
    suspend fun suspend(userId: Long, duration: SuspendDuration, reason: String)

    /** 정지 풀기. 정지되지 않았어도 성공해요 */
    suspend fun unsuspend(userId: Long)

    /** 탈퇴시키기. 그 이메일·소셜 계정으로는 다시 가입할 수 없어요 */
    suspend fun withdraw(userId: Long)

    /** 의견 목록. 최근 것이 위, 20개씩. [status]가 null이면 전부 */
    suspend fun feedback(status: FeedbackStatus? = null, cursor: String? = null): FeedbackPage

    /** 처리 표시. 없으면 404 FEEDBACK_NOT_FOUND */
    suspend fun markFeedback(id: Long, status: FeedbackStatus): Feedback

    /** 의견 보내기(관리자가 아니어도 돼요). 지난 24시간에 10번을 넘으면 429 TOO_MANY_REQUESTS */
    suspend fun sendFeedback(category: FeedbackCategory, text: String, appVersion: String, deviceModel: String): Feedback
}
