package com.lolog.app.notification

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.lolog.app.LologApp
import com.lolog.app.notification.Notifier.Companion.payloadOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * FCM 받기. 서버는 data 메시지만 보내요(api-dto 3장, notification 블록 없음).
 * 그래서 앱이 꺼져 있거나 백그라운드여도 여기로 오고, 알림은 Notifier가 직접 만들어요.
 */
class LologMessagingService : FirebaseMessagingService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(message: RemoteMessage) {
        val payload = payloadOf(message.data)
        if (payload == null) {
            // 모르는 type은 무시해요. 데이터 내용은 로그에 남기지 않아요
            Log.w(TAG, "알 수 없는 푸시를 받았어요")
            return
        }
        (application as LologApp).container.notifier.show(payload)
        // 앱이 켜져 있으면 보고 있는 화면이 새로 받고, 새 소식 점도 켜요(#67 7번)
        PushBus.emit(payload)
    }

    /** 토큰이 바뀌면 다시 등록해요(FR-NOTI). 로그인 전이면 로그인할 때 등록해요 */
    override fun onNewToken(token: String) {
        val container = (application as LologApp).container
        if (!container.tokens.loggedIn.value) return
        scope.launch { PushTokens.send(container.repository, token) }
    }

    private companion object {
        const val TAG = "LologPush"
    }
}
