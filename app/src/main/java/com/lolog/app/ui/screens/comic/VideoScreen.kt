package com.lolog.app.ui.screens.comic

import com.lolog.app.util.VideoExport
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lolog.app.data.model.VideoStatus
import com.lolog.app.data.model.VideoSummary
import com.lolog.app.data.model.inProgress
import com.lolog.app.data.repo.LologException
import com.lolog.app.data.repo.LologRepository
import com.lolog.app.ui.components.GhostButton
import com.lolog.app.ui.components.ListErrorState
import com.lolog.app.ui.components.LologDialog
import com.lolog.app.ui.components.LologSnackbarHost
import com.lolog.app.ui.components.LologTopBar
import com.lolog.app.ui.components.PrimaryButton
import com.lolog.app.ui.components.TypingDots
import com.lolog.app.ui.components.errorText
import com.lolog.app.ui.navigation.LocalContainer
import com.lolog.app.ui.theme.LologColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/*
 * 쇼츠 한 편 보기(LOLog-docs video.md 2장). 컨텐츠 › 미디어 탭, 카드 자세히, VIDEO_DONE·VIDEO_FAILED 알림에서 들어와요.
 *  - 만드는 중(대기·목소리·그림): 단계 문구와 점 세 개. 5초마다 다시 받아요
 *  - 완성: 세로 영상(9:16) 재생. 주소는 10분짜리라 재생에 실패하면 한 번 새 주소를 받아 다시 틀어요
 *  - 실패: 안내. 카드에서 다시 만들 수 있어요(실패는 횟수에 안 세요)
 *  - 아래 "쇼츠 지우기"는 만드는 중에도 돼요(지워도 횟수는 돌아오지 않아요)
 *  - 완성이면 "폰에 저장": 동영상(Movies/LOLog)에 mp4로 넣어요. 공유는 저장한 파일로 해요(video.md)
 */

class VideoViewModel(private val repo: LologRepository, private val videoId: Long) : ViewModel() {
    data class State(
        val video: VideoSummary? = null,
        val error: String? = null,
        val busy: Boolean = false,
        val deleted: Boolean = false,
        val toast: String? = null,
        /** 재생에 실패해 새 주소를 받은 횟수. 한 번 받고도 실패하면 안내를 보여요 */
        val urlRetries: Int = 0,
        val playFailed: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /** 만드는 중이면 끝날 때까지 5초마다 다시 받아요 */
    fun watch() = viewModelScope.launch {
        while (true) {
            if (!load()) return@launch
            if (_state.value.video?.status?.inProgress != true) return@launch
            delay(5_000)
        }
    }

    private suspend fun load(): Boolean = try {
        val v = repo.video(videoId)
        _state.update { it.copy(video = v, error = null) }
        true
    } catch (e: LologException) {
        _state.update { it.copy(error = errorText(e)) }
        false
    }

    /** 재생 실패(주소 만료 등): 한 번은 새 주소를 받아 다시 틀고, 그래도 안 되면 안내 */
    fun playError() = viewModelScope.launch {
        if (_state.value.urlRetries >= 1) {
            _state.update { it.copy(playFailed = true) }
            return@launch
        }
        _state.update { it.copy(urlRetries = it.urlRetries + 1) }
        load()
    }

    fun retryPlay() = viewModelScope.launch {
        _state.update { it.copy(urlRetries = 0, playFailed = false) }
        load()
    }

    fun delete() = viewModelScope.launch {
        _state.update { it.copy(busy = true) }
        try {
            repo.deleteVideo(videoId)
            _state.update { it.copy(busy = false, deleted = true) }
        } catch (e: LologException) {
            _state.update { it.copy(busy = false, toast = errorText(e)) }
        }
    }

    fun consumed() = _state.update { it.copy(toast = null) }

    fun toast(msg: String) = _state.update { it.copy(toast = msg) }

    /** 저장하다 주소가 만료(403)되면 쇼츠를 다시 받아 새 videoUrl을 써요 */
    suspend fun freshVideoUrl(): String? =
        runCatching { repo.video(videoId) }.getOrNull()?.also { v -> _state.update { it.copy(video = v) } }?.videoUrl
}

@Composable
fun VideoScreen(videoId: Long, onBack: () -> Unit, onDeleted: () -> Unit) {
    val repo = LocalContainer.current.repository
    val vm = viewModel(key = "video-$videoId") { VideoViewModel(repo, videoId) }
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var askDelete by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    // 폰에 저장하는 동안 true. 버튼에 점이 돌고 다시 못 눌러요
    var saving by remember { mutableStateOf(false) }
    fun save(url: String) {
        if (saving) return
        saving = true
        scope.launch {
            try {
                val r = VideoExport.saveToMovies(context, url, "lolog_shorts_$videoId", container.network, vm::freshVideoUrl)
                vm.toast(VideoExport.message(r))
            } finally {
                saving = false
            }
        }
    }

    LaunchedEffect(Unit) { vm.watch() }
    LaunchedEffect(state.deleted) { if (state.deleted) onDeleted() }
    LaunchedEffect(state.toast) { state.toast?.let { vm.consumed(); snackbar.showSnackbar(it) } }

    val video = state.video
    Scaffold(
        topBar = { LologTopBar(video?.cardSummary?.ifBlank { null } ?: "쇼츠", onBack = onBack) },
        snackbarHost = { LologSnackbarHost(snackbar) },
        bottomBar = {
            if (video != null) {
                Column(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val url = video.videoUrl
                    if (video.status == VideoStatus.DONE && url != null) {
                        PrimaryButton("폰에 저장", { save(url) }, loading = saving, enabled = !state.busy)
                    }
                    GhostButton("쇼츠 지우기", { askDelete = true }, enabled = !state.busy && !saving)
                }
            }
        },
        containerColor = LologColors.Canvas,
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
            when {
                video == null && state.error != null -> ListErrorState(onRetry = { vm.watch() })
                video == null -> TypingDots()
                video.status == VideoStatus.DONE && !state.playFailed && video.videoUrl != null ->
                    VideoPlayer(video.videoUrl, onError = vm::playError)
                video.status == VideoStatus.DONE -> Message(
                    "영상을 불러오지 못했어요", "인터넷 연결을 확인하고 다시 시도해 주세요.",
                    button = "다시 시도", onButton = vm::retryPlay,
                )
                video.status == VideoStatus.FAILED -> Message(
                    "쇼츠를 만들지 못했어요", "카드에서 다시 만들 수 있어요. 실패는 횟수에 안 세요.",
                )
                else -> Making(video.status)
            }
        }
    }

    if (askDelete) {
        LologDialog(
            title = "이 쇼츠를 지울까요?",
            body = "지워도 이 카드로 만들 수 있는 횟수는 돌아오지 않아요.",
            confirmText = "지우기",
            danger = true,
            loading = state.busy,
            onConfirm = { vm.delete() },
            onDismiss = { if (!state.busy) askDelete = false },
        )
    }
}

/** 세로 9:16 영상. 처음부터 재생하고, 누르면 재생·멈춤 막대가 나와요 */
@Composable
private fun VideoPlayer(url: String, onError: () -> Unit) {
    // 주소가 바뀌면(새로 받은 10분 주소) 그 주소로 다시 틀어요
    var view by remember { mutableStateOf<VideoView?>(null) }
    DisposableEffect(Unit) { onDispose { view?.stopPlayback() } }
    AndroidView(
        factory = { ctx ->
            VideoView(ctx).apply {
                setMediaController(MediaController(ctx).also { it.setAnchorView(this) })
                setOnPreparedListener { it.isLooping = false; start() }
                view = this
            }
        },
        update = { v ->
            v.setOnErrorListener { _, _, _ -> onError(); true }
            if (v.tag != url) {
                v.tag = url
                v.setVideoURI(Uri.parse(url))
            }
        },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).aspectRatio(9f / 16f)
            .clip(RoundedCornerShape(18.dp)).background(Color.Black),
    )
}

/** 만드는 중: 단계마다 문구(video.md 1장 상태) */
@Composable
private fun Making(status: VideoStatus) {
    val step = when (status) {
        VideoStatus.QUEUED -> "곧 만들기 시작해요"
        VideoStatus.VOICE -> "대사를 목소리로 읽고 있어요"
        else -> "장면을 그리고 영상으로 합치고 있어요"
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TypingDots(dot = 8.dp)
        Spacer(Modifier.height(4.dp))
        Text(step, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = LologColors.Ink)
        Text("몇 분 걸려요. 다 되면 알림으로 알려 드려요.", fontSize = 13.sp, color = LologColors.Muted)
    }
}

@Composable
private fun Message(title: String, body: String, button: String? = null, onButton: () -> Unit = {}) {
    Column(Modifier.padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = LologColors.Ink)
        Spacer(Modifier.height(6.dp))
        Text(body, fontSize = 13.sp, color = LologColors.Muted, textAlign = TextAlign.Center)
        if (button != null) {
            Spacer(Modifier.height(16.dp))
            PrimaryButton(button, onButton)
        }
    }
}
