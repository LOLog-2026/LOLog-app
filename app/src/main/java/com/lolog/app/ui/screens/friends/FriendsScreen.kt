package com.lolog.app.ui.screens.friends

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PersonAddAlt
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lolog.app.data.model.Friend
import com.lolog.app.data.model.FriendRequestDirection
import com.lolog.app.data.model.FriendRules
import com.lolog.app.data.model.Profile
import com.lolog.app.data.model.ProfileCharacterStatus
import com.lolog.app.data.repo.LologRepository
import com.lolog.app.ui.components.*
import com.lolog.app.ui.navigation.LocalContainer
import com.lolog.app.ui.screens.rooms.Features
import com.lolog.app.ui.screens.rooms.RoomListScreen
import com.lolog.app.ui.screens.start.LabeledField
import com.lolog.app.ui.theme.LologColors
import com.lolog.app.ui.theme.LologShape
import com.lolog.app.util.dayLabel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class FriendsViewModel(private val repo: LologRepository) : ViewModel() {
    data class State(
        val loading: Boolean = true,
        val error: String? = null,
        val profile: Profile? = null,
        val friends: List<Friend> = emptyList(),
        val receivedCount: Int = 0,
        val sentCount: Int = 0,
        val savingNickname: Boolean = false,
        val busy: Boolean = false,
        val snackbar: String? = null,
        val loads: Int = 0, // 새로 받기에 성공한 횟수. 맨 위 줄 연노랑을 새 목록에 칠하려고 봐요
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /** 프로필·친구·요청 수를 한 번에 새로 받아요. 화면에 들어올 때마다 불러요 */
    fun load() = viewModelScope.launch {
        try {
            coroutineScope {
                val profile = async { repo.profile() }
                val friends = async { repo.friends() }
                val received = async { repo.friendRequests(FriendRequestDirection.RECEIVED) }
                val sent = async { repo.friendRequests(FriendRequestDirection.SENT) }
                val p = profile.await()
                val f = friends.await()
                val r = received.await().size
                val s = sent.await().size
                _state.update { it.copy(loading = false, error = null, profile = p, friends = f, receivedCount = r, sentCount = s, loads = it.loads + 1) }
            }
        } catch (e: Throwable) {
            _state.update { it.copy(loading = false, error = if (it.profile == null) errorText(e) else null, snackbar = if (it.profile != null) errorText(e) else null) }
        }
    }

    /** 친구 화면 맨 위 칸에서 닉네임을 처음 정해요. 정하면 친구 코드가 생겨요 */
    fun setNickname(raw: String) {
        if (raw.isBlank() || _state.value.savingNickname) return
        viewModelScope.launch {
            _state.update { it.copy(savingNickname = true) }
            try {
                val p = repo.updateNickname(raw)
                _state.update { it.copy(savingNickname = false, profile = p) }
            } catch (e: Throwable) {
                _state.update { it.copy(savingNickname = false, snackbar = errorText(e)) }
            }
        }
    }

    /** 친구 끊기·차단하기. 끝나면 목록에서 빼요 */
    fun remove(friend: Friend, block: Boolean, onDone: () -> Unit) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try {
                if (block) repo.block(friend.userId) else repo.removeFriend(friend.userId)
                _state.update { s -> s.copy(busy = false, friends = s.friends.filterNot { it.userId == friend.userId }) }
            } catch (e: Throwable) {
                _state.update { it.copy(busy = false, snackbar = errorText(e)) }
            }
            onDone()
        }
    }

    fun snackbarShown() = _state.update { it.copy(snackbar = null) }
}

// ── 친구 탭 ──────────────────────────────────────────
// 시안: 친구-시안-1110.png. 위 막대는 다른 탭처럼 제목만

@Composable
fun FriendsScreen(
    onBack: () -> Unit,
    onAddFriend: () -> Unit,
    onRequests: () -> Unit,
    onChangeNickname: () -> Unit,
    /** 맨 위 상자의 "내 캐릭터" 줄 */
    onMyCharacter: () -> Unit = {},
    /** 다른 화면에서 돌아올 때 띠에 보여 줄 글자(예: 친구 요청을 보냈어요) */
    message: String? = null,
    /** 친구 추가에서 바로 친구가 됐으면 목록 맨 위 줄을 잠깐 연노랑으로 */
    highlightTop: Boolean = false,
    onMessageShown: () -> Unit = {},
    /** 친구 방(목업에서만). null이면 "방" / "친구" 알약 없이 지금 모양 그대로 */
    onRoom: ((Long) -> Unit)? = null,
    onCreateRoom: (() -> Unit)? = null,
) {
    val roomsOn = Features.ROOMS && onRoom != null && onCreateRoom != null
    // 맨 위 알약: 처음엔 "방"
    var showRooms by rememberSaveable { mutableStateOf(roomsOn) }
    val repo = LocalContainer.current.repository
    val vm = viewModel { FriendsViewModel(repo) }
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var nickname by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var menuFor by remember { mutableStateOf<Friend?>(null) }
    var confirm by remember { mutableStateOf<Pair<Friend, Boolean>?>(null) } // (친구, 차단이면 true)
    var highlight by remember { mutableStateOf(false) }

    BackHandler(onBack = onBack)
    LaunchedEffect(Unit) { vm.load() }
    LaunchedEffect(state.snackbar) { state.snackbar?.let { snackbar.showSnackbar(it); vm.snackbarShown() } }
    // 띠는 화면 범위에서 띄워요(값을 비우면 이 효과가 다시 돌아 띠가 끊겨요)
    LaunchedEffect(message, highlightTop) {
        // 새 친구가 들어간 목록을 받은 뒤에 칠해요(돌아온 직후 목록은 예전 것)
        if (highlightTop) {
            val start = vm.state.value.loads
            scope.launch { vm.state.first { it.loads > start }; highlight = true }
        }
        message?.let { onMessageShown(); scope.launch { snackbar.showSnackbar(it) } }
    }
    LaunchedEffect(highlight) { if (highlight) { delay(1_800); highlight = false } }

    Scaffold(
        containerColor = LologColors.Paper,
        snackbarHost = { LologSnackbarHost(snackbar) },
        topBar = {
            LologTopBar("친구", onBack = onBack) {
                if (roomsOn && showRooms) {
                    TextButton(onClick = { onCreateRoom?.invoke() }) {
                        Text("+ 방 만들기", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = LologColors.Accent)
                    }
                }
            }
        },
    ) { scaffoldPad ->
      Column(Modifier.padding(scaffoldPad)) {
        if (roomsOn) {
            Row(
                Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterPill("방", showRooms) { showRooms = true }
                FilterPill("친구", !showRooms) { showRooms = false }
            }
        }
        // 위 여백은 바깥 Column이 줬어요
        val pad = PaddingValues(0.dp)
        when {
            roomsOn && showRooms -> RoomListScreen(
                onRoom = { onRoom?.invoke(it) },
                onCreate = { onCreateRoom?.invoke() },
                onFriends = { showRooms = false },
                modifier = Modifier.weight(1f),
            )
            state.loading && state.profile == null -> LoadingBox(Modifier.padding(pad))
            state.error != null -> ErrorBox(state.error!!, onRetry = { vm.load() }, modifier = Modifier.padding(pad))
            else -> {
                val profile = state.profile ?: Profile()
                val hasNickname = profile.nickname != null
                val trimmed = query.trim()
                val shown = if (trimmed.isEmpty()) state.friends else state.friends.filter { it.nickname.contains(trimmed, ignoreCase = true) }
                // 닉네임이 겹치는 친구만 옆에 코드를 작게 보여 줘요
                val dupNames = state.friends.groupingBy { it.nickname }.eachCount().filterValues { it > 1 }.keys

                LazyColumn(
                    Modifier.padding(pad).fillMaxSize(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
                ) {
                    item {
                        MyBox(
                            profile = profile,
                            nickname = nickname,
                            onNickname = { nickname = it.take(FriendRules.NICKNAME_MAX) },
                            saving = state.savingNickname,
                            onSave = { vm.setNickname(nickname) },
                            onChange = onChangeNickname,
                            onMyCharacter = onMyCharacter,
                            onCopy = { code ->
                                clipboard.setText(AnnotatedString(code))
                                scope.launch { snackbar.showSnackbar("코드를 복사했어요") }
                            },
                            onShare = { code ->
                                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "LOLog 친구 코드: $code")
                                context.startActivity(Intent.createChooser(send, null))
                            },
                        )
                        Spacer(Modifier.height(16.dp))
                        PrimaryButton(
                            "친구 추가", onAddFriend, enabled = hasNickname,
                            leading = { Icon(Icons.Outlined.PersonAddAlt, null, modifier = Modifier.size(18.dp)) },
                        )
                        if (!hasNickname) {
                            Text(
                                "닉네임을 정하면 친구를 추가할 수 있어요", fontSize = 12.sp, color = LologColors.Muted,
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier.fillMaxWidth().clip(LologShape.sm).clickable(onClick = onRequests).padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("받은 요청 ${state.receivedCount}", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = LologColors.Ink)
                            Text(" · ", fontSize = 14.sp, color = LologColors.Muted)
                            Text("보낸 요청 ${state.sentCount}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = LologColors.Muted)
                            Spacer(Modifier.weight(1f))
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = LologColors.Muted)
                        }
                        if (state.friends.isNotEmpty()) {
                            OutlinedTextField(
                                value = query, onValueChange = { query = it }, singleLine = true,
                                placeholder = { Text("닉네임 검색", fontSize = 14.sp, color = LologColors.Muted) },
                                leadingIcon = { Icon(Icons.Outlined.Search, null, tint = LologColors.Muted, modifier = Modifier.size(18.dp)) },
                                shape = LologShape.sm,
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = LologColors.Accent, unfocusedBorderColor = LologColors.Line,
                                    focusedContainerColor = LologColors.Paper, unfocusedContainerColor = LologColors.Paper,
                                ),
                            )
                            Spacer(Modifier.height(14.dp))
                        }
                        SectionTitle("친구 ${state.friends.size}")
                        Spacer(Modifier.height(4.dp))
                    }
                    if (state.friends.isEmpty()) {
                        item {
                            CenterMessage(
                                "아직 친구가 없어요", "친구 코드를 주고받으면 서로 친구가 돼요",
                                Modifier.fillMaxWidth().padding(top = 16.dp),
                            )
                        }
                    } else if (shown.isEmpty()) {
                        item {
                            Text(
                                "'$trimmed' 닉네임의 친구가 없어요", fontSize = 14.sp, color = LologColors.Muted,
                                modifier = Modifier.padding(vertical = 20.dp),
                            )
                        }
                    }
                    itemsIndexed(shown, key = { _, f -> f.userId }) { i, f ->
                        FriendRow(
                            friend = f,
                            showCode = f.nickname in dupNames,
                            highlighted = highlight && i == 0 && trimmed.isEmpty(),
                            onMore = { menuFor = f },
                        )
                    }
                }
            }
        }
      }
    }

    menuFor?.let { f ->
        LologSheet(onDismiss = { menuFor = null }) {
            Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 28.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FriendAvatar(f.thumbnailUrl, 52.dp)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(f.nickname, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = LologColors.Ink)
                        Text("${f.friendCode} · ${dayLabel(f.friendsSince)}부터 친구", fontSize = 13.sp, color = LologColors.Muted)
                    }
                }
                Spacer(Modifier.height(14.dp))
                HorizontalDivider(thickness = 1.dp, color = LologColors.Line)
                SheetRow("친구 끊기", LologColors.Ink) { menuFor = null; confirm = f to false }
                SheetRow("차단하기", LologColors.Rec) { menuFor = null; confirm = f to true }
            }
        }
    }

    // 탭 화면 바탕의 기본 글자색이 회색이라 확인 창 제목은 Ink로
    confirm?.let { (f, block) -> CompositionLocalProvider(LocalContentColor provides LologColors.Ink) {
        LologDialog(
            title = if (block) "${f.nickname}님을 차단할까요?" else "${f.nickname}님과 친구를 끊을까요?",
            body = if (block) {
                "친구에서 빠지고, ${f.nickname}님은 내 코드로 요청을 보낼 수 없어요. 차단한 건 ${f.nickname}님에게 알리지 않아요."
            } else {
                "서로의 친구 목록에서 빠져요. 나중에 다시 친구 요청을 보낼 수 있어요."
            },
            confirmText = if (block) "차단하기" else "친구 끊기",
            danger = true,
            loading = state.busy,
            onConfirm = { vm.remove(f, block) { confirm = null } },
            onDismiss = { if (!state.busy) confirm = null },
        )
    } }
}

/** 맨 위 상자: 닉네임이 없으면 입력 칸, 있으면 내 닉네임과 친구 코드 */
@Composable
private fun MyBox(
    profile: Profile,
    nickname: String,
    onNickname: (String) -> Unit,
    saving: Boolean,
    onSave: () -> Unit,
    onChange: () -> Unit,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
    onMyCharacter: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(LologColors.Canvas).padding(18.dp),
    ) {
        val name = profile.nickname
        if (name == null) {
            LabeledField(
                "닉네임", nickname, onNickname,
                placeholder = "친구에게 보여질 별명",
                counter = "${nickname.length} / ${FriendRules.NICKNAME_MAX}",
                info = "닉네임을 정하면 내 친구 코드가 생겨요",
                enabled = !saving,
            ) {
                Button(
                    onClick = onSave,
                    enabled = nickname.isNotBlank() && !saving,
                    shape = LologShape.sm,
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LologColors.Accent, contentColor = LologColors.Paper,
                        disabledContainerColor = LologColors.Line, disabledContentColor = LologColors.Muted,
                    ),
                    modifier = Modifier.height(56.dp),
                ) {
                    if (saving) TypingDots(color = LologColors.Paper, dot = 5.dp)
                    else Text("정하기", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = LologColors.Ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                SmallPillButton("바꾸기", onChange)
            }
            profile.friendCode?.let { code ->
                Spacer(Modifier.height(14.dp))
                Text("내 친구 코드", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = LologColors.Muted)
                Spacer(Modifier.height(2.dp))
                Text(
                    code, fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                    letterSpacing = 2.sp, color = LologColors.Ink,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallPillButton("코드 복사", { onCopy(code) })
                    SmallPillButton("코드 공유", { onShare(code) })
                }
            }
        }
        // 내 캐릭터(characters.md 3장). 닉네임이 없어도 만들 수 있어요
        HorizontalDivider(Modifier.padding(vertical = 14.dp), thickness = 1.dp, color = LologColors.Line)
        val character = profile.character
        Row(Modifier.fillMaxWidth().clickable(onClick = onMyCharacter), verticalAlignment = Alignment.CenterVertically) {
            SpeakerAvatar(profile.nickname ?: "나", 0, size = 40.dp, thumbnailUrl = character?.thumbnailUrl)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("내 캐릭터", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = LologColors.Ink)
                Text(
                    when (character?.status) {
                        ProfileCharacterStatus.READY -> "친구가 내 캐릭터를 만화에 쓸 수 있어요"
                        ProfileCharacterStatus.PROCESSING -> "그리는 중이에요"
                        ProfileCharacterStatus.FAILED -> "만들지 못했어요. 다시 만들어 보세요"
                        else -> "사진 한 장으로 만들어요"
                    },
                    fontSize = 12.sp, color = LologColors.Muted,
                )
            }
            SmallPillButton(if (character == null) "만들기" else "보기", onMyCharacter)
        }
    }
}

/** 친구 한 줄: 그림 · 닉네임(겹치면 코드) · ⋯ */
@Composable
private fun FriendRow(friend: Friend, showCode: Boolean, highlighted: Boolean, onMore: () -> Unit) {
    val bg by animateColorAsState(if (highlighted) LologColors.AccentSoft else Color.Transparent, tween(400), label = "friend-row")
    Row(
        Modifier.fillMaxWidth().clip(LologShape.sm).background(bg).padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FriendAvatar(friend.thumbnailUrl)
        Spacer(Modifier.width(12.dp))
        Text(friend.nickname, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = LologColors.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (showCode) {
            Spacer(Modifier.width(6.dp))
            Text(friend.friendCode, fontSize = 11.sp, color = LologColors.Muted, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onMore) { Icon(Icons.Outlined.MoreHoriz, contentDescription = "더 보기", tint = LologColors.Muted) }
    }
}

@Composable
internal fun SheetRow(text: String, color: Color, onClick: () -> Unit) {
    Text(
        text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = color,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp),
    )
}
