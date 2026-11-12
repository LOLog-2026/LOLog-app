package com.lolog.app.ui.screens.admin

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lolog.app.data.model.AdminErrorCodes
import com.lolog.app.data.model.Feedback
import com.lolog.app.data.model.FeedbackCategory
import com.lolog.app.data.model.FeedbackStatus
import com.lolog.app.data.repo.AdminRepository
import com.lolog.app.data.repo.LologException
import com.lolog.app.ui.components.*
import com.lolog.app.ui.navigation.LocalContainer
import com.lolog.app.ui.theme.LologColors
import com.lolog.app.ui.theme.LologShape
import com.lolog.app.util.longDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/*
 * 관리자 의견·오류 신고 (admin.md 5·7장)
 *  - 탭: 처리 전(OPEN, 처음) · 처리함(DONE) · 전체. 최근 것이 위, 끝에 닿으면 다음 20개
 *  - 한 줄: 종류 칩(오류 신고·의견), 날짜, 글(누르면 펼침), 보낸 사람(탈퇴했으면 "탈퇴한 사용자"), 앱 버전·기기
 *  - 오른쪽 아래 "처리함으로"·"처리 전으로"(PATCH). 지금 탭과 맞지 않게 되면 목록에서 빠져요
 */
class AdminFeedbackViewModel(private val repo: AdminRepository) : ViewModel() {
    /** 탭 순서: 처리 전 · 처리함 · 전체(null) */
    val tabs: List<FeedbackStatus?> = listOf(FeedbackStatus.OPEN, FeedbackStatus.DONE, null)

    data class State(
        val tab: FeedbackStatus? = FeedbackStatus.OPEN,
        val items: List<Feedback> = emptyList(),
        val nextCursor: String? = null,
        val loading: Boolean = true,
        val refreshing: Boolean = false,
        val loadingMore: Boolean = false,
        val error: String? = null,
        val snackbar: String? = null,
        /** 처리 표시를 바꾸는 중인 의견 */
        val marking: Set<Long> = emptySet(),
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private var listJob: Job? = null

    fun load() {
        listJob?.cancel()
        val tab = _state.value.tab
        listJob = viewModelScope.launch {
            try {
                val page = repo.feedback(tab, null)
                _state.update {
                    if (it.tab != tab) it
                    else it.copy(items = page.items, nextCursor = page.nextCursor, loading = false, refreshing = false, loadingMore = false, error = null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update {
                    if (it.items.isEmpty()) it.copy(loading = false, refreshing = false, error = errorText(e))
                    else it.copy(loading = false, refreshing = false, snackbar = errorText(e))
                }
            }
        }
    }

    fun setTab(tab: FeedbackStatus?) {
        if (tab == _state.value.tab) return
        _state.update { it.copy(tab = tab, items = emptyList(), nextCursor = null, loading = true, error = null) }
        load()
    }

    fun refresh() {
        _state.update { it.copy(refreshing = true) }
        load()
    }

    fun retry() {
        _state.update { it.copy(loading = true, error = null) }
        load()
    }

    fun loadMore() {
        val s = _state.value
        val cursor = s.nextCursor ?: return
        if (s.loading || s.refreshing || s.loadingMore) return
        _state.update { it.copy(loadingMore = true) }
        listJob = viewModelScope.launch {
            try {
                val page = repo.feedback(s.tab, cursor)
                _state.update { st ->
                    if (st.tab != s.tab) st
                    else {
                        val have = st.items.map { it.id }.toSet()
                        st.copy(items = st.items + page.items.filterNot { it.id in have }, nextCursor = page.nextCursor, loadingMore = false)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update { it.copy(loadingMore = false, snackbar = errorText(e)) }
            }
        }
    }

    /** 처리함 ↔ 처리 전. 바뀐 뒤 지금 탭과 맞지 않으면 목록에서 빼요 */
    fun toggle(f: Feedback) {
        if (f.id in _state.value.marking) return
        val next = if (f.status == FeedbackStatus.OPEN) FeedbackStatus.DONE else FeedbackStatus.OPEN
        _state.update { it.copy(marking = it.marking + f.id) }
        viewModelScope.launch {
            try {
                val updated = repo.markFeedback(f.id, next)
                _state.update { st ->
                    val keep = st.tab == null || st.tab == updated.status
                    st.copy(
                        marking = st.marking - f.id,
                        items = if (keep) st.items.map { if (it.id == f.id) updated else it } else st.items.filterNot { it.id == f.id },
                        snackbar = if (next == FeedbackStatus.DONE) "처리함으로 옮겼어요" else "처리 전으로 옮겼어요",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val gone = (e as? LologException)?.code == AdminErrorCodes.FEEDBACK_NOT_FOUND
                _state.update { st ->
                    st.copy(
                        marking = st.marking - f.id,
                        items = if (gone) st.items.filterNot { it.id == f.id } else st.items,
                        snackbar = if (gone) "찾을 수 없는 의견이에요" else errorText(e),
                    )
                }
            }
        }
    }

    fun snackbarShown() = _state.update { it.copy(snackbar = null) }
}

internal fun feedbackTabLabel(tab: FeedbackStatus?) = tab?.label ?: "전체"

/** "회원 12" · "탈퇴한 사용자"(admin.md 5장) */
internal fun senderLabel(f: Feedback) = f.userId?.let { "회원 $it" } ?: "탈퇴한 사용자"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminFeedbackScreen(onBack: () -> Unit) {
    val repo = LocalContainer.current.admin
    val vm = viewModel { AdminFeedbackViewModel(repo) }
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
        topBar = { LologTopBar("의견·오류 신고", onBack) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ContentTabs(vm.tabs.map(::feedbackTabLabel), vm.tabs.indexOf(state.tab), { vm.setTab(vm.tabs[it]) })
            Box(Modifier.fillMaxSize()) {
                when {
                    state.loading && state.items.isEmpty() -> SkeletonList()
                    state.error != null && state.items.isEmpty() ->
                        ListErrorState(onRetry = vm::retry, title = "의견을 불러오지 못했어요", body = state.error!!)
                    else -> PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
                        LazyColumn(
                            Modifier.fillMaxSize(), state = listState,
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (state.items.isEmpty()) {
                                item {
                                    Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                                        Text(
                                            if (state.tab == FeedbackStatus.OPEN) "처리할 의견이 없어요" else "아직 의견이 없어요",
                                            fontSize = 14.sp, color = LologColors.Muted,
                                        )
                                    }
                                }
                            }
                            items(state.items, key = { it.id }) { f -> FeedbackRow(f, marking = f.id in state.marking) { vm.toggle(f) } }
                            if (state.loadingMore) item { LoadMoreDots() }
                            if (state.items.isNotEmpty() && state.nextCursor == null) item { ListEnd() }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FeedbackRow(f: Feedback, marking: Boolean, onToggle: () -> Unit) {
    var open by remember(f.id) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().softCard().clickable { open = !open }.animateContentSize().padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (f.category == FeedbackCategory.BUG) StateChip(f.category.label, LologColors.RecSoft, LologColors.Rec)
            else StateChip(f.category.label, LologColors.MemorySoft, LologColors.MemoryText)
            Spacer(Modifier.width(8.dp))
            Text(longDate(f.createdAt), fontSize = 12.sp, color = LologColors.Muted, modifier = Modifier.weight(1f))
            if (f.status == FeedbackStatus.DONE) Text("처리함", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = LologColors.Ok)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            f.text, fontSize = 14.sp, color = LologColors.Ink, lineHeight = 20.sp,
            maxLines = if (open) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "${senderLabel(f)} · 앱 ${f.appVersion} · ${f.deviceModel}",
            fontSize = 12.sp, color = LologColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(
                if (marking) "바꾸는 중…" else if (f.status == FeedbackStatus.OPEN) "처리함으로" else "처리 전으로",
                fontSize = 13.sp, fontWeight = FontWeight.Bold,
                color = if (marking) LologColors.Muted else LologColors.Accent,
                modifier = Modifier.clip(LologShape.pill).clickable(enabled = !marking, onClick = onToggle)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}
