package com.lolog.app.ui

import com.lolog.app.ui.screens.library.sameSite
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 블로그 카드의 블로그 이름을 찾을 때 글 링크와 연결한 블로그 주소를 맞게 비교하는지 본다. */
class BlogSiteMatchTest {

    @Test
    fun 주소_앞부분이_같으면_같은_블로그다() {
        assertTrue(sameSite("https://blog.naver.com/minji_trip/223", "blog.naver.com/minji_trip"))
        assertTrue(sameSite("https://m.blog.naver.com/minji_trip/223", "https://blog.naver.com/minji_trip/"))
        assertTrue(sameSite("https://minji.tistory.com/12", "minji.tistory.com"))
    }

    @Test
    fun 아이디가_다르면_다른_블로그다() {
        assertFalse(sameSite("https://blog.naver.com/minji_trip2/223", "blog.naver.com/minji_trip"))
        assertFalse(sameSite("https://blog.naver.com/other/1", "blog.naver.com/minji_trip"))
        assertFalse(sameSite("https://blog.naver.com/x", ""))
    }
}
