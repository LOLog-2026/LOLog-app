package com.lolog.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import com.lolog.app.data.model.PushPayload
import com.lolog.app.notification.Notifier.Companion.readPayload
import com.lolog.app.ui.navigation.LocalContainer
import com.lolog.app.ui.navigation.LologNavHost
import com.lolog.app.ui.theme.LologTheme

class MainActivity : ComponentActivity() {
    /** 알림을 눌러 들어왔을 때 갈 곳 */
    private val pendingPush = mutableStateOf<PushPayload?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingPush.value = intent?.readPayload()
        val container = (application as LologApp).container
        // 시연용: 가짜 데이터 모드에서는 로그인 화면을 건너뛰고 바로 들어가요(가짜 토큰으로 자동 로그인).
        // 처음 깔았을 때도 로그인 없이 권한 안내 → 홈. 설정에서 로그아웃하면 로그인 화면은 볼 수 있어요.
        // 실제 서버 모드(USE_MOCK=false)에서는 원래대로 로그인 화면이 떠요.
        if (BuildConfig.USE_MOCK && !container.tokens.loggedIn.value) {
            container.tokens.save("mock-access", "mock-refresh")
        }
        setContent {
            LologTheme {
                CompositionLocalProvider(LocalContainer provides container) {
                    LologNavHost(
                        pendingPush = pendingPush.value,
                        onPushHandled = { pendingPush.value = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.readPayload()?.let { pendingPush.value = it }
    }
}
