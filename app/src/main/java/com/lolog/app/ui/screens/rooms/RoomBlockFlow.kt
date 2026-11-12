package com.lolog.app.ui.screens.rooms

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lolog.app.data.model.Room
import com.lolog.app.data.model.RoomErrorCodes
import com.lolog.app.data.model.RoomMember
import com.lolog.app.data.repo.LologException
import com.lolog.app.data.repo.LologRepository
import com.lolog.app.data.repo.RoomRepository
import com.lolog.app.ui.components.LologSheet
import com.lolog.app.ui.components.errorText
import com.lolog.app.ui.theme.LologColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 같은 방 사람 차단(시안 11, rooms.md "차단한 사람이 있는 방").
 * 내 방 목록에 그 사람이 들어 있는 방이 있으면 바로 차단하지 않고 나가기·숨기기·그대로 보기를 물어요. 없으면 지금처럼 바로 차단해요.
 * 나가기는 차단(hideInRooms = false) 뒤 그 방들에서 차례로 나가요(서버 한 번 호출이 아니에요). 내가 방장인 방은 방마다 새 방장을 골라요.
 * 친구도 바로 차단할 수 있고 POST /api/blocks 한 번에 친구 관계도 끊겨요(friends.md 차단).
 * 멤버 시트, 친구 화면(끊기 → 차단), 받은 요청 차단이 같이 써요.
 */
class RoomBlockViewModel(private val rooms: RoomRepository?, private val repo: LologRepository) : ViewModel() {

    data class Target(val userId: Long, val nickname: String)

    enum class Choice { LEAVE, HIDE, KEEP }

    /** 차단이 끝남. [leftRooms]는 나온 방, [message]는 띄울 스낵바(바로 차단했으면 null) */
    data class Done(val userId: Long, val leftRooms: Set<Long>, val message: String?)

    data class State(
        val target: Target? = null,
        /** 그 사람과 같이 있는 방 */
        val shared: List<Room> = emptyList(),
        /** 나가기·숨기기·그대로 보기 시트 */
        val asking: Boolean = false,
        /** 나가기를 고른 뒤 새 방장을 골라야 하는 방(내가 방장인 방). 맨 앞 방의 시트를 띄워요 */
        val ownerRooms: List<Room> = emptyList(),
        val busy: Boolean = false,
        val snackbar: String? = null,
        val done: Done? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val left = mutableSetOf<Long>()

    /** 차단 시작. 시트를 닫으면 아무것도 바뀌지 않아요 */
    fun start(userId: Long, nickname: String) {
        if (_state.value.busy) return
        left.clear()
        viewModelScope.launch {
            _state.update { it.copy(busy = true, target = Target(userId, nickname)) }
            val shared = try {
                rooms?.rooms()?.filter { r -> r.members.any { it.userId == userId && !it.isMe } }.orEmpty()
            } catch (e: Throwable) {
                _state.update { it.copy(busy = false, target = null, snackbar = errorText(e)) }
                return@launch
            }
            if (shared.isNotEmpty()) {
                _state.update { it.copy(busy = false, shared = shared, asking = true) }
                return@launch
            }
            // 같이 있는 방이 없으면 지금처럼 바로 차단
            try {
                repo.block(userId)
                _state.update { State(done = Done(userId, emptySet(), null)) }
            } catch (e: Throwable) {
                _state.update { it.copy(busy = false, target = null, snackbar = errorText(e)) }
            }
        }
    }

    fun choose(choice: Choice) {
        val t = _state.value.target ?: return
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try {
                repo.block(t.userId, hideInRooms = choice == Choice.HIDE)
            } catch (e: Throwable) {
                _state.update { it.copy(busy = false, snackbar = errorText(e)) }
                return@launch
            }
            _state.update { it.copy(asking = false) }
            if (choice != Choice.LEAVE) return@launch finish()
            val owned = mutableListOf<Room>()
            for (r in _state.value.shared) {
                if (r.isMineOwner && r.members.any { !it.isMe }) {
                    owned += r
                } else {
                    try {
                        rooms?.leave(r.id, null)
                        left += r.id
                    } catch (e: Throwable) {
                        // 한 방에서 실패해도 다른 방은 계속 나가요
                        _state.update { it.copy(snackbar = errorText(e)) }
                    }
                }
            }
            if (owned.isEmpty()) finish() else _state.update { it.copy(busy = false, ownerRooms = owned) }
        }
    }

    /** 내가 방장인 방: 새 방장에게 넘기고 나가요 */
    fun leaveAsOwner(room: Room, newOwner: RoomMember) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try {
                rooms?.leave(room.id, newOwner.userId)
                left += room.id
                next()
            } catch (e: Throwable) {
                _state.update { it.copy(busy = false, snackbar = errorText(e)) }
                // 새 방장이 필요하다는 답이면 그 방 시트를 그대로 두고, 그 밖의 오류는 다음 방으로
                if ((e as? LologException)?.code != RoomErrorCodes.NEW_OWNER_REQUIRED) next()
            }
        }
    }

    /** 새 방장 고르기를 닫으면 그 방에는 남아요(차단은 이미 됐어요) */
    fun skipOwner() = next()

    private fun next() {
        val rest = _state.value.ownerRooms.drop(1)
        if (rest.isEmpty()) finish() else _state.update { it.copy(busy = false, ownerRooms = rest) }
    }

    private fun finish() {
        val t = _state.value.target ?: return
        _state.update { State(snackbar = it.snackbar, done = Done(t.userId, left.toSet(), "차단했어요")) }
    }

    /** 시트를 닫으면 아무것도 하지 않아요 */
    fun cancel() {
        if (!_state.value.busy) _state.update { State() }
    }

    fun doneShown() = _state.update { it.copy(done = null) }
    fun snackbarShown() = _state.update { it.copy(snackbar = null) }
}

/**
 * 차단 흐름의 시트들(나가기·숨기기·그대로 보기, 방마다 새 방장 고르기)과 결과 알림.
 * [onDone]은 차단이 끝났을 때, [onSnackbar]는 오류 문구를 띄울 때 불러요
 */
@Composable
fun RoomBlockHost(vm: RoomBlockViewModel, onDone: (RoomBlockViewModel.Done) -> Unit, onSnackbar: (String) -> Unit) {
    val state by vm.state.collectAsState()
    LaunchedEffect(state.done) { state.done?.let { vm.doneShown(); onDone(it) } }
    LaunchedEffect(state.snackbar) { state.snackbar?.let { vm.snackbarShown(); onSnackbar(it) } }

    val t = state.target
    if (state.asking && t != null) {
        BlockChoiceSheet(t.nickname, state.shared, state.busy, onChoose = vm::choose, onDismiss = vm::cancel)
    }
    state.ownerRooms.firstOrNull()?.let { room ->
        PickOwnerSheet(
            room = room,
            loading = state.busy,
            onLeave = { vm.leaveAsOwner(room, it) },
            onDismiss = { if (!state.busy) vm.skipOwner() },
        )
    }
}

/** 시안 11: "{닉네임}님과 같이 있는 방이 n개 있어요" */
@Composable
private fun BlockChoiceSheet(
    nickname: String,
    shared: List<Room>,
    busy: Boolean,
    onChoose: (RoomBlockViewModel.Choice) -> Unit,
    onDismiss: () -> Unit,
) {
    LologSheet(onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 28.dp)) {
            Text("${nickname}님과 같이 있는 방이 ${shared.size}개 있어요", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = LologColors.Ink)
            Text(
                "차단해도 방에서 저절로 빠지지 않아요. 어떻게 할까요?", fontSize = 13.sp, color = LologColors.Muted,
                modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
            )
            ChoiceRow(
                if (shared.size == 1) "\"${shared.first().name}\" 방에서 나가기" else "방에서 나가기",
                null, LologColors.Rec, busy,
            ) { onChoose(RoomBlockViewModel.Choice.LEAVE) }
            HorizontalDivider(thickness = 1.dp, color = LologColors.Line)
            ChoiceRow(
                "남고 ${nickname}님 글 숨기기", "\"숨긴 글\"로 보여요. ${nickname}님에게는 알리지 않아요.", LologColors.Ink, busy,
            ) { onChoose(RoomBlockViewModel.Choice.HIDE) }
            HorizontalDivider(thickness = 1.dp, color = LologColors.Line)
            ChoiceRow("남고 그대로 보기", null, LologColors.Ink, busy) { onChoose(RoomBlockViewModel.Choice.KEEP) }
            HorizontalDivider(thickness = 1.dp, color = LologColors.Line)
            Text("나중에 설정 › 차단한 사람에서 바꿀 수 있어요.", fontSize = 12.sp, color = LologColors.Muted, modifier = Modifier.padding(top = 12.dp))
        }
    }
}

@Composable
private fun ChoiceRow(title: String, sub: String?, color: androidx.compose.ui.graphics.Color, busy: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(enabled = !busy, onClick = onClick).padding(vertical = 14.dp)) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = color)
        if (sub != null) Text(sub, fontSize = 12.sp, color = LologColors.Muted, modifier = Modifier.padding(top = 3.dp))
    }
}
