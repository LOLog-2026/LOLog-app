package com.lolog.app.ui.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lolog.app.AppContainer
import com.lolog.app.data.model.InboxItem
import com.lolog.app.data.model.PushPayload
import com.lolog.app.data.model.FriendRequestDirection
import com.lolog.app.ui.components.LIBRARY_NAME
import com.lolog.app.ui.components.LologDialog
import com.lolog.app.ui.components.OfflineBar
import com.lolog.app.ui.components.rememberIsOnline
import com.lolog.app.ui.screens.admin.AdminHomeScreen
import com.lolog.app.ui.screens.admin.AdminNoticeEditScreen
import com.lolog.app.ui.screens.admin.AdminNoticeListScreen
import com.lolog.app.ui.screens.comic.ComicListScreen
import com.lolog.app.ui.screens.comic.ComicScreen
import com.lolog.app.ui.screens.comic.VideoScreen
import com.lolog.app.ui.screens.friends.AddFriendScreen
import com.lolog.app.ui.screens.friends.BlockedScreen
import com.lolog.app.ui.screens.friends.NicknameScreen
import com.lolog.app.ui.screens.friends.ProfileCharacterScreen
import com.lolog.app.ui.screens.friends.VOICE_PICKED
import com.lolog.app.ui.screens.friends.VoicePickScreen
import com.lolog.app.ui.screens.friends.FriendRequestsScreen
import com.lolog.app.ui.screens.friends.FriendsScreen
import com.lolog.app.ui.screens.home.HomeScreen
import com.lolog.app.ui.screens.inbox.InboxScreen
import com.lolog.app.ui.screens.inbox.NoticeScreen
import com.lolog.app.ui.screens.inbox.RoomTarget
import com.lolog.app.ui.screens.rooms.Features
import com.lolog.app.ui.screens.rooms.RoomCreateScreen
import com.lolog.app.ui.screens.rooms.RoomInviteScreen
import com.lolog.app.ui.screens.rooms.RoomScreen
import com.lolog.app.ui.screens.library.CardDetailScreen
import com.lolog.app.ui.screens.library.CardEditScreen
import com.lolog.app.ui.screens.library.LibraryScreen
import com.lolog.app.ui.screens.recording.AnalyzingScreen
import com.lolog.app.ui.screens.recording.RecordingScreen
import com.lolog.app.ui.screens.settings.PasswordChangeScreen
import com.lolog.app.ui.screens.settings.SettingsScreen
import com.lolog.app.ui.screens.start.LoginScreen
import com.lolog.app.ui.screens.start.PasswordResetScreen
import com.lolog.app.ui.screens.start.PermissionScreen
import com.lolog.app.ui.screens.start.SignupScreen
import com.lolog.app.ui.screens.start.SplashScreen
import com.lolog.app.ui.theme.LologColors

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer 없음") }

private const val CARD_SAVED = "cardSaved"
/** 녹음을 끝내고 홈으로 돌아갈 때 넘기는 녹음 번호 */
private const val FINISHED_RECORDING = "finishedRecording"
/** 로그인 → 비밀번호 찾기로 넘기는 이메일(찾기 화면 이메일 칸을 미리 채워요) */
private const val RESET_EMAIL_INPUT = "resetEmailInput"
/** 비밀번호 찾기에서 바꾸고 로그인으로 돌아갈 때 넘기는 이메일 */
private const val PASSWORD_RESET_DONE = "passwordResetDone"
/** 비밀번호 바꾸기에서 설정으로 돌아갈 때 띠에 보여 줄 글자 */
private const val SETTINGS_MESSAGE = "settingsMessage"
/** 친구 추가·요청 화면에서 친구 탭으로 돌아갈 때 띠에 보여 줄 글자 */
private const val FRIENDS_MESSAGE = "friendsMessage"
/** 친구 추가에서 바로 친구가 됐으면 true: 친구 목록 맨 위 줄을 잠깐 연노랑으로 */
private const val FRIENDS_HIGHLIGHT = "friendsHighlight"
/** 친구 탭을 "방" 알약으로 열기(방을 나가거나 방이 없어졌을 때) */
private const val FRIENDS_ROOMS = "friendsRooms"
// 내 목소리 고르기에서 저장하고 내 캐릭터 화면으로 돌아올 때 띠 문구
private const val VOICE_MESSAGE = "voiceMessage"
/** 알림함 → 공지사항 자세히로 넘기는 값. 목록에서 받은 그대로 그려요(따로 조회 없음) */
private const val NOTICE_ID = "noticeId"
private const val NOTICE_TITLE = "noticeTitle"
private const val NOTICE_BODY = "noticeBody"
private const val NOTICE_DATE = "noticeDate"
/** 방 만들기·친구 초대 화면 → 방 화면 스낵바 문구 */
private const val ROOM_MESSAGE = "roomMessage"
/** 공지 쓰기·고치기 화면 → 관리자 공지 목록 스낵바 문구 */
private const val ADMIN_NOTICE_MESSAGE = "adminNoticeMessage"

object Routes {
    const val SPLASH = "splash"
    const val LOGIN = "login"
    const val SIGNUP = "signup"
    const val PASSWORD_RESET = "passwordReset"
    const val PERMISSION = "permission"
    const val HOME = "home"
    const val LIBRARY = "library"
    const val COMICS = "comics"
    const val FRIENDS = "friends"
    const val ADD_FRIEND = "friends/add"
    const val FRIEND_REQUESTS = "friends/requests"
    const val NICKNAME = "nickname"
    const val PROFILE_CHARACTER = "profile/character"
    const val VOICE_PICK = "profile/character/voice"
    const val BLOCKED = "blocked"
    const val SETTINGS = "settings"
    const val PASSWORD_CHANGE = "passwordChange"
    const val ADMIN = "admin"
    /** 관리자 공지 목록 */
    const val ADMIN_NOTICES = "admin/notices"
    /** 공지 쓰기·고치기. 고칠 공지는 목록 화면의 savedStateHandle로 넘겨요 */
    const val ADMIN_NOTICE_EDIT = "admin/notices/edit"
    /** 알림함(공지사항·알림) */
    const val INBOX = "inbox"
    const val NOTICE = "notice/{id}"
    const val RECORDING = "recording"
    const val ANALYZING = "analyzing/{recordingId}"
    const val CARD = "card/{cardId}"
    const val CARD_EDIT = "card/{cardId}/edit"
    const val COMIC = "comic/{comicId}"
    const val VIDEO = "video/{videoId}"
    /** 친구 방(목업에서만) */
    /** tab은 처음 열 탭(0 = 4컷, 2 = 대화). 없으면 4컷 */
    const val ROOM = "room/{id}?tab={tab}"
    const val ROOM_CREATE = "rooms/create"
    /** 방장: 친구 초대 */
    const val ROOM_INVITE = "rooms/{id}/invite"

    fun analyzing(id: Long) = "analyzing/$id"
    fun card(id: Long) = "card/$id"
    fun cardEdit(id: Long) = "card/$id/edit"
    fun comic(id: Long) = "comic/$id"
    fun video(id: Long) = "video/$id"
    fun room(id: Long) = "room/$id"
    fun roomChat(id: Long) = "room/$id?tab=2"
    fun roomInvite(id: Long) = "rooms/$id/invite"
    fun notice(id: Long) = "notice/$id"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

// 아래 탭 4개: 보관함 · 홈 · 컨텐츠 · 친구. 설정은 홈 위쪽 톱니 버튼으로 들어가요
private val tabs = listOf(
    Tab(Routes.LIBRARY, LIBRARY_NAME, Icons.Outlined.Style),
    Tab(Routes.HOME, "홈", Icons.Outlined.Home),
    Tab(Routes.COMICS, "컨텐츠", Icons.Outlined.GridView),
    Tab(Routes.FRIENDS, "친구", Icons.Outlined.People),
)

/** 탭 사이 이동: 스택을 쌓지 않고 상태를 남겨 둬요 */
fun NavHostController.goTab(route: String) {
    // 홈은 스택 맨 아래에 있으니 그 위를 모두 걷어 내요.
    // (저장해 둔 홈 탭 상태를 되살리다 '분석 중' 화면이 다시 나오던 문제 막기)
    if (route == Routes.HOME) {
        if (!popBackStack(Routes.HOME, inclusive = false)) navigate(Routes.HOME) { launchSingleTop = true }
        return
    }
    navigate(route) {
        popUpTo(Routes.HOME) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** 방 목록(친구 탭의 "방" 알약)으로 돌아가요. [message]는 거기서 띄울 스낵바 문구 */
fun NavHostController.openRoomList(message: String? = null) {
    if (!popBackStack(Routes.FRIENDS, inclusive = false)) goTab(Routes.FRIENDS)
    currentBackStackEntry?.savedStateHandle?.let {
        it[FRIENDS_ROOMS] = true
        if (message != null) it[FRIENDS_MESSAGE] = message
    }
}

/** 로그인 흐름 → 메인으로 갈 때 뒤로 가기로 못 돌아가게 */
fun NavHostController.resetTo(route: String) = navigate(route) {
    popUpTo(graph.id) { inclusive = true }
    launchSingleTop = true
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LologNavHost(pendingPush: PushPayload?, onPushHandled: () -> Unit) {
    val nav = rememberNavController()
    val container = LocalContainer.current
    val loggedIn by container.repository.isLoggedIn.collectAsState(initial = container.tokens.loggedIn.value)
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    val showTabs = current in tabs.map { it.route }

    // 친구 탭의 빨간 숫자 = 받은 요청 수. 홈이나 친구 화면을 열 때마다 새로 받아요(푸시는 이번에 없음)
    var receivedCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(current, loggedIn) {
        if (!loggedIn) { receivedCount = 0; return@LaunchedEffect }
        if (current == Routes.HOME || current == Routes.FRIENDS) {
            runCatching { container.repository.friendRequests(FriendRequestDirection.RECEIVED).size }
                .onSuccess { receivedCount = it }
        }
    }

    // 알림을 눌렀을 때 갈 화면. 알림함의 알림 줄도 같은 함수(openPush)를 써요
    LaunchedEffect(pendingPush, loggedIn, current) {
        val p = pendingPush ?: return@LaunchedEffect
        if (!loggedIn || current == null || current == Routes.SPLASH) return@LaunchedEffect
        nav.openPush(p)
        onPushHandled()
    }

    // 로그인 ⑦: 새 토큰도 못 받으면 "다시 로그인해 주세요" 창 → 로그인 화면
    val expired by container.tokens.sessionExpired.collectAsState()
    if (expired) {
        LologDialog(
            title = "다시 로그인해 주세요",
            body = "로그인한 지 오래돼서 안전을 위해 로그아웃됐어요. 모아 둔 카드와 만화는 그대로 있어요.",
            confirmText = "로그인하기",
            dismissText = "닫기",
            onConfirm = { container.tokens.expiredShown(); nav.resetTo(Routes.LOGIN) },
            onDismiss = { container.tokens.expiredShown(); nav.resetTo(Routes.LOGIN) },
        )
    }

    // 공용 ⑦⑧: 인터넷이 끊기면 모든 화면 위에 회색 띠 (스플래시 제외)
    val online by rememberIsOnline()
    Scaffold(
        containerColor = LologColors.Canvas,
        topBar = { OfflineBar(visible = !online && current != Routes.SPLASH) },
        bottomBar = { if (showTabs) BottomTabs(current, receivedCount) { nav.goTab(it) } },
    ) { padding ->
        NavHost(
            nav, startDestination = Routes.SPLASH,
            modifier = Modifier.padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding()).consumeWindowInsets(padding),
        ) {
            composable(Routes.SPLASH) {
                SplashScreen(onDone = {
                    nav.resetTo(
                        when {
                            !loggedIn -> Routes.LOGIN
                            !container.permissionAsked -> Routes.PERMISSION
                            else -> Routes.HOME
                        },
                    )
                })
            }
            composable(Routes.LOGIN) { entry ->
                val resetEmail by entry.savedStateHandle.getStateFlow<String?>(PASSWORD_RESET_DONE, null).collectAsState()
                LoginScreen(
                    onLoggedIn = { nav.resetTo(if (container.permissionAsked) Routes.HOME else Routes.PERMISSION) },
                    onSignup = { nav.navigate(Routes.SIGNUP) },
                    onFindPassword = { email ->
                        entry.savedStateHandle[RESET_EMAIL_INPUT] = email
                        nav.navigate(Routes.PASSWORD_RESET) { launchSingleTop = true }
                    },
                    resetEmail = resetEmail,
                    onResetShown = { entry.savedStateHandle[PASSWORD_RESET_DONE] = null },
                )
            }
            composable(Routes.PASSWORD_RESET) {
                val login = nav.previousBackStackEntry
                PasswordResetScreen(
                    initialEmail = remember { login?.savedStateHandle?.get<String>(RESET_EMAIL_INPUT).orEmpty() },
                    onBack = { nav.popBackStack() },
                    onDone = { email ->
                        login?.savedStateHandle?.set(PASSWORD_RESET_DONE, email)
                        nav.popBackStack()
                    },
                )
            }
            composable(Routes.SIGNUP) {
                SignupScreen(onBack = { nav.popBackStack() }, onDone = { nav.resetTo(Routes.PERMISSION) })
            }
            composable(Routes.PERMISSION) {
                PermissionScreen(onDone = { container.permissionAsked = true; nav.resetTo(Routes.HOME) })
            }
            composable(Routes.HOME) { entry ->
                val finished by entry.savedStateHandle.getStateFlow<Long?>(FINISHED_RECORDING, null).collectAsState()
                HomeScreen(
                    onRecord = { nav.navigate(Routes.RECORDING) },
                    onCard = { nav.navigate(Routes.card(it)) },
                    onLibrary = { nav.goTab(Routes.LIBRARY) },
                    onComics = { nav.goTab(Routes.COMICS) },
                    onSettings = { nav.navigate(Routes.SETTINGS) { launchSingleTop = true } },
                    onInbox = { nav.navigate(Routes.INBOX) { launchSingleTop = true } },
                    finishedRecordingId = finished,
                    onFinishedShown = { entry.savedStateHandle[FINISHED_RECORDING] = null },
                )
            }
            composable(Routes.RECORDING) {
                RecordingScreen(
                    onBack = { nav.popBackStack() },
                    onFinished = { id ->
                        // 녹음이 끝나면 '분석 중' 화면 대신 홈으로 돌아가 위쪽 띠로 상태를 보여 줘요
                        val home = runCatching { nav.getBackStackEntry(Routes.HOME) }.getOrNull()
                        if (home != null) {
                            home.savedStateHandle[FINISHED_RECORDING] = id
                            nav.popBackStack(Routes.HOME, inclusive = false)
                        } else {
                            nav.resetTo(Routes.HOME)
                            runCatching { nav.getBackStackEntry(Routes.HOME).savedStateHandle[FINISHED_RECORDING] = id }
                        }
                    },
                )
            }
            composable(Routes.ANALYZING, arguments = listOf(navArgument("recordingId") { type = NavType.LongType })) {
                AnalyzingScreen(
                    recordingId = it.arguments!!.getLong("recordingId"),
                    onHome = { nav.goTab(Routes.HOME) },
                    onLibrary = { nav.goTab(Routes.LIBRARY) },
                )
            }
            composable(Routes.LIBRARY) {
                LibraryScreen(
                    onCard = { nav.navigate(Routes.card(it)) },
                    onRecord = { nav.navigate(Routes.RECORDING) },
                    onBack = { nav.goTab(Routes.HOME) },
                )
            }
            composable(Routes.CARD, arguments = listOf(navArgument("cardId") { type = NavType.LongType })) { entry ->
                val id = entry.arguments!!.getLong("cardId")
                // 카드 고치기에서 저장하고 돌아오면 "저장했어요" 안내
                val saved by entry.savedStateHandle.getStateFlow(CARD_SAVED, false).collectAsState()
                CardDetailScreen(
                    savedJustNow = saved,
                    onSavedShown = { entry.savedStateHandle[CARD_SAVED] = false },
                    cardId = id,
                    onBack = { nav.popBackStack() },
                    onEdit = { nav.navigate(Routes.cardEdit(id)) },
                    onComic = { comicId -> nav.navigate(Routes.comic(comicId)) },
                    onDeleted = { nav.popBackStack() },
                    onNotFound = { nav.goTab(Routes.LIBRARY) },
                    onMakeCharacter = { nav.navigate(Routes.PROFILE_CHARACTER) { launchSingleTop = true } },
                    onVideo = { videoId -> nav.navigate(Routes.video(videoId)) },
                )
            }
            composable(Routes.CARD_EDIT, arguments = listOf(navArgument("cardId") { type = NavType.LongType })) {
                CardEditScreen(
                    cardId = it.arguments!!.getLong("cardId"),
                    onBack = { nav.popBackStack() },
                    onSaved = {
                        nav.previousBackStackEntry?.savedStateHandle?.set(CARD_SAVED, true)
                        nav.popBackStack()
                    },
                    onMakeCharacter = { nav.navigate(Routes.PROFILE_CHARACTER) { launchSingleTop = true } },
                )
            }
            composable(Routes.COMICS) {
                ComicListScreen(
                    onComic = { nav.navigate(Routes.comic(it)) },
                    onVideo = { nav.navigate(Routes.video(it)) },
                    onBack = { nav.goTab(Routes.HOME) },
                    onLibrary = { nav.goTab(Routes.LIBRARY) },
                )
            }
            composable(Routes.COMIC, arguments = listOf(navArgument("comicId") { type = NavType.LongType })) {
                ComicScreen(
                    comicId = it.arguments!!.getLong("comicId"),
                    onBack = { nav.popBackStack() },
                    onCard = { cardId -> nav.navigate(Routes.card(cardId)) },
                    onEditCard = { cardId -> nav.navigate(Routes.cardEdit(cardId)) },
                    onOtherComic = { comicId -> nav.navigate(Routes.comic(comicId)) { popUpTo(Routes.COMIC) { inclusive = true } } },
                    onDeleted = { nav.popBackStack() },
                )
            }
            composable(Routes.VIDEO, arguments = listOf(navArgument("videoId") { type = NavType.LongType })) {
                VideoScreen(
                    videoId = it.arguments!!.getLong("videoId"),
                    onBack = { nav.popBackStack() },
                    onDeleted = { nav.popBackStack() },
                )
            }
            composable(Routes.FRIENDS) { entry ->
                val message by entry.savedStateHandle.getStateFlow<String?>(FRIENDS_MESSAGE, null).collectAsState()
                val highlight by entry.savedStateHandle.getStateFlow(FRIENDS_HIGHLIGHT, false).collectAsState()
                val openRooms by entry.savedStateHandle.getStateFlow(FRIENDS_ROOMS, false).collectAsState()
                FriendsScreen(
                    onBack = { nav.goTab(Routes.HOME) },
                    onAddFriend = { nav.navigate(Routes.ADD_FRIEND) { launchSingleTop = true } },
                    onRequests = { nav.navigate(Routes.FRIEND_REQUESTS) { launchSingleTop = true } },
                    onChangeNickname = { nav.navigate(Routes.NICKNAME) { launchSingleTop = true } },
                    onMyCharacter = { nav.navigate(Routes.PROFILE_CHARACTER) { launchSingleTop = true } },
                    message = message,
                    highlightTop = highlight,
                    onMessageShown = {
                        entry.savedStateHandle[FRIENDS_MESSAGE] = null
                        entry.savedStateHandle[FRIENDS_HIGHLIGHT] = false
                    },
                    onRoom = if (Features.ROOMS) { id -> nav.navigate(Routes.room(id)) } else null,
                    onCreateRoom = if (Features.ROOMS) { { nav.navigate(Routes.ROOM_CREATE) { launchSingleTop = true } } } else null,
                    openRooms = openRooms,
                    onRoomsOpened = { entry.savedStateHandle[FRIENDS_ROOMS] = false },
                )
            }
            if (Features.ROOMS) {
                composable(Routes.ROOM_CREATE) {
                    RoomCreateScreen(
                        onBack = { nav.popBackStack() },
                        // 만들면 그 방으로. 뒤로 가면 방 만들기 대신 방 목록으로
                        onCreated = { id, invited ->
                            nav.navigate(Routes.room(id)) { popUpTo(Routes.ROOM_CREATE) { inclusive = true } }
                            nav.currentBackStackEntry?.savedStateHandle?.set(ROOM_MESSAGE, "${invited}명에게 초대를 보냈어요")
                        },
                        onAddFriend = { nav.navigate(Routes.ADD_FRIEND) { popUpTo(Routes.FRIENDS); launchSingleTop = true } },
                    )
                }
                composable(
                    Routes.ROOM,
                    arguments = listOf(
                        navArgument("id") { type = NavType.LongType },
                        navArgument("tab") { type = NavType.IntType; defaultValue = 0 },
                    ),
                ) { entry ->
                    val id = entry.arguments!!.getLong("id")
                    val message by entry.savedStateHandle.getStateFlow<String?>(ROOM_MESSAGE, null).collectAsState()
                    RoomScreen(
                        roomId = id,
                        initialTab = entry.arguments!!.getInt("tab"),
                        onBack = { nav.popBackStack() },
                        onLeft = { msg -> nav.openRoomList(msg) },
                        onInvite = { nav.navigate(Routes.roomInvite(id)) { launchSingleTop = true } },
                        message = message,
                        onMessageShown = { entry.savedStateHandle[ROOM_MESSAGE] = null },
                    )
                }
                composable(Routes.ROOM_INVITE, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                    RoomInviteScreen(
                        roomId = it.arguments!!.getLong("id"),
                        onBack = { nav.popBackStack() },
                        // 초대하면 방으로 돌아가 스낵바로 알려요
                        onInvited = { count ->
                            nav.previousBackStackEntry?.savedStateHandle?.set(ROOM_MESSAGE, "${count}명에게 초대를 보냈어요")
                            nav.popBackStack()
                        },
                    )
                }
            }
            composable(Routes.ADD_FRIEND) {
                AddFriendScreen(
                    onBack = { nav.popBackStack() },
                    onDone = { message, becameFriends ->
                        nav.previousBackStackEntry?.savedStateHandle?.let {
                            it[FRIENDS_MESSAGE] = message
                            it[FRIENDS_HIGHLIGHT] = becameFriends
                        }
                        nav.popBackStack()
                    },
                )
            }
            composable(Routes.FRIEND_REQUESTS) {
                FriendRequestsScreen(onBack = { nav.popBackStack() })
            }
            composable(Routes.SETTINGS) { entry ->
                val message by entry.savedStateHandle.getStateFlow<String?>(SETTINGS_MESSAGE, null).collectAsState()
                SettingsScreen(
                    onBack = { nav.popBackStack() },
                    onLoggedOut = { nav.resetTo(Routes.LOGIN) },
                    onChangePassword = { nav.navigate(Routes.PASSWORD_CHANGE) { launchSingleTop = true } },
                    onNickname = { nav.navigate(Routes.NICKNAME) { launchSingleTop = true } },
                    onBlocked = { nav.navigate(Routes.BLOCKED) { launchSingleTop = true } },
                    onAdmin = { nav.navigate(Routes.ADMIN) { launchSingleTop = true } },
                    message = message,
                    onMessageShown = { entry.savedStateHandle[SETTINGS_MESSAGE] = null },
                )
            }
            composable(Routes.ADMIN) {
                AdminHomeScreen(
                    onBack = { nav.popBackStack() },
                    onNotices = { nav.navigate(Routes.ADMIN_NOTICES) { launchSingleTop = true } },
                )
            }
            composable(Routes.ADMIN_NOTICES) { entry ->
                val message by entry.savedStateHandle.getStateFlow<String?>(ADMIN_NOTICE_MESSAGE, null).collectAsState()
                AdminNoticeListScreen(
                    onBack = { nav.popBackStack() },
                    onNew = {
                        entry.savedStateHandle[NOTICE_ID] = null
                        nav.navigate(Routes.ADMIN_NOTICE_EDIT) { launchSingleTop = true }
                    },
                    onEdit = { n ->
                        entry.savedStateHandle[NOTICE_ID] = n.id
                        entry.savedStateHandle[NOTICE_TITLE] = n.title
                        entry.savedStateHandle[NOTICE_BODY] = n.body
                        entry.savedStateHandle[NOTICE_DATE] = n.createdAt
                        nav.navigate(Routes.ADMIN_NOTICE_EDIT) { launchSingleTop = true }
                    },
                    message = message,
                    onMessageShown = { entry.savedStateHandle[ADMIN_NOTICE_MESSAGE] = null },
                )
            }
            composable(Routes.ADMIN_NOTICE_EDIT) {
                val list = nav.previousBackStackEntry?.savedStateHandle
                // 목록에서 넘긴 id가 있으면 고치기, 없으면 새로 쓰기
                val original = remember {
                    list?.get<Long>(NOTICE_ID)?.let { id ->
                        InboxItem.Notice(
                            id, list.get<String>(NOTICE_TITLE).orEmpty(), list.get<String>(NOTICE_BODY).orEmpty(),
                            list.get<String>(NOTICE_DATE).orEmpty(), read = true,
                        )
                    }
                }
                AdminNoticeEditScreen(
                    original = original,
                    onBack = { nav.popBackStack() },
                    onDone = { message ->
                        list?.set(ADMIN_NOTICE_MESSAGE, message)
                        nav.popBackStack()
                    },
                )
            }
            composable(Routes.INBOX) { entry ->
                InboxScreen(
                    onBack = { nav.popBackStack() },
                    onNotice = { n ->
                        entry.savedStateHandle[NOTICE_ID] = n.id
                        entry.savedStateHandle[NOTICE_TITLE] = n.title
                        entry.savedStateHandle[NOTICE_BODY] = n.body
                        entry.savedStateHandle[NOTICE_DATE] = n.createdAt
                        nav.navigate(Routes.notice(n.id)) { launchSingleTop = true }
                    },
                    onPush = { nav.openPush(it) },
                    onRoom = { target ->
                        when (target) {
                            RoomTarget.List -> nav.openRoomList()
                            is RoomTarget.Chat -> nav.navigate(Routes.roomChat(target.roomId)) { launchSingleTop = true }
                        }
                    },
                )
            }
            composable(Routes.NOTICE, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                val id = entry.arguments!!.getLong("id")
                val inbox = nav.previousBackStackEntry?.savedStateHandle
                // 알림함에서 넘긴 값이 이 공지 것일 때만 그려요
                val ok = remember { inbox?.get<Long>(NOTICE_ID) == id }
                NoticeScreen(
                    title = remember { if (ok) inbox?.get<String>(NOTICE_TITLE) else null },
                    body = remember { if (ok) inbox?.get<String>(NOTICE_BODY).orEmpty() else "" },
                    createdAt = remember { if (ok) inbox?.get<String>(NOTICE_DATE) else null },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(Routes.PROFILE_CHARACTER) { entry ->
                val message by entry.savedStateHandle.getStateFlow<String?>(VOICE_MESSAGE, null).collectAsState()
                ProfileCharacterScreen(
                    onBack = { nav.popBackStack() },
                    onPickVoice = { nav.navigate(Routes.VOICE_PICK) { launchSingleTop = true } },
                    message = message,
                    onMessageShown = { entry.savedStateHandle[VOICE_MESSAGE] = null },
                )
            }
            composable(Routes.VOICE_PICK) {
                VoicePickScreen(
                    onBack = { nav.popBackStack() },
                    onDone = {
                        nav.previousBackStackEntry?.savedStateHandle?.set(VOICE_MESSAGE, VOICE_PICKED)
                        nav.popBackStack()
                    },
                )
            }
            composable(Routes.NICKNAME) {
                NicknameScreen(
                    onBack = { nav.popBackStack() },
                    onDone = { message ->
                        // 설정과 친구 탭 두 곳에서 들어와요. 돌아갈 화면의 띠 열쇠에 넣어요
                        val prev = nav.previousBackStackEntry
                        val key = if (prev?.destination?.route == Routes.FRIENDS) FRIENDS_MESSAGE else SETTINGS_MESSAGE
                        prev?.savedStateHandle?.set(key, message)
                        nav.popBackStack()
                    },
                )
            }
            composable(Routes.BLOCKED) {
                BlockedScreen(onBack = { nav.popBackStack() })
            }
            composable(Routes.PASSWORD_CHANGE) {
                PasswordChangeScreen(
                    onBack = { nav.popBackStack() },
                    onDone = { message ->
                        nav.previousBackStackEntry?.savedStateHandle?.set(SETTINGS_MESSAGE, message)
                        nav.popBackStack()
                    },
                )
            }
        }
    }
}

@Composable
private fun BottomTabs(current: String?, receivedCount: Int, onTab: (String) -> Unit) {
    NavigationBar(containerColor = LologColors.Paper, tonalElevation = 0.dp) {
        tabs.forEach { tab ->
            NavigationBarItem(
                selected = current == tab.route,
                onClick = { onTab(tab.route) },
                icon = {
                    val count = if (tab.route == Routes.FRIENDS) receivedCount else 0
                    BadgedBox(badge = {
                        if (count > 0) Badge(containerColor = LologColors.Rec, contentColor = LologColors.Paper) { Text(if (count > 99) "99+" else "$count") }
                    }) { Icon(tab.icon, contentDescription = null) }
                },
                label = { Text(tab.label, style = MaterialTheme.typography.labelSmall) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = LologColors.Accent,
                    selectedTextColor = LologColors.Accent,
                    indicatorColor = LologColors.AccentSoft,
                    unselectedIconColor = LologColors.Muted,
                    unselectedTextColor = LologColors.Muted,
                ),
            )
        }
    }
}
