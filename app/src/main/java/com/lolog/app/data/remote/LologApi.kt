package com.lolog.app.data.remote

import kotlinx.serialization.json.JsonObject
import retrofit2.http.*

/*
 * 주소 목록. 주소나 메서드가 바뀌면 여기만 고쳐요.
 * LOLog-docs spec/api-spec.md·api-dto.md(2026-11-06) 기준. 요청·응답 모양은 ApiDtos.kt.
 */

/** 인증 서비스 (/auth). Nginx가 /auth를 인증 서비스로 넘겨요. */
interface AuthApi {
    @POST("auth/signup") suspend fun signup(@Body body: EmailAuthDto): TokenDto
    @POST("auth/login") suspend fun login(@Body body: EmailAuthDto): TokenDto
    @POST("auth/social/{provider}")
    suspend fun social(@Path("provider") provider: String, @Body body: SocialAuthDto): TokenDto
    @POST("auth/refresh") suspend fun refresh(@Body body: RefreshDto): TokenDto
    @POST("auth/logout") suspend fun logout(@Body body: RefreshDto)
    @DELETE("auth/me") suspend fun withdraw()
    @GET("auth/me") suspend fun me(): MeDto
    @POST("auth/me/social/{provider}")
    suspend fun linkSocial(@Path("provider") provider: String, @Body body: SocialAuthDto): LinkedProvidersDto
}

/** 메인 백엔드 (/api). Authorization: Bearer <액세스 토큰> 필요 */
interface MainApi {
    @POST("api/recordings") suspend fun uploadRecording(@Body body: UploadRecordingRequest): UploadRecordingResponse
    @GET("api/recordings/{id}") suspend fun recording(@Path("id") id: Long): RecordingStatusDto
    /** 앱을 다시 켰을 때 "분석 중" 녹음 찾기. status 예: "RECEIVED,DETECTING" */
    @GET("api/recordings")
    suspend fun recordings(@Query("cursor") cursor: String?, @Query("size") size: Int, @Query("status") status: String?): RecordingPageDto

    @GET("api/cards") suspend fun cards(@Query("cursor") cursor: String?, @Query("size") size: Int): CardPageDto
    @GET("api/cards/{id}") suspend fun card(@Path("id") id: Long): CardDetailDto
    @PATCH("api/cards/{id}") suspend fun updateCard(@Path("id") id: Long, @Body body: JsonObject): CardDetailDto
    @DELETE("api/cards/{id}") suspend fun deleteCard(@Path("id") id: Long)

    /** 요청 본문 없음 */
    @POST("api/cards/{id}/comics") suspend fun requestComic(@Path("id") cardId: Long): ComicAcceptedDto
    @GET("api/comics") suspend fun comics(@Query("cursor") cursor: String?, @Query("size") size: Int): ComicPageDto
    @GET("api/comics/{id}") suspend fun comic(@Path("id") id: Long): ComicDto
    @DELETE("api/comics/{id}") suspend fun deleteComic(@Path("id") id: Long)

    @PUT("api/devices/fcm-token") suspend fun putFcmToken(@Body body: FcmTokenDto)
    @HTTP(method = "DELETE", path = "api/devices/fcm-token", hasBody = true)
    suspend fun deleteFcmToken(@Body body: FcmTokenDto)
}
