package com.lolog.app.data

import com.lolog.app.data.model.RoomMessage
import com.lolog.app.data.remote.LologJson
import com.lolog.app.data.remote.RoomItemDto
import com.lolog.app.data.remote.RoomMapper
import com.lolog.app.data.remote.RoomMapper.toModel
import com.lolog.app.data.remote.RoomMessageListDto
import com.lolog.app.data.remote.RoomPostDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 서버(lolog-api #51) 응답 JSON → 앱 모델. JSON은 서버 테스트(RoomApiTests)에서 나오는 모양 그대로예요 */
class RoomMapperTest {

    @Test
    fun `방 목록의 마지막 소식은 종류마다 목업과 같은 문장이 된다`() {
        val room = LologJson.decodeFromString(RoomItemDto.serializer(), """
            {"id":1,"name":"과팀플","members":[{"userId":1,"nickname":"나","thumbnailUrl":null,"isMe":true},
              {"userId":2,"nickname":"준","thumbnailUrl":"https://x/t.png","isMe":false}],
             "lastActivity":{"type":"POSTED","nickname":"준","isMe":false,"at":"2026-11-11T03:00:00Z","text":null},
             "unread":2,"createdAt":"2026-11-11T02:00:00Z"}
        """.trimIndent()).toModel()
        assertEquals("준님이 만화를 올렸어요", room.lastActivity)
        assertEquals("2026-11-11T03:00:00Z", room.lastActivityAt)
        assertEquals(2, room.unread)
        assertTrue(room.members[0].isMe)
        assertEquals("https://x/t.png", room.members[1].thumbnailUrl)

        fun text(type: String, isMe: Boolean, t: String? = null) =
            RoomMapper.activityText(com.lolog.app.data.remote.RoomActivityDto(type, "준", isMe, "", t))
        assertEquals("방을 만들었어요", text("CREATED", true))
        assertEquals("준님이 방을 만들었어요", text("CREATED", false))
        assertEquals("내가 만화를 좋아해요", text("LIKED", true))
        assertEquals("나 \"민트초코 최고\"", text("COMMENTED", true, "민트초코 최고"))
        assertEquals("준 \"안녕\"", text("COMMENTED", false, "안녕"))
    }

    @Test
    fun `소식이 없으면 만든 시각으로 정렬한다`() {
        val room = LologJson.decodeFromString(RoomItemDto.serializer(),
            """{"id":1,"name":"방","members":[],"lastActivity":null,"unread":0,"createdAt":"2026-11-11T02:00:00Z"}""").toModel()
        assertEquals("", room.lastActivity)
        assertEquals("2026-11-11T02:00:00Z", room.lastActivityAt)
    }

    @Test
    fun `대화 줄은 알림 줄, 좋아요, 댓글로 나뉘고 댓글의 만화는 받은 목록에서 찾는다`() {
        val post = LologJson.decodeFromString(RoomPostDto.serializer(), """
            {"id":10,"roomId":1,"comicId":5,"title":"민트초코","imageUrl":"https://x/c.png",
             "author":{"userId":2,"nickname":"준","isMe":false},"postedAt":"t","likeCount":1,"likedByMe":true,"commentCount":1}
        """.trimIndent()).toModel()
        val list = LologJson.decodeFromString(RoomMessageListDto.serializer(), """
            {"messages":[
              {"id":1,"type":"CREATED","member":{"userId":1,"nickname":"나","isMe":true},"at":"t1","postId":null,"post":null,"text":null},
              {"id":2,"type":"POSTED","member":{"userId":2,"nickname":"준","isMe":false},"at":"t2","postId":10,"post":{"id":10,"title":"민트초코","imageUrl":"u"},"text":null},
              {"id":3,"type":"LIKED","member":{"userId":1,"nickname":"나","isMe":true},"at":"t3","postId":10,"post":{"id":10,"title":"민트초코","imageUrl":"u"},"text":null},
              {"id":4,"type":"COMMENT","member":{"userId":1,"nickname":"나","isMe":true},"at":"t4","postId":10,"post":{"id":10,"title":"민트초코","imageUrl":"u"},"text":"최고"},
              {"id":5,"type":"COMMENT","member":{"userId":2,"nickname":"준","isMe":false},"at":"t5","postId":11,"post":{"id":11,"title":"모르는 만화","imageUrl":null},"text":"이건?"},
              {"id":6,"type":"COMMENT","member":{"userId":2,"nickname":"준","isMe":false},"at":"t6","postId":null,"post":null,"text":"다들 뭐 해"},
              {"id":7,"type":"FUTURE","member":{"userId":2,"nickname":"준","isMe":false},"at":"t7"}
            ]}
        """.trimIndent()).messages.mapNotNull { it.toModel(1, mapOf(10L to post)) }

        assertEquals(6, list.size) // 모르는 종류는 건너뜀
        assertEquals("방을 만들었어요", (list[0] as RoomMessage.System).text)
        assertEquals("준님이 만화를 올렸어요", (list[1] as RoomMessage.System).text)
        assertEquals(10L, list[1].postId)
        assertTrue(list[2] is RoomMessage.Like)
        val mine = list[3] as RoomMessage.Comment
        assertEquals(post, mine.post) // 받은 목록의 만화 그대로
        val unknown = list[4] as RoomMessage.Comment
        assertEquals("모르는 만화", unknown.post?.title) // 목록에 없으면 서버 요약으로
        assertNull((list[5] as RoomMessage.Comment).post)
    }
}
