package com.lolog.app.ui.screens.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/*
 * 카드 고치기 (mockups/카드-고치기-시안.png)
 *  ① 기본: 아무것도 안 바꿔서 저장이 꺼져 있어요
 *  ② 고치는 중: 화자 이름을 바꾸면 대사 이름표도 한 번에 바뀌어요. 고친 줄은 노란 표시, 입력 중엔 남은 글자 수
 *  ③ 화자 고르기: 대사 앞 이름표를 누르면 아래에서 올라오는 창
 *  ④ 빈 대사 오류: 빨간 안내, 저장 꺼짐. 이름 10자, 장소 20자, 대사 200자
 *  ⑤ 나가기 확인: 고친 게 있는데 X를 누르면 물어봐요
 * 서버에는 PATCH /api/cards/{id}로 바뀐 것만 보내요. 응답은 고친 뒤의 카드 전체(명세).
 */

/** 고친 줄 배경 (연한 형광펜) */
private val ChangedBg = Color(0xFFFFF6D6) // 11/10 확정: 고친 줄 연노랑

class CardEditViewModel(private val repo: LologRepository, private val id: Long) : ViewModel() {
    data class State(
        val original: CardDetail? = null,
        val saving: Boolean = false,
        val saved: Boolean = false,
        val loadError: String? = null,
        val toast: String? = null,
        /** 서버가 알려 준 틀린 칸 (예: lines[2].text). 명세엔 없는 제안이라 대개 null이고, 빈 대사는 앱이 먼저 막아요 */
        val badField: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    // 편집 중인 값
    var place by mutableStateOf("")
    val speakers: SnapshotStateList<Speaker> = mutableStateListOf()
    val lines: SnapshotStateList<Line> = mutableStateListOf()

    init { load() }

    fun load() = viewModelScope.launch {
        _state.update { it.copy(loadError = null) }
        runCatching { repo.card(id) }
            .onSuccess { c ->
                place = c.place.orEmpty()
                speakers.clear(); speakers.addAll(c.speakers)
                lines.clear(); lines.addAll(c.lines)
                _state.update { it.copy(original = c) }
            }
            .onFailure { e -> _state.update { it.copy(loadError = errorText(e)) } }
    }

    private fun patch(): CardEdit {
        val o = _state.value.original ?: return CardEdit()
        // 명세 PATCH: 바꾼 화자·대사만 보내요. 화자는 이름과 그림을 따로 봐요
        return CardEdit(
            speakers = speakers.filter { speakerChanged(it) }.ifEmpty { null },
            characters = speakers.filter { characterChanged(it) }.associate { it.key to it.character }.ifEmpty { null },
            lines = lines.mapNotNull { now ->
                val before = o.lines.firstOrNull { it.id == now.id } ?: return@mapNotNull LineEdit(now.id, now.speaker, now.text)
                LineEdit(
                    id = now.id,
                    speaker = now.speaker.takeIf { it != before.speaker },
                    text = now.text.takeIf { it != before.text },
                ).takeIf { it.speaker != null || it.text != null }
            }.ifEmpty { null },
            place = place.trim().takeIf { it != o.place.orEmpty() },
        )
    }

    val changed: Boolean get() = !patch().isEmpty
    val hasEmptyLine: Boolean get() = lines.any { it.text.isBlank() }
    val canSave: Boolean get() = _state.value.original != null && changed && !hasEmptyLine && !_state.value.saving

    fun lineChanged(line: Line): Boolean = _state.value.original?.lines?.firstOrNull { it.id == line.id } != line
    fun speakerChanged(sp: Speaker): Boolean = _state.value.original?.speakers?.firstOrNull { it.key == sp.key }?.name != sp.name
    fun characterChanged(sp: Speaker): Boolean =
        characterDiffers(_state.value.original?.speakers?.firstOrNull { it.key == sp.key }?.character, sp.character)

    /** 화자 고치기 팝업에서 고른 이름·그림. 서버에는 "저장" 때 한꺼번에 보내요 */
    fun applySpeaker(key: String, pick: SpeakerPick) {
        val i = speakers.indexOfFirst { it.key == key }
        if (i >= 0) speakers[i] = speakers[i].copy(name = pick.name, character = if (pick.characterChanged) pick.character else speakers[i].character)
    }

    fun setLineText(i: Int, text: String) {
        lines[i] = lines[i].copy(text = text.take(TextLimits.LINE))
        if (_state.value.badField == "lines[$i].text") _state.update { it.copy(badField = null) }
    }

    fun setLineSpeaker(i: Int, key: String) { lines[i] = lines[i].copy(speaker = key) }

    /** PATCH /api/cards/{id} — speakers[], lines[], place 중 바꾼 것만 */
    fun save() {
        if (!canSave) return
        val p = patch()
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            try {
                repo.updateCard(id, p)
                _state.update { it.copy(saving = false, saved = true) }
            } catch (e: LologException) {
                _state.update { it.copy(saving = false, toast = errorText(e), badField = e.field) }
            }
        }
    }

    fun toastShown() = _state.update { it.copy(toast = null) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardEditScreen(
    cardId: Long,
    onBack: () -> Unit,
    onSaved: () -> Unit = onBack,
    /** 화자 팝업의 "내 캐릭터 만들기". 고치던 내용은 이 화면에 그대로 남아요 */
    onMakeCharacter: () -> Unit = {},
) {
    val repo = LocalContainer.current.repository
    val vm = viewModel { CardEditViewModel(repo, cardId) }
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var askLeave by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Speaker?>(null) }
    val charVm = viewModel { SpeakerCharacterViewModel(repo) }
    val charState by charVm.state.collectAsState()
    var pickingFor by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(state.saved) { if (state.saved) onSaved() }
    LaunchedEffect(state.toast) { state.toast?.let { snackbar.showSnackbar(it); vm.toastShown() } }

    // ⑤ 고친 게 있으면 그냥 나가지 않고 물어봐요
    val leave = { if (vm.changed) { askLeave = true } else onBack() }
    BackHandler(enabled = vm.changed) { askLeave = true }

    // 고르는 중인 화자: 이름을 바꾸는 화자, 또는 화자를 고르는 대사의 화자
    val activeSpeaker = renaming?.key ?: pickingFor?.let { vm.lines.getOrNull(it)?.speaker }

    Scaffold(
        containerColor = LologColors.Paper,
        snackbarHost = { LologSnackbarHost(snackbar) },
        topBar = {
            // 위 칸: ✕ · "카드 고치기" · 코랄 작은 알약 "저장". 아래 1dp 선
            Column(Modifier.background(LologColors.Paper).statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = leave) { Icon(Icons.Default.Close, "닫기") }
                    Text("카드 고치기", fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    SaveChip(enabled = vm.canSave, loading = state.saving, onClick = vm::save)
                }
                HorizontalDivider(thickness = 1.dp, color = LologColors.Line)
            }
        },
    ) { pad ->
        val original = state.original
        if (original == null) {
            if (state.loadError != null) ListErrorState(onRetry = { vm.load() }, modifier = Modifier.padding(pad), title = "카드를 불러오지 못했어요")
            else SkeletonList(Modifier.padding(pad), count = 3)
            return@Scaffold
        }
        Column(
            Modifier.padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (original.locked) {
                Text(
                    "만화를 만드는 중이에요. 다 그려진 뒤에 고칠 수 있어요.",
                    fontSize = 13.sp, color = LologColors.Rec,
                    modifier = Modifier.fillMaxWidth().background(LologColors.RecSoft, LologShape.sm).padding(12.dp),
                )
            }

            // 화자: 이름표를 누르면 화자 고치기 팝업(이름·그림)
            EditLabel("화자 (눌러서 이름·그림 바꾸기)")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                vm.speakers.forEachIndexed { i, sp ->
                    SpeakerChip(vm.speakers.displayName(sp.key), i, active = sp.key == activeSpeaker, character = sp.character) {
                        renaming = sp; charVm.open()
                    }
                }
            }

            // 장소 (선택, 20자)
            PlaceField(vm.place) { vm.place = it.take(TextLimits.PLACE) }

            // 대사: 글자를 누르면 바로 고치기, 이름표를 누르면 화자 고르기
            EditLabel("대사")
            vm.lines.forEachIndexed { i, line ->
                LineRow(
                    line = line,
                    speakers = vm.speakers,
                    changed = vm.lineChanged(line),
                    error = line.text.isBlank() || state.badField == "lines[$i].text",
                    onText = { vm.setLineText(i, it) },
                    onPickSpeaker = { pickingFor = i },
                )
            }
            Text(
                if (vm.hasEmptyLine) "빈 대사가 있으면 저장 버튼이 꺼져요. 이름 ${TextLimits.SPEAKER_NAME}자, 장소 ${TextLimits.PLACE}자, 대사 ${TextLimits.LINE}자까지."
                else "글자를 누르면 바로 고칠 수 있어요. 이름표를 누르면 누가 한 말인지 바꿀 수 있어요.",
                fontSize = 12.sp, color = LologColors.Muted, lineHeight = 18.sp,
            )
            if (vm.lines.any { vm.lineChanged(it) }) PenMemo("노란 표시 = 고친 줄", angle = -2f)
            Spacer(Modifier.height(24.dp))
        }
    }

    renaming?.let { sp ->
        SpeakerCharacterSheet(
            speaker = sp,
            placeholder = "화자${vm.speakers.indexOfFirst { it.key == sp.key } + 1}",
            data = charState,
            onRetry = { charVm.open() },
            onClose = { vm.applySpeaker(sp.key, it); renaming = null },
            onMakeMine = onMakeCharacter,
        )
    }
    pickingFor?.let { i ->
        vm.lines.getOrNull(i)?.let { line ->
            SpeakerSheet(
                speakers = vm.speakers,
                selected = line.speaker,
                onPick = { vm.setLineSpeaker(i, it) },
                onDismiss = { pickingFor = null },
            )
        }
    }
    if (askLeave) {
        LologDialog(
            title = "고친 내용이 사라져요",
            body = "저장하지 않고 나갈까요?",
            confirmText = "나가기",
            dismissText = "계속 고치기",
            danger = true,
            onConfirm = { askLeave = false; onBack() },
            onDismiss = { askLeave = false },
        )
    }
}

/** 위쪽 저장 버튼: 바꾼 게 있고 빈 대사가 없을 때만 파랗게 켜져요 */
@Composable
private fun SaveChip(enabled: Boolean, loading: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.padding(end = 12.dp).clip(LologShape.pill)
            .background(if (enabled || loading) LologColors.Accent else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .height(34.dp).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) TypingDots(color = LologColors.Paper, dot = 5.dp)
        else Text("저장", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (enabled) LologColors.Paper else LologColors.Muted)
    }
}

@Composable
private fun EditLabel(text: String) {
    Text(text, fontSize = 11.5.sp, color = LologColors.Muted)
}

/** 화자 칩 바탕: 화자 색을 옅게 */
private fun speakerSoft(index: Int): Color = speakerColor(index).copy(alpha = 0.35f).compositeOver(LologColors.Paper)

/** 화자 칩(알약): 누르면 화자 고치기 팝업. 고르는 중인 칩은 코랄 테두리 + 연필. 그림이 붙었으면 앞에 작은 그림 */
@Composable
private fun SpeakerChip(name: String, index: Int, active: Boolean, character: SpeakerCharacter?, onClick: () -> Unit) {
    Row(
        Modifier.clip(LologShape.pill)
            .background(speakerSoft(index))
            .then(if (active) Modifier.border(1.5.dp, LologColors.Accent, LologShape.pill) else Modifier)
            .clickable(onClick = onClick)
            .height(34.dp).padding(start = if (character != null) 4.dp else 14.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (character?.thumbnailUrl != null) {
            SpeakerAvatar(name, index, size = 26.dp, thumbnailUrl = character.thumbnailUrl, cacheKey = character.cacheKey)
            Spacer(Modifier.width(6.dp))
        }
        Text(name, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        if (active) {
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Outlined.Edit, "이름 바꾸기", tint = LologColors.Ink, modifier = Modifier.size(14.dp))
        }
    }
}

/** 장소 칸: 핀 아이콘 + 글 + 오른쪽 "n/20" */
@Composable
private fun PlaceField(value: String, onChange: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(LologShape.sm).border(1.dp, LologColors.Line, LologShape.sm).height(48.dp).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Place, null, tint = LologColors.Muted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text("장소를 적어 주세요 (선택)", fontSize = 14.sp, color = LologColors.Muted)
            BasicTextField(
                value, onChange, singleLine = true,
                textStyle = TextStyle(fontSize = 14.sp, color = LologColors.Ink),
                cursorBrush = SolidColor(LologColors.Accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text("${value.length}/${TextLimits.PLACE}", fontSize = 11.sp, color = LologColors.Muted)
    }
}

/**
 * 대사 한 줄: 왼쪽 화자 칩, 오른쪽 글. 고친 줄은 연노랑 바탕,
 * 지금 고치는 줄은 글 칸에 코랄 1.5dp 테두리 + 오른쪽 아래 "n/200". 비거나 너무 길면 빨간 테두리와 안내
 */
@Composable
private fun LineRow(
    line: Line,
    speakers: List<Speaker>,
    changed: Boolean,
    error: Boolean,
    onText: (String) -> Unit,
    onPickSpeaker: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val border = when {
        error -> LologColors.Rec
        focused -> LologColors.Accent
        else -> Color.Transparent
    }
    Column(
        Modifier.fillMaxWidth()
            .clip(LologShape.sm)
            .background(if (changed && !focused && !error) ChangedBg else LologColors.Paper)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            SpeakerTag(speakers, line.speaker, onPickSpeaker)
            Spacer(Modifier.width(10.dp))
            Column(
                Modifier.weight(1f).clip(LologShape.sm).border(1.5.dp, border, LologShape.sm)
                    .padding(horizontal = if (focused || error) 8.dp else 0.dp, vertical = if (focused || error) 8.dp else 3.dp),
            ) {
                BasicTextField(
                    line.text, onText,
                    textStyle = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, color = LologColors.Ink),
                    cursorBrush = SolidColor(LologColors.Accent),
                    modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
                )
                if (focused && !error) Text(
                    "${line.text.length}/${TextLimits.LINE}", fontSize = 11.sp, color = LologColors.Muted,
                    modifier = Modifier.align(Alignment.End).padding(top = 4.dp),
                )
            }
        }
        when {
            error && line.text.isBlank() -> Text(
                "대사를 입력해 주세요", fontSize = 12.sp, color = LologColors.Rec,
                modifier = Modifier.padding(start = 64.dp, top = 6.dp),
            )
            error -> Text(
                "대사는 ${TextLimits.LINE}자까지 쓸 수 있어요", fontSize = 12.sp, color = LologColors.Rec,
                modifier = Modifier.padding(start = 64.dp, top = 6.dp),
            )
        }
    }
}

/** 대사 앞 화자 칩: 누르면 화자 고르기 시트 */
@Composable
private fun SpeakerTag(speakers: List<Speaker>, key: String, onClick: () -> Unit) {
    val idx = speakers.indexOfFirst { it.key == key }.coerceAtLeast(0)
    Box(
        Modifier.clip(LologShape.pill).background(speakerSoft(idx)).clickable(onClick = onClick)
            .height(28.dp).widthIn(min = 46.dp).padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(speakers.displayName(key), fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** ③ 누가 한 말인가요? 화자 버튼들(고른 것은 코랄 테두리 + ✓) */
@Composable
private fun SpeakerSheet(speakers: List<Speaker>, selected: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    LologSheet(onDismiss = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("누가 한 말인가요?", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            // 한 줄에 2개씩
            speakers.withIndex().chunked(2).forEach { row ->
                Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { (_, sp) ->
                        val on = sp.key == selected
                        Row(
                            Modifier.weight(1f).height(50.dp).clip(LologShape.pill)
                                .border(if (on) 1.5.dp else 1.dp, if (on) LologColors.Accent else LologColors.Line, LologShape.pill)
                                .clickable { onPick(sp.key) },
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(speakers.displayName(sp.key), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            if (on) {
                                Spacer(Modifier.width(6.dp))
                                Icon(Icons.Default.Check, "선택됨", tint = LologColors.Accent, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}
