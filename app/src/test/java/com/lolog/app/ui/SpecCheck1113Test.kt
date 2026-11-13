package com.lolog.app.ui

import com.lolog.app.data.model.ComicPage
import com.lolog.app.data.model.ComicStatus
import com.lolog.app.data.model.ComicSummary
import com.lolog.app.data.model.ErrorCodes
import com.lolog.app.data.model.isValidPassword
import com.lolog.app.data.model.passwordFitsBytes
import com.lolog.app.data.model.passwordHasLetterAndDigit
import com.lolog.app.data.repo.LologException
import com.lolog.app.ui.components.errorText
import com.lolog.app.ui.screens.friends.FRIEND_LIMIT_TEXT
import com.lolog.app.ui.screens.rooms.MY_COMICS_MAX_PAGES
import com.lolog.app.ui.screens.rooms.doneComicsPage
import com.lolog.app.ui.social.SocialSignIn
import com.lolog.app.ui.social.socialErrorText
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 명세 점검(11/13, #102)에서 고친 오류 문구·규칙 */
class SpecCheck1113Test {

    @Test
    fun 비밀번호_규칙_줄은_버튼과_같은_기준으로_본다() {
        // 한글·다른 나라 숫자는 영문·숫자로 치지 않아요(전에는 isLetter·isDigit라 체크가 켜졌어요)
        assertFalse(passwordHasLetterAndDigit("가나다라1234"))
        assertFalse(passwordHasLetterAndDigit("abcd١٢٣٤"))
        assertTrue(passwordHasLetterAndDigit("abcd1234"))
        // 72바이트: 한글은 한 글자 3바이트
        assertTrue(passwordFitsBytes("a1" + "가".repeat(23)))
        assertFalse(passwordFitsBytes("a1" + "가".repeat(24)))
        // 규칙 줄(8~64자 · 영문과 숫자 · 72바이트)이 다 맞으면 버튼 조건(isValidPassword)도 맞아요
        for (pw in listOf("abcd1234", "가나다라1234", "a1" + "가".repeat(24), "a1" + "가".repeat(23), "abcdefgh", "Ab1")) {
            val lines = pw.length in 8..64 && passwordHasLetterAndDigit(pw) && passwordFitsBytes(pw)
            assertEquals(pw, lines, isValidPassword(pw))
        }
    }

    @Test
    fun 문구는_명세_문장_그대로다() {
        // functional-spec FR-AUTH-02 예외, FR-AUTH-06 예외
        assertEquals("로그인하지 못했어요. 다시 시도해 주세요", socialErrorText(LologException(SocialSignIn.SOCIAL_SDK_ERROR)))
        assertEquals("다른 로그인 방법을 먼저 연결해 주세요", errorText(LologException(ErrorCodes.LAST_LOGIN_METHOD)))
    }

    @Test
    fun 카드_잠김은_만화와_쇼츠_둘_다_맞는_문구다() {
        assertEquals("이 에피소드로 만화나 쇼츠를 만드는 중이에요", errorText(LologException(ErrorCodes.CARD_LOCKED)))
    }

    @Test
    fun 친구_100명_문구는_친구_추가와_받은_요청이_같다() {
        assertEquals("친구는 100명까지예요", FRIEND_LIMIT_TEXT)
    }

    private fun comic(id: Long, status: ComicStatus) = ComicSummary(id = id, cardId = id, status = status, createdAt = "2026-11-13T10:00:00+09:00")

    @Test
    fun 올릴_만화는_다음_쪽을_이어_받는다() = runBlocking {
        val pages = mapOf(
            null to ComicPage(listOf(comic(1, ComicStatus.DONE), comic(2, ComicStatus.DRAWING)), "p2"),
            "p2" to ComicPage(listOf(comic(3, ComicStatus.DONE)), null),
        )
        val (first, next) = doneComicsPage(null) { pages.getValue(it) }
        assertEquals(listOf(1L), first.map { it.id })
        assertEquals("p2", next)
        val (second, end) = doneComicsPage(next) { pages.getValue(it) }
        assertEquals(listOf(3L), second.map { it.id })
        assertNull(end)
    }

    @Test
    fun 한_쪽이_다_그리는_중이면_완성된_만화가_나올_때까지_더_받는다() = runBlocking {
        var calls = 0
        val (list, next) = doneComicsPage(null) { c ->
            calls++
            if (c == null) ComicPage(listOf(comic(1, ComicStatus.FAILED)), "p2") else ComicPage(listOf(comic(2, ComicStatus.DONE)), "p3")
        }
        assertEquals(2, calls)
        assertEquals(listOf(2L), list.map { it.id })
        assertEquals("p3", next)
        // 끝없이 비어 있으면 정한 쪽 수에서 멈추고 cursor를 남겨요(다시 내리면 이어 받아요)
        calls = 0
        val (empty, left) = doneComicsPage(null) { calls++; ComicPage(listOf(comic(9, ComicStatus.QUEUED)), "more") }
        assertEquals(MY_COMICS_MAX_PAGES, calls)
        assertTrue(empty.isEmpty())
        assertEquals("more", left)
    }
}
