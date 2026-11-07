package com.lolog.app.ui.screens.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Notifications
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
import com.lolog.app.ui.components.*
import com.lolog.app.ui.navigation.LocalContainer
import com.lolog.app.ui.theme.LologColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SettingsViewModel(private val repo: LologRepository) : ViewModel() {
    data class State(
        val me: Account? = null,
        val linking: SocialProvider? = null,
        val busy: Boolean = false,
        val loggedOut: Boolean = false,
        val toast: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /** GET /auth/me → email, linkedProviders[] */
    fun load() = viewModelScope.launch {
        runCatching { repo.me() }.onSuccess { me -> _state.update { it.copy(me = me) } }
    }

    /** POST /auth/me/social/{provider}. 409 SOCIAL_ALREADY_LINKED면 토스트(19번) */
    fun link(provider: SocialProvider) = viewModelScope.launch {
        _state.update { it.copy(linking = provider) }
        try {
            // TODO: 소셜 SDK 로그인으로 받은 토큰 넣기
            val linked = repo.linkSocial(provider, "mock-${provider.name.lowercase()}-token")
            _state.update { s -> s.copy(linking = null, me = s.me?.copy(linkedProviders = linked), toast = "${provider.label} 계정을 연결했어요") }
        } catch (e: LologException) {
            val msg = if (e.code == ErrorCodes.SOCIAL_ALREADY_LINKED) "이미 다른 LOLog 계정에 연결된 ${provider.label} 계정이에요" else e.message
            _state.update { it.copy(linking = null, toast = msg) }
        }
    }

    /** POST /auth/logout + DELETE /api/devices/fcm-token */
    fun logout() = viewModelScope.launch {
        _state.update { it.copy(busy = true) }
        repo.logout()
        _state.update { it.copy(busy = false, loggedOut = true) }
    }

    /** DELETE /auth/me — 회원 정보 소프트 삭제, 서버가 카드·만화를 모두 지움 */
    fun withdraw() = viewModelScope.launch {
        _state.update { it.copy(busy = true) }
        try { repo.withdraw(); _state.update { it.copy(busy = false, loggedOut = true) } }
        catch (e: LologException) { _state.update { it.copy(busy = false, toast = e.message) } }
    }

    fun toastShown() = _state.update { it.copy(toast = null) }
}

// ── 16 설정 · 19 소셜 연결 실패 · 23 탈퇴 ──────────────

@Composable
fun SettingsScreen(onBack: () -> Unit, onLoggedOut: () -> Unit) {
    val container = LocalContainer.current
    val vm = viewModel { SettingsViewModel(container.repository) }
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var askLogout by remember { mutableStateOf(false) }
    var askWithdraw by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.load() }
    LaunchedEffect(state.loggedOut) { if (state.loggedOut) onLoggedOut() }
    LaunchedEffect(state.toast) { state.toast?.let { snackbar.showSnackbar(it); vm.toastShown() } }

    Scaffold(
        modifier = Modifier.noteLines(),
        containerColor = Color.Transparent,
        snackbarHost = { LologSnackbarHost(snackbar) },
        topBar = { LologTopBar("설정", onBack = onBack) },
    ) { pad ->
        Column(
            Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 프로필
            val email = state.me?.email.orEmpty()
            Row(Modifier.fillMaxWidth().softCard().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(CircleShape).background(LologColors.Laugh), contentAlignment = Alignment.Center) {
                    Text(email.take(1).uppercase().ifBlank { "?" }, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(email.ifBlank { "불러오는 중…" }, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    // 가입 방법은 /auth/me에 없어서 연결된 소셜 계정으로 짐작해요
                    Text(if (state.me?.linkedProviders.isNullOrEmpty()) "이메일로 가입" else "소셜 계정 연결됨", fontSize = 12.sp, color = LologColors.Muted)
                }
            }

            SectionTitle("연결된 계정")
            Column(Modifier.fillMaxWidth().softCard()) {
                SocialProvider.entries.forEachIndexed { i, p ->
                    if (i > 0) HorizontalDivider(color = LologColors.Line)
                    val linked = state.me?.linkedProviders?.contains(p) == true
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        ProviderLogo(p)
                        Spacer(Modifier.width(12.dp))
                        Text(p.label, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        when {
                            linked -> Text("연결됨", fontSize = 13.sp, color = LologColors.Muted)
                            state.linking == p -> TypingDots(dot = 6.dp)
                            else -> SmallPillButton("연결하기", { vm.link(p) })
                        }
                    }
                }
            }

            SectionTitle("알림")
            Column(Modifier.fillMaxWidth().softCard()) {
                // 알림 스위치는 API에 없어 안드로이드 알림 설정으로 보내요 (협의 2-5)
                SettingsRow(Icons.Outlined.Notifications, "알림 설정", "휴대폰 설정에서 켜고 끌 수 있어요") {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }

            SectionTitle("개인정보")
            Column(Modifier.fillMaxWidth().softCard()) {
                SettingsRow(Icons.AutoMirrored.Outlined.Logout, "로그아웃") { askLogout = true }
                HorizontalDivider(color = LologColors.Line)
                SettingsRow(Icons.Outlined.DeleteOutline, "탈퇴하고 모든 카드 지우기", danger = true) { askWithdraw = true }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (askLogout) {
        LologDialog(
            title = "로그아웃할까요?",
            body = "이 폰에서는 알림이 오지 않아요. 카드와 만화는 그대로 남아 있어요.",
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

@Composable
private fun ProviderLogo(p: SocialProvider) {
    val (bg, fg, mark) = when (p) {
        SocialProvider.KAKAO -> Triple(LologColors.Kakao, LologColors.KakaoText, "●")
        SocialProvider.GOOGLE -> Triple(LologColors.Canvas, LologColors.Ink, "G")
        SocialProvider.NAVER -> Triple(LologColors.Naver, Color.White, "N")
    }
    Box(Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(bg), contentAlignment = Alignment.Center) {
        Text(mark, fontSize = 11.sp, fontWeight = FontWeight.Black, color = fg)
    }
}

@Composable
private fun SettingsRow(icon: ImageVector, title: String, sub: String? = null, danger: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (danger) LologColors.Rec else LologColors.Muted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = if (danger) LologColors.Rec else LologColors.Ink)
            sub?.let { Text(it, fontSize = 12.sp, color = LologColors.Muted) }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = LologColors.Muted)
    }
}
