package com.lolog.app.data.remote

import kotlinx.serialization.Serializable

// ── 친구와 같이 쓰는 방 (LOLog-docs spec/rooms.md 2장, 초안 docs #57 · 서버 lolog-api #51) ──

/** POST /api/rooms. memberIds는 나를 뺀 친구 회원 ID 1~5개 */
@Serializable
data class RoomCreateDto(val name: String, val memberIds: List<Long>)

/** POST /api/rooms/{id}/posts */
@Serializable
data class RoomPostCreateDto(val comicId: Long)

/** POST /api/rooms/{id}/messages. postId가 없으면 방 전체에 단 댓글 */
@Serializable
data class RoomCommentDto(val text: String, val postId: Long? = null)

/** RoomMember. thumbnailUrl은 그 사람의 프로필 캐릭터 256 그림(없으면 null) */
@Serializable
data class RoomMemberDto(val userId: Long, val nickname: String = "", val thumbnailUrl: String? = null, val isMe: Boolean = false)

/** 마지막 소식. type CREATED·POSTED·LIKED·COMMENTED, text는 COMMENTED일 때 댓글 내용. 문장은 앱이 만들어요 */
@Serializable
data class RoomActivityDto(val type: String = "", val nickname: String = "", val isMe: Boolean = false, val at: String = "", val text: String? = null)

@Serializable
data class RoomItemDto(
    val id: Long,
    val name: String = "",
    val members: List<RoomMemberDto> = emptyList(),
    val lastActivity: RoomActivityDto? = null,
    val unread: Int = 0,
    val createdAt: String = "",
)

@Serializable
data class RoomListDto(val rooms: List<RoomItemDto> = emptyList())

/** RoomPost. imageUrl은 완성 이미지의 10분 서명 주소 */
@Serializable
data class RoomPostDto(
    val id: Long,
    val roomId: Long,
    val comicId: Long,
    val title: String = "",
    val imageUrl: String? = null,
    val author: RoomMemberDto,
    val postedAt: String = "",
    val likeCount: Int = 0,
    val likedByMe: Boolean = false,
    val commentCount: Int = 0,
)

@Serializable
data class RoomPostListDto(val posts: List<RoomPostDto> = emptyList())

@Serializable
data class RoomPostSummaryDto(val id: Long, val title: String = "", val imageUrl: String? = null)

/** 대화 한 줄. type CREATED·POSTED·LIKED·COMMENT. text는 COMMENT일 때만 */
@Serializable
data class RoomMessageDto(
    val id: Long,
    val type: String = "",
    val member: RoomMemberDto,
    val at: String = "",
    val postId: Long? = null,
    val post: RoomPostSummaryDto? = null,
    val text: String? = null,
)

@Serializable
data class RoomMessageListDto(val messages: List<RoomMessageDto> = emptyList())
