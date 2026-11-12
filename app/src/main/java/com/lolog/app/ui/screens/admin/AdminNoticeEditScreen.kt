package com.lolog.app.ui.screens.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lolog.app.data.model.ErrorCodes
import com.lolog.app.data.model.InboxItem
import com.lolog.app.data.model.NOTICE_TITLE_MAX
import com.lolog.app.data.repo.LologException
import com.lolog.app.data.repo.LologRepository
import com.lolog.app.ui.components.*
import com.lolog.app.ui.navigation.LocalContainer
import com.lolog.app.ui.theme.LologColors
import com.lolog.app.ui.theme.LologShape
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 공지 쓰기·고치기·지우기. [original]이 null이면 새로 쓰기, 있으면 그 공지 고치기(목록에서 받은 값) */
class AdminNoticeEditViewModel(private val repo: LologRepository, val original: InboxItem.Notice?) : ViewModel() {
    data class State(
        val title: String = "",
        val body: String = "",
        /** 올리기·고치기 요청 중 */
        val sending: Boolean = false,
        /** 지우기 요청 중 */
        val deleting: Boolean = false,
        val confirmDelete: Boolean = false,
        /** 400 INVALID_REQUEST field title·body: 칸 아래 빨간 글 */
        val titleError: String? = null,
        val bodyError: String? = null,
        val snackbar: String? = null,
        /** 끝나서 목록으로 돌아가요. 목록에 띄울 스낵바 문구(null이면 아무것도 안 띄움) */
        val done: Done? = null,
    ) {
        val busy get() = sending || deleting
        /** 제목·내용이 (앞뒤 공백 빼고) 비면 버튼이 흐리고 안 눌려요 */
        val canSubmit get() = title.isNotBlank() && body.isNotBlank() && !busy
    }

    data class Done(val message: String?)

    private val _state = MutableStateFlow(State(title = original?.title.orEmpty(), body = original?.body.orEmpty()))
    val state: StateFlow<State> = _state

    /** 한 줄, 100자 넘게는 못 쳐요(붙여 넣어도 잘라요) */
    fun setTitle(v: String) = _state.update {
        it.copy(title = v.replace("\n", " ").take(NOTICE_TITLE_MAX), titleError = null)
    }

    fun setBody(v: String) = _state.update { it.copy(body = v, bodyError = null) }

    /** 올리기·고치기. 고칠 때는 바뀐 필드만 보내고, 아무것도 안 바꿨으면 요청 없이 돌아가요 */
    fun submit() {
        val s = _state.value
        if (!s.canSubmit) return
        val o = original
        val title = s.title.trim()
        val body = s.body.trim()
        if (o != null && s.title == o.title && s.body == o.body) {
            _state.update { it.copy(done = Done(null)) }
            return
        }
        _state.update { it.copy(sending = true) }
        viewModelScope.launch {
            try {
                if (o == null) {
                    repo.createNotice(title, body)
                } else {
                    repo.updateNotice(
                        o.id,
                        title = title.takeIf { s.title != o.title },
                        body = body.takeIf { s.body != o.body },
                    )
                }
                _state.update { it.copy(sending = false, done = Done(if (o == null) "공지를 올렸어요" else "공지를 고쳤어요")) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update { fail(it.copy(sending = false), e) }
            }
        }
    }

    fun askDelete() {
        if (original == null || _state.value.busy) return
        _state.update { it.copy(confirmDelete = true) }
    }

    fun cancelDelete() {
        if (_state.value.deleting) return
        _state.update { it.copy(confirmDelete = false) }
    }

    fun delete() {
        val o = original ?: return
        if (_state.value.busy) return
        _state.update { it.copy(deleting = true) }
        viewModelScope.launch {
            try {
                repo.deleteNotice(o.id)
                _state.update { it.copy(deleting = false, confirmDelete = false, done = Done("공지를 지웠어요")) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update { fail(it.copy(deleting = false, confirmDelete = false), e) }
            }
        }
    }

    /**
     * 400 field title·body → 칸 아래 안내, 403 → 쓴 글은 두고 스낵바("관리자만 할 수 있어요". 다시 로그인하면 될 수 있어요),
     * 404 → 목록으로("이미 지워진 공지예요", 목록은 돌아가면 새로 받아요), 그 밖 → 쓴 글은 두고 스낵바
     */
    private fun fail(s: State, e: Throwable): State {
        val code = (e as? LologException)?.code
        val field = (e as? LologException)?.field
        return when {
            (code == ErrorCodes.INVALID_REQUEST || code == ErrorCodes.VALIDATION_FAILED) && field == "title" ->
                s.copy(titleError = "제목은 1~${NOTICE_TITLE_MAX}자로 적어 주세요")
            (code == ErrorCodes.INVALID_REQUEST || code == ErrorCodes.VALIDATION_FAILED) && field == "body" ->
                s.copy(bodyError = "내용을 적어 주세요")
            code == ErrorCodes.NOTICE_NOT_FOUND -> s.copy(done = Done("이미 지워진 공지예요"))
            else -> s.copy(snackbar = errorText(e))
        }
    }

    fun snackbarShown() = _state.update { it.copy(snackbar = null) }
}

// ── 공지 쓰기·고치기 (시안 5번, admin.md 4장) ─────────

/**
 * 새로 쓰기와 고치기를 한 화면으로 해요. [original]이 있으면 고치기예요.
 * 끝나면 [onDone]에 목록에 띄울 스낵바 문구(null이면 없음)를 넘겨요.
 */
@Composable
fun AdminNoticeEditScreen(original: InboxItem.Notice?, onBack: () -> Unit, onDone: (String?) -> Unit) {
    val repo = LocalContainer.current.repository
    val vm = viewModel { AdminNoticeEditViewModel(repo, original) }
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val editing = vm.original != null

    LaunchedEffect(state.snackbar) { state.snackbar?.let { snackbar.showSnackbar(it); vm.snackbarShown() } }
    LaunchedEffect(state.done) { state.done?.let { onDone(it.message) } }

    Scaffold(
        containerColor = LologColors.Paper,
        snackbarHost = { LologSnackbarHost(snackbar) },
        topBar = {
            LologTopBar(if (editing) "공지 고치기" else "새 공지", onBack) {
                if (editing) {
                    Text(
                        "지우기", fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        color = if (state.busy) LologColors.Muted else LologColors.Rec,
                        modifier = Modifier.clip(LologShape.pill).clickable(enabled = !state.busy, onClick = vm::askDelete)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        },
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GhostButton("취소", onBack, Modifier.weight(1f), enabled = !state.busy)
                PrimaryButton(
                    if (editing) "고치기" else "올리기", vm::submit, Modifier.weight(1f),
                    enabled = state.canSubmit, loading = state.sending,
                )
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            FieldBox("제목", counter = "${state.title.length} / $NOTICE_TITLE_MAX", error = state.titleError != null) {
                BasicTextField(
                    state.title, vm::setTitle, singleLine = true, enabled = !state.busy,
                    textStyle = TextStyle(fontSize = 15.sp, color = LologColors.Ink),
                    cursorBrush = SolidColor(LologColors.Accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            state.titleError?.let { FieldError(it) }
            Spacer(Modifier.height(10.dp))
            FieldBox("내용", error = state.bodyError != null) {
                BasicTextField(
                    state.body, vm::setBody, enabled = !state.busy,
                    textStyle = TextStyle(fontSize = 15.sp, color = LologColors.Ink, lineHeight = 22.sp),
                    cursorBrush = SolidColor(LologColors.Accent),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp),
                )
            }
            state.bodyError?.let { FieldError(it) }
            Spacer(Modifier.height(12.dp))
            PushNote()
        }
    }

    if (state.confirmDelete) {
        LologDialog(
            title = "이 공지를 지울까요?",
            body = "모든 사람의 알림함에서 사라져요.",
            confirmText = "지우기",
            onConfirm = vm::delete,
            onDismiss = vm::cancelDelete,
            danger = true,
            loading = state.deleting,
        )
    }
}

/** 흰 칸: 위에 작은 회색 이름(오른쪽에 글자 수) + 입력. 틀리면 빨간 테두리 */
@Composable
private fun FieldBox(label: String, error: Boolean, counter: String? = null, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(LologShape.md).background(LologColors.Paper)
            .border(1.dp, if (error) LologColors.Rec else LologColors.Line, LologShape.md)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = LologColors.Muted, modifier = Modifier.weight(1f))
            counter?.let { Text(it, fontSize = 11.sp, color = LologColors.Muted) }
        }
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
private fun FieldError(text: String) {
    Text(text, fontSize = 12.sp, color = LologColors.Rec, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
}

/** 파란 점선 상자 안내 */
@Composable
private fun PushNote() {
    Box(
        Modifier.fillMaxWidth().drawBehind {
            drawRoundRect(
                LologColors.Pen,
                cornerRadius = CornerRadius(14.dp.toPx()),
                style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))),
            )
        }.padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            "푸시는 보내지 않아요. 모든 사용자의 알림함 \"공지사항\"에 올라가요.",
            fontSize = 13.sp, color = LologColors.Muted, lineHeight = 19.sp,
        )
    }
}
