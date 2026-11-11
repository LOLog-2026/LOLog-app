package com.lolog.app.ui

import com.lolog.app.ui.components.comicCacheKey
import com.lolog.app.ui.components.comicMemoryCacheKey
import com.lolog.app.ui.components.comicThumbCacheKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** 만화 그림 캐시 이름이 주소가 아니라 만화 id로 정해지는지 본다(#35). */
class ComicCacheKeyTest {

    @Test
    fun 캐시_이름은_만화_id로_정해진다() {
        assertEquals("comic-12", comicCacheKey(12))
        assertNotEquals(comicCacheKey(12), comicCacheKey(13))
    }

    @Test
    fun 썸네일은_원본과_다른_이름을_쓴다() {
        assertEquals("comic-12-thumb", comicThumbCacheKey(12))
        assertNotEquals(comicCacheKey(12), comicThumbCacheKey(12))
    }

    @Test
    fun 메모리_캐시는_큰_그림과_작은_칸을_나눈다() {
        assertEquals("comic-12", comicMemoryCacheKey(comicCacheKey(12), small = false))
        assertEquals("comic-12-small", comicMemoryCacheKey(comicCacheKey(12), small = true))
    }
}
