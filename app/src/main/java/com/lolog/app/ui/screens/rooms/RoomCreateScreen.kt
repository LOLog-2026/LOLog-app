package com.lolog.app.ui.screens.rooms

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PersonAddAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lolog.app.data.model.Friend
import com.lolog.app.data.model.RoomRules
import com.lolog.app.data.repo.LologRepository
import com.lolog.app.data.repo.RoomRepository
import com.lolog.app.ui.components.*
import com.lolog.app.ui.navigation.LocalContainer
import com.lolog.app.ui.screens.friends.FriendAvatar
import com.lolog.app.ui.screens.start.LabeledField
import com.lolog.app.ui.theme.LologColors
import com.lolog.app.ui.theme.LologShape
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class RoomCreateViewModel(private val rooms: RoomRepository, private val repo: LologRepository) : ViewModel() {
    data class State(
        val friends: List<Friend>? = null,
        val error: String? = null,
        val creating: Boolean = false,
        val createdId: Long? = null,
        val snackbar: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    init { load() }

    fun load() = viewModelScope.launch {
        try {
            val f = repo.friends()
            _state.update { it.copy(friends = f, error = null) }
        } catch (e: Throwable) {
            _state.update { it.copy(error = errorText(e)) }
        }
    }

    fun create(name: String, memberIds: List<Long>) {
        if (_state.value.creating) return
        viewModelScope.launch {
            _state.update { it.copy(creating = true) }
            try {
                val room = rooms.createRoom(name, memberIds)
                _state.update { it.copy(creating = false, createdId = room.id) }
            } catch (e: Throwable) {
                _state.update { it.copy(creating = false, snackbar = errorText(e)) }
            }
        }
    }

    fun toast(msg: String?) = _state.update { it.copy(snackbar = msg) }
}

/** 방 만들기: 방 이름 + 친구 고르기(나 포함 최대 6명). 만들면 그 방으로 들어가요 */
@Composable
fun RoomCreateScreen(onBack: () -> Unit, onCreated: (Long) -> Unit, onAddFriend: () -> Unit) {
    val container = LocalContainer.current
    val roomRepo = container.rooms ?: return
    val vm = viewModel { RoomCreateViewModel(roomRepo, container.repository) }
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var name by rememberSaveable { mutableStateOf("") }
    var picked by rememberSaveable { mutableStateOf(listOf<Long>()) }
    val maxPick = RoomRules.MEMBER_MAX - 1

    LaunchedEffect(state.createdId) { state.createdId?.let(onCreated) }
    LaunchedEffect(state.snackbar) { state.snackbar?.let { snackbar.showSnackbar(it); vm.toast(null) } }

    Scaffold(
        containerColor = LologColors.Paper,
        snackbarHost = { LologSnackbarHost(snackbar) },
        topBar = { LologTopBar("방 만들기", onBack = onBack) },
        bottomBar = {
            val friends = state.friends
            if (!friends.isNullOrEmpty()) {
                Box(Modifier.fillMaxWidth().background(LologColors.Paper).navigationBarsPadding().imePadding().padding(16.dp)) {
                    PrimaryButton(
                        if (picked.isEmpty()) "만들기" else "만들기 (나 포함 ${picked.size + 1}명)",
                        onClick = { vm.create(name, picked) },
                        enabled = name.isNotBlank() && picked.isNotEmpty(),
                        loading = state.creating,
                    )
                }
            }
        },
    ) { pad ->
        val friends = state.friends
        when {
            friends == null && state.error != null -> ErrorBox(state.error!!, onRetry = { vm.load() }, modifier = Modifier.padding(pad))
            friends == null -> LoadingBox(Modifier.padding(pad))
            friends.isEmpty() -> EmptyState(
                Icons.Outlined.PersonAddAlt, "친구를 먼저 추가해 주세요", "방에는 친구만 초대할 수 있어요",
                memo = "친구 코드를 주고받아요", buttonText = "친구 추가하기", onButton = onAddFriend, modifier = Modifier.padding(pad),
            )
            else -> LazyColumn(
                Modifier.padding(pad).fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp),
            ) {
                item {
                    LabeledField(
                        "방 이름", name, { name = it.take(RoomRules.NAME_MAX) },
                        placeholder = "예: 과팀플 4인방",
                        counter = "${name.length} / ${RoomRules.NAME_MAX}",
                        enabled = !state.creating,
                    )
                    Spacer(Modifier.height(22.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionTitle("같이 볼 친구")
                        Spacer(Modifier.weight(1f))
                        Text("${picked.size} / $maxPick", fontSize = 12.sp, color = LologColors.Muted)
                    }
                    Text("나를 포함해 ${RoomRules.MEMBER_MAX}명까지 들어갈 수 있어요", fontSize = 12.sp, color = LologColors.Muted, modifier = Modifier.padding(top = 2.dp, bottom = 6.dp))
                }
                items(friends, key = { it.userId }) { f ->
                    val on = f.userId in picked
                    PickRow(f, on) {
                        picked = when {
                            on -> picked - f.userId
                            picked.size >= maxPick -> { vm.toast("나를 포함해 ${RoomRules.MEMBER_MAX}명까지 들어갈 수 있어요"); picked }
                            else -> picked + f.userId
                        }
                    }
                }
            }
        }
    }
}

/** 친구 한 줄 + 오른쪽 체크 */
@Composable
private fun PickRow(friend: Friend, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(LologShape.sm).clickable(onClick = onToggle).padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FriendAvatar(friend.thumbnailUrl, 40.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            friend.nickname, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = LologColors.Ink,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        Checkbox(
            checked = checked, onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(checkedColor = LologColors.Accent, uncheckedColor = LologColors.Line, checkmarkColor = LologColors.Paper),
        )
    }
}
