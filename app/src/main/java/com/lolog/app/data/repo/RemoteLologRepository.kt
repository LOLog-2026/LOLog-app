package com.lolog.app.data.repo

import com.lolog.app.data.remote.*
import com.lolog.app.data.remote.ApiMapper.toDto
import com.lolog.app.data.remote.ApiMapper.toModel
import com.lolog.app.data.remote.ApiMapper.toDetail
import com.lolog.app.data.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException
import java.io.IOException
import java.util.Locale

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

    override suspend fun requestEmailCode(email: String) = call { auth.requestEmailCode(EmailCodeDto(email)) }
    override suspend fun signup(email: String, password: String, code: String) = call { save(auth.signup(SignupDto(email, password, code))) }
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

    override suspend fun requestPasswordResetCode(email: String) = call { auth.requestPasswordResetCode(PasswordResetCodeDto(email)) }
    override suspend fun resetPassword(email: String, code: String, newPassword: String) =
        call { auth.resetPassword(PasswordResetDto(email, code, newPassword)) }
    override suspend fun changePassword(currentPassword: String, newPassword: String) =
        call { save(auth.changePassword(PasswordChangeDto(currentPassword, newPassword))) }

    override suspend fun uploadRecording(part: RecordingPart) = call { api.uploadRecording(part.toDto()).toModel() }
    override suspend fun recordingStatus(recordingId: Long) = call { api.recording(recordingId).toModel() }
    override suspend fun analyzingRecordingId(): Long? = call {
        api.recordings(cursor = null, size = 1, status = "RECEIVED,DETECTING").recordings.firstOrNull()?.recordingId
    }

    override suspend fun cards(cursor: String?, size: Int, sort: SortOrder) = call { api.cards(cursor, size, sort.query).toModel() }
    override suspend fun card(id: Long) = call { api.card(id).toModel() }
    override suspend fun updateCard(id: Long, edit: CardEdit) = call { api.updateCard(id, with(ApiMapper) { edit.patchBody() }).toModel() }
    override suspend fun deleteCard(id: Long) = call { api.deleteCard(id) }

    override suspend fun requestComic(cardId: Long) = call { api.requestComic(cardId).toModel() }
    override suspend fun comics(cursor: String?, size: Int, sort: SortOrder) = call { api.comics(cursor, size, sort.query).toModel() }
    override suspend fun comic(id: Long) = call { api.comic(id).toDetail(id) }
    override suspend fun deleteComic(id: Long) = call { api.deleteComic(id) }

    override suspend fun profile() = call { api.profile().toModel() }
    override suspend fun updateNickname(nickname: String) = call { api.updateProfile(ProfileUpdateRequest(nickname.trim())).toModel() }
    override suspend fun uploadProfileCharacter(photo: ByteArray) = call {
        val part = MultipartBody.Part.createFormData("photo", "photo.jpg", photo.toRequestBody("image/jpeg".toMediaType()))
        api.uploadProfileCharacter(part).toModel()
    }
    override suspend fun deleteProfileCharacter() = call { api.deleteProfileCharacter() }
    override suspend fun sendFriendRequest(code: String) =
        call { api.sendFriendRequest(FriendRequestCreate(code.trim().uppercase(Locale.ROOT))).toModel() }
    override suspend fun friendRequests(direction: FriendRequestDirection) =
        call { api.friendRequests(direction.query).requests.map { it.toModel() } }
    override suspend fun acceptFriendRequest(id: Long) = call { api.acceptFriendRequest(id).toModel() }
    override suspend fun deleteFriendRequest(id: Long) = call { api.deleteFriendRequest(id) }
    override suspend fun friends() = call { api.friends().friends.map { it.toModel() } }
    override suspend fun removeFriend(userId: Long) = call { api.removeFriend(userId) }
    override suspend fun block(userId: Long) = call { api.block(BlockCreate(userId)) }
    override suspend fun blocks() = call { api.blocks().blocks.map { it.toModel() } }
    override suspend fun unblock(userId: Long) = call { api.unblock(userId) }

    override suspend fun defaultCharacters() = call { api.defaultCharacters().characters.map { it.toModel() } }

    override suspend fun registerFcmToken(token: String) = call {
        api.putFcmToken(FcmTokenDto(token))
        tokens.fcmToken = token
    }

    override suspend fun unregisterFcmToken(token: String) = call { api.deleteFcmToken(FcmTokenDto(token)) }
}
