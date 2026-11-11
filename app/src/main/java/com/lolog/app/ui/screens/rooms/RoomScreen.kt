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
        val left: Boolean = false,
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
            _state.update { it.copy(error = errorText(e)) }
        }
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
                _state.update { it.copy(busy = false, snackbar = errorText(e)) }
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
                _state.update { it.copy(sending = false, snackbar = errorText(e)) }
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
                _state.update { it.copy(busy = false, snackbar = errorText(e)) }
                // 이미 답했거나 취소된 초대, 방장이 바뀐 경우는 방을 다시 받아 맞춰요
                val code = (e as? LologException)?.code
                if (code == RoomErrorCodes.ROOM_INVITE_NOT_FOUND || code == RoomErrorCodes.ROOM_OWNER_ONLY) reloadRoom()
            }
        }
    }

    fun leave() {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try {
                rooms.leaveRoom(roomId)
                _state.update { it.copy(busy = false, left = true) }
            } catch (e: Throwable) {
                _state.update { it.copy(busy = false, snackbar = errorText(e)) }
            }
        }
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
    onLeft: () -> Unit,
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
    var askRemove by remember { mutableStateOf<RoomPost?>(null) }
    var picking by remember { mutableStateOf(false) }
    // 칸마다 화면 위치(꾹 누르면 그 자리에 칸을 다시 그려요). 그릴 때 쓰지 않아서 상태로 두지 않아요
    val cellBounds = remember { mutableMapOf<Long, Rect>() }
    var rootOffset by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(state.left) { if (state.left) onLeft() }
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

    if (menu) {
        LologSheet(onDismiss = { menu = false }) {
            Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 28.dp)) {
                SheetRow("멤버 보기", LologColors.Ink) { menu = false; members = true }
                SheetRow("방 나가기", LologColors.Rec) { menu = false; askLeave = true }
            }
        }
    }

    if (members && room != null) {
        LologSheet(onDismiss = { members = false }) {
            MemberSheet(
                room,
                busy = state.busy,
                onCancelInvite = { vm.cancelInvite(it) },
                onInvite = { members = false; onInvite() },
            )
        }
    }

    if (askLeave && room != null) {
        LologDialog(
            title = "${room.name}에서 나갈까요?",
            body = "나가면 이 방의 만화와 대화를 더 볼 수 없어요.",
            confirmText = "나가기",
            danger = true,
            loading = state.busy,
            onConfirm = { vm.leave() },
            onDismiss = { if (!state.busy) askLeave = false },
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
private fun MemberSheet(room: Room, busy: Boolean, onCancelInvite: (RoomInvite) -> Unit, onInvite: () -> Unit) {
    val owner = room.isMineOwner
    Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 28.dp)) {
        Text(
            "멤버 ${room.members.size}명" + if (room.invites.isNotEmpty()) " · 초대 ${room.invites.size}명" else "",
            fontSize = 17.sp, fontWeight = FontWeight.Bold, color = LologColors.Ink, modifier = Modifier.padding(bottom = 8.dp),
        )
        room.members.forEachIndexed { i, m ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                MemberAvatar(m, i, 36.dp)
                Spacer(Modifier.width(12.dp))
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
                if (post.author.isMe) "내 만화" else post.author.nickname, fontSize = 11.sp, color = LologColors.Muted,
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
