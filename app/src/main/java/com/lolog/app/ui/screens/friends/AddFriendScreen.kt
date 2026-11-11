package com.lolog.app.ui.screens.friends

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lolog.app.data.model.ErrorCodes
import com.lolog.app.data.model.FriendRequestStatus
import com.lolog.app.data.model.FriendRules
import com.lolog.app.data.repo.LologException
import com.lolog.app.data.repo.LologRepository
import com.lolog.app.ui.components.*
import com.lolog.app.ui.navigation.LocalContainer
import com.lolog.app.ui.screens.start.LabeledField
import com.lolog.app.ui.theme.LologColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AddFriendViewModel(private val repo: LologRepository) : ViewModel() {
    /** 끝나면 친구 화면으로 돌아가 [message]를 띠로 보여 줘요. [becameFriends]면 목록 맨 위 줄을 잠깐 연노랑으로 */
    data class Done(val message: String, val becameFriends: Boolean)

    data class State(
        val loading: Boolean = false,
        val error: String? = null,     // 칸 아래 빨간 글자
        val snackbar: String? = null,
        val done: Done? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /** POST /api/friend-requests */
    fun send(code: String) {
        if (_state.value.loading || code.length != FriendRules.CODE_LENGTH) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, snackbar = null) }
            try {
                val r = repo.sendFriendRequest(code)
                val name = r.user.nickname
                val done = if (r.status == FriendRequestStatus.ACCEPTED) Done("${name}님과 친구가 됐어요", true)
                else Done("${name}님에게 친구 요청을 보냈어요", false)
                _state.update { it.copy(loading = false, done = done) }
            } catch (e: Throwable) {
                val code2 = (e as? LologException)?.code
                _state.update {
                    val s = it.copy(loading = false)
                    // 오류 문구는 프론트가 정함(11/11)
                    when (code2) {
                        ErrorCodes.FRIEND_CODE_NOT_FOUND -> s.copy(error = "친구 코드를 찾지 못했어요. 다시 확인해 주세요")
                        ErrorCodes.CANNOT_FRIEND_SELF -> s.copy(error = "내 친구 코드예요")
                        ErrorCodes.ALREADY_FRIENDS -> s.copy(error = "이미 친구예요")
                        ErrorCodes.FRIEND_REQUEST_EXISTS -> s.copy(error = "이미 요청을 보냈어요")
                        ErrorCodes.USER_BLOCKED -> s.copy(error = "차단한 사람이에요. 설정의 \"차단한 사람\"에서 풀고 보내 주세요")
                        ErrorCodes.FRIEND_LIMIT -> s.copy(error = "친구는 ${FriendRules.FRIEND_MAX}명까지예요")
                        ErrorCodes.TOO_MANY_REQUESTS, "HTTP_429" -> s.copy(error = "잠시 뒤에 다시 넣어 주세요")
                        ErrorCodes.INVALID_REQUEST, ErrorCodes.VALIDATION_FAILED -> s.copy(error = "친구 코드 8자리를 넣어 주세요")
                        // 닉네임 없이 들어온 경우. 친구 화면으로 돌아가 닉네임 칸을 보여 줘요
                        ErrorCodes.PROFILE_REQUIRED -> s.copy(done = Done("닉네임을 먼저 정해 주세요", false))
                        else -> s.copy(snackbar = errorText(e))
                    }
                }
            }
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }
    fun snackbarShown() = _state.update { it.copy(snackbar = null) }
}

// ── 친구 탭 → 친구 추가 ─────────────────────────────

@Composable
fun AddFriendScreen(onBack: () -> Unit, onDone: (message: String, becameFriends: Boolean) -> Unit) {
    val repo = LocalContainer.current.repository
    val vm = viewModel { AddFriendViewModel(repo) }
    val state by vm.state.collectAsState()
    var code by remember { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.done) { state.done?.let { onDone(it.message, it.becameFriends) } }
    LaunchedEffect(state.snackbar) { state.snackbar?.let { snackbar.showSnackbar(it); vm.snackbarShown() } }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = { LologTopBar("친구 추가", onBack) },
        snackbarHost = { LologSnackbarHost(snackbar) },
        modifier = Modifier.noteLines(),
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding().padding(horizontal = 24.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Spacer(Modifier.height(8.dp))
                Text("친구에게 받은\n친구 코드를 넣어 주세요", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 33.sp, color = LologColors.Ink)
                Spacer(Modifier.height(24.dp))
                LabeledField(
                    "친구 코드", code, { code = cleanFriendCode(it, FriendRules.CODE_LENGTH); vm.clearError() },
                    icon = Icons.Outlined.Tag, placeholder = "8자리 코드", type = KeyboardType.Ascii,
                    counter = "${code.length} / ${FriendRules.CODE_LENGTH}",
                    error = state.error, info = "친구가 수락하면 서로의 친구 목록에 보여요",
                    enabled = !state.loading,
                )
            }
            PrimaryButton(
                "요청 보내기", { vm.send(code) },
                enabled = code.length == FriendRules.CODE_LENGTH, loading = state.loading,
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}
