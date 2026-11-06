package com.lolog.app.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lolog.app.data.model.CardSummary
import com.lolog.app.BuildConfig
import com.lolog.app.data.model.ComicStatus
import com.lolog.app.data.model.RecordingProgress
import com.lolog.app.data.model.RecordingStatus
import com.lolog.app.data.repo.LologRepository
import com.lolog.app.ui.components.*
import com.lolog.app.ui.navigation.LocalContainer
import com.lolog.app.ui.theme.LologColors
import com.lolog.app.ui.theme.LologShape
import com.lolog.app.ui.theme.LologText
import com.lolog.app.util.isNew
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class HomeViewModel(private val repo: LologRepository) : ViewModel() {
    data class State(val cardCount: String = "-", val comicCount: String = "-", val recent: CardSummary? = null)

    /** 방금 끝낸 녹음의 정리 상태 (마이크 아래 띠 한 줄). null이면 띠 없음 */
    data class Analysis(val recordingId: Long, val progress: RecordingProgress? = null)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val _analysis = MutableStateFlow<Analysis?>(null)
    val analysis: StateFlow<Analysis?> = _analysis
    private var watchJob: Job? = null

    /** GET /api/cards?size=1 로 최근 카드. 숫자는 목록 개수(전체 개수 API가 없어 첫 페이지로 셈) */
    fun load() = viewModelScope.launch {
        runCatching { coroutineScope {
            val cards = async { repo.cards(size = 50) }
            val comics = async { repo.comics(size = 50) }
            val c = cards.await()
            val m = comics.await()
            State(
                cardCount = c.cards.size.toString() + if (c.nextCursor != null) "+" else "",
                comicCount = m.comics.count { it.status == ComicStatus.DONE }.toString() + if (m.nextCursor != null) "+" else "",
                recent = c.cards.firstOrNull(),
            )
        } }.onSuccess { _state.value = it }
    }

    /**
     * 녹음을 끝내고 홈으로 오면 GET /api/recordings/{id}로 정리 상태를 봐요(명세: 5초마다, DONE·FAILED면 멈춤. 목업은 2초).
     * 홈 탭을 나갔다 와도 이 ViewModel이 살아 있어서 띠가 그대로예요.
     */
    fun watch(recordingId: Long) {
        if (_analysis.value?.recordingId == recordingId) return
        _analysis.value = Analysis(recordingId)
        watchJob?.cancel()
        watchJob = viewModelScope.launch {
            val interval = if (BuildConfig.USE_MOCK) 2_000L else 5_000L
            while (isActive) {
                runCatching { repo.recordingStatus(recordingId) }.onSuccess { p ->
                    _analysis.update { it?.copy(progress = p) }
                }
                val st = _analysis.value?.progress?.status
                if (st == RecordingStatus.DONE || st == RecordingStatus.FAILED) {
                    load() // 숫자와 "방금 들어온 카드"를 새로
                    break
                }
                delay(interval)
            }
        }
    }

    fun dismiss() { watchJob?.cancel(); _analysis.value = null }
}

// ── 04 홈 ─────────────────────────────────────────────

@Composable
fun HomeScreen(
    onRecord: () -> Unit,
    onCard: (Long) -> Unit,
    onLibrary: () -> Unit,
    onSettings: () -> Unit,
    /** 방금 끝낸 녹음 ID (녹음 화면에서 끝내고 돌아오면 들어와요) */
    finishedRecordingId: Long? = null,
    onFinishedShown: () -> Unit = {},
) {
    val repo = LocalContainer.current.repository
    val vm = viewModel { HomeViewModel(repo) }
    val state by vm.state.collectAsState()
    val analysis by vm.analysis.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.load() }
    // 녹음 끝내고 돌아왔을 때: 정리 상태 보기 시작 + 아래 검은 띠 한 번
    LaunchedEffect(finishedRecordingId) {
        val id = finishedRecordingId ?: return@LaunchedEffect
        vm.watch(id)
        onFinishedShown()
        snackbar.showSnackbar("녹음을 끝냈어요. 정리되면 알려 드릴게요")
    }

    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier.fillMaxSize().noteLines().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // 위쪽 줄: 왼쪽 LOLog, 오른쪽 설정 톱니
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("LOLog", style = LologText.brand, modifier = Modifier.padding(vertical = 4.dp))
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onSettings) {
                Icon(Icons.Outlined.Settings, contentDescription = "설정", tint = LologColors.Muted)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "재밌는 대화가 시작될 것 같다면", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Text("눌러서 녹음", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.highlighter())
            Text("하세요", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        }
        Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
            RecordButton(onRecord)
        }
        Text("최대 3시간 · 1시간마다 자동으로 올려요", fontSize = 12.sp, color = LologColors.Muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        // 녹음 후 홈 시안(mockups/녹음후-홈-시안.png): 정리 상태는 이 띠 한 줄로만
        analysis?.let { a -> AnalysisStrip(a, onLibrary = { vm.dismiss(); onLibrary() }, onDismiss = vm::dismiss) }
        val newCards = analysis?.progress?.takeIf { it.status == RecordingStatus.DONE }?.cardCount ?: 0
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat(state.cardCount, "모은 이야기 카드", Modifier.weight(1f).clickable(onClick = onLibrary), plus = newCards)
            Stat(state.comicCount, "완성된 4컷만화", Modifier.weight(1f))
        }
        state.recent?.let { card ->
            Row(verticalAlignment = Alignment.Bottom) {
                SectionTitle("방금 들어온 카드")
                Spacer(Modifier.width(8.dp))
                PenMemo("이거 진짜 웃겼음 ↓")
            }
            CardItem(card, isNew = isNew(card.createdAt), onClick = { onCard(card.id) })
        }
    }
    LologSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }
}

/** 마이크 아래 정리 상태 띠: 올림 → 이야기 찾는 중 → 카드 N개 / 0개 / 실패 */
@Composable
private fun AnalysisStrip(a: HomeViewModel.Analysis, onLibrary: () -> Unit, onDismiss: () -> Unit) {
    val p = a.progress
    when {
        p?.status == RecordingStatus.DONE && p.cardCount > 0 -> Strip(
            title = "새 카드 ${p.cardCount}개 생겼어요", body = "방금 녹음에서 찾았어요",
            bg = LologColors.AccentSoft, border = LologColors.Accent, titleColor = LologColors.Accent,
            lead = { Text("✓", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = LologColors.Accent) },
            action = "보기 →", onClick = onLibrary,
        )
        p?.status == RecordingStatus.DONE -> Strip(
            title = "이번엔 재밌는 이야기가 없었어요", body = "다음 수다를 기대해요",
            bg = LologColors.Canvas, border = LologColors.Line,
            lead = { Text("💬", fontSize = 15.sp) },
            action = "✕", onClick = onDismiss,
        )
        p?.status == RecordingStatus.FAILED -> Strip(
            title = "이야기를 정리하지 못했어요", body = "잠시 후 다시 녹음해 주세요",
            bg = LologColors.RecSoft, border = LologColors.RecSoft, titleColor = LologColors.Rec,
            lead = { Text("!", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = LologColors.Rec) },
            action = "✕", onClick = onDismiss,
        )
        p?.status == RecordingStatus.DETECTING -> Strip(
            title = "재밌는 이야기 찾는 중…", body = "보통 30초쯤 걸려요. 그동안 다시 녹음해도 돼요",
            bg = Color(0xFFF6F8FF), border = LologColors.AccentSoft,
            lead = { TypingDots(dot = 6.dp) },
        )
        else -> Strip(
            title = "방금 녹음 올렸어요", body = "앱을 닫아도 정리는 계속돼요",
            bg = LologColors.Paper, border = LologColors.Line,
            lead = { TypingDots(dot = 6.dp) },
        )
    }
}

@Composable
private fun Strip(
    title: String,
    body: String,
    bg: Color,
    border: Color,
    lead: @Composable () -> Unit,
    titleColor: Color = LologColors.Ink,
    action: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().clip(LologShape.md).background(bg).border(1.5.dp, border, LologShape.md)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.widthIn(min = 28.dp), contentAlignment = Alignment.Center) { lead() }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = titleColor)
            Text(body, fontSize = 12.sp, color = LologColors.Muted)
        }
        if (action != null) Text(action, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (action == "✕") LologColors.Muted else LologColors.Accent)
    }
}

@Composable
private fun RecordButton(onClick: () -> Unit) {
    Box(
        Modifier
            .size(140.dp)
            .drawBehind { drawCircle(LologColors.RecSoft, radius = size.minDimension / 2 + 10.dp.toPx()) }
            .clip(CircleShape)
            .background(LologColors.Rec)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Default.Mic, contentDescription = "녹음 시작", tint = LologColors.Paper, modifier = Modifier.size(56.dp)) }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier, plus: Int = 0) {
    Column(modifier.softCard().padding(12.dp)) {
        Row {
            Text(value, style = LologText.stat)
            if (plus > 0) Text("+$plus", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = LologColors.Rec, modifier = Modifier.padding(start = 3.dp))
        }
        Text(label, fontSize = 12.sp, color = LologColors.Muted)
    }
}
