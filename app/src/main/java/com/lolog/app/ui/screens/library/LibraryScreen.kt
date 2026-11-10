package com.lolog.app.ui.screens.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lolog.app.data.model.CardKind
import com.lolog.app.data.model.CardSummary
import com.lolog.app.data.model.SortOrder
import com.lolog.app.data.repo.LologException
import com.lolog.app.data.repo.LologRepository
import com.lolog.app.ui.components.*
import com.lolog.app.ui.navigation.LocalContainer
import com.lolog.app.ui.theme.LologColors
import com.lolog.app.util.EpisodeSeen
import com.lolog.app.util.dayWithWeek
import com.lolog.app.util.localDay
import com.lolog.app.ui.theme.LologShape
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class LibraryViewModel(private val repo: LologRepository) : ViewModel() {
    data class State(
        val cards: List<CardSummary> = emptyList(),
        val nextCursor: String? = null,
        val loading: Boolean = true,
        val loadingMore: Boolean = false,
        val error: String? = null,
        val filter: CardKind? = null,
        /** 앱을 다시 켜면 최신순으로 돌아가요 */
        val sort: SortOrder = SortOrder.NEWEST,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /** GET /api/cards?size=20(&sort=oldest) — 처음부터 다시 */
    fun refresh() = viewModelScope.launch {
        val sort = _state.value.sort
        _state.update { it.copy(loading = it.cards.isEmpty(), error = null) }
        try {
            val page = repo.cards(null, 20, sort)
            // 받는 사이에 정렬을 바꿨으면 버려요
            _state.update { if (it.sort != sort) it else it.copy(cards = page.cards, nextCursor = page.nextCursor, loading = false) }
        } catch (e: LologException) {
            _state.update { if (it.sort != sort) it else it.copy(loading = false, error = errorText(e)) }
        }
    }

    /** "더보기"를 누르면 nextCursor로 20개씩 더 (처음과 같은 sort) */
    fun loadMore() {
        val s = _state.value
        val cursor = s.nextCursor ?: return
        if (s.loadingMore) return
        _state.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            runCatching { repo.cards(cursor, 20, s.sort) }
                .onSuccess { p -> _state.update { if (it.sort != s.sort) it else it.copy(cards = it.cards + p.cards, nextCursor = p.nextCursor, loadingMore = false) } }
                .onFailure { _state.update { if (it.sort != s.sort) it else it.copy(loadingMore = false) } }
        }
    }

    fun setFilter(kind: CardKind?) = _state.update { it.copy(filter = kind) }

    /** 정렬을 바꾸면 cursor·목록을 비우고 처음부터 다시 불러요 (명세) */
    fun setSort(sort: SortOrder) {
        if (sort == _state.value.sort) return
        _state.update { it.copy(sort = sort, cards = emptyList(), nextCursor = null, loading = true, loadingMore = false, error = null) }
        refresh()
    }
}

// ── 07 보관함 · 17 빈 보관함 (공용 부품 ①④⑤, 보관함·만화 ①) ──

@Composable
fun LibraryScreen(onCard: (Long) -> Unit, onRecord: () -> Unit, onBack: () -> Unit) {
    val repo = LocalContainer.current.repository
    val vm = viewModel { LibraryViewModel(repo) }
    val state by vm.state.collectAsState()
    LaunchedEffect(Unit) { vm.refresh() }
    // "새로!" 기준: 이번에 열기 전, 마지막으로 에피소드 탭을 연 시간. 여는 지금 시간을 저장해요
    val context = LocalContext.current
    val seenBefore = remember { EpisodeSeen.open(context) }
    // 휴대폰 뒤로 가기도 ←와 같게 홈 탭으로
    BackHandler(onBack = onBack)

    Column(Modifier.fillMaxSize()) {
        // 위 칸: 흰 바탕 제목 줄 + 종류 칩 줄. 목록 밖에 두어 맨 위에 고정돼요
        ListHeader {
            LologTopBar(LIBRARY_NAME, onBack = onBack, actions = { SortMenu(state.sort, vm::setSort) })
            // 종류는 웃김 · 황당 · 추억 3개로 고정 (카드 결정사항 ①). 정렬을 바꾸는 중에도 칩 줄은 그대로 둬요
            if (state.cards.isNotEmpty() || state.loading) {
                Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KindFilterPill("전체", null, state.filter == null) { vm.setFilter(null) }
                    listOf(CardKind.FUNNY, CardKind.ABSURD, CardKind.MEMORY).forEach { k ->
                        KindFilterPill(k.label, k, state.filter == k) { vm.setFilter(k) }
                    }
                }
            }
        }
        // 아래 칸: 연한 회색, 노트 줄 없음
        Column(Modifier.fillMaxWidth().weight(1f).background(LologColors.Canvas)) {
            when {
                state.loading -> SkeletonList()
                state.error != null && state.cards.isEmpty() -> ListErrorState(onRetry = { vm.refresh() })
                state.cards.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.Style,
                    title = "아직 카드가 없어요",
                    body = "대화를 녹음하면 재밌는 이야기를 카드로 모아 줘요",
                    memo = "오늘 수다부터 녹음해 볼까요?",
                    buttonText = "녹음하러 가기",
                    onButton = onRecord,
                )
                else -> CardList(state, vm, seenBefore, onCard)
            }
        }
    }
}

/**
 * 녹음 날짜(recordedAt, 없으면 createdAt)별 묶음 + 2열 카드.
 * 다음 20개는 맨 아래 "더보기"를 눌렀을 때만 불러요(11/10 확정: 자동 불러오기 뺌).
 */
@Composable
private fun CardList(state: LibraryViewModel.State, vm: LibraryViewModel, seenBefore: Long?, onCard: (Long) -> Unit) {
    val shown = state.cards.filter { state.filter == null || it.kind == state.filter }
    // 받은 순서(정렬)를 지키면서 날짜별로 묶어요
    val groups = remember(shown) { shown.groupBy { localDay(it.recordedAt ?: it.createdAt) }.toList() }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        groups.forEach { (day, cards) ->
            item(key = "day-$day") {
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.Bottom) {
                    Text(day?.let { dayWithWeek(it) } ?: "날짜 없음", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = LologColors.Ink)
                    Spacer(Modifier.width(6.dp))
                    Text("카드 ${cards.size}개", fontSize = 11.sp, color = LologColors.Muted)
                }
            }
            items(cards.chunked(2), key = { row -> row.first().id }) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { card ->
                        GridCard(card, EpisodeSeen.isNewSince(card.createdAt, seenBefore), { onCard(card.id) }, Modifier.weight(1f))
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        when {
            state.loadingMore -> item { LoadMoreDots() }
            state.nextCursor != null -> item {
                Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) { MoreButton(vm::loadMore) }
            }
            shown.isNotEmpty() -> item { ListEnd() }
        }
    }
}

/** 2열 카드 한 장: 종류 연한 색 바탕, 흰 종류 칩, 제목 2줄, 맨 아래 "대사 n · 만화 n" */
@Composable
private fun GridCard(card: CardSummary, isNew: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(16.dp)
    Box(modifier) {
        Column(
            Modifier.fillMaxWidth().height(112.dp).clip(shape).background(card.kind.soft())
                .then(if (isNew) Modifier.border(2.dp, LologColors.Accent, shape) else Modifier)
                .clickable(onClick = onClick)
                .padding(12.dp),
        ) {
            KindChip(card.kind, onCard = true)
            Spacer(Modifier.height(8.dp))
            Text(card.summary, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.ExtraBold, color = LologColors.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.weight(1f))
            val meta = listOfNotNull(
                card.lineCount?.let { "대사 $it" },
                card.comicCount.takeIf { it > 0 }?.let { "만화 $it" },
            ).joinToString(" · ")
            if (meta.isNotEmpty()) Text(meta, fontSize = 10.5.sp, color = card.kind.text())
        }
        if (isNew) PenMemo("새로!", Modifier.align(Alignment.TopEnd).offset(x = (-8).dp, y = (-8).dp), angle = 3f)
    }
}

/** 흰 알약 "더보기 ⌄" (높이 34, Line 테두리) */
@Composable
private fun MoreButton(onClick: () -> Unit) {
    Row(
        Modifier.height(34.dp).clip(LologShape.pill).background(LologColors.Paper)
            .border(1.dp, LologColors.Line, LologShape.pill).clickable(onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("더보기", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = LologColors.Ink)
        Icon(Icons.Filled.KeyboardArrowDown, null, tint = LologColors.Ink, modifier = Modifier.size(18.dp))
    }
}
