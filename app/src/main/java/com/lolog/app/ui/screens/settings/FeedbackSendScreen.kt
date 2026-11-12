package com.lolog.app.ui.screens.settings

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lolog.app.BuildConfig
import com.lolog.app.data.model.ErrorCodes
import com.lolog.app.data.model.FEEDBACK_PER_DAY
import com.lolog.app.data.model.FEEDBACK_TEXT_MAX
import com.lolog.app.data.model.FeedbackCategory
import com.lolog.app.data.repo.AdminRepository
import com.lolog.app.data.repo.LologException
import com.lolog.app.ui.components.FilterPill
import com.lolog.app.ui.components.GhostButton
import com.lolog.app.ui.components.LologSnackbarHost
import com.lolog.app.ui.components.LologTopBar
import com.lolog.app.ui.components.PrimaryButton
import com.lolog.app.ui.components.errorText
import com.lolog.app.ui.navigation.LocalContainer
import com.lolog.app.ui.screens.admin.FieldBox
import com.lolog.app.ui.screens.admin.FieldError
import com.lolog.app.ui.theme.LologColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/*
 * 설정 › 의견 보내기 (admin.md 5·7장, POST /api/feedback)
 *  - 종류: 오류 신고(BUG) · 의견(OPINION). 글은 1~1000자
 *  - 앱 버전(BuildConfig.VERSION_NAME)과 기기 이름(Build.MODEL)은 같이 보내요(화면에 적어 둬요)
 *  - 지난 24시간에 10번까지(429면 안내). 탈퇴해도 보낸 의견은 남는다는 걸 적어요
 */
class FeedbackSendViewModel(
    private val repo: AdminRepository,
    val appVersion: String,
    val deviceModel: String,
) : ViewModel() {
    data class State(
        val category: FeedbackCategory = FeedbackCategory.BUG,
        val text: String = "",
        val sending: Boolean = false,
        val textError: String? = null,
        val snackbar: String? = null,
        val sent: Boolean = false,
    ) {
        val canSend get() = text.isNotBlank() && !sending
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    fun setCategory(c: FeedbackCategory) = _state.update { it.copy(category = c) }
    fun setText(v: String) = _state.update { it.copy(text = v.take(FEEDBACK_TEXT_MAX), textError = null) }

    fun send() {
        val s = _state.value
        if (!s.canSend) return
        _state.update { it.copy(sending = true) }
        viewModelScope.launch {
            try {
                // 기기 이름·앱 버전은 서버 칸 길이(100자·30자)에 맞춰 잘라요(admin.md 9장)
                repo.sendFeedback(s.category, s.text.trim(), appVersion.take(30).ifBlank { "unknown" }, deviceModel.take(100).ifBlank { "unknown" })
                _state.update { it.copy(sending = false, sent = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val ex = e as? LologException
                _state.update {
                    when {
                        ex?.code == ErrorCodes.TOO_MANY_REQUESTS ->
                            it.copy(sending = false, snackbar = "의견은 하루에 ${FEEDBACK_PER_DAY}번까지 보낼 수 있어요. 내일 다시 보내 주세요")
                        (ex?.code == ErrorCodes.INVALID_REQUEST || ex?.code == ErrorCodes.VALIDATION_FAILED) && ex.field == "text" ->
                            it.copy(sending = false, textError = "내용을 1~${FEEDBACK_TEXT_MAX}자로 적어 주세요")
                        else -> it.copy(sending = false, snackbar = errorText(e))
                    }
                }
            }
        }
    }

    fun snackbarShown() = _state.update { it.copy(snackbar = null) }
}

/** 보내면 [onSent]로 설정 화면에 돌아가 스낵바를 띄워요 */
@Composable
fun FeedbackSendScreen(onBack: () -> Unit, onSent: (String) -> Unit) {
    val repo = LocalContainer.current.admin
    val vm = viewModel { FeedbackSendViewModel(repo, BuildConfig.VERSION_NAME, "${Build.MANUFACTURER} ${Build.MODEL}".trim()) }
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.snackbar) { state.snackbar?.let { snackbar.showSnackbar(it); vm.snackbarShown() } }
    LaunchedEffect(state.sent) { if (state.sent) onSent("보냈어요. 고마워요!") }

    Scaffold(
        containerColor = LologColors.Paper,
        snackbarHost = { LologSnackbarHost(snackbar) },
        topBar = { LologTopBar("의견 보내기", onBack) },
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GhostButton("취소", onBack, Modifier.weight(1f), enabled = !state.sending)
                PrimaryButton("보내기", vm::send, Modifier.weight(1f), enabled = state.canSend, loading = state.sending)
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text("무엇을 보낼까요?", fontSize = 12.sp, color = LologColors.Muted, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FeedbackCategory.entries.forEach { c -> FilterPill(c.label, state.category == c) { vm.setCategory(c) } }
            }
            Spacer(Modifier.height(14.dp))
            FieldBox("내용", counter = "${state.text.length} / $FEEDBACK_TEXT_MAX", error = state.textError != null) {
                Box {
                    if (state.text.isEmpty()) {
                        Text(
                            if (state.category == FeedbackCategory.BUG) "어떤 화면에서 무엇을 했을 때 문제가 생겼는지 적어 주세요" else "바라는 점이나 좋았던 점을 적어 주세요",
                            fontSize = 15.sp, color = LologColors.Muted,
                        )
                    }
                    BasicTextField(
                        state.text, vm::setText, enabled = !state.sending,
                        textStyle = TextStyle(fontSize = 15.sp, color = LologColors.Ink, lineHeight = 22.sp),
                        cursorBrush = SolidColor(LologColors.Accent),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp),
                    )
                }
            }
            state.textError?.let { FieldError(it) }
            Spacer(Modifier.height(12.dp))
            Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("앱 버전(${vm.appVersion})과 기기 이름(${vm.deviceModel})을 같이 보내요.", fontSize = 12.sp, color = LologColors.Muted)
                Text("하루에 ${FEEDBACK_PER_DAY}번까지 보낼 수 있어요.", fontSize = 12.sp, color = LologColors.Muted)
                Text("탈퇴해도 보낸 의견은 지워지지 않고 남아요.", fontSize = 12.sp, color = LologColors.Muted)
            }
        }
    }
}
