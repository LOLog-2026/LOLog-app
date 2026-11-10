package com.lolog.app.data.model

/*
 * 친구와 같이 쓰는 방(시안 20~24). 명세는 LOLog-docs spec/rooms.md(초안 docs #57), 서버는 lolog-api #51.
 * 실서버는 RemoteRoomRepository, 목업은 MockRooms예요. 규칙(RoomRules)은 서버와 같아요.
 */

/** 규칙(서버와 같음): 방 인원은 나 포함 2~6명, 방 이름 1~20자, 댓글 1~200자 */
object RoomRules {
    const val NAME_MAX = 20
    const val MEMBER_MIN = 2
    const val MEMBER_MAX = 6
    const val COMMENT_MAX = 200
}

/** 방 오류 코드(rooms.md 오류 코드). ROOM_POST_NOT_MINE는 403, ROOM_POST_EXISTS·ROOM_MEMBER_LIMIT는 409 */
object RoomErrorCodes {
    const val ROOM_NOT_FOUND = "ROOM_NOT_FOUND"
    const val ROOM_POST_NOT_FOUND = "ROOM_POST_NOT_FOUND"
    const val ROOM_POST_EXISTS = "ROOM_POST_EXISTS"       // 같은 만화를 같은 방에 또 올림
    const val ROOM_POST_NOT_MINE = "ROOM_POST_NOT_MINE"   // 남이 올린 만화를 내리려 함
    const val ROOM_MEMBER_LIMIT = "ROOM_MEMBER_LIMIT"     // 나 포함 6명을 넘음
}

/** 방 멤버. thumbnailUrl은 그 사람의 프로필 캐릭터 그림(없으면 이름 첫 글자 동그라미) */
data class RoomMember(
    val userId: Long,
    val nickname: String,
    val thumbnailUrl: String? = null,
    /** 나인지. 내 만화면 "내 만화", 내 댓글이면 오른쪽 말풍선 */
    val isMe: Boolean = false,
)

/** 방 목록 한 줄. lastActivity는 "준님이 만화를 올렸어요" 같은 마지막 소식, unread는 받지 않은(안 읽은) 글 수 */
data class Room(
    val id: Long,
    val name: String,
    val members: List<RoomMember>,
    val lastActivity: String,
    val lastActivityAt: String,
    val unread: Int,
)

/** 방에 올린 만화 하나. mockLines는 목업 그림(mock://)을 그릴 대사라 실서버에서는 비어 있어요 */
data class RoomPost(
    val id: Long,
    val roomId: Long,
    val comicId: Long,
    val title: String,
    val imageUrl: String?,
    val author: RoomMember,
    val postedAt: String,
    val likeCount: Int,
    val likedByMe: Boolean,
    val commentCount: Int,
    val mockLines: List<String> = emptyList(),
)

/** 대화 한 줄: 가운데 회색 알약(System·Like) 또는 댓글 말풍선(Comment) */
sealed interface RoomMessage {
    val id: Long
    val at: String
    /** 어느 만화에 대한 줄인지. 그 만화를 내리면 같이 지워져요 */
    val postId: Long?

    /** "준님이 만화를 올렸어요" */
    data class System(override val id: Long, override val at: String, val text: String, override val postId: Long? = null) : RoomMessage

    /** "서연님이 이 만화를 좋아해요" */
    data class Like(override val id: Long, override val at: String, val member: RoomMember, override val postId: Long) : RoomMessage

    /** 댓글. post가 있으면 말풍선 위에 그 만화 캡션을 붙여요 */
    data class Comment(override val id: Long, override val at: String, val author: RoomMember, val text: String, val post: RoomPost?) : RoomMessage {
        override val postId: Long? get() = post?.id
    }
}

/** 소식 문장의 주어: "준님이", 나는 "내가" */
val RoomMember.subject: String get() = if (isMe) "내가" else "${nickname}님이"
