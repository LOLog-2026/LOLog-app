package com.lolog.app.data

import com.lolog.app.data.mock.MockData
import com.lolog.app.data.mock.mockPage
import com.lolog.app.data.model.SortOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 목업 저장소의 정렬(LOLog-docs#37)이 명세처럼 쪽을 이어 주는지 본다. */
class MockSortTest {

    @Test
    fun 오래된_순은_첫_쪽과_다음_쪽이_id_오름차순으로_이어진다() {
        val cards = MockData.cards()
        val first = mockPage(cards, cursor = null, size = 2, sort = SortOrder.OLDEST)
        assertNotNull(first.second)
        // 다음 쪽도 같은 sort로 불러요
        val second = mockPage(cards, cursor = first.second, size = 2, sort = SortOrder.OLDEST)

        val ids = (first.first + second.first).map { it.id }
        assertEquals(cards.map { it.id }.sorted().take(4), ids)

        // 끝까지 넘기면 모든 카드가 오름차순으로 한 번씩 나오고 마지막 cursor는 null
        val all = mutableListOf<Long>()
        var cursor: String? = null
        do {
            val (page, next) = mockPage(cards, cursor, 2, SortOrder.OLDEST)
            all += page.map { it.id }
            cursor = next
        } while (cursor != null)
        assertEquals(cards.map { it.id }.sorted(), all)
        assertNull(cursor)
    }
}
