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

/**
 * RoomMember. thumbnailUrl은 그 사람의 프로필 캐릭터 256 그림(없으면 null). isOwner·suspended·friendState·left는 초안이라 없으면 기본값.
 * 대화·올린 만화의 사람이 방에서 나갔으면 left가 true이고 userId·nickname·thumbnailUrl이 null이에요
 */
@Serializable
data class RoomMemberDto(
    val userId: Long? = null,
    val nickname: String? = null,
    val thumbnailUrl: String? = null,
    val isMe: Boolean = false,
    val isOwner: Boolean = false,
    val suspended: Boolean = false,
    val friendState: String? = null,
    val left: Boolean = false,
)

/** PATCH /api/rooms/{id} (방장, 초안) */
@Serializable
data class RoomRenameDto(val name: String)

/**
 * 마지막 소식. type CREATED·POSTED·LIKED·COMMENTED, text는 COMMENTED일 때 댓글 내용. 문장은 앱이 만들어요.
 * 그 사람이 방을 나갔으면 nickname이 null이에요(rooms.md 6장)
 */
@Serializable
data class RoomActivityDto(val type: String = "", val nickname: String? = null, val isMe: Boolean = false, val at: String = "", val text: String? = null)

/** RoomItem. ownerId·invites는 초안이라 없으면 null·빈 배열. invites는 방장이 아니면 빈 배열 */
@Serializable
data class RoomItemDto(
    val id: Long,
    val name: String = "",
    val ownerId: Long? = null,
    val members: List<RoomMemberDto> = emptyList(),
    val invites: List<RoomInviteDto> = emptyList(),
    val lastActivity: RoomActivityDto? = null,
    val unread: Int = 0,
    val createdAt: String = "",
)

// ── 방장과 초대 (rooms.md "방장과 초대", 초안 · 서버 lolog-api #59) ──

/** POST /api/rooms/{id}/invites. 초대할 친구 회원 ID(나를 뺌) */
@Serializable
data class RoomInviteCreateDto(val userIds: List<Long>)

/** RoomInvite의 room */
@Serializable
data class RoomRefDto(val id: Long, val name: String = "")

/** RoomInvite. 방장이 보는 초대는 invitedUser, 내가 받은 초대는 invitedBy가 있어요 */
@Serializable
data class RoomInviteDto(
    val id: Long,
    val room: RoomRefDto,
    val invitedUser: RoomMemberDto? = null,
    val invitedBy: RoomMemberDto? = null,
    val hasBlockedMember: Boolean = false,
    val createdAt: String = "",
)

/** 201 POST /api/rooms/{id}/invites, 200 GET /api/room-invites */
@Serializable
data class RoomInviteListDto(val invites: List<RoomInviteDto> = emptyList())

@Serializable
data class RoomListDto(val rooms: List<RoomItemDto> = emptyList())

/** RoomPost. imageUrl은 완성 이미지의 10분 서명 주소. hidden(숨긴 사람이 올린 만화)이면 title·imageUrl이 null(rooms.md 6장) */
@Serializable
data class RoomPostDto(
    val id: Long,
    val roomId: Long,
    val comicId: Long,
    val title: String? = null,
    val imageUrl: String? = null,
    val author: RoomMemberDto,
    val postedAt: String = "",
    val likeCount: Int = 0,
    val likedByMe: Boolean = false,
    val commentCount: Int = 0,
    val hidden: Boolean = false,
)

@Serializable
data class RoomPostListDto(val posts: List<RoomPostDto> = emptyList())

@Serializable
data class RoomPostSummaryDto(val id: Long, val title: String = "", val imageUrl: String? = null)

/** 대화 한 줄. type CREATED·POSTED·LIKED·COMMENT. text는 COMMENT일 때만. hidden(초안)이면 text·post가 null */
@Serializable
data class RoomMessageDto(
    val id: Long,
    val type: String = "",
    val member: RoomMemberDto,
    val at: String = "",
    val postId: Long? = null,
    val post: RoomPostSummaryDto? = null,
    val text: String? = null,
    val hidden: Boolean = false,
)

@Serializable
data class RoomMessageListDto(val messages: List<RoomMessageDto> = emptyList(), val nextCursor: String? = null)
