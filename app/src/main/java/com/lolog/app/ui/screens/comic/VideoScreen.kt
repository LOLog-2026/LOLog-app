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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
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
import com.lolog.app.data.model.CardDetail
import com.lolog.app.data.model.VideoStatus
import com.lolog.app.data.model.VideoSummary
import com.lolog.app.data.model.inProgress
import com.lolog.app.data.repo.LologException
import com.lolog.app.data.repo.LologRepository
import com.lolog.app.ui.components.GhostButton
import com.lolog.app.ui.components.IndeterminateBar
import com.lolog.app.ui.components.ListErrorState
import com.lolog.app.ui.components.LologDialog
import com.lolog.app.ui.components.LologNoticeDialog
import com.lolog.app.ui.components.LologSheet
import com.lolog.app.ui.components.LologSnackbarHost
import com.lolog.app.ui.components.LologTopBar
import com.lolog.app.ui.components.PrimaryButton
import com.lolog.app.ui.components.TypingDots
import com.lolog.app.ui.components.errorText
import com.lolog.app.ui.navigation.LocalContainer
import com.lolog.app.ui.theme.LologColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/*
 * 쇼츠 한 편 보기(LOLog-docs video.md 2장). 컨텐츠 › 미디어 탭, 카드 자세히, VIDEO_DONE·VIDEO_FAILED 알림에서 들어와요.
 *  - 만드는 중(대기·목소리·그림): 4컷 만드는 중과 같은 판(단계 알약 · 그동안 대화 다시 보기). 5초마다 다시 받아요
 *  - 완성: 세로 영상(9:16) 재생. 주소는 10분짜리라 재생에 실패하면 한 번 새 주소를 받아 다시 틀어요
 *  - 실패: 안내. 카드에서 다시 만들 수 있어요(실패는 횟수에 안 세요)
 *  - 만화 보기 화면에 맞춰요(video.md 9장, #95): 지우기는 위 오른쪽 ⋮ 메뉴 "쇼츠 지우기"(만드는 중·실패도. 지워도 횟수는 안 돌아와요)
 *  - 완성이면 아래 "내보내기" 하나 → 만화와 같은 줄의 시트: 폰에 저장(Movies/LOLog) · 카카오톡으로 보내기 · 인스타그램 · 다른 앱으로 공유
 *    공유는 mp4를 받아 그 앱의 공유 화면으로 넘겨요(공유용 임시 파일만, 갤러리에 안 남김). 받는 중에는 창(진행 막대 · [취소]),
 *    그 앱이 없으면 창, 못 받으면 띠. 보냈는지는 알 수 없어서 돌아와도 띠는 없어요
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
        /** 그동안 대화 다시 보기에 쓸 카드. 카드가 지워졌거나 못 받으면 null(그 칸만 빼요) */
        val card: CardDetail? = null,
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

    /** 카드는 만드는 중일 때 한 번만 받아요 */
    private var cardTried = false

    private suspend fun load(): Boolean = try {
        val v = repo.video(videoId)
        _state.update { it.copy(video = v, error = null) }
        val cardId = v.cardId
        if (v.status.inProgress && cardId != null && !cardTried) {
            cardTried = true
            runCatching { repo.card(cardId) }.onSuccess { c -> _state.update { it.copy(card = c) } }
        }
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
                VideoExport.message(r)?.let(vm::toast)
            } finally {
                saving = false
            }
        }
    }
    var menu by remember { mutableStateOf(false) }
    var exportSheet by remember { mutableStateOf(false) }
    // 공유할 영상을 받는 동안의 작업. 있으면 받는 중 창을 띄우고 [취소]로 멈춰요
    var shareJob by remember { mutableStateOf<Job?>(null) }
    val sharing = shareJob != null
    // 고른 앱이 폰에 없을 때 창에 보여 줄 앱 이름
    var missingApp by remember { mutableStateOf<String?>(null) }
    var sharingTo by remember { mutableStateOf(VideoExport.Target.OTHER) }
    fun share(url: String, target: VideoExport.Target) {
        if (saving || sharing) return
        sharingTo = target
        shareJob = scope.launch {
            val me = coroutineContext[Job]
            try {
                val r = VideoExport.share(context, target, url, videoId, container.network, vm::freshVideoUrl)
                if (r == VideoExport.Result.NO_APP) missingApp = target.appName
                else VideoExport.message(r, sharing = true)?.let(vm::toast)
            } finally {
                // 취소 뒤 바로 다시 누른 새 작업은 지우지 않아요
                if (shareJob === me) shareJob = null
            }
        }
    }
    fun cancelShare() {
        shareJob?.cancel()
        shareJob = null
    }

    LaunchedEffect(Unit) { vm.watch() }
    LaunchedEffect(state.deleted) { if (state.deleted) onDeleted() }
    LaunchedEffect(state.toast) { state.toast?.let { vm.consumed(); snackbar.showSnackbar(it) } }

    val video = state.video
    // 완성·실패가 아니면 만드는 중 화면(모르는 상태도 지금처럼 만드는 중으로)
    val making = video != null && video.status != VideoStatus.DONE && video.status != VideoStatus.FAILED
    // 공유·저장·지우는 중에는 내보내기·지우기를 다시 못 눌러요
    val idle = !state.busy && !saving && !sharing
    Scaffold(
        topBar = {
            LologTopBar(video?.cardSummary?.ifBlank { null } ?: "쇼츠", onBack = onBack) {
                // 만화 화면처럼 ⋮ 메뉴에서 지워요. 만드는 중·실패도 지울 수 있어요
                if (video != null) {
                    Box {
                        IconButton(onClick = { menu = true }, enabled = idle) { Icon(Icons.Default.MoreVert, "더 보기") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("쇼츠 지우기", color = LologColors.Rec) }, onClick = { menu = false; askDelete = true })
                        }
                    }
                }
            }
        },
        snackbarHost = { LologSnackbarHost(snackbar) },
        bottomBar = {
            val url = video?.videoUrl
            if (video?.status == VideoStatus.DONE && url != null && !state.playFailed) {
                // 만화 보기처럼 아래는 "내보내기" 하나
                Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    PrimaryButton("내보내기", { if (idle) exportSheet = true }, loading = saving || sharing)
                }
            }
        },
        // 만드는 중은 4컷 만드는 중처럼 흰 바탕에 연한 판
        containerColor = if (making) LologColors.Paper else LologColors.Canvas,
    ) { pad ->
        if (video != null && making) {
            Making(video.status, state.card, Modifier.padding(pad))
        } else Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
            when {
                video == null && state.error != null -> ListErrorState(onRetry = { vm.watch() })
                video == null -> TypingDots()
                video.status == VideoStatus.DONE && !state.playFailed && video.videoUrl != null ->
                    VideoPlayer(video.videoUrl, onError = vm::playError)
                video.status == VideoStatus.DONE -> Message(
                    "영상을 불러오지 못했어요", "인터넷 연결을 확인하고 다시 시도해 주세요.",
                    button = "다시 시도", onButton = vm::retryPlay,
                )
                else -> Message(
                    "쇼츠를 만들지 못했어요", "에피소드에서 다시 만들 수 있어요. 실패는 횟수에 안 세요.",
                )
            }
        }
    }

    val exportUrl = video?.videoUrl
    if (exportSheet && exportUrl != null) {
        // 만화 내보내기 시트와 같은 줄(video.md 9장, 줄 이름은 초안). 시트는 바로 닫고 받는 동안은 창으로 기다려요
        LologSheet(onDismiss = { exportSheet = false }) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("쇼츠 내보내기", fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                ShareRow(Icons.Outlined.Download, "폰에 저장") { exportSheet = false; save(exportUrl) }
                ShareRow(Icons.Outlined.ChatBubbleOutline, "카카오톡으로 보내기") { exportSheet = false; share(exportUrl, VideoExport.Target.KAKAO) }
                ShareRow(Icons.Outlined.CameraAlt, "인스타그램") { exportSheet = false; share(exportUrl, VideoExport.Target.INSTAGRAM) }
                ShareRow(Icons.Outlined.Share, "다른 앱으로 공유") { exportSheet = false; share(exportUrl, VideoExport.Target.OTHER) }
            }
        }
    }

    if (sharing) {
        LologNoticeDialog(
            title = "영상을 준비하고 있어요",
            body = if (sharingTo.pkg == null) "쇼츠 파일을 폰에 받는 중이에요. 다 받으면 공유할 앱을 고를 수 있어요."
            else "쇼츠 파일을 폰에 받는 중이에요. 다 받으면 ${sharingTo.appName}이 열려요.",
            buttonText = "취소",
            onButton = ::cancelShare,
            primary = false,
        ) { IndeterminateBar(Modifier.padding(top = 4.dp)) }
    }

    missingApp?.let { app ->
        LologNoticeDialog(
            title = "$app 앱이 없어요",
            body = "${app}으로 보내려면 $app 앱이 필요해요. 설치한 뒤 다시 눌러 주세요.",
            buttonText = "확인",
            onButton = { missingApp = null },
        )
    }

    if (askDelete) {
        LologDialog(
            title = "이 쇼츠를 지울까요?",
            body = "지워도 이 에피소드로 만들 수 있는 횟수는 돌아오지 않아요.",
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

/** 쇼츠 단계 알약 4개: 요청 받음 › 목소리 › 그림 › 완성 */
private val VIDEO_STEPS = listOf("요청 받음", "목소리", "그림", "완성")

/** 쇼츠 상태 → 지금 단계 알약 번호(video.md 1장 상태) */
internal fun videoStepIndex(status: VideoStatus): Int = when (status) {
    VideoStatus.QUEUED, VideoStatus.UNKNOWN -> 0
    VideoStatus.VOICE -> 1
    VideoStatus.DRAWING -> 2
    VideoStatus.DONE, VideoStatus.FAILED -> 3
}

/** 만드는 중: 4컷 만드는 중(ComicScreen Progress)과 같은 판 + 그동안 대화 다시 보기 */
@Composable
private fun Making(status: VideoStatus, card: CardDetail?, modifier: Modifier) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        // Canvas 판: 점 3개 + 문구 + 단계 알약
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(LologColors.Canvas).padding(vertical = 20.dp, horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TypingDots(dot = 6.dp)
            Spacer(Modifier.height(12.dp))
            Text("쇼츠를 만들고 있어요", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = LologColors.Ink)
            Spacer(Modifier.height(4.dp))
            Text(
                "몇 분 걸려요. 앱을 닫아도 돼요. 다 되면 알려 드려요.",
                fontSize = 12.5.sp, color = LologColors.Muted, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            StepPills(videoStepIndex(status), VIDEO_STEPS)
        }
        if (card != null && card.lines.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            ConversationReplay(card, running = status.inProgress)
        }
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
