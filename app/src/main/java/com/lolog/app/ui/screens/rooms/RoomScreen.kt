package com.lolog.app.ui.screens.rooms

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lolog.app.data.model.*
import com.lolog.app.data.repo.LologException
import com.lolog.app.data.repo.LologRepository
import com.lolog.app.data.repo.RoomRepository
import com.lolog.app.ui.components.*
import com.lolog.app.ui.navigation.LocalContainer
import com.lolog.app.ui.screens.friends.SheetRow
import com.lolog.app.ui.theme.LologColors
import com.lolog.app.ui.theme.LologShape
import com.lolog.app.util.ComicExport
import com.lolog.app.util.dayLabel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class RoomViewModel(private val rooms: RoomRepository, private val repo: LologRepository, private val roomId: Long) : ViewModel() {
    data class State(
        val room: Room? = null,
        val posts: List<RoomPost> = emptyList(),
        val messages: List<RoomMessage> = emptyList(),
        val error: String? = null,
        /** 들어올 때 받지 않은 글이 있었으면 "대화" 탭에 코랄 점 */
        val chatDot: Boolean = false,
        val busy: Boolean = false,
        val sending: Boolean = false,
        val snackbar: String? = null,
        /** 방을 떠남(나가기·해산·방이 없어짐). 방 목록으로 돌아가 이 문구를 스낵바로 띄워요 */
        val exit: String? = null,
        /** 새 방장을 골라야 나갈 수 있어요(NEW_OWNER_REQUIRED). 새 방장 고르기 시트를 다시 띄워요 */
        val pickOwner: Boolean = false,
        /** 올릴 수 있는 내 만화(완성된 것만). null = 불러오는 중 */
        val myComics: List<ComicSummary>? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    init { load() }

    fun load() = viewModelScope.launch {
        try {
            val room = rooms.room(roomId)
            val posts = rooms.posts(roomId)
            val messages = rooms.messages(roomId) // 불러오면 받지 않은 글이 0이 돼요
            _state.update { it.copy(room = room, posts = posts, messages = messages, error = null, chatDot = room.unread > 0) }
        } catch (e: Throwable) {
            if (gone(e)) return@launch
            _state.update { it.copy(error = errorText(e)) }
        }
    }

    /** 방이 해산됐거나 내보내져서 없어졌으면(ROOM_NOT_FOUND) 방 목록으로 돌아가요 */
    private fun gone(e: Throwable): Boolean {
        if ((e as? LologException)?.code != RoomErrorCodes.ROOM_NOT_FOUND) return false
        _state.update { it.copy(busy = false, sending = false, exit = "방이 없어졌어요") }
        return true
    }

    /** 실패하면 스낵바. 방이 없어졌으면 방 목록으로 */
    private fun fail(e: Throwable) {
        if (!gone(e)) _state.update { it.copy(busy = false, sending = false, snackbar = errorText(e)) }
    }

    /** 만화·대화를 다시 받아요(올리기·좋아요·댓글 뒤) */
    private suspend fun refresh() {
        val posts = rooms.posts(roomId)
        val messages = rooms.messages(roomId)
        _state.update { it.copy(posts = posts, messages = messages) }
    }

    private fun act(success: String? = null, block: suspend () -> Unit) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try {
                block()
                refresh()
                _state.update { it.copy(busy = false, snackbar = success) }
            } catch (e: Throwable) {
                fail(e)
            }
        }
    }

    fun chatSeen() = _state.update { it.copy(chatDot = false) }

    fun loadMyComics() = viewModelScope.launch {
        _state.update { it.copy(myComics = null) }
        try {
            val list = repo.comics(size = 50).comics.filter { it.status.stage == ComicStage.DONE }
            _state.update { it.copy(myComics = list) }
        } catch (e: Throwable) {
            _state.update { it.copy(myComics = emptyList(), snackbar = errorText(e)) }
        }
    }

    fun post(comicId: Long) = act("만화를 올렸어요") { rooms.postComic(roomId, comicId) }
    fun remove(post: RoomPost, onDone: () -> Unit) = act("방에서 내렸어요") { rooms.removePost(post.id); onDone() }
    fun toggleLike(post: RoomPost) = act { rooms.toggleLike(post.id) }

    fun send(postId: Long?, text: String, onSent: () -> Unit) {
        if (text.isBlank() || _state.value.sending) return
        viewModelScope.launch {
            _state.update { it.copy(sending = true) }
            try {
                rooms.sendComment(roomId, postId, text)
                refresh()
                _state.update { it.copy(sending = false) }
                onSent()
            } catch (e: Throwable) {
                fail(e)
            }
        }
    }

    /** 방(멤버·기다리는 초대)만 다시 받아요. 친구 초대 화면에서 돌아왔을 때 */
    fun reloadRoom() = viewModelScope.launch {
        runCatching { rooms.room(roomId) }.onSuccess { room -> _state.update { it.copy(room = room) } }
    }

    /** 방장: 답을 기다리는 초대 취소(확인 창 없이 바로) */
    fun cancelInvite(invite: RoomInvite) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try {
                rooms.cancelInvite(roomId, invite.id)
                val room = rooms.room(roomId)
                _state.update { it.copy(busy = false, room = room, snackbar = "초대를 취소했어요") }
            } catch (e: Throwable) {
                fail(e)
                // 이미 답했거나 취소된 초대, 방장이 바뀐 경우는 방을 다시 받아 맞춰요
                val code = (e as? LologException)?.code
                if (code == RoomErrorCodes.ROOM_INVITE_NOT_FOUND || code == RoomErrorCodes.ROOM_OWNER_ONLY) reloadRoom()
            }
        }
    }

    // ── 방 관리·나가기 ──

    /** 방장 일을 하다 실패. 방장이 바뀌었으면(ROOM_OWNER_ONLY) 방을 다시 받아 메뉴를 맞춰요 */
    private fun ownerFail(e: Throwable) {
        fail(e)
        if ((e as? LologException)?.code == RoomErrorCodes.ROOM_OWNER_ONLY) reloadRoom()
    }

    private fun busy(block: suspend () -> Unit) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            block()
        }
    }

    /** 방장: 이름 바꾸기. 바뀌면 위 제목도 바뀌어요 */
    fun rename(name: String, onDone: () -> Unit) = busy {
        try {
            val room = rooms.rename(roomId, name)
            _state.update { it.copy(busy = false, room = room, snackbar = "방 이름을 바꿨어요") }
            onDone()
        } catch (e: Throwable) {
            ownerFail(e)
        }
    }

    /** 나가기. 내가 방장이고 다른 멤버가 있으면 [newOwnerId]를 꼭 넘겨요 */
    fun leave(newOwnerId: Long?) = busy {
        try {
            rooms.leave(roomId, newOwnerId)
            _state.update { it.copy(busy = false, exit = "방에서 나왔어요") }
        } catch (e: Throwable) {
            fail(e)
            // 그사이 방장이 됐거나 멤버가 바뀌었으면 방을 다시 받고 새 방장 고르기를 다시 띄워요
            if ((e as? LologException)?.code == RoomErrorCodes.NEW_OWNER_REQUIRED) {
                runCatching { rooms.room(roomId) }.onSuccess { room -> _state.update { it.copy(room = room) } }
                _state.update { it.copy(pickOwner = true) }
            }
        }
    }

    fun ownerPicked() = _state.update { it.copy(pickOwner = false) }

    /** 방장: 방 해산 */
    fun disband() = busy {
        try {
            rooms.disband(roomId)
            _state.update { it.copy(busy = false, exit = "방을 해산했어요") }
        } catch (e: Throwable) {
            ownerFail(e)
        }
    }

    /** 방장: 멤버 내보내기. 그 사람의 글은 "나간 멤버"로 바뀌어서 대화도 다시 받아요 */
    fun kick(member: RoomMember, onDone: () -> Unit) = busy {
        try {
            rooms.kick(roomId, member.userId)
            val room = rooms.room(roomId)
            refresh()
            _state.update { it.copy(busy = false, room = room, snackbar = "${member.nickname}님을 내보냈어요") }
        } catch (e: Throwable) {
            ownerFail(e)
        }
        onDone()
    }

    fun toast(msg: String?) = _state.update { it.copy(snackbar = msg) }
}

/** 꾹 누른 칸: 만화와 그 칸의 화면 위치(방 화면 기준) */
internal data class Pressed(val post: RoomPost, val bounds: Rect)

// ── 21~24 방 안 ─────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomScreen(
    roomId: Long,
    onBack: () -> Unit,
    /** 방을 떠남(나가기·해산·방이 없어짐). 방 목록으로 돌아가 [message]를 스낵바로 띄워요 */
    onLeft: (message: String) -> Unit,
    /** 방장: "+ 친구 초대" */
    onInvite: () -> Unit,
    /** 방 만들기·친구 초대 화면에서 넘어온 스낵바 문구 */
    message: String? = null,
    onMessageShown: () -> Unit = {},
) {
    val container = LocalContainer.current
    val roomRepo = container.rooms ?: return
    val vm = viewModel { RoomViewModel(roomRepo, container.repository, roomId) }
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // 위 탭: 0 = 4컷, 1 = 미디어(쇼츠), 2 = 대화
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var zoom by remember { mutableStateOf<RoomPost?>(null) }
    var pressed by remember { mutableStateOf<Pressed?>(null) }
    var replyTo by remember { mutableStateOf<RoomPost?>(null) }
    var menu by remember { mutableStateOf(false) }
    var members by remember { mutableStateOf(false) }
    var askLeave by remember { mutableStateOf(false) }
    var pickOwner by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var askDisband by remember { mutableStateOf(false) }
    var memberFor by remember { mutableStateOf<RoomMember?>(null) }
    var askKick by remember { mutableStateOf<RoomMember?>(null) }
    var askRemove by remember { mutableStateOf<RoomPost?>(null) }
    var picking by remember { mutableStateOf(false) }
    // 칸마다 화면 위치(꾹 누르면 그 자리에 칸을 다시 그려요). 그릴 때 쓰지 않아서 상태로 두지 않아요
    val cellBounds = remember { mutableMapOf<Long, Rect>() }
    var rootOffset by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(state.exit) { state.exit?.let(onLeft) }
    LaunchedEffect(state.pickOwner) { if (state.pickOwner) { askLeave = false; pickOwner = true; vm.ownerPicked() } }
    // 초대를 보내고 돌아오면 기다리는 초대가 바뀌어서 방을 다시 받아요
    LaunchedEffect(message) { message?.let { onMessageShown(); vm.reloadRoom(); snackbar.showSnackbar(it) } }
    LaunchedEffect(state.snackbar) { state.snackbar?.let { snackbar.showSnackbar(it); vm.toast(null) } }
    LaunchedEffect(tab) { if (tab == 2) vm.chatSeen() }
    BackHandler(enabled = pressed != null) { pressed = null }

    // 최신 좋아요·댓글 수로 보여 주려고 id로 다시 찾아요
    fun latest(p: RoomPost) = state.posts.firstOrNull { it.id == p.id }

    fun save(post: RoomPost) = scope.launch {
        val bytes = post.imageUrl?.let { ComicExport.pngBytes(context, it, comicCacheKey(post.comicId), post.mockLines, container.network) { null } }
        vm.toast(
            when {
                bytes == null -> "이미지를 받지 못했어요"
                ComicExport.saveToGallery(context, bytes, "lolog_room_${post.id}") -> "갤러리에 저장했어요"
                else -> "저장하지 못했어요"
            },
        )
    }

    val room = state.room
    Box(Modifier.fillMaxSize().onGloballyPositioned { rootOffset = it.positionInWindow() }) {
        Scaffold(
            containerColor = LologColors.Paper,
            // "+ 내 만화 올리기" 버튼이 있는 4컷 탭에서만 띠를 버튼 위로 올려요
            snackbarHost = { LologSnackbarHost(snackbar, Modifier.padding(bottom = if (tab == 0) 72.dp else 0.dp)) },
            topBar = {
                TopAppBar(
                    title = {
                        if (room != null) Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                room.name, style = MaterialTheme.typography.titleLarge,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                            )
                            Spacer(Modifier.width(8.dp))
                            AvatarStack(room.members, size = 24.dp)
                            Spacer(Modifier.width(6.dp))
                            Text("${room.members.size}명", fontSize = 12.sp, color = LologColors.Muted)
                        }
                    },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
                    actions = {
                        if (room != null) IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreHoriz, "더 보기", tint = LologColors.Ink) }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            },
        ) { pad ->
            when {
                room == null && state.error != null -> ErrorBox(state.error!!, onRetry = { vm.load() }, modifier = Modifier.padding(pad))
                room == null -> LoadingBox(Modifier.padding(pad))
                else -> Column(Modifier.padding(pad).fillMaxSize()) {
                    ContentTabs(listOf("4컷", "미디어", "대화"), tab, { tab = it }, dotAt = if (state.chatDot) 2 else null)
                    Box(Modifier.weight(1f)) {
                        when (tab) {
                            0 -> {
                                ComicGrid(
                                    room, state.posts,
                                    onOpen = { zoom = it },
                                    onLongPress = { p ->
                                        cellBounds[p.id]?.let { b -> pressed = Pressed(p, b.translate(-rootOffset)) }
                                    },
                                    onPlaced = { id, b -> cellBounds[id] = b },
                                )
                                PrimaryButton(
                                    "내 만화 올리기", { picking = true; vm.loadMyComics() },
                                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp).width(200.dp)
                                        .shadow(6.dp, LologShape.pill, ambientColor = LologColors.Accent, spotColor = LologColors.Accent),
                                    leading = { Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp)) },
                                )
                            }
                            // 쇼츠 API는 명세 대기라 지금은 안내만 있는 임시 탭
                            1 -> ShortsComingSoon("쇼츠가 생기면 이 방에도 올릴 수 있어요.")
                            else -> RoomChat(
                                room = room,
                                messages = state.messages,
                                replyTo = replyTo?.let { latest(it) ?: it },
                                sending = state.sending,
                                onClearReply = { replyTo = null },
                                onCaption = { p -> zoom = latest(p) ?: p },
                                onSend = { text, done -> vm.send(replyTo?.id, text) { replyTo = null; done() } },
                            )
                        }
                    }
                }
            }
        }

        // 23 꾹 누르기: 그 칸만 떠오르고 뒤는 어둡게
        pressed?.let { pr ->
            val post = latest(pr.post) ?: pr.post
            room?.let { r ->
                PressedOverlay(
                    room = r, post = post, bounds = pr.bounds,
                    onDismiss = { pressed = null },
                    onLike = { pressed = null; vm.toggleLike(post) },
                    onComment = { pressed = null; replyTo = post; tab = 2 },
                    onSave = { pressed = null; save(post) },
                    onZoom = { pressed = null; zoom = post },
                    onRemove = { pressed = null; askRemove = post },
                )
            }
        }
    }

    // 22 누르면 크게
    zoom?.let { z ->
        val post = latest(z) ?: z
        room?.let { r -> ComicPopup(r, post, onLike = { vm.toggleLike(post) }, onClose = { zoom = null }) }
    }

    if (picking) {
        PickComicSheet(
            comics = state.myComics,
            posted = state.posts.filter { it.author.isMe }.map { it.comicId }.toSet(),
            onPick = { picking = false; vm.post(it) },
            onDismiss = { picking = false },
        )
    }

    // 7 방 메뉴: 방장만 이름 바꾸기·해산
    if (menu && room != null) {
        val owner = room.isMineOwner
        LologSheet(onDismiss = { menu = false }) {
            Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 28.dp)) {
                Text(
                    room.name, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = LologColors.Ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 6.dp),
                )
                SheetRow("멤버 보기", LologColors.Ink) { menu = false; members = true }
                if (owner) SheetRow("방 이름 바꾸기", LologColors.Ink) { menu = false; renaming = true }
                SheetRow("방 나가기", LologColors.Rec) {
                    menu = false
                    // 방장이고 다른 멤버가 있으면 새 방장부터 골라요(시안 8)
                    if (owner && room.members.any { !it.isMe }) pickOwner = true else askLeave = true
                }
                if (owner) SheetRow("방 해산", LologColors.Rec) { menu = false; askDisband = true }
            }
        }
    }

    if (members && room != null) {
        LologSheet(onDismiss = { members = false }) {
            MemberSheet(
                room,
                busy = state.busy,
                onMember = { members = false; memberFor = it },
                onCancelInvite = { vm.cancelInvite(it) },
                onInvite = { members = false; onInvite() },
            )
        }
    }

    // 6 멤버 누르기: 방장이고 남이면 "방에서 내보내기"
    memberFor?.let { m ->
        LologSheet(onDismiss = { memberFor = null }) {
            MemberActionSheet(
                member = m,
                index = room?.memberIndex(m) ?: 0,
                canKick = room?.isMineOwner == true && !m.isMe,
                onKick = { memberFor = null; askKick = m },
            )
        }
    }

    askKick?.let { m ->
        LologDialog(
            title = "${m.nickname}님을 내보낼까요?",
            body = KICK_NOTE,
            confirmText = "내보내기",
            danger = true,
            loading = state.busy,
            onConfirm = { vm.kick(m) { askKick = null } },
            onDismiss = { if (!state.busy) askKick = null },
        )
    }

    if (renaming && room != null) {
        RenameDialog(
            current = room.name,
            loading = state.busy,
            onSave = { vm.rename(it) { renaming = false } },
            onDismiss = { if (!state.busy) renaming = false },
        )
    }

    // 방장이 아니거나 혼자 남은 방장: 확인 창
    if (askLeave && room != null) {
        val alone = room.isMineOwner && room.members.none { !it.isMe }
        LologDialog(
            title = "방에서 나갈까요?",
            body = if (alone) "혼자 남은 방이라 나가면 방이 지워져요. 각자 컨텐츠에 있는 만화는 그대로 남아요."
            else "올린 만화와 글은 \"나간 멤버\"로 남아요.",
            confirmText = "나가기",
            danger = true,
            loading = state.busy,
            onConfirm = { vm.leave(null) },
            onDismiss = { if (!state.busy) askLeave = false },
        )
    }

    // 8 방장이 나가기: 새 방장 고르기
    if (pickOwner && room != null) {
        PickOwnerSheet(
            room = room,
            loading = state.busy,
            onLeave = { vm.leave(it.userId) },
            onDismiss = { if (!state.busy) pickOwner = false },
        )
    }

    // 9 방 해산
    if (askDisband && room != null) {
        LologDialog(
            title = "\"${room.name}\" 방을 해산할까요?",
            body = "방의 만화와 대화가 모두 지워져요. 각자 컨텐츠에 있는 만화는 그대로 남아요. 멤버에게 알림이 가요.",
            confirmText = "해산하기",
            danger = true,
            loading = state.busy,
            onConfirm = { vm.disband() },
            onDismiss = { if (!state.busy) askDisband = false },
        )
    }

    askRemove?.let { p ->
        LologDialog(
            title = "이 만화를 방에서 내릴까요?",
            body = "이 만화에 달린 좋아요와 댓글도 같이 사라져요. 컨텐츠 화면의 만화는 그대로 있어요.",
            confirmText = "내리기",
            danger = true,
            loading = state.busy,
            onConfirm = { vm.remove(p) { askRemove = null } },
            onDismiss = { if (!state.busy) askRemove = null },
        )
    }
}

/**
 * 멤버 시트: "멤버 4명 · 초대 1명", 방장은 노란 "방장" 알약, 나는 "나".
 * 내가 방장이면 아래에 답을 기다리는 초대(흐린 얼굴 + "초대 취소")와 "+ 친구 초대"
 */
@Composable
private fun MemberSheet(
    room: Room,
    busy: Boolean,
    onMember: (RoomMember) -> Unit,
    onCancelInvite: (RoomInvite) -> Unit,
    onInvite: () -> Unit,
) {
    val owner = room.isMineOwner
    Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 28.dp)) {
        Text(
            "멤버 ${room.members.size}명" + if (room.invites.isNotEmpty()) " · 초대 ${room.invites.size}명" else "",
            fontSize = 17.sp, fontWeight = FontWeight.Bold, color = LologColors.Ink, modifier = Modifier.padding(bottom = 8.dp),
        )
        room.members.forEachIndexed { i, m ->
            Row(
                Modifier.fillMaxWidth().clip(LologShape.sm).clickable { onMember(m) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MemberAvatar(m, i, 36.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(m.nickname, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = LologColors.Ink)
                        if (m.isOwner || m.userId == room.ownerId) {
                            Spacer(Modifier.width(6.dp))
                            OwnerPill()
                        }
                        if (m.isMe) {
                            Spacer(Modifier.width(6.dp))
                            Text("나", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = LologColors.Accent)
                        }
                    }
                    if (m.suspended) Text(SUSPENDED, fontSize = 12.sp, color = LologColors.Muted)
                }
            }
        }
        if (owner) {
            if (room.invites.isNotEmpty()) {
                Text("답을 기다리는 초대", fontSize = 12.sp, color = LologColors.Muted, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                room.invites.forEach { inv ->
                    val who = inv.invitedUser ?: return@forEach
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.alpha(0.45f)) { MemberAvatar(who, room.members.size, 36.dp) }
                        Spacer(Modifier.width(12.dp))
                        Text(who.nickname, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = LologColors.Ink, modifier = Modifier.weight(1f))
                        SmallPillButton("초대 취소", { if (!busy) onCancelInvite(inv) })
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            PrimaryButton("+ 친구 초대", onInvite)
        }
    }
}

/** 정지된 계정 표시(멤버 시트) */
private const val SUSPENDED = "정지된 계정"

/** 내보내기 안내(멤버 누르기 시트·확인 창) */
private const val KICK_NOTE = "올린 만화와 글은 \"나간 멤버\"로 남아요. 다시 초대할 수 있어요."

/** 6 멤버 누르기: 얼굴·닉네임, 방장이고 남이면 빨간 "방에서 내보내기" */
@Composable
private fun MemberActionSheet(member: RoomMember, index: Int, canKick: Boolean, onKick: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MemberAvatar(member, index, 52.dp)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(member.nickname, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = LologColors.Ink)
                if (member.suspended) Text(SUSPENDED, fontSize = 13.sp, color = LologColors.Muted)
            }
        }
        if (canKick) {
            Spacer(Modifier.height(14.dp))
            HorizontalDivider(thickness = 1.dp, color = LologColors.Line)
            Column(Modifier.fillMaxWidth().clickable(onClick = onKick).padding(vertical = 14.dp)) {
                Text("방에서 내보내기", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = LologColors.Rec)
                Text(KICK_NOTE, fontSize = 12.sp, color = LologColors.Muted, modifier = Modifier.padding(top = 3.dp))
            }
        }
    }
}

/** 방 이름 바꾸기: 지금 이름이 들어 있는 입력 창. 앞뒤 공백을 뺀 길이가 1~20자면 저장 버튼이 켜져요 */
@Composable
private fun RenameDialog(current: String, loading: Boolean, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(current) }
    val trimmed = name.trim()
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().background(LologColors.Paper, LologShape.dialog)
                .padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("방 이름 바꾸기", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = LologColors.Ink)
            OutlinedTextField(
                value = name, onValueChange = { name = it.take(RoomRules.NAME_MAX) },
                singleLine = true,
                supportingText = { Text("${trimmed.length} / ${RoomRules.NAME_MAX}", fontSize = 12.sp, color = LologColors.Muted) },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = LologColors.Accent, unfocusedBorderColor = LologColors.Line),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton("취소", onDismiss, Modifier.weight(1f))
                PrimaryButton(
                    "저장", { onSave(trimmed) },
                    enabled = trimmed.length in 1..RoomRules.NAME_MAX, loading = loading, modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 8 새 방장 고르기: 남은 멤버 중 한 명(첫 사람을 골라 둠). [취소][{닉네임}님에게 넘기고 나가기] */
@Composable
private fun PickOwnerSheet(room: Room, loading: Boolean, onLeave: (RoomMember) -> Unit, onDismiss: () -> Unit) {
    val others = room.members.filter { !it.isMe }
    var picked by remember(room.id) { mutableStateOf(others.firstOrNull()?.userId) }
    val who = others.firstOrNull { it.userId == picked } ?: others.firstOrNull()
    LologSheet(onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 28.dp)) {
            Text("새 방장을 골라 주세요", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = LologColors.Ink)
            Text(
                "방장이 나가려면 남은 멤버 중 한 명에게 방장을 넘겨야 해요.", fontSize = 13.sp, color = LologColors.Muted,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            others.forEach { m ->
                Row(
                    Modifier.fillMaxWidth().clip(LologShape.sm).clickable { picked = m.userId }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = m.userId == who?.userId, onClick = { picked = m.userId },
                        colors = RadioButtonDefaults.colors(selectedColor = LologColors.Accent, unselectedColor = LologColors.Line),
                    )
                    Spacer(Modifier.width(4.dp))
                    MemberAvatar(m, room.memberIndex(m), 32.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(m.nickname, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = LologColors.Ink)
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton("취소", onDismiss, Modifier.weight(1f))
                PrimaryButton(
                    who?.let { "${it.nickname}님에게 넘기고 나가기" } ?: "나가기",
                    { who?.let(onLeave) },
                    enabled = who != null, loading = loading, modifier = Modifier.weight(1.6f),
                )
            }
        }
    }
}

/** 노란 작은 알약 "방장" */
@Composable
private fun OwnerPill() {
    Text(
        "방장", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = androidx.compose.ui.graphics.lerp(LologColors.Laugh, LologColors.Ink, 0.35f),
        modifier = Modifier.clip(LologShape.pill).background(LologColors.LaughSoft).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** 21 4컷 탭: 2열 격자 */
@Composable
private fun ComicGrid(
    room: Room,
    posts: List<RoomPost>,
    onOpen: (RoomPost) -> Unit,
    onLongPress: (RoomPost) -> Unit,
    onPlaced: (Long, Rect) -> Unit,
) {
    if (posts.isEmpty()) {
        CenterMessage(
            "아직 올린 만화가 없어요", "내 만화를 올려 친구와 같이 봐요",
            Modifier.fillMaxSize().padding(top = 40.dp),
        )
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize().background(LologColors.Canvas),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(posts, key = { it.id }) { p ->
            ComicCell(
                room, p,
                modifier = Modifier.onGloballyPositioned { onPlaced(p.id, it.boundsInWindow()) },
                onClick = { onOpen(p) },
                onLongClick = { onLongPress(p) },
            )
        }
    }
}

/** 만화 한 칸: 첫 칸 그림 · 제목 · 올린 사람 · 하트 · 말풍선. [onLongClick]이 없으면(떠오른 칸) 누를 수 없어요 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ComicCell(
    room: Room,
    post: RoomPost,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
) {
    Column(
        modifier
            .softCard(RoundedCornerShape(14.dp))
            .then(if (onLongClick != null) Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick) else Modifier)
            .padding(8.dp),
    ) {
        ComicImage(post.imageUrl, post.mockLines, Modifier.fillMaxWidth(), small = true, cacheKey = comicCacheKey(post.comicId))
        Spacer(Modifier.height(8.dp))
        Text(post.title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = LologColors.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            MemberAvatar(post.author, room.memberIndex(post.author), 18.dp)
            Spacer(Modifier.width(5.dp))
            Text(
                if (post.author.isMe) "내 만화" else post.author.displayName, fontSize = 11.sp, color = LologColors.Muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            LikeCount(post, 13)
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Outlined.ChatBubbleOutline, null, tint = LologColors.Muted, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(2.dp))
            Text("${post.commentCount}", fontSize = 11.sp, color = LologColors.Muted)
        }
    }
}

/** 하트 + 수. 내가 눌렀으면 채운 코랄 하트 */
@Composable
internal fun LikeCount(post: RoomPost, iconSize: Int, textSize: Int = 11) {
    Icon(
        if (post.likedByMe) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
        if (post.likedByMe) "좋아요 취소" else "좋아요",
        tint = LologColors.Accent, modifier = Modifier.size(iconSize.dp),
    )
    Spacer(Modifier.width(2.dp))
    Text("${post.likeCount}", fontSize = textSize.sp, color = if (post.likedByMe) LologColors.Accent else LologColors.Muted)
}

/** 방 안 멤버 순서(동그라미 색). 나간 멤버면 0 */
internal fun Room.memberIndex(m: RoomMember): Int = members.indexOfFirst { it.userId == m.userId }.coerceAtLeast(0)

/** "+ 내 만화 올리기" 시트: 완성된 내 만화만, 이미 올린 건 회색 "올림" */
@Composable
private fun PickComicSheet(comics: List<ComicSummary>?, posted: Set<Long>, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    LologSheet(onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("내 만화 올리기", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = LologColors.Ink)
            Text("완성된 만화만 올릴 수 있어요", fontSize = 12.sp, color = LologColors.Muted, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp))
            when {
                comics == null -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { TypingDots() }
                comics.isEmpty() -> Text(
                    "아직 완성된 만화가 없어요", fontSize = 14.sp, color = LologColors.Muted,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                else -> LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(comics, key = { it.id }) { c ->
                        val done = c.id in posted
                        Row(
                            Modifier.fillMaxWidth().clip(LologShape.sm)
                                .then(if (done) Modifier else Modifier.clickable { onPick(c.id) })
                                .alpha(if (done) 0.5f else 1f)
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ComicImage(c.thumbnailUrl ?: c.imageUrl, emptyList(), Modifier.size(52.dp), small = true)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(c.cardSummary ?: "4컷만화", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = LologColors.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(dayLabel(c.createdAt), fontSize = 12.sp, color = LologColors.Muted)
                            }
                            if (done) {
                                Text(
                                    "올림", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = LologColors.Muted,
                                    modifier = Modifier.clip(LologShape.pill).background(LologColors.Canvas).padding(horizontal = 9.dp, vertical = 3.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
