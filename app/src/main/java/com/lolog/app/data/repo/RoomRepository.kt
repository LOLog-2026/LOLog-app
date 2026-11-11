package com.lolog.app.data.repo

import com.lolog.app.data.model.*

/**
 * 친구와 같이 쓰는 방(LOLog-docs spec/rooms.md). 실서버는 RemoteRoomRepository, 목업은 MockRooms예요.
 * 실패하면 LologException(code는 RoomErrorCodes)을 던져요.
 */
interface RoomRepository {
    /** 마지막 소식이 최근인 방이 위 */
    suspend fun rooms(): List<Room>
    /**
     * memberIds는 나를 뺀 친구 회원 ID(1~5명). 방 이름은 앞뒤 공백을 잘라 1~20자.
     * 고른 친구는 멤버가 아니라 초대(room.invites)로 들어가고, 만든 사람이 방장이에요. 방이 20개면 ROOM_LIMIT
     */
    suspend fun createRoom(name: String, memberIds: List<Long>): Room
    suspend fun room(id: Long): Room
    /** 최근에 올린 만화가 앞 */
    suspend fun posts(roomId: Long): List<RoomPost>
    /** 내 만화를 올려요. 같은 만화를 같은 방에 또 올리면 ROOM_POST_EXISTS */
    suspend fun postComic(roomId: Long, comicId: Long): RoomPost
    /** 올린 사람만 내릴 수 있어요. 그 만화의 좋아요·댓글도 같이 지워져요 */
    suspend fun removePost(postId: Long)
    /** 좋아요 ↔ 좋아요 취소 */
    suspend fun toggleLike(postId: Long): RoomPost
    /** 오래된 줄이 위. 불러오면 그 방의 받지 않은 글이 0이 돼요 */
    suspend fun messages(roomId: Long): List<RoomMessage>
    /** postId가 있으면 그 만화 캡션을 단 댓글. 글은 앞뒤 공백을 잘라 1~200자 */
    suspend fun sendComment(roomId: Long, postId: Long?, text: String): RoomMessage.Comment
    suspend fun leaveRoom(roomId: Long)

    // ── 방장과 초대 ──

    /** 방장만(ROOM_OWNER_ONLY). 멤버 + 답을 기다리는 초대가 6명을 넘으면 ROOM_MEMBER_LIMIT. 이미 초대한 사람은 그대로 둬요 */
    suspend fun invite(roomId: Long, userIds: List<Long>): List<RoomInvite>
    /** 방장만. 없거나 이미 답한 초대면 ROOM_INVITE_NOT_FOUND */
    suspend fun cancelInvite(roomId: Long, inviteId: Long)
    /** 내가 받은 초대(답을 기다리는 것만) */
    suspend fun myInvites(): List<RoomInvite>
    /** 수락하면 그 방에 들어가요. 방이 20개면 ROOM_LIMIT, 없거나 이미 답한 초대면 ROOM_INVITE_NOT_FOUND */
    suspend fun acceptInvite(inviteId: Long): Room
    /** 거절하면 초대만 없어져요(방장에게 알리지 않음) */
    suspend fun declineInvite(inviteId: Long)
}
