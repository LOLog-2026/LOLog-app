package com.lolog.app.data.repo

import com.lolog.app.data.model.ErrorCodes
import com.lolog.app.data.model.Room
import com.lolog.app.data.model.RoomErrorCodes
import com.lolog.app.data.model.RoomInvite
import com.lolog.app.data.model.RoomMessage
import com.lolog.app.data.model.RoomPost
import com.lolog.app.data.remote.ApiClient
import com.lolog.app.data.remote.ErrorDto
import com.lolog.app.data.remote.LologJson
import com.lolog.app.data.remote.RoomCommentDto
import com.lolog.app.data.remote.RoomCreateDto
import com.lolog.app.data.remote.RoomInviteCreateDto
import com.lolog.app.data.remote.RoomMapper.toModel
import com.lolog.app.data.remote.RoomPostCreateDto
import com.lolog.app.data.remote.RoomRenameDto
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import retrofit2.HttpException

/**
 * 친구 방 실서버 구현(LOLog-docs spec/rooms.md, 서버 lolog-api #51 · 방장과 초대는 #59 · 방 관리와 나간 멤버는 #60).
 * 서버 주소에는 방 ID가 필요한데 [RoomRepository]의 내리기·좋아요는 만화 ID만 받아서,
 * 받은 만화를 기억해 두고(어느 방인지, 내가 좋아요를 눌렀는지) 그걸로 주소와 PUT·DELETE를 골라요.
 */
class RemoteRoomRepository(private val network: ApiClient) : RoomRepository {

    private val api get() = network.main

    /** 받은 만화(올린 만화 ID → 최근 모습). 화면은 늘 posts()를 먼저 불러요 */
    private val posts = ConcurrentHashMap<Long, RoomPost>()

    private fun remember(p: RoomPost): RoomPost = p.also { posts[it.id] = it }

    private fun known(postId: Long): RoomPost =
        posts[postId] ?: throw LologException(RoomErrorCodes.ROOM_POST_NOT_FOUND, "만화를 찾지 못했어요")

    override suspend fun rooms(): List<Room> = call { api.rooms() }.rooms.map { it.toModel() }

    override suspend fun createRoom(name: String, memberIds: List<Long>): Room =
        call { api.createRoom(RoomCreateDto(name.trim(), memberIds.distinct())) }.toModel()

    override suspend fun room(id: Long): Room = call { api.room(id) }.toModel()

    override suspend fun posts(roomId: Long): List<RoomPost> {
        val list = call { api.roomPosts(roomId) }.posts.map { remember(it.toModel()) }
        // 내려간 만화는 기억에서도 뺀다
        val ids = list.map { it.id }.toSet()
        posts.values.removeIf { it.roomId == roomId && it.id !in ids }
        return list
    }

    override suspend fun postComic(roomId: Long, comicId: Long): RoomPost =
        remember(call { api.postToRoom(roomId, RoomPostCreateDto(comicId)) }.toModel())

    override suspend fun removePost(postId: Long) {
        val p = known(postId)
        call { api.removeRoomPost(p.roomId, postId) }
        posts.remove(postId)
    }

    /** 좋아요 ↔ 취소. 내가 눌렀으면 DELETE, 아니면 PUT(서버는 두 번 눌러도 결과가 같아요) */
    override suspend fun toggleLike(postId: Long): RoomPost {
        val p = known(postId)
        val dto = call { if (p.likedByMe) api.unlikeRoomPost(p.roomId, postId) else api.likeRoomPost(p.roomId, postId) }
        return remember(dto.toModel())
    }

    override suspend fun messages(roomId: Long): List<RoomMessage> {
        val list = call { api.roomMessages(roomId) }.messages
        val byId = posts.values.filter { it.roomId == roomId }.associateBy { it.id }
        return list.mapNotNull { it.toModel(roomId, byId) }
    }

    override suspend fun sendComment(roomId: Long, postId: Long?, text: String): RoomMessage.Comment {
        val dto = call { api.commentInRoom(roomId, RoomCommentDto(text.trim(), postId)) }
        val byId = posts.values.filter { it.roomId == roomId }.associateBy { it.id }
        // 댓글 수는 화면이 만화 목록을 다시 불러 맞춰요. 여기서는 기억해 둔 만화에 +1만 해 둬요
        postId?.let { id -> posts[id]?.let { remember(it.copy(commentCount = it.commentCount + 1)) } }
        return dto.toModel(roomId, byId) as? RoomMessage.Comment
            ?: throw LologException(ErrorCodes.NETWORK, "댓글을 보내지 못했어요")
    }

    override suspend fun rename(roomId: Long, name: String): Room =
        call { api.renameRoom(roomId, RoomRenameDto(name.trim())) }.toModel()

    override suspend fun disband(roomId: Long) {
        call { api.disbandRoom(roomId) }
        posts.values.removeIf { it.roomId == roomId }
    }

    override suspend fun leave(roomId: Long, newOwnerId: Long?) {
        call { api.leaveRoom(roomId, newOwnerId) }
        posts.values.removeIf { it.roomId == roomId }
    }

    override suspend fun kick(roomId: Long, userId: Long) {
        call { api.kickRoomMember(roomId, userId) }
    }

    override suspend fun invite(roomId: Long, userIds: List<Long>): List<RoomInvite> =
        call { api.inviteToRoom(roomId, RoomInviteCreateDto(userIds.distinct())) }.invites.map { it.toModel() }

    override suspend fun cancelInvite(roomId: Long, inviteId: Long) {
        call { api.cancelRoomInvite(roomId, inviteId) }
    }

    override suspend fun myInvites(): List<RoomInvite> = call { api.roomInvites() }.invites.map { it.toModel() }

    override suspend fun acceptInvite(inviteId: Long): Room = call { api.acceptRoomInvite(inviteId) }.toModel()

    override suspend fun declineInvite(inviteId: Long) {
        call { api.declineRoomInvite(inviteId) }
    }

    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (e: HttpException) {
        val raw = e.response()?.errorBody()?.string()
        val err = raw?.let { runCatching { LologJson.decodeFromString(ErrorDto.serializer(), it) }.getOrNull() }
        throw LologException(err?.code?.ifBlank { null } ?: "HTTP_${e.code()}", err?.message?.ifBlank { null } ?: "요청을 처리하지 못했어요", err?.field)
    } catch (e: IOException) {
        throw LologException(ErrorCodes.NETWORK, "인터넷 연결을 확인해 주세요")
    }
}
