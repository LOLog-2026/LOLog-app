package com.lolog.app.ui.screens.home

import androidx.compose.material3.LocalContentColor
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lolog.app.data.model.CardSource
import com.lolog.app.data.model.CardSummary
import com.lolog.app.BuildConfig
import com.lolog.app.data.model.ComicStatus
import com.lolog.app.data.model.PushType
import com.lolog.app.data.model.RecordingProgress
import com.lolog.app.data.model.RecordingStatus
import com.lolog.app.data.repo.LologRepository
import com.lolog.app.recording.AudioUpload
import com.lolog.app.recording.RecordingState
import com.lolog.app.ui.badges.NewDot
import com.lolog.app.ui.components.*
import com.lolog.app.ui.navigation.LocalContainer
import com.lolog.app.ui.theme.LologColors
import com.lolog.app.ui.theme.LologText
import com.lolog.app.util.EpisodeSeen
import com.lolog.app.util.dayLabel
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.lolog.app.R
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image

/** "방금 들어온 카드"에서 옆으로 넘겨 볼 수 있는 최대 장수 */
private const val RECENT_MAX = 5

/** 정리 중 띠의 큰 글자: 4초마다 차례로 바뀌어요. 문장을 고칠 때는 이 목록만 고쳐요 */
val SEARCHING_LINES = listOf(
    "재밌는 이야기 찾는 중…",
    "웃긴 순간을 골라내는 중…",
    "누가 무슨 말을 했는지 나누는 중…",
    "에피소드 제목을 짓는 중…",
)
private const val SEARCHING_LINE_MS = 4_000L
private const val SEARCHING_SUB = "다 되면 알려 드릴게요. 앱을 닫아도 돼요"

class HomeViewModel(private val repo: LologRepository) : ViewModel() {
    data class State(val cardCount: String = "-", val comicCount: String = "-", val recent: List<CardSummary> = emptyList())

    /** 방금 끝낸 녹음의 정리 상태 (마이크 아래 띠 한 줄). null이면 띠 없음 */
    data class Analysis(val recordingId: Long, val progress: RecordingProgress? = null)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val _analysis = MutableStateFlow<Analysis?>(null)
    val analysis: StateFlow<Analysis?> = _analysis
    private var watchJob: Job? = null

    /** 최근 카드는 받은 목록 앞 5장. 숫자는 목록 개수(전체 개수 API가 없어 첫 페이지로 셈) */
    fun load() = viewModelScope.launch {
        runCatching { coroutineScope {
            val cards = async { repo.cards(size = 50) }
            val comics = async { repo.comics(size = 50) }
            val c = cards.await()
            val m = comics.await()
            State(
                cardCount = c.cards.size.toString() + if (c.nextCursor != null) "+" else "",
                comicCount = m.comics.count { it.status == ComicStatus.DONE }.toString() + if (m.nextCursor != null) "+" else "",
                recent = c.cards.take(RECENT_MAX),
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
                    load() // 숫자와 "방금 들어온 에피소드"를 새로
                    break
                }
                delay(interval)
            }
        }
    }

    /** 앱을 다시 켰을 때 아직 "분석 중"인 녹음이 있으면 이어서 지켜봐요(api-spec GET /api/recordings) */
    fun resumeAnalysis() = viewModelScope.launch {
        if (_analysis.value != null) return@launch
        val id = runCatching { repo.analyzingRecordingId() }.getOrNull() ?: return@launch
        if (_analysis.value == null) watch(id)
    }

    fun dismiss() { watchJob?.cancel(); _analysis.value = null }
}

// ── 04 홈 (11/10 확정 디자인) ───────────────────────────

@Composable
fun HomeScreen(
    onRecord: () -> Unit,
    onCard: (Long) -> Unit,
    onLibrary: () -> Unit,
    /** "컨텐츠" 숫자 칸을 누르면 쇼츠 화면의 "4컷" 탭으로 */
    onComics: () -> Unit,
    /** 종 버튼 → 알림함 */
    onInbox: () -> Unit = {},
    /** 방금 끝낸 녹음 ID (녹음 화면에서 끝내고 돌아오면 들어와요) */
    finishedRecordingId: Long? = null,
    onFinishedShown: () -> Unit = {},
    /** 다른 화면에서 돌아올 때 아래 띠에 한 번 보여 줄 문구(녹음 취소 등) */
    message: String? = null,
    onMessageShown: () -> Unit = {},
) {
    val container = LocalContainer.current
    val repo = container.repository
    val vm = viewModel { HomeViewModel(repo) }
    val state by vm.state.collectAsState()
    val analysis by vm.analysis.collectAsState()
    // 종 버튼의 코랄 점: 안 읽은 알림이나 공지사항이 있음(GET /api/me/badges의 inbox, inbox.md 4장)
    val badges by container.badges.badges.collectAsState()
    val hasUnread = badges.inbox
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    // 앱이 켜져 있을 때 새 카드·만화 푸시가 오면 숫자와 최근 카드를 새로 받아요(#67 7번)
    LaunchedEffect(Unit) {
        repo.pushEvents.collect { p ->
            if (p.type == PushType.CARDS_CREATED || p.type == PushType.COMIC_DONE) vm.load()
        }
    }
    LaunchedEffect(Unit) {
        vm.load()
        // 알림함 등 다른 화면에서 돌아올 때마다 점을 새로 받아요
        container.badges.refresh()
        if (finishedRecordingId == null) vm.resumeAnalysis()
    }
    // 녹음 끝내고 돌아왔을 때: 정리 상태 보기 시작 + 아래 검은 띠 한 번
    LaunchedEffect(finishedRecordingId) {
        val id = finishedRecordingId ?: return@LaunchedEffect
        vm.watch(id)
        onFinishedShown()
        snackbar.showSnackbar("녹음을 끝냈어요. 정리되면 알려 드릴게요")
    }
    // 값을 비우면 이 효과가 다시 돌아 띠가 끊기므로 띠는 화면 범위에서 띄워요
    val scope = rememberCoroutineScope()
    LaunchedEffect(message) { message?.let { onMessageShown(); scope.launch { snackbar.showSnackbar(it) } } }
    val status = analysis?.progress?.status
    // 올리는 중(아직 상태 없음)·받음·찾는 중이면 "정리하고 있어요"
    val searching = analysis != null && status != RecordingStatus.DONE && status != RecordingStatus.FAILED
    // "새로!" 기준: 에피소드 탭을 마지막으로 연 뒤에 만든 카드
    val seenAt = remember(state) { EpisodeSeen.lastOpened(context) }

    // 탭 Scaffold(Canvas 바탕)의 기본 글자색이 회색(Muted)이라 홈 글자는 진한 Ink로
    CompositionLocalProvider(LocalContentColor provides LologColors.Ink) {
    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier.fillMaxSize().noteLines().statusBarsPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        // 위쪽 줄: 왼쪽 로고(마크 + 글자), 오른쪽 종(설정은 아래 탭)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.logo_mark), contentDescription = null, modifier = Modifier.width(32.dp))
            Spacer(Modifier.width(8.dp))
            Image(painterResource(R.drawable.logo_word), contentDescription = "LOLog", modifier = Modifier.height(20.dp))
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onInbox) {
                Box {
                    Icon(Icons.Outlined.Notifications, contentDescription = if (hasUnread) "알림함, 새 소식 있음" else "알림함", tint = LologColors.Ink)
                    if (hasUnread) {
                        Box(
                            Modifier.align(Alignment.TopEnd).offset(x = 1.dp, y = (-1).dp)
                                .size(7.dp).clip(CircleShape).background(LologColors.Accent),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        // 큰 제목 두 줄
        if (searching) {
            Text("방금 녹음을\n정리하고 있어요", fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.ExtraBold)
        } else {
            Text("재밌는 대화가 시작될 것 같다면", fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.ExtraBold)
            Row {
                Text("눌러서 녹음", fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.highlighter())
                Text("하세요", fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
        Spacer(Modifier.height(18.dp))
        // 가운데 코랄 마이크
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            RecordButton(onRecord)
            Spacer(Modifier.height(10.dp))
            Text("눌러서 녹음", fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
            Text("최대 3시간 · 1시간마다 자동으로 올려요", fontSize = 11.sp, color = LologColors.Muted)
            SaveAudioCheck(container)
            AudioUploadLine(container)
        }
        Spacer(Modifier.height(14.dp))
        // 마이크 아래 상태 띠
        analysis?.let { a ->
            AnalysisStrip(a, searching, onLibrary = { vm.dismiss(); onLibrary() }, onDismiss = vm::dismiss)
            Spacer(Modifier.height(12.dp))
        }
        val newCards = analysis?.progress?.takeIf { it.status == RecordingStatus.DONE }?.cardCount ?: 0
        StatBox(state.cardCount, state.comicCount, newCards, badges.cards, onLibrary, onComics)
        Spacer(Modifier.height(18.dp))
        if (state.recent.isEmpty()) {
            EmptyCards()
        } else {
            Text("방금 들어온 에피소드", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = LologColors.Muted)
            Spacer(Modifier.height(10.dp))
            RecentStack(state.recent, isNew = { EpisodeSeen.isNewSince(it.createdAt, seenAt) }, onCard = onCard)
            Spacer(Modifier.height(10.dp))
            // 안내만 하는 손글씨(누르는 동작 없음)
            Text(
                "또 녹음하면 에피소드가 더 쌓여요 ↑",
                style = LologText.memo.copy(fontSize = 18.sp),
                modifier = Modifier.align(Alignment.End).rotate(-2f),
            )
        }
        Spacer(Modifier.height(16.dp))
    }
    LologSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }
    }
}

/** 마이크 아래 정리 상태 띠: 올림·찾는 중(문장이 4초마다 바뀜) → 카드 n개 / 0개 / 실패 */
@Composable
private fun AnalysisStrip(a: HomeViewModel.Analysis, searching: Boolean, onLibrary: () -> Unit, onDismiss: () -> Unit) {
    val p = a.progress
    when {
        searching -> {
            var i by remember { mutableIntStateOf(0) }
            LaunchedEffect(Unit) {
                while (true) {
                    delay(SEARCHING_LINE_MS)
                    i = (i + 1) % SEARCHING_LINES.size
                }
            }
            StatusStrip(searching = true, title = SEARCHING_LINES[i], sub = SEARCHING_SUB)
        }
        p?.status == RecordingStatus.DONE && p.cardCount > 0 ->
            StatusStrip(searching = false, title = "에피소드 ${p.cardCount}개를 만들었어요", onView = onLibrary)
        p?.status == RecordingStatus.DONE -> Strip(
            title = "이번엔 재밌는 이야기가 없었어요", body = "다음 수다를 기대해요",
            bg = LologColors.Canvas, border = LologColors.Line,
            lead = { Text("💬", fontSize = 15.sp) },
            action = "✕", onClick = onDismiss,
        )
        else -> Strip(
            title = "이야기를 정리하지 못했어요", body = "잠시 후 다시 녹음해 주세요",
            bg = LologColors.RecSoft, border = LologColors.RecSoft, titleColor = LologColors.Rec,
            lead = { Text("!", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = LologColors.Rec) },
            action = "✕", onClick = onDismiss,
        )
    }
}

/** 카드 0개·실패처럼 확정 디자인에 따로 없는 띠는 지금 모양 그대로 */
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
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(bg).border(1.5.dp, border, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.widthIn(min = 28.dp), contentAlignment = Alignment.Center) { lead() }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = titleColor)
            Text(body, fontSize = 11.sp, color = LologColors.Muted)
        }
        if (action != null) Text(action, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = LologColors.Muted)
    }
}

/**
 * "음성도 저장" 체크(audio.md 1장). 기본은 꺼짐, 한 번 고른 값은 폰이 기억해요.
 * 녹음 중(올리는 중 포함)에는 바꿀 수 없어요. 녹음 화면이 시작할 때 이 값을 읽어요
 */
@Composable
private fun SaveAudioCheck(container: com.lolog.app.AppContainer) {
    var checked by remember { mutableStateOf(container.saveAudio) }
    val recorderState by container.recorder.state.collectAsState()
    val busy = recorderState is RecordingState.Recording || recorderState is RecordingState.Uploading
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = checked,
            onCheckedChange = { checked = it; container.saveAudio = it },
            enabled = !busy,
            colors = CheckboxDefaults.colors(checkedColor = LologColors.Accent),
        )
        Text(
            "음성도 저장", fontSize = 12.5.sp, fontWeight = FontWeight.Bold,
            color = if (busy) LologColors.Muted else LologColors.Ink,
            modifier = Modifier.clickable(enabled = !busy) { checked = !checked; container.saveAudio = checked },
        )
    }
}

/** 녹음을 끝낸 뒤 음성 파일을 뒤에서 올리는 동안만 체크 아래 한 줄. 다 올리면 사라져요 */
@Composable
private fun AudioUploadLine(container: com.lolog.app.AppContainer) {
    val upload by container.recorder.audioUpload.collectAsState()
    val sending = upload as? AudioUpload.Sending ?: return
    Row(verticalAlignment = Alignment.CenterVertically) {
        TypingDots(dot = 5.dp)
        Spacer(Modifier.width(6.dp))
        Text("음성 ${sending.seq}번째 파일 올리는 중", fontSize = 11.sp, color = LologColors.Muted)
    }
}

/** 코랄 마이크 78dp 원 + 그림자 */
@Composable
private fun RecordButton(onClick: () -> Unit) {
    Box(
        Modifier
            .size(78.dp)
            .shadow(10.dp, CircleShape, ambientColor = LologColors.Accent, spotColor = LologColors.Accent)
            .clip(CircleShape)
            .background(LologColors.Accent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Default.Mic, contentDescription = "녹음 시작", tint = LologColors.Paper, modifier = Modifier.size(32.dp)) }
}

/**
 * 숫자 칸: Canvas 상자 하나를 세로 선으로 반 나눔. 각 칸은 이름(왼쪽) · 숫자+단위(오른쪽) 가로 한 줄.
 * "에피소드" 칸이 에피소드 화면 입구예요. [newEpisode]면 이름 옆에 새 소식 점
 */
@Composable
private fun StatBox(cards: String, comics: String, plus: Int, newEpisode: Boolean, onLibrary: () -> Unit, onComics: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(RoundedCornerShape(14.dp)).background(LologColors.Canvas),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatCell("에피소드", cards, "개", plus, Modifier.weight(1f).clickable(onClick = onLibrary), dot = newEpisode)
        Box(Modifier.padding(vertical = 14.dp).width(1.dp).fillMaxHeight().background(LologColors.Line))
        StatCell("컨텐츠", comics, "편", 0, Modifier.weight(1f).clickable(onClick = onComics))
    }
}

@Composable
private fun StatCell(label: String, value: String, unit: String, plus: Int, modifier: Modifier, dot: Boolean = false) {
    Row(modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
            if (dot) NewDot(Modifier.padding(start = 4.dp).semantics { contentDescription = "새 소식 있음" })
        }
        Text(value, style = LologText.stat, maxLines = 1)
        Spacer(Modifier.width(2.dp))
        Text(unit, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Bottom).padding(bottom = 3.dp))
        if (plus > 0) Text("+$plus", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = LologColors.Rec, modifier = Modifier.padding(start = 3.dp).align(Alignment.Top))
    }
}

/** 카드가 하나도 없을 때: 점선 상자 + 손글씨 */
@Composable
private fun EmptyCards() {
    Box(
        Modifier.fillMaxWidth().height(110.dp).drawBehind {
            drawRoundRect(
                LologColors.Line,
                cornerRadius = CornerRadius(16.dp.toPx()),
                style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))),
            )
        },
        contentAlignment = Alignment.Center,
    ) { PenMemo("에피소드가 곧 여기 쌓여요", angle = 0f) }
}

/**
 * 방금 들어온 카드 묶음: 맨 앞 카드 뒤로 두 장이 살짝 기울어져 겹쳐 보여요.
 * 옆으로 밀면 다음 카드(최대 5장), 누르면 카드 보기.
 * 카드 목록 응답(CardSummaryDto)에 대사가 없어서 대사 말풍선은 빼고 제목·날짜만 보여 줘요.
 */
@Composable
private fun RecentStack(cards: List<CardSummary>, isNew: (CardSummary) -> Boolean, onCard: (Long) -> Unit) {
    val pager = rememberPagerState { cards.size }
    Box(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        // 뒤에 깔린 두 장(+4°, -3°)
        Box(Modifier.matchParentSize().padding(horizontal = 10.dp).offset(y = 8.dp).rotate(4f).clip(RoundedCornerShape(18.dp)).background(LologColors.AbsurdSoft))
        Box(Modifier.matchParentSize().padding(horizontal = 6.dp).offset(y = 5.dp).rotate(-3f).clip(RoundedCornerShape(18.dp)).background(LologColors.LaughSoft))
        HorizontalPager(pager, pageSpacing = 12.dp) { page ->
            val card = cards[page]
            FrontCard(card, isNew(card), swipeHint = cards.size > 1, onClick = { onCard(card.id) })
        }
    }
}

@Composable
private fun FrontCard(card: CardSummary, isNew: Boolean, swipeHint: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().softCard(RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 글 카드는 에피소드 목록처럼 종류 칩 옆에 출처 꼬리표("글"·"블로그")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            KindChip(card.kind)
            SourceTag(card.source)
            Spacer(Modifier.weight(1f))
            if (isNew) PenMemo("새로!", angle = 3f)
        }
        Text(card.summary, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val meta = listOfNotNull(
            // 글 카드의 날짜는 녹음 날짜가 아니라 쓴 날이라(texts.md 2장) "녹음"을 붙이지 않아요(카드 자세히와 같음)
            dayLabel(card.recordedAt ?: card.createdAt).ifBlank { null }?.let { if (card.source == CardSource.RECORDING) "$it 녹음" else it },
            card.place?.takeIf { it.isNotBlank() },
            if (swipeHint) "밀어서 넘기기 →" else null,
        ).joinToString(" · ")
        Text(meta, fontSize = 11.sp, color = LologColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
