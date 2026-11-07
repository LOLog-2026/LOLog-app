package com.lolog.app.ui.screens.library

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
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /** GET /api/cards?size=20 — 처음부터 다시 */
    fun refresh() = viewModelScope.launch {
        _state.update { it.copy(loading = it.cards.isEmpty(), error = null) }
        try {
            val page = repo.cards(null, 20)
            _state.update { it.copy(cards = page.cards, nextCursor = page.nextCursor, loading = false) }
        } catch (e: LologException) {
            _state.update { it.copy(loading = false, error = e.message.ifBlank { "카드를 불러오지 못했어요" }) }
        }
    }

    /** 아래로 내리면 nextCursor로 20개씩 더 */
    fun loadMore() {
        val s = _state.value
        val cursor = s.nextCursor ?: return
        if (s.loadingMore) return
        _state.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            runCatching { repo.cards(cursor, 20) }
                .onSuccess { p -> _state.update { it.copy(cards = it.cards + p.cards, nextCursor = p.nextCursor, loadingMore = false) } }
                .onFailure { _state.update { it.copy(loadingMore = false) } }
        }
    }

    fun setFilter(kind: CardKind?) = _state.update { it.copy(filter = kind) }
}

// ── 07 보관함 · 17 빈 보관함 (공용 부품 ①④⑤, 보관함·만화 ①) ──

@Composable
fun LibraryScreen(onCard: (Long) -> Unit, onRecord: () -> Unit) {
    val repo = LocalContainer.current.repository
    val vm = viewModel { LibraryViewModel(repo) }
    val state by vm.state.collectAsState()
    LaunchedEffect(Unit) { vm.refresh() }

    Column(Modifier.fillMaxSize().noteLines()) {
        LologTopBar("보관함")
        // 종류는 웃김 · 황당 · 추억 3개로 고정 (카드 결정사항 ①)
        if (state.cards.isNotEmpty()) {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterPill("전체", state.filter == null) { vm.setFilter(null) }
                listOf(CardKind.FUNNY, CardKind.ABSURD, CardKind.MEMORY).forEach { k ->
                    FilterPill(k.label, state.filter == k) { vm.setFilter(k) }
                }
            }
        }
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
