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
import androidx.compose.ui.text.style.TextOverflow
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
import com.lolog.app.data.model.VOICE_CLONE_SENTENCES
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
 * 내 목소리 만들기(시안 11/13 2~7). 한 화면 안에서 단계를 바꿔요: INTRO 알림·동의 → READ 문장 읽기 → REVIEW 다시 듣기 → MAKING 만드는 중
 *  - 동의 ①②는 필수, ③(친구 쇼츠)은 선택. 체크 안 하면 친구 쇼츠엔 기본 목소리(팀 확인 필요)
 *  - 문장마다 마이크를 눌러 녹음, 네모를 누르면 끝. 15초면 저절로 끝나요
 *  - 1초 미만이면 "조금 더 길게", 소리가 작으면 "소리가 작아요" → 다시 녹음
 *  - 문장 파일은 기기에만 두고, "내 목소리 만들기"에서 PUT 한 번(임시: 첫 문장 녹음만 보내요. 다음에 문장 하나로 바꿔요)
 *  - 녹음은 VoiceSampleRecorder(MediaRecorder, m4a)로 cacheDir에만. 만들기를 시작했거나 화면을 나가면 지워요
 *  - 대화 녹음 중에는 마이크 버튼을 꺼요(마이크를 같이 못 써요)
 *  명세 없음, 목업만(실제 서버는 NOT_READY)
 */

/** 동의 ①(내 목소리만)과 ②(쇼츠에 씀)를 모두 체크해야 "동의하고 녹음하기"가 켜져요. ③(친구 쇼츠)은 선택 */
internal fun introReady(ownVoiceOnly: Boolean, agreeShorts: Boolean, @Suppress("UNUSED_PARAMETER") shareWithFriends: Boolean): Boolean =
    ownVoiceOnly && agreeShorts

const val VOICE_TAKE_SHORT = "조금 더 길게 읽어 주세요"
const val VOICE_TAKE_QUIET = "소리가 작아요. 조금 더 가까이서 다시 읽어 주세요"

class VoiceCloneViewModel(private val repo: LologRepository, private val app: Context) : ViewModel() {
    enum class Step { INTRO, READ, REVIEW, MAKING }

    data class State(
        val step: Step = Step.INTRO,
        val ownVoiceOnly: Boolean = false,
        val agreeShorts: Boolean = false,
        val shareWithFriends: Boolean = false,
        /** 지금 읽는 문장(0~4) */
        val index: Int = 0,
        /** REVIEW에서 "다시"로 왔으면 이 문장만 읽고 REVIEW로 돌아가요 */
        val fromReview: Boolean = false,
        val recording: Boolean = false,
        val elapsedMs: Long = 0,
        /** 물결: 최근 소리 크기(0~1) */
        val levels: List<Float> = emptyList(),
        /** 빨간 글(다시 녹음) */
        val problem: String? = null,
        val uploading: Boolean = false,
        /** 녹음한 문장 번호 */
        val done: Set<Int> = emptySet(),
        val starting: Boolean = false,
        val toast: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val recorder = VoiceSampleRecorder(app)
    private var ticker: Job? = null
    private var peak = 0

    fun file(index: Int) = File(voiceCloneDir(app), "sample_$index.m4a")

    fun setOwnVoiceOnly(v: Boolean) = _state.update { it.copy(ownVoiceOnly = v) }
    fun setAgreeShorts(v: Boolean) = _state.update { it.copy(agreeShorts = v) }
    fun setShareWithFriends(v: Boolean) = _state.update { it.copy(shareWithFriends = v) }

    /** 동의하고 마이크 권한을 받았을 때 */
    fun beginReading() = _state.update { it.copy(step = Step.READ, index = 0, fromReview = false, problem = null) }

    fun startTake() {
        val s = _state.value
        if (s.recording || s.uploading || s.step != Step.READ) return
        val f = file(s.index)
        if (!recorder.start(f)) {
            _state.update { it.copy(toast = "녹음을 시작하지 못했어요") }
            return
        }
        peak = 0
        _state.update { it.copy(recording = true, elapsedMs = 0, levels = emptyList(), problem = null) }
        val t0 = SystemClock.elapsedRealtime()
        ticker = viewModelScope.launch {
            while (true) {
                delay(100)
                val amp = recorder.amplitude()
                peak = maxOf(peak, amp)
                val elapsed = SystemClock.elapsedRealtime() - t0
                _state.update { it.copy(elapsedMs = elapsed, levels = (it.levels + amp / 32767f).takeLast(WAVE_BARS)) }
                if (elapsed >= VOICE_SAMPLE_MAX_MS) { stopTake(); break }
            }
        }
    }

    /** 네모를 눌렀거나 15초가 됨: 판정하고 괜찮으면 올려요 */
    fun stopTake() {
        val s = _state.value
        if (!s.recording) return
        ticker?.cancel()
        ticker = null
        val saved = recorder.stop()
        val f = file(s.index)
        val check = if (saved) checkTake(s.elapsedMs, peak) else TakeCheck.TOO_SHORT
        _state.update { it.copy(recording = false) }
        when (check) {
            TakeCheck.TOO_SHORT -> { f.delete(); _state.update { it.copy(problem = VOICE_TAKE_SHORT) } }
            TakeCheck.TOO_QUIET -> { f.delete(); _state.update { it.copy(problem = VOICE_TAKE_QUIET) } }
            TakeCheck.OK -> taken(s.index)
        }
    }

    /** 문장 하나를 다 읽음: 다음 문장으로, 다 읽었으면 다시 듣기로 */
    private fun taken(index: Int) = _state.update { s ->
        val done = s.done + index
        val next = VOICE_CLONE_SENTENCES.indices.firstOrNull { it !in done }
        if (s.fromReview || next == null) s.copy(done = done, step = Step.REVIEW, fromReview = false)
        else s.copy(done = done, index = next)
    }

    /** 다른 앱으로 가거나 나갈 때: 녹음 중이던 문장은 버려요 */
    fun cancelTake() {
        if (!_state.value.recording) return
        ticker?.cancel()
        ticker = null
        recorder.stop()
        file(_state.value.index).delete()
        _state.update { it.copy(recording = false, elapsedMs = 0, levels = emptyList()) }
    }

    /** REVIEW에서 "다시": 그 문장만 다시 읽어요 */
    fun retake(index: Int) = _state.update { it.copy(step = Step.READ, index = index, fromReview = true, problem = null) }

    fun startClone() = viewModelScope.launch {
        val s = _state.value
        if (s.starting) return@launch
        _state.update { it.copy(starting = true) }
        try {
            repo.uploadVoiceClone(file(0), VOICE_CLONE_SENTENCES[0], s.shareWithFriends)
            clearFiles()
            _state.update { it.copy(starting = false, step = Step.MAKING, done = emptySet()) }
        } catch (e: LologException) {
            _state.update { it.copy(starting = false, toast = cloneError(e)) }
        }
    }

    /** 녹음을 그만둠: 읽은 문장 파일을 모두 지워요 */
    fun abandon() {
        cancelTake()
        clearFiles()
        _state.update { it.copy(done = emptySet()) }
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

private fun cloneError(e: LologException): String =
    if (e.code == ErrorCodes.NOT_READY) VOICE_CLONE_NOT_READY else errorText(e)

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
    // 다른 앱으로 가면 녹음 중이던 문장은 버려요
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

    // 읽는 중·다시 듣기에서 나가면 확인 창(읽은 문장은 지워져요)
    val reading = state.step == VoiceCloneViewModel.Step.READ || state.step == VoiceCloneViewModel.Step.REVIEW
    fun back() {
        when {
            state.step == VoiceCloneViewModel.Step.MAKING -> onDone()
            reading && (state.recording || state.done.isNotEmpty()) -> askQuit = true
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
                        "동의하고 녹음하기", onClick = ::agreed,
                        enabled = introReady(state.ownVoiceOnly, state.agreeShorts, state.shareWithFriends),
                    )
                    VoiceCloneViewModel.Step.READ -> MicArea(state, convoRecording, onStart = { player.stop(); vm.startTake() }, onStop = vm::stopTake)
                    VoiceCloneViewModel.Step.REVIEW -> PrimaryButton(
                        "내 목소리 만들기", onClick = { player.stop(); vm.startClone() }, loading = state.starting,
                    )
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
            body = "읽은 문장은 지워져요",
            confirmText = "그만하기", danger = true,
            onConfirm = { askQuit = false; player.stop(); vm.abandon(); onBack() },
            onDismiss = { askQuit = false },
        )
    }
}

/** 시안 2: 알림과 동의 3개 */
@Composable
private fun IntroStep(state: VoiceCloneViewModel.State, vm: VoiceCloneViewModel, modifier: Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text("문장 5개를 읽으면\n내 목소리를 만들어요", fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 30.sp, color = LologColors.Ink)
        Spacer(Modifier.height(16.dp))
        Column(
            Modifier.fillMaxWidth().clip(LologShape.sm).background(LologColors.Canvas).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            listOf(
                "조용한 곳에서 평소 말투로 읽어 주세요",
                "쇼츠에서 내 캐릭터가 이 목소리로 말해요",
                "친구가 내 캐릭터를 쓰면 친구 쇼츠에도 나와요",
                "녹음 파일은 목소리를 만든 뒤 바로 지워요",
                "내 목소리는 언제든 지울 수 있어요",
            ).forEach { Text("· $it", fontSize = 13.sp, color = LologColors.Muted, lineHeight = 19.sp) }
        }
        Spacer(Modifier.height(16.dp))
        AgreeRow("내 목소리만 녹음할게요. 다른 사람 목소리는 넣지 않아요", "필수", state.ownVoiceOnly, vm::setOwnVoiceOnly)
        AgreeRow("내 목소리로 만든 소리를 쇼츠에 쓰는 데 동의해요", "필수", state.agreeShorts, vm::setAgreeShorts)
        // 선택: 체크 안 하면 친구 쇼츠에는 기본 목소리로 읽어요(팀 확인 필요)
        AgreeRow("친구 쇼츠에도 내 목소리가 나오는 데 동의해요", "선택", state.shareWithFriends, vm::setShareWithFriends)
    }
}

/** 체크 한 줄. 줄 전체를 누르면 바뀌고, 화면 읽기는 글과 필수·선택을 같이 읽어요 */
@Composable
private fun AgreeRow(text: String, tag: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(LologShape.sm)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked, onCheckedChange = null,
            colors = CheckboxDefaults.colors(checkedColor = LologColors.Accent, uncheckedColor = LologColors.Line),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            "$text ($tag)", fontSize = 13.sp, color = LologColors.Ink, lineHeight = 19.sp, modifier = Modifier.weight(1f),
        )
    }
}

/** 위 진행 막대 5칸. 화면 읽기는 "5개 중 n번째" */
@Composable
private fun SentenceProgress(filled: Int, current: Int?, modifier: Modifier = Modifier) {
    val total = VOICE_CLONE_SENTENCES.size
    val label = if (current != null) "${total}개 중 ${current + 1}번째" else "${total}개 모두 읽음"
    Row(modifier.fillMaxWidth().semantics { contentDescription = label }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(total) { i ->
            val color = when {
                i < filled -> LologColors.Accent
                i == current -> LologColors.Accent.copy(alpha = 0.5f)
                else -> LologColors.Line
            }
            Box(Modifier.weight(1f).height(4.dp).clip(LologShape.pill).background(color))
        }
    }
}

/** 시안 3~5: 문장 하나 읽기 */
@Composable
private fun ReadStep(state: VoiceCloneViewModel.State, modifier: Modifier) {
    val total = VOICE_CLONE_SENTENCES.size
    Column(modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        // 다시 읽기(fromReview)면 다 읽은 칸은 그대로 채워 둬요
        SentenceProgress(filled = state.done.size.takeIf { state.fromReview } ?: state.index, current = state.index)
        Spacer(Modifier.height(10.dp))
        Text("${state.index + 1} / $total · 아래 문장을 소리 내어 읽어 주세요", fontSize = 12.sp, color = LologColors.Muted)
        Spacer(Modifier.height(12.dp))
        Text(
            VOICE_CLONE_SENTENCES[state.index], fontSize = 19.sp, fontWeight = FontWeight.Bold, lineHeight = 28.sp,
            color = LologColors.Ink, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clip(LologShape.md).background(LologColors.AccentSoft).padding(horizontal = 20.dp, vertical = 24.dp),
        )
        if (state.recording) {
            Spacer(Modifier.height(28.dp))
            Wave(state.levels, Modifier.fillMaxWidth().height(40.dp))
            Spacer(Modifier.height(8.dp))
            Text(
                clock(state.elapsedMs), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = LologColors.Accent,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
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
        val enabled = !convoRecording && !state.uploading
        val desc = if (state.recording) "녹음 끝" else "녹음 시작"
        Box(
            Modifier.size(76.dp).clip(CircleShape)
                .background(if (state.recording) LologColors.Paper else if (enabled) LologColors.Accent else LologColors.Line)
                .border(if (state.recording) 2.dp else 0.dp, LologColors.Accent, CircleShape)
                .clickable(enabled = enabled, role = Role.Button, onClick = if (state.recording) onStop else onStart)
                .semantics { contentDescription = desc },
            contentAlignment = Alignment.Center,
        ) {
            when {
                state.uploading -> CircularProgressIndicator(Modifier.size(26.dp), color = LologColors.Paper, strokeWidth = 2.5.dp)
                state.recording -> Box(Modifier.size(24.dp).clip(RoundedCornerShape(5.dp)).background(LologColors.Accent))
                else -> Icon(Icons.Filled.Mic, null, tint = LologColors.Paper, modifier = Modifier.size(32.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            when {
                state.uploading -> "올리는 중이에요"
                state.recording -> "다 읽었으면 눌러요"
                state.problem != null -> "다시 녹음"
                else -> "눌러서 녹음 시작"
            },
            fontSize = 12.sp, color = LologColors.Muted,
        )
    }
}

/** 시안 6: 5개 모두 읽었어요. 들어 보기·다시 */
@Composable
private fun ReviewStep(state: VoiceCloneViewModel.State, vm: VoiceCloneViewModel, player: VoicePlayer, modifier: Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
        SentenceProgress(filled = VOICE_CLONE_SENTENCES.size, current = null)
        Spacer(Modifier.height(14.dp))
        Text("5개 모두 읽었어요", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = LologColors.Ink)
        Text(
            "들어 보고 다시 읽고 싶은 문장은 \"다시\"를 눌러요.", fontSize = 12.sp, color = LologColors.Muted,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        Column(Modifier.fillMaxWidth().clip(LologShape.md).border(1.dp, LologColors.Line, LologShape.md)) {
            VOICE_CLONE_SENTENCES.forEachIndexed { i, sentence ->
                if (i > 0) HorizontalDivider(color = LologColors.Line)
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(22.dp).clip(CircleShape).background(LologColors.Accent), contentAlignment = Alignment.Center) {
                        Text("${i + 1}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = LologColors.Paper)
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        sentence, fontSize = 13.sp, color = LologColors.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    val f = vm.file(i)
                    VoicePreviewButton(
                        f.absolutePath.takeIf { f.exists() }, "sample-$i", player, freshUrl = { null }, compact = true,
                    )
                    TextButton(
                        onClick = { player.stop(); vm.retake(i) },
                        modifier = Modifier.semantics { contentDescription = "${i + 1}번 문장 다시 읽기" },
                    ) {
                        Text("다시", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = LologColors.Muted)
                    }
                }
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
            "3분쯤 걸려요. 다른 화면으로 가도 괜찮아요.\n다 되면 알림으로 알려 드릴게요.",
            fontSize = 13.sp, color = LologColors.Muted, lineHeight = 20.sp, textAlign = TextAlign.Center,
        )
    }
}
