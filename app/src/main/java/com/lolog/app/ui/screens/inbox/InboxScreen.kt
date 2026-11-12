package com.lolog.app.ui.screens.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lolog.app.data.model.ErrorCodes
import com.lolog.app.data.model.InboxItem
import com.lolog.app.data.model.InboxTab
import com.lolog.app.data.model.PushPayload
import com.lolog.app.data.repo.LologException
import com.lolog.app.data.repo.LologRepository
import com.lolog.app.notification.Notifier
import com.lolog.app.ui.components.*
import com.lolog.app.ui.navigation.LocalContainer
import com.lolog.app.ui.theme.LologColors
import com.lolog.app.ui.theme.LologShape
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class InboxViewModel(private val repo: LologRepository) : ViewModel() {
    data class State(
        val tab: InboxTab = InboxTab.ALL,
        val items: List<InboxItem> = emptyList(),
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

    /** 첫 쪽·다음 쪽 받기. 탭을 바꾸거나 새로고침하면 하던 것을 멈춰요(늦게 온 다른 탭 목록이 섞이지 않게) */
    private var listJob: Job? = null
    private var started = false

    fun start() {
        if (started) return
        started = true
        firstPage()
    }

    fun select(tab: InboxTab) {
        if (tab == _state.value.tab) return
        _state.update { State(tab = tab) }
        firstPage()
    }

    /** 당겨서 새로고침 */
    fun refresh() {
        _state.update { it.copy(refreshing = true) }
        firstPage()
    }

    fun retry() {
        _state.update { it.copy(loading = true, error = null) }
        firstPage()
    }

    /** [silent]면 실패해도 지금 목록을 그대로 두고 아무것도 띄우지 않아요 */
    private fun firstPage(silent: Boolean = false) {
        listJob?.cancel()
        val tab = _state.value.tab
        listJob = viewModelScope.launch {
            try {
                val page = repo.inbox(tab)
                _state.update {
                    it.copy(items = page.items, nextCursor = page.nextCursor, loading = false, refreshing = false, loadingMore = false, error = null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update {
                    when {
                        silent -> it
                        it.items.isEmpty() -> it.copy(loading = false, refreshing = false, error = errorText(e))
                        else -> it.copy(loading = false, refreshing = false, snackbar = errorText(e))
                    }
                }
            }
        }
    }

    /** 아래로 내리면 nextCursor로 다음 20개 */
    fun loadMore() {
        val s = _state.value
        val cursor = s.nextCursor ?: return
        if (s.loading || s.refreshing || s.loadingMore) return
        _state.update { it.copy(loadingMore = true) }
        listJob = viewModelScope.launch {
            try {
                val page = repo.inbox(s.tab, cursor)
                _state.update { st ->
                    val have = st.items.map { it.kind to it.id }.toSet()
                    st.copy(items = st.items + page.items.filterNot { (it.kind to it.id) in have }, nextCursor = page.nextCursor, loadingMore = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update { it.copy(loadingMore = false, snackbar = errorText(e)) }
            }
        }
    }

    /**
     * 줄을 누르면 화면에서 바로 읽음으로 바꾸고 POST .../read. 이동은 결과를 기다리지 않아요(실패해도 그대로 이동).
     * 없어진 줄(404)이면 목록을 새로 받고 조용히 넘어가요.
     */
    fun open(item: InboxItem) {
        if (item.read) return
        _state.update { s -> s.copy(items = s.items.map { if (it.kind == item.kind && it.id == item.id) it.asRead() else it }) }
        viewModelScope.launch {
            try {
                repo.readInbox(item.kind, item.id)
            } catch (e: LologException) {
                if (e.code == ErrorCodes.NOTICE_NOT_FOUND || e.code == ErrorCodes.NOTIFICATION_NOT_FOUND) firstPage(silent = true)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
            }
        }
    }

    /** "모두 읽음": POST /api/inbox/read가 되면 모든 줄을 읽음으로 */
    fun readAll() = viewModelScope.launch {
        try {
            repo.readAllInbox()
            _state.update { s -> s.copy(items = s.items.map { it.asRead() }) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            _state.update { it.copy(snackbar = errorText(e)) }
        }
    }

    fun snackbarShown() = _state.update { it.copy(snackbar = null) }
}

private val TAB_LABELS = listOf(InboxTab.ALL to "전체", InboxTab.NOTICE to "공지사항", InboxTab.NOTIFICATION to "알림")

// 표시 색(시안 7·9번): 공지 파랑, 카드 노랑, 만화·캐릭터 코랄, 친구 초록, 그 밖 회색
private fun InboxBadge.colors(): Pair<Color, Color> = when (this) {
    InboxBadge.NOTICE -> Color(0xFFE9EEFD) to LologColors.Pen
    InboxBadge.CARDS -> LologColors.LaughSoft to Color(0xFFB7700B)
    InboxBadge.COMIC, InboxBadge.CHARACTER -> LologColors.RecSoft to LologColors.Accent
    InboxBadge.FRIEND -> LologColors.MemorySoft to LologColors.MemoryText
    InboxBadge.ROOM -> Color(0xFFF0EBFD) to Color(0xFF7A5AF8)
    InboxBadge.OTHER -> LologColors.Canvas to LologColors.Muted
}

// ── 알림함 (시안 7·9번) ─────────────────────────────

/**
 * 공지사항과 알림을 최근 것부터 섞어서 보여 줘요. 줄을 누르면 읽음이 되고
 * 공지는 [onNotice](공지사항 자세히), 알림은 [onPush](푸시를 눌렀을 때와 같은 곳)로 가요.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    onBack: () -> Unit,
    onNotice: (InboxItem.Notice) -> Unit,
    onPush: (PushPayload) -> Unit,
    /** 방 알림: 방 목록이나 그 방 대화 탭 */
    onRoom: (RoomTarget) -> Unit = {},
) {
    val repo = LocalContainer.current.repository
    val vm = viewModel { InboxViewModel(repo) }
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) { vm.start() }
    LaunchedEffect(state.snackbar) { state.snackbar?.let { snackbar.showSnackbar(it); vm.snackbarShown() } }
    // 끝에서 3줄 안쪽까지 내려오면 다음 쪽
    LaunchedEffect(listState) {
        snapshotFlow {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= listState.layoutInfo.totalItemsCount - 3
        }.distinctUntilChanged().filter { it }.collect { vm.loadMore() }
    }
    // 탭을 바꾸면 맨 위부터
    LaunchedEffect(state.tab) { listState.scrollToItem(0) }

    Scaffold(
        containerColor = LologColors.Paper,
        snackbarHost = { LologSnackbarHost(snackbar) },
        topBar = { LologTopBar("알림함", onBack) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TAB_LABELS.forEach { (tab, label) -> FilterPill(label, state.tab == tab) { vm.select(tab) } }
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.clip(LologShape.pill).border(1.dp, LologColors.Line, LologShape.pill)
                        .clickable { vm.readAll() }.height(32.dp).padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("모두 읽음", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = LologColors.Accent) }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.loading && state.items.isEmpty() -> SkeletonList()
                    state.error != null && state.items.isEmpty() ->
                        ListErrorState(onRetry = vm::retry, title = "알림함을 불러오지 못했어요", body = state.error!!)
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
                                        Text("아직 받은 소식이 없어요", fontSize = 14.sp, color = LologColors.Muted)
                                    }
                                }
                            }
                            items(state.items, key = { "${it.kind}-${it.id}" }) { item ->
                                InboxRow(item) {
                                    vm.open(item)
                                    when (item) {
                                        is InboxItem.Notice -> onNotice(item)
                                        // 방 알림은 ui에서 갈 곳을 정해요. 모르는 type이면 payloadOf가 null: 이동 없이 읽음만
                                        is InboxItem.Notification ->
                                            item.roomTarget()?.let(onRoom) ?: Notifier.payloadOf(item.data)?.let(onPush)
                                    }
                                }
                            }
                            if (state.loadingMore) item { LoadMoreDots() }
                            if (state.tab == InboxTab.NOTIFICATION && state.items.isNotEmpty() && state.nextCursor == null) {
                                item {
                                    Text(
                                        "30일 지난 알림은 지워져요.", fontSize = 12.sp, color = LologColors.Muted,
                                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 한 줄: 왼쪽 표시 + 굵은 제목 + 작은 글 한 줄 + 오른쪽 시간, 안 읽었으면 코랄 점. 읽은 줄은 제목이 보통 굵기·회색 */
@Composable
private fun InboxRow(item: InboxItem, onClick: () -> Unit) {
    val badge = item.badge()
    val (bg, fg) = badge.colors()
    val line = item.line()
    Row(
        Modifier.fillMaxWidth().clip(LologShape.sm).background(Color(0xFFF7F8FA)).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(9.dp)).background(bg), contentAlignment = Alignment.Center) {
            Text(badge.label, fontSize = if (badge.label.length > 2) 10.sp else 11.sp, fontWeight = FontWeight.Bold, color = fg, maxLines = 1)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                line.title, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (item.read) FontWeight.Normal else FontWeight.Bold,
                color = if (item.read) LologColors.Muted else LologColors.Ink,
            )
            if (line.sub.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(line.sub, fontSize = 12.sp, color = LologColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(inboxTime(item.createdAt), fontSize = 11.sp, color = LologColors.Muted)
            Spacer(Modifier.height(6.dp))
            Box(Modifier.size(6.dp).clip(CircleShape).background(if (item.read) Color.Transparent else LologColors.Accent))
        }
    }
}
