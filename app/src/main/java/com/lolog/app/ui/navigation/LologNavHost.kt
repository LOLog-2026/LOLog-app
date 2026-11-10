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
import com.lolog.app.data.model.PushPayload
import com.lolog.app.data.model.FriendRequestDirection
import com.lolog.app.data.model.PushType
import com.lolog.app.ui.components.LIBRARY_NAME
import com.lolog.app.ui.components.LologDialog
import com.lolog.app.ui.components.OfflineBar
import com.lolog.app.ui.components.rememberIsOnline
import com.lolog.app.ui.screens.comic.ComicListScreen
import com.lolog.app.ui.screens.comic.ComicScreen
import com.lolog.app.ui.screens.friends.AddFriendScreen
import com.lolog.app.ui.screens.friends.BlockedScreen
import com.lolog.app.ui.screens.friends.NicknameScreen
import com.lolog.app.ui.screens.friends.FriendRequestsScreen
import com.lolog.app.ui.screens.friends.FriendsScreen
import com.lolog.app.ui.screens.home.HomeScreen
import com.lolog.app.ui.screens.rooms.Features
import com.lolog.app.ui.screens.rooms.RoomCreateScreen
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
    const val BLOCKED = "blocked"
    const val SETTINGS = "settings"
    const val PASSWORD_CHANGE = "passwordChange"
    const val RECORDING = "recording"
    const val ANALYZING = "analyzing/{recordingId}"
    const val CARD = "card/{cardId}"
    const val CARD_EDIT = "card/{cardId}/edit"
    const val COMIC = "comic/{comicId}"
    /** 친구 방(목업에서만) */
    const val ROOM = "room/{id}"
    const val ROOM_CREATE = "rooms/create"

    fun analyzing(id: Long) = "analyzing/$id"
    fun card(id: Long) = "card/$id"
    fun cardEdit(id: Long) = "card/$id/edit"
    fun comic(id: Long) = "comic/$id"
    fun room(id: Long) = "room/$id"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

// 아래 탭 4개: 보관함 · 홈 · 내 만화 · 친구. 설정은 홈 위쪽 톱니 버튼으로 들어가요
private val tabs = listOf(
    Tab(Routes.LIBRARY, LIBRARY_NAME, Icons.Outlined.Style),
    Tab(Routes.HOME, "홈", Icons.Outlined.Home),
    Tab(Routes.COMICS, "내 만화", Icons.Outlined.GridView),
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

    // 알림을 눌렀을 때 갈 화면 (협의 2-2 기본안)
    LaunchedEffect(pendingPush, loggedIn, current) {
        val p = pendingPush ?: return@LaunchedEffect
        if (!loggedIn || current == null || current == Routes.SPLASH) return@LaunchedEffect
        when (p.type) {
            // 명세: 새 카드 → 보관함 새로고침. (제안한 cardIds가 오고 1개면 그 카드로)
            PushType.CARDS_CREATED ->
                if (p.cardIds.size == 1) nav.navigate(Routes.card(p.cardIds.first())) else nav.goTab(Routes.LIBRARY)
            PushType.COMIC_DONE -> p.comicId?.let { nav.navigate(Routes.comic(it)) } ?: nav.goTab(Routes.COMICS)
            // 실패 → 그 만화 화면. "만들지 못했어요" 안내와 "다시 시도" 버튼이 거기 있어요
            PushType.COMIC_FAILED -> p.comicId?.let { nav.navigate(Routes.comic(it)) } ?: nav.goTab(Routes.COMICS)
        }
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
                )
            }
            composable(Routes.COMICS) {
                ComicListScreen(
                    onComic = { nav.navigate(Routes.comic(it)) },
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
            composable(Routes.FRIENDS) { entry ->
                val message by entry.savedStateHandle.getStateFlow<String?>(FRIENDS_MESSAGE, null).collectAsState()
                val highlight by entry.savedStateHandle.getStateFlow(FRIENDS_HIGHLIGHT, false).collectAsState()
                FriendsScreen(
                    onBack = { nav.goTab(Routes.HOME) },
                    onAddFriend = { nav.navigate(Routes.ADD_FRIEND) { launchSingleTop = true } },
                    onRequests = { nav.navigate(Routes.FRIEND_REQUESTS) { launchSingleTop = true } },
                    onChangeNickname = { nav.navigate(Routes.NICKNAME) { launchSingleTop = true } },
                    message = message,
                    highlightTop = highlight,
                    onMessageShown = {
                        entry.savedStateHandle[FRIENDS_MESSAGE] = null
                        entry.savedStateHandle[FRIENDS_HIGHLIGHT] = false
                    },
                    onRoom = if (Features.ROOMS) { _ -> } else null,
                    onCreateRoom = if (Features.ROOMS) { { nav.navigate(Routes.ROOM_CREATE) { launchSingleTop = true } } } else null,
                )
            }
            if (Features.ROOMS) {
                composable(Routes.ROOM_CREATE) {
                    RoomCreateScreen(
                        onBack = { nav.popBackStack() },
                        onCreated = { nav.popBackStack() },
                        onAddFriend = { nav.navigate(Routes.ADD_FRIEND) { popUpTo(Routes.FRIENDS); launchSingleTop = true } },
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
                    message = message,
                    onMessageShown = { entry.savedStateHandle[SETTINGS_MESSAGE] = null },
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
