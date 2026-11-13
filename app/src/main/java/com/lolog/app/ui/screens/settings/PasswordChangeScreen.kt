package com.lolog.app.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lolog.app.data.model.ErrorCodes
import com.lolog.app.data.model.PASSWORD_MAX
import com.lolog.app.data.model.isValidPassword
import com.lolog.app.data.repo.LologException
import com.lolog.app.data.repo.LologRepository
import com.lolog.app.ui.components.*
import com.lolog.app.ui.navigation.LocalContainer
import com.lolog.app.ui.screens.start.LabeledField
import com.lolog.app.ui.screens.start.PasswordRuleLines
import com.lolog.app.ui.theme.LologColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class PasswordChangeViewModel(private val repo: LologRepository) : ViewModel() {
    data class State(
        val loading: Boolean = false,
        val currentError: String? = null,  // 지금 비밀번호 칸 아래 빨간 줄
        val currentWrong: Boolean = false, // 틀리면 화면이 지금 비밀번호 칸을 비워요
        val passwordError: String? = null, // 새 비밀번호 칸 아래 빨간 줄 (서버가 규칙을 거절했을 때)
        val snackbar: String? = null,
        val doneMessage: String? = null,   // 있으면 설정으로 돌아가 이 글자를 띠로 보여 줘요
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /** POST /auth/me/password. 성공하면 저장소가 새 토큰을 저장해요 (FR-AUTH-07) */
    fun change(current: String, newPassword: String) {
        if (_state.value.loading || current.isEmpty() || !isValidPassword(newPassword)) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, currentError = null, currentWrong = false, passwordError = null, snackbar = null) }
            try {
                repo.changePassword(current, newPassword)
                _state.update { it.copy(loading = false, doneMessage = "비밀번호를 바꿨어요") }
            } catch (e: Throwable) {
                val ex = e as? LologException
                val invalid = ex?.code == ErrorCodes.INVALID_REQUEST || ex?.code == ErrorCodes.VALIDATION_FAILED
                _state.update {
                    val s = it.copy(loading = false)
                    when {
                        // 지금 비밀번호가 틀려도 로그아웃하지 않아요(명세)
                        ex?.code == ErrorCodes.INVALID_CREDENTIALS -> s.copy(currentError = "지금 비밀번호가 맞지 않아요", currentWrong = true)
                        // 소셜로만 가입한 계정. 설정에서 줄을 숨겨서 보통은 안 와요
                        invalid && ex?.field == "currentPassword" -> s.copy(doneMessage = "소셜로 가입한 계정은 비밀번호가 없어요")
                        invalid && ex?.field == "newPassword" -> s.copy(passwordError = "비밀번호 규칙을 확인해 주세요")
                        else -> s.copy(snackbar = errorText(e))
                    }
                }
            }
        }
    }

    fun clearCurrentError() = _state.update { it.copy(currentError = null, currentWrong = false) }
    fun clearPasswordError() = _state.update { it.copy(passwordError = null) }
    fun snackbarShown() = _state.update { it.copy(snackbar = null) }
}

// ── 설정 → 비밀번호 바꾸기 (FR-AUTH-07) ─────────────────
// 사진: 비밀번호-찾기-바꾸기-시안-1110.png 9·10·11. 칸과 규칙 줄은 가입 화면과 같은 부품이에요

@Composable
fun PasswordChangeScreen(onBack: () -> Unit, onDone: (message: String) -> Unit) {
    val repo = LocalContainer.current.repository
    val vm = viewModel { PasswordChangeViewModel(repo) }
    val state by vm.state.collectAsState()
    var current by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.doneMessage) { state.doneMessage?.let(onDone) }
    LaunchedEffect(state.snackbar) { state.snackbar?.let { snackbar.showSnackbar(it); vm.snackbarShown() } }
    LaunchedEffect(state.currentWrong) { if (state.currentWrong) current = "" }

    // 규칙과 버튼 조건은 가입 화면과 같아요
    val confirmOk = confirm.isNotEmpty() && confirm == password
    val allOk = current.isNotEmpty() && isValidPassword(password) && confirmOk

    Scaffold(
        containerColor = Color.Transparent,
        topBar = { LologTopBar("비밀번호 바꾸기", onBack) },
        snackbarHost = { LologSnackbarHost(snackbar) },
        modifier = Modifier.noteLines(),
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding().padding(horizontal = 24.dp)) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LabeledField(
                    "지금 비밀번호", current, { current = it.take(PASSWORD_MAX); vm.clearCurrentError() },
                    icon = Icons.Outlined.Key, placeholder = "지금 비밀번호", type = KeyboardType.Password, secret = true,
                    error = state.currentError, enabled = !state.loading,
                )
                LabeledField(
                    "새 비밀번호", password, { password = it.take(PASSWORD_MAX); vm.clearPasswordError() },
                    icon = Icons.Outlined.Key, placeholder = "새 비밀번호", type = KeyboardType.Password, secret = true,
                    error = state.passwordError, enabled = !state.loading,
                    counter = if (password.isNotEmpty()) "${password.length} / $PASSWORD_MAX" else null,
                )
                PasswordRuleLines(password)
                Spacer(Modifier.height(2.dp))
                LabeledField(
                    "새 비밀번호 확인", confirm, { confirm = it.take(PASSWORD_MAX) },
                    icon = Icons.Outlined.Key, placeholder = "한 번 더 입력해 주세요", type = KeyboardType.Password, secret = true,
                    error = if (confirm.isNotEmpty() && !confirmOk) "비밀번호가 서로 달라요" else null,
                    enabled = !state.loading,
                )
            }
            Text(
                "바꾸면 다른 폰에서는 로그아웃돼요. 이 폰은 그대로 써요.",
                fontSize = 12.sp, color = LologColors.Muted, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            )
            PrimaryButton("바꾸기", { vm.change(current, password) }, enabled = allOk, loading = state.loading)
            Spacer(Modifier.height(16.dp))
        }
    }
}
