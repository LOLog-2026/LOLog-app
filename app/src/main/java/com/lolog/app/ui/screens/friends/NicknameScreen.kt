package com.lolog.app.ui.screens.friends

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lolog.app.data.model.FriendRules
import com.lolog.app.data.repo.LologRepository
import com.lolog.app.ui.components.*
import com.lolog.app.ui.navigation.LocalContainer
import com.lolog.app.ui.screens.start.LabeledField
import com.lolog.app.ui.theme.LologColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class NicknameViewModel(private val repo: LologRepository) : ViewModel() {
    data class State(
        val current: String? = null, // 지금 닉네임. 불러오면 칸을 채워요
        val loading: Boolean = false,
        val snackbar: String? = null,
        val doneMessage: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    fun load() = viewModelScope.launch {
        runCatching { repo.profile() }.onSuccess { p -> _state.update { it.copy(current = p.nickname.orEmpty()) } }
    }

    /** PATCH /api/me/profile. 앞뒤 공백은 저장소가 잘라 보내요 */
    fun save(nickname: String) {
        if (_state.value.loading || nickname.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, snackbar = null) }
            try {
                repo.updateNickname(nickname)
                _state.update { it.copy(loading = false, doneMessage = "닉네임을 바꿨어요") }
            } catch (e: Throwable) {
                _state.update { it.copy(loading = false, snackbar = errorText(e)) }
            }
        }
    }

    fun snackbarShown() = _state.update { it.copy(snackbar = null) }
}

// ── 설정·친구 탭 → 닉네임 바꾸기 ─────────────────────

@Composable
fun NicknameScreen(onBack: () -> Unit, onDone: (message: String) -> Unit) {
    val repo = LocalContainer.current.repository
    val vm = viewModel { NicknameViewModel(repo) }
    val state by vm.state.collectAsState()
    var nickname by remember { mutableStateOf("") }
    var filled by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.load() }
    // 지금 닉네임을 받아 오면 한 번만 칸을 채워요(그 전에 적은 게 있으면 그대로)
    LaunchedEffect(state.current) {
        val cur = state.current ?: return@LaunchedEffect
        if (!filled && nickname.isEmpty()) nickname = cur
        filled = true
    }
    LaunchedEffect(state.doneMessage) { state.doneMessage?.let(onDone) }
    LaunchedEffect(state.snackbar) { state.snackbar?.let { snackbar.showSnackbar(it); vm.snackbarShown() } }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = { LologTopBar("닉네임 바꾸기", onBack) },
        snackbarHost = { LologSnackbarHost(snackbar) },
        modifier = Modifier.noteLines(),
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding().padding(horizontal = 24.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Spacer(Modifier.height(8.dp))
                Text("친구에게 보여질\n별명이에요", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 33.sp, color = LologColors.Ink)
                Spacer(Modifier.height(24.dp))
                LabeledField(
                    "닉네임", nickname, { nickname = it.take(FriendRules.NICKNAME_MAX) },
                    icon = Icons.Outlined.Person, placeholder = "친구에게 보여질 별명",
                    counter = "${nickname.length} / ${FriendRules.NICKNAME_MAX}",
                    info = "다른 사람과 겹쳐도 괜찮아요. 친구 코드로 구분해요.",
                    enabled = !state.loading,
                )
            }
            PrimaryButton("저장", { vm.save(nickname) }, enabled = nickname.isNotBlank(), loading = state.loading)
            Spacer(Modifier.height(16.dp))
        }
    }
}
