package com.lolog.app.data.mock

import com.lolog.app.data.model.AdminErrorCodes
import com.lolog.app.data.model.AdminUser
import com.lolog.app.data.model.AdminUserPage
import com.lolog.app.data.model.AdminUserProfile
import com.lolog.app.data.model.AdminUserSort
import com.lolog.app.data.model.ErrorCodes
import com.lolog.app.data.model.FEEDBACK_PER_DAY
import com.lolog.app.data.model.FEEDBACK_TEXT_MAX
import com.lolog.app.data.model.Feedback
import com.lolog.app.data.model.FeedbackCategory
import com.lolog.app.data.model.FeedbackPage
import com.lolog.app.data.model.FeedbackStatus
import com.lolog.app.data.model.SUSPEND_REASON_MAX
import com.lolog.app.data.model.SuspendDuration
import com.lolog.app.data.repo.AdminRepository
import com.lolog.app.data.repo.LologException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.OffsetDateTime

/**
 * 관리자 계정 관리·의견 목업. 서버(admin.md 2·3·5·6·7장)와 같은 규칙으로 답해요.
 *  - 관리자 주소는 [isAdmin]이 false면 403 FORBIDDEN (MockLologRepository: admin@lolog.app으로 로그인)
 *  - 계정 목록 20개씩, 이메일 일부로 찾기(대소문자 무시), 정렬 NEWEST·OLDEST·EMAIL
 *  - 관리자는 정지·탈퇴시킬 수 없어요(403), 없는 회원은 404 USER_NOT_FOUND
 *  - 의견은 20개씩 최근 것이 위, 한 사람이 24시간에 10번까지
 */
class MockAdmin(
    private val isAdmin: suspend () -> Boolean,
    private val netMs: Long = 300,
    private val clock: () -> OffsetDateTime = { OffsetDateTime.now() },
) : AdminRepository {

    private val lock = Mutex()
    private suspend fun net() { if (netMs > 0) delay(netMs) }
    private fun fail(code: String, msg: String, field: String? = null): Nothing = throw LologException(code, msg, field)
    private suspend fun requireAdmin() { if (!isAdmin()) fail(ErrorCodes.FORBIDDEN, "관리자만 쓸 수 있습니다.") }

    private val base = OffsetDateTime.parse("2026-11-01T09:00:00+09:00")

    /** 가입한 순서(userId가 클수록 최근) */
    private val users = mutableListOf(
        AdminUser(1, "admin@lolog.app", base.toString(), "ADMIN"),
        AdminUser(2, "minsu@example.com", base.plusDays(1).toString(), "USER"),
        AdminUser(3, "jiwoo@example.com", base.plusDays(2).toString(), "USER"),
        AdminUser(
            4, "spam_bot@example.com", base.plusDays(3).toString(), "USER",
            suspendedAt = base.plusDays(4).toString(), suspendedUntil = base.plusDays(4).plusMonths(1).toString(),
            suspendReason = "광고 글을 계속 올림",
        ),
        AdminUser(5, "Seohyun@Example.com", base.plusDays(5).toString(), "USER"),
    ).also { list ->
        // 다음 쪽을 볼 수 있게 시험용 계정을 더 둬요
        for (i in 6L..30L) list += AdminUser(i, "tester%02d@example.com".format(i), base.plusDays(i).toString(), "USER")
    }

    private val profiles = mapOf(
        1L to AdminUserProfile("운영자", "ADMIN1"),
        2L to AdminUserProfile("민수", "MINSU2"),
        3L to AdminUserProfile("지우", "JIWOO3"),
        4L to AdminUserProfile("광고봇", "SPAM44"),
    )

    private var nextFeedbackId = 1L
    private val feedbackItems = mutableListOf<Feedback>().also { list ->
        fun add(userId: Long?, c: FeedbackCategory, text: String, status: FeedbackStatus, daysAgo: Long) {
            list += Feedback(
                nextFeedbackId++, userId, c, text, "0.1.0", "LM-G900N", status,
                OffsetDateTime.now().minusDays(daysAgo).toString(),
            )
        }
        add(3, FeedbackCategory.OPINION, "친구 방에서 쇼츠도 같이 보고 싶어요", FeedbackStatus.DONE, 3)
        add(null, FeedbackCategory.BUG, "녹음이 가끔 멈춰요", FeedbackStatus.OPEN, 2)
        add(2, FeedbackCategory.BUG, "만화 저장 버튼을 누르면 앱이 잠깐 멈춰요", FeedbackStatus.OPEN, 1)
        for (i in 1..22) add(2, FeedbackCategory.OPINION, "시험용 의견 $i", FeedbackStatus.OPEN, 0)
    }

    /** 내가 보낸 의견 시각(24시간에 10번 세기) */
    private val mySent = mutableListOf<OffsetDateTime>()

    override suspend fun users(q: String?, sort: AdminUserSort, cursor: String?): AdminUserPage = lock.withLock {
        net(); requireAdmin()
        val key = q?.trim()?.lowercase()?.ifEmpty { null }
        val found = users.filter { key == null || key in it.email.lowercase() }.let { l ->
            when (sort) {
                AdminUserSort.NEWEST -> l.sortedByDescending { it.userId }
                AdminUserSort.OLDEST -> l.sortedBy { it.userId }
                AdminUserSort.EMAIL -> l.sortedWith(compareBy({ it.email.lowercase() }, { it.userId }))
            }
        }.map { it.current() }
        val start = cursor?.toIntOrNull() ?: 0
        val page = found.drop(start).take(PAGE)
        AdminUserPage(page, if (start + PAGE < found.size) (start + PAGE).toString() else null)
    }

    override suspend fun userProfile(userId: Long): AdminUserProfile = lock.withLock {
        net(); requireAdmin()
        profiles[userId] ?: AdminUserProfile(null, null)
    }

    override suspend fun suspend(userId: Long, duration: SuspendDuration, reason: String) = lock.withLock {
        net(); requireAdmin()
        val r = reason.trim()
        if (r.isEmpty() || r.length > SUSPEND_REASON_MAX) fail(ErrorCodes.INVALID_REQUEST, "reason은 1~200자여야 합니다.", "reason")
        val i = target(userId, "관리자는 정지할 수 없습니다.")
        val now = clock()
        users[i] = users[i].copy(
            suspendedAt = now.toString(),
            suspendedUntil = duration.months?.let { now.plusMonths(it).toString() },
            suspendReason = r,
        )
    }

    override suspend fun unsuspend(userId: Long) = lock.withLock {
        net(); requireAdmin()
        val i = target(userId, "관리자는 정지할 수 없습니다.")
        users[i] = users[i].copy(suspendedAt = null, suspendedUntil = null, suspendReason = null)
    }

    override suspend fun withdraw(userId: Long) = lock.withLock {
        net(); requireAdmin()
        val i = target(userId, "관리자는 탈퇴시킬 수 없습니다.")
        users.removeAt(i)
        // 보낸 의견은 남기고 보낸 사람만 비워요(admin.md 5장)
        feedbackItems.replaceAll { if (it.userId == userId) it.copy(userId = null) else it }
    }

    override suspend fun feedback(status: FeedbackStatus?, cursor: String?): FeedbackPage = lock.withLock {
        net(); requireAdmin()
        val all = feedbackItems.filter { status == null || it.status == status }.sortedByDescending { it.id }
        val after = cursor?.toLongOrNull()
        val rest = if (after == null) all else all.filter { it.id < after }
        val page = rest.take(PAGE)
        FeedbackPage(page, if (rest.size > PAGE) page.last().id.toString() else null)
    }

    override suspend fun markFeedback(id: Long, status: FeedbackStatus): Feedback = lock.withLock {
        net(); requireAdmin()
        val i = feedbackItems.indexOfFirst { it.id == id }
        if (i < 0) fail(AdminErrorCodes.FEEDBACK_NOT_FOUND, "의견을 찾을 수 없어요.")
        feedbackItems[i] = feedbackItems[i].copy(status = status)
        feedbackItems[i]
    }

    override suspend fun sendFeedback(category: FeedbackCategory, text: String, appVersion: String, deviceModel: String): Feedback =
        lock.withLock {
            net()
            val t = text.trim()
            if (t.isEmpty() || t.length > FEEDBACK_TEXT_MAX) fail(ErrorCodes.INVALID_REQUEST, "내용은 1~1000자예요.", "text")
            val now = clock()
            mySent.removeAll { it.isBefore(now.minusDays(1)) }
            if (mySent.size >= FEEDBACK_PER_DAY) fail(ErrorCodes.TOO_MANY_REQUESTS, "의견은 하루에 10번까지 보낼 수 있어요.")
            mySent += now
            val f = Feedback(nextFeedbackId++, MockFriends.MY_USER_ID, category, t, appVersion, deviceModel, FeedbackStatus.OPEN, now.toString())
            feedbackItems += f
            f
        }

    /** 기한이 지난 정지는 목록에서 빈칸으로(서버와 같음) */
    private fun AdminUser.current(): AdminUser {
        val until = suspendedUntil?.let { OffsetDateTime.parse(it) }
        return if (suspended && until != null && !until.isAfter(clock())) copy(suspendedAt = null, suspendedUntil = null, suspendReason = null)
        else this
    }

    /** 대상 확인: 없으면 404, 관리자면 403 */
    private fun target(userId: Long, adminMessage: String): Int {
        val i = users.indexOfFirst { it.userId == userId }
        if (i < 0) fail(ErrorCodes.USER_NOT_FOUND, "회원이 없습니다.")
        if (users[i].isAdmin) fail(ErrorCodes.FORBIDDEN, adminMessage)
        return i
    }

    companion object {
        const val PAGE = 20
    }
}
