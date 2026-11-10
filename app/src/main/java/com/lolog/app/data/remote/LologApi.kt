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
    @GET("api/comics")
    suspend fun comics(@Query("cursor") cursor: String?, @Query("size") size: Int, @Query("sort") sort: String? = null): ComicPageDto
    @GET("api/comics/{id}") suspend fun comic(@Path("id") id: Long): ComicDto
    @DELETE("api/comics/{id}") suspend fun deleteComic(@Path("id") id: Long)

    // 친구·프로필·차단 (LOLog-docs spec/friends.md 2장)
    @GET("api/me/profile") suspend fun profile(): ProfileResponse
    @PATCH("api/me/profile") suspend fun updateProfile(@Body body: ProfileUpdateRequest): ProfileResponse
    /** 사진을 올려 프로필 캐릭터 만들기·다시 만들기(characters.md 4장). multipart photo(JPEG·PNG, 10MB 이하). 202 PROCESSING */
    @Multipart
    @PUT("api/me/profile/character")
    suspend fun uploadProfileCharacter(@Part photo: MultipartBody.Part): ProfileCharacterDto
    /** 204. 없어도 204 */
    @DELETE("api/me/profile/character") suspend fun deleteProfileCharacter()
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
    @GET("api/blocks") suspend fun blocks(): BlockListResponse
    /** 204. 차단한 적 없어도 204 */
    @DELETE("api/blocks/{userId}") suspend fun unblock(@Path("userId") userId: Long)

    // 화자 그림 (LOLog-docs spec/characters.md 4장)
    @GET("api/characters/defaults") suspend fun defaultCharacters(): DefaultCharacterListDto

    @PUT("api/devices/fcm-token") suspend fun putFcmToken(@Body body: FcmTokenDto)
    @HTTP(method = "DELETE", path = "api/devices/fcm-token", hasBody = true)
    suspend fun deleteFcmToken(@Body body: FcmTokenDto)
}
