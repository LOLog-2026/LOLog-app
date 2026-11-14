package com.lolog.app.ui

import com.lolog.app.data.model.ComicPage
import com.lolog.app.data.model.ComicStatus
import com.lolog.app.data.model.ComicSummary
import com.lolog.app.data.model.SortOrder
import com.lolog.app.data.model.VideoPage
import com.lolog.app.data.model.VideoStatus
import com.lolog.app.data.model.VideoSummary
import com.lolog.app.data.repo.LologRepository
import com.lolog.app.ui.screens.comic.CONTENT_PAGE_SIZE
import com.lolog.app.ui.screens.comic.ComicListViewModel
import com.lolog.app.ui.screens.comic.VideoListViewModel
import com.lolog.app.ui.screens.comic.durationText
import com.lolog.app.ui.screens.comic.clampPage
import com.lolog.app.ui.screens.comic.fetchAll
import com.lolog.app.ui.screens.comic.pageCount
import com.lolog.app.ui.screens.comic.pageSlice
import com.lolog.app.ui.screens.comic.pageSummary
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 컨텐츠(4컷·쇼츠) 목록 쪽 나누기: 한 쪽 6편, 끝까지 받기, 정렬 바꾸면 1쪽. */
@OptIn(ExperimentalCoroutinesApi::class)
class ContentPagesTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun 사십_편이면_일곱_쪽이고_일곱째_쪽은_네_편() {
        val items = (1..40).toList()
        assertEquals(6, CONTENT_PAGE_SIZE)
        assertEquals(7, pageCount(40))
        assertEquals(4, pageSlice(items, 7).size)
        assertEquals((37..40).toList(), pageSlice(items, 7))
        assertEquals((1..6).toList(), pageSlice(items, 1))
    }

    @Test
    fun 영_편이면_쪽이_없고_1쪽으로_둔다() {
        assertEquals(0, pageCount(0))
        assertTrue(pageSlice(emptyList<Int>(), 1).isEmpty())
        assertEquals(1, clampPage(3, 0))
    }

    @Test
    fun 편_수가_줄면_마지막_쪽으로() {
        assertEquals(2, clampPage(7, 12))
        assertEquals(1, clampPage(0, 12))
    }

    @Test
    fun 맨_위_글() {
        assertEquals("40편 · 1쪽", pageSummary(40, 1))
        assertEquals("70편 · 6쪽 · 31~36번째", pageSummary(70, 6))
        assertEquals("70편 · 12쪽 · 67~70번째 · 마지막", pageSummary(70, 12))
    }

    @Test
    fun cursor가_null일_때까지_이어_받는다() = runTest {
        val cursors = mutableListOf<String?>()
        val all = fetchAll { cursor ->
            cursors += cursor
            when (cursor) {
                null -> listOf(1, 2) to "a"
                "a" -> listOf(3) to "b"
                else -> listOf(4) to null
            }
        }
        assertEquals(listOf(1, 2, 3, 4), all)
        assertEquals(listOf(null, "a", "b"), cursors)
    }

    @Test
    fun 같은_cursor를_또_주면_멈춘다() = runTest {
        var calls = 0
        val all = fetchAll { _ -> calls++; listOf(calls) to "same" }
        assertEquals(listOf(1, 2), all)
    }

    @Test
    fun 영_편이면_빈_화면_조건이_된다() = runTest {
        val vm = ComicListViewModel(repo(0))
        vm.refresh()
        advanceUntilIdle()
        val st = vm.state.value
        assertFalse(st.loading)
        assertNull(st.error)
        assertTrue(st.comics.isEmpty())
        assertEquals(1, st.page)
    }

    @Test
    fun 끝까지_받아_한_목록으로_만든다() = runTest {
        val sizes = mutableListOf<Int>()
        val vm = ComicListViewModel(repo(120, sizes))
        vm.refresh()
        advanceUntilIdle()
        assertEquals(120, vm.state.value.comics.size)
        // size=50으로 세 번(50 + 50 + 20)
        assertEquals(listOf(50, 50, 50), sizes)
    }

    @Test
    fun 정렬을_바꾸면_1쪽으로() = runTest {
        val vm = ComicListViewModel(repo(40))
        vm.refresh()
        advanceUntilIdle()
        vm.setPage(3)
        assertEquals(3, vm.state.value.page)
        vm.setSort(SortOrder.OLDEST)
        assertEquals(1, vm.state.value.page)
        advanceUntilIdle()
        assertEquals(1, vm.state.value.page)
        assertEquals(40, vm.state.value.comics.size)
    }

    @Test
    fun 없는_쪽으로는_가지_않는다() = runTest {
        val vm = ComicListViewModel(repo(40))
        vm.refresh()
        advanceUntilIdle()
        vm.setPage(99)
        assertEquals(7, vm.state.value.page)
    }

    @Test
    fun 쇼츠도_정렬을_바꾸면_1쪽으로() = runTest {
        val vm = VideoListViewModel(repo(18))
        vm.refresh()
        advanceUntilIdle()
        assertEquals(18, vm.state.value.videos.size)
        vm.setPage(2)
        assertEquals(2, vm.state.value.page)
        vm.setSort(SortOrder.OLDEST)
        advanceUntilIdle()
        assertEquals(1, vm.state.value.page)
    }

    @Test
    fun 쇼츠_길이() {
        assertEquals("0:28", durationText(28_400))
        assertEquals("1:05", durationText(65_000))
    }

    /** comics()·videos()만 답하는 가짜 저장소. 다른 함수는 부르지 않아요 */
    private fun repo(total: Int, sizes: MutableList<Int> = mutableListOf()): LologRepository {
        val unused = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(LologRepository::class.java)) { _, m, _ ->
            throw UnsupportedOperationException(m.name)
        } as LologRepository
        val all = (1..total).map { ComicSummary(it.toLong(), it.toLong(), ComicStatus.DONE, createdAt = "2026-11-13T00:00:00Z") }
        return object : LologRepository by unused {
            override suspend fun comics(cursor: String?, size: Int, sort: SortOrder): ComicPage {
                sizes += size
                val from = cursor?.toInt() ?: 0
                val to = (from + size).coerceAtMost(all.size)
                return ComicPage(all.subList(from, to), if (to < all.size) "$to" else null)
            }

            override suspend fun videos(cursor: String?, size: Int, sort: SortOrder): VideoPage {
                val videos = (1..total).map { VideoSummary(it.toLong(), it.toLong(), "쇼츠 $it", VideoStatus.DONE, createdAt = "2026-11-13T00:00:00Z") }
                val from = cursor?.toInt() ?: 0
                val to = (from + size).coerceAtMost(videos.size)
                return VideoPage(videos.subList(from, to), if (to < videos.size) "$to" else null)
            }
        }
    }
}
