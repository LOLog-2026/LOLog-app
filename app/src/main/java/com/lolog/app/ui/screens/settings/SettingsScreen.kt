package com.lolog.app.ui.screens.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.border
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lolog.app.data.model.ErrorCodes
import com.lolog.app.data.model.Account
import com.lolog.app.data.model.SocialProvider
import com.lolog.app.data.repo.LologException
import com.lolog.app.data.repo.LologRepository
import com.lolog.app.recording.RecordingController
import com.lolog.app.recording.RecordingState
import com.lolog.app.ui.components.*
import com.lolog.app.ui.navigation.LocalContainer
import com.lolog.app.ui.social.SocialSignIn
import com.lolog.app.ui.social.rememberSocialSignIn
import com.lolog.app.ui.social.socialErrorText
import com.lolog.app.ui.theme.LologColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val repo: LologRepository,
    private val recorder: RecordingController,
) : ViewModel() {
    data class State(
        val me: Account? = null,
        val nickname: String? = null,   // 친구에게 보여질 별명. 없으면 "정하기"
        val blockedCount: Int? = null,
        val linking: SocialProvider? = null,
        val busy: Boolean = false,
        val loggedOut: Boolean = false,
        val toast: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /** GET /auth/me → email, linkedProviders[] */
    fun load() = viewModelScope.launch {
        launch { runCatching { repo.me() }.onSuccess { me -> _state.update { it.copy(me = me) } } }
        // 친구 기능: 닉네임과 차단한 사람 수 (GET /api/me/profile, GET /api/blocks)
        launch { runCatching { repo.profile() }.onSuccess { p -> _state.update { it.copy(nickname = p.nickname) } } }
        launch { runCatching { repo.blocks().size }.onSuccess { n -> _state.update { it.copy(blockedCount = n) } } }
    }

    /**
     * 그 회사 SDK로 토큰을 받아 POST /auth/me/social/{provider} (FR-AUTH-06). 409 SOCIAL_ALREADY_LINKED면 토스트(19번).
     * 사용자가 SDK 창을 닫으면 조용히 원래대로 돌아가요
     */
    fun link(provider: SocialProvider, signIn: SocialSignIn) {
        if (_state.value.linking != null) return
        viewModelScope.launch {
            _state.update { it.copy(linking = provider) }
            try {
                val token = signIn.token(provider)
                if (token == null) {
                    _state.update { it.copy(linking = null) }
                    return@launch
                }
                val linked = repo.linkSocial(provider, token)
                _state.update { s -> s.copy(linking = null, me = s.me?.copy(linkedProviders = linked), toast = "${provider.label} 계정을 연결했어요") }
            } catch (e: LologException) {
                val msg = socialErrorText(e)
                _state.update { it.copy(linking = null, toast = msg) }
            }
        }
    }

    /** POST /auth/logout + DELETE /api/devices/fcm-token */
    fun logout() = viewModelScope.launch {
        _state.update { it.copy(busy = true) }
        // 녹음 중이면 마감해 글을 올리고, 올릴 수 있는 음성도 올린 뒤 로그아웃해요(FR-AUTH-04, audio.md "로그아웃할 때").
        // 그래도 못 올린 글·음성은 다음에 로그인한 계정으로 올라가지 않게 토큰을 지우기 전에 녹음 서비스가 지워요
        recorder.prepareLogout()
        repo.logout()
        _state.update { it.copy(busy = false, loggedOut = true) }
    }

    /** DELETE /auth/me — 회원 정보 소프트 삭제, 서버가 카드·만화를 모두 지움 */
    fun withdraw() = viewModelScope.launch {
        _state.update { it.copy(busy = true) }
        try {
            repo.withdraw()
            // 진행 중인 녹음과 폰에 남은 글·음성은 올리지 않고 버려요(FR-AUTH-05, audio.md 3장)
            recorder.discardAll()
            _state.update { it.copy(busy = false, loggedOut = true) }
        }
        catch (e: LologException) { _state.update { it.copy(busy = false, toast = errorText(e)) } }
    }

    fun toastShown() = _state.update { it.copy(toast = null) }
}

// ── 16 설정 · 19 소셜 연결 실패 · 23 탈퇴 ──────────────

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onLoggedOut: () -> Unit,
    onChangePassword: () -> Unit,
    onNickname: () -> Unit,
    onBlocked: () -> Unit,
    message: String? = null,
    onMessageShown: () -> Unit = {},
) {
    val container = LocalContainer.current
    val vm = viewModel { SettingsViewModel(container.repository, container.recorder) }
    val state by vm.state.collectAsState()
    val signIn = rememberSocialSignIn()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var askLogout by remember { mutableStateOf(false) }
    val recorderState by container.recorder.state.collectAsState()
    val recording = recorderState as? RecordingState.Recording
    // 녹음 중이 아닐 때 폰에 못 올린 글·음성이 남았는지. 로그아웃 창을 열 때 확인해요
    var unsent by remember { mutableStateOf(false) }
    LaunchedEffect(askLogout) { if (askLogout) unsent = container.recorder.hasUnsent() }
    var askWithdraw by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.load() }
    LaunchedEffect(state.loggedOut) { if (state.loggedOut) onLoggedOut() }
    LaunchedEffect(state.toast) { state.toast?.let { snackbar.showSnackbar(it); vm.toastShown() } }
    // 비밀번호 바꾸기에서 돌아오면 아래 띠로 결과를 알려요. 값을 비우면 이 효과가 다시 돌아 띠가 끊기므로 띠는 화면 범위에서 띄워요
    val scope = rememberCoroutineScope()
    LaunchedEffect(message) { message?.let { onMessageShown(); scope.launch { snackbar.showSnackbar(it) } } }

    Scaffold(
        containerColor = LologColors.Paper,
        snackbarHost = { LologSnackbarHost(snackbar) },
        topBar = { LologTopBar("설정", onBack = onBack) },
    ) { pad ->
        Column(
            Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            // 맨 위 Canvas 상자: 연노랑 동그라미 안 편지 아이콘 + 이메일
            val email = state.me?.email.orEmpty()
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(LologColors.Canvas).padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(48.dp).clip(CircleShape).background(LologColors.AccentSoft), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.MailOutline, null, tint = LologColors.Laugh, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(14.dp))
                // 가입 방법은 /auth/me에 없어서 이메일만 보여 줘요
                Text(email.ifBlank { "불러오는 중…" }, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(22.dp))
            Text("연결된 계정", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = LologColors.Muted)
            Spacer(Modifier.height(4.dp))
            SocialProvider.entries.forEach { p ->
                val linked = state.me?.linkedProviders?.contains(p) == true
                val canLink = !linked && state.linking == null && signIn.isReady(p)
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = canLink) { vm.link(p, signIn) }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ProviderLogo(p)
                    Spacer(Modifier.width(14.dp))
                    Text(p.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    when {
                        linked -> Text("연결됨", fontSize = 13.sp, color = LologColors.Muted)
                        state.linking == p -> TypingDots(dot = 6.dp)
                        // 그 회사 키가 local.properties에 없으면 로그인 화면처럼 "준비 중"으로 두고 눌리지 않게 해요(목업은 늘 켜짐)
                        !signIn.isReady(p) -> Text("준비 중", fontSize = 13.sp, color = LologColors.Muted)
                        else -> Text("연결하기", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = LologColors.Accent)
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 10.dp), thickness = 1.dp, color = LologColors.Line)
            // 비밀번호가 있는 계정만 보여 줘요. 소셜로만 가입했으면 줄이 아예 없어요(FR-AUTH-07, 시안 12)
            if (state.me?.hasPassword == true) {
                SettingsRow("비밀번호 바꾸기", onClick = onChangePassword)
            }
            // 친구 기능 두 줄은 소셜 계정에도 보여요
            SettingsRow("닉네임 바꾸기", trailing = state.nickname ?: "정하기", onClick = onNickname)
            SettingsRow("차단한 사람", trailing = state.blockedCount?.let { "${it}명" }, onClick = onBlocked)
            // 알림 스위치는 API에 없어 안드로이드 알림 설정으로 보내요 (협의 2-5)
            SettingsRow("알림 설정", trailing = "휴대폰 설정 ›") {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            SettingsRow("로그아웃") { askLogout = true }
            SettingsRow("탈퇴하고 모든 카드 지우기", danger = true) { askWithdraw = true }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (askLogout) {
        LologDialog(
            title = "로그아웃할까요?",
            // 녹음 중·남은 녹음 문구는 audio.md "로그아웃할 때" 표 그대로
            body = when {
                recording?.audioSaved == true -> "녹음 중이에요. 로그아웃하면 녹음이 멈춰요. 글은 올리고, 올리지 못한 음성은 지워져요."
                recording != null -> "녹음 중이에요. 로그아웃하면 녹음이 멈추고, 지금까지 녹음한 내용은 올린 뒤 로그아웃돼요"
                unsent -> "아직 올리지 못한 녹음이 있어요. 로그아웃하면 지워져요."
                else -> "이 폰에서는 알림이 오지 않아요. 카드와 만화는 그대로 남아 있어요."
            },
            confirmText = "로그아웃",
            loading = state.busy,
            onConfirm = { vm.logout() },
            onDismiss = { askLogout = false },
        )
    }
    if (askWithdraw) {
        LologDialog(
            title = "탈퇴하고 모든 카드를 지울까요?",
            body = "모은 이야기 카드와 4컷만화가 모두 지워지고 되돌릴 수 없어요. 연결된 소셜 계정도 함께 끊겨요.",
            confirmText = "탈퇴하기",
            danger = true,
            loading = state.busy,
            onConfirm = { vm.withdraw() },
            onDismiss = { askWithdraw = false },
        )
    }
}

/** 28dp 둥근 네모 아이콘 */
@Composable
private fun ProviderLogo(p: SocialProvider) {
    val shape = RoundedCornerShape(8.dp)
    when (p) {
        SocialProvider.GOOGLE -> Box(
            Modifier.size(28.dp).clip(shape).background(LologColors.Paper).border(1.dp, LologColors.Line, shape),
            contentAlignment = Alignment.Center,
        ) { Text("G", fontSize = 13.sp, fontWeight = FontWeight.Black, color = LologColors.Ink) }
        SocialProvider.NAVER -> Box(Modifier.size(28.dp).clip(shape).background(LologColors.Naver), contentAlignment = Alignment.Center) {
            Text("N", fontSize = 13.sp, fontWeight = FontWeight.Black, color = Color.White)
        }
    }
}

/** 글자만 있는 줄. [trailing]이 있으면 오른쪽에 회색 글자 */
@Composable
private fun SettingsRow(title: String, trailing: String? = null, danger: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            color = if (danger) LologColors.Accent else LologColors.Ink, modifier = Modifier.weight(1f),
        )
        trailing?.let { Text(it, fontSize = 13.sp, color = LologColors.Muted) }
    }
}
