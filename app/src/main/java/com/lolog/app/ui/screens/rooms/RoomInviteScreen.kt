package com.lolog.app.ui.screens.rooms

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lolog.app.data.model.Friend
import com.lolog.app.data.model.Room
import com.lolog.app.data.model.RoomErrorCodes
import com.lolog.app.data.model.RoomRules
import com.lolog.app.data.repo.LologException
import com.lolog.app.data.repo.LologRepository
import com.lolog.app.data.repo.RoomRepository
import com.lolog.app.ui.components.*
import com.lolog.app.ui.navigation.LocalContainer
import com.lolog.app.ui.screens.friends.FriendAvatar
import com.lolog.app.ui.theme.LologColors
import com.lolog.app.ui.theme.LologShape
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class RoomInviteViewModel(private val rooms: RoomRepository, private val repo: LologRepository, private val roomId: Long) : ViewModel() {
    data class State(
        val room: Room? = null,
        val friends: List<Friend>? = null,
        val error: String? = null,
        val sending: Boolean = false,
        /** 초대를 보낸 사람 수. 있으면 방으로 돌아가요 */
        val sent: Int? = null,
        val snackbar: String? = null,
        /** 고른 친구 */
        val picked: List<Long> = emptyList(),
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    init { load() }

    /** 방(멤버·기다리는 초대)과 내 친구 목록 */
    fun load() = viewModelScope.launch {
        try {
            coroutineScope {
                val r = async { rooms.room(roomId) }
                val f = async { repo.friends() }
                val room = r.await()
                val friends = f.await()
                _state.update { it.copy(room = room, friends = friends, error = null) }
            }
        } catch (e: Throwable) {
            _state.update { it.copy(error = if (it.room == null) errorText(e) else null, snackbar = if (it.room != null) errorText(e) else null) }
        }
    }

    /** 남은 자리만큼만 고를 수 있어요 */
    fun toggle(userId: Long) {
        val s = _state.value
        when {
            userId in s.picked -> _state.update { it.copy(picked = it.picked - userId) }
            s.picked.size >= (s.room?.seatsLeft ?: 0) -> toast("나를 포함해 ${RoomRules.MEMBER_MAX}명까지 들어갈 수 있어요")
            else -> _state.update { it.copy(picked = it.picked + userId) }
        }
    }

    fun invite() {
        val userIds = _state.value.picked
        if (_state.value.sending || userIds.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(sending = true) }
            try {
                rooms.invite(roomId, userIds)
                _state.update { it.copy(sending = false, sent = userIds.size) }
            } catch (e: Throwable) {
                _state.update { it.copy(sending = false, snackbar = errorText(e)) }
                // 그사이 인원이 바뀌었으면 고른 친구를 비우고 방을 다시 받아 남은 자리를 맞춰요
                if ((e as? LologException)?.code == RoomErrorCodes.ROOM_MEMBER_LIMIT) {
                    _state.update { it.copy(picked = emptyList()) }
                    load()
                }
            }
        }
    }

    fun toast(msg: String?) = _state.update { it.copy(snackbar = msg) }
}

// ── 방장: 친구 초대 ─────────────────────────────────
// 멤버 + 답을 기다리는 초대를 합쳐 나 포함 6명까지. 이미 멤버이거나 초대한 친구는 고를 수 없어요

@Composable
fun RoomInviteScreen(roomId: Long, onBack: () -> Unit, onInvited: (Int) -> Unit) {
    val container = LocalContainer.current
    val roomRepo = container.rooms ?: return
    val vm = viewModel { RoomInviteViewModel(roomRepo, container.repository, roomId) }
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val picked = state.picked

    LaunchedEffect(state.sent) { state.sent?.let(onInvited) }
    LaunchedEffect(state.snackbar) { state.snackbar?.let { snackbar.showSnackbar(it); vm.toast(null) } }

    val room = state.room
    val seats = room?.seatsLeft ?: 0

    Scaffold(
        containerColor = LologColors.Paper,
        snackbarHost = { LologSnackbarHost(snackbar) },
        topBar = { LologTopBar("친구 초대", onBack = onBack) },
        bottomBar = {
            if (room != null && !state.friends.isNullOrEmpty()) {
                Box(Modifier.fillMaxWidth().background(LologColors.Paper).navigationBarsPadding().padding(16.dp)) {
                    PrimaryButton(
                        "${picked.size}명 초대하기",
                        onClick = { vm.invite() },
                        enabled = picked.isNotEmpty(),
                        loading = state.sending,
                    )
                }
            }
        },
    ) { pad ->
        val friends = state.friends
        when {
            (room == null || friends == null) && state.error != null -> ErrorBox(state.error!!, onRetry = { vm.load() }, modifier = Modifier.padding(pad))
            room == null || friends == null -> LoadingBox(Modifier.padding(pad))
            else -> {
                val members = room.members.map { it.userId }.toSet()
                val invited = room.invites.mapNotNull { it.invitedUser?.userId }.toSet()
                LazyColumn(
                    Modifier.padding(pad).fillMaxSize(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp),
                ) {
                    item {
                        Text(
                            "나를 포함해 ${RoomRules.MEMBER_MAX}명까지예요. 지금 멤버 ${room.members.size}명 + 초대 ${room.invites.size}명이라 ${seats}명 더 초대할 수 있어요.",
                            fontSize = 13.sp, color = LologColors.Muted, modifier = Modifier.padding(bottom = 10.dp),
                        )
                    }
                    if (friends.isEmpty()) {
                        item { CenterMessage("초대할 친구가 없어요", "친구를 먼저 추가해 주세요", Modifier.fillMaxWidth().padding(top = 40.dp)) }
                    }
                    items(friends, key = { it.userId }) { f ->
                        val note = when (f.userId) {
                            in members -> "이미 멤버예요"
                            in invited -> "초대 보냄 · 답 기다리는 중"
                            else -> null
                        }
                        InviteRow(f, note, f.userId in picked) { vm.toggle(f.userId) }
                    }
                }
            }
        }
    }
}

/** 친구 한 줄 + 오른쪽 체크. [note]가 있으면(이미 멤버·초대 보냄) 작은 글을 달고 체크를 흐리게 */
@Composable
private fun InviteRow(friend: Friend, note: String?, checked: Boolean, onToggle: () -> Unit) {
    val enabled = note == null
    Row(
        Modifier.fillMaxWidth().clip(LologShape.sm)
            .then(if (enabled) Modifier.clickable(onClick = onToggle) else Modifier)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FriendAvatar(friend.thumbnailUrl, 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(friend.nickname, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = LologColors.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            note?.let { Text(it, fontSize = 12.sp, color = LologColors.Muted) }
        }
        Checkbox(
            checked = checked, onCheckedChange = { onToggle() }, enabled = enabled,
            modifier = Modifier.alpha(if (enabled) 1f else 0.4f),
            colors = CheckboxDefaults.colors(
                checkedColor = LologColors.Accent, uncheckedColor = LologColors.Line, checkmarkColor = LologColors.Paper,
                disabledUncheckedColor = LologColors.Line,
            ),
        )
    }
}
