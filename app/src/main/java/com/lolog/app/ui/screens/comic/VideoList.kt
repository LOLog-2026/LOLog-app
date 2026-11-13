package com.lolog.app.ui.screens.comic

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.SmartDisplay
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
 * 4컷 탭과 같은 모양이에요: 2열 격자 + 아래 숫자 쪽 줄(한 쪽 6편), 정렬은 최신순·오래된순.
 * 서버에 page·totalCount가 없어 cursor로 끝까지 받아 쪽을 나눠요(임시, ContentPaging.kt).
 * 보이는 쪽에 만드는 중인 게 있으면 5초마다 확인해요.
 */

class VideoListViewModel(private val repo: LologRepository) : ViewModel() {
    data class State(
        val videos: List<VideoSummary> = emptyList(),
        val loading: Boolean = true,
        /** 당겨서 새로 고침 중 */
        val refreshing: Boolean = false,
        val error: String? = null,
        val toast: String? = null,
        val sort: SortOrder = SortOrder.NEWEST,
        /** 보고 있는 쪽(1부터). 4컷 탭과 따로 기억해요 */
        val page: Int = 1,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /**
     * GET /api/videos?size=50(&sort=oldest)를 nextCursor가 없을 때까지 받아 앱에서 쪽을 나눠요.
     * 서버에 page·totalCount 생기면 그 쪽만 받도록 바꿀 것(임시)
     */
    fun refresh(pull: Boolean = false) = viewModelScope.launch {
        val sort = _state.value.sort
        if (pull) _state.update { it.copy(refreshing = true) }
        try {
            val videos = fetchAll { cursor -> repo.videos(cursor, CONTENT_FETCH_SIZE, sort).let { it.videos to it.nextCursor } }
            _state.update {
                if (it.sort != sort) it
                else it.copy(videos = videos, page = clampPage(it.page, videos.size), loading = false, refreshing = false, error = null)
            }
        } catch (e: LologException) {
            // 받아 둔 목록이 있으면 그대로 두고 띠로만 알려요
            _state.update {
                if (it.sort != sort) it
                else it.copy(loading = false, refreshing = false, error = errorText(e), toast = if (pull && it.videos.isNotEmpty()) errorText(e) else it.toast)
            }
        }
    }

    /** 정렬을 바꾸면 목록을 비우고 1쪽부터 다시 받아요 */
    fun setSort(sort: SortOrder) {
        if (sort == _state.value.sort) return
        _state.update { it.copy(sort = sort, videos = emptyList(), page = 1, loading = true, refreshing = false, error = null) }
        refresh()
    }

    fun setPage(page: Int) = _state.update { it.copy(page = clampPage(page, it.videos.size)) }

    fun toastShown() = _state.update { it.copy(toast = null) }

    /**
     * 보이는 쪽에 만드는 중인 쇼츠가 있으면 5초마다 GET /api/videos/{id}로 확인해요.
     * 끝난 게 있으면 목록을 다시 받아요. 보이는 쪽에 만드는 중이 없으면 멈춰요
     */
    suspend fun watchVisible() {
        while (true) {
            val st = _state.value
            val ids = pageSlice(st.videos, st.page).filter { it.status.inProgress }.map { it.id }
            if (st.loading || ids.isEmpty()) return
            delay(5_000)
            val done = ids.any { id -> runCatching { !repo.video(id).status.inProgress }.getOrDefault(false) }
            if (done) refresh().join()
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
    LaunchedEffect(Unit) { vm.refresh() }
    // 보이는 쪽에 만드는 중인 쇼츠가 바뀔 때마다 다시 지켜봐요
    val watching = pageSlice(state.videos, state.page).filter { it.status.inProgress }.map { it.id }
    LaunchedEffect(watching, state.loading) { vm.watchVisible() }
    when {
        state.loading -> SkeletonList(count = 4)
        state.error != null && state.videos.isEmpty() -> ListErrorState(onRetry = { vm.refresh() })
        state.videos.isEmpty() -> EmptyState(
            icon = Icons.Outlined.SmartDisplay,
            title = "아직 쇼츠가 없어요",
            body = "에피소드에서 컨텐츠 만들기 › 쇼츠를 고르면 여기에 모여요",
            memo = "대사를 목소리로 읽어 줘요 ✎",
            buttonText = "$LIBRARY_NAME 가기",
            onButton = onLibrary,
            primary = false,
        )
        // 2열 격자 + 아래 숫자 쪽 줄(11/13 시안 6·7번)
        else -> ContentGrid(
            items = state.videos, page = state.page, onPage = vm::setPage,
            refreshing = state.refreshing, onRefresh = { vm.refresh(pull = true) },
            key = { it.id },
        ) { v ->
            VideoCell(v, onClick = { onVideo(v.id) }, onImageError = vm::imageFailed)
        }
    }
}

/** 영상 길이 "0:28" */
internal fun durationText(ms: Long): String {
    val sec = ms / 1000
    return "${sec / 60}:${(sec % 60).toString().padStart(2, '0')}"
}

/**
 * 격자 칸 하나: 흰 둥근 카드(14dp). 위는 세로로 긴 썸네일 + 가운데 ▶ 동그라미 + 오른쪽 아래 길이,
 * 아래 제목 1줄, 날짜 + 상태 칩
 */
@Composable
private fun VideoCell(v: VideoSummary, onClick: () -> Unit, onImageError: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(Modifier.fillMaxWidth().softCard(shape).clickable(onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 6.dp).aspectRatio(9f / 13f)
                .clip(RoundedCornerShape(10.dp)).background(LologColors.Canvas),
        ) {
            when {
                v.status == VideoStatus.DONE && v.thumbnailUrl != null -> {
                    AsyncImage(
                        model = v.thumbnailUrl, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(), onError = { onImageError() },
                    )
                    Box(
                        Modifier.align(Alignment.Center).size(38.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(22.dp)) }
                }
                v.status == VideoStatus.FAILED -> Text(
                    "에피소드에서 다시 만들 수 있어요", fontSize = 11.sp, color = LologColors.Muted, textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 10.dp),
                )
                v.status.inProgress -> Box(Modifier.align(Alignment.Center)) { TypingDots(dot = 5.dp) }
                else -> Icon(Icons.Outlined.SmartDisplay, null, tint = LologColors.Muted, modifier = Modifier.align(Alignment.Center).size(26.dp))
            }
            // 길이는 있을 때만 오른쪽 아래 검은 알약
            if (v.status == VideoStatus.DONE) v.durationMs?.let { ms ->
                Text(
                    durationText(ms), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(LologShape.pill)
                        .background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Column(Modifier.padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 10.dp)) {
            Text(
                v.cardSummary.ifBlank { "쇼츠" }, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = LologColors.Ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        v.status.inProgress -> "방금"
                        else -> shortDate(v.createdAt) + if (v.cardId == null) " · 원래 에피소드 지워짐" else ""
                    },
                    fontSize = 10.5.sp, color = LologColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(4.dp))
                VideoStateChip(v.status)
            }
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
        label, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = fg, maxLines = 1,
        modifier = Modifier.clip(LologShape.pill).background(bg).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
