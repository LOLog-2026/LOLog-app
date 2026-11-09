package com.lolog.app

import android.app.Application
import com.lolog.app.data.mock.MockLologRepository
import com.lolog.app.data.model.PushType
import com.lolog.app.notification.PushTokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LologApp : Application() {
    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notifier.createChannels()

        // 로그인하면(앱을 켰을 때 이미 로그인돼 있어도) 푸시 토큰을 서버에 등록해요(FR-NOTI)
        appScope.launch {
            container.tokens.loggedIn.collect { loggedIn ->
                if (loggedIn) launch(Dispatchers.IO) { PushTokens.register(this@LologApp, container.repository) }
            }
        }

        // 목업에서는 저장소가 흘려보내는 이벤트를 알림으로 띄워요. (실제는 LologMessagingService가 같은 일을 해요)
        appScope.launch {
            container.repository.pushEvents.collect { p ->
                val title = (container.repository as? MockLologRepository)
                    ?.takeIf { p.type == PushType.COMIC_DONE }
                    ?.let { repo -> p.comicId?.let { repo.cardForComic(it)?.summary } }
                container.notifier.show(p, title)
            }
        }
    }
}
