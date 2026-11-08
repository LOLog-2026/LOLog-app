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
private val ChangedBg = Color(0xFFFFF6D1)

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
        // 명세 PATCH: 바꾼 화자·대사만 보내요
        return CardEdit(
            speakers = speakers.filter { speakerChanged(it) }.ifEmpty { null },
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

    fun renameSpeaker(key: String, name: String) {
        val i = speakers.indexOfFirst { it.key == key }
        if (i >= 0) speakers[i] = speakers[i].copy(name = name.trim().take(TextLimits.SPEAKER_NAME).ifBlank { null })
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
fun CardEditScreen(cardId: Long, onBack: () -> Unit, onSaved: () -> Unit = onBack) {
    val repo = LocalContainer.current.repository
    val vm = viewModel { CardEditViewModel(repo, cardId) }
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var askLeave by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Speaker?>(null) }
    var pickingFor by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(state.saved) { if (state.saved) onSaved() }
    LaunchedEffect(state.toast) { state.toast?.let { snackbar.showSnackbar(it); vm.toastShown() } }

    // ⑤ 고친 게 있으면 그냥 나가지 않고 물어봐요
    val leave = { if (vm.changed) { askLeave = true } else onBack() }
    BackHandler(enabled = vm.changed) { askLeave = true }

    Scaffold(
        modifier = Modifier.noteLines(),
        containerColor = Color.Transparent,
        snackbarHost = { LologSnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("카드 고치기", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = { IconButton(onClick = leave) { Icon(Icons.Default.Close, "닫기") } },
                actions = { SaveChip(enabled = vm.canSave, loading = state.saving, onClick = vm::save) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { pad ->
        val original = state.original
        if (original == null) {
            if (state.loadError != null) ListErrorState(onRetry = { vm.load() }, modifier = Modifier.padding(pad), title = "카드를 불러오지 못했어요")
            else SkeletonList(Modifier.padding(pad), count = 3)
            return@Scaffold
        }
        Column(
            Modifier.padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (original.locked) {
                Text(
                    "만화를 만드는 중이에요. 다 그려진 뒤에 고칠 수 있어요.",
                    fontSize = 13.sp, color = LologColors.Rec,
                    modifier = Modifier.fillMaxWidth().background(LologColors.RecSoft, LologShape.sm).padding(12.dp),
                )
            }

            // 화자: 이름표를 누르면 이름 바꾸기
            SectionTitle("화자")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                vm.speakers.forEachIndexed { i, sp ->
                    SpeakerChip(vm.speakers.displayName(sp.key), i, changed = vm.speakerChanged(sp)) { renaming = sp }
                }
            }

            // 장소 (선택, 20자)
            SectionTitle("장소")
            PlaceField(vm.place) { vm.place = it.take(TextLimits.PLACE) }

            // 대사: 글자를 누르면 바로 고치기, 이름표를 누르면 화자 고르기
            SectionTitle("대사")
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
        RenameDialog(
            current = sp.name.orEmpty(),
            placeholder = "화자${vm.speakers.indexOfFirst { it.key == sp.key } + 1}",
            onDone = { vm.renameSpeaker(sp.key, it); renaming = null },
            onDismiss = { renaming = null },
        )
    }
    pickingFor?.let { i ->
        vm.lines.getOrNull(i)?.let { line ->
            SpeakerSheet(
                quote = line.text,
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
private fun SpeakerChip(name: String, index: Int, changed: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.clip(LologShape.sm)
            .background(LologColors.Paper)
            .border(if (changed) 1.5.dp else 1.dp, if (changed) LologColors.Accent else LologColors.Line, LologShape.sm)
            .clickable(onClick = onClick)
            .height(36.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(speakerColor(index)))
        Spacer(Modifier.width(6.dp))
        Text(name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Spacer(Modifier.width(4.dp))
        Icon(Icons.Outlined.Edit, "이름 바꾸기", tint = LologColors.Muted, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun PlaceField(value: String, onChange: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().softCard(LologShape.sm).height(48.dp).padding(horizontal = 14.dp),
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
    }
}

/** 대사 한 줄: 이름표 + 글자. 고치는 중엔 파란 테두리와 남은 글자 수, 비면 빨간 테두리와 안내 */
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
            .border(1.5.dp, border, LologShape.sm)
            .then(
                if (changed && !focused && !error) Modifier.drawBehind {
                    drawLine(LologColors.Laugh, Offset(0f, 0f), Offset(0f, size.height), 6.dp.toPx())
                } else Modifier,
            )
            .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = if (focused || error) 6.dp else 10.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            SpeakerTag(speakers, line.speaker, onPickSpeaker)
            Spacer(Modifier.width(10.dp))
            BasicTextField(
                line.text, onText,
                textStyle = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, color = LologColors.Ink),
                cursorBrush = SolidColor(LologColors.Accent),
                modifier = Modifier.weight(1f).padding(top = 3.dp).onFocusChanged { focused = it.isFocused },
            )
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
            focused -> Text(
                "${line.text.length} / ${TextLimits.LINE}", fontSize = 11.sp, color = LologColors.Muted,
                modifier = Modifier.align(Alignment.End).padding(top = 6.dp),
            )
        }
    }
}

/** 대사 앞 이름표: 누르면 화자 고르기 창 */
@Composable
private fun SpeakerTag(speakers: List<Speaker>, key: String, onClick: () -> Unit) {
    val idx = speakers.indexOfFirst { it.key == key }
    Row(
        Modifier.clip(LologShape.pill).background(LologColors.Canvas).clickable(onClick = onClick)
            .height(26.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(speakerColor(idx)))
        Spacer(Modifier.width(4.dp))
        Text(speakers.displayName(key), fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** ③ 누가 한 말인가요? */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpeakerSheet(quote: String, speakers: List<Speaker>, selected: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = LologColors.Paper) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("누가 한 말인가요?", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("\"$quote\"", fontSize = 13.sp, color = LologColors.Muted, maxLines = 2)
            Spacer(Modifier.height(12.dp))
            speakers.forEachIndexed { i, sp ->
                if (i > 0) HorizontalDivider(color = LologColors.Line)
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(sp.key) }.padding(vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(speakerColor(i)))
                    Spacer(Modifier.width(10.dp))
                    Text(speakers.displayName(sp.key), fontSize = 15.sp, modifier = Modifier.weight(1f))
                    if (sp.key == selected) Icon(Icons.Default.Check, "선택됨", tint = LologColors.Accent)
                }
            }
            Spacer(Modifier.height(12.dp))
            GhostButton("닫기", onDismiss)
        }
    }
}

/** 화자 이름 바꾸기 (10자까지, 비우면 다시 "화자1") */
@Composable
private fun RenameDialog(current: String, placeholder: String, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LologColors.Paper,
        title = { Text("이름 바꾸기", fontSize = 18.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                OutlinedTextField(
                    value = text, onValueChange = { text = it.take(TextLimits.SPEAKER_NAME) }, singleLine = true,
                    placeholder = { Text(placeholder, color = LologColors.Muted) },
                    shape = LologShape.sm,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = LologColors.Accent, unfocusedBorderColor = LologColors.Line),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "${text.length} / ${TextLimits.SPEAKER_NAME} · 비우면 \"$placeholder\"처럼 순서대로 불러요",
                    fontSize = 11.sp, color = LologColors.Muted, modifier = Modifier.padding(top = 6.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onDone(text) }) { Text("확인", fontWeight = FontWeight.Bold, color = LologColors.Accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소", color = LologColors.Muted) } },
    )
}
