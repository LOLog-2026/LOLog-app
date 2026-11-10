package com.lolog.app.ui.screens.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import com.lolog.app.util.isNew
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

    /** 아래로 내리면 nextCursor로 20개씩 더 (처음과 같은 sort) */
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
    // 휴대폰 뒤로 가기도 ←와 같게 홈 탭으로
    BackHandler(onBack = onBack)

    Column(Modifier.fillMaxSize()) {
        // 위 칸: 흰 바탕 제목 줄 + 종류 칩 줄
        ListHeader {
            LologTopBar(LIBRARY_NAME, onBack = onBack, actions = { SortMenu(state.sort, vm::setSort) })
            // 종류는 웃김 · 황당 · 추억 3개로 고정 (카드 결정사항 ①). 정렬을 바꾸는 중에도 칩 줄은 그대로 둬요
            if (state.cards.isNotEmpty() || state.loading) {
                Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterPill("전체", state.filter == null) { vm.setFilter(null) }
                    listOf(CardKind.FUNNY, CardKind.ABSURD, CardKind.MEMORY).forEach { k ->
                        FilterPill(k.label, state.filter == k) { vm.setFilter(k) }
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
                else -> CardList(state, vm, onCard)
            }
        }
    }
}

@Composable
private fun CardList(state: LibraryViewModel.State, vm: LibraryViewModel, onCard: (Long) -> Unit) {
    val listState = rememberLazyListState()
    val shown = state.cards.filter { state.filter == null || it.kind == state.filter }
    // 끝에서 3개 남으면 다음 20개 (cursor)
    val nearEnd by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 } }
    LaunchedEffect(nearEnd, shown.size) { if (nearEnd >= shown.size - 3) vm.loadMore() }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(shown, key = { it.id }) { card ->
            CardItem(card, isNew = isNew(card.createdAt), onClick = { onCard(card.id) })
        }
        when {
            state.loadingMore -> item { LoadMoreDots() }
            state.nextCursor == null && shown.isNotEmpty() -> item { ListEnd() }
        }
    }
}
