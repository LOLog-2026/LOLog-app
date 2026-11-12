package com.lolog.app.data.model

/*
 * 친구와 같이 쓰는 방(시안 20~24). 명세는 LOLog-docs spec/rooms.md(초안 docs #57), 서버는 lolog-api #51.
 * 실서버는 RemoteRoomRepository, 목업은 MockRooms예요. 규칙(RoomRules)은 서버와 같아요.
 */

/** 규칙(서버와 같음): 방 인원은 나 포함 2~6명, 방 이름 1~20자, 댓글 1~200자, 한 사람의 방은 20개까지 */
object RoomRules {
    const val NAME_MAX = 20
    const val MEMBER_MIN = 2
    /** 멤버와 답을 기다리는 초대를 합쳐서 세요 */
    const val MEMBER_MAX = 6
    const val COMMENT_MAX = 200
    /** 한 사람이 들어갈 수 있는 방 수 */
    const val ROOM_MAX = 20
}

/** 방 오류 코드(rooms.md 오류 코드). ROOM_POST_NOT_MINE는 403, ROOM_POST_EXISTS·ROOM_MEMBER_LIMIT는 409 */
object RoomErrorCodes {
    const val ROOM_NOT_FOUND = "ROOM_NOT_FOUND"
    const val ROOM_POST_NOT_FOUND = "ROOM_POST_NOT_FOUND"
    const val ROOM_POST_EXISTS = "ROOM_POST_EXISTS"       // 같은 만화를 같은 방에 또 올림
    const val ROOM_POST_NOT_MINE = "ROOM_POST_NOT_MINE"   // 남이 올린 만화를 내리려 함
    const val ROOM_MEMBER_LIMIT = "ROOM_MEMBER_LIMIT"     // 멤버 + 답을 기다리는 초대가 나 포함 6명을 넘음
    const val ROOM_LIMIT = "ROOM_LIMIT"                   // 409 방 만들기·초대 수락: 이미 방 20개에 들어가 있음
    const val ROOM_OWNER_ONLY = "ROOM_OWNER_ONLY"         // 403 방장만 할 수 있는 일(초대·초대 취소·이름 바꾸기·내보내기·해산)
    const val ROOM_INVITE_NOT_FOUND = "ROOM_INVITE_NOT_FOUND" // 404 없거나 이미 답한 초대, 내 초대가 아님
    const val NEW_OWNER_REQUIRED = "NEW_OWNER_REQUIRED"   // 409 방장이 다른 멤버가 있는데 새 방장을 고르지 않고 나감
}

/**
 * 방 멤버. thumbnailUrl은 그 사람의 프로필 캐릭터 그림(없으면 이름 첫 글자 동그라미).
 * 대화·올린 만화의 사람이 방에서 나갔으면 left가 true예요. 서버는 userId·nickname·thumbnailUrl을 null로 주고,
 * 앱은 userId를 [LEFT_ID], nickname을 빈 글자로 받아요(화면은 [displayName]으로 "나간 멤버"를 보여요).
 */
data class RoomMember(
    val userId: Long,
    val nickname: String,
    val thumbnailUrl: String? = null,
    /** 나인지. 내 만화면 "내 만화", 내 댓글이면 오른쪽 말풍선 */
    val isMe: Boolean = false,
    /** 방장인지. 멤버 시트에 "방장" 알약 */
    val isOwner: Boolean = false,
    /** 정지된 계정인지(초안) */
    val suspended: Boolean = false,
    /** 나와의 친구 관계(초안. FRIEND·REQUESTED·RECEIVED·NONE·BLOCKED·ME). 멤버 시트 친구 버튼에 써요 */
    val friendState: String? = null,
    /** 방에서 나간 사람인지(초안) */
    val left: Boolean = false,
) {
    /** 화면에 쓰는 이름. 나간 사람은 "나간 멤버" */
    val displayName: String get() = if (left) LEFT_NAME else nickname

    companion object {
        /** 나간 멤버의 userId 자리(서버는 null) */
        const val LEFT_ID = 0L
        const val LEFT_NAME = "나간 멤버"
    }
}

/**
 * 방 목록 한 줄. lastActivity는 "준님이 만화를 올렸어요" 같은 마지막 소식, unread는 받지 않은(안 읽은) 글 수.
 * ownerId는 방장(서버가 안 보내면 null), invites는 답을 기다리는 초대(방장이 아니면 비어 있어요).
 */
data class Room(
    val id: Long,
    val name: String,
    val members: List<RoomMember>,
    val lastActivity: String,
    val lastActivityAt: String,
    val unread: Int,
    val ownerId: Long? = null,
    val invites: List<RoomInvite> = emptyList(),
) {
    /** 내가 방장인지. ownerId가 없으면 멤버의 isOwner로 봐요 */
    val isMineOwner: Boolean
        get() = members.any { it.isMe && (it.isOwner || it.userId == ownerId) }

    /** 멤버와 답을 기다리는 초대를 합친 인원(나 포함) */
    val headcount: Int get() = members.size + invites.size

    /** 더 초대할 수 있는 사람 수 */
    val seatsLeft: Int get() = (RoomRules.MEMBER_MAX - headcount).coerceAtLeast(0)
}

/**
 * 방 초대 하나(rooms.md RoomInvite, 초안). 방장이 보는 초대는 invitedUser, 내가 받은 초대는 invitedBy가 있어요.
 * hasBlockedMember는 내가 차단한 사람이 그 방에 있으면 true(수락 전에 알려 줘요).
 * memberCount는 받은 초대 카드의 "멤버 n명"이에요. 명세에 없는 값이라 실서버에서는 null이에요(목업만 채움).
 */
data class RoomInvite(
    val id: Long,
    val roomId: Long,
    val roomName: String,
    val invitedUser: RoomMember?,
    val invitedBy: RoomMember?,
    val hasBlockedMember: Boolean,
    val createdAt: String,
    val memberCount: Int? = null,
)

/**
 * 방에 올린 만화 하나. mockLines는 목업 그림(mock://)을 그릴 대사라 실서버에서는 비어 있어요.
 * hidden은 내가 숨기기로 한 차단한 사람이 올린 만화예요. 서버는 title·imageUrl을 null로 줘서 title은 빈 글자예요
 */
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
    val hidden: Boolean = false,
)

/**
 * 대화 한 줄: 가운데 회색 알약(System·Like) 또는 댓글 말풍선(Comment).
 * hidden은 내가 숨기기로 한 차단한 사람의 줄이에요(초안). 서버는 text·post를 null로 줘서 댓글이면 text는 빈 글자, post는 null이에요
 */
sealed interface RoomMessage {
    val id: Long
    val at: String
    /** 어느 만화에 대한 줄인지. 그 만화를 내리면 같이 지워져요 */
    val postId: Long?
    val hidden: Boolean

    /** "준님이 만화를 올렸어요" */
    data class System(
        override val id: Long, override val at: String, val text: String, override val postId: Long? = null,
        override val hidden: Boolean = false,
    ) : RoomMessage

    /** "서연님이 이 만화를 좋아해요" */
    data class Like(
        override val id: Long, override val at: String, val member: RoomMember, override val postId: Long,
        override val hidden: Boolean = false,
    ) : RoomMessage

    /** 댓글. post가 있으면 말풍선 위에 그 만화 캡션을 붙여요 */
    data class Comment(
        override val id: Long, override val at: String, val author: RoomMember, val text: String, val post: RoomPost?,
        override val hidden: Boolean = false,
    ) : RoomMessage {
        override val postId: Long? get() = post?.id
    }
}

/**
 * 대화 한 묶음(rooms.md 2장, lolog-api #60). 오래된 줄이 앞이에요.
 * nextCursor가 있으면 그 앞 줄이 더 있어요. 다음 요청에 그대로 넘겨요(뜻은 해석하지 않아요)
 */
data class RoomMessagePage(val messages: List<RoomMessage>, val nextCursor: String?)

/** 소식 문장의 주어: "준님이", 나는 "내가", 나간 사람은 "나간 멤버가" */
val RoomMember.subject: String get() = when {
    isMe -> "내가"
    left -> "${RoomMember.LEFT_NAME}가"
    else -> "${nickname}님이"
}
