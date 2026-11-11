package com.lolog.app.data.remote

import com.lolog.app.data.model.Room
import com.lolog.app.data.model.RoomInvite
import com.lolog.app.data.model.RoomMember
import com.lolog.app.data.model.RoomMessage
import com.lolog.app.data.model.RoomPost
import com.lolog.app.data.model.subject

/**
 * 방 DTO → 앱 모델(LOLog-docs spec/rooms.md 2장).
 * 서버는 소식을 문장이 아니라 { type, nickname, isMe }로 보내서 문장은 여기서 만들어요(목업 문구와 같게).
 */
object RoomMapper {

    fun RoomMemberDto.toModel() = RoomMember(userId, nickname, thumbnailUrl?.ifBlank { null }, isMe, isOwner, suspended, friendState?.ifBlank { null })

    fun RoomInviteDto.toModel() = RoomInvite(
        id = id,
        roomId = room.id,
        roomName = room.name,
        invitedUser = invitedUser?.toModel(),
        invitedBy = invitedBy?.toModel(),
        hasBlockedMember = hasBlockedMember,
        createdAt = createdAt,
    )

    /** 방 목록의 마지막 소식 문장. 예: "준님이 만화를 올렸어요", 내 댓글이면 나 "민트초코 최고" */
    fun activityText(a: RoomActivityDto?): String {
        if (a == null) return ""
        val subject = if (a.isMe) "내가" else "${a.nickname}님이"
        return when (a.type.uppercase()) {
            "CREATED" -> createdText(a.isMe, a.nickname)
            "POSTED" -> "$subject 만화를 올렸어요"
            "LIKED" -> "$subject 만화를 좋아해요"
            "COMMENTED" -> "${if (a.isMe) "나" else a.nickname} \"${a.text.orEmpty()}\""
            else -> ""
        }
    }

    private fun createdText(isMe: Boolean, nickname: String) = if (isMe) "방을 만들었어요" else "${nickname}님이 방을 만들었어요"

    fun RoomItemDto.toModel() = Room(
        id = id,
        name = name,
        members = members.map { it.toModel() },
        lastActivity = activityText(lastActivity),
        lastActivityAt = lastActivity?.at?.ifBlank { null } ?: createdAt,
        unread = unread,
        ownerId = ownerId,
        invites = invites.map { it.toModel() },
    )

    fun RoomPostDto.toModel() = RoomPost(
        id = id, roomId = roomId, comicId = comicId, title = title, imageUrl = imageUrl?.ifBlank { null },
        author = author.toModel(), postedAt = postedAt, likeCount = likeCount, likedByMe = likedByMe,
        commentCount = commentCount,
    )

    /**
     * 대화 한 줄. CREATED·POSTED는 가운데 알림 줄(System), LIKED는 Like, COMMENT는 Comment.
     * 댓글의 만화는 [posts](이미 받은 만화 목록)에서 찾고, 없으면 서버가 준 요약으로 채워요. 모르는 종류는 null(건너뜀).
     */
    fun RoomMessageDto.toModel(roomId: Long, posts: Map<Long, RoomPost>): RoomMessage? {
        val m = member.toModel()
        return when (type.uppercase()) {
            "CREATED" -> RoomMessage.System(id, at, createdText(m.isMe, m.nickname))
            "POSTED" -> RoomMessage.System(id, at, "${m.subject} 만화를 올렸어요", postId)
            "LIKED" -> postId?.let { RoomMessage.Like(id, at, m, it) }
            "COMMENT" -> RoomMessage.Comment(
                id, at, m, text.orEmpty(),
                postId?.let { pid ->
                    posts[pid] ?: post?.let { RoomPost(it.id, roomId, 0, it.title, it.imageUrl?.ifBlank { null }, m, at, 0, false, 0) }
                },
            )
            else -> null
        }
    }
}
