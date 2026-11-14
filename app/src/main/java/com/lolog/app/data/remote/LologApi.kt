package com.lolog.app.data.remote

import kotlinx.serialization.json.JsonObject
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.*

/*
 * 주소 목록. 주소나 메서드가 바뀌면 여기만 고쳐요.
 * LOLog-docs spec/api-spec.md·api-dto.md(2026-11-06) 기준. 요청·응답 모양은 ApiDtos.kt.
 */

/** 인증 서비스 (/auth). Nginx가 /auth를 인증 서비스로 넘겨요. */
interface AuthApi {
    /** 204 본문 없음 */
    @POST("auth/email/code") suspend fun requestEmailCode(@Body body: EmailCodeDto)
    @POST("auth/signup") suspend fun signup(@Body body: SignupDto): TokenDto
    @POST("auth/login") suspend fun login(@Body body: EmailAuthDto): TokenDto
    @POST("auth/social/{provider}")
    suspend fun social(@Path("provider") provider: String, @Body body: SocialAuthDto): TokenDto
    @POST("auth/refresh") suspend fun refresh(@Body body: RefreshDto): TokenDto
    @POST("auth/logout") suspend fun logout(@Body body: RefreshDto)
    @DELETE("auth/me") suspend fun withdraw()
    @GET("auth/me") suspend fun me(): MeDto
    @POST("auth/me/social/{provider}")
    suspend fun linkSocial(@Path("provider") provider: String, @Body body: SocialAuthDto): LinkedProvidersDto
    /** 연결 끊기. 연결돼 있지 않아도 200. 비밀번호 없는 계정의 마지막 연결이면 409 LAST_LOGIN_METHOD(api-spec.md) */
    @DELETE("auth/me/social/{provider}")
    suspend fun unlinkSocial(@Path("provider") provider: String): LinkedProvidersDto

    // 비밀번호 찾기·바꾸기 (FR-AUTH-07, LOLog-docs #45)
    /** 204 본문 없음. 가입 여부와 관계없이 늘 204 */
    @POST("auth/password/reset/code") suspend fun requestPasswordResetCode(@Body body: PasswordResetCodeDto)
    /** 204 본문 없음. 토큰은 주지 않아요 */
    @POST("auth/password/reset") suspend fun resetPassword(@Body body: PasswordResetDto)
    /** 다른 기기 로그인은 끊고 이 기기에는 새 토큰 한 벌을 줘요 */
    @POST("auth/me/password") suspend fun changePassword(@Body body: PasswordChangeDto): TokenDto
}

/** 메인 백엔드 (/api). Authorization: Bearer <액세스 토큰> 필요 */
interface MainApi {
    @POST("api/recordings") suspend fun uploadRecording(@Body body: UploadRecordingRequest): UploadRecordingResponse
    /**
     * 음성 파일 하나 올리기(명세 audio.md 6장). 204 본문 없음. 같은 seq를 다시 올리면 덮어써요.
     * file은 audio/aac(ADTS), startMs·endMs는 녹음 시작부터의 ms(0 ≤ startMs < endMs)를 글자 그대로 담아요.
     */
    @Multipart
    @PUT("api/recordings/{id}/audio/{seq}")
    suspend fun uploadAudio(
        @Path("id") recordingId: Long,
        @Path("seq") seq: Int,
        @Part file: MultipartBody.Part,
        @Part("startMs") startMs: RequestBody,
        @Part("endMs") endMs: RequestBody,
    )
    @GET("api/recordings/{id}") suspend fun recording(@Path("id") id: Long): RecordingStatusDto
    /** 앱을 다시 켰을 때 "분석 중" 녹음 찾기. status 예: "RECEIVED,DETECTING" */
    @GET("api/recordings")
    suspend fun recordings(@Query("cursor") cursor: String?, @Query("size") size: Int, @Query("status") status: String?): RecordingPageDto

    /** sort: "oldest"면 오래된순, null(빼면)이면 최신순 */
    @GET("api/cards")
    suspend fun cards(@Query("cursor") cursor: String?, @Query("size") size: Int, @Query("sort") sort: String? = null): CardPageDto
    @GET("api/cards/{id}") suspend fun card(@Path("id") id: Long): CardDetailDto
    @PATCH("api/cards/{id}") suspend fun updateCard(@Path("id") id: Long, @Body body: JsonObject): CardDetailDto
    @DELETE("api/cards/{id}") suspend fun deleteCard(@Path("id") id: Long)

    /** 요청 본문 없음 */
    @POST("api/cards/{id}/comics") suspend fun requestComic(@Path("id") cardId: Long): ComicAcceptedDto

    // 쇼츠 (LOLog-docs spec/video.md 2장). 409 VIDEO_IN_PROGRESS·VIDEO_LIMIT, 없으면 404 VIDEO_NOT_FOUND
    @POST("api/cards/{id}/videos") suspend fun requestVideo(@Path("id") cardId: Long): VideoAcceptedDto
    @GET("api/videos")
    suspend fun videos(@Query("cursor") cursor: String?, @Query("size") size: Int, @Query("sort") sort: String? = null): VideoPageDto
    @GET("api/videos/{id}") suspend fun video(@Path("id") id: Long): VideoDto

    // 글로 카드 만들기 (texts.md 6장)
    @POST("api/texts") suspend fun createText(@Body body: TextCreateDto): TextAcceptedDto
    @GET("api/texts/{id}") suspend fun text(@Path("id") id: Long): TextDto
    @POST("api/blogs/check") suspend fun checkBlog(@Body body: BlogCheckRequestDto): BlogCheckDto
    @POST("api/blogs") suspend fun addBlog(@Body body: BlogCreateDto): BlogDto
    @GET("api/blogs") suspend fun blogs(): BlogListDto
    @PATCH("api/blogs/{id}") suspend fun updateBlog(@Path("id") id: Long, @Body body: BlogUpdateDto): BlogDto
    @DELETE("api/blogs/{id}") suspend fun deleteBlog(@Path("id") id: Long)
    @GET("api/blogs/{id}/posts") suspend fun blogPosts(@Path("id") id: Long, @Query("cursor") cursor: String?): BlogPostPageDto
    @DELETE("api/videos/{id}") suspend fun deleteVideo(@Path("id") id: Long)
    @GET("api/comics")
    suspend fun comics(@Query("cursor") cursor: String?, @Query("size") size: Int, @Query("sort") sort: String? = null): ComicPageDto
    @GET("api/comics/{id}") suspend fun comic(@Path("id") id: Long): ComicDto
    @DELETE("api/comics/{id}") suspend fun deleteComic(@Path("id") id: Long)

    // 친구·프로필·차단 (LOLog-docs spec/friends.md 2장)
    @GET("api/me/profile") suspend fun profile(): ProfileResponse
    @PATCH("api/me/profile") suspend fun updateProfile(@Body body: ProfileUpdateRequest): ProfileResponse
    /** 사진을 올려 프로필 캐릭터 만들기·다시 만들기(characters.md 4장). multipart photo(JPEG·PNG, 5MB 이하). 202 PROCESSING */
    @Multipart
    @PUT("api/me/profile/character")
    suspend fun uploadProfileCharacter(@Part photo: MultipartBody.Part): ProfileCharacterDto
    /** 204. 없어도 204 */
    @DELETE("api/me/profile/character") suspend fun deleteProfileCharacter()
    /** 프로필 캐릭터 목소리 고르기(characters.md FR-CHAR-04, 초안). 본문은 ApiMapper.voiceBody(). 204 */
    @PATCH("api/me/profile/character/voice") suspend fun setProfileCharacterVoice(@Body body: JsonObject)
    /** 201. 상대가 이미 나에게 요청했으면 바로 친구가 되고 status가 ACCEPTED */
    @POST("api/friend-requests") suspend fun sendFriendRequest(@Body body: FriendRequestCreate): FriendRequestResponse
    /** direction: received·sent (필수) */
    @GET("api/friend-requests") suspend fun friendRequests(@Query("direction") direction: String): FriendRequestListResponse
    @POST("api/friend-requests/{id}/accept") suspend fun acceptFriendRequest(@Path("id") id: Long): FriendItem
    /** 204. 받은 요청 거절, 보낸 요청 취소 */
    @DELETE("api/friend-requests/{id}") suspend fun deleteFriendRequest(@Path("id") id: Long)
    @GET("api/friends") suspend fun friends(): FriendListResponse
    /** 204. 친구가 아니어도 204 */
    @DELETE("api/friends/{userId}") suspend fun removeFriend(@Path("userId") userId: Long)
    /** 204 */
    @POST("api/blocks") suspend fun block(@Body body: BlockCreate)
    // 방에서 그 사람 글 숨기기 바꾸기(friends.md 2장, 초안 · 서버 lolog-api #61)
    @PATCH("api/blocks/{userId}") suspend fun updateBlock(@Path("userId") userId: Long, @Body body: BlockUpdate)
    @GET("api/blocks") suspend fun blocks(): BlockListResponse
    /** 204. 차단한 적 없어도 204 */
    @DELETE("api/blocks/{userId}") suspend fun unblock(@Path("userId") userId: Long)

    // 화자 그림 (LOLog-docs spec/characters.md 4장)
    @GET("api/characters/defaults") suspend fun defaultCharacters(): DefaultCharacterListDto

    // 친구와 같이 쓰는 방 (LOLog-docs spec/rooms.md 2장). 멤버가 아니면 404 ROOM_NOT_FOUND
    @GET("api/rooms") suspend fun rooms(): RoomListDto
    @POST("api/rooms") suspend fun createRoom(@Body body: RoomCreateDto): RoomItemDto
    @GET("api/rooms/{id}") suspend fun room(@Path("id") id: Long): RoomItemDto
    // 방장이 다른 멤버가 있는데 newOwnerId 없이 나가면 409 NEW_OWNER_REQUIRED(초안). null이면 쿼리를 빼요
    @DELETE("api/rooms/{id}/members/me") suspend fun leaveRoom(@Path("id") id: Long, @Query("newOwnerId") newOwnerId: Long?)
    // 방 관리(rooms.md "방장과 초대", 초안 · 서버 lolog-api #60). 방장만(403 ROOM_OWNER_ONLY)
    @PATCH("api/rooms/{id}") suspend fun renameRoom(@Path("id") id: Long, @Body body: RoomRenameDto): RoomItemDto
    @DELETE("api/rooms/{id}") suspend fun disbandRoom(@Path("id") id: Long)
    @DELETE("api/rooms/{id}/members/{userId}") suspend fun kickRoomMember(@Path("id") id: Long, @Path("userId") userId: Long)
    @GET("api/rooms/{id}/posts") suspend fun roomPosts(@Path("id") id: Long): RoomPostListDto
    @POST("api/rooms/{id}/posts") suspend fun postToRoom(@Path("id") id: Long, @Body body: RoomPostCreateDto): RoomPostDto
    @DELETE("api/rooms/{id}/posts/{postId}") suspend fun removeRoomPost(@Path("id") id: Long, @Path("postId") postId: Long)
    @PUT("api/rooms/{id}/posts/{postId}/like") suspend fun likeRoomPost(@Path("id") id: Long, @Path("postId") postId: Long): RoomPostDto
    @DELETE("api/rooms/{id}/posts/{postId}/like") suspend fun unlikeRoomPost(@Path("id") id: Long, @Path("postId") postId: Long): RoomPostDto
    /** cursor가 null이면 최근 줄부터. 응답의 nextCursor로 그 앞 줄을 받아요(rooms.md 2장) */
    @GET("api/rooms/{id}/messages")
    suspend fun roomMessages(@Path("id") id: Long, @Query("cursor") cursor: String? = null): RoomMessageListDto
    @POST("api/rooms/{id}/messages") suspend fun commentInRoom(@Path("id") id: Long, @Body body: RoomCommentDto): RoomMessageDto
    // 방장과 초대(rooms.md "방장과 초대", 초안). 초대·취소는 방장만(403 ROOM_OWNER_ONLY)
    @POST("api/rooms/{id}/invites") suspend fun inviteToRoom(@Path("id") id: Long, @Body body: RoomInviteCreateDto): RoomInviteListDto
    @DELETE("api/rooms/{id}/invites/{inviteId}") suspend fun cancelRoomInvite(@Path("id") id: Long, @Path("inviteId") inviteId: Long)
    @GET("api/room-invites") suspend fun roomInvites(): RoomInviteListDto
    @POST("api/room-invites/{inviteId}/accept") suspend fun acceptRoomInvite(@Path("inviteId") inviteId: Long): RoomItemDto
    @DELETE("api/room-invites/{inviteId}") suspend fun declineRoomInvite(@Path("inviteId") inviteId: Long)

    // 알림함 (LOLog-docs PR #59 spec/inbox.md 5장). 최근 것이 위, 한 번에 20개
    /** tab: ALL·NOTICE·NOTIFICATION. cursor는 받은 nextCursor 그대로 */
    @GET("api/inbox") suspend fun inbox(@Query("tab") tab: String, @Query("cursor") cursor: String?): InboxPageDto
    @GET("api/inbox/unread") suspend fun inboxUnread(): InboxUnreadDto
    /** 204. kind는 notice·notification. 없으면 404 NOTICE_NOT_FOUND·NOTIFICATION_NOT_FOUND */
    @POST("api/inbox/{kind}/{id}/read") suspend fun readInbox(@Path("kind") kind: String, @Path("id") id: Long)
    /** 204. 지금까지 온 것을 모두 읽음 */
    @POST("api/inbox/read") suspend fun readAllInbox()

    // 앱 안 새 소식 점 (LOLog-docs spec/inbox.md 4·5장)
    @GET("api/me/badges") suspend fun badges(): BadgesDto
    /** 204 본문 없음. kind는 cards·comics·profileCharacter·friends·rooms·inbox */
    @POST("api/me/badges/{kind}/read") suspend fun readBadge(@Path("kind") kind: String)

    // 관리자 공지 (LOLog-docs spec/admin.md 7장). 관리자가 아니면 403 FORBIDDEN, 없는 공지는 404 NOTICE_NOT_FOUND
    /** 201 Notice */
    @POST("api/admin/notices") suspend fun createNotice(@Body body: NoticeCreateDto): NoticeDto
    /** 바꾼 것만 보내요. 200 Notice */
    @PATCH("api/admin/notices/{id}") suspend fun updateNotice(@Path("id") id: Long, @Body body: NoticeUpdateDto): NoticeDto
    /** 204 */
    @DELETE("api/admin/notices/{id}") suspend fun deleteNotice(@Path("id") id: Long)

    @PUT("api/devices/fcm-token") suspend fun putFcmToken(@Body body: FcmTokenDto)
    @HTTP(method = "DELETE", path = "api/devices/fcm-token", hasBody = true)
    suspend fun deleteFcmToken(@Body body: FcmTokenDto)
}
