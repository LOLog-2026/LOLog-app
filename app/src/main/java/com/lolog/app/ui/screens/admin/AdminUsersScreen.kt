package com.lolog.app.ui.screens.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lolog.app.data.model.AdminUser
import com.lolog.app.data.model.AdminUserProfile
import com.lolog.app.data.model.AdminUserSort
import com.lolog.app.data.model.ErrorCodes
import com.lolog.app.data.model.SUSPEND_REASON_MAX
import com.lolog.app.data.model.SuspendDuration
import com.lolog.app.data.repo.AdminRepository
import com.lolog.app.data.repo.LologException
import com.lolog.app.ui.components.*
import com.lolog.app.ui.navigation.LocalContainer
import com.lolog.app.ui.theme.LologColors
import com.lolog.app.ui.theme.LologShape
import com.lolog.app.util.dayLabel
import com.lolog.app.util.longDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.OffsetDateTime

/*
 * 관리자 계정 관리 (admin.md 2·3·6장)
 *  - 이메일 일부로 찾기(치고 0.3초 뒤에 찾아요), 정렬 가입 최신순·오래된순·이메일순, 끝에 닿으면 다음 20개
 *  - 한 줄을 누르면 아래에서 올라오는 칸: 닉네임·친구 코드(GET /api/admin/users/{id}), 가입일, 정지 상태
 *  - 정지(기한 1·3·6개월·영구 + 이유 1~200자), 정지 풀기, 탈퇴시키기(다시 가입 못 함). 관리자 계정은 버튼이 없어요
 */
class AdminUsersViewModel(private val repo: AdminRepository) : ViewModel() {
    data class State(
        val query: String = "",
        val sort: AdminUserSort = AdminUserSort.NEWEST,
        val users: List<AdminUser> = emptyList(),
        val nextCursor: String? = null,
        val loading: Boolean = true,
        val loadingMore: Boolean = false,
        val error: String? = null,
        val snackbar: String? = null,
        /** 아래 칸에 연 사람 */
        val selected: AdminUser? = null,
        val profile: AdminUserProfile? = null,
        val profileLoading: Boolean = false,
        val profileFailed: Boolean = false,
        /** 정지 창 */
        val suspending: Boolean = false,
        val duration: SuspendDuration = SuspendDuration.MONTH_1,
        val reason: String = "",
        val reasonError: String? = null,
        val confirmUnsuspend: Boolean = false,
        val confirmWithdraw: Boolean = false,
        /** 정지·풀기·탈퇴시키기 요청 중 */
        val busy: Boolean = false,
    ) {
        val canSuspend get() = reason.isNotBlank() && !busy
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private var listJob: Job? = null
    private var profileJob: Job? = null

    /** 처음부터 다시 받기. [debounce]면 검색어를 치는 동안 기다렸다가 받아요 */
    fun load(debounce: Boolean = false) {
        listJob?.cancel()
        val s = _state.value
        listJob = viewModelScope.launch {
            if (debounce) delay(300)
            try {
                val page = repo.users(s.query, s.sort, null)
                _state.update { it.copy(users = page.users, nextCursor = page.nextCursor, loading = false, loadingMore = false, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update {
                    if (it.users.isEmpty()) it.copy(loading = false, error = errorText(e))
                    else it.copy(loading = false, snackbar = errorText(e))
                }
            }
        }
    }

    fun setQuery(q: String) {
        _state.update { it.copy(query = q, loading = it.users.isEmpty()) }
        load(debounce = true)
    }

    fun setSort(sort: AdminUserSort) {
        if (sort == _state.value.sort) return
        _state.update { it.copy(sort = sort, users = emptyList(), nextCursor = null, loading = true, error = null) }
        load()
    }

    fun retry() {
        _state.update { it.copy(loading = true, error = null) }
        load()
    }

    /** 다음 쪽도 같은 q·sort로(admin.md 6장) */
    fun loadMore() {
        val s = _state.value
        val cursor = s.nextCursor ?: return
        if (s.loading || s.loadingMore) return
        _state.update { it.copy(loadingMore = true) }
        listJob = viewModelScope.launch {
            try {
                val page = repo.users(s.query, s.sort, cursor)
                _state.update { st ->
                    val have = st.users.map { it.userId }.toSet()
                    st.copy(users = st.users + page.users.filterNot { it.userId in have }, nextCursor = page.nextCursor, loadingMore = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update { it.copy(loadingMore = false, snackbar = errorText(e)) }
            }
        }
    }

    fun open(u: AdminUser) {
        _state.update { it.copy(selected = u, profile = null, profileLoading = true, profileFailed = false) }
        profileJob?.cancel()
        profileJob = viewModelScope.launch {
            try {
                val p = repo.userProfile(u.userId)
                _state.update { if (it.selected?.userId != u.userId) it else it.copy(profile = p, profileLoading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update { if (it.selected?.userId != u.userId) it else it.copy(profileLoading = false, profileFailed = true) }
            }
        }
    }

    fun close() {
        if (_state.value.busy) return
        profileJob?.cancel()
        _state.update { it.copy(selected = null, profile = null, suspending = false, confirmUnsuspend = false, confirmWithdraw = false) }
    }

    fun askSuspend() = _state.update { it.copy(suspending = true, duration = SuspendDuration.MONTH_1, reason = "", reasonError = null) }
    fun setDuration(d: SuspendDuration) = _state.update { it.copy(duration = d) }
    /** 한 줄, 200자 넘게는 못 쳐요 */
    fun setReason(v: String) = _state.update { it.copy(reason = v.replace("\n", " ").take(SUSPEND_REASON_MAX), reasonError = null) }
    fun askUnsuspend() = _state.update { it.copy(confirmUnsuspend = true) }
    fun askWithdraw() = _state.update { it.copy(confirmWithdraw = true) }
    fun cancelDialogs() {
        if (_state.value.busy) return
        _state.update { it.copy(suspending = false, confirmUnsuspend = false, confirmWithdraw = false) }
    }

    /** 정지. 서버는 204만 주니 목록의 그 줄을 지금 시각 기준으로 바꿔요(admin.md 2장: 정지한 때부터 달력으로) */
    fun suspendUser(now: () -> OffsetDateTime = { OffsetDateTime.now() }) {
        val s = _state.value
        val u = s.selected ?: return
        if (!s.canSuspend) return
        val reason = s.reason.trim()
        act(u, doneText = "${s.duration.label} 정지했어요", close = false, request = { repo.suspend(u.userId, s.duration, reason) }) {
            val at = now()
            it.copy(suspendedAt = at.toString(), suspendedUntil = s.duration.months?.let { m -> at.plusMonths(m).toString() }, suspendReason = reason)
        }
    }

    fun unsuspend() {
        val u = _state.value.selected ?: return
        act(u, doneText = "정지를 풀었어요", close = false, request = { repo.unsuspend(u.userId) }) {
            it.copy(suspendedAt = null, suspendedUntil = null, suspendReason = null)
        }
    }

    /** 탈퇴시키기. 목록에서 빼고 아래 칸을 닫아요 */
    fun withdraw() {
        val u = _state.value.selected ?: return
        act(u, doneText = "탈퇴시켰어요", close = true, request = { repo.withdraw(u.userId) }) { null }
    }

    private fun act(u: AdminUser, doneText: String, close: Boolean, request: suspend () -> Unit, change: (AdminUser) -> AdminUser?) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                request()
                val next = change(u)
                _state.update { st ->
                    st.copy(
                        busy = false, suspending = false, confirmUnsuspend = false, confirmWithdraw = false,
                        users = if (next == null) st.users.filterNot { it.userId == u.userId } else st.users.map { if (it.userId == u.userId) next else it },
                        selected = if (close) null else next,
                        snackbar = doneText,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update { fail(it.copy(busy = false), u, e) }
            }
        }
    }

    /**
     * 400 reason → 이유 칸 아래 안내(창은 그대로), 403 관리자 대상 → 안내, 404 → 이미 탈퇴한 계정이라 목록에서 빼요,
     * 그 밖 → 창을 닫고 스낵바
     */
    private fun fail(s: State, u: AdminUser, e: Throwable): State {
        val code = (e as? LologException)?.code
        val field = (e as? LologException)?.field
        return when {
            code == ErrorCodes.INVALID_REQUEST && field == "reason" -> s.copy(reasonError = "이유를 1~${SUSPEND_REASON_MAX}자로 적어 주세요")
            code == ErrorCodes.USER_NOT_FOUND -> s.copy(
                users = s.users.filterNot { it.userId == u.userId }, selected = null,
                suspending = false, confirmUnsuspend = false, confirmWithdraw = false, snackbar = "이미 탈퇴한 계정이에요",
            )
            // 같은 403이어도 대상이 관리자일 때와 내가 관리자에서 빠졌을 때를 나눠 보여요.
            // 서버 message는 개발자용이라(api-dto ErrorResponse) 목록에서 받은 대상의 role로 나눠요
            code == ErrorCodes.FORBIDDEN -> s.copy(
                suspending = false, confirmUnsuspend = false, confirmWithdraw = false,
                // 정지 풀기도 관리자 계정은 403이에요(admin.md, 정함 11-13)
                snackbar = when {
                    !u.isAdmin -> errorText(e)
                    s.confirmUnsuspend -> "관리자 계정은 정지를 풀 수 없어요"
                    else -> "관리자 계정은 정지하거나 탈퇴시킬 수 없어요"
                },
            )
            else -> s.copy(suspending = false, confirmUnsuspend = false, confirmWithdraw = false, snackbar = errorText(e))
        }
    }

    fun snackbarShown() = _state.update { it.copy(snackbar = null) }
}

/** "12월 11일까지 정지" · "영구 정지" */
internal fun suspendLabel(u: AdminUser): String? = when {
    !u.suspended -> null
    u.permanent -> "영구 정지"
    else -> "${dayLabel(u.suspendedUntil)}까지 정지"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminUsersScreen(onBack: () -> Unit) {
    val repo = LocalContainer.current.admin
    val vm = viewModel { AdminUsersViewModel(repo) }
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) { vm.load() }
    LaunchedEffect(state.snackbar) { state.snackbar?.let { snackbar.showSnackbar(it); vm.snackbarShown() } }
    LaunchedEffect(listState) {
        snapshotFlow {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= listState.layoutInfo.totalItemsCount - 3
        }.distinctUntilChanged().filter { it }.collect { vm.loadMore() }
    }

    Scaffold(
        containerColor = LologColors.Paper,
        snackbarHost = { LologSnackbarHost(snackbar) },
        topBar = { LologTopBar("계정 관리", onBack) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SearchField(state.query, vm::setQuery, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AdminUserSort.entries.forEach { s -> FilterPill(s.label, state.sort == s) { vm.setSort(s) } }
            }
            Box(Modifier.fillMaxSize()) {
                when {
                    state.loading && state.users.isEmpty() -> SkeletonList()
                    state.error != null && state.users.isEmpty() ->
                        ListErrorState(onRetry = vm::retry, title = "계정을 불러오지 못했어요", body = state.error!!)
                    state.users.isEmpty() -> Text(
                        if (state.query.isBlank()) "아직 가입한 사람이 없어요" else "'${state.query.trim()}'이(가) 들어간 이메일이 없어요",
                        fontSize = 14.sp, color = LologColors.Muted, modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    )
                    else -> LazyColumn(
                        Modifier.fillMaxSize(), state = listState,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(state.users, key = { it.userId }) { u -> UserRow(u) { vm.open(u) } }
                        if (state.loadingMore) item { LoadMoreDots() }
                        if (state.nextCursor == null) item { ListEnd() }
                    }
                }
            }
        }
    }

    state.selected?.let { u ->
        LologSheet(onDismiss = vm::close) { UserSheet(u, state, vm) }
    }

    if (state.suspending) SuspendDialog(state, vm)
    if (state.confirmUnsuspend) {
        LologDialog(
            title = "정지를 풀까요?",
            body = "바로 다시 로그인할 수 있어요.",
            confirmText = "풀기",
            onConfirm = vm::unsuspend,
            onDismiss = vm::cancelDialogs,
            loading = state.busy,
        )
    }
    if (state.confirmWithdraw) {
        LologDialog(
            title = "이 계정을 탈퇴시킬까요?",
            body = "에피소드·만화·친구 등 모든 데이터가 지워지고 되돌릴 수 없어요. 이 이메일과 연결된 소셜 계정으로는 다시 가입할 수 없어요.",
            confirmText = "탈퇴시키기",
            onConfirm = vm::withdraw,
            onDismiss = vm::cancelDialogs,
            danger = true,
            loading = state.busy,
        )
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(LologShape.pill).background(LologColors.Canvas).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, null, tint = LologColors.Muted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text("이메일로 찾기", fontSize = 14.sp, color = LologColors.Muted)
            BasicTextField(
                value, { onChange(it.replace("\n", "").take(254)) }, singleLine = true,
                textStyle = TextStyle(fontSize = 14.sp, color = LologColors.Ink),
                cursorBrush = SolidColor(LologColors.Accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 흰 카드 한 줄: 이메일(굵게) + 가입일, 오른쪽에 관리자·정지 칩 */
@Composable
private fun UserRow(u: AdminUser, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().softCard().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(u.email, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = LologColors.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text("${dayLabel(u.createdAt)} 가입" + (suspendLabel(u)?.let { " · $it" } ?: ""), fontSize = 12.sp, color = LologColors.Muted, maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        when {
            u.isAdmin -> StateChip("관리자", LologColors.AccentSoft, LologColors.Accent)
            u.suspended -> StateChip("정지", LologColors.RecSoft, LologColors.Rec)
        }
    }
}

@Composable
internal fun StateChip(label: String, bg: Color, fg: Color) {
    Text(
        label, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = fg,
        modifier = Modifier.clip(LologShape.pill).background(bg).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** 아래 칸: 이메일, 닉네임·친구 코드, 가입일, 정지 상태와 버튼 */
@Composable
private fun UserSheet(u: AdminUser, state: AdminUsersViewModel.State, vm: AdminUsersViewModel) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
        Text(u.email, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = LologColors.Ink)
        Spacer(Modifier.height(12.dp))
        Column(Modifier.fillMaxWidth().clip(LologShape.md).background(LologColors.Canvas).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val p = state.profile
            InfoLine(
                "닉네임",
                when {
                    state.profileLoading -> "불러오는 중…"
                    state.profileFailed -> "불러오지 못했어요"
                    else -> p?.nickname ?: "아직 안 정함"
                },
            )
            InfoLine("친구 코드", if (state.profileLoading || state.profileFailed) "-" else p?.friendCode ?: "-")
            InfoLine("가입", dayLabel(u.createdAt))
            InfoLine("회원 번호", u.userId.toString())
            if (u.isAdmin) InfoLine("역할", "관리자")
        }
        if (u.suspended) {
            Spacer(Modifier.height(10.dp))
            Column(Modifier.fillMaxWidth().clip(LologShape.md).background(LologColors.RecSoft).padding(14.dp)) {
                Text(suspendLabel(u).orEmpty(), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = LologColors.Rec)
                u.suspendReason?.let {
                    Spacer(Modifier.height(4.dp))
                    Text("이유: $it", fontSize = 13.sp, color = LologColors.Ink)
                }
                u.suspendedAt?.let {
                    Spacer(Modifier.height(2.dp))
                    Text("${longDate(it)}에 정지함", fontSize = 12.sp, color = LologColors.Muted)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        if (u.isAdmin) {
            Text("관리자 계정은 정지하거나 탈퇴시킬 수 없어요.", fontSize = 12.sp, color = LologColors.Muted)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (u.suspended) {
                    GhostButton("정지 풀기", vm::askUnsuspend, Modifier.weight(1f), enabled = !state.busy)
                    GhostButton("다시 정지", vm::askSuspend, Modifier.weight(1f), enabled = !state.busy)
                } else {
                    GhostButton("정지하기", vm::askSuspend, Modifier.weight(1f), enabled = !state.busy)
                }
            }
            Spacer(Modifier.height(8.dp))
            PrimaryButton("탈퇴시키기", vm::askWithdraw, Modifier.fillMaxWidth(), enabled = !state.busy, danger = true)
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row {
        Text(label, fontSize = 13.sp, color = LologColors.Muted, modifier = Modifier.width(76.dp))
        Text(value, fontSize = 13.sp, color = LologColors.Ink, fontWeight = FontWeight.Medium)
    }
}

/** 정지 창: 기한 고르기 + 이유(1~200자). 다시 정지하면 지금부터 새 기한·이유로 바뀌어요 */
@Composable
private fun SuspendDialog(state: AdminUsersViewModel.State, vm: AdminUsersViewModel) {
    androidx.compose.ui.window.Dialog(onDismissRequest = vm::cancelDialogs) {
        Column(
            Modifier.fillMaxWidth().clip(LologShape.md).background(LologColors.Paper).padding(20.dp),
        ) {
            Text("계정 정지", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = LologColors.Ink)
            Spacer(Modifier.height(4.dp))
            Text(
                if (state.selected?.suspended == true) "지금부터 새 기한과 이유로 바뀌어요." else "정지하면 바로 로그아웃되고, 기한이 지나면 저절로 풀려요.",
                fontSize = 13.sp, color = LologColors.Muted,
            )
            Spacer(Modifier.height(14.dp))
            Text("기한", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = LologColors.Muted)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SuspendDuration.entries.forEach { d -> FilterPill(d.label, state.duration == d) { vm.setDuration(d) } }
            }
            Spacer(Modifier.height(14.dp))
            FieldBox("이유", counter = "${state.reason.length} / $SUSPEND_REASON_MAX", error = state.reasonError != null) {
                Box {
                    if (state.reason.isEmpty()) Text("사용자에게는 보이지 않아요", fontSize = 14.sp, color = LologColors.Muted)
                    BasicTextField(
                        state.reason, vm::setReason, enabled = !state.busy,
                        textStyle = TextStyle(fontSize = 14.sp, color = LologColors.Ink, lineHeight = 20.sp),
                        cursorBrush = SolidColor(LologColors.Accent),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                    )
                }
            }
            state.reasonError?.let { FieldError(it) }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton("취소", vm::cancelDialogs, Modifier.weight(1f), enabled = !state.busy)
                PrimaryButton("정지하기", { vm.suspendUser() }, Modifier.weight(1f), enabled = state.canSuspend, loading = state.busy, danger = true)
            }
        }
    }
}
