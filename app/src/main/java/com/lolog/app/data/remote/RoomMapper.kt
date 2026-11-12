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

    /** 나간 사람(left)은 userId·nickname이 null이라 [RoomMember.LEFT_ID]·빈 글자로 받아요 */
    fun RoomMemberDto.toModel() = RoomMember(
        userId = userId ?: RoomMember.LEFT_ID,
        nickname = nickname.orEmpty(),
        thumbnailUrl = thumbnailUrl?.ifBlank { null },
        isMe = isMe,
        isOwner = isOwner,
        suspended = suspended,
        friendState = friendState?.ifBlank { null },
        left = left || userId == null,
    )

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
        // 나간 멤버면 nickname이 null: "나간 멤버가 …"
        val name = a.nickname?.ifBlank { null }
        val subject = when {
            a.isMe -> "내가"
            name == null -> "${RoomMember.LEFT_NAME}가"
            else -> "${name}님이"
        }
        return when (a.type.uppercase()) {
            "CREATED" -> if (!a.isMe && name == null) "$subject 방을 만들었어요" else createdText(a.isMe, name.orEmpty())
            "POSTED" -> "$subject 만화를 올렸어요"
            "LIKED" -> "$subject 만화를 좋아해요"
            "COMMENTED" -> "${if (a.isMe) "나" else name ?: RoomMember.LEFT_NAME} \"${a.text.orEmpty()}\""
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
        id = id, roomId = roomId, comicId = comicId, title = title.orEmpty(), imageUrl = imageUrl?.ifBlank { null },
        author = author.toModel(), postedAt = postedAt, likeCount = likeCount, likedByMe = likedByMe,
        commentCount = commentCount, hidden = hidden,
    )

    /**
     * 대화 한 줄. CREATED·POSTED는 가운데 알림 줄(System), LIKED는 Like, COMMENT는 Comment.
     * 댓글의 만화는 [posts](이미 받은 만화 목록)에서 찾고, 없으면 서버가 준 요약으로 채워요. 모르는 종류는 null(건너뜀).
     * 숨긴 글(hidden)은 내용을 비워요(서버도 text·post를 null로 줘요).
     */
    fun RoomMessageDto.toModel(roomId: Long, posts: Map<Long, RoomPost>): RoomMessage? {
        val m = member.toModel()
        return when (type.uppercase()) {
            "CREATED" -> RoomMessage.System(id, at, if (m.left) "${m.subject} 방을 만들었어요" else createdText(m.isMe, m.nickname), hidden = hidden)
            "POSTED" -> RoomMessage.System(id, at, "${m.subject} 만화를 올렸어요", postId, hidden)
            "LIKED" -> postId?.let { RoomMessage.Like(id, at, m, it, hidden) }
            "COMMENT" -> if (hidden) RoomMessage.Comment(id, at, m, "", null, hidden = true) else RoomMessage.Comment(
                id, at, m, text.orEmpty(),
                postId?.let { pid ->
                    posts[pid] ?: post?.let { RoomPost(it.id, roomId, 0, it.title, it.imageUrl?.ifBlank { null }, m, at, 0, false, 0) }
                },
            )
            else -> null
        }
    }
}
