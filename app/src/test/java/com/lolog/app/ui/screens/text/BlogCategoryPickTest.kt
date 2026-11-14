package com.lolog.app.ui.screens.text

import com.lolog.app.data.model.ErrorCodes
import com.lolog.app.data.repo.LologException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 블로그 카테고리 고르기가 3개까지만 되는지 본다(texts.md 4장 "카테고리 고르기", 정함 11-14). */
class BlogCategoryPickTest {

    private val form = BlogForm(options = listOf("여행", "일상", "맛집", "공부"))

    @Test
    fun 세_개까지_고르고_넷째는_안내만_한다() {
        val three = form.toggled("여행").toggled("일상").toggled("맛집")
        assertEquals(setOf("여행", "일상", "맛집"), three.picked)
        val four = three.toggled("공부")
        assertEquals(three.picked, four.picked)
        assertEquals("카테고리는 3개까지 골라요", four.customError)
        // 하나를 빼면 다시 고를 수 있고 안내는 지워져요
        val swapped = four.toggled("여행").toggled("공부")
        assertEquals(setOf("일상", "맛집", "공부"), swapped.picked)
        assertNull(swapped.customError)
    }

    @Test
    fun 세_개를_골랐으면_직접_적기도_막는다() {
        val three = form.toggled("여행").toggled("일상").toggled("맛집").copy(custom = "독서")
        val added = three.withCustom()
        assertEquals(three.picked, added.picked)
        assertEquals("카테고리는 3개까지 골라요", added.customError)
        assertEquals(setOf("여행", "독서"), form.toggled("여행").copy(custom = "독서").withCustom().picked)
    }

    @Test
    fun 서버가_카테고리_목록을_거절하면_까닭에_맞는_문구다() {
        fun text(message: String, field: String) = textErrorText(LologException(ErrorCodes.INVALID_REQUEST, message, field))
        assertEquals("카테고리는 3개까지 골라요", text("카테고리는 3개까지 골라요.", "categories"))
        assertEquals("이 블로그는 카테고리를 고를 수 없어요. 고르지 않으면 전부 가져와요", text("이 블로그는 카테고리를 고를 수 없어요.", "categories"))
        assertEquals("카테고리는 1~30자, 같은 이름은 한 번만 고를 수 있어요", text("카테고리는 1~30자예요.", "categories[1]"))
    }
}
