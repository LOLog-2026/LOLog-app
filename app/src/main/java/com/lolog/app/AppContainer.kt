package com.lolog.app

import android.content.Context
import com.lolog.app.data.remote.ApiClient
import com.lolog.app.data.mock.MockLologRepository
import com.lolog.app.data.mock.MockRecordingController
import com.lolog.app.data.mock.MockRooms
import com.lolog.app.data.repo.LologRepository
import com.lolog.app.data.repo.RemoteLologRepository
import com.lolog.app.data.repo.RoomRepository
import com.lolog.app.data.repo.TokenStore
import com.lolog.app.notification.Notifier
import com.lolog.app.recording.RealRecordingController
import com.lolog.app.recording.RecordingController
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/** 앱 전체에서 하나씩만 쓰는 것들. DI 라이브러리 없이 손으로 묶었어요. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val tokens = TokenStore(appContext)
    val network: ApiClient? = if (BuildConfig.USE_MOCK) null else ApiClient(tokens)

    val repository: LologRepository =
        if (BuildConfig.USE_MOCK) MockLologRepository(tokens) else RemoteLologRepository(network!!, tokens)

    /** 친구 방: 명세·서버가 아직 없어서 목업에서만 있어요(실서버면 null, 화면도 숨겨요) */
    val rooms: RoomRepository? = if (BuildConfig.USE_MOCK) MockRooms.of(repository) else null

    // 화면은 RecordingController 약속만 봐요(지원 ↔ 서현).
    // 목업이면 가짜 녹음, 아니면 마이크·STT·업로드를 하는 실제 녹음이에요.
    val recorder: RecordingController =
        if (BuildConfig.USE_MOCK) {
            MockRecordingController(repository)
        } else {
            RealRecordingController(
                appContext,
                send = { network!!.main.uploadRecording(it) },
                // PUT /api/recordings/{id}/audio/{seq}: 파트 file(audio/aac) + 폼 값 startMs·endMs (audio.md 6장)
                sendAudio = { recordingId, audio ->
                    val text = "text/plain".toMediaType()
                    network!!.main.uploadAudio(
                        recordingId = recordingId,
                        seq = audio.seq,
                        file = MultipartBody.Part.createFormData("file", audio.file.name, audio.file.asRequestBody("audio/aac".toMediaType())),
                        startMs = audio.startMs.toString().toRequestBody(text),
                        endMs = audio.endMs.toString().toRequestBody(text),
                    )
                },
            )
        }

    val notifier = Notifier(appContext)

    private val prefs = appContext.getSharedPreferences("lolog_app", Context.MODE_PRIVATE)
    var permissionAsked: Boolean
        get() = prefs.getBoolean("perm_asked", false)
        set(v) { prefs.edit().putBoolean("perm_asked", v).apply() }

    /** 녹음 버튼 화면의 "음성도 저장" 체크. 기본은 꺼짐이고 한 번 고른 값을 폰이 기억해요(audio.md 1장) */
    var saveAudio: Boolean
        get() = prefs.getBoolean("save_audio", false)
        set(v) { prefs.edit().putBoolean("save_audio", v).apply() }
}
