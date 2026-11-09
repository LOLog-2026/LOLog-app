package com.lolog.app.ui.screens.recording

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.lolog.app.ui.theme.LologShape
import kotlin.math.abs
import kotlin.math.sin
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lolog.app.BuildConfig
import com.lolog.app.data.model.RecordingStatus
import com.lolog.app.data.model.RecordingProgress
import com.lolog.app.data.repo.LologRepository
import com.lolog.app.recording.PartUpload
import com.lolog.app.recording.RecordingState
import com.lolog.app.ui.components.*
import com.lolog.app.ui.navigation.LocalContainer
import com.lolog.app.ui.screens.start.PrivacyNote
import com.lolog.app.ui.theme.LologColors
import com.lolog.app.ui.theme.LologText
import com.lolog.app.util.clock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// ── 05 녹음 중 · 파형 ──────────────────────────────

@Composable
fun RecordingScreen(onBack: () -> Unit, onFinished: (Long) -> Unit) {
    val recorder = LocalContainer.current.recorder
    val state by recorder.state.collectAsState()
    val context = LocalContext.current
    var askStop by remember { mutableStateOf(false) }
    var denied by remember { mutableStateOf(false) }

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) recorder.start() else denied = true
    }
    // 들어왔을 때의 상태. 지난 녹음의 Uploaded·UploadFailed가 남아 있으면 새 녹음을 시작해요
    val entered = remember { recorder.state.value }
    LaunchedEffect(Unit) {
        if (entered !is RecordingState.Recording && entered !is RecordingState.Uploading) {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (granted) recorder.start() else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    // 약속: "업로드 완료"를 받으면 그 녹음 ID로 분석 중 화면
    LaunchedEffect(state) {
        val done = state as? RecordingState.Uploaded ?: return@LaunchedEffect
        if (done !== entered) onFinished(done.recordingId) // 지난 녹음 결과로는 넘어가지 않아요
    }

    BackHandler(enabled = state is RecordingState.Recording) { askStop = true }

    if (denied) {
        Column(Modifier.fillMaxSize().noteLines().systemBarsPadding().padding(24.dp), verticalArrangement = Arrangement.Center) {
            CenterMessage("마이크 권한이 필요해요", "녹음하려면 휴대폰 설정에서 LOLog의 마이크를 허용해 주세요.")
            Spacer(Modifier.height(16.dp))
            GhostButton("돌아가기", onBack)
        }
        return
    }

    val s = state
    // 녹음이 끝나 Uploading이 되어도 마지막 경과 시간을 계속 보여 줘요
    var lastElapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(s) { if (s is RecordingState.Recording) lastElapsed = s.elapsedSec }
    val elapsedSec = (s as? RecordingState.Recording)?.elapsedSec ?: lastElapsed
    val partUpload = (s as? RecordingState.Recording)?.partUpload ?: PartUpload.None

    // 1시간 묶음을 다 올리면(Sending → None) 위에 작은 띠를 4초 동안
    var sentPartNo by remember { mutableIntStateOf(0) }
    var showPartChip by remember { mutableStateOf(false) }
    var lastSending by remember { mutableIntStateOf(0) }
    LaunchedEffect(partUpload) {
        when (partUpload) {
            is PartUpload.Sending -> lastSending = partUpload.partNo
            PartUpload.None -> if (lastSending > 0) {
                sentPartNo = lastSending; lastSending = 0
                showPartChip = true; delay(4_000); showPartChip = false
            }
            is PartUpload.Failed -> lastSending = 0
        }
    }
    // 파형은 실제 소리 크기가 아니라 "녹음 중"을 보여 주는 가짜 막대예요(팀에서 정함). 말하는 척 흉내만 내요
    val talking = s is RecordingState.Recording && (elapsedSec / 4) % 3 != 2L

    Column(
        Modifier.fillMaxSize().noteLines().systemBarsPadding().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(LologColors.Rec))
            Spacer(Modifier.width(6.dp))
            Text(
                when (s) {
                    is RecordingState.Uploading -> "올리는 중"
                    is RecordingState.UploadFailed -> "올리지 못했어요"
                    else -> "녹음 중"
                },
                fontSize = 12.sp, fontWeight = FontWeight.Bold, color = LologColors.Rec,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(clock(elapsedSec), style = LologText.timer)
        Text("최대 3시간 · 1시간마다 자동으로 올려요", fontSize = 12.sp, color = LologColors.Muted)
        if (partUpload is PartUpload.Failed) {
            Text("${partUpload.partNo}번째 묶음은 폰에 두었다가 다시 올려요", fontSize = 12.sp, color = LologColors.Rec)
        }
        Spacer(Modifier.height(16.dp))
        // 녹음 중 · 파형 시안(mockups/녹음중-파형-시안.png): 대사 글자 대신 소리 크기 막대
        Column(Modifier.weight(1f).fillMaxWidth().softCard()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Waveform(
                    running = s is RecordingState.Recording,
                    talking = talking,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 18.dp),
                )
                if (showPartChip) {
                    Box(Modifier.align(Alignment.TopCenter).padding(top = 10.dp)) {
                        StatusPill("↑ ${sentPartNo}번째 1시간 묶음 올렸어요 · 녹음은 계속돼요", LologColors.AccentSoft, LologColors.Accent)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp)) {
                Text("-5초", fontSize = 10.sp, color = LologColors.Muted)
                Spacer(Modifier.weight(1f))
                Text("지금", fontSize = 10.sp, color = LologColors.Muted)
            }
            HorizontalDivider(color = LologColors.Line)
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                when (partUpload) {
                    is PartUpload.Sending -> StatusPill("↑ ${partUpload.partNo}번째 묶음 올리는 중", LologColors.AccentSoft, LologColors.Accent)
                    else -> StatusPill("● 듣고 있어요", LologColors.RecSoft, LologColors.Rec)
                }
                Spacer(Modifier.weight(1f))
                if (sentPartNo > 0) Text("${sentPartNo}번째 묶음 올림 ✓", fontSize = 12.sp, color = LologColors.Muted)
            }
        }
        Spacer(Modifier.height(12.dp))
        PrivacyNote("말은 폰 안에서만 글자로 바꿔요. 글자만 서버로 올라가요.")
        Spacer(Modifier.height(12.dp))
        if (s is RecordingState.UploadFailed) {
            Text(s.message, fontSize = 13.sp, color = LologColors.Rec, textAlign = TextAlign.Center)
            Text("대화는 폰에 남아 있어요. 다음에 녹음하거나 앱을 다시 켜면 다시 올려요", fontSize = 12.sp, color = LologColors.Muted, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            GhostButton("돌아가기", onBack)
        } else {
            PrimaryButton(
                "녹음 끝내기", { recorder.stop() }, danger = true,
                loading = s is RecordingState.Uploading, enabled = s is RecordingState.Recording,
                leading = { Icon(Icons.Default.Stop, null) },
            )
        }
    }

    if (askStop) {
        LologDialog(
            title = "녹음을 끝낼까요?",
            body = "지금까지 대화를 올리고 이야기를 찾기 시작해요.",
            confirmText = "녹음 끝내기",
            dismissText = "계속 녹음",
            danger = true,
            onConfirm = { askStop = false; recorder.stop() },
            onDismiss = { askStop = false },
        )
    }
}

/** 0.1초에 막대 하나, 최근 약 5초. 이만큼 쌓이면 가장 오래된 막대부터 빠져요 */
private const val MAX_BARS = 50

/**
 * 소리 크기 막대(아이폰 음성 메모처럼). 새 막대는 빨간 선에서 생기고 왼쪽으로 흘러가요.
 * 방금 소리(최근 8개)는 빨강, 지나간 소리는 회색, 빨간 선 오른쪽은 앞으로 올 자리(옅은 점).
 *
 * 실제 마이크 소리 크기가 아니라 "녹음 중"을 보여 주는 가짜 파형이에요(팀에서 정함). 막대 높이는 mockLevel(...)이 만들어요.
 */
@Composable
private fun Waveform(running: Boolean, talking: Boolean, modifier: Modifier) {
    val levels = remember { mutableStateListOf<Float>() }
    val talkingNow by rememberUpdatedState(talking)
    LaunchedEffect(running) {
        val rnd = kotlin.random.Random(System.nanoTime())
        var phase = 0f
        while (running) {
            phase += 0.33f
            levels.add(mockLevel(talkingNow, phase, rnd))
            if (levels.size > MAX_BARS) levels.removeAt(0)
            delay(100)
        }
    }
    val grey = Color(0xFFC9CED6)
    Canvas(modifier) {
        val barW = 3.dp.toPx()
        val gap = 2.dp.toPx()
        val nowX = size.width * 0.75f
        val midY = size.height / 2
        // 가운데 기준선
        drawLine(LologColors.Line, Offset(0f, midY), Offset(size.width, midY), 1.dp.toPx())
        // 앞으로 올 자리
        var fx = nowX + gap * 3
        while (fx < size.width) {
            drawRoundRect(LologColors.Line, Offset(fx, midY - 1.5.dp.toPx()), Size(barW, 3.dp.toPx()), CornerRadius(barW / 2))
            fx += barW + gap
        }
        // 지나간 소리: 오른쪽(빨간 선)에서 왼쪽으로
        val n = levels.size
        for (i in 0 until n) {
            val fromNow = n - 1 - i
            val x = nowX - barW - gap - fromNow * (barW + gap)
            if (x < 0) continue
            val h = (levels[i] * size.height * 0.85f).coerceAtLeast(3.dp.toPx())
            drawRoundRect(
                if (fromNow < 8) LologColors.Rec else grey,
                Offset(x, midY - h / 2), Size(barW, h), CornerRadius(barW / 2),
            )
        }
        // 빨간 지금 선
        drawLine(LologColors.Rec, Offset(nowX, 6.dp.toPx()), Offset(nowX, size.height), 2.dp.toPx())
        drawCircle(LologColors.Rec, 5.dp.toPx(), Offset(nowX, 6.dp.toPx()))
    }
}

/** 목업 소리 크기: 말할 때는 크게 출렁이고, 조용할 때는 바닥에 낮게 */
private fun mockLevel(talking: Boolean, phase: Float, rnd: kotlin.random.Random): Float =
    if (talking) (0.2f + 0.8f * abs(sin(phase)) * (0.45f + rnd.nextFloat() * 0.55f)).coerceIn(0f, 1f)
    else 0.02f + rnd.nextFloat() * 0.04f

@Composable
private fun StatusPill(text: String, bg: Color, fg: Color) {
    Text(
        text, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = fg,
        modifier = Modifier.clip(LologShape.pill).background(bg).padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

// ── 06 업로드·분석 · 20 이야기를 못 찾았어요 ───────────

class AnalyzingViewModel(private val repo: LologRepository, private val id: Long) : ViewModel() {
    private val _status = MutableStateFlow<RecordingProgress?>(null)
    val status: StateFlow<RecordingProgress?> = _status

    init {
        // GET /api/recordings/{id} — 앱이 열려 있을 때 5초마다 (목업은 2초)
        viewModelScope.launch {
            val interval = if (BuildConfig.USE_MOCK) 2_000L else 5_000L
            while (isActive) {
                runCatching { repo.recordingStatus(id) }.onSuccess { _status.value = it }
                val s = _status.value?.status
                if (s == RecordingStatus.DONE || s == RecordingStatus.FAILED) break
                delay(interval)
            }
        }
    }
}

@Composable
fun AnalyzingScreen(recordingId: Long, onHome: () -> Unit, onLibrary: () -> Unit) {
    val container = LocalContainer.current
    val vm = viewModel { AnalyzingViewModel(container.repository, recordingId) }
    val status by vm.status.collectAsState()

    val s = status?.status
    when {
        s == RecordingStatus.DONE && status!!.cardCount == 0 -> NoStory(failed = false, onHome)
        s == RecordingStatus.FAILED -> NoStory(failed = true, onHome)
        else -> Analyzing(status, onLibrary)
    }
}

@Composable
private fun Analyzing(status: RecordingProgress?, onLibrary: () -> Unit) {
    val s = status?.status
    val done = s == RecordingStatus.DONE
    val detecting = s == RecordingStatus.DETECTING
    Column(Modifier.fillMaxSize().noteLines().navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        LologTopBar(if (done) "카드로 정리했어요" else "이야기 찾는 중")
        Column(Modifier.fillMaxWidth().softCard().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("대화", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("음성 파일은 올리지 않아요", fontSize = 12.sp, color = LologColors.Muted)
            if (!done) { Spacer(Modifier.height(8.dp)); IndeterminateBar() }
        }
        StepList(
            listOf(
                StepItem("대화 텍스트 받음", "서버에 잘 도착했어요", if (s == null) StepState.NOW else StepState.DONE),
                StepItem(
                    "이야기 찾는 중", "웃기거나 황당하거나 추억이 될 이야기를 고르고, 누가 말했는지 나누고 있어요",
                    when { done -> StepState.DONE; detecting -> StepState.NOW; else -> StepState.WAIT },
                ),
                StepItem(
                    "카드로 정리 완료",
                    if (done) "카드 ${status!!.cardCount}개를 보관함에 넣었어요" else "찾은 카드 수를 알려 드리고 보관함에 넣어요",
                    if (done) StepState.DONE else StepState.WAIT,
                ),
            ),
        )
        Spacer(Modifier.weight(1f))
        if (done) PrimaryButton("보관함에서 보기", onLibrary)
        else PrivacyNote("앱을 닫아도 계속 진행돼요. 카드가 생기면 알림으로 알려 드려요.")
    }
}

@Composable
private fun NoStory(failed: Boolean, onHome: () -> Unit) {
    Column(
        Modifier.fillMaxSize().noteLines().systemBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(if (failed) LologColors.RecSoft else LologColors.AccentSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (failed) Icons.Outlined.ErrorOutline else Icons.Outlined.SearchOff, null,
                tint = if (failed) LologColors.Rec else LologColors.Accent, modifier = Modifier.size(30.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        if (failed) {
            Text("분석하지 못했어요", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            // FAILED는 끝난 상태예요. 서버가 여러 번 시도한 뒤라 기다려도 바뀌지 않아요(FR-CARD 예외)
            Text("여러 번 시도했지만 이번 녹음은 정리하지 못했어요.\n다음 녹음부터 다시 찾아볼게요.", fontSize = 14.sp, color = LologColors.Muted, textAlign = TextAlign.Center)
        } else {
            Text("이번 대화에서는", fontSize = 16.sp, color = LologColors.Muted)
            Text("카드로 남길 이야기를 못 찾았어요", fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text("웃긴 일이나 기억할 만한 순간이 생길 때 다시 녹음해 보세요.", fontSize = 14.sp, color = LologColors.Muted, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            PenMemo("다음엔 꼭!")
        }
        Spacer(Modifier.height(32.dp))
        PrimaryButton("홈으로", onHome)
    }
}
