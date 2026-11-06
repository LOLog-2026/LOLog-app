package com.lolog.app

import android.app.Application
import com.lolog.app.data.mock.MockLologRepository
import com.lolog.app.data.model.PushType
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

        // 목업에서는 저장소가 흘려보내는 이벤트를 알림으로 띄워요. (실제는 FCM 서비스가 같은 일을 해요)
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
