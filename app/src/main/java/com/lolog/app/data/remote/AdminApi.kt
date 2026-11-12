package com.lolog.app.data.remote

import com.lolog.app.data.model.AdminUser
import com.lolog.app.data.model.AdminUserPage
import com.lolog.app.data.model.AdminUserProfile
import com.lolog.app.data.model.Feedback
import com.lolog.app.data.model.FeedbackCategory
import com.lolog.app.data.model.FeedbackPage
import com.lolog.app.data.model.FeedbackStatus
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/** 관리자 계정 관리·의견 주소(LOLog-docs spec/admin.md 7장). 서버는 LOLog-auth·lolog-api의 AdminController */
interface AdminApi {
    /** 200 { users, nextCursor }. null인 쿼리는 보내지 않아요 */
    @GET("auth/admin/users")
    suspend fun users(@Query("q") q: String?, @Query("sort") sort: String?, @Query("cursor") cursor: String?): AdminUserPageDto

    /** 204 */
    @POST("auth/admin/users/{id}/suspend") suspend fun suspend(@Path("id") id: Long, @Body body: SuspendDto)

    /** 204 */
    @DELETE("auth/admin/users/{id}/suspend") suspend fun unsuspend(@Path("id") id: Long)

    /** 204 */
    @DELETE("auth/admin/users/{id}") suspend fun withdraw(@Path("id") id: Long)

    @GET("api/admin/users/{id}") suspend fun userProfile(@Path("id") id: Long): AdminUserProfileDto

    @GET("api/admin/feedback")
    suspend fun feedback(@Query("status") status: String?, @Query("cursor") cursor: String?): FeedbackPageDto

    @PATCH("api/admin/feedback/{id}") suspend fun markFeedback(@Path("id") id: Long, @Body body: FeedbackStatusDto): FeedbackDto

    /** 201 FeedbackItem */
    @POST("api/feedback") suspend fun sendFeedback(@Body body: FeedbackRequestDto): FeedbackDto
}

@Serializable
data class AdminUserDto(
    val userId: Long,
    val email: String = "",
    val createdAt: String = "",
    val role: String = "USER",
    val suspendedAt: String? = null,
    val suspendedUntil: String? = null,
    val suspendReason: String? = null,
)

@Serializable
data class AdminUserPageDto(val users: List<AdminUserDto> = emptyList(), val nextCursor: String? = null)

/** SuspendRequest: duration MONTH_1·MONTH_3·MONTH_6·PERMANENT, reason 1~200자 */
@Serializable
data class SuspendDto(val duration: String, val reason: String)

@Serializable
data class AdminUserProfileDto(val nickname: String? = null, val friendCode: String? = null)

@Serializable
data class FeedbackRequestDto(val category: String, val text: String, val appVersion: String, val deviceModel: String)

@Serializable
data class FeedbackStatusDto(val status: String)

@Serializable
data class FeedbackDto(
    val id: Long,
    val userId: Long? = null,
    val category: String = "OPINION",
    val text: String = "",
    val appVersion: String = "",
    val deviceModel: String = "",
    val status: String = "OPEN",
    val createdAt: String = "",
)

@Serializable
data class FeedbackPageDto(val items: List<FeedbackDto> = emptyList(), val nextCursor: String? = null)

/** 관리자 DTO → 앱 모델 */
object AdminMapper {
    fun AdminUserDto.toModel() = AdminUser(userId, email, createdAt, role, suspendedAt, suspendedUntil, suspendReason)
    fun AdminUserPageDto.toModel() = AdminUserPage(users.map { it.toModel() }, nextCursor)
    fun AdminUserProfileDto.toModel() = AdminUserProfile(nickname, friendCode)

    /** 모르는 종류·상태가 오면 의견·처리 전으로 봐요 */
    fun FeedbackDto.toModel() = Feedback(
        id = id,
        userId = userId,
        category = FeedbackCategory.entries.firstOrNull { it.name == category } ?: FeedbackCategory.OPINION,
        text = text,
        appVersion = appVersion,
        deviceModel = deviceModel,
        status = FeedbackStatus.entries.firstOrNull { it.name == status } ?: FeedbackStatus.OPEN,
        createdAt = createdAt,
    )

    fun FeedbackPageDto.toModel() = FeedbackPage(items.map { it.toModel() }, nextCursor)
}
