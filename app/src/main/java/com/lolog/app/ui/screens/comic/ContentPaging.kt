package com.lolog.app.ui.screens.comic

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lolog.app.ui.components.PageNumbers
import com.lolog.app.ui.theme.LologColors

/*
 * 컨텐츠 › 쇼츠·4컷 목록의 쪽 나누기(11/13 시안 1~7번).
 * 서버에 page·totalCount 생기면 그 쪽만 받도록 바꿀 것(임시). 지금은 cursor로 끝까지 받아 앱이 쪽을 나눠요.
 */

/** 한 쪽 = 2열 × 3줄 */
const val CONTENT_PAGE_SIZE = 6

/** 끝까지 받을 때 한 번에 받는 수 */
internal const val CONTENT_FETCH_SIZE = 50

/** 전체 쪽 수. 0편이면 0쪽 */
internal fun pageCount(total: Int, size: Int = CONTENT_PAGE_SIZE): Int = (total + size - 1) / size

/** 쪽 번호를 1 ~ 마지막 쪽 안으로. 0편이어도 1쪽 */
internal fun clampPage(page: Int, total: Int): Int = page.coerceIn(1, pageCount(total).coerceAtLeast(1))

/** [page]쪽(1부터)에 들어갈 편들 */
internal fun <T> pageSlice(items: List<T>, page: Int, size: Int = CONTENT_PAGE_SIZE): List<T> {
    val from = ((page - 1) * size).coerceIn(0, items.size)
    return items.subList(from, (from + size).coerceAtMost(items.size))
}

/** 목록 맨 위 작은 글: "40편 · 1쪽" / "70편 · 6쪽 · 31~36번째" / "70편 · 12쪽 · 67~70번째 · 마지막" */
internal fun pageSummary(total: Int, page: Int, size: Int = CONTENT_PAGE_SIZE): String {
    if (page <= 1) return "${total}편 · 1쪽"
    val from = (page - 1) * size + 1
    val to = (page * size).coerceAtMost(total)
    val last = if (page >= pageCount(total, size)) " · 마지막" else ""
    return "${total}편 · ${page}쪽 · $from~${to}번째$last"
}

/**
 * cursor로 nextCursor가 null일 때까지 받아 하나로 이어요. 중간에 실패하면 그 오류를 그대로 던져요.
 * 서버가 같은 cursor를 또 주면 끝없이 돌지 않게 멈춰요
 */
internal suspend fun <T> fetchAll(fetch: suspend (cursor: String?) -> Pair<List<T>, String?>): List<T> {
    val all = mutableListOf<T>()
    var cursor: String? = null
    do {
        val (items, next) = fetch(cursor)
        all += items
        if (next == cursor) break
        cursor = next
    } while (cursor != null)
    return all
}

/**
 * 2열 격자(간격 10dp, 바깥 12dp) + 아래 숫자 쪽 줄. 맨 위에 "40편 · 1쪽" 줄.
 * 쪽을 바꾸면 맨 위로 올려요. 쪽 줄은 목록 밖이라 스크롤해도 아래 탭 바로 위에 붙어 있어요
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> ContentGrid(
    items: List<T>,
    page: Int,
    onPage: (Int) -> Unit,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    key: (T) -> Any,
    cell: @Composable (T) -> Unit,
) {
    val grid = rememberLazyGridState()
    LaunchedEffect(page) { grid.scrollToItem(0) }
    Column(Modifier.fillMaxSize()) {
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxWidth().weight(1f)) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                state = grid,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "summary", span = { GridItemSpan(maxLineSpan) }) {
                    Text(pageSummary(items.size, page), fontSize = 11.sp, color = LologColors.Muted, modifier = Modifier.padding(start = 2.dp))
                }
                items(pageSlice(items, page), key = key) { cell(it) }
            }
        }
        PageNumbers(total = pageCount(items.size), current = page, onPage = onPage)
    }
}
