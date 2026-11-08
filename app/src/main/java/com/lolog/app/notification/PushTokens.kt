package com.lolog.app.notification

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.lolog.app.BuildConfig
import com.lolog.app.data.repo.LologRepository
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * FCM 토큰을 서버에 등록해요(FR-NOTI: 로그인할 때와 토큰이 바뀔 때, PUT /api/devices/fcm-token).
 * google-services.json 없이 빌드했으면 Firebase가 없으니 아무것도 하지 않아요.
 * 토큰 값은 로그에 남기지 않아요.
 */
object PushTokens {
    private const val TAG = "LologPush"

    /** 이 빌드에 Firebase 설정이 들어 있는지 */
    fun available(context: Context): Boolean = FirebaseApp.getApps(context).isNotEmpty()

    /** 로그인 상태에서 부르면 지금 토큰을 받아 서버에 등록해요. 목업에서는 가짜 토큰을 넣어요 */
    suspend fun register(context: Context, repository: LologRepository) {
        if (BuildConfig.USE_MOCK) {
            runCatching { repository.registerFcmToken("mock-fcm-token") }
            return
        }
        if (!available(context)) {
            Log.w(TAG, "Firebase 설정(google-services.json)이 없어 푸시를 쓰지 않아요")
            return
        }
        val token = currentToken() ?: return
        send(repository, token)
    }

    /** 새 토큰을 서버에 보내요. 실패해도 다음 로그인·앱 실행 때 다시 보내요 */
    suspend fun send(repository: LologRepository, token: String) {
        try {
            repository.registerFcmToken(token)
            Log.i(TAG, "푸시 토큰을 등록했어요")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "푸시 토큰을 등록하지 못했어요: ${e.javaClass.simpleName}")
        }
    }

    private suspend fun currentToken(): String? = suspendCancellableCoroutine { cont ->
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (!task.isSuccessful) Log.w(TAG, "푸시 토큰을 받지 못했어요", task.exception)
            cont.resume(if (task.isSuccessful) task.result else null)
        }
    }
}
