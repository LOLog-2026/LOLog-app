package com.lolog.app.data

import com.lolog.app.data.model.CardSource
import com.lolog.app.data.model.TextStatus
import com.lolog.app.data.remote.ApiMapper.toModel
import com.lolog.app.data.remote.BlogDto
import com.lolog.app.data.remote.BlogPostPageDto
import com.lolog.app.data.remote.CardDetailDto
import com.lolog.app.data.remote.CardPageDto
import com.lolog.app.data.remote.LologJson
import com.lolog.app.data.remote.TextDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 글로 카드 만들기(texts.md 6장) 서버 응답을 앱 모델로 읽기. 모양은 LOLog-api PR #73·#74 응답 그대로 */
class TextMappingTest {

    @Test
    fun 글_카드는_source와_textId를_읽고_시간이_null이어도_된다() {
        val d = LologJson.decodeFromString(
            CardDetailDto.serializer(),
            """{"id":5,"recordingId":null,"kind":"웃김","summary":"마트","place":"마트","recordedAt":"2026-11-13T10:00:00+09:00",
               "startMs":null,"endMs":null,"speakers":[],"lines":[],"comics":[],"comicRemaining":3,"videos":[],
               "audio":{"status":"NONE"},"source":"BLOG","textId":8,"truncated":true,"createdAt":"2026-11-13T10:00:00+09:00"}""",
        ).toModel()
        assertEquals(CardSource.BLOG, d.source)
        assertEquals(8L, d.textId)
        assertTrue(d.truncated)
        assertEquals(0L, d.startMs)

        val page = LologJson.decodeFromString(
            CardPageDto.serializer(),
            """{"cards":[{"id":5,"kind":"웃김","summary":"s","startMs":null,"endMs":null,"source":"WRITTEN","textId":8,"createdAt":"x"},
                         {"id":4,"kind":"웃김","summary":"s","startMs":0,"endMs":10,"source":"RECORDING","textId":null,"createdAt":"x"}],
               "nextCursor":null}""",
        ).toModel()
        assertEquals(CardSource.WRITTEN, page.cards[0].source)
        assertEquals(8L, page.cards[0].textId)
        assertEquals(CardSource.RECORDING, page.cards[1].source)
        assertNull(page.cards[1].textId)
    }

    @Test
    fun 예전_서버처럼_source가_없으면_녹음이다() {
        val d = LologJson.decodeFromString(CardDetailDto.serializer(), """{"id":1,"summary":"s","startMs":0,"endMs":1,"createdAt":"x"}""").toModel()
        assertEquals(CardSource.RECORDING, d.source)
        assertFalse(d.truncated)
    }

    @Test
    fun 블로그_글_원문은_link와_blogTitle() {
        val t = LologJson.decodeFromString(
            TextDto.serializer(),
            """{"textId":8,"source":"BLOG","status":"DONE","cardId":5,"title":"그날","body":null,"place":null,"situation":null,
               "link":"https://x/1","blogTitle":"일기장","truncated":false,"createdAt":"2026-11-13T10:00:00+09:00"}""",
        ).toModel()
        assertEquals(CardSource.BLOG, t.source)
        assertEquals(TextStatus.DONE, t.status)
        assertEquals("일기장", t.blogTitle)
        assertEquals("https://x/1", t.link)
        assertNull(t.body)
    }

    @Test
    fun 블로그_응답에는_platform이_없다() {
        val b = LologJson.decodeFromString(
            BlogDto.serializer(), """{"id":3,"siteUrl":"https://blog.naver.com/a","title":"내 블로그","categories":["일상"]}""",
        ).toModel()
        assertEquals(3L, b.id)
        assertEquals("내 블로그", b.title)
        assertEquals(listOf("일상"), b.categories)
        // optimized가 안 오면(예전 서버) 안내를 띄우지 않게 true로 봐요
        assertTrue(b.optimized)
        val other = LologJson.decodeFromString(
            BlogDto.serializer(), """{"id":4,"siteUrl":"https://a.blogspot.com","categories":[],"optimized":false}""",
        ).toModel()
        assertEquals(false, other.optimized)
        val check = LologJson.decodeFromString(
            com.lolog.app.data.remote.BlogCheckDto.serializer(), """{"feedUrl":"https://a/rss","categories":["일상"],"optimized":false}""",
        ).toModel()
        assertEquals(false, check.optimized)
    }

    @Test
    fun 카드를_지운_블로그_글은_status가_null() {
        val page = LologJson.decodeFromString(
            BlogPostPageDto.serializer(),
            """{"posts":[{"title":"a","link":"https://x/a","category":"일상","publishedAt":null,"cardId":null,"status":null},
                         {"title":"b","link":"https://x/b","category":null,"publishedAt":"2026-11-01T10:00:00+09:00","cardId":9,"status":"DONE"}],
               "nextCursor":"12"}""",
        ).toModel()
        assertNull(page.posts[0].status)
        assertNull(page.posts[0].cardId)
        assertEquals(TextStatus.DONE, page.posts[1].status)
        assertEquals("12", page.nextCursor)
    }
}
