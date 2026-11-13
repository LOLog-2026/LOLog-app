package com.lolog.app.ui

import com.lolog.app.data.mock.MockAdmin
import com.lolog.app.data.model.AdminUserSort
import com.lolog.app.data.model.FEEDBACK_PER_DAY
import com.lolog.app.data.model.FEEDBACK_TEXT_MAX
import com.lolog.app.data.model.FeedbackCategory
import com.lolog.app.data.model.FeedbackStatus
import com.lolog.app.data.model.SUSPEND_REASON_MAX
import com.lolog.app.data.model.SuspendDuration
import com.lolog.app.data.remote.AdminMapper.toModel
import com.lolog.app.data.remote.AdminUserPageDto
import com.lolog.app.data.remote.FeedbackDto
import com.lolog.app.data.remote.LologJson
import com.lolog.app.data.remote.SuspendDto
import com.lolog.app.data.remote.isPublicAuthPath
import com.lolog.app.ui.screens.admin.AdminFeedbackViewModel
import com.lolog.app.ui.screens.admin.AdminUsersViewModel
import com.lolog.app.ui.screens.admin.senderLabel
import com.lolog.app.ui.screens.admin.suspendLabel
import com.lolog.app.ui.screens.settings.FeedbackSendViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.OffsetDateTime

/** 관리자 계정 관리·의견, 설정의 의견 보내기(LOLog-docs spec/admin.md 2·3·5·6·7장) */
@OptIn(ExperimentalCoroutinesApi::class)
class AdminScreensTest {

    private val dispatcher = StandardTestDispatcher()
    private val now = OffsetDateTime.parse("2026-11-12T10:00:00+09:00")

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun mock(admin: Boolean = true) = MockAdmin(isAdmin = { admin }, netMs = 0, clock = { now })
    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }

    // ── 계정 관리 ──

    @Test
    fun `계정 목록은 20개씩 받고 다음 쪽을 이어 붙인다`() = test {
        val vm = AdminUsersViewModel(mock())
        vm.load(); advanceUntilIdle()
        assertEquals(20, vm.state.value.users.size)
        assertEquals(30L, vm.state.value.users.first().userId) // 기본은 가입 최신순
        assertNotNull(vm.state.value.nextCursor)
        vm.loadMore(); advanceUntilIdle()
        assertEquals(30, vm.state.value.users.size)
        assertNull(vm.state.value.nextCursor)
    }

    @Test
    fun `이메일 찾기는 대소문자를 가리지 않고 정렬을 바꾸면 처음부터 받는다`() = test {
        val vm = AdminUsersViewModel(mock())
        vm.load(); advanceUntilIdle()
        vm.setQuery("SEOHYUN"); advanceUntilIdle()
        assertEquals(listOf("Seohyun@Example.com"), vm.state.value.users.map { it.email })
        vm.setQuery(""); vm.setSort(AdminUserSort.EMAIL); advanceUntilIdle()
        assertEquals("admin@lolog.app", vm.state.value.users.first().email)
    }

    @Test
    fun `관리자가 아니면 목록 대신 안내를 보여 준다`() = test {
        val vm = AdminUsersViewModel(mock(admin = false))
        vm.load(); advanceUntilIdle()
        assertTrue(vm.state.value.users.isEmpty())
        assertEquals("관리자만 할 수 있어요", vm.state.value.error)
    }

    @Test
    fun `한 사람을 열면 닉네임과 친구 코드를 받는다`() = test {
        val vm = AdminUsersViewModel(mock())
        vm.setSort(AdminUserSort.OLDEST); advanceUntilIdle() // 먼저 가입한 계정이 첫 쪽에
        vm.open(vm.state.value.users.first { it.userId == 2L }); advanceUntilIdle()
        assertEquals("민수", vm.state.value.profile?.nickname)
        assertEquals("MINSU2", vm.state.value.profile?.friendCode)
        // 닉네임을 정하기 전이면 둘 다 null
        vm.open(vm.state.value.users.first { it.userId == 20L }); advanceUntilIdle()
        assertNull(vm.state.value.profile?.nickname)
    }

    @Test
    fun `정지하면 그 줄이 지금부터 고른 기한까지 정지로 바뀐다`() = test {
        val repo = mock()
        val vm = AdminUsersViewModel(repo)
        vm.setSort(AdminUserSort.OLDEST); advanceUntilIdle() // 먼저 가입한 계정이 첫 쪽에
        vm.open(vm.state.value.users.first { it.userId == 2L })
        vm.askSuspend()
        assertFalse(vm.state.value.canSuspend) // 이유가 비면 버튼이 꺼져요
        vm.setDuration(SuspendDuration.MONTH_3)
        vm.setReason("  욕설을 계속 씀  ")
        vm.suspendUser { now }; advanceUntilIdle()
        val u = vm.state.value.users.first { it.userId == 2L }
        assertEquals(now.toString(), u.suspendedAt)
        assertEquals(now.plusMonths(3).toString(), u.suspendedUntil)
        assertEquals("욕설을 계속 씀", u.suspendReason)
        assertFalse(vm.state.value.suspending)
        assertEquals("3개월 정지했어요", vm.state.value.snackbar)
        // 서버(목업)에도 같게 남아요
        assertEquals("욕설을 계속 씀", repo.users(q = "minsu").users.single().suspendReason)
    }

    @Test
    fun `영구 정지와 정지 풀기`() = test {
        val vm = AdminUsersViewModel(mock())
        vm.setSort(AdminUserSort.OLDEST); advanceUntilIdle() // 먼저 가입한 계정이 첫 쪽에
        vm.open(vm.state.value.users.first { it.userId == 3L })
        vm.askSuspend(); vm.setDuration(SuspendDuration.PERMANENT); vm.setReason("광고")
        vm.suspendUser { now }; advanceUntilIdle()
        val u = vm.state.value.selected!!
        assertTrue(u.permanent)
        assertEquals("영구 정지", suspendLabel(u))
        vm.askUnsuspend(); vm.unsuspend(); advanceUntilIdle()
        assertFalse(vm.state.value.users.first { it.userId == 3L }.suspended)
        assertEquals("정지를 풀었어요", vm.state.value.snackbar)
    }

    @Test
    fun `정지 이유는 200자까지만 들어간다`() = test {
        val vm = AdminUsersViewModel(mock())
        vm.setReason("가".repeat(SUSPEND_REASON_MAX + 30))
        assertEquals(SUSPEND_REASON_MAX, vm.state.value.reason.length)
    }

    @Test
    fun `관리자 계정은 정지할 수 없다고 알린다`() = test {
        val vm = AdminUsersViewModel(mock())
        vm.setSort(AdminUserSort.OLDEST); advanceUntilIdle() // 먼저 가입한 계정이 첫 쪽에
        vm.open(vm.state.value.users.first { it.isAdmin })
        vm.askSuspend(); vm.setReason("시험")
        vm.suspendUser { now }; advanceUntilIdle()
        assertEquals("관리자 계정은 정지하거나 탈퇴시킬 수 없어요", vm.state.value.snackbar)
        assertFalse(vm.state.value.users.first { it.isAdmin }.suspended)
    }

    @Test
    fun `내가 관리자에서 빠졌으면 서버 message와 상관없이 관리자만 할 수 있다고 알린다`() = test {
        var admin = true
        val vm = AdminUsersViewModel(MockAdmin(isAdmin = { admin }, netMs = 0, clock = { now }))
        vm.setSort(AdminUserSort.OLDEST); advanceUntilIdle()
        vm.open(vm.state.value.users.first { it.userId == 2L })
        vm.askSuspend(); vm.setReason("시험")
        admin = false // 목록을 받은 뒤 관리자에서 빠짐 → 403 FORBIDDEN
        vm.suspendUser { now }; advanceUntilIdle()
        assertEquals("관리자만 할 수 있어요", vm.state.value.snackbar)
    }

    @Test
    fun `탈퇴시키면 목록에서 빠지고 보낸 의견은 탈퇴한 사용자로 남는다`() = test {
        val repo = mock()
        val vm = AdminUsersViewModel(repo)
        vm.setSort(AdminUserSort.OLDEST); advanceUntilIdle() // 먼저 가입한 계정이 첫 쪽에
        vm.open(vm.state.value.users.first { it.userId == 3L })
        vm.askWithdraw(); vm.withdraw(); advanceUntilIdle()
        assertTrue(vm.state.value.users.none { it.userId == 3L })
        assertNull(vm.state.value.selected)
        assertTrue(repo.users(q = "jiwoo").users.isEmpty())
        val left = repo.feedback(FeedbackStatus.DONE).items.single()
        assertNull(left.userId)
        assertEquals("탈퇴한 사용자", senderLabel(left))
    }

    // ── 의견 목록 ──

    @Test
    fun `의견은 처리 전 탭부터 보이고 처리하면 그 탭에서 빠진다`() = test {
        val vm = AdminFeedbackViewModel(mock())
        vm.load(); advanceUntilIdle()
        val first = vm.state.value.items.first()
        assertTrue(vm.state.value.items.all { it.status == FeedbackStatus.OPEN })
        assertEquals(20, vm.state.value.items.size)
        vm.toggle(first); advanceUntilIdle()
        assertTrue(vm.state.value.items.none { it.id == first.id })
        assertEquals("처리함으로 옮겼어요", vm.state.value.snackbar)
        vm.setTab(FeedbackStatus.DONE); advanceUntilIdle()
        assertTrue(vm.state.value.items.any { it.id == first.id })
        // 전체 탭에서는 바꿔도 그대로 남아요
        vm.setTab(null); advanceUntilIdle()
        vm.toggle(vm.state.value.items.first { it.id == first.id }); advanceUntilIdle()
        assertEquals(FeedbackStatus.OPEN, vm.state.value.items.first { it.id == first.id }.status)
    }

    @Test
    fun `의견 다음 쪽은 같은 탭으로 이어 받는다`() = test {
        val vm = AdminFeedbackViewModel(mock())
        vm.load(); advanceUntilIdle()
        vm.loadMore(); advanceUntilIdle()
        assertEquals(24, vm.state.value.items.size)
        assertEquals(24, vm.state.value.items.map { it.id }.toSet().size)
        assertNull(vm.state.value.nextCursor)
    }

    // ── 의견 보내기 ──

    @Test
    fun `의견을 보내면 끝나고 하루 10번을 넘으면 안내한다`() = test {
        val repo = mock(admin = false) // 관리자가 아니어도 보낼 수 있어요
        repeat(FEEDBACK_PER_DAY) { repo.sendFeedback(FeedbackCategory.OPINION, "의견 $it", "0.1.0", "LG Velvet") }
        val vm = FeedbackSendViewModel(repo, "0.1.0", "LG Velvet")
        vm.setCategory(FeedbackCategory.BUG); vm.setText("녹음이 멈춰요")
        vm.send(); advanceUntilIdle()
        assertFalse(vm.state.value.sent)
        assertEquals("의견은 하루에 10번까지 보낼 수 있어요. 내일 다시 보내 주세요", vm.state.value.snackbar)

        val ok = FeedbackSendViewModel(mock(admin = false), "0.1.0", "LG Velvet")
        ok.setText("  쇼츠가 좋아요  ")
        ok.send(); advanceUntilIdle()
        assertTrue(ok.state.value.sent)
    }

    @Test
    fun `의견 글은 1000자까지만 들어가고 비면 보내기가 꺼진다`() = test {
        val vm = FeedbackSendViewModel(mock(), "0.1.0", "LG Velvet")
        assertFalse(vm.state.value.canSend)
        vm.setText("   ")
        assertFalse(vm.state.value.canSend)
        vm.setText("가".repeat(FEEDBACK_TEXT_MAX + 5))
        assertEquals(FEEDBACK_TEXT_MAX, vm.state.value.text.length)
    }

    // ── 서버 모양 ──

    @Test
    fun `계정 목록과 의견 응답을 모델로 바꾼다`() {
        val page = LologJson.decodeFromString(
            AdminUserPageDto.serializer(),
            """{"users":[{"userId":7,"email":"a@b.c","createdAt":"2026-11-01T00:00:00Z","role":"USER",
               "suspendedAt":"2026-11-10T00:00:00Z","suspendedUntil":null,"suspendReason":"광고"}],"nextCursor":"7"}""",
        ).toModel()
        val u = page.users.single()
        assertTrue(u.suspended)
        assertTrue(u.permanent)
        assertEquals("7", page.nextCursor)

        val f = LologJson.decodeFromString(
            FeedbackDto.serializer(),
            """{"id":1,"userId":null,"category":"BUG","text":"멈춰요","appVersion":"0.1.0","deviceModel":"LM-G900N","status":"NEW","createdAt":"2026-11-12T00:00:00Z"}""",
        ).toModel()
        assertEquals(FeedbackCategory.BUG, f.category)
        assertEquals(FeedbackStatus.OPEN, f.status) // 모르는 상태는 처리 전
        assertEquals("탈퇴한 사용자", senderLabel(f))
    }

    @Test
    fun `정지 요청은 서버 이름 그대로 보낸다`() {
        assertEquals(
            """{"duration":"MONTH_6","reason":"광고"}""",
            LologJson.encodeToString(SuspendDto.serializer(), SuspendDto(SuspendDuration.MONTH_6.name, "광고")),
        )
    }

    @Test
    fun `관리자 auth 주소에는 토큰을 붙인다`() {
        assertFalse(isPublicAuthPath("/auth/admin/users"))
        assertFalse(isPublicAuthPath("/auth/admin/users/3/suspend"))
        assertFalse(isPublicAuthPath("/auth/me"))
        assertTrue(isPublicAuthPath("/auth/login"))
        assertTrue(isPublicAuthPath("/auth/refresh"))
        assertFalse(isPublicAuthPath("/api/admin/feedback"))
    }
}
