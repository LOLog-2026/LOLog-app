package com.lolog.app.ui.screens.friends

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lolog.app.data.model.ErrorCodes
import com.lolog.app.data.model.FriendRequest
import com.lolog.app.data.model.FriendRequestDirection
import com.lolog.app.data.repo.LologException
import com.lolog.app.data.repo.LologRepository
import com.lolog.app.ui.screens.rooms.RoomBlockHost
import com.lolog.app.ui.screens.rooms.RoomBlockViewModel
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

class FriendRequestsViewModel(private val repo: LologRepository) : ViewModel() {
    data class State(
        val loading: Boolean = true,
        val error: String? = null,
        val received: List<FriendRequest> = emptyList(),
        val sent: List<FriendRequest> = emptyList(),
        val busy: Set<Long> = emptySet(), // 처리 중인 요청 id
        val snackbar: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    fun load() = viewModelScope.launch {
        try {
            coroutineScope {
                val r = async { repo.friendRequests(FriendRequestDirection.RECEIVED) }
                val s = async { repo.friendRequests(FriendRequestDirection.SENT) }
                val received = r.await()
                val sent = s.await()
                _state.update { it.copy(loading = false, error = null, received = received, sent = sent) }
            }
        } catch (e: Throwable) {
            _state.update { it.copy(loading = false, error = errorText(e)) }
        }
    }

    private fun drop(id: Long) = _state.update { s ->
        s.copy(received = s.received.filterNot { it.id == id }, sent = s.sent.filterNot { it.id == id }, busy = s.busy - id)
    }

    private fun failed(id: Long, e: Throwable) = _state.update {
        val msg = if ((e as? LologException)?.code == ErrorCodes.PROFILE_REQUIRED) "닉네임을 먼저 정해 주세요" else errorText(e)
        // 이미 없어진 요청이면 목록에서도 빼요
        if ((e as? LologException)?.code == ErrorCodes.FRIEND_REQUEST_NOT_FOUND) {
            it.copy(received = it.received.filterNot { r -> r.id == id }, sent = it.sent.filterNot { r -> r.id == id }, busy = it.busy - id, snackbar = msg)
        } else it.copy(busy = it.busy - id, snackbar = msg)
    }

    /** 수락하면 줄이 빠지고 띠 "민수님과 친구가 됐어요" */
    fun accept(r: FriendRequest) = act(r) {
        repo.acceptFriendRequest(r.id)
        _state.update { it.copy(snackbar = "${r.user.nickname}님과 친구가 됐어요") }
    }

    /** 거절·취소는 조용히 빠져요 */
    fun delete(r: FriendRequest) = act(r) { repo.deleteFriendRequest(r.id) }

    private fun act(r: FriendRequest, block: suspend () -> Unit) {
        if (r.id in _state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = it.busy + r.id) }
            try { block(); drop(r.id) } catch (e: Throwable) { failed(r.id, e) }
        }
    }

    /** 차단이 끝났어요. 그 사람과 오간 요청을 빼요 */
    fun blocked(userId: Long, message: String?) = _state.update { s ->
        s.copy(
            received = s.received.filterNot { it.user.userId == userId },
            sent = s.sent.filterNot { it.user.userId == userId },
            snackbar = message ?: s.snackbar,
        )
    }

    fun toast(msg: String) = _state.update { it.copy(snackbar = msg) }

    fun snackbarShown() = _state.update { it.copy(snackbar = null) }
}

// ── 친구 탭 → 친구 요청 (받은 요청 · 보낸 요청) ─────────

@Composable
fun FriendRequestsScreen(onBack: () -> Unit) {
    val repo = LocalContainer.current.repository
    val vm = viewModel { FriendRequestsViewModel(repo) }
    // 받은 요청 차단. 같은 방에 있으면 나가기·숨기기·그대로 보기를 물어요
    val roomRepo = LocalContainer.current.rooms
    val blockVm = viewModel(key = "requestBlock") { RoomBlockViewModel(roomRepo, repo) }
    val blockState by blockVm.state.collectAsState()
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var direction by rememberSaveable { mutableStateOf(FriendRequestDirection.RECEIVED) }
    var menuFor by remember { mutableStateOf<FriendRequest?>(null) }
    var confirmBlock by remember { mutableStateOf<FriendRequest?>(null) }

    LaunchedEffect(Unit) { vm.load() }
    LaunchedEffect(state.snackbar) { state.snackbar?.let { snackbar.showSnackbar(it); vm.snackbarShown() } }

    Scaffold(
        containerColor = LologColors.Paper,
        snackbarHost = { LologSnackbarHost(snackbar) },
        topBar = { LologTopBar("친구 요청", onBack) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterPill("받은 요청 ${state.received.size}", direction == FriendRequestDirection.RECEIVED) { direction = FriendRequestDirection.RECEIVED }
                FilterPill("보낸 요청 ${state.sent.size}", direction == FriendRequestDirection.SENT) { direction = FriendRequestDirection.SENT }
            }
            val list = if (direction == FriendRequestDirection.RECEIVED) state.received else state.sent
            when {
                state.loading -> LoadingBox()
                state.error != null -> ErrorBox(state.error!!, onRetry = { vm.load() })
                list.isEmpty() -> CenterMessage(
                    if (direction == FriendRequestDirection.RECEIVED) "받은 요청이 없어요" else "보낸 요청이 없어요",
                    "친구 코드를 주고받으면 서로 친구가 돼요",
                    Modifier.fillMaxWidth().padding(top = 40.dp),
                )
                else -> LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 24.dp)) {
                    items(list, key = { it.id }) { r ->
                        RequestRow(
                            request = r,
                            busy = r.id in state.busy,
                            onAccept = { vm.accept(r) },
                            onDelete = { vm.delete(r) },
                            onMore = { menuFor = r },
                        )
                    }
                }
            }
        }
    }

    menuFor?.let { r ->
        LologSheet(onDismiss = { menuFor = null }) {
            Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 28.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FriendAvatar(null, 52.dp)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(r.user.nickname, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = LologColors.Ink)
                        Text("${r.user.friendCode} · ${relativeTime(r.createdAt)}", fontSize = 13.sp, color = LologColors.Muted)
                    }
                }
                Spacer(Modifier.height(14.dp))
                HorizontalDivider(thickness = 1.dp, color = LologColors.Line)
                SheetRow("차단하기", LologColors.Rec) { menuFor = null; confirmBlock = r }
            }
        }
    }

    // 바깥 Scaffold의 기본 글자색이 회색이라 확인 창 제목은 Ink로
    confirmBlock?.let { r -> CompositionLocalProvider(LocalContentColor provides LologColors.Ink) {
        val n = r.user.nickname
        LologDialog(
            title = "${n}님을 차단할까요?",
            body = "요청이 지워지고, ${n}님은 내 코드로 요청을 보낼 수 없어요. 차단한 건 ${n}님에게 알리지 않아요.",
            confirmText = "차단하기",
            danger = true,
            loading = blockState.busy,
            onConfirm = { confirmBlock = null; blockVm.start(r.user.userId, r.user.nickname) },
            onDismiss = { if (!blockState.busy) confirmBlock = null },
        )
    } }

    RoomBlockHost(blockVm, onDone = { vm.blocked(it.userId, it.message) }, onSnackbar = vm::toast)
}

/** 받은 요청: 그림 · 닉네임 · "코드 · 3분 전" · [수락][거절] ⋯ / 보낸 요청: ... · [취소] */
@Composable
private fun RequestRow(request: FriendRequest, busy: Boolean, onAccept: () -> Unit, onDelete: () -> Unit, onMore: () -> Unit) {
    val received = request.direction == FriendRequestDirection.RECEIVED
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp).alpha(if (busy) 0.5f else 1f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FriendAvatar(null)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(request.user.nickname, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = LologColors.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row {
                Text(request.user.friendCode, fontSize = 12.sp, color = LologColors.Muted, fontFamily = FontFamily.Monospace)
                Text(" · ${relativeTime(request.createdAt)}", fontSize = 12.sp, color = LologColors.Muted)
            }
        }
        if (received) {
            AcceptPill(onClick = { if (!busy) onAccept() })
            Spacer(Modifier.width(6.dp))
            SmallPillButton("거절", { if (!busy) onDelete() })
            IconButton(onClick = onMore) { Icon(Icons.Outlined.MoreHoriz, contentDescription = "더 보기", tint = LologColors.Muted) }
        } else {
            SmallPillButton("취소", { if (!busy) onDelete() }, Modifier.padding(end = 12.dp))
        }
    }
}

/** 코랄 바탕 "수락" (SmallPillButton과 같은 크기) */
@Composable
private fun AcceptPill(onClick: () -> Unit) {
    Box(
        Modifier.clip(LologShape.pill).background(LologColors.Accent).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) { Text("수락", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = LologColors.Paper) }
}
