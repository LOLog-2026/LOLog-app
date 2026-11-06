package com.lolog.app.ui.screens.comic

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
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
                    _state.update { it.copy(error = e.message) }
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
        catch (e: LologException) { _state.update { it.copy(busy = false, toast = e.message) } }
    }

    fun toast(msg: String?) = _state.update { it.copy(toast = msg) }

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
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(state.deleted) { if (state.deleted) onDeleted() }
    LaunchedEffect(state.newComicId) { state.newComicId?.let(onOtherComic) }
    LaunchedEffect(state.toast) { state.toast?.let { snackbar.showSnackbar(it); vm.toast(null) } }

    val comic = state.comic
    val stage = comic?.status?.stage
    Scaffold(
        modifier = Modifier.noteLines(),
        containerColor = Color.Transparent,
        snackbarHost = { LologSnackbarHost(snackbar) },
        topBar = {
            LologTopBar(
                when (stage) {
                    ComicStage.DONE -> ""
                    ComicStage.FAILED -> "만화"
                    else -> "만화 그리는 중"
                },
                onBack,
            ) {
                if (stage == ComicStage.DONE || stage == ComicStage.FAILED) {
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
                comic, state.card, onCard,
                onSave = {
                    // 저장: 바로 갤러리에 넣고 "갤러리에 저장했어요"
                    scope.launch {
                        val lines = state.card?.lines?.map { it.text }.orEmpty()
                        val bytes = comic.imageUrl?.let { ComicExport.pngBytes(it, lines, container.network) }
                        vm.toast(
                            when {
                                bytes == null -> "이미지를 받지 못했어요"
                                ComicExport.saveToGallery(context, bytes, "lolog_comic_${comic.id}") -> "✓ 갤러리에 저장했어요"
                                else -> "저장하지 못했어요"
                            },
                        )
                    }
                },
                onShare = { shareSheet = true },
                onImageError = vm::imageFailed,
                modifier = m,
            )
            stage == ComicStage.FAILED -> Failed(
                comic, state.card?.remaining,
                state.busy, vm::retry, { onEditCard(comic.cardId) }, m,
            )
            else -> Progress(comic, m)
        }
    }

    if (shareSheet && comic != null) {
        ShareSheet(comic, state.card, container.network, onDone = { msg -> shareSheet = false; vm.toast(msg) }, onDismiss = { shareSheet = false })
    }
    if (askDelete) {
        LologDialog(
            title = "이 만화를 지울까요?",
            body = "지운 만화는 되돌릴 수 없어요.",
            confirmText = "지우기",
            danger = true,
            loading = state.busy,
            onConfirm = { vm.delete() },
            onDismiss = { askDelete = false },
        )
    }
}

// ── 11 진행 상태 ──

@Composable
private fun Progress(comic: ComicDetail, modifier: Modifier) {
    val making = comic.status.stage == ComicStage.MAKING
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(
            Modifier.fillMaxWidth().height(200.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                .background(LologColors.AccentSoft),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            TypingDots()
            Spacer(Modifier.height(12.dp))
            Text("4컷을 그리고 있어요", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("보통 몇 분 걸려요. 앱을 닫아도 돼요.", fontSize = 13.sp, color = LologColors.Muted)
        }
        StepList(
            listOf(
                StepItem("대기", comic.createdAt?.let { "${timeOnly(it)} 요청 받음" } ?: "요청 받음", if (making) StepState.DONE else StepState.NOW),
                StepItem(
                    "만드는 중",
                    when (comic.status) {
                        ComicStatus.STORYBOARD -> "콘티를 짜고 있어요"
                        ComicStatus.DRAWING -> "콘티를 다 짰고, 컷을 그려 말풍선에 대사를 넣고 있어요"
                        else -> "콘티를 짜고 4칸을 그려요"
                    },
                    if (making) StepState.NOW else StepState.WAIT,
                ),
                StepItem("완료", "알림으로 알려 드려요", StepState.WAIT),
            ),
        )
        if (making) IndeterminateBar()
    }
}

// ── 13 만화 보기 ──

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Viewer(
    comic: ComicDetail,
    card: CardDetail?,
    onCard: (Long) -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onImageError: () -> Unit,
    modifier: Modifier,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    Column(modifier.fillMaxSize()) {
    // 세로 4칸 긴 한 장을 폰 가로에 맞춰 위아래로 스크롤해서 봐요
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(card?.summary ?: comic.cardSummary ?: "4컷만화", style = MaterialTheme.typography.headlineMedium)
        Text(
            listOfNotNull(
                dayLabel(card?.recordedAt ?: comic.createdAt).ifBlank { null },
                card?.speakers?.displayNames()?.joinToString(", ")?.ifBlank { null },
            ).joinToString(" · "),
            fontSize = 13.sp, color = LologColors.Muted,
        )
        if (comic.cardDeleted) {
            // 지워진 카드의 만화: 회색 띠. 다시 그리기·원본 카드 보기는 꺼져요
            Row(
                Modifier.fillMaxWidth().background(LologColors.Canvas, androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).padding(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(Icons.Outlined.Info, null, tint = LologColors.Muted, modifier = Modifier.size(16.dp).padding(top = 2.dp))
                Spacer(Modifier.width(8.dp))
                Text("원래 카드는 지워졌어요. 다시 그리기와 원본 카드 보기는 할 수 없어요.", fontSize = 13.sp, color = LologColors.Muted, lineHeight = 19.sp)
            }
        } else {
            PenMemo("아래로 내려 보기 ↓ · 두 손가락으로 확대")
        }
        // 핀치 확대. 확대 안 했을 때는 한 손가락 끌기를 스크롤에 넘김
        Box(Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).pointerInput(Unit) {
            // 두 손가락일 때(또는 이미 확대했을 때)만 확대·이동을 가져가요.
            // 한 손가락으로 끌면 손대지 않아서 바깥 세로 스크롤이 그대로 움직여요.
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                do {
                    val event = awaitPointerEvent()
                    val fingers = event.changes.count { it.pressed }
                    if (fingers >= 2 || scale > 1f) {
                        scale = (scale * event.calculateZoom()).coerceIn(1f, 4f)
                        offset = if (scale == 1f) androidx.compose.ui.geometry.Offset.Zero else offset + event.calculatePan()
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                } while (event.changes.any { it.pressed })
            }
        }) {
            ComicImage(
                comic.imageUrl, card?.lines?.map { it.text }.orEmpty(),
                Modifier.fillMaxWidth().graphicsLayer {
                    scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
                },
                ratio = comic.ratio,
                onLoadError = onImageError,
            )
        }
        if (!comic.cardDeleted) {
            Row(
                Modifier.fillMaxWidth().clickable { onCard(comic.cardId) }.padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("원본 카드 보기", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = LologColors.Accent)
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = LologColors.Accent)
            }
        }
    }
    // 저장·공유 버튼은 아래에 고정. 지워진 카드여도 그대로 돼요
    Row(
        Modifier.fillMaxWidth().background(LologColors.Paper).navigationBarsPadding().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        GhostButton("저장", onSave, Modifier.weight(1f))
        PrimaryButton("공유", onShare, Modifier.weight(1f))
    }
    }
}

// ── 14 저장·공유 ──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareSheet(comic: ComicDetail, card: CardDetail?, network: ApiClient?, onDone: (String?) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lines = card?.lines?.map { it.text }.orEmpty()
    val name = "lolog_comic_${comic.id}"

    fun withBytes(block: suspend (ByteArray) -> String?) = scope.launch {
        val bytes = comic.imageUrl?.let { ComicExport.pngBytes(it, lines, network) }
        onDone(if (bytes == null) "이미지를 받지 못했어요" else block(bytes))
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = LologColors.Paper) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("만화 내보내기", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
            ShareRow(Icons.Outlined.Download, "갤러리에 저장", "PNG 이미지") {
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
private fun ShareRow(icon: ImageVector, title: String, sub: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(LologColors.Canvas), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = LologColors.Ink)
        }
        Column {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            sub?.let { Text(it, fontSize = 12.sp, color = LologColors.Muted) }
        }
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
                // 어느 단계에서 멈췄는지는 API에 없어서 "만드는 중"까지 지난 것으로 보여 줘요
                StepItem("만드는 중", "콘티까지 짰어요", StepState.DONE),
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
