package com.lolog.app.data

import com.lolog.app.data.mock.MockTexts
import com.lolog.app.data.model.CardDetail
import com.lolog.app.data.model.CardSource
import com.lolog.app.data.model.ErrorCodes
import com.lolog.app.data.model.TEXT_BODY_MAX
import com.lolog.app.data.model.TextInput
import com.lolog.app.data.model.TextStatus
import com.lolog.app.data.repo.LologException
import com.lolog.app.data.model.BlogPlatform
import com.lolog.app.data.model.blogPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 글로 카드 만들기 목업(MockTexts, texts.md)이 서버와 같은 규칙대로 도는지 본다. */
class MockTextsTest {

    private var now = 1_000_000L
    private var id = 100L
    private val cards = mutableListOf<CardDetail>()
    private val texts = MockTexts(clock = { now }, newId = { id++ }, addCard = { cards.add(0, it) })

    private fun error(block: () -> Unit): LologException? = try {
        block(); null
    } catch (e: LologException) {
        e
    }

    private fun input(title: String = "동생이랑 마트 간 날", body: String = "오늘 동생이랑 마트에 갔다", place: String? = "동네 마트") =
        TextInput(title, body, place, situation = "주말 저녁")

    @Test
    fun 제목이나_본문이_비면_거절한다() {
        val noTitle = error { texts.create(input(title = "")) }
        assertEquals(ErrorCodes.INVALID_REQUEST, noTitle?.code)
        assertEquals("title", noTitle?.field)
        val noBody = error { texts.create(input(body = "")) }
        assertEquals(ErrorCodes.INVALID_REQUEST, noBody?.code)
        assertEquals("body", noBody?.field)
    }

    @Test
    fun 공백만_있는_제목_본문도_거절한다() {
        assertEquals("title", error { texts.create(input(title = "   ")) }?.field)
        assertEquals("body", error { texts.create(input(body = " \n\t ")) }?.field)
    }

    @Test
    fun 본문이_상한을_넘으면_TEXT_TOO_LONG() {
        assertNull(error { texts.create(input(body = "가".repeat(TEXT_BODY_MAX))) })
        val e = error { texts.create(input(body = "가".repeat(TEXT_BODY_MAX + 1))) }
        assertEquals(ErrorCodes.TEXT_TOO_LONG, e?.code)
        assertEquals("body", e?.field)
    }

    @Test
    fun 시간이_지나면_QUEUED가_DONE이_되고_글_카드가_한_장_생긴다() {
        val textId = texts.create(input())
        assertEquals(TextStatus.QUEUED, texts.get(textId).status)

        now += MockTexts.MAKE_MS - 1
        assertEquals(emptyList<Long>(), texts.settle())
        assertEquals(TextStatus.QUEUED, texts.get(textId).status)

        now += 1
        assertEquals(listOf(textId), texts.settle())
        val done = texts.get(textId)
        assertEquals(TextStatus.DONE, done.status)
        assertEquals(1, cards.size)
        val card = cards.single()
        assertEquals(card.id, done.cardId)
        assertEquals(CardSource.WRITTEN, card.source)
        assertEquals(textId, card.textId)
        assertEquals("동네 마트", card.place)
        assertEquals(listOf("화자1", "화자2"), card.speakers.map { it.key })
        assertEquals(3, card.lines.size)
        // 원문은 남아요(직접 쓴 글)
        assertEquals("오늘 동생이랑 마트에 갔다", done.body)

        // 다시 정리해도 카드가 또 생기지 않아요
        now += 10_000
        assertEquals(emptyList<Long>(), texts.settle())
        assertEquals(1, cards.size)
    }

    @Test
    fun 본문에_실패가_있으면_FAILED이고_카드가_없다() {
        val textId = texts.create(input(body = "이건 실패 시험"))
        now += MockTexts.MAKE_MS
        texts.settle()
        val t = texts.get(textId)
        assertEquals(TextStatus.FAILED, t.status)
        assertNull(t.cardId)
        assertTrue(cards.isEmpty())
    }

    @Test
    fun 블로그_확인은_네이버_티스토리만_찾는다() {
        val naver = texts.check("blog.naver.com/minji_trip")
        assertEquals("https://rss.blog.naver.com/minji_trip.xml", naver.feedUrl)
        assertEquals(MockTexts.MOCK_CATEGORIES, naver.categories)
        assertEquals("https://minji.tistory.com/rss", texts.check("https://minji.tistory.com").feedUrl)
        assertEquals(ErrorCodes.INVALID_BLOG, error { texts.check("https://example.com/blog") }?.code)
    }

    @Test
    fun 내부_주소는_블로그_확인에서_거절한다() {
        listOf("http://localhost:8080/naver", "http://127.0.0.1/tistory", "http://192.168.0.5/blog.naver.com").forEach { url ->
            assertEquals(url, ErrorCodes.INVALID_BLOG, error { texts.check(url) }?.code)
        }
    }

    @Test
    fun 블로그를_연결하면_가져온_글_4개와_블로그_카드_2장이_생긴다() {
        val blog = texts.add("blog.naver.com/minji_trip", listOf("여행", "일상"))
        assertEquals(listOf(blog), texts.list())
        val posts = texts.posts(blog.id, null)
        assertEquals(4, posts.posts.size)
        assertNull(posts.nextCursor)
        assertEquals(2, posts.posts.count { it.status == TextStatus.DONE })
        assertEquals(1, posts.posts.count { it.status == TextStatus.QUEUED })
        assertEquals(1, posts.posts.count { it.status == TextStatus.FAILED })

        assertEquals(2, cards.size)
        assertTrue(cards.all { it.source == CardSource.BLOG && it.textId != null })
        assertEquals(1, cards.count { it.truncated })
        // 블로그 글 원문은 본문 없이 링크만
        val text = texts.get(cards.first().textId!!)
        assertNull(text.body)
        assertTrue(text.link!!.startsWith("https://blog.naver.com/minji_trip/"))

        // 카테고리 바꾸기, 연결 끊기(카드는 남음)
        assertEquals(listOf("맛집"), texts.update(blog.id, listOf("맛집")).categories)
        texts.delete(blog.id)
        assertTrue(texts.list().isEmpty())
        assertEquals(2, cards.size)
    }

    @Test
    fun 같은_블로그를_또_연결하면_BLOG_EXISTS() {
        texts.add("blog.naver.com/minji_trip", emptyList())
        assertEquals(ErrorCodes.BLOG_EXISTS, error { texts.add("https://m.blog.naver.com/minji_trip", emptyList()) }?.code)
    }

    @Test
    fun 카테고리는_대소문자만_달라도_같은_이름이다() {
        val e = error { texts.add("blog.naver.com/minji_trip", listOf("Daily", "daily")) }
        assertEquals(ErrorCodes.INVALID_REQUEST, e?.code)
        assertEquals("categories[1]", e?.field)
        assertEquals("categories[0]", error { texts.add("blog.naver.com/minji_trip", listOf("가".repeat(31))) }?.field)
        assertEquals(listOf("여행"), texts.add("blog.naver.com/minji_trip", listOf(" 여행 ")).categories)
    }

    @Test
    fun 카드를_지우면_글도_지우고_가져온_글은_카드_지움이_된다() {
        val blog = texts.add("blog.naver.com/minji_trip", emptyList())
        val card = cards.first()
        texts.cardDeleted(card.id)
        assertEquals(ErrorCodes.TEXT_NOT_FOUND, error { texts.get(card.textId!!) }?.code)
        val post = texts.posts(blog.id, null).posts.single { it.title == card.summary }
        assertNull(post.cardId)
        assertNull(post.status)
    }

    @Test
    fun 블로그_글_원문에는_블로그_이름이_있다() {
        texts.add("blog.naver.com/minji_trip", emptyList())
        assertEquals("민지의 여행일기", texts.get(cards.first().textId!!).blogTitle)
    }

    @Test
    fun 플랫폼_표시는_주소로_정한다() {
        assertEquals(BlogPlatform.NAVER, blogPlatform("blog.naver.com/minji"))
        assertEquals(BlogPlatform.NAVER, blogPlatform("https://m.blog.naver.com/minji/1"))
        assertEquals(BlogPlatform.TISTORY, blogPlatform("https://minji.tistory.com/rss"))
        assertEquals(BlogPlatform.OTHER, blogPlatform("https://example.com/naver.com"))
        assertEquals(BlogPlatform.VELOG, blogPlatform("velog.io/@minji/posts"))
        assertEquals(BlogPlatform.MEDIUM, blogPlatform("https://medium.com/@minji"))
        assertEquals(BlogPlatform.MEDIUM, blogPlatform("https://minji.medium.com/"))
        assertEquals(BlogPlatform.WORDPRESS, blogPlatform("https://minji.wordpress.com/2026/11/01/a"))
        assertTrue(BlogPlatform.TISTORY.categorySelectable)
        assertTrue(BlogPlatform.WORDPRESS.categorySelectable)
        assertEquals(false, BlogPlatform.VELOG.categorySelectable)
        assertEquals(false, BlogPlatform.MEDIUM.categorySelectable)
    }

    @Test
    fun 카테고리는_3개까지이고_velog는_카테고리를_고를_수_없다() {
        val many = error { texts.add("blog.naver.com/three", listOf("여행", "일상", "맛집", "공부")) }
        assertEquals(ErrorCodes.INVALID_REQUEST, many?.code)
        assertEquals("categories", many?.field)
        assertEquals(3, texts.add("blog.naver.com/three", listOf("여행", "일상", "맛집")).categories.size)

        val velog = texts.check("velog.io/@minji")
        assertTrue(velog.categories.isEmpty())
        assertEquals("categories", error { texts.add("velog.io/@minji", listOf("여행")) }?.field)
        assertTrue(texts.add("velog.io/@minji", emptyList()).optimized)
        // 따로 맞추지 않은 블로그
        assertEquals(false, texts.check("https://minji.blogspot.com").optimized)
    }
}
