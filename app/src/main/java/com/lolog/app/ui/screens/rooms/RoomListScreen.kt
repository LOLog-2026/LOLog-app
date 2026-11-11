package com.lolog.app.ui.screens.rooms

import androidx.compose.foundation.background
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
import com.lolog.app.data.repo.LologRepository
import com.lolog.app.data.repo.RoomRepository
import com.lolog.app.ui.components.*
import com.lolog.app.ui.navigation.LocalContainer
import com.lolog.app.ui.theme.LologColors
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
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /** 방 목록과 친구 수(0명이면 방 만들기 대신 친구 추가 안내). 화면에 돌아올 때마다 새로 받아요 */
    fun load() = viewModelScope.launch {
        try {
            coroutineScope {
                val r = async { rooms.rooms() }
                val f = async { repo.friends().size }
                val list = r.await()
                val count = f.await()
                _state.update { State(loading = false, rooms = list, friendCount = count) }
            }
        } catch (e: Throwable) {
            _state.update { it.copy(loading = false, error = if (it.rooms == null) errorText(e) else null) }
        }
    }
}

// ── 20 방 목록 ──────────────────────────────────────
// 친구 탭 안 "방" 알약에서 보여요. 위 막대("+ 방 만들기")는 친구 화면이 그려요

@Composable
fun RoomListScreen(
    onRoom: (Long) -> Unit,
    onCreate: () -> Unit,
    /** 친구가 0명일 때 "친구" 알약으로 */
    onFriends: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = LocalContainer.current
    val roomRepo = container.rooms ?: return
    val vm = viewModel { RoomListViewModel(roomRepo, container.repository) }
    val state by vm.state.collectAsState()

    LifecycleResumeEffect(Unit) {
        vm.load()
        onPauseOrDispose { }
    }

    val list = state.rooms
    when {
        list == null && state.error != null -> ListErrorState(onRetry = { vm.load() }, modifier = modifier)
        list == null -> SkeletonList(modifier, count = 4)
        list.isEmpty() -> {
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
            items(list, key = { it.id }) { room -> RoomRow(room) { onRoom(room.id) } }
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
