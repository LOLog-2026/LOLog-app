package com.lolog.app.ui.screens.comic

import com.lolog.app.data.model.BadgeKind
import com.lolog.app.data.model.PushType
import com.lolog.app.ui.badges.OnPush
import com.lolog.app.ui.badges.SeenBadge
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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

// ── 15 컨텐츠(내 만화) 목록 (보관함·만화 시안 ②, 공용 ⑥) ──

@Composable
fun ComicListScreen(onComic: (Long) -> Unit, onBack: () -> Unit, onLibrary: () -> Unit = {}, onVideo: (Long) -> Unit = {}) {
    val repo = LocalContainer.current.repository
    val vm = viewModel { ComicListViewModel(repo) }
    val state by vm.state.collectAsState()
    // 미디어 탭(쇼츠) 목록. 탭을 오가도 목록·정렬을 지키려고 화면에 하나 둬요
    val videoVm = viewModel { VideoListViewModel(repo) }
    val videoState by videoVm.state.collectAsState()
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
    // 컨텐츠 새 소식 점 끄기, 만화·쇼츠가 끝났다는 푸시가 오면 두 목록을 새로 받기(inbox.md 4장, #67 7번)
    SeenBadge(BadgeKind.COMICS)
    OnPush(PushType.COMIC_DONE, PushType.COMIC_FAILED, PushType.VIDEO_DONE, PushType.VIDEO_FAILED) {
        vm.refresh()
        videoVm.refresh()
    }
    LaunchedEffect(state.openComicId) { state.openComicId?.let { vm.consumed(); onComic(it) } }
    LaunchedEffect(state.toast) { state.toast?.let { vm.consumed(); snackbar.showSnackbar(it) } }
    // 휴대폰 뒤로 가기도 ←와 같게 홈 탭으로
    BackHandler(onBack = onBack)
    // 위 탭: 0 = 4컷, 1 = 미디어(쇼츠). 처음엔 4컷
    var tab by rememberSaveable { mutableIntStateOf(0) }

    // 정렬을 바꾸면 첫 편부터 보여 줘요
    val pager = key(state.sort) { rememberPagerState { state.comics.size } }
    // 끝 쪽에 닿으면 다음 20개 (정렬·cursor 그대로)
    LaunchedEffect(pager.currentPage, state.comics.size) {
        if (state.comics.isNotEmpty() && pager.currentPage >= state.comics.size - 1) vm.loadMore()
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // 위 칸: 흰 바탕 제목 줄 + "4컷 / 미디어" 탭(아래 1dp 선은 탭이 그려요). 맨 위에 고정, 아래만 넘어가요
            Column(Modifier.fillMaxWidth().background(LologColors.Paper)) {
                // 정렬은 탭마다 따로(4컷·쇼츠 목록 모두 latest·oldest)
                LologTopBar("컨텐츠", onBack = onBack, actions = {
                    if (tab == 0) SortMenu(state.sort, vm::setSort) else SortMenu(videoState.sort, videoVm::setSort)
                })
                ContentTabs(listOf("4컷", "미디어"), tab, { tab = it })
            }
            // 아래 칸: 연한 회색, 노트 줄 없음
            Column(Modifier.fillMaxWidth().weight(1f).background(LologColors.Canvas)) {
                when {
                    // 미디어 탭: 내 쇼츠(video.md 2장)
                    tab == 1 -> VideoTab(videoVm, videoState, onVideo, onLibrary)
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
                    // 한 편씩 옆으로 넘기기(11/10 확정)
                    else -> Column(Modifier.fillMaxSize().padding(vertical = 16.dp)) {
                        HorizontalPager(
                            pager,
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            pageSpacing = 12.dp,
                            verticalAlignment = Alignment.Top,
                        ) { page ->
                            val c = state.comics[page]
                            ComicPage(c, onClick = { onComic(c.id) }, onRetry = { vm.retry(c) }, onImageError = vm::imageFailed)
                        }
                        Spacer(Modifier.height(14.dp))
                        PageDots(state.comics.size, pager.currentPage)
                        if (state.loadingMore) LoadMoreDots()
                    }
                }
            }
        }
        LologSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }
}

/** 한 장: 흰 카드(둥근 18dp, 그림자), 위에 그림 위쪽 부분(아래로 갈수록 흰색으로 흐려짐), 아래 제목·날짜·상태 칩 */
@Composable
private fun ComicPage(c: ComicSummary, onClick: () -> Unit, onRetry: () -> Unit, onImageError: () -> Unit) {
    val stage = c.status.stage
    val shape = RoundedCornerShape(18.dp)
    Column(Modifier.fillMaxWidth().softCard(shape).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
            when (stage) {
                ComicStage.DONE -> ComicImage(
                    c.thumbnailUrl ?: c.imageUrl, emptyList(), Modifier.fillMaxSize(), small = true, onLoadError = onImageError,
                    // thumbnailUrl은 원본과 다른 그림이라 캐시 이름을 따로 둬요
                    cacheKey = if (c.thumbnailUrl != null) comicThumbCacheKey(c.id) else comicCacheKey(c.id),
                )
                ComicStage.FAILED -> if (!c.cardDeleted) Box(
                    Modifier.align(Alignment.Center).clip(LologShape.pill).border(1.dp, LologColors.Rec, LologShape.pill)
                        .clickable(onClick = onRetry).padding(horizontal = 14.dp, vertical = 7.dp),
                ) { Text("다시 시도", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = LologColors.Rec) }
                else -> Box(Modifier.align(Alignment.Center)) { TypingDots(dot = 6.dp) }
            }
            // 아래쪽 흰색으로 흐려짐
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.35f)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, LologColors.Paper))),
            )
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(c.cardSummary ?: "4컷만화", fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = LologColors.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        stage == ComicStage.WAITING || stage == ComicStage.MAKING -> "방금"
                        else -> shortDate(c.createdAt) + if (c.cardDeleted) " · 원래 카드 지워짐" else ""
                    },
                    fontSize = 11.sp, color = LologColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            StateChip(stage)
        }
    }
}

/** 상태 칩: 완성 / 그리는 중 / 실패 */
@Composable
private fun StateChip(stage: ComicStage) {
    val (label, bg, fg) = when (stage) {
        ComicStage.DONE -> Triple("완성", LologColors.MemorySoft, LologColors.MemoryText)
        ComicStage.FAILED -> Triple("실패", LologColors.RecSoft, LologColors.Rec)
        else -> Triple("그리는 중", LologColors.AccentSoft, LologColors.Accent)
    }
    Text(
        label, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = fg,
        modifier = Modifier.clip(LologShape.pill).background(bg).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** 쪽 표시 점: 지금 쪽은 코랄 긴 막대. 많으면 지금 쪽 주변 7개만 */
@Composable
internal fun PageDots(count: Int, current: Int) {
    val window = 7
    val start = (current - window / 2).coerceIn(0, (count - window).coerceAtLeast(0))
    val end = (start + window).coerceAtMost(count)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        for (i in start until end) {
            val on = i == current
            Box(
                Modifier.padding(horizontal = 3.dp).height(6.dp).width(if (on) 20.dp else 6.dp)
                    .clip(LologShape.pill).background(if (on) LologColors.Accent else LologColors.Line),
            )
        }
    }
}
