package com.lolog.app.ui.screens.comic

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lolog.app.ui.screens.library.LineBubble
import com.lolog.app.ui.theme.LologShape
import com.lolog.app.ui.theme.LologText
import com.lolog.app.util.longDate
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lolog.app.BuildConfig
import com.lolog.app.data.remote.ApiClient
import com.lolog.app.data.model.*
import com.lolog.app.data.repo.LologException
import com.lolog.app.data.repo.LologRepository
import com.lolog.app.ui.components.*
import com.lolog.app.ui.navigation.LocalContainer
import com.lolog.app.ui.theme.LologColors
import com.lolog.app.util.ComicExport
import com.lolog.app.util.dayLabel
import com.lolog.app.util.timeOnly
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ComicViewModel(private val repo: LologRepository, private val id: Long) : ViewModel() {
    data class State(
        val comic: ComicDetail? = null,
        val card: CardDetail? = null,
        val error: String? = null,
        val busy: Boolean = false,
        val deleted: Boolean = false,
        val newComicId: Long? = null,
        val toast: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    init {
        // GET /api/comics/{id} — 화면이 열려 있는 동안 상태 조회 (끝나면 멈춤). 푸시도 같이 와요.
        viewModelScope.launch {
            val interval = if (BuildConfig.USE_MOCK) 1_500L else 5_000L
            while (isActive) {
                try {
                    val c = repo.comic(id)
                    // 카드가 지워졌으면 카드 조회는 건너뛰어요
                    val card = if (c.cardDeleted) null else _state.value.card ?: runCatching { repo.card(c.cardId) }.getOrNull()
                    _state.update { it.copy(comic = c, card = card, error = null) }
                    if (!c.status.inProgress) break
                } catch (e: LologException) {
                    _state.update { it.copy(error = errorText(e)) }
                    if (e.code == ErrorCodes.COMIC_NOT_FOUND) break
                }
                delay(interval)
            }
        }
    }

    /** 21 다시 그리기: 같은 카드로 POST /api/cards/{id}/comics → 새 만화 */
    fun retry() {
        val cardId = _state.value.comic?.cardId ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try {
                val res = repo.requestComic(cardId)
                _state.update { it.copy(busy = false, newComicId = res.comicId) }
            } catch (e: LologException) {
                _state.update { it.copy(busy = false, toast = errorText(e)) }
            }
        }
    }

    /** DELETE /api/comics/{id} */
    fun delete() = viewModelScope.launch {
        _state.update { it.copy(busy = true) }
        try { repo.deleteComic(id); _state.update { it.copy(busy = false, deleted = true) } }
        catch (e: LologException) { _state.update { it.copy(busy = false, toast = errorText(e)) } }
    }

    fun toast(msg: String?) = _state.update { it.copy(toast = msg) }

    /** 저장·공유하다 주소가 만료(403)되면 만화를 다시 조회해 새 imageUrl을 받아요 */
    suspend fun freshImageUrl(): String? =
        runCatching { repo.comic(id) }.getOrNull()?.also { c -> _state.update { it.copy(comic = c) } }?.imageUrl

    /** imageUrl은 10분 뒤 만료돼요(명세). 그림을 못 받으면 만화를 다시 조회해 새 주소를 받아요. 같은 주소로는 한 번만 */
    private var refreshedFor: String? = null
    fun imageFailed() {
        val url = _state.value.comic?.imageUrl ?: return
        if (refreshedFor == url) return
        refreshedFor = url
        viewModelScope.launch { runCatching { repo.comic(id) }.onSuccess { c -> _state.update { it.copy(comic = c) } } }
    }
}

/** 11 진행 상태 · 13 만화 보기 · 14 저장·공유 · 21 만화 실패를 상태에 따라 보여 줘요 */
@Composable
fun ComicScreen(
    comicId: Long,
    onBack: () -> Unit,
    onCard: (Long) -> Unit,
    onEditCard: (Long) -> Unit,
    onOtherComic: (Long) -> Unit,
    onDeleted: () -> Unit,
) {
    val container = LocalContainer.current
    val vm = viewModel { ComicViewModel(container.repository, comicId) }
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var menu by remember { mutableStateOf(false) }
    var askDelete by remember { mutableStateOf(false) }
    var shareSheet by remember { mutableStateOf(false) }
    var zoom by remember { mutableStateOf(false) }

    LaunchedEffect(state.deleted) { if (state.deleted) onDeleted() }
    LaunchedEffect(state.newComicId) { state.newComicId?.let(onOtherComic) }
    LaunchedEffect(state.toast) { state.toast?.let { snackbar.showSnackbar(it); vm.toast(null) } }

    val comic = state.comic
    val card = state.card
    val stage = comic?.status?.stage
    val title = card?.summary ?: comic?.cardSummary
    Scaffold(
        containerColor = if (stage == ComicStage.DONE) LologColors.Canvas else LologColors.Paper,
        snackbarHost = { LologSnackbarHost(snackbar) },
        topBar = {
            ComicTopBar(
                title = when (stage) {
                    ComicStage.DONE -> title ?: "4컷만화"
                    ComicStage.FAILED -> "만화"
                    null -> ""
                    else -> title ?: "만화 그리는 중"
                },
                // 그리는 중에는 카드 보기처럼 녹음 날짜·장소를 같이 보여 줘요
                sub = if (stage == ComicStage.WAITING || stage == ComicStage.MAKING) card?.let { recordedLine(it) } else null,
                onBack = onBack,
            ) {
                // 만드는 중인 만화도 지울 수 있어요(FR-LIB-03). 작업이 멈췄을 때 카드를 풀 수 있게요
                if (comic != null) {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "더 보기") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("만화 지우기", color = LologColors.Rec) }, onClick = { menu = false; askDelete = true })
                        }
                    }
                }
            }
        },
    ) { pad ->
        val m = Modifier.padding(pad)
        when {
            comic == null && state.error != null -> ErrorBox(state.error!!, onBack, m)
            comic == null -> LoadingBox(m)
            stage == ComicStage.DONE -> Viewer(
                comic, card, onCard,
                onZoom = { zoom = true },
                onExport = { shareSheet = true },
                onImageError = vm::imageFailed,
                modifier = m,
            )
            stage == ComicStage.FAILED -> Failed(
                comic, card?.remaining,
                state.busy, vm::retry, { onEditCard(comic.cardId) }, m,
            )
            else -> Progress(comic, card, m)
        }
    }

    if (zoom && comic != null) {
        ZoomViewer(comic, card, onImageError = vm::imageFailed, onClose = { zoom = false })
    }
    if (shareSheet && comic != null) {
        ShareSheet(comic, card, container.network, vm::freshImageUrl, onDone = { msg -> shareSheet = false; vm.toast(msg) }, onDismiss = { shareSheet = false })
    }
    if (askDelete) {
        LologDialog(
            title = "이 만화를 지울까요?",
            body = if (comic?.status?.inProgress == true) {
                "만들고 있는 만화도 멈추고 지워요. 되돌릴 수 없고, 지운 만화도 만들기 횟수에 세요."
            } else {
                "지운 만화는 되돌릴 수 없어요."
            },
            confirmText = "지우기",
            danger = true,
            loading = state.busy,
            onConfirm = { vm.delete() },
            onDismiss = { askDelete = false },
        )
    }
}

/** "11월 4일 오후 7:20 녹음 · 홍대 카페" */
private fun recordedLine(card: CardDetail): String =
    listOfNotNull("${longDate(card.recordedAt ?: card.createdAt)} 녹음", card.place?.takeIf { it.isNotBlank() }).joinToString(" · ")

/** 위 칸: ← · 제목(+ 작은 줄) · 오른쪽 아이콘. 아래 1dp 선 */
@Composable
private fun ComicTopBar(title: String, sub: String?, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(LologColors.Paper).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로") }
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = if (sub != null) 13.5.sp else 15.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (sub != null) Text(sub, fontSize = 10.sp, color = LologColors.Muted, maxLines = 1)
            }
            actions()
        }
        HorizontalDivider(thickness = 1.dp, color = LologColors.Line)
    }
}

// ── 11 그리는 중 ──

/** 단계 알약 4개: 요청 받음 › 콘티 › 그림 › 완성 */
private val STEPS = listOf("요청 받음", "콘티", "그림", "완성")

private fun stepIndex(status: ComicStatus): Int = when (status) {
    ComicStatus.QUEUED, ComicStatus.UNKNOWN -> 0
    ComicStatus.STORYBOARD -> 1
    ComicStatus.DRAWING -> 2
    ComicStatus.DONE, ComicStatus.FAILED -> 3
}

@Composable
private fun Progress(comic: ComicDetail, card: CardDetail?, modifier: Modifier) {
    Column(modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
            // Canvas 판: 점 3개 + 문구 + 단계 알약
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(LologColors.Canvas).padding(vertical = 20.dp, horizontal = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                TypingDots(dot = 6.dp)
                Spacer(Modifier.height(12.dp))
                Text("4컷을 그리고 있어요", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(4.dp))
                Text("앱을 닫아도 돼요. 다 되면 알려 드려요.", fontSize = 12.5.sp, color = LologColors.Muted)
                Spacer(Modifier.height(14.dp))
                StepPills(stepIndex(comic.status))
            }
            if (card != null && card.lines.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                ConversationReplay(card, running = comic.status.inProgress)
            }
        }
        // 아래: 잠김 안내 + 꺼진 버튼
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Lock, null, tint = LologColors.Muted, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(4.dp))
                Text("만화 그리는 중엔 고치거나 지울 수 없어요", fontSize = 12.sp, color = LologColors.Muted)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton("고치기", {}, Modifier.weight(1f), enabled = false)
                PrimaryButton("그리는 중…", {}, Modifier.weight(1f), enabled = false)
            }
        }
    }
}

@Composable
private fun StepPills(now: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        STEPS.forEachIndexed { i, label ->
            if (i > 0) Text("›", fontSize = 12.sp, color = LologColors.Muted, modifier = Modifier.padding(horizontal = 3.dp))
            // 지난 단계 코랄, 지금 단계 연한 코랄, 남은 단계 흰 바탕 회색 글자
            val (bg, fg) = when {
                i < now -> LologColors.Accent to LologColors.Paper
                i == now -> LologColors.RecSoft to LologColors.Accent
                else -> LologColors.Paper to LologColors.Muted
            }
            Text(
                label, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = fg,
                modifier = Modifier.clip(LologShape.pill).background(bg).padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}

/** 말풍선 하나가 올라오기 전 "•••"를 보여 주는 시간. 40자 넘는 대사는 조금 더 길게 */
private const val TYPING_MS = 400L
private const val TYPING_LONG_MS = 700L
private const val LONG_LINE = 40
/** 말풍선이 나온 뒤 다음 "•••"까지 읽을 틈 (시안에 숫자가 없어 앱이 정함) */
private const val READ_MS = 900L
/** 다 나오면 쉬었다가 처음부터 */
private const val REPLAY_PAUSE_MS = 2_000L

/**
 * 그동안 대화 다시 보기: 말풍선이 카톡처럼 하나씩 올라와요(백엔드 변경 없음, 카드 대사는 이미 받아 옴).
 * 다 나오면 2초 쉬고 처음부터. DONE·FAILED가 되면 멈추고, 휴대폰 "애니메이션 줄이기"면 재생 없이 전부 보여요.
 */
@Composable
private fun ConversationReplay(card: CardDetail, running: Boolean) {
    val context = LocalContext.current
    val reduceMotion = remember {
        android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val lines = card.lines
    val animate = running && !reduceMotion
    var shown by remember { mutableIntStateOf(if (animate) 0 else lines.size) }
    var typing by remember { mutableStateOf(false) }
    LaunchedEffect(animate, lines) {
        if (!animate) { typing = false; shown = lines.size; return@LaunchedEffect }
        while (true) {
            shown = 0
            for (i in lines.indices) {
                typing = true
                delay(if (lines[i].text.length > LONG_LINE) TYPING_LONG_MS else TYPING_MS)
                typing = false
                shown = i + 1
                delay(READ_MS)
            }
            delay(REPLAY_PAUSE_MS)
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("그동안 대화 다시 보기", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = LologColors.Muted, modifier = Modifier.weight(1f))
        Text("$shown / ${lines.size}", style = LologText.memo.copy(fontSize = 16.sp))
    }
    Spacer(Modifier.height(12.dp))
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        lines.take(shown).forEachIndexed { i, line ->
            // key로 회차마다 새로 올라오게
            key(i, line.id) { RisingBubble(card, line, animate) }
        }
        if (typing && shown < lines.size) {
            LineBubble(card, lines[shown]) { TypingDots(color = LologColors.Muted, dot = 5.dp) }
        }
    }
}

/** 아래에서 약 8dp 살짝 올라오며(0.2초) 나타나는 말풍선 */
@Composable
private fun RisingBubble(card: CardDetail, line: Line, animate: Boolean) {
    val p = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(Unit) { p.animateTo(1f, tween(200)) }
    LineBubble(
        card, line,
        Modifier.graphicsLayer {
            alpha = p.value
            translationY = (1f - p.value) * 8.dp.toPx()
        },
    )
}

// ── 13 만화 보기 ──

@Composable
private fun Viewer(
    comic: ComicDetail,
    card: CardDetail?,
    onCard: (Long) -> Unit,
    onZoom: () -> Unit,
    onExport: () -> Unit,
    onImageError: () -> Unit,
    modifier: Modifier,
) {
    Column(modifier.fillMaxSize()) {
        // 세로 4칸 긴 한 장: 적당한 크기(화면 너비 약 70%, 최대 300dp)로 가운데, 세로로 길면 아래로 내려 봐요
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (comic.cardDeleted) {
                // 지워진 카드의 만화: 회색 띠. 원본 카드 보기는 꺼져요
                Row(
                    Modifier.padding(horizontal = 16.dp).fillMaxWidth().background(LologColors.Paper, RoundedCornerShape(12.dp)).padding(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(Icons.Outlined.Info, null, tint = LologColors.Muted, modifier = Modifier.size(16.dp).padding(top = 2.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("원래 카드는 지워졌어요. 다시 그리기와 원본 카드 보기는 할 수 없어요.", fontSize = 13.sp, color = LologColors.Muted, lineHeight = 19.sp)
                }
            }
            PenMemo("만화를 누르면 크게 볼 수 있어요", angle = 0f)
            Box(
                Modifier
                    .fillMaxWidth(0.7f)
                    .widthIn(max = 300.dp)
                    .shadow(3.dp, RoundedCornerShape(14.dp), ambientColor = Color(0x14191F28), spotColor = Color(0x14191F28))
                    .clip(RoundedCornerShape(14.dp))
                    .background(LologColors.Paper)
                    .clickable(onClick = onZoom)
                    .padding(10.dp),
            ) {
                ComicImage(
                    comic.imageUrl, card?.lines?.map { it.text }.orEmpty(),
                    Modifier.fillMaxWidth(),
                    ratio = comic.ratio,
                    onLoadError = onImageError,
                )
                // 오른쪽 위 확대 아이콘
                Box(
                    Modifier.align(Alignment.TopEnd).padding(8.dp).size(30.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.OpenInFull, "크게 보기", tint = Color.White, modifier = Modifier.size(16.dp)) }
            }
            if (!comic.cardDeleted) {
                Row(
                    Modifier.clip(LologShape.pill).clickable { onCard(comic.cardId) }.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("원본 카드 보기", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = LologColors.Accent)
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = LologColors.Accent)
                }
            }
        }
        // 맨 아래 흰 띠 안에 "내보내기". 지워진 카드여도 그대로 돼요
        Box(Modifier.fillMaxWidth().background(LologColors.Paper).navigationBarsPadding().padding(16.dp)) {
            PrimaryButton("내보내기", onExport)
        }
    }
}

/**
 * 크게 보기: 검은 바탕 전체 화면. 처음엔 화면 너비에 맞추고, 두 손가락으로 1~4배, 확대한 채 끌어서 이동,
 * 두 번 누르면 1배 ↔ 2배. ✕나 뒤로 가기로 닫아요.
 */
@Composable
private fun ZoomViewer(comic: ComicDetail, card: CardDetail?, onImageError: () -> Unit, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        var scale by remember { mutableFloatStateOf(1f) }
        Box(Modifier.fillMaxSize().background(ZoomBg)) {
            BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding().padding(vertical = 56.dp).clipToBounds()) {
                val boxW = constraints.maxWidth.toFloat()
                val boxH = constraints.maxHeight.toFloat()
                val imgH = boxW / comic.ratio
                // 그림이 화면 밖으로 너무 나가지 않게: 가장자리까지만 움직여요
                fun clamp(o: Offset, s: Float): Offset {
                    val maxX = ((boxW * s - boxW) / 2).coerceAtLeast(0f)
                    val maxY = ((imgH * s - boxH) / 2).coerceAtLeast(0f)
                    return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
                }
                // 처음엔 그림 맨 위부터
                var offset by remember { mutableStateOf(clamp(Offset(0f, Float.MAX_VALUE), 1f)) }
                Box(
                    Modifier.fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures(onDoubleTap = { scale = if (scale > 1f) 1f else 2f; offset = clamp(offset, scale) })
                        }
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoomBy, _ ->
                                scale = (scale * zoomBy).coerceIn(1f, 4f)
                                offset = clamp(offset + pan, scale)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    ComicImage(
                        comic.imageUrl, card?.lines?.map { it.text }.orEmpty(),
                        Modifier.requiredWidth(with(LocalDensity.current) { boxW.toDp() }).graphicsLayer {
                            scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
                        },
                        ratio = comic.ratio,
                        onLoadError = onImageError,
                    )
                }
            }
            // 위: ✕ · 가운데 배율
            Box(Modifier.fillMaxWidth().statusBarsPadding().height(56.dp)) {
                IconButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterStart)) {
                    Icon(Icons.Default.Close, "닫기", tint = Color.White)
                }
                Text("${(scale * 100).roundToInt()}%", fontSize = 13.sp, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.align(Alignment.Center))
            }
            // 아래: 흐린 안내
            Text(
                "두 손가락으로 확대 · 두 번 눌러 2배 · ✕로 닫기", fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 18.dp),
            )
        }
    }
}

/** 크게 보기 바탕(11/10 확정 #0E1013) */
private val ZoomBg = Color(0xFF0E1013)

// ── 14 저장·공유 ──

@Composable
private fun ShareSheet(
    comic: ComicDetail,
    card: CardDetail?,
    network: ApiClient?,
    freshUrl: suspend () -> String?,
    onDone: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lines = card?.lines?.map { it.text }.orEmpty()
    val name = "lolog_comic_${comic.id}"

    fun withBytes(block: suspend (ByteArray) -> String?) = scope.launch {
        val bytes = comic.imageUrl?.let { ComicExport.pngBytes(it, lines, network, freshUrl) }
        onDone(if (bytes == null) "이미지를 받지 못했어요" else block(bytes))
    }

    LologSheet(onDismiss = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("만화 내보내기", fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
            ShareRow(Icons.Outlined.Download, "갤러리에 저장 (PNG)") {
                withBytes { if (ComicExport.saveToGallery(context, it, name)) "갤러리에 저장했어요" else "저장하지 못했어요" }
            }
            ShareRow(Icons.Outlined.ChatBubbleOutline, "카카오톡으로 보내기") {
                withBytes { ComicExport.share(context, ComicExport.shareUri(context, it, name), ComicExport.KAKAO); null }
            }
            ShareRow(Icons.Outlined.CameraAlt, "인스타그램 스토리") {
                withBytes { ComicExport.share(context, ComicExport.shareUri(context, it, name), ComicExport.INSTAGRAM); null }
            }
            ShareRow(Icons.Outlined.Share, "다른 앱으로 공유") {
                withBytes { ComicExport.share(context, ComicExport.shareUri(context, it, name)); null }
            }
        }
    }
}

@Composable
private fun ShareRow(icon: ImageVector, title: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(LologColors.Canvas), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = LologColors.Ink)
        }
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ── 21 만화 실패 ──

/** 실패한 만화는 3번에 안 세요(11/5 확정). 남은 횟수는 서버 comicRemaining */
@Composable
private fun Failed(comic: ComicDetail, left: Int?, busy: Boolean, onRetry: () -> Unit, onEdit: () -> Unit, modifier: Modifier) {
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(64.dp).clip(CircleShape).background(LologColors.RecSoft), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.ErrorOutline, null, tint = LologColors.Rec, modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.height(12.dp))
            Text("만화를 그리지 못했어요", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            // 실패 이유(failReason)는 보여 주지 않아요 (디버깅용)
            Text(
                "다시 시도해 주세요. 실패한 만화는 ${COMIC_LIMIT_PER_CARD}번에 세지 않아요.",
                fontSize = 14.sp, color = LologColors.Muted, textAlign = TextAlign.Center,
            )
        }
        StepList(
            listOf(
                StepItem("대기", comic.createdAt?.let { "${timeOnly(it)} 요청 받음" } ?: "", StepState.DONE),
                // 어느 단계에서 멈췄는지는 앱에 오지 않아요(명세: 실패 이유는 앱에 보내지 않음). 지난 단계를 지어내지 않아요
                StepItem("실패", "다시 시도하면 새로 그려요. 대사를 줄이거나 등장인물을 정리하면 더 잘 그려져요", StepState.FAILED),
            ),
        )
        Spacer(Modifier.weight(1f))
        PrimaryButton(
            when {
                comic.cardDeleted -> "원래 카드가 지워져서 다시 그릴 수 없어요"
                left == null -> "다시 시도"
                left > 0 -> "다시 시도 · ${left}번 남음"
                else -> "${COMIC_LIMIT_PER_CARD}번 다 그렸어요"
            },
            onRetry, loading = busy, enabled = !comic.cardDeleted && (left == null || left > 0),
        )
        if (!comic.cardDeleted) GhostButton("카드 고치기", onEdit)
    }
}
