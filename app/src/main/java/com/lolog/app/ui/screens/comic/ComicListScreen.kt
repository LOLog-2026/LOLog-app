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
        val loading: Boolean = true,
        /** 당겨서 새로 고침 중 */
        val refreshing: Boolean = false,
        val error: String? = null,
        val toast: String? = null,
        val openComicId: Long? = null,
        /** 앱을 다시 켜면 최신순으로 돌아가요 */
        val sort: SortOrder = SortOrder.NEWEST,
        /** 보고 있는 쪽(1부터). 쇼츠 탭과 따로 기억해요 */
        val page: Int = 1,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /**
     * GET /api/comics?size=50(&sort=oldest)를 nextCursor가 없을 때까지 받아 앱에서 쪽을 나눠요.
     * 서버에 page·totalCount 생기면 그 쪽만 받도록 바꿀 것(임시)
     */
    fun refresh(pull: Boolean = false) = viewModelScope.launch {
        val sort = _state.value.sort
        if (pull) _state.update { it.copy(refreshing = true) }
        try {
            val comics = fetchAll { cursor -> repo.comics(cursor, CONTENT_FETCH_SIZE, sort).let { it.comics to it.nextCursor } }
            // 받는 사이에 정렬을 바꿨으면 버려요. 편 수가 줄었으면 마지막 쪽으로
            _state.update {
                if (it.sort != sort) it
                else it.copy(comics = comics, page = clampPage(it.page, comics.size), loading = false, refreshing = false, error = null)
            }
        } catch (e: LologException) {
            // 받아 둔 목록이 있으면 그대로 두고 띠로만 알려요
            _state.update {
                if (it.sort != sort) it
                else it.copy(loading = false, refreshing = false, error = errorText(e), toast = if (pull && it.comics.isNotEmpty()) errorText(e) else it.toast)
            }
        }
    }

    /** 정렬을 바꾸면 목록을 비우고 1쪽부터 다시 받아요 (명세) */
    fun setSort(sort: SortOrder) {
        if (sort == _state.value.sort) return
        _state.update { it.copy(sort = sort, comics = emptyList(), page = 1, loading = true, refreshing = false, error = null) }
        refresh()
    }

    fun setPage(page: Int) = _state.update { it.copy(page = clampPage(page, it.comics.size)) }

    /**
     * 보이는 쪽에 그리는 중인 만화가 있으면 5초마다 GET /api/comics/{id}로 확인해요.
     * 끝난 게 있으면 목록을 다시 받아 썸네일까지 맞춰요. 보이는 쪽에 그리는 중이 없으면 멈춰요
     */
    suspend fun watchVisible() {
        while (true) {
            val st = _state.value
            val ids = pageSlice(st.comics, st.page).filter { it.status.inProgress }.map { it.id }
            if (st.loading || ids.isEmpty()) return
            delay(5_000)
            val done = ids.any { id -> runCatching { !repo.comic(id).status.inProgress }.getOrDefault(false) }
            if (done) refresh().join()
        }
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
fun ComicListScreen(
    onComic: (Long) -> Unit,
    onBack: () -> Unit,
    onLibrary: () -> Unit = {},
    onVideo: (Long) -> Unit = {},
    /** true면 위 탭을 "4컷"으로 열어요(홈 4컷만화 칸) */
    openFourCut: Boolean = false,
    onFourCutOpened: () -> Unit = {},
) {
    val repo = LocalContainer.current.repository
    val vm = viewModel { ComicListViewModel(repo) }
    val state by vm.state.collectAsState()
    // 미디어 탭(쇼츠) 목록. 탭을 오가도 목록·정렬을 지키려고 화면에 하나 둬요
    val videoVm = viewModel { VideoListViewModel(repo) }
    val videoState by videoVm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.refresh() }
    // 보이는 쪽에 그리는 중인 만화가 바뀔 때마다 다시 지켜봐요
    val watching = pageSlice(state.comics, state.page).filter { it.status.inProgress }.map { it.id }
    LaunchedEffect(watching, state.loading) { vm.watchVisible() }
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
    // 위 탭: 0 = 쇼츠, 1 = 4컷. 처음엔 쇼츠
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // 홈 "컨텐츠" 숫자 칸에서 오면 4컷 탭 1쪽
    LaunchedEffect(openFourCut) { if (openFourCut) { tab = 1; vm.setPage(1); onFourCutOpened() } }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // 위 칸: 흰 바탕 제목 줄 + "쇼츠 / 4컷" 탭(아래 1dp 선은 탭이 그려요). 맨 위에 고정, 아래만 넘어가요
            Column(Modifier.fillMaxWidth().background(LologColors.Paper)) {
                // 정렬은 탭마다 따로(4컷·쇼츠 목록 모두 latest·oldest)
                LologTopBar("컨텐츠", onBack = onBack, actions = {
                    if (tab == 0) SortMenu(videoState.sort, videoVm::setSort) else SortMenu(state.sort, vm::setSort)
                })
                ContentTabs(listOf("쇼츠", "4컷"), tab, { tab = it })
            }
            // 아래 칸: 연한 회색, 노트 줄 없음
            Column(Modifier.fillMaxWidth().weight(1f).background(LologColors.Canvas)) {
                when {
                    // 쇼츠 탭: 내 쇼츠(video.md 2장)
                    tab == 0 -> VideoTab(videoVm, videoState, onVideo, onLibrary)
                    state.loading -> SkeletonList(count = 4)
                    state.error != null && state.comics.isEmpty() -> ListErrorState(onRetry = { vm.refresh() })
                    state.comics.isEmpty() -> EmptyState(
                        icon = Icons.Outlined.GridView,
                        title = "아직 만화가 없어요",
                        body = "${LIBRARY_NAME}를 하나 골라 4컷만화로 만들어 보세요",
                        memo = "에피소드 하나면 충분해요 ✎",
                        buttonText = "$LIBRARY_NAME 가기",
                        onButton = onLibrary,
                        primary = false,
                    )
                    // 2열 격자 + 아래 숫자 쪽 줄(11/13 시안)
                    else -> ContentGrid(
                        items = state.comics, page = state.page, onPage = vm::setPage,
                        refreshing = state.refreshing, onRefresh = { vm.refresh(pull = true) },
                        key = { it.id },
                    ) { c ->
                        ComicCell(c, onClick = { onComic(c.id) }, onRetry = { vm.retry(c) }, onImageError = vm::imageFailed)
                    }
                }
            }
        }
        // 아래 쪽 줄을 가리지 않게 조금 띄워요
        LologSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 64.dp))
    }
}

/** 격자 칸 하나: 흰 둥근 카드(14dp). 위 그림(아래로 흐려짐), 아래 제목 1줄, 날짜 + 상태 칩. 실패면 그림 자리에 "다시 시도" */
@Composable
private fun ComicCell(c: ComicSummary, onClick: () -> Unit, onRetry: () -> Unit, onImageError: () -> Unit) {
    val stage = c.status.stage
    val shape = RoundedCornerShape(14.dp)
    Column(Modifier.fillMaxWidth().softCard(shape).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).padding(start = 8.dp, end = 8.dp, top = 8.dp)) {
            when (stage) {
                ComicStage.DONE -> ComicImage(
                    c.thumbnailUrl ?: c.imageUrl, emptyList(), Modifier.fillMaxSize(), small = true, onLoadError = onImageError,
                    // thumbnailUrl은 원본과 다른 그림이라 캐시 이름을 따로 둬요
                    cacheKey = if (c.thumbnailUrl != null) comicThumbCacheKey(c.id) else comicCacheKey(c.id),
                )
                ComicStage.FAILED -> if (!c.cardDeleted) Box(
                    Modifier.align(Alignment.Center).clip(LologShape.pill).border(1.dp, LologColors.Rec, LologShape.pill)
                        .clickable(onClick = onRetry).padding(horizontal = 12.dp, vertical = 6.dp),
                ) { Text("다시 시도", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = LologColors.Rec) }
                else -> Box(Modifier.align(Alignment.Center)) { TypingDots(dot = 5.dp) }
            }
            // 아래쪽 흰색으로 흐려짐
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.35f)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, LologColors.Paper))),
            )
        }
        Column(Modifier.padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 10.dp)) {
            Text(c.cardSummary ?: "4컷만화", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = LologColors.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        stage == ComicStage.WAITING || stage == ComicStage.MAKING -> "방금"
                        else -> shortDate(c.createdAt) + if (c.cardDeleted) " · 원래 에피소드 지워짐" else ""
                    },
                    fontSize = 10.5.sp, color = LologColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(4.dp))
                StateChip(stage)
            }
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
        label, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = fg, maxLines = 1,
        modifier = Modifier.clip(LologShape.pill).background(bg).padding(horizontal = 8.dp, vertical = 3.dp),
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
