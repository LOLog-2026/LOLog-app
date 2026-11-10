package com.lolog.app.ui.social

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.lolog.app.BuildConfig
import com.lolog.app.data.model.ErrorCodes
import com.lolog.app.data.model.SocialProvider
import com.lolog.app.data.repo.LologException
import com.lolog.app.ui.components.errorText
import com.navercorp.nid.NidOAuth
import com.navercorp.nid.core.data.datastore.NidOAuthInitializingCallback
import com.navercorp.nid.core.data.errorcode.NidOAuthErrorCode
import com.navercorp.nid.oauth.util.NidOAuthCallback
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 각 회사 SDK로 로그인해 서버에 보낼 providerToken을 받아요(LOLog-docs api-dto SocialLoginRequest).
 *  - 구글: Credential Manager(Sign in with Google)의 ID 토큰. serverClientId는 "웹" 클라이언트 ID
 *  - 네이버: 네이버 로그인 SDK의 액세스 토큰
 * 목업 모드에서는 SDK를 부르지 않고 목업 토큰을 돌려줘요(에뮬레이터에서 키 없이 시험).
 * 키는 local.properties → BuildConfig(app/build.gradle.kts). 비어 있으면 그 회사 버튼은 "준비 중"이에요.
 */
class SocialSignIn(private val activity: Activity) {

    /** 버튼을 켤 수 있는지. 목업은 늘 켜고, 실제 서버는 키가 있을 때만 */
    fun isReady(provider: SocialProvider): Boolean = BuildConfig.USE_MOCK || when (provider) {
        SocialProvider.GOOGLE -> BuildConfig.LOLOG_GOOGLE_WEB_CLIENT_ID.isNotBlank()
        SocialProvider.NAVER -> BuildConfig.LOLOG_NAVER_CLIENT_ID.isNotBlank() && BuildConfig.LOLOG_NAVER_CLIENT_SECRET.isNotBlank()
    }

    /**
     * 그 회사 로그인 창을 띄워 토큰을 받아요.
     * 사용자가 창을 닫으면 null(화면은 조용히 원래대로), 그 밖의 SDK 실패는 LologException.
     */
    suspend fun token(provider: SocialProvider): String? {
        if (BuildConfig.USE_MOCK) return "mock-${provider.name.lowercase()}-token"
        return when (provider) {
            SocialProvider.GOOGLE -> google()
            SocialProvider.NAVER -> naver()
        }
    }

    private suspend fun google(): String? {
        val option = GetSignInWithGoogleOption.Builder(BuildConfig.LOLOG_GOOGLE_WEB_CLIENT_ID).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val credential = try {
            CredentialManager.create(activity).getCredential(activity, request).credential
        } catch (e: GetCredentialCancellationException) {
            return null
        } catch (e: GetCredentialException) {
            throw sdkError("google", e.type, e)
        }
        if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            throw sdkError("google", credential.type, null)
        }
        return try {
            GoogleIdTokenCredential.createFrom(credential.data).idToken
        } catch (e: GoogleIdTokenParsingException) {
            throw sdkError("google", "parse", e)
        }
    }

    private suspend fun naver(): String? {
        initNaver()
        return suspendCancellableCoroutine { cont ->
            NidOAuth.requestLogin(activity, object : NidOAuthCallback {
                override fun onSuccess() {
                    val token = NidOAuth.getAccessToken()
                    if (token.isNullOrBlank()) cont.resumeWithException(sdkError("naver", "no_token", null))
                    else cont.resume(token)
                }

                override fun onFailure(errorCode: String, errorDescription: String) {
                    // 창을 닫거나 동의하지 않으면 사용자가 그만둔 것이라 조용히 돌아가요
                    val last = NidOAuth.getLastErrorCode()
                    if (last == NidOAuthErrorCode.CLIENT_USER_CANCEL || last == NidOAuthErrorCode.SERVER_ERROR_ACCESS_DENIED) cont.resume(null)
                    else cont.resumeWithException(sdkError("naver", "$errorCode $errorDescription", null))
                }
            })
        }
    }

    /** 네이버 SDK 초기화(처음 한 번). 초기화는 비동기라 끝날 때까지 기다려요 */
    private suspend fun initNaver() {
        if (NidOAuth.isInitialized()) return
        suspendCancellableCoroutine { cont ->
            NidOAuth.initialize(
                activity.applicationContext, BuildConfig.LOLOG_NAVER_CLIENT_ID, BuildConfig.LOLOG_NAVER_CLIENT_SECRET, NAVER_APP_NAME,
                object : NidOAuthInitializingCallback {
                    override fun onSuccess() = cont.resume(Unit)
                    override fun onFailure(e: Exception) = cont.resumeWithException(sdkError("naver", "init", e))
                },
            )
        }
    }

    /** SDK 실패. 화면에는 errorText의 기본 문장("문제가 생겼어요…")이 나가고, 자세한 건 로그로만 남겨요 */
    private fun sdkError(provider: String, detail: String, cause: Throwable?): LologException {
        Log.w(TAG, "$provider 로그인 실패: $detail", cause)
        return LologException(SOCIAL_SDK_ERROR, "$provider 로그인 실패: $detail")
    }

    companion object {
        private const val TAG = "SocialSignIn"
        /** 앱 내부용 오류 코드: 회사 SDK에서 토큰을 받지 못함 */
        const val SOCIAL_SDK_ERROR = "SOCIAL_SDK_ERROR"
        private const val NAVER_APP_NAME = "LOLog"
    }
}

/**
 * 소셜 로그인·연결 오류 문장(LOLog-docs api-dto 오류 표).
 *  - 401 INVALID_TOKEN: 그 회사가 토큰을 거절, 이메일을 주지 않음, 구글 이메일 미인증
 *  - 500: 구글·네이버 서버 오류
 * 나머지(ACCOUNT_EXISTS, SOCIAL_ALREADY_LINKED, 인터넷 끊김 등)는 공용 errorText를 따라요.
 */
fun socialErrorText(e: Throwable): String {
    val code = (e as? LologException)?.code
    return when {
        code == ErrorCodes.INVALID_TOKEN -> "로그인할 수 없는 계정이에요. 다른 방법으로 로그인해 주세요"
        code == ErrorCodes.INTERNAL_ERROR || code?.startsWith("HTTP_5") == true -> "잠시 뒤 다시 시도해 주세요"
        else -> errorText(e)
    }
}

/** 화면에서 SocialSignIn을 하나 만들어요. Credential Manager·네이버 SDK는 Activity가 있어야 창을 띄워요 */
@Composable
fun rememberSocialSignIn(): SocialSignIn {
    val context = LocalContext.current
    return remember(context) { SocialSignIn(context.findActivity()) }
}

private tailrec fun Context.findActivity(): Activity = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> error("Activity를 찾지 못했어요")
}
