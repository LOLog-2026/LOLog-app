package com.lolog.app.data.repo

import com.lolog.app.data.model.AdminUserSort
import com.lolog.app.data.model.ErrorCodes
import com.lolog.app.data.model.FeedbackCategory
import com.lolog.app.data.model.FeedbackStatus
import com.lolog.app.data.model.SuspendDuration
import com.lolog.app.data.remote.AdminMapper.toModel
import com.lolog.app.data.remote.ApiClient
import com.lolog.app.data.remote.ErrorDto
import com.lolog.app.data.remote.FeedbackRequestDto
import com.lolog.app.data.remote.FeedbackStatusDto
import com.lolog.app.data.remote.LologJson
import com.lolog.app.data.remote.SuspendDto
import retrofit2.HttpException
import java.io.IOException

/** 관리자 계정 관리·의견 실서버 구현(LOLog-auth /auth/admin, lolog-api #63) */
class RemoteAdminRepository(private val network: ApiClient) : AdminRepository {

    private val api get() = network.admin

    override suspend fun users(q: String?, sort: AdminUserSort, cursor: String?) =
        call { api.users(q?.trim()?.ifEmpty { null }, sort.name, cursor) }.toModel()

    override suspend fun userProfile(userId: Long) = call { api.userProfile(userId) }.toModel()

    override suspend fun suspend(userId: Long, duration: SuspendDuration, reason: String) =
        call { api.suspend(userId, SuspendDto(duration.name, reason.trim())) }

    override suspend fun unsuspend(userId: Long) = call { api.unsuspend(userId) }

    override suspend fun withdraw(userId: Long) = call { api.withdraw(userId) }

    override suspend fun feedback(status: FeedbackStatus?, cursor: String?) =
        call { api.feedback(status?.name, cursor) }.toModel()

    override suspend fun markFeedback(id: Long, status: FeedbackStatus) =
        call { api.markFeedback(id, FeedbackStatusDto(status.name)) }.toModel()

    override suspend fun sendFeedback(category: FeedbackCategory, text: String, appVersion: String, deviceModel: String) =
        call { api.sendFeedback(FeedbackRequestDto(category.name, text.trim(), appVersion, deviceModel)) }.toModel()

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
