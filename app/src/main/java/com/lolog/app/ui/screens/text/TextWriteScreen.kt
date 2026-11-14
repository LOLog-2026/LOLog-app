package com.lolog.app.ui.screens.text

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lolog.app.data.model.ErrorCodes
import com.lolog.app.data.model.TEXT_BODY_MAX
import com.lolog.app.data.model.TextInput
import com.lolog.app.data.model.TextLimits
import com.lolog.app.data.model.BLOG_CATEGORY_COUNT_MAX
import com.lolog.app.data.model.BLOG_CATEGORY_MAX
import com.lolog.app.data.model.textLength
import com.lolog.app.data.repo.LologException
import com.lolog.app.data.repo.LologRepository
import com.lolog.app.ui.components.*
import com.lolog.app.ui.navigation.LocalContainer
import com.lolog.app.ui.theme.LologColors
import com.lolog.app.ui.theme.LologShape
import com.lolog.app.util.TextDraft
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/*
 * 글로 만들기(글로 카드 만들기 시안 2·3·6·7·8번, LOLog-docs PR #62 texts.md 초안). 에피소드의 "글로 만들기"로 들어와요.
 *  - 위 탭: 직접 쓰기 | 블로그. 처음엔 직접 쓰기
 *  - 직접 쓰기: 제목·본문(필수) + 장소·상황(선택). 날짜·나온 사람 칸은 없어요
 *    쓰는 중에는 0.5초 뒤 폰에 임시 저장(TextDraft)하고, 다시 열면 불러와요. 보내면 지워요. 뒤로 가기는 확인 없이 나가요
 *    보내면 카드 만드는 중 화면으로 가고 이 화면은 스택에서 빠져요
 *  - 블로그(TextBlogTab.kt): 주소 확인 → 카테고리 고르기 → 연결, 연결한 블로그의 가져온 글
 *  - 서버에 아직 API가 없어서 실서버에서는 "아직 서버 준비 중이에요" 띠가 나와요(쓴 글은 그대로)
 */

class TextWriteViewModel(private val repo: LologRepository) : ViewModel() {
    data class State(
        val title: String = "",
        val body: String = "",
        val place: String = "",
        val situation: String = "",
        /** 임시 저장을 불러왔으면 true. 그 전에는 저장하지 않아요(빈 값으로 덮지 않게) */
        val loaded: Boolean = false,
        val sending: Boolean = false,
        val toast: String? = null,
        /** 보내서 받은 textId. 있으면 만드는 중 화면으로 가요 */
        val createdId: Long? = null,
    ) {
        /** 본문이 상한을 넘었어요. 서버도 거절해서(TEXT_TOO_LONG) 줄여야 보낼 수 있어요(texts.md 2장) */
        val tooLong: Boolean get() = textLength(body) > TEXT_BODY_MAX
        /** 장소는 그대로 카드 장소가 되어 카드 장소 한도(20자)를 넘을 수 없어요 */
        val placeTooLong: Boolean get() = textLength(place.trim()) > TextLimits.PLACE
        /** 제목·본문이 공백만이 아니고 길이 한도 안이어야 켜져요 */
        val canSend: Boolean get() = title.isNotBlank() && body.isNotBlank() && !tooLong && !placeTooLong && !sending
        val draft: TextDraft.Draft get() = TextDraft.Draft(title, body, place, situation)
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    fun loadDraft(d: TextDraft.Draft) {
        if (_state.value.loaded) return
        _state.update { it.copy(title = d.title, body = d.body, place = d.place, situation = d.situation, loaded = true) }
    }

    fun setTitle(v: String) = _state.update { it.copy(title = v) }
    /** 붙여넣은 긴 글이 몰래 잘리지 않게 그대로 받고, 넘으면 글자 수를 빨갛게 보여 주고 보내기를 막아요 */
    fun setBody(v: String) = _state.update { it.copy(body = v) }
    fun setPlace(v: String) = _state.update { it.copy(place = v) }
    fun setSituation(v: String) = _state.update { it.copy(situation = v) }

    /** POST /api/texts. 실패하면 띠만 보여 주고 쓴 글은 그대로 둬요 */
    fun send() {
        val s = _state.value
        if (!s.canSend) return
        _state.update { it.copy(sending = true) }
        viewModelScope.launch {
            try {
                val id = repo.createText(TextInput(s.title.trim(), s.body.trim(), s.place.trim().ifBlank { null }, s.situation.trim().ifBlank { null }))
                _state.update { it.copy(sending = false, createdId = id) }
            } catch (e: LologException) {
                _state.update { it.copy(sending = false, toast = textErrorText(e)) }
            }
        }
    }

    fun toastShown() = _state.update { it.copy(toast = null) }
}

/**
 * 글·블로그 오류 문구. 서버 message는 개발자용이라 화면에 띄우지 않고(api-dto ErrorResponse) 코드마다 앱 문구를 써요.
 * 그 밖(인터넷 등)은 공통 문구
 */
internal fun textErrorText(e: Throwable): String {
    val ex = e as? LologException
    return when (ex?.code) {
    ErrorCodes.TEXT_TOO_LONG -> "글이 너무 길어요. ${"%,d".format(TEXT_BODY_MAX)}자 안으로 줄여 주세요"
    ErrorCodes.INVALID_BLOG -> "블로그를 찾지 못했어요. 주소를 확인해 주세요"
    ErrorCodes.BLOG_EXISTS -> "이미 연결한 블로그예요"
    ErrorCodes.BLOG_NOT_FOUND -> "블로그를 찾지 못했어요. 이미 연결을 끊었을 수 있어요"
    ErrorCodes.TEXT_NOT_FOUND -> "글을 찾지 못했어요. 에피소드를 지웠을 수 있어요"
    ErrorCodes.INVALID_REQUEST -> when (ex.field) {
        "title" -> "제목을 써 주세요"
        "body" -> "본문을 써 주세요"
        "place" -> "장소는 ${TextLimits.PLACE}자까지 쓸 수 있어요"
        "situation" -> "상황이 너무 길어요"
        // field가 categories 하나면 목록 전체 문제예요(3개 넘음, 카테고리가 없는 블로그. LOLog-api #85)
        "categories" -> if (ex.message.contains("고를 수 없")) "이 블로그는 카테고리를 고를 수 없어요. 고르지 않으면 전부 가져와요"
        else "카테고리는 ${BLOG_CATEGORY_COUNT_MAX}개까지 골라요"
        else -> if (ex.field?.startsWith("categories") == true) "카테고리는 1~${BLOG_CATEGORY_MAX}자, 같은 이름은 한 번만 고를 수 있어요"
        else "입력한 내용을 확인해 주세요"
    }
    ErrorCodes.NOT_READY -> "아직 서버 준비 중이에요"
    else -> errorText(e)
    }
}

/** [initialTab] 0 = 직접 쓰기, 1 = 블로그. [onCard]는 블로그 탭의 "카드 만듦" 글을 눌렀을 때 */
@Composable
fun TextWriteScreen(initialTab: Int, onBack: () -> Unit, onCreated: (Long) -> Unit, onCard: (Long) -> Unit) {
    val repo = LocalContainer.current.repository
    val vm = viewModel { TextWriteViewModel(repo) }
    val state by vm.state.collectAsState()
    val blogVm = viewModel { TextBlogViewModel(repo) }
    val blogToast by remember { blogVm.state.map { it.toast } }.collectAsState(null)
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableIntStateOf(initialTab.coerceIn(0, 1)) }

    LaunchedEffect(Unit) { vm.loadDraft(TextDraft.load(context)) }
    // 0.5초 동안 더 안 쓰면 임시 저장. 보내는 중이거나 보낸 뒤에는 저장하지 않아요
    LaunchedEffect(state.draft, state.loaded) {
        if (!state.loaded) return@LaunchedEffect
        delay(500)
        if (!state.sending && state.createdId == null) TextDraft.save(context, state.draft)
    }
    LaunchedEffect(state.createdId) {
        val id = state.createdId ?: return@LaunchedEffect
        TextDraft.clear(context)
        onCreated(id)
    }
    LaunchedEffect(state.toast) { state.toast?.let { snackbar.showSnackbar(it); vm.toastShown() } }
    LaunchedEffect(blogToast) { blogToast?.let { snackbar.showSnackbar(it); blogVm.toastShown() } }

    Scaffold(
        containerColor = LologColors.Paper,
        snackbarHost = { LologSnackbarHost(snackbar) },
        topBar = { LologTopBar("글로 만들기", onBack) },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            ContentTabs(listOf("직접 쓰기", "블로그"), tab, { tab = it })
            when (tab) {
                0 -> WriteTab(state, vm, Modifier.weight(1f))
                else -> BlogTab(blogVm, onCard, Modifier.weight(1f))
            }
        }
    }
}

/** 직접 쓰기 탭(시안 2·3번): 안내 · 제목 · 본문(남은 높이) · 장소 · 상황 · 카드 만들기 */
@Composable
private fun WriteTab(state: TextWriteViewModel.State, vm: TextWriteViewModel, modifier: Modifier) {
    Column(modifier.fillMaxWidth().imePadding()) {
        Column(Modifier.weight(1f).padding(horizontal = 16.dp).padding(top = 14.dp)) {
            // 안내 문구는 명세(texts.md 3장) 그대로
            InfoBox("직접 겪은 일을 적어 보세요. 일기, 일지, 기억나는 일화를 만화와 쇼츠로 만들어 드려요.")
            Spacer(Modifier.height(14.dp))
            FieldLabel("제목", required = true)
            InputBox(state.title, vm::setTitle, "이 기록의 이름", enabled = !state.sending)
            Spacer(Modifier.height(12.dp))
            FieldLabel("본문", required = true)
            InputBox(
                state.body, vm::setBody,
                "있었던 일을 자유롭게 적어 주세요.\n누가 무슨 말을 했는지 쓰면 더 재밌어요.",
                singleLine = false, enabled = !state.sending,
                counter = "%,d / %,d".format(textLength(state.body), TEXT_BODY_MAX),
                error = state.tooLong,
                modifier = Modifier.weight(1f).heightIn(min = 120.dp),
            )
            Spacer(Modifier.height(12.dp))
            FieldLabel("장소", note = "선택")
            InputBox(state.place, vm::setPlace, "어디서 있었던 일인가요?", enabled = !state.sending, error = state.placeTooLong)
            if (state.placeTooLong) FieldError("장소는 ${TextLimits.PLACE}자까지 쓸 수 있어요")
            Spacer(Modifier.height(12.dp))
            // 상황은 AI에 배경으로만 넘기고 카드에는 저장하지 않아요(texts.md 3장)
            FieldLabel("상황", note = "선택 · 에피소드엔 안 남아요")
            InputBox(state.situation, vm::setSituation, "언제 · 누구와 · 왜", enabled = !state.sending)
        }
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PrimaryButton("에피소드 만들기", vm::send, enabled = state.canSend, loading = state.sending)
            if (!state.canSend && !state.sending) {
                val hint = when {
                    state.tooLong -> "본문을 ${"%,d".format(TEXT_BODY_MAX)}자 안으로 줄이면 만들 수 있어요"
                    state.placeTooLong -> "장소를 ${TextLimits.PLACE}자 안으로 줄이면 만들 수 있어요"
                    else -> "제목과 본문을 쓰면 만들 수 있어요"
                }
                Text(hint, fontSize = 12.sp, color = if (state.tooLong || state.placeTooLong) LologColors.Rec else LologColors.Muted, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

/** 칸 아래 빨간 글(블로그 탭의 직접 적기 오류와 같은 모양) */
@Composable
private fun FieldError(text: String) {
    Text(text, fontSize = 12.sp, color = LologColors.Rec, modifier = Modifier.padding(start = 2.dp, top = 4.dp))
}

/** 회색 안내 상자 */
@Composable
internal fun InfoBox(text: String, modifier: Modifier = Modifier) {
    Text(
        text, fontSize = 12.5.sp, lineHeight = 19.sp, color = LologColors.Muted,
        modifier = modifier.fillMaxWidth().clip(LologShape.sm).background(LologColors.Canvas).padding(horizontal = 14.dp, vertical = 12.dp),
    )
}

/** 칸 이름. [required]면 코랄 *, [note]는 오른쪽 작은 회색 글 */
@Composable
internal fun FieldLabel(text: String, required: Boolean = false, note: String? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 2.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = LologColors.Ink)
        if (required) Text(" *", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = LologColors.Accent)
        Spacer(Modifier.weight(1f))
        if (note != null) Text(note, fontSize = 11.sp, color = LologColors.Muted)
    }
}

/**
 * 테두리 입력 칸. 쓰는 중이면 코랄 테두리, [error]면 빨간 테두리.
 * [singleLine]이 아니면 받은 높이를 채우고 오른쪽 아래에 [counter]
 */
@Composable
internal fun InputBox(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    enabled: Boolean = true,
    error: Boolean = false,
    counter: String? = null,
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val color = when {
        error -> LologColors.Rec
        focused -> LologColors.Accent
        else -> LologColors.Line
    }
    Box(
        modifier.fillMaxWidth().clip(LologShape.sm).background(LologColors.Paper)
            .border(if (focused || error) 1.5.dp else 1.dp, color, LologShape.sm)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        if (value.isEmpty()) Text(placeholder, fontSize = 14.sp, lineHeight = 21.sp, color = LologColors.Muted)
        BasicTextField(
            value, onChange, enabled = enabled, singleLine = singleLine,
            textStyle = TextStyle(fontSize = 14.sp, lineHeight = 21.sp, color = LologColors.Ink),
            cursorBrush = SolidColor(LologColors.Accent),
            interactionSource = source,
            modifier = if (singleLine) Modifier.fillMaxWidth() else Modifier.fillMaxSize().padding(bottom = 18.dp),
        )
        if (counter != null) {
            Text(counter, fontSize = 11.sp, color = LologColors.Muted, modifier = Modifier.align(Alignment.BottomEnd))
        }
    }
}
