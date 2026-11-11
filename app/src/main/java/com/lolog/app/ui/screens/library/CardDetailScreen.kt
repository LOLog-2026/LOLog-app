package com.lolog.app.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.SmartDisplay
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lolog.app.data.model.*
import com.lolog.app.data.repo.LologException
import com.lolog.app.data.repo.LologRepository
import com.lolog.app.ui.components.*
import com.lolog.app.ui.navigation.LocalContainer
import com.lolog.app.ui.theme.LologColors
import com.lolog.app.ui.theme.LologShape
import com.lolog.app.util.dayLabel
import com.lolog.app.util.longDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/*
 * 카드 상세 (mockups/카드-결정사항-시안.png ②③⑤, 보관함-만화-시안.png ③④⑥)
 *  - 제목 아래 "녹음 날짜·시간 · 장소". 이름 없는 화자는 화자1·2·3
 *  - 이 카드로 만든 만화: "1 / 3번 · 실패는 안 셈". 실패한 만화는 그 자리에서 다시 시도
 *  - 만화 그리는 중: 고치기·지우기·다시 그리기 모두 꺼짐 (서버도 409 CARD_LOCKED / COMIC_IN_PROGRESS)
 *  - 아래 버튼 "컨텐츠 만들기" → 4컷 만화 / 쇼츠(준비 중) 고르는 시트 (컨텐츠 시안 v3 ①②)
 *  - 3번 다 씀: 회색 버튼 "4컷은 3번 다 만들었어요"
 *  - 카드를 지워도 만화는 '컨텐츠'에 남아요
 */

class CardDetailViewModel(private val repo: LologRepository, private val id: Long) : ViewModel() {
    data class State(
        val card: CardDetail? = null,
        val comics: Map<Long, ComicDetail> = emptyMap(),
        val loading: Boolean = true,
        val error: String? = null,
        val notFound: Boolean = false,
        val busy: Boolean = false,
        val deleted: Boolean = false,
        val requestedComicId: Long? = null,
        val toast: String? = null,
        val deleteFailed: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /** GET /api/cards/{id} */
    fun load() = viewModelScope.launch {
        try {
            val card = repo.card(id)
            _state.update { it.copy(card = card, loading = false, error = null) }
            // 만화 날짜·그림은 카드 응답에 없어서 만화를 따로 조회해요
            val comics = card.comics.mapNotNull { c -> runCatching { repo.comic(c.id) }.getOrNull()?.let { c.id to it } }.toMap()
            _state.update { it.copy(comics = comics) }
        } catch (e: LologException) {
            _state.update {
                if (e.code == ErrorCodes.CARD_NOT_FOUND) it.copy(loading = false, notFound = true)
                else it.copy(loading = false, error = errorText(e))
            }
        }
    }

    /** POST /api/cards/{id}/comics → 202, comicId. 실패한 만화 "다시 시도"도 같은 요청이에요 */
    fun requestComic() = viewModelScope.launch {
        _state.update { it.copy(busy = true) }
        try {
            val res = repo.requestComic(id)
            _state.update { it.copy(busy = false, requestedComicId = res.comicId) }
        } catch (e: LologException) {
            _state.update { it.copy(busy = false, toast = errorText(e)) }
            load()
        }
    }

    /** DELETE /api/cards/{id} */
    fun delete() = viewModelScope.launch {
        _state.update { it.copy(busy = true) }
        try {
            repo.deleteCard(id)
            _state.update { it.copy(busy = false, deleted = true) }
        } catch (e: LologException) {
            // 공용 ③ 가벼운 실패: 아래 스낵바 + 다시 시도
            _state.update { it.copy(busy = false, deleteFailed = true, toast = if (e.code == ErrorCodes.CARD_LOCKED) errorText(e) else "카드를 지우지 못했어요") }
        }
    }

    /** 화자 고치기 팝업에서 고른 것을 바로 PATCH해요(바뀐 것만). 성공·실패는 아래 띠로 알려요 */
    fun saveSpeaker(key: String, pick: SpeakerPick) {
        val edit = _state.value.card?.speakers?.firstOrNull { it.key == key }?.editFor(pick) ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try {
                val card = repo.updateCard(id, edit)
                _state.update { it.copy(busy = false, card = card, toast = speakerSavedText(edit)) }
            } catch (e: LologException) {
                _state.update { it.copy(busy = false, toast = errorText(e)) }
            }
        }
    }

    /**
     * 음성 서명 주소(10분)가 만료됐을 때 카드 자세히를 다시 불러 새 주소를 받아요(replay.md 3장).
     * 그사이 음성 상태가 바뀌었으면 화면도 같이 바뀌어요
     */
    suspend fun freshAudioUrl(): String? = runCatching { repo.card(id) }.getOrNull()?.let { card ->
        _state.update { it.copy(card = card) }
        card.audio.url
    }

    fun consumed() = _state.update { it.copy(requestedComicId = null, toast = null, deleteFailed = false) }
}

// ── 08 카드 상세 · 10 컨텐츠 만들기 · 22 카드 지우기 · 24 3번 다 씀 ──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardDetailScreen(
    cardId: Long,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onComic: (Long) -> Unit,
    onDeleted: () -> Unit,
    onNotFound: () -> Unit,
    /** 화자 팝업의 "내 캐릭터 만들기" */
    onMakeCharacter: () -> Unit = {},
    savedJustNow: Boolean = false,
    onSavedShown: () -> Unit = {},
) {
    val repo = LocalContainer.current.repository
    val vm = viewModel { CardDetailViewModel(repo, cardId) }
    val state by vm.state.collectAsState()
    var menu by remember { mutableStateOf(false) }
    var askDelete by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(false) }
    var speakerSheet by remember { mutableStateOf<String?>(null) } // 화자 고치기 팝업을 연 화자 key
    val charVm = viewModel { SpeakerCharacterViewModel(repo) }
    val charState by charVm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { vm.load() }
    LaunchedEffect(state.deleted) { if (state.deleted) onDeleted() }
    LaunchedEffect(state.notFound) { if (state.notFound) onNotFound() }
    LaunchedEffect(state.requestedComicId) { state.requestedComicId?.let { sheet = false; vm.consumed(); onComic(it) } }
    LaunchedEffect(state.toast) {
        val msg = state.toast ?: return@LaunchedEffect
        val retry = state.deleteFailed
        askDelete = false
        // 띠를 다 보여 준 뒤에 비워요. 먼저 비우면 이 효과가 다시 시작되면서 띠가 바로 사라져요
        val r = snackbar.showSnackbar(msg, actionLabel = if (retry) "다시 시도" else null)
        vm.consumed()
        if (r == SnackbarResult.ActionPerformed && retry) vm.delete()
    }
    // 카드 고치기에서 저장하고 돌아오면
    LaunchedEffect(savedJustNow) {
        if (savedJustNow) { onSavedShown(); snackbar.showSnackbar("저장했어요. ${LIBRARY_NAME}에도 바로 반영됐어요") }
    }

    val card = state.card
    Scaffold(
        // 11/10 확정: 흰색에서 아래로 아주 옅은 크림으로
        modifier = Modifier.background(Brush.verticalGradient(listOf(LologColors.Paper, Cream))),
        containerColor = Color.Transparent,
        snackbarHost = { LologSnackbarHost(snackbar) },
        topBar = {
            CardTopBar(card, onBack) {
                IconButton(onClick = onEdit, enabled = card != null && !card.locked) {
                    Icon(Icons.Outlined.Edit, "카드 고치기", modifier = Modifier.size(21.dp))
                }
                Box {
                    IconButton(onClick = { menu = true }, enabled = card != null) { Icon(Icons.Default.MoreVert, "더 보기") }
                    DropdownMenu(menu, { menu = false }) {
                        val locked = card?.locked == true
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, tint = if (locked) LologColors.Muted else LologColors.Rec) },
                            text = {
                                Column {
                                    Text("카드 지우기", color = if (locked) LologColors.Muted else LologColors.Rec)
                                    if (locked) Text("만화를 다 그린 뒤에 지울 수 있어요", fontSize = 11.sp, color = LologColors.Muted)
                                }
                            },
                            enabled = !locked,
                            onClick = { menu = false; askDelete = true },
                        )
                    }
                }
            }
        },
        bottomBar = { card?.let { BottomActions(it, state.busy, onDraw = { sheet = true }) } },
    ) { pad ->
        when {
            state.loading -> SkeletonList(Modifier.padding(pad), count = 3)
            card == null -> ListErrorState(onRetry = { vm.load() }, modifier = Modifier.padding(pad), title = "카드를 불러오지 못했어요")
            else -> CardBody(
                card, state.comics, state.busy, onComic, onRetry = { vm.requestComic() }, Modifier.padding(pad),
                freshAudioUrl = vm::freshAudioUrl,
                // 만화 그리는 중에는 고칠 수 없어서(409 CARD_LOCKED) 화자를 눌러도 팝업을 열지 않아요
                onSpeakerClick = if (card.locked) null else { key -> speakerSheet = key; charVm.open() },
            )
        }
    }

    if (sheet && card != null) {
        ComicRequestSheet(card, busy = state.busy, onConfirm = vm::requestComic, onDismiss = { sheet = false })
    }
    speakerSheet?.let { key ->
        val sp = card?.speakers?.firstOrNull { it.key == key } ?: return@let
        SpeakerCharacterSheet(
            speaker = sp,
            placeholder = "화자${card.speakers.indexOf(sp) + 1}",
            data = charState,
            onRetry = { charVm.open() },
            onClose = { pick -> speakerSheet = null; vm.saveSpeaker(key, pick) },
            onMakeMine = onMakeCharacter,
            freshVoiceUrl = { charVm.freshVoiceUrl(it) },
        )
    }
    if (askDelete) {
        LologDialog(
            title = "이 카드를 지울까요?",
            body = "지운 카드는 되돌릴 수 없어요. 이 카드로 만든 만화는 '컨텐츠'에 그대로 남아요.",
            confirmText = "지우기",
            danger = true,
            loading = state.busy,
            onConfirm = { vm.delete() },
            onDismiss = { askDelete = false },
        )
    }
}

/** 카드 보기 바탕 아래쪽 크림색(11/10 확정 #FFFCF5) */
private val Cream = Color(0xFFFFFCF5)

/** 위 칸: ← · 제목(13.5sp) + "11월 4일 오후 7:20 녹음 · 홍대 카페"(10sp) · 오른쪽 아이콘들. 아래 1dp 선 */
@Composable
private fun CardTopBar(card: CardDetail?, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit) {
    // 제목·아이콘은 진한 Ink (탭 Scaffold의 기본 글자색은 회색이라 정해 줘요)
    CompositionLocalProvider(LocalContentColor provides LologColors.Ink) {
    Column(Modifier.fillMaxWidth().background(LologColors.Paper).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로") }
            Column(Modifier.weight(1f)) {
                if (card != null) {
                    Text(card.summary, fontSize = 13.5.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull("${longDate(card.recordedAt ?: card.createdAt)} 녹음", card.place?.takeIf { it.isNotBlank() }).joinToString(" · "),
                        fontSize = 10.sp, color = LologColors.Muted, maxLines = 1,
                    )
                }
            }
            actions()
        }
        HorizontalDivider(thickness = 1.dp, color = LologColors.Line)
    }
    }
}

/** 아래: "이 카드로 만든 4컷 n / 3번" + 전체 너비 알약 "컨텐츠 만들기". 남은 횟수는 4컷 기준, 잠김 규칙은 그대로 */
@Composable
private fun BottomActions(card: CardDetail, busy: Boolean, onDraw: () -> Unit) {
    val full = card.remaining <= 0 && !card.locked
    Column(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        when {
            card.locked -> LockNote("만화 그리는 중엔 고치거나 지울 수 없어요")
            full -> LockNote("한 카드로는 ${COMIC_LIMIT_PER_CARD}번까지 그릴 수 있어요")
            else -> Text(
                "이 카드로 만든 4컷 ${card.used} / ${COMIC_LIMIT_PER_CARD}번", fontSize = 11.sp, color = LologColors.Muted,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp),
            )
        }
        if (full) {
            PrimaryButton("4컷은 ${COMIC_LIMIT_PER_CARD}번 다 만들었어요", {}, enabled = false)
        } else {
            PrimaryButton(
                text = when {
                    card.locked -> "그리는 중…"
                    card.used == 0 && card.comics.isEmpty() -> "컨텐츠 만들기"
                    else -> "컨텐츠 또 만들기 · 4컷 ${card.remaining}번 남음"
                },
                onClick = onDraw, enabled = !card.locked, loading = busy,
            )
        }
    }
}

@Composable
private fun LockNote(text: String) {
    Row(Modifier.padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Lock, null, tint = LologColors.Muted, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, fontSize = 12.sp, color = LologColors.Muted)
    }
}

@Composable
private fun CardBody(
    card: CardDetail,
    comics: Map<Long, ComicDetail>,
    busy: Boolean,
    onComic: (Long) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier,
    onSpeakerClick: ((String) -> Unit)? = null,
    freshAudioUrl: suspend () -> String? = { null },
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 가운데 위 "● 웃김 · 대화 n줄"
        KindChip(card.kind, Modifier.align(Alignment.CenterHorizontally), suffix = "대화 ${card.lines.size}줄")
        // 순간 다시 듣기: 대사 위에 재생 줄(READY) 또는 안내 한 줄(PENDING). NONE이면 아무것도 없어요
        CardAudioSection(card, freshAudioUrl)
        // 대사는 처음부터 전부 말풍선으로(재생 없음)
        CardLines(card, onSpeakerClick = onSpeakerClick)

        if (card.comics.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("이 카드로 만든 만화", fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                val full = card.remaining <= 0
                Text(
                    "${card.used} / ${COMIC_LIMIT_PER_CARD}번" + if (!full && card.comics.any { it.status == ComicStatus.FAILED }) " · 실패는 안 셈" else "",
                    fontSize = 12.sp, fontWeight = if (full) FontWeight.Bold else FontWeight.Normal,
                    color = if (full) LologColors.Rec else LologColors.Muted,
                )
            }
            // 만든 순서대로 1번째, 2번째…
            card.comics.sortedBy { it.id }.forEachIndexed { i, ref ->
                ComicRow(i + 1, ref, comics[ref.id], card, busy, onOpen = { onComic(ref.id) }, onRetry = onRetry)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ComicRow(no: Int, ref: ComicRef, detail: ComicDetail?, card: CardDetail, busy: Boolean, onOpen: () -> Unit, onRetry: () -> Unit) {
    val stage = ref.status.stage
    Row(
        Modifier.fillMaxWidth().softCard(LologShape.sm).clickable(onClick = onOpen).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(52.dp).clip(LologShape.sm).background(LologColors.Canvas)) {
            // 만화 화면과 같은 원본(imageUrl)이라 같은 캐시 이름을 써요(#35)
            if (stage == ComicStage.DONE) ComicImage(detail?.imageUrl, card.lines.map { it.text }, Modifier.fillMaxSize(), small = true, cacheKey = comicCacheKey(ref.id))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("${no}번째 만화", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(
                when (stage) {
                    ComicStage.DONE -> listOfNotNull("완료", detail?.createdAt?.let { dayLabel(it) }?.ifBlank { null }).joinToString(" · ")
                    ComicStage.FAILED -> "실패 · 다시 시도해 주세요"
                    ComicStage.MAKING -> "그리고 있어요 · 1~2분 걸려요"
                    ComicStage.WAITING -> "곧 그리기 시작해요"
                },
                fontSize = 12.sp, color = LologColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        when (stage) {
            ComicStage.DONE -> Text("완료", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = LologColors.MemoryText)
            // 실패: 이유는 안 보여 주고 그 자리에서 다시 시도 (같은 POST 요청). 그리는 중이면 꺼져요
            ComicStage.FAILED -> Box(
                Modifier.clip(LologShape.pill).border(1.dp, if (card.locked) LologColors.Line else LologColors.Rec, LologShape.pill)
                    .clickable(enabled = !card.locked && !busy, onClick = onRetry)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) { Text("다시 시도", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (card.locked) LologColors.Muted else LologColors.Rec) }
            else -> StageBadge(stage)
        }
    }
}

/**
 * 대사 말풍선: 화자 목록 순서가 짝수(0, 2…)면 왼쪽, 홀수면 오른쪽. 이름 없는 화자는 "화자1·2".
 * 카드 보기와 만화 그리는 중 화면이 같이 써요.
 */
@Composable
fun CardLines(
    card: CardDetail,
    modifier: Modifier = Modifier,
    lines: List<Line> = card.lines,
    onSpeakerClick: ((String) -> Unit)? = null,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        lines.forEach { line -> LineBubble(card, line, onSpeakerClick = onSpeakerClick) }
    }
}

/** 화자에 그림이 붙어 있으면 이름 동그라미 대신 그 그림. [onSpeakerClick]이 있으면 화자 그림·이름을 눌러 화자 고치기 */
@Composable
fun LineBubble(
    card: CardDetail,
    line: Line,
    modifier: Modifier = Modifier,
    onSpeakerClick: ((String) -> Unit)? = null,
    content: (@Composable () -> Unit)? = null,
) {
    val idx = card.speakers.indexOfFirst { it.key == line.speaker }.coerceAtLeast(0)
    ChatBubble(
        card.speakers.displayName(line.speaker), idx, line.text, left = idx % 2 == 0, modifier = modifier, content = content,
        character = card.speakers.getOrNull(idx)?.character,
        onAvatarClick = onSpeakerClick?.let { { it(line.speaker) } },
    )
}

/**
 * 10 컨텐츠 만들기 시트(컨텐츠 시안 v3 ②): 4컷 만화 / 쇼츠 중 고르기.
 * 쇼츠는 서버 명세·API가 아직 없어서 회색 "준비 중"으로 못 골라요. 그래서 4컷이 늘 골라져 있어요
 */
@Composable
private fun ComicRequestSheet(card: CardDetail, busy: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    LologSheet(onDismiss = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("이 카드로 무엇을 만들까요?", fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(bottom = 4.dp))
            ContentOption(
                title = "4컷 만화",
                body = "세로 4칸 그림. 보통 몇 분 걸려요.",
                note = "이 카드로 ${COMIC_LIMIT_PER_CARD}번 중 ${card.remaining}번 남음",
                selected = true,
                thumb = { FourCutThumb() },
            )
            ContentOption(
                title = "쇼츠",
                body = "짧은 세로 영상. 곧 만들 수 있어요.",
                selected = false,
                enabled = false,
                thumb = { Icon(Icons.Outlined.SmartDisplay, null, tint = LologColors.Muted, modifier = Modifier.size(20.dp)) },
            )
            Text("다 되면 알림으로 알려 드려요. 실패는 횟수에 안 세요.", fontSize = 12.sp, color = LologColors.Muted, modifier = Modifier.padding(vertical = 2.dp))
            PrimaryButton("4컷 만화로 만들기", onConfirm, loading = busy)
            GhostButton("취소", onDismiss)
        }
    }
}

/** 고르기 한 줄: 왼쪽 작은 그림 · 제목(+ 꺼졌으면 "준비 중" 칩) · 설명 · 오른쪽 라디오. 고른 줄은 코랄 테두리 */
@Composable
private fun ContentOption(
    title: String,
    body: String,
    selected: Boolean,
    thumb: @Composable () -> Unit,
    note: String? = null,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (selected) LologColors.Accent.copy(alpha = 0.05f) else if (enabled) LologColors.Paper else OptionOff)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) LologColors.Accent else LologColors.Line, shape)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(48.dp).clip(LologShape.sm).background(LologColors.Paper).border(1.dp, LologColors.Line, LologShape.sm),
            contentAlignment = Alignment.Center,
        ) { thumb() }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = if (enabled) LologColors.Ink else LologColors.Muted)
                if (!enabled) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "준비 중", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = LologColors.Muted,
                        modifier = Modifier.clip(LologShape.pill).background(LologColors.Canvas).padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(body, fontSize = 12.sp, color = LologColors.Muted)
            note?.let { Text(it, fontSize = 12.sp, color = LologColors.Muted) }
        }
        RadioButton(
            selected = selected, onClick = null, enabled = enabled,
            colors = RadioButtonDefaults.colors(selectedColor = LologColors.Accent, unselectedColor = LologColors.Line, disabledUnselectedColor = LologColors.Line),
        )
    }
}

/** 4컷 만화 작은 그림: 세로 4칸(노랑·분홍·민트·노랑) */
@Composable
private fun FourCutThumb() {
    Column(Modifier.width(30.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        listOf(LologColors.AccentSoft, LologColors.RecSoft, LologColors.MemorySoft, LologColors.AccentSoft).forEach {
            Box(Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(1.5.dp)).background(it))
        }
    }
}

/** 꺼진 고르기 줄 바탕(시안의 아주 옅은 회색) */
private val OptionOff = Color(0xFFF7F8FA)
