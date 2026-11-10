package com.lolog.app.ui.screens.comic

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lolog.app.data.model.ComicStage
import com.lolog.app.data.model.ComicSummary
import com.lolog.app.data.model.SortOrder
import com.lolog.app.data.model.inProgress
import com.lolog.app.data.model.stage
import com.lolog.app.data.repo.LologException
import com.lolog.app.data.repo.LologRepository
import com.lolog.app.ui.components.*
import com.lolog.app.ui.navigation.LocalContainer
import com.lolog.app.ui.theme.LologColors
import com.lolog.app.ui.theme.LologShape
import com.lolog.app.util.shortDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ComicListViewModel(private val repo: LologRepository) : ViewModel() {
    data class State(
        val comics: List<ComicSummary> = emptyList(),
        val nextCursor: String? = null,
        val loading: Boolean = true,
        val loadingMore: Boolean = false,
        val error: String? = null,
        val toast: String? = null,
        val openComicId: Long? = null,
        /** 앱을 다시 켜면 최신순으로 돌아가요 */
        val sort: SortOrder = SortOrder.NEWEST,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /** GET /api/comics?size=20(&sort=oldest) */
    fun refresh() = viewModelScope.launch {
        val sort = _state.value.sort
        try {
            val p = repo.comics(null, 20, sort)
            // 받는 사이에 정렬을 바꿨으면 버려요
            _state.update { if (it.sort != sort) it else it.copy(comics = p.comics, nextCursor = p.nextCursor, loading = false, error = null) }
        } catch (e: LologException) {
            _state.update { if (it.sort != sort) it else it.copy(loading = false, error = errorText(e)) }
        }
    }

    fun loadMore() {
        val st = _state.value
        val cursor = st.nextCursor ?: return
        if (st.loadingMore) return // 중복 요청 막기
        _state.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            // 다음 쪽도 처음과 같은 sort로
            runCatching { repo.comics(cursor, 20, st.sort) }
                .onSuccess { p -> _state.update { if (it.sort != st.sort) it else it.copy(comics = it.comics + p.comics, nextCursor = p.nextCursor, loadingMore = false) } }
                .onFailure { _state.update { if (it.sort != st.sort) it else it.copy(loadingMore = false) } }
        }
    }

    /** 정렬을 바꾸면 cursor·목록을 비우고 처음부터 다시 불러요 (명세) */
    fun setSort(sort: SortOrder) {
        if (sort == _state.value.sort) return
        _state.update { it.copy(sort = sort, comics = emptyList(), nextCursor = null, loading = true, loadingMore = false, error = null) }
        refresh()
    }

    /** 실패한 만화 "다시 시도" = 같은 카드로 POST /api/cards/{id}/comics 다시 */
    fun retry(c: ComicSummary) = viewModelScope.launch {
        try {
            val res = repo.requestComic(c.cardId)
            _state.update { it.copy(openComicId = res.comicId) }
        } catch (e: LologException) {
            _state.update { it.copy(toast = errorText(e)) }
        }
    }

    fun consumed() = _state.update { it.copy(toast = null, openComicId = null) }

    /** 썸네일을 못 받으면(주소 10분 만료) 목록을 한 번 새로 받아요. 1분에 한 번까지 */
    private var lastImageRefresh = 0L
    fun imageFailed() {
        val now = System.currentTimeMillis()
        if (now - lastImageRefresh < 60_000) return
        lastImageRefresh = now
        refresh()
    }
}

// ── 15 내 만화 목록 (보관함·만화 시안 ②, 공용 ⑥) ──

@Composable
fun ComicListScreen(onComic: (Long) -> Unit, onBack: () -> Unit, onLibrary: () -> Unit = {}) {
    val repo = LocalContainer.current.repository
    val vm = viewModel { ComicListViewModel(repo) }
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        // 그리는 중인 게 있으면 가끔 새로 고침
        while (true) {
            vm.refresh().join()
            // 정렬을 바꿔 다시 받는 중이면 아직 멈추지 않아요
            val st = vm.state.value
            if (!st.loading && st.comics.none { it.status.inProgress }) break
            delay(5_000)
        }
    }
    LaunchedEffect(state.openComicId) { state.openComicId?.let { vm.consumed(); onComic(it) } }
    LaunchedEffect(state.toast) { state.toast?.let { vm.consumed(); snackbar.showSnackbar(it) } }
    // 휴대폰 뒤로 가기도 ←와 같게 홈 탭으로
    BackHandler(onBack = onBack)

    // 정렬을 바꾸면 맨 위부터 보여 줘요
    val grid = key(state.sort) { rememberLazyGridState() }
    val last by remember { derivedStateOf { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 } }
    LaunchedEffect(last, state.comics.size) { if (last >= state.comics.size - 4) vm.loadMore() }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // 위 칸: 흰 바탕 제목 줄
            ListHeader {
                LologTopBar("내 만화", onBack = onBack, actions = { SortMenu(state.sort, vm::setSort) })
            }
            // 아래 칸: 연한 회색, 노트 줄 없음
            Column(Modifier.fillMaxWidth().weight(1f).background(LologColors.Canvas)) {
                when {
                    state.loading -> SkeletonList(count = 4)
                    state.error != null && state.comics.isEmpty() -> ListErrorState(onRetry = { vm.refresh() })
                    state.comics.isEmpty() -> EmptyState(
                        icon = Icons.Outlined.GridView,
                        title = "아직 만화가 없어요",
                        body = "${LIBRARY_NAME}에서 카드를 골라 4컷만화로 만들어 보세요",
                        memo = "카드 하나면 충분해요 ✎",
                        buttonText = "$LIBRARY_NAME 가기",
                        onButton = onLibrary,
                        primary = false,
                    )
                    // 고른 정렬로 20개씩 (cursor)
                    else -> LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        state = grid,
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(state.comics, key = { it.id }) { c -> ComicTile(c, onClick = { onComic(c.id) }, onRetry = { vm.retry(c) }, onImageError = vm::imageFailed) }
                        when {
                            state.loadingMore -> item(span = { GridItemSpan(2) }) { LoadMoreDots() }
                            state.nextCursor == null -> item(span = { GridItemSpan(2) }) { ListEnd() }
                        }
                    }
                }
            }
        }
        LologSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }
}

/** 썸네일 칸: 위에 1컷 썸네일 + 상태 배지, 아래 카드 제목과 만든 날짜. 실패는 이유 없이 "다시 시도"만 */
@Composable
private fun ComicTile(c: ComicSummary, onClick: () -> Unit, onRetry: () -> Unit, onImageError: () -> Unit) {
    val stage = c.status.stage
    Column(Modifier.softCard(LologShape.sm).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(LologColors.Canvas)) {
            when (stage) {
                ComicStage.DONE -> ComicImage(c.thumbnailUrl ?: c.imageUrl, emptyList(), Modifier.fillMaxSize(), small = true, onLoadError = onImageError)
                ComicStage.FAILED -> if (!c.cardDeleted) Box(
                    Modifier.align(Alignment.Center).clip(LologShape.pill).border(1.dp, LologColors.Rec, LologShape.pill)
                        .clickable(onClick = onRetry).padding(horizontal = 14.dp, vertical = 7.dp),
                ) { Text("다시 시도", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = LologColors.Rec) }
                else -> Box(Modifier.align(Alignment.Center)) { TypingDots(dot = 6.dp) }
            }
            StageBadge(stage, Modifier.align(Alignment.TopStart).padding(8.dp))
        }
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                c.cardSummary ?: "4컷만화", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                when {
                    stage == ComicStage.WAITING || stage == ComicStage.MAKING -> "방금"
                    stage == ComicStage.FAILED -> listOf(shortDate(c.createdAt), "다시 시도해 주세요").filter { it.isNotBlank() }.joinToString(" · ")
                    else -> shortDate(c.createdAt) + if (c.cardDeleted) " · 원래 카드 지워짐" else ""
                },
                fontSize = 11.sp, color = LologColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
