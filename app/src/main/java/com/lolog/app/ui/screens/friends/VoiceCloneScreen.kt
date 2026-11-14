package com.lolog.app.ui.screens.friends

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lolog.app.data.model.ErrorCodes
import com.lolog.app.data.model.VOICE_CLONE_SENTENCE
import com.lolog.app.data.repo.LologException
import com.lolog.app.data.repo.LologRepository
import com.lolog.app.recording.RecordingState
import com.lolog.app.ui.components.*
import com.lolog.app.ui.navigation.LocalContainer
import com.lolog.app.ui.theme.LologColors
import com.lolog.app.ui.theme.LologShape
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/*
 * 내 목소리 만들기(시안 2판 2~7, 기준 LOLog-docs PR #70 병합 전). 한 화면 안에서 단계를 바꿔요:
 *   INTRO 안내·동의 → READ 문장 읽기 → REVIEW 들어 보기 → MAKING 만드는 중
 *  - 동의: 필수 1개(원본 보관, 명세 문구 그대로), 선택 1개(친구 쇼츠 → shareWithFriends)
 *  - 문장 하나를 마이크로 녹음, 네모를 누르면 끝. 10초면 저절로 끝나요(명세: 5~10초 권장)
 *  - 5초보다 짧으면 "조금 짧아요" → 다시 녹음. 소리 크기는 워커가 검사해요
 *  - REVIEW의 "내 목소리 만들기"에서 PUT 한 번(audio, consent=true, shareWithFriends, text) → MAKING
 *  - 녹음은 VoiceSampleRecorder(MediaRecorder, m4a)로 cacheDir에만. 올렸거나 화면을 나가면 지워요
 *  - 대화 녹음 중에는 마이크와 만들기 버튼을 꺼요(서버도 409 RECORDING_IN_PROGRESS)
 *  서버가 아직 없어 목업만(실제 서버는 NOT_READY)
 */

/** 필수 동의(원본 보관)를 체크해야 "동의하고 녹음하기"가 켜져요. 친구 쇼츠 동의는 선택 */
internal fun introReady(agreeKeep: Boolean): Boolean = agreeKeep

/** 필수 동의 문구(명세 #70 그대로) */
const val VOICE_AGREE_KEEP =
    "목소리 만들기에서 녹음한 파일만 보관해요. 내 목소리를 만드는 데만 쓰고 다른 사람에게는 공개되지 않아요. 내 목소리를 지우거나 탈퇴하면 같이 지워져요."
/** 선택 동의 문구(친구 쇼츠) */
const val VOICE_AGREE_FRIENDS = "친구 쇼츠에도 내 목소리가 나오는 데 동의해요. 안 하면 친구 쇼츠에선 기본 목소리로 나와요"
const val VOICE_TAKE_SHORT = "조금 짧아요. 5초 넘게 천천히 읽어 주세요"
/** 만드는 중 안내(명세 #70 "조금 시간이 걸려요") */
const val VOICE_CLONE_MAKING = "조금 시간이 걸려요. 다른 화면으로 가도 괜찮아요. 다 되면 알림으로 알려 드릴게요."

class VoiceCloneViewModel(private val repo: LologRepository, private val app: Context) : ViewModel() {
    enum class Step { INTRO, READ, REVIEW, MAKING }

    data class State(
        val step: Step = Step.INTRO,
        /** 필수 동의(원본 보관) */
        val agreeKeep: Boolean = false,
        /** 선택 동의(친구 쇼츠) */
        val shareWithFriends: Boolean = false,
        val recording: Boolean = false,
        /** 녹음 중이면 지금까지, 끝났으면 그 녹음 길이 */
        val elapsedMs: Long = 0,
        /** 물결: 최근 소리 크기(0~1) */
        val levels: List<Float> = emptyList(),
        /** 빨간 글(다시 녹음) */
        val problem: String? = null,
        val uploading: Boolean = false,
        val toast: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val recorder = VoiceSampleRecorder(app)
    private var ticker: Job? = null
    /** 사용자가 친구 쇼츠 동의를 직접 바꿨는지(바꿨으면 지난 값으로 덮지 않아요) */
    private var shareTouched = false

    val file: File get() = File(voiceCloneDir(app), "sample.m4a")

    init {
        // 다시 녹음이면 지난번 친구 쇼츠 동의를 그대로 체크해 둬요
        viewModelScope.launch {
            val share = try { repo.getVoiceClone()?.shareWithFriends } catch (e: LologException) { null } ?: return@launch
            if (!shareTouched) _state.update { it.copy(shareWithFriends = share) }
        }
    }

    fun setAgreeKeep(v: Boolean) = _state.update { it.copy(agreeKeep = v) }
    fun setShareWithFriends(v: Boolean) {
        shareTouched = true
        _state.update { it.copy(shareWithFriends = v) }
    }

    /** 동의하고 마이크 권한을 받았을 때 */
    fun beginReading() = _state.update { it.copy(step = Step.READ, elapsedMs = 0, problem = null) }

    fun startTake() {
        val s = _state.value
        if (s.recording || s.uploading || s.step != Step.READ) return
        file.delete()
        if (!recorder.start(file)) {
            _state.update { it.copy(toast = "녹음을 시작하지 못했어요") }
            return
        }
        _state.update { it.copy(recording = true, elapsedMs = 0, levels = emptyList(), problem = null) }
        val t0 = SystemClock.elapsedRealtime()
        ticker = viewModelScope.launch {
            while (true) {
                delay(100)
                val amp = recorder.amplitude()
                val elapsed = SystemClock.elapsedRealtime() - t0
                _state.update { it.copy(elapsedMs = elapsed, levels = (it.levels + amp / 32767f).takeLast(WAVE_BARS)) }
                if (elapsed >= VOICE_SAMPLE_MAX_MS) { stopTake(); break }
            }
        }
    }

    /** 네모를 눌렀거나 10초가 됨: 5초를 넘었으면 들어 보기로, 아니면 다시 녹음 */
    fun stopTake() {
        val s = _state.value
        if (!s.recording) return
        ticker?.cancel()
        ticker = null
        val saved = recorder.stop()
        if (saved && longEnough(s.elapsedMs)) {
            _state.update { it.copy(recording = false, step = Step.REVIEW, problem = null) }
        } else {
            file.delete()
            // 막대는 그 길이에 둬서 얼마나 짧았는지 보여요
            _state.update { it.copy(recording = false, problem = VOICE_TAKE_SHORT) }
        }
    }

    /** 다른 앱으로 가거나 나갈 때: 녹음 중이던 것은 버려요 */
    fun cancelTake() {
        if (!_state.value.recording) return
        ticker?.cancel()
        ticker = null
        recorder.stop()
        file.delete()
        _state.update { it.copy(recording = false, elapsedMs = 0, levels = emptyList()) }
    }

    /** REVIEW에서 "다시 녹음" */
    fun retake() {
        file.delete()
        _state.update { it.copy(step = Step.READ, elapsedMs = 0, levels = emptyList(), problem = null) }
    }

    /** "내 목소리 만들기": 녹음과 읽은 문장, 동의를 한 번에 올려요 */
    fun make() = viewModelScope.launch {
        val s = _state.value
        if (s.uploading || s.step != Step.REVIEW) return@launch
        _state.update { it.copy(uploading = true) }
        try {
            repo.uploadVoiceClone(file, s.shareWithFriends)
            clearFiles()
            _state.update { it.copy(uploading = false, step = Step.MAKING) }
        } catch (e: LologException) {
            _state.update { it.copy(uploading = false, toast = cloneError(e)) }
        }
    }

    /** 녹음을 그만둠: 녹음 파일을 지워요 */
    fun abandon() {
        cancelTake()
        clearFiles()
    }

    fun toastShown() = _state.update { it.copy(toast = null) }

    private fun clearFiles() {
        voiceCloneDir(app).deleteRecursively()
    }

    override fun onCleared() {
        cancelTake()
        clearFiles()
    }

    companion object {
        const val WAVE_BARS = 28
    }
}

private fun cloneError(e: LologException): String = when (e.code) {
    ErrorCodes.NOT_READY -> VOICE_CLONE_NOT_READY
    ErrorCodes.RECORDING_IN_PROGRESS -> VOICE_CLONE_RECORDING_NOTE
    ErrorCodes.VOICE_CLONE_IN_PROGRESS -> "내 목소리를 만드는 중이에요. 다 되면 다시 녹음할 수 있어요"
    ErrorCodes.VOICE_CLONE_UNAVAILABLE -> "지금은 내 목소리를 만들 수 없어요. 잠시 후 다시 해 주세요"
    ErrorCodes.CHARACTER_NOT_FOUND -> "내 캐릭터를 먼저 만들어 주세요"
    else -> errorText(e)
}

/** 0:04 */
private fun clock(ms: Long): String {
    val sec = ms / 1000
    return "%d:%02d".format(sec / 60, sec % 60)
}

@Composable
fun VoiceCloneScreen(onBack: () -> Unit, onDone: () -> Unit) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val vm = viewModel { VoiceCloneViewModel(container.repository, context.applicationContext) }
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val player = rememberVoicePlayer(onError = { scope.launch { snackbar.showSnackbar("녹음을 재생하지 못했어요") } })
    var denied by remember { mutableStateOf(false) }
    var askQuit by remember { mutableStateOf(false) }

    // 대화 녹음 중이면 마이크를 꺼요
    val recState by container.recorder.state.collectAsState()
    val convoRecording = recState is RecordingState.Recording || recState is RecordingState.Uploading
    LaunchedEffect(convoRecording) { if (convoRecording) vm.cancelTake() }

    LaunchedEffect(state.toast) {
        val msg = state.toast ?: return@LaunchedEffect
        snackbar.showSnackbar(msg)
        vm.toastShown()
    }
    // 다른 앱으로 가면 녹음 중이던 것은 버려요
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) vm.cancelTake() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.beginReading() else denied = true
    }
    fun agreed() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (granted) vm.beginReading() else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    // 녹음 중이거나 들어 보기에서 나가면 확인 창(녹음은 지워져요)
    fun back() {
        when {
            state.step == VoiceCloneViewModel.Step.MAKING -> onDone()
            state.recording || state.step == VoiceCloneViewModel.Step.REVIEW -> askQuit = true
            else -> onBack()
        }
    }
    BackHandler { back() }

    if (denied) {
        // 대화 녹음 화면과 같은 권한 안내
        Column(Modifier.fillMaxSize().background(LologColors.Paper).systemBarsPadding().padding(24.dp), verticalArrangement = Arrangement.Center) {
            CenterMessage("마이크 권한이 필요해요", "녹음하려면 휴대폰 설정에서 LOLog의 마이크를 허용해 주세요.")
            Spacer(Modifier.height(16.dp))
            GhostButton("돌아가기", onBack)
        }
        return
    }

    Scaffold(
        containerColor = LologColors.Paper,
        snackbarHost = { LologSnackbarHost(snackbar) },
        topBar = { LologTopBar("내 목소리 만들기", onBack = ::back) },
        bottomBar = {
            Box(Modifier.fillMaxWidth().background(LologColors.Paper).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
                when (state.step) {
                    VoiceCloneViewModel.Step.INTRO -> PrimaryButton(
                        "동의하고 녹음하기", onClick = ::agreed, enabled = introReady(state.agreeKeep),
                    )
                    VoiceCloneViewModel.Step.READ -> MicArea(state, convoRecording, onStart = { player.stop(); vm.startTake() }, onStop = vm::stopTake)
                    VoiceCloneViewModel.Step.REVIEW -> Column {
                        if (convoRecording) {
                            VoiceCloneRecordingNote()
                            Spacer(Modifier.height(12.dp))
                        }
                        PrimaryButton(
                            "내 목소리 만들기", onClick = { player.stop(); vm.make() },
                            enabled = !convoRecording, loading = state.uploading,
                        )
                    }
                    VoiceCloneViewModel.Step.MAKING -> GhostButton("나가기", onDone)
                }
            }
        },
    ) { pad ->
        val body = Modifier.padding(pad).fillMaxSize()
        when (state.step) {
            VoiceCloneViewModel.Step.INTRO -> IntroStep(state, vm, body)
            VoiceCloneViewModel.Step.READ -> ReadStep(state, body)
            VoiceCloneViewModel.Step.REVIEW -> ReviewStep(state, vm, player, body)
            VoiceCloneViewModel.Step.MAKING -> MakingStep(body)
        }
    }

    if (askQuit) {
        LologDialog(
            title = "녹음을 그만할까요?",
            body = "녹음한 파일은 지워져요",
            confirmText = "그만하기", danger = true,
            onConfirm = { askQuit = false; player.stop(); vm.abandon(); onBack() },
            onDismiss = { askQuit = false },
        )
    }
}

/** 시안 2: 안내와 동의 2개(필수·선택) */
@Composable
private fun IntroStep(state: VoiceCloneViewModel.State, vm: VoiceCloneViewModel, modifier: Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text("문장 하나를 읽으면\n내 목소리를 만들어요", fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 30.sp, color = LologColors.Ink)
        Spacer(Modifier.height(16.dp))
        Column(
            Modifier.fillMaxWidth().clip(LologShape.sm).background(LologColors.Canvas).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            listOf(
                "조용한 곳에서 평소 말투로 읽어 주세요",
                "${VOICE_SAMPLE_MIN_MS / 1000}~${VOICE_SAMPLE_MAX_MS / 1000}초면 돼요",
                "내 캐릭터가 쇼츠에서 이 목소리로 말해요",
                "우리 서버에서 만들어요. 다른 회사로 보내지 않아요",
            ).forEach { Text("· $it", fontSize = 13.sp, color = LologColors.Muted, lineHeight = 19.sp) }
        }
        Spacer(Modifier.height(16.dp))
        AgreeRow(VOICE_AGREE_KEEP, "필수", state.agreeKeep, vm::setAgreeKeep)
        AgreeRow(VOICE_AGREE_FRIENDS, "선택", state.shareWithFriends, vm::setShareWithFriends)
    }
}

/** 체크 한 줄. 줄 전체를 누르면 바뀌고, 화면 읽기는 필수·선택과 글을 같이 읽어요 */
@Composable
private fun AgreeRow(text: String, tag: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(LologShape.sm)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Checkbox(
            checked = checked, onCheckedChange = null,
            colors = CheckboxDefaults.colors(checkedColor = LologColors.Accent, uncheckedColor = LologColors.Line),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            "($tag) $text", fontSize = 13.sp, color = LologColors.Ink, lineHeight = 19.sp, modifier = Modifier.weight(1f),
        )
    }
}

/** 읽을 문장(연한 코랄 카드) */
@Composable
private fun SentenceCard(fontSize: Int, modifier: Modifier = Modifier) {
    Text(
        VOICE_CLONE_SENTENCE, fontSize = fontSize.sp, fontWeight = FontWeight.Bold, lineHeight = (fontSize * 1.45f).sp,
        color = LologColors.Ink, textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth().clip(LologShape.md).background(LologColors.AccentSoft).padding(horizontal = 20.dp, vertical = 24.dp),
    )
}

/** 시안 3~5: 문장 읽기. 녹음했으면 길이 막대(5초 선)를 보여 줘요 */
@Composable
private fun ReadStep(state: VoiceCloneViewModel.State, modifier: Modifier) {
    Column(modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text("아래 문장을 평소 말투로 소리 내어 읽어 주세요", fontSize = 12.sp, color = LologColors.Muted)
        Spacer(Modifier.height(12.dp))
        SentenceCard(fontSize = 19)
        if (state.recording) {
            Spacer(Modifier.height(28.dp))
            Wave(state.levels, Modifier.fillMaxWidth().height(40.dp))
        }
        if (state.recording || state.elapsedMs > 0) {
            Spacer(Modifier.height(if (state.recording) 16.dp else 28.dp))
            TakeBar(state.elapsedMs)
        }
        if (state.recording) {
            Spacer(Modifier.height(8.dp))
            Text(
                clock(state.elapsedMs), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = LologColors.Accent,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 녹음 길이 막대: 0초 ~ 최대, 하한(5초)에 선 */
@Composable
private fun TakeBar(elapsedMs: Long) {
    val mark = VOICE_SAMPLE_MIN_MS.toFloat() / VOICE_SAMPLE_MAX_MS
    val filled = (elapsedMs.toFloat() / VOICE_SAMPLE_MAX_MS).coerceIn(0f, 1f)
    val label = "녹음 ${elapsedMs / 1000}초, ${VOICE_SAMPLE_MIN_MS / 1000}초부터 돼요"
    Column(Modifier.fillMaxWidth().semantics { contentDescription = label }) {
        Canvas(Modifier.fillMaxWidth().height(12.dp)) {
            val h = 4.dp.toPx()
            val y = (size.height - h) / 2
            drawRoundRect(LologColors.Line, Offset(0f, y), Size(size.width, h), CornerRadius(h / 2))
            drawRoundRect(LologColors.Accent, Offset(0f, y), Size(size.width * filled, h), CornerRadius(h / 2))
            val x = size.width * mark
            drawLine(LologColors.Ink, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.5.dp.toPx())
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth()) {
            Text("0초", fontSize = 11.sp, color = LologColors.Muted, modifier = Modifier.align(Alignment.CenterStart))
            Text(
                "${VOICE_SAMPLE_MIN_MS / 1000}초부터 OK", fontSize = 11.sp, color = LologColors.Muted,
                modifier = Modifier.align(BiasAlignment(mark * 2 - 1, 0f)),
            )
            Text("${VOICE_SAMPLE_MAX_MS / 1000}초", fontSize = 11.sp, color = LologColors.Muted, modifier = Modifier.align(Alignment.CenterEnd))
        }
    }
}

/** 소리 크기 물결(가운데 정렬 막대) */
@Composable
private fun Wave(levels: List<Float>, modifier: Modifier) {
    val bars = List(VoiceCloneViewModel.WAVE_BARS - levels.size) { 0f } + levels
    Canvas(modifier) {
        val gap = 4.dp.toPx()
        val w = 3.dp.toPx()
        val total = bars.size * w + (bars.size - 1) * gap
        var x = (size.width - total) / 2
        bars.forEach { v ->
            // 작은 소리도 보이게 제곱근으로 키워요
            val h = (kotlin.math.sqrt(v) * size.height).coerceAtLeast(w)
            drawRoundRect(LologColors.Accent, Offset(x, (size.height - h) / 2), Size(w, h), CornerRadius(w / 2))
            x += w + gap
        }
    }
}

/** 아래 마이크: 누르면 녹음 시작, 녹음 중이면 네모(끝). 빨간 글이 있으면 위에 보여 줘요 */
@Composable
private fun MicArea(state: VoiceCloneViewModel.State, convoRecording: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        state.problem?.let {
            Text(
                it, fontSize = 13.sp, color = LologColors.Accent, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clip(LologShape.sm).background(LologColors.RecSoft).padding(horizontal = 14.dp, vertical = 10.dp),
            )
            Spacer(Modifier.height(14.dp))
        }
        if (convoRecording) {
            VoiceCloneRecordingNote()
            Spacer(Modifier.height(14.dp))
        }
        val enabled = !convoRecording
        val desc = if (state.recording) "녹음 끝" else "녹음 시작"
        Box(
            Modifier.size(76.dp).clip(CircleShape)
                .background(if (state.recording) LologColors.Paper else if (enabled) LologColors.Accent else LologColors.Line)
                .border(if (state.recording) 2.dp else 0.dp, LologColors.Accent, CircleShape)
                .clickable(enabled = enabled, role = Role.Button, onClick = if (state.recording) onStop else onStart)
                .semantics { contentDescription = desc },
            contentAlignment = Alignment.Center,
        ) {
            if (state.recording) Box(Modifier.size(24.dp).clip(RoundedCornerShape(5.dp)).background(LologColors.Accent))
            else Icon(Icons.Filled.Mic, null, tint = LologColors.Paper, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            when {
                state.recording -> "다 읽었으면 눌러요"
                state.problem != null -> "다시 녹음"
                else -> "눌러서 녹음 시작"
            },
            fontSize = 12.sp, color = LologColors.Muted,
        )
    }
}

/** 시안 6: 잘 녹음됐어요. 내 녹음 듣기·다시 녹음 */
@Composable
private fun ReviewStep(state: VoiceCloneViewModel.State, vm: VoiceCloneViewModel, player: VoicePlayer, modifier: Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text("잘 녹음됐어요", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = LologColors.Ink)
        Spacer(Modifier.height(12.dp))
        SentenceCard(fontSize = 15)
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth().clip(LologShape.md).border(1.dp, LologColors.Line, LologShape.md)
                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val f = vm.file
            VoicePreviewButton(f.absolutePath.takeIf { f.exists() }, "sample", player, freshUrl = { null }, compact = true)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("내 녹음 듣기", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = LologColors.Ink)
                Text(clock(state.elapsedMs), fontSize = 11.sp, color = LologColors.Muted)
            }
            TextButton(onClick = { player.stop(); vm.retake() }) {
                Text("다시 녹음", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = LologColors.Muted)
            }
        }
    }
}

/** 시안 7: 만드는 중 */
@Composable
private fun MakingStep(modifier: Modifier) {
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator(Modifier.size(56.dp), color = LologColors.Accent, trackColor = LologColors.RecSoft, strokeWidth = 4.dp)
        Spacer(Modifier.height(24.dp))
        Text("내 목소리를 만들고 있어요", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = LologColors.Ink)
        Spacer(Modifier.height(8.dp))
        Text(
            VOICE_CLONE_MAKING.replace(". ", ".\n"),
            fontSize = 13.sp, color = LologColors.Muted, lineHeight = 20.sp, textAlign = TextAlign.Center,
        )
    }
}
