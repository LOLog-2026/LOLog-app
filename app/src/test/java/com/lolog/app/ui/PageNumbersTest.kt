package com.lolog.app.ui

import com.lolog.app.ui.components.PAGE_NUMBER_SLOTS
import com.lolog.app.ui.components.pageNumbers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 쪽 넘기기 줄에 보여 줄 숫자(null = "…")가 시안 규칙대로 나오는지 본다. */
class PageNumbersTest {

    private val dots: Int? = null

    @Test
    fun 한_쪽이면_1만() {
        assertEquals(listOf(1), pageNumbers(1, 1))
    }

    @Test
    fun 일곱_쪽_이하면_다_보여_준다() {
        assertEquals(listOf(1, 2, 3, 4), pageNumbers(4, 1))
        assertEquals(listOf(1, 2, 3, 4), pageNumbers(4, 4))
        for (p in listOf(1, 4, 5, 6, 7)) assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), pageNumbers(7, p))
    }

    @Test
    fun 여덟_쪽() {
        val front = listOf(1, 2, 3, 4, 5, dots, 8)
        val back = listOf(1, dots, 4, 5, 6, 7, 8)
        assertEquals(front, pageNumbers(8, 1))
        assertEquals(front, pageNumbers(8, 4))
        // 5쪽 = N-3 이라 뒤쪽 모양
        assertEquals(back, pageNumbers(8, 5))
        assertEquals(back, pageNumbers(8, 6))
        assertEquals(back, pageNumbers(8, 8))
    }

    @Test
    fun 열두_쪽() {
        val front = listOf(1, 2, 3, 4, 5, dots, 12)
        val back = listOf(1, dots, 8, 9, 10, 11, 12)
        assertEquals(front, pageNumbers(12, 1))
        assertEquals(front, pageNumbers(12, 4))
        assertEquals(listOf(1, dots, 4, 5, 6, dots, 12), pageNumbers(12, 5))
        assertEquals(listOf(1, dots, 5, 6, 7, dots, 12), pageNumbers(12, 6))
        assertEquals(back, pageNumbers(12, 9))
        assertEquals(back, pageNumbers(12, 12))
    }

    @Test
    fun 숫자_칸은_늘_일곱_개_이하이고_지금_쪽과_끝_쪽이_들어_있다() {
        for (total in 1..30) for (p in 1..total) {
            val cells = pageNumbers(total, p)
            assertTrue(cells.size <= PAGE_NUMBER_SLOTS)
            assertTrue(p in cells)
            assertTrue(1 in cells && total in cells)
        }
    }
}
