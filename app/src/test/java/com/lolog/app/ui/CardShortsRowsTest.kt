package com.lolog.app.ui

import com.lolog.app.data.model.VideoRef
import com.lolog.app.data.model.VideoStatus
import com.lolog.app.ui.screens.library.ShortsMark
import com.lolog.app.ui.screens.library.shortsRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 카드 자세히 "이 카드로 만든 컨텐츠"의 쇼츠 줄 문구가 상태에 맞게 나오는지 본다. */
class CardShortsRowsTest {

    @Test
    fun 만든_순서대로_n번째_쇼츠가_된다() {
        val rows = shortsRows(listOf(VideoRef(9, VideoStatus.VOICE), VideoRef(4, VideoStatus.DONE)))
        assertEquals(listOf(4L, 9L), rows.map { it.id })
        assertEquals(listOf("1번째 쇼츠", "2번째 쇼츠"), rows.map { it.title })
    }

    @Test
    fun 상태마다_작은_글과_오른쪽_표시가_정해진다() {
        val rows = shortsRows(
            listOf(
                VideoRef(1, VideoStatus.QUEUED),
                VideoRef(2, VideoStatus.VOICE),
                VideoRef(3, VideoStatus.DRAWING),
                VideoRef(4, VideoStatus.DONE),
                VideoRef(5, VideoStatus.FAILED),
            ),
        )
        assertEquals(
            listOf(
                "곧 만들기 시작해요",
                "목소리 만드는 중 · 몇 분 걸려요",
                "그림 만드는 중 · 몇 분 걸려요",
                "완료",
                "실패 · 카드에서 다시 만들 수 있어요",
            ),
            rows.map { it.sub },
        )
        assertEquals(
            listOf(ShortsMark.MAKING, ShortsMark.MAKING, ShortsMark.MAKING, ShortsMark.DONE, ShortsMark.FAILED),
            rows.map { it.mark },
        )
    }

    @Test
    fun 쇼츠가_없으면_줄도_없다() {
        assertTrue(shortsRows(emptyList()).isEmpty())
    }
}
