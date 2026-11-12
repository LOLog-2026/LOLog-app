package com.lolog.app.ui.screens.friends

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lolog.app.data.model.BlockedUser
import com.lolog.app.data.repo.LologRepository
import com.lolog.app.ui.components.*
import com.lolog.app.ui.navigation.LocalContainer
import com.lolog.app.ui.theme.LologColors
import com.lolog.app.util.dayLabel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class BlockedViewModel(private val repo: LologRepository) : ViewModel() {
    data class State(
        val loading: Boolean = true,
        val error: String? = null,
        val blocks: List<BlockedUser> = emptyList(),
        val busy: Set<Long> = emptySet(),
        val snackbar: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    fun load() = viewModelScope.launch {
        try {
            val list = repo.blocks()
            _state.update { it.copy(loading = false, error = null, blocks = list) }
        } catch (e: Throwable) {
            _state.update { it.copy(loading = false, error = errorText(e)) }
        }
    }

    /** 풀어도 친구로 돌아가지 않아요. 줄만 조용히 빠져요 */
    fun unblock(b: BlockedUser) {
        if (b.userId in _state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = it.busy + b.userId) }
            try {
                repo.unblock(b.userId)
                _state.update { s -> s.copy(blocks = s.blocks.filterNot { it.userId == b.userId }, busy = s.busy - b.userId) }
            } catch (e: Throwable) {
                _state.update { it.copy(busy = it.busy - b.userId, snackbar = errorText(e)) }
            }
        }
    }

    /** 방에서 이 사람 글 숨기기(hideInRooms). 먼저 바꿔 보이고, 실패하면 되돌려요 */
    fun setHide(b: BlockedUser, hide: Boolean) {
        if (b.userId in _state.value.busy) return
        fun put(value: Boolean) = _state.update { s -> s.copy(blocks = s.blocks.map { if (it.userId == b.userId) it.copy(hideInRooms = value) else it }) }
        viewModelScope.launch {
            put(hide)
            _state.update { it.copy(busy = it.busy + b.userId) }
            try {
                repo.setHideInRooms(b.userId, hide)
                _state.update { it.copy(busy = it.busy - b.userId, snackbar = if (hide) "방에서 이 사람 글을 숨겨요" else "방에서 이 사람 글이 보여요") }
            } catch (e: Throwable) {
                put(!hide)
                _state.update { it.copy(busy = it.busy - b.userId, snackbar = errorText(e)) }
            }
        }
    }

    fun snackbarShown() = _state.update { it.copy(snackbar = null) }
}

// ── 설정 → 차단한 사람 ───────────────────────────────

@Composable
fun BlockedScreen(onBack: () -> Unit) {
    val repo = LocalContainer.current.repository
    val vm = viewModel { BlockedViewModel(repo) }
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.load() }
    LaunchedEffect(state.snackbar) { state.snackbar?.let { snackbar.showSnackbar(it); vm.snackbarShown() } }

    Scaffold(
        containerColor = LologColors.Paper,
        snackbarHost = { LologSnackbarHost(snackbar) },
        topBar = { LologTopBar("차단한 사람", onBack) },
    ) { pad ->
        when {
            state.loading -> LoadingBox(Modifier.padding(pad))
            state.error != null -> ErrorBox(state.error!!, onRetry = { vm.load() }, modifier = Modifier.padding(pad))
            else -> LazyColumn(
                Modifier.padding(pad).fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            ) {
                if (state.blocks.isEmpty()) {
                    item {
                        Text(
                            "차단한 사람이 없어요", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = LologColors.Ink,
                            modifier = Modifier.padding(vertical = 20.dp),
                        )
                    }
                }
                items(state.blocks, key = { it.userId }) { b ->
                    val busy = b.userId in state.busy
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp).alpha(if (busy) 0.5f else 1f), verticalAlignment = Alignment.CenterVertically) {
                        FriendAvatar(null)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                b.nickname ?: "알 수 없음", fontSize = 15.sp, fontWeight = FontWeight.Bold,
                                color = if (b.nickname != null) LologColors.Ink else LologColors.Muted,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Row {
                                Text(b.friendCode, fontSize = 12.sp, color = LologColors.Muted, fontFamily = FontFamily.Monospace)
                                Text(" · ${dayLabel(b.blockedAt)}", fontSize = 12.sp, color = LologColors.Muted)
                            }
                        }
                        SmallPillButton("풀기", { if (!busy) vm.unblock(b) })
                    }
                    // 같은 방에서 이 사람 글을 "숨긴 글"로 보일지
                    Row(
                        Modifier.fillMaxWidth().padding(start = 56.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("방에서 글 숨기기", fontSize = 13.sp, color = LologColors.Muted, modifier = Modifier.weight(1f))
                        Switch(
                            checked = b.hideInRooms,
                            onCheckedChange = { vm.setHide(b, it) },
                            enabled = !busy,
                            modifier = Modifier.scale(0.75f),
                            colors = SwitchDefaults.colors(checkedTrackColor = LologColors.Accent, checkedThumbColor = LologColors.Paper),
                        )
                    }
                }
                item {
                    Text(
                        "차단한 사람은 내 친구 코드로 요청을 보낼 수 없어요. 풀어도 친구로 돌아가지 않아요.",
                        fontSize = 12.sp, color = LologColors.Muted, lineHeight = 18.sp,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }
        }
    }
}
