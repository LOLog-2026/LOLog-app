package com.lolog.app.ui.screens.admin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lolog.app.data.model.InboxItem
import com.lolog.app.data.model.InboxTab
import com.lolog.app.data.repo.LologRepository
import com.lolog.app.ui.components.*
import com.lolog.app.ui.navigation.LocalContainer
import com.lolog.app.ui.theme.LologColors
import com.lolog.app.ui.theme.LologShape
import com.lolog.app.util.dayLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 관리자 공지 목록. 따로 주소가 없어서 알림함 공지사항 탭(GET /api/inbox?tab=NOTICE)을 같이 써요 */
class AdminNoticeListViewModel(private val repo: LologRepository) : ViewModel() {
    data class State(
        val items: List<InboxItem.Notice> = emptyList(),
        val nextCursor: String? = null,
        val loading: Boolean = true,
        val refreshing: Boolean = false,
        val loadingMore: Boolean = false,
        /** 처음 목록을 못 받았을 때(서버가 아직 지원하지 않을 때 포함) 오류 안내 */
        val error: String? = null,
        val snackbar: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /** 첫 쪽·다음 쪽 받기. 처음부터 다시 받으면 하던 것을 멈춰요(늦게 온 옛 목록이 섞이지 않게) */
    private var listJob: Job? = null

    /** 처음부터 다시 받기. 화면에 돌아올 때마다(쓰기·고치기·지우기 뒤) 불러요. 받는 동안 지금 목록은 그대로 둬요 */
    fun load() {
        listJob?.cancel()
        listJob = viewModelScope.launch {
            try {
                val page = repo.inbox(InboxTab.NOTICE)
                _state.update {
                    it.copy(
                        items = page.items.filterIsInstance<InboxItem.Notice>(), nextCursor = page.nextCursor,
                        loading = false, refreshing = false, loadingMore = false, error = null,
                    )
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

    /** 당겨서 새로고침 */
    fun refresh() {
        _state.update { it.copy(refreshing = true) }
        load()
    }

    fun retry() {
        _state.update { it.copy(loading = true, error = null) }
        load()
    }

    /** 아래로 내리면 nextCursor로 다음 쪽 */
    fun loadMore() {
        val s = _state.value
        val cursor = s.nextCursor ?: return
        if (s.loading || s.refreshing || s.loadingMore) return
        _state.update { it.copy(loadingMore = true) }
        listJob = viewModelScope.launch {
            try {
                val page = repo.inbox(InboxTab.NOTICE, cursor)
                _state.update { st ->
                    val have = st.items.map { it.id }.toSet()
                    val more = page.items.filterIsInstance<InboxItem.Notice>().filterNot { it.id in have }
                    st.copy(items = st.items + more, nextCursor = page.nextCursor, loadingMore = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update { it.copy(loadingMore = false, snackbar = errorText(e)) }
            }
        }
    }

    fun snackbarShown() = _state.update { it.copy(snackbar = null) }
}

// ── 관리자 공지 목록 (시안 4번, admin.md 4장) ─────────

/**
 * 줄을 누르면 [onEdit](고치기 화면, 목록에서 받은 값으로 채움). 여기서는 읽음 처리를 하지 않아요.
 * [message]는 쓰기·고치기·지우기 화면이 돌아오며 넘긴 스낵바 문구예요.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminNoticeListScreen(
    onBack: () -> Unit,
    onNew: () -> Unit,
    onEdit: (InboxItem.Notice) -> Unit,
    message: String?,
    onMessageShown: () -> Unit,
) {
    val repo = LocalContainer.current.repository
    val vm = viewModel { AdminNoticeListViewModel(repo) }
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    // 다른 화면에서 돌아와 다시 그려질 때마다 처음부터 받아요
    LaunchedEffect(Unit) { vm.load() }
    LaunchedEffect(state.snackbar) { state.snackbar?.let { snackbar.showSnackbar(it); vm.snackbarShown() } }
    val scope = rememberCoroutineScope()
    // 문구를 먼저 비우고 띄워요(비우면 이 효과가 다시 돌아서 scope로 띄워요)
    LaunchedEffect(message) { message?.let { onMessageShown(); scope.launch { snackbar.showSnackbar(it) } } }
    // 끝에서 3줄 안쪽까지 내려오면 다음 쪽
    LaunchedEffect(listState) {
        snapshotFlow {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= listState.layoutInfo.totalItemsCount - 3
        }.distinctUntilChanged().filter { it }.collect { vm.loadMore() }
    }

    Scaffold(
        containerColor = LologColors.Paper,
        snackbarHost = { LologSnackbarHost(snackbar) },
        topBar = {
            LologTopBar("공지사항", onBack) {
                Text(
                    "+ 새 공지", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = LologColors.Accent,
                    modifier = Modifier.clip(LologShape.pill).clickable(onClick = onNew).padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading && state.items.isEmpty() -> SkeletonList()
                state.error != null && state.items.isEmpty() ->
                    ListErrorState(onRetry = vm::retry, title = "공지를 불러오지 못했어요", body = state.error!!)
                else -> PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        Modifier.fillMaxSize(), state = listState,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (state.items.isEmpty()) {
                            // 빈 목록도 당겨서 새로고침할 수 있게 목록 안에 둬요
                            item {
                                Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("아직 쓴 공지가 없어요", fontSize = 14.sp, color = LologColors.Muted)
                                }
                            }
                        }
                        items(state.items, key = { it.id }) { n -> NoticeRow(n) { onEdit(n) } }
                        if (state.loadingMore) item { LoadMoreDots() }
                        if (state.items.isNotEmpty() && state.nextCursor == null) {
                            item {
                                Text(
                                    "누르면 고치거나 지울 수 있어요. 지우면 모두의 알림함에서 사라져요.",
                                    fontSize = 12.sp, color = LologColors.Muted, textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 흰 카드 한 줄: 작은 회색 날짜 + 굵은 제목 한 줄 + 본문 첫 줄(회색, 한 줄) */
@Composable
private fun NoticeRow(n: InboxItem.Notice, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().softCard().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(dayLabel(n.createdAt), fontSize = 12.sp, color = LologColors.Muted)
        Spacer(Modifier.height(4.dp))
        Text(n.title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = LologColors.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val first = n.body.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (first.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Text(first, fontSize = 13.sp, color = LologColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
