package com.lolog.app.ui.screens.rooms

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.PersonAddAlt
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lolog.app.data.model.Room
import com.lolog.app.data.model.RoomErrorCodes
import com.lolog.app.data.model.RoomInvite
import com.lolog.app.data.repo.LologException
import com.lolog.app.data.repo.LologRepository
import com.lolog.app.data.repo.RoomRepository
import com.lolog.app.ui.components.*
import com.lolog.app.ui.navigation.LocalContainer
import com.lolog.app.ui.theme.LologColors
import com.lolog.app.ui.theme.LologShape
import com.lolog.app.util.relativeTime
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class RoomListViewModel(private val rooms: RoomRepository, private val repo: LologRepository) : ViewModel() {
    data class State(
        val loading: Boolean = true,
        val error: String? = null,
        val rooms: List<Room>? = null,
        val friendCount: Int = 0,
        /** 내가 받은 초대(목록 맨 위 카드) */
        val invites: List<RoomInvite> = emptyList(),
        /** 수락·거절 중인 초대 */
        val acting: Long? = null,
        /** 수락해서 들어갈 방 */
        val openRoom: Long? = null,
        val snackbar: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /** 방 목록, 받은 초대, 친구 수(0명이면 방 만들기 대신 친구 추가 안내). 화면에 돌아올 때마다 새로 받아요 */
    fun load() = viewModelScope.launch {
        try {
            coroutineScope {
                val r = async { rooms.rooms() }
                // 초대를 못 받아도 방 목록은 보여요(받아 둔 초대를 그대로 둬요)
                val i = async { runCatching { rooms.myInvites() }.getOrNull() }
                val f = async { repo.friends().size }
                val list = r.await()
                val invites = i.await()
                val count = f.await()
                _state.update { it.copy(loading = false, error = null, rooms = list, friendCount = count, invites = invites ?: it.invites) }
            }
        } catch (e: Throwable) {
            _state.update { it.copy(loading = false, error = if (it.rooms == null) errorText(e) else null) }
        }
    }

    /** 수락하면 그 방으로 들어가요 */
    fun accept(invite: RoomInvite) = answer(invite) {
        val room = rooms.acceptInvite(invite.id)
        _state.update { s -> s.copy(invites = s.invites.filter { it.id != invite.id }, openRoom = room.id) }
    }

    /** 거절하면 카드만 사라져요(방장에게 알리지 않아요) */
    fun decline(invite: RoomInvite) = answer(invite) {
        rooms.declineInvite(invite.id)
        _state.update { s -> s.copy(invites = s.invites.filter { it.id != invite.id }) }
    }

    private fun answer(invite: RoomInvite, block: suspend () -> Unit) {
        if (_state.value.acting != null) return
        viewModelScope.launch {
            _state.update { it.copy(acting = invite.id) }
            try {
                block()
                _state.update { it.copy(acting = null) }
            } catch (e: Throwable) {
                _state.update { it.copy(acting = null, snackbar = errorText(e)) }
                // 이미 답했거나 취소된 초대면 초대와 방 목록을 다시 받아요
                if ((e as? LologException)?.code == RoomErrorCodes.ROOM_INVITE_NOT_FOUND) load()
            }
        }
    }

    fun roomOpened() = _state.update { it.copy(openRoom = null) }
    fun snackbarShown() = _state.update { it.copy(snackbar = null) }
}

// ── 20 방 목록 ──────────────────────────────────────
// 친구 탭 안 "방" 알약에서 보여요. 위 막대("+ 방 만들기")는 친구 화면이 그려요. 받은 초대는 목록 맨 위 카드

@Composable
fun RoomListScreen(
    onRoom: (Long) -> Unit,
    onCreate: () -> Unit,
    /** 친구가 0명일 때 "친구" 알약으로 */
    onFriends: () -> Unit,
    /** 스낵바 문구(이 화면을 품은 친구 화면이 띄워요) */
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = LocalContainer.current
    val roomRepo = container.rooms ?: return
    val vm = viewModel { RoomListViewModel(roomRepo, container.repository) }
    val state by vm.state.collectAsState()

    // 차단한 사람이 있는 방에 들어가기 전에 물어볼 초대
    var askBlocked by remember { mutableStateOf<RoomInvite?>(null) }

    LifecycleResumeEffect(Unit) {
        vm.load()
        onPauseOrDispose { }
    }
    LaunchedEffect(state.openRoom) { state.openRoom?.let { vm.roomOpened(); onRoom(it) } }
    LaunchedEffect(state.snackbar) { state.snackbar?.let { vm.snackbarShown(); onMessage(it) } }

    askBlocked?.let { inv ->
        LologDialog(
            title = "이 방에 차단한 사람이 있어요",
            body = "들어가면 그 사람 글은 지금 설정대로 보여요(숨기기 또는 그대로 보기). 설정 › 차단한 사람에서 바꿀 수 있어요.",
            confirmText = "들어가기",
            onConfirm = { askBlocked = null; vm.accept(inv) },
            onDismiss = { askBlocked = null },
        )
    }

    val list = state.rooms
    val invites = state.invites
    when {
        list == null && state.error != null -> ListErrorState(onRetry = { vm.load() }, modifier = modifier)
        list == null -> SkeletonList(modifier, count = 4)
        // 초대가 있으면 방이 0개여도 빈 화면 대신 초대 카드를 보여요
        list.isEmpty() && invites.isEmpty() -> {
            if (state.friendCount == 0) {
                EmptyState(
                    Icons.Outlined.PersonAddAlt, "아직 방이 없어요", "친구를 먼저 추가해 주세요",
                    memo = "친구가 있어야 방을 만들 수 있어요", buttonText = "친구 추가하러 가기", onButton = onFriends, modifier = modifier,
                )
            } else {
                EmptyState(
                    Icons.Outlined.Forum, "아직 방이 없어요", "친구와 방을 만들어 만화를 같이 봐요",
                    memo = "만화는 같이 보면 더 웃겨요", buttonText = "방 만들기", onButton = onCreate, modifier = modifier,
                )
            }
        }
        else -> LazyColumn(
            modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(invites, key = { "invite-${it.id}" }) { inv ->
                InviteCard(
                    inv,
                    acting = state.acting == inv.id,
                    onAccept = { if (inv.hasBlockedMember) askBlocked = inv else vm.accept(inv) },
                    onDecline = { vm.decline(inv) },
                )
            }
            items(list, key = { it.id }) { room -> RoomRow(room) { onRoom(room.id) } }
        }
    }
}

/** 받은 초대 카드(코랄 연한 바탕): 초대한 사람 얼굴 · "준님이 "주말 카레팟" 방에 초대했어요" · 멤버 수 · 시간 · [거절][수락] */
@Composable
private fun InviteCard(invite: RoomInvite, acting: Boolean, onAccept: () -> Unit, onDecline: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(LologShape.md).background(LologColors.RecSoft.copy(alpha = 0.55f))
            .border(1.dp, LologColors.RecSoft, LologShape.md).padding(14.dp),
    ) {
        Text("받은 초대", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = LologColors.Accent)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            invite.invitedBy?.let { MemberAvatar(it, 0, 40.dp); Spacer(Modifier.width(12.dp)) }
            Column(Modifier.weight(1f)) {
                val who = invite.invitedBy?.nickname?.ifBlank { null }
                Text(
                    if (who != null) "${who}님이 \"${invite.roomName}\" 방에 초대했어요" else "\"${invite.roomName}\" 방에 초대받았어요",
                    fontSize = 14.sp, fontWeight = FontWeight.Bold, color = LologColors.Accent,
                )
                Spacer(Modifier.height(2.dp))
                // 멤버 수는 명세에 없어서 실서버에서는 시간만 보여요
                Text(
                    listOfNotNull(invite.memberCount?.let { "멤버 ${it}명" }, relativeTime(invite.createdAt).ifBlank { null }).joinToString(" · "),
                    fontSize = 12.sp, color = LologColors.Muted,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton("거절", onDecline, Modifier.weight(1f), enabled = !acting)
            PrimaryButton("수락", onAccept, Modifier.weight(1f), loading = acting)
        }
    }
}

/** 방 한 줄: 멤버 얼굴 · 방 이름 + "4명" · 마지막 소식 · 시간 · 받지 않은 글 수 */
@Composable
private fun RoomRow(room: Room, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().softCard().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AvatarStack(room.members, size = 30.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    room.name, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = LologColors.Ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(5.dp))
                Text("${room.members.size}명", fontSize = 11.sp, color = LologColors.Muted)
            }
            Spacer(Modifier.height(2.dp))
            Text(room.lastActivity, fontSize = 12.5.sp, color = LologColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(relativeTime(room.lastActivityAt), fontSize = 11.sp, color = LologColors.Muted)
            Spacer(Modifier.height(5.dp))
            if (room.unread > 0) {
                Box(
                    Modifier.defaultMinSize(minWidth = 20.dp).height(20.dp).clip(CircleShape).background(LologColors.Accent).padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(if (room.unread > 99) "99+" else "${room.unread}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = LologColors.Paper) }
            } else {
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}
