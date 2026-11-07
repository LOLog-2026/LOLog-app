package com.lolog.app

import android.content.Context
import com.lolog.app.data.remote.ApiClient
import com.lolog.app.data.mock.MockLologRepository
import com.lolog.app.data.mock.MockRecordingController
import com.lolog.app.data.repo.LologRepository
import com.lolog.app.data.repo.RemoteLologRepository
import com.lolog.app.data.repo.TokenStore
import com.lolog.app.notification.Notifier
import com.lolog.app.recording.RecordingController

/** 앱 전체에서 하나씩만 쓰는 것들. DI 라이브러리 없이 손으로 묶었어요. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val tokens = TokenStore(appContext)
    val network: ApiClient? = if (BuildConfig.USE_MOCK) null else ApiClient(tokens)

    val repository: LologRepository =
        if (BuildConfig.USE_MOCK) MockLologRepository(tokens) else RemoteLologRepository(network!!, tokens)

    // 화면은 RecordingController 약속만 봐요(지원 ↔ 서현).
    // TODO(지원 님 PR #3 머지 뒤): USE_MOCK=false일 때 실제 녹음으로 바꾸기
    //   RealRecordingController(appContext, send = { network!!.main.uploadRecording(it) })
    val recorder: RecordingController = MockRecordingController(repository)

    val notifier = Notifier(appContext)

    private val prefs = appContext.getSharedPreferences("lolog_app", Context.MODE_PRIVATE)
    var permissionAsked: Boolean
        get() = prefs.getBoolean("perm_asked", false)
        set(v) { prefs.edit().putBoolean("perm_asked", v).apply() }
}
