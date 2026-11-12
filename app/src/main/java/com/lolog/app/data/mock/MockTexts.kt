package com.lolog.app.data.mock

import com.lolog.app.data.model.*
import com.lolog.app.data.repo.LologException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * 글로 카드 만들기 목업(LOLog-docs PR #62 spec/texts.md 초안). 서버에 아직 API가 없어서 목업에서만 끝까지 돌아요.
 * MockLologRepository가 lock 안에서 불러요. [clock]을 바꿔 시험에서 시간을 넘길 수 있어요.
 *
 * 규칙
 *  - 직접 쓴 글: 제목·본문이 비거나 공백만 → 400 INVALID_REQUEST(field title·body),
 *    본문이 TEXT_BODY_MAX를 넘음 → 400 TEXT_TOO_LONG(이름은 초안)
 *  - 올리면 QUEUED, 4초 뒤 DONE + 카드 1장(source WRITTEN). 본문에 "실패"가 있으면 FAILED(실패 화면 확인용)
 *  - 블로그 확인: 주소에 naver·tistory가 있으면 찾음(카테고리 여행·일상·맛집·공부·리뷰), 아니면 400 INVALID_BLOG.
 *    localhost·127.·192.168.은 내부 주소라 같은 오류(서버의 SSRF 막기 흉내)
 *  - 블로그 연결: 가져온 글 4개(카드 만듦 2 · 만드는 중 1 · 실패 1)와 BLOG 카드 2장(1장은 truncated)이 생겨요
 *  - 처음부터 카드 7(택시)은 직접 쓴 글(text 1) 카드예요
 *  - 없는 글·블로그의 오류 코드는 명세에 없어서 목업에서만 TEXT_NOT_FOUND·BLOG_NOT_FOUND를 써요
 */
internal class MockTexts(
    private val clock: () -> Long = System::currentTimeMillis,
    /** 새 글·블로그·카드 id. 목업 저장소의 id와 겹치지 않게 받아 써요 */
    private val newId: () -> Long,
    /** 새 카드를 에피소드 맨 앞에 넣어요 */
    private val addCard: (CardDetail) -> Unit,
) {
    private class Entry(var detail: TextDetail, val createdMs: Long)

    private val texts = mutableListOf(
        Entry(
            TextDetail(
                textId = SEED_TEXT_ID, source = CardSource.WRITTEN, status = TextStatus.DONE, cardId = 7,
                title = "택시에서 들은 이야기",
                body = "야근하고 택시를 탔는데 기사님이 갑자기 요즘 고민 있냐고 물으셨다. 진로 얘기를 조금 했더니 " +
                    "기사님도 내 나이 땐 택시 할 줄 몰랐다고 하셔서 이상하게 마음이 편해졌다.",
                place = "택시 안", situation = "야근하고 집에 가는 길", link = null, truncated = false,
                createdAt = "2026-10-29T23:10:00+09:00",
            ),
            createdMs = 0,
        ),
    )
    private val blogs = mutableListOf<Blog>()
    private val posts = mutableMapOf<Long, List<BlogPost>>()

    private fun nowIso(): String = OffsetDateTime.ofInstant(Instant.ofEpochMilli(clock()), ZoneId.systemDefault()).toString()

    // ── 직접 쓴 글 ──

    fun create(input: TextInput): Long {
        fun bad(field: String, msg: String): Nothing = throw LologException(ErrorCodes.INVALID_REQUEST, msg, field)
        if (input.title.isBlank()) bad("title", "제목을 써 주세요")
        if (input.body.isBlank()) bad("body", "본문을 써 주세요")
        if (input.body.length > TEXT_BODY_MAX) {
            throw LologException(ErrorCodes.TEXT_TOO_LONG, "글이 너무 길어요. 줄여서 다시 보내 주세요", "body")
        }
        val id = newId()
        texts += Entry(
            TextDetail(
                textId = id, source = CardSource.WRITTEN, status = TextStatus.QUEUED, cardId = null,
                title = input.title.trim(), body = input.body.trim(),
                place = input.place?.trim()?.ifBlank { null }, situation = input.situation?.trim()?.ifBlank { null },
                link = null, truncated = false, createdAt = nowIso(),
            ),
            createdMs = clock(),
        )
        return id
    }

    /** 올린 지 4초 지난 QUEUED 글을 끝내요. 새로 만든 카드 수를 돌려줘요 */
    fun settle(): Int {
        var made = 0
        texts.filter { it.detail.status == TextStatus.QUEUED && it.detail.source == CardSource.WRITTEN && clock() - it.createdMs >= MAKE_MS }
            .forEach { e ->
                val d = e.detail
                // 목업 시험용: 본문에 "실패"가 있으면 만들지 못해요
                if (d.body.orEmpty().contains("실패")) {
                    e.detail = d.copy(status = TextStatus.FAILED)
                    return@forEach
                }
                val card = writtenCard(newId(), d)
                addCard(card)
                e.detail = d.copy(status = TextStatus.DONE, cardId = card.id)
                made++
            }
        return made
    }

    fun get(id: Long): TextDetail = texts.firstOrNull { it.detail.textId == id }?.detail
        ?: throw LologException("TEXT_NOT_FOUND", "글을 찾지 못했어요")

    /** 직접 쓴 글 카드: 장소는 적은 그대로, 날짜는 올린 시각(texts.md 2장), 대사 3줄은 목업 */
    private fun writtenCard(id: Long, t: TextDetail) = CardDetail(
        id = id, kind = CardKind.FUNNY, summary = t.title.take(30), place = t.place,
        speakers = listOf(Speaker("화자1"), Speaker("화자2")),
        lines = listOf(
            Line(1, "화자1", "오늘 있었던 일 들어 볼래?"),
            Line(2, "화자2", "뭔데 뭔데"),
            // 목업 만화는 40자 넘는 대사가 있으면 실패해서 잘라 둬요
            Line(3, "화자1", t.body.orEmpty().take(40)),
        ),
        createdAt = nowIso(), recordedAt = t.createdAt,
        source = CardSource.WRITTEN, textId = t.textId,
    )

    // ── 블로그 ──

    fun check(url: String): BlogCheck {
        val u = url.trim().lowercase()
        val notFound = LologException(ErrorCodes.INVALID_BLOG, "블로그를 찾지 못했어요. 주소를 확인해 주세요")
        // 서버는 내부 주소를 거절해요(SSRF 막기). 목업은 흔한 내부 주소만 흉내 내요
        if (INTERNAL_HOSTS.any { it in u }) throw notFound
        val platform = when {
            "naver" in u -> "NAVER"
            "tistory" in u -> "TISTORY"
            else -> throw notFound
        }
        val path = u.substringAfter("://").trimEnd('/')
        val feed = if (platform == "NAVER") "https://rss.blog.naver.com/${path.substringAfterLast('/')}.xml" else "https://${path.substringBefore('/')}/rss"
        return BlogCheck(feed, platform, MOCK_CATEGORIES)
    }

    fun add(url: String, categories: List<String>): Blog {
        val c = check(url)
        checkCategories(categories)
        val blog = Blog(newId(), c.platform, url.trim(), if (c.platform == "NAVER") "민지의 여행일기" else null, categories.distinct())
        blogs += blog
        posts[blog.id] = samplePosts(blog)
        return blog
    }

    fun update(id: Long, categories: List<String>): Blog {
        checkCategories(categories)
        val i = blogs.indexOfFirst { it.id == id }
        if (i < 0) blogNotFound()
        blogs[i] = blogs[i].copy(categories = categories.distinct())
        return blogs[i]
    }

    /** 연결한 순서대로 */
    fun list(): List<Blog> = blogs.toList()

    /** 이미 만든 카드는 남아요 */
    fun delete(id: Long) {
        if (!blogs.removeIf { it.id == id }) blogNotFound()
        posts.remove(id)
    }

    fun posts(id: Long, cursor: String?): BlogPostPage {
        val list = posts[id] ?: blogNotFound()
        val (page, next) = mockPage(list, cursor, 20, SortOrder.NEWEST)
        return BlogPostPage(page, next)
    }

    private fun blogNotFound(): Nothing = throw LologException("BLOG_NOT_FOUND", "블로그를 찾지 못했어요")

    private fun checkCategories(categories: List<String>) {
        if (categories.any { it.isBlank() || it.length > BLOG_CATEGORY_MAX }) {
            throw LologException(ErrorCodes.INVALID_REQUEST, "카테고리는 1~${BLOG_CATEGORY_MAX}자로 써 주세요", "categories")
        }
    }

    /** 가져온 글 4개(최신이 위): 카드 만듦 2(1장은 길어서 앞부분만) · 만드는 중 1 · 실패 1 */
    private fun samplePosts(blog: Blog): List<BlogPost> {
        val now = OffsetDateTime.ofInstant(Instant.ofEpochMilli(clock()), ZoneId.systemDefault())
        val site = blog.siteUrl.removePrefix("https://").removePrefix("http://").trimEnd('/')
        fun post(title: String, category: String, hoursAgo: Long, status: TextStatus, truncated: Boolean = false, place: String? = null): BlogPost {
            val link = "https://$site/${newId()}"
            val published = now.minusHours(hoursAgo).toString()
            val textId = newId()
            var cardId: Long? = null
            if (status == TextStatus.DONE) {
                val card = CardDetail(
                    id = newId(), kind = CardKind.MEMORY, summary = title, place = place,
                    speakers = listOf(Speaker("화자1"), Speaker("화자2")),
                    lines = listOf(
                        Line(1, "화자1", "이거 왜 안 가?"),
                        Line(2, "화자2", "체인 빠진 거 같은데…"),
                        Line(3, "화자1", "여기서? 지금?"),
                    ),
                    createdAt = nowIso(), recordedAt = published,
                    source = CardSource.BLOG, textId = textId, truncated = truncated,
                )
                addCard(card)
                cardId = card.id
            }
            // 블로그 글은 카드가 생기면 본문을 지우고 링크만 남겨요(texts.md 2장)
            texts += Entry(
                TextDetail(
                    textId, CardSource.BLOG, status, cardId, title, body = null, place = null, situation = null,
                    link = link, truncated = truncated, createdAt = published,
                ),
                createdMs = clock(),
            )
            return BlogPost(title, link, category, published, cardId, status)
        }
        return listOf(
            post("제주 3일차, 우도에서 생긴 일", "여행", 1, TextStatus.DONE, truncated = true, place = "우도"),
            post("비 오는 날 혼자 한 일", "일상", 5, TextStatus.QUEUED),
            post("부산 1박 2일 정리", "여행", 30, TextStatus.DONE, place = "부산"),
            post("아주 긴 글 테스트", "일상", 50, TextStatus.FAILED),
        )
    }

    companion object {
        /** 처음부터 있는 직접 쓴 글(카드 7) */
        const val SEED_TEXT_ID = 1L
        /** 올리고 카드가 생기기까지 */
        const val MAKE_MS = 4_000L
        val MOCK_CATEGORIES = listOf("여행", "일상", "맛집", "공부", "리뷰")
        private val INTERNAL_HOSTS = listOf("localhost", "127.", "192.168.")
    }
}
