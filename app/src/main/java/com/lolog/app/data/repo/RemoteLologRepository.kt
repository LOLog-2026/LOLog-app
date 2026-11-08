package com.lolog.app.data.repo

import com.lolog.app.data.remote.*
import com.lolog.app.data.remote.ApiMapper.toDto
import com.lolog.app.data.remote.ApiMapper.toModel
import com.lolog.app.data.remote.ApiMapper.toDetail
import com.lolog.app.data.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import retrofit2.HttpException
import java.io.IOException

/** 실제 서버용 구현. DTO → 앱 모델 변환은 ApiMapper가 해요. */
class RemoteLologRepository(
    private val network: ApiClient,
    private val tokens: TokenStore,
) : LologRepository {

    override val isLoggedIn: Flow<Boolean> = tokens.loggedIn
    override val pushEvents: Flow<PushPayload> = emptyFlow() // 실제 푸시는 FCM 서비스가 받아요

    private val auth get() = network.auth
    private val api get() = network.main

    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (e: HttpException) {
        val raw = e.response()?.errorBody()?.string()
        val err = raw?.let { runCatching { LologJson.decodeFromString(ErrorDto.serializer(), it) }.getOrNull() }
        throw LologException(err?.code?.ifBlank { null } ?: "HTTP_${e.code()}", err?.message?.ifBlank { null } ?: "요청을 처리하지 못했어요", err?.field)
    } catch (e: IOException) {
        throw LologException(ErrorCodes.NETWORK, "인터넷 연결을 확인해 주세요")
    }

    private fun save(t: TokenDto) = tokens.save(t.accessToken, t.refreshToken)

    override suspend fun signup(email: String, password: String) = call { save(auth.signup(EmailAuthDto(email, password))) }
    override suspend fun login(email: String, password: String) = call { save(auth.login(EmailAuthDto(email, password))) }

    override suspend fun socialLogin(provider: SocialProvider, providerToken: String): Boolean = call {
        val t = auth.social(ApiMapper.providerPath(provider), SocialAuthDto(providerToken))
        save(t)
        t.isNewUser ?: false
    }

    override suspend fun logout() {
        // 로그아웃: 이 폰의 푸시 토큰부터 지우고(FR-AUTH-04) 리프레시 토큰을 무효화
        runCatching { tokens.fcmToken?.let { unregisterFcmToken(it) } }
        runCatching { tokens.refreshToken?.let { auth.logout(RefreshDto(it)) } }
        tokens.clear()
    }

    override suspend fun withdraw() = call { auth.withdraw(); tokens.clear() }
    override suspend fun me(): Account = call { auth.me().toModel() }
    override suspend fun linkSocial(provider: SocialProvider, providerToken: String) = call {
        ApiMapper.providers(auth.linkSocial(ApiMapper.providerPath(provider), SocialAuthDto(providerToken)).linkedProviders)
    }

    override suspend fun uploadRecording(part: RecordingPart) = call { api.uploadRecording(part.toDto()).toModel() }
    override suspend fun recordingStatus(recordingId: Long) = call { api.recording(recordingId).toModel() }
    override suspend fun analyzingRecordingId(): Long? = call {
        api.recordings(cursor = null, size = 1, status = "RECEIVED,DETECTING").recordings.firstOrNull()?.recordingId
    }

    override suspend fun cards(cursor: String?, size: Int) = call { api.cards(cursor, size).toModel() }
    override suspend fun card(id: Long) = call { api.card(id).toModel() }
    override suspend fun updateCard(id: Long, edit: CardEdit) = call { api.updateCard(id, with(ApiMapper) { edit.patchBody() }).toModel() }
    override suspend fun deleteCard(id: Long) = call { api.deleteCard(id) }

    override suspend fun requestComic(cardId: Long) = call { api.requestComic(cardId).toModel() }
    override suspend fun comics(cursor: String?, size: Int) = call { api.comics(cursor, size).toModel() }
    override suspend fun comic(id: Long) = call { api.comic(id).toDetail(id) }
    override suspend fun deleteComic(id: Long) = call { api.deleteComic(id) }

    override suspend fun registerFcmToken(token: String) = call {
        api.putFcmToken(FcmTokenDto(token))
        tokens.fcmToken = token
    }

    override suspend fun unregisterFcmToken(token: String) = call { api.deleteFcmToken(FcmTokenDto(token)) }
}
