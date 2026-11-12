package com.lolog.app.ui.screens.comic

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.SmartDisplay
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.lolog.app.data.model.SortOrder
import com.lolog.app.data.model.VideoStatus
import com.lolog.app.data.model.VideoSummary
import com.lolog.app.data.model.inProgress
import com.lolog.app.data.repo.LologException
import com.lolog.app.data.repo.LologRepository
import com.lolog.app.ui.components.EmptyState
import com.lolog.app.ui.components.LIBRARY_NAME
import com.lolog.app.ui.components.LoadMoreDots
import com.lolog.app.ui.components.ListErrorState
import com.lolog.app.ui.components.SkeletonList
import com.lolog.app.ui.components.TypingDots
import com.lolog.app.ui.components.errorText
import com.lolog.app.ui.components.softCard
import com.lolog.app.ui.theme.LologColors
import com.lolog.app.ui.theme.LologShape
import com.lolog.app.util.shortDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/*
 * 컨텐츠 › 미디어 탭: 내 쇼츠 목록(LOLog-docs video.md 2장 GET /api/videos).
 * 4컷 탭과 같은 모양이에요: 한 편씩 옆으로 넘기고, 정렬은 최신순·오래된순, 끝 쪽에 닿으면 다음 20개.
 * 만드는 중인 게 있으면 5초마다 새로 받아요.
 */

class VideoListViewModel(private val repo: LologRepository) : ViewModel() {
    data class State(
        val videos: List<VideoSummary> = emptyList(),
        val nextCursor: String? = null,
        val loading: Boolean = true,
        val loadingMore: Boolean = false,
        val error: String? = null,
        val sort: SortOrder = SortOrder.NEWEST,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    fun refresh() = viewModelScope.launch {
        val sort = _state.value.sort
        try {
            val p = repo.videos(null, 20, sort)
            _state.update { if (it.sort != sort) it else it.copy(videos = p.videos, nextCursor = p.nextCursor, loading = false, error = null) }
        } catch (e: LologException) {
            _state.update { if (it.sort != sort) it else it.copy(loading = false, error = errorText(e)) }
        }
    }

    fun loadMore() {
        val st = _state.value
        val cursor = st.nextCursor ?: return
        if (st.loadingMore) return
        _state.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            // 다음 쪽도 처음과 같은 sort로(video.md 2장)
            runCatching { repo.videos(cursor, 20, st.sort) }
                .onSuccess { p -> _state.update { if (it.sort != st.sort) it else it.copy(videos = it.videos + p.videos, nextCursor = p.nextCursor, loadingMore = false) } }
                .onFailure { _state.update { if (it.sort != st.sort) it else it.copy(loadingMore = false) } }
        }
    }

    fun setSort(sort: SortOrder) {
        if (sort == _state.value.sort) return
        _state.update { it.copy(sort = sort, videos = emptyList(), nextCursor = null, loading = true, loadingMore = false, error = null) }
        refresh()
    }

    /** 처음 보일 때와 만드는 중인 게 있는 동안 5초마다 */
    suspend fun watch() {
        while (true) {
            refresh().join()
            val st = _state.value
            if (!st.loading && st.videos.none { it.status.inProgress }) break
            delay(5_000)
        }
    }

    /** 썸네일 주소(10분)가 만료됐으면 한 번 새로 받아요. 1분에 한 번까지 */
    private var lastImageRefresh = 0L
    fun imageFailed() {
        val now = System.currentTimeMillis()
        if (now - lastImageRefresh < 60_000) return
        lastImageRefresh = now
        refresh()
    }
}

@Composable
internal fun VideoTab(vm: VideoListViewModel, state: VideoListViewModel.State, onVideo: (Long) -> Unit, onLibrary: () -> Unit) {
    LaunchedEffect(Unit) { vm.watch() }
    val pager = key(state.sort) { rememberPagerState { state.videos.size } }
    LaunchedEffect(pager.currentPage, state.videos.size) {
        if (state.videos.isNotEmpty() && pager.currentPage >= state.videos.size - 1) vm.loadMore()
    }
    when {
        state.loading -> SkeletonList(count = 4)
        state.error != null && state.videos.isEmpty() -> ListErrorState(onRetry = { vm.refresh() })
        state.videos.isEmpty() -> EmptyState(
            icon = Icons.Outlined.SmartDisplay,
            title = "아직 쇼츠가 없어요",
            body = "카드에서 컨텐츠 만들기 › 쇼츠를 고르면 여기에 모여요",
            memo = "대사를 목소리로 읽어 줘요 ✎",
            buttonText = "$LIBRARY_NAME 가기",
            onButton = onLibrary,
            primary = false,
        )
        else -> Column(Modifier.fillMaxSize().padding(vertical = 16.dp)) {
            HorizontalPager(
                pager,
                contentPadding = PaddingValues(horizontal = 20.dp),
                pageSpacing = 12.dp,
                verticalAlignment = Alignment.Top,
            ) { page ->
                val v = state.videos[page]
                VideoPage(v, onClick = { onVideo(v.id) }, onImageError = vm::imageFailed)
            }
            Spacer(Modifier.height(14.dp))
            PageDots(state.videos.size, pager.currentPage)
            if (state.loadingMore) LoadMoreDots()
        }
    }
}

/** 한 장: 4컷 탭과 같은 흰 카드. 위는 첫 장면 그림(완성일 때) 위에 재생 표시, 아래 제목·날짜·상태 칩 */
@Composable
private fun VideoPage(v: VideoSummary, onClick: () -> Unit, onImageError: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(Modifier.fillMaxWidth().softCard(shape).clickable(onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).padding(start = 16.dp, end = 16.dp, top = 16.dp)
                .clip(RoundedCornerShape(12.dp)).background(LologColors.Canvas),
        ) {
            when {
                v.status == VideoStatus.DONE && v.thumbnailUrl != null -> {
                    AsyncImage(
                        model = v.thumbnailUrl, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(), onError = { onImageError() },
                    )
                    Box(
                        Modifier.align(Alignment.Center).size(52.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(30.dp)) }
                }
                v.status == VideoStatus.FAILED -> Text(
                    "카드에서 다시 만들 수 있어요", fontSize = 12.5.sp, color = LologColors.Muted, modifier = Modifier.align(Alignment.Center),
                )
                v.status.inProgress -> Box(Modifier.align(Alignment.Center)) { TypingDots(dot = 6.dp) }
                else -> Icon(Icons.Outlined.SmartDisplay, null, tint = LologColors.Muted, modifier = Modifier.align(Alignment.Center).size(28.dp))
            }
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.25f)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, LologColors.Paper.copy(alpha = 0.6f)))),
            )
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    v.cardSummary.ifBlank { "쇼츠" }, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = LologColors.Ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when {
                        v.status.inProgress -> "방금"
                        else -> shortDate(v.createdAt) + (v.durationMs?.let { " · ${it / 1000}초" } ?: "") +
                            if (v.cardId == null) " · 원래 카드 지워짐" else ""
                    },
                    fontSize = 11.sp, color = LologColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            VideoStateChip(v.status)
        }
    }
}

/** 상태 칩: 완성 / 만드는 중 / 실패 (4컷 탭 칩과 같은 색) */
@Composable
private fun VideoStateChip(status: VideoStatus) {
    val (label, bg, fg) = when {
        status == VideoStatus.DONE -> Triple("완성", LologColors.MemorySoft, LologColors.MemoryText)
        status == VideoStatus.FAILED -> Triple("실패", LologColors.RecSoft, LologColors.Rec)
        else -> Triple("만드는 중", LologColors.AccentSoft, LologColors.Accent)
    }
    Text(
        label, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = fg,
        modifier = Modifier.clip(LologShape.pill).background(bg).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
