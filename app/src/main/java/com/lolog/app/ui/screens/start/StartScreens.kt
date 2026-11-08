package com.lolog.app.ui.screens.start

import com.lolog.app.BuildConfig
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lolog.app.data.model.ErrorCodes
import com.lolog.app.data.model.SocialProvider
import com.lolog.app.data.model.PASSWORD_MAX
import com.lolog.app.data.model.PASSWORD_MIN
import com.lolog.app.data.model.isValidPassword
import com.lolog.app.data.repo.LologException
import com.lolog.app.data.repo.LologRepository
import com.lolog.app.ui.components.*
import com.lolog.app.ui.navigation.LocalContainer
import com.lolog.app.ui.theme.LologColors
import com.lolog.app.ui.theme.LologShape
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ── 01 스플래시: SplashScreen.kt (3D 얼굴이 떠오르는 첫 화면) ──

/** 예전 첫 화면의 파란 말풍선. 지금은 안 쓰지만 앱 아이콘 시안용으로 남겨 둬요 */
@Composable
fun BubbleMark(size: Int = 84) {
    Box(
        Modifier.size(size.dp)
            .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp, bottomEnd = 26.dp, bottomStart = 8.dp))
            .background(LologColors.Accent),
        contentAlignment = Alignment.Center,
    ) { TypingDots(color = LologColors.Paper, dot = 10.dp) }
}

// ── 02 로그인 · 25 이메일 회원가입 ─────────────────────
// 사진: mockups/이메일-로그인-시안.png, 로그인-세부-시안.png


class AuthViewModel(private val repo: LologRepository) : ViewModel() {
    data class State(
        val loading: Boolean = false,
        val emailError: String? = null,      // 이메일 칸 아래 빨간 줄 (예: 이미 가입된 이메일)
        val passwordError: String? = null,   // 비밀번호 칸 아래 빨간 줄 (예: 이메일이나 비밀번호가 맞지 않아요)
        val snackbar: String? = null,        // 아래 검은 띠 (인터넷 끊김·서버 오류)
        val accountExists: SocialProvider? = null, // 소셜: 같은 이메일로 이미 가입됨
        val done: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    fun login(email: String, password: String) = submit(null) { repo.login(email, password) }

    fun signup(email: String, password: String) {
        // 화면에서 규칙을 다 맞춰야 버튼이 켜지지만, 한 번 더 확인 (FR-AUTH-01)
        if (!isEmail(email) || !isValidPassword(password)) return
        submit(null, isSignup = true) { repo.signup(email, password) }
    }

    /** TODO(소셜): 카카오·구글·네이버 SDK 키가 들어오면 켜요. 지금은 버튼이 "준비 중"이라 불리지 않아요 */
    fun social(provider: SocialProvider) = submit(provider) { repo.socialLogin(provider, "mock-${provider.name.lowercase()}-token") }

    /** 칸을 다시 고치면 그 칸 아래 오류는 지워요 */
    fun clearEmailError() = _state.update { it.copy(emailError = null) }
    fun clearPasswordError() = _state.update { it.copy(passwordError = null) }
    fun snackbarShown() = _state.update { it.copy(snackbar = null) }
    fun dismiss() = _state.update { it.copy(accountExists = null) }

    private fun submit(provider: SocialProvider?, isSignup: Boolean = false, block: suspend () -> Unit) {
        if (_state.value.loading) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, emailError = null, passwordError = null, snackbar = null) }
            try {
                block()
                _state.update { it.copy(loading = false, done = true) }
            } catch (e: Throwable) {
                val code = (e as? LologException)?.code
                _state.update {
                    val s = it.copy(loading = false)
                    when {
                        code == ErrorCodes.ACCOUNT_EXISTS && provider != null -> s.copy(accountExists = provider)
                        code == ErrorCodes.ACCOUNT_EXISTS -> s.copy(emailError = "이미 가입된 계정이에요. 처음 가입한 방법으로 로그인해 주세요")
                        code == ErrorCodes.INVALID_CREDENTIALS -> s.copy(passwordError = "이메일 또는 비밀번호가 맞지 않아요")
                        (code == ErrorCodes.INVALID_REQUEST || code == ErrorCodes.VALIDATION_FAILED) && (e as LologException).field == "email" ->
                            s.copy(emailError = "이메일 형식을 확인해 주세요")
                        (code == ErrorCodes.INVALID_REQUEST || code == ErrorCodes.VALIDATION_FAILED) && (e as LologException).field == "password" ->
                            s.copy(passwordError = "비밀번호 규칙을 확인해 주세요")
                        else -> s.copy(snackbar = errorText(e))
                    }
                }
            }
        }
    }
}

fun isEmail(s: String) = android.util.Patterns.EMAIL_ADDRESS.matcher(s).matches()

@Composable
private fun authViewModel(): AuthViewModel {
    val repo = LocalContainer.current.repository
    return viewModel { AuthViewModel(repo) }
}

@Composable
fun LoginScreen(onLoggedIn: () -> Unit, onSignup: () -> Unit) {
    val vm = authViewModel()
    val state by vm.state.collectAsState()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var emailTouched by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.done) { if (state.done) onLoggedIn() }
    LaunchedEffect(state.snackbar) { state.snackbar?.let { snackbar.showSnackbar(it); vm.snackbarShown() } }

    // ① 입력 오류: 이메일 형식이 틀리면 칸 아래 빨간 안내. 둘 다 채워야 버튼이 켜져요
    val emailOk = isEmail(email.trim())
    val emailFormatError = if (emailTouched && email.isNotBlank() && !emailOk) "이메일 형식을 확인해 주세요" else null

    Scaffold(containerColor = Color.Transparent, snackbarHost = { LologSnackbarHost(snackbar) }, modifier = Modifier.noteLines()) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding()
                .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            Text("LOLog", fontSize = 34.sp, fontWeight = FontWeight.Black, letterSpacing = (-1.5).sp, color = LologColors.Accent)
            Text(
                buildAnnotatedString {
                    append("오늘 나눈 ")
                    withStyle(SpanStyle(background = LologColors.Highlight)) { append("웃긴 얘기") }
                    append(",\n카드로 모아 둘게요")
                },
                fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 28.sp,
            )
            Spacer(Modifier.height(14.dp))
            // 소셜: 카카오 → 구글 → 네이버. 앱 키가 들어오기 전엔 "준비 중"으로 눌리지 않아요
            SocialButtons(ready = false, enabled = !state.loading, onClick = vm::social)
            OrDivider("또는 이메일로")
            LabeledField(
                "이메일", email,
                { email = it; emailTouched = false; vm.clearEmailError() },
                icon = Icons.Outlined.MailOutline, placeholder = "이메일", type = KeyboardType.Email,
                error = state.emailError ?: emailFormatError, enabled = !state.loading,
                onFocusLost = { emailTouched = true },
            )
            LabeledField(
                "비밀번호", password,
                { password = it.take(PASSWORD_MAX); vm.clearPasswordError() },
                icon = Icons.Outlined.Key, placeholder = "비밀번호", type = KeyboardType.Password, secret = true,
                error = state.passwordError, enabled = !state.loading,
            )
            Spacer(Modifier.height(4.dp))
            // ② 로딩: 버튼에 점, 그동안 입력칸 잠김
            PrimaryButton(
                "이메일로 로그인",
                { emailTouched = true; if (emailOk) vm.login(email.trim(), password) },
                enabled = emailOk && password.isNotEmpty(), loading = state.loading,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Text("회원가입", fontSize = 13.sp, color = LologColors.Muted, modifier = Modifier.clickable(enabled = !state.loading, onClick = onSignup).padding(8.dp))
                Text("·", fontSize = 13.sp, color = LologColors.Muted, modifier = Modifier.padding(vertical = 8.dp))
                // 비밀번호 찾기는 API에 없어 자리만 둬요
                Text("비밀번호 찾기", fontSize = 13.sp, color = LologColors.Muted, modifier = Modifier.padding(8.dp))
            }
        }
    }

    state.accountExists?.let { provider ->
        LologDialog(
            title = "이미 가입된 계정이에요",
            body = "이 ${provider.label} 계정의 이메일로 가입한 계정이 있어요. 처음 가입한 방법으로 로그인해 주세요.",
            confirmText = "확인",
            dismissText = "닫기",
            onConfirm = vm::dismiss,
            onDismiss = vm::dismiss,
        )
    }
}

@Composable
fun SignupScreen(onBack: () -> Unit, onDone: () -> Unit) {
    val vm = authViewModel()
    val state by vm.state.collectAsState()
    var email by remember { mutableStateOf("") }
    var emailTouched by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.done) { if (state.done) onDone() }
    LaunchedEffect(state.snackbar) { state.snackbar?.let { snackbar.showSnackbar(it); vm.snackbarShown() } }

    // ④ 규칙은 입력하는 동안 바로 보여 주고, 다 맞아야 가입 버튼이 켜져요 (FR-AUTH-01)
    val emailOk = isEmail(email.trim())
    val lengthOk = password.length in PASSWORD_MIN..PASSWORD_MAX
    val mixOk = password.any { it.isLetter() } && password.any { it.isDigit() }
    val confirmOk = confirm.isNotEmpty() && confirm == password
    val allOk = emailOk && lengthOk && mixOk && confirmOk
    val emailFormatError = if (emailTouched && email.isNotBlank() && !emailOk) "이메일 형식을 확인해 주세요" else null

    Scaffold(
        containerColor = Color.Transparent,
        topBar = { LologTopBar("이메일로 가입", onBack) },
        snackbarHost = { LologSnackbarHost(snackbar) },
        modifier = Modifier.noteLines(),
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding().padding(horizontal = 24.dp)) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LabeledField(
                    "이메일", email, { email = it; emailTouched = false; vm.clearEmailError() },
                    icon = Icons.Outlined.MailOutline, placeholder = "이메일", type = KeyboardType.Email,
                    error = state.emailError ?: emailFormatError, enabled = !state.loading,
                    onFocusLost = { emailTouched = true },
                )
                LabeledField(
                    "비밀번호", password, { password = it.take(PASSWORD_MAX) },
                    icon = Icons.Outlined.Key, placeholder = "비밀번호", type = KeyboardType.Password, secret = true,
                    enabled = !state.loading, counter = if (password.isNotEmpty()) "${password.length} / $PASSWORD_MAX" else null,
                )
                RuleLine(if (password.isEmpty() || lengthOk) "8자 이상" else "8자 이상 (지금 ${password.length}자)", lengthOk, password.isNotEmpty())
                RuleLine("영문과 숫자 모두 포함", mixOk, password.isNotEmpty())
                Spacer(Modifier.height(2.dp))
                LabeledField(
                    "비밀번호 확인", confirm, { confirm = it.take(PASSWORD_MAX) },
                    icon = Icons.Outlined.Key, placeholder = "한 번 더 입력해 주세요", type = KeyboardType.Password, secret = true,
                    error = if (confirm.isNotEmpty() && !confirmOk) "비밀번호가 서로 달라요" else null,
                    enabled = !state.loading,
                )
            }
            Text(
                buildAnnotatedString {
                    append("가입하면 ")
                    withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { append("이용약관") }
                    append("과 ")
                    withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { append("개인정보 처리방침") }
                    append("에 동의하는 것으로 봐요.")
                },
                fontSize = 12.sp, color = LologColors.Muted, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            )
            PrimaryButton("가입하기", { vm.signup(email.trim(), password) }, enabled = allOk, loading = state.loading)
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** 비밀번호 규칙 한 줄: 맞으면 파란 체크, 틀리면 빨간 ✕, 아직 입력 전이면 회색 */
@Composable
private fun RuleLine(text: String, ok: Boolean, touched: Boolean) {
    val color = when { !touched -> LologColors.Muted; ok -> LologColors.Accent; else -> LologColors.Rec }
    val icon = when { !touched -> Icons.Outlined.RadioButtonUnchecked; ok -> Icons.Outlined.Check; else -> Icons.Outlined.Close }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 12.sp, color = color)
    }
}

/** 카카오 → 구글 → 네이버. ready=false면 옅게 + "준비 중" 표시 + 눌리지 않음 */
@Composable
private fun SocialButtons(ready: Boolean, enabled: Boolean, onClick: (SocialProvider) -> Unit) {
    SocialButton("카카오로 시작하기", "●", LologColors.Kakao, LologColors.KakaoText, ready, enabled) { onClick(SocialProvider.KAKAO) }
    SocialButton("Google로 시작하기", "G", LologColors.Paper, LologColors.Ink, ready, enabled, border = true) { onClick(SocialProvider.GOOGLE) }
    SocialButton("네이버로 시작하기", "N", LologColors.Naver, Color.White, ready, enabled) { onClick(SocialProvider.NAVER) }
}

@Composable
private fun SocialButton(text: String, logo: String, bg: Color, fg: Color, ready: Boolean, enabled: Boolean, border: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(50.dp)
            .alpha(if (ready) 1f else 0.55f)
            .clip(LologShape.md)
            .background(bg)
            .then(if (border) Modifier.border(1.5.dp, LologColors.Line, LologShape.md) else Modifier)
            .clickable(enabled = ready && enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(logo, color = fg, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, modifier = Modifier.align(Alignment.CenterStart).padding(start = 18.dp))
        Text(text, color = fg, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        if (!ready) {
            Text(
                "준비 중", fontSize = 10.sp, color = fg,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 14.dp)
                    .clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.08f))
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun OrDivider(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
        HorizontalDivider(Modifier.weight(1f), color = LologColors.Line)
        Text(text, fontSize = 12.sp, color = LologColors.Muted, modifier = Modifier.padding(horizontal = 10.dp))
        HorizontalDivider(Modifier.weight(1f), color = LologColors.Line)
    }
}

/** 이름표 + 아이콘 입력칸. error가 있으면 빨간 테두리 + 아래 빨간 안내 */
@Composable
fun LabeledField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    icon: ImageVector? = null,
    placeholder: String = "",
    type: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
    error: String? = null,
    enabled: Boolean = true,
    counter: String? = null,
    onFocusLost: () -> Unit = {},
) {
    var hadFocus by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = LologColors.Muted)
        OutlinedTextField(
            value = value, onValueChange = onChange, singleLine = true, enabled = enabled,
            isError = error != null,
            placeholder = { Text(placeholder, fontSize = 14.sp, color = LologColors.Muted) },
            leadingIcon = if (icon != null) { { Icon(icon, null, tint = LologColors.Muted, modifier = Modifier.size(18.dp)) } } else null,
            trailingIcon = if (counter != null) { { Text(counter, fontSize = 11.sp, color = LologColors.Muted, modifier = Modifier.padding(end = 12.dp)) } } else null,
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = type),
            shape = LologShape.sm,
            modifier = Modifier.fillMaxWidth().onFocusChanged { f ->
                if (f.isFocused) hadFocus = true else if (hadFocus) onFocusLost()
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = LologColors.Accent, unfocusedBorderColor = LologColors.Line,
                errorBorderColor = LologColors.Rec, disabledBorderColor = LologColors.Line,
                focusedContainerColor = LologColors.Paper, unfocusedContainerColor = LologColors.Paper,
                errorContainerColor = LologColors.Paper, disabledContainerColor = LologColors.Paper,
            ),
        )
        if (error != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Info, null, tint = LologColors.Rec, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(4.dp))
                Text(error, fontSize = 12.sp, color = LologColors.Rec)
            }
        }
    }
}

// ── 03 권한 요청 ──────────────────────────────────────

@Composable
fun PermissionScreen(onDone: () -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()

    fun finish() {
        // 허용 후 푸시 토큰 등록 (PUT /api/devices/fcm-token)
        // TODO(FCM): FirebaseMessaging.getInstance().token 으로 받은 값을 넣기. 그 전에는 목업에서만 가짜 토큰을 등록해요.
        //  실제 서버에 가짜 토큰을 넣으면 서버가 FCM에 보낼 때마다 실패해요. 푸시가 없는 동안 앱은 5초 조회로 상태를 알아요.
        if (BuildConfig.USE_MOCK) {
            scope.launch { runCatching { container.repository.registerFcmToken("mock-fcm-token") } }
        }
        onDone()
    }

    val notiLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { finish() }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // 마이크 다음에 알림을 물어요. 알림은 거부해도 진행
        if (Build.VERSION.SDK_INT >= 33) notiLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else finish()
    }

    Column(
        Modifier.fillMaxSize().noteLines().systemBarsPadding().padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("시작하기 전에", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
        Text("두 가지만 허락해 주세요", fontSize = 16.sp, color = LologColors.Muted)
        Spacer(Modifier.height(8.dp))
        PermissionItem(Icons.Outlined.Mic, LologColors.RecSoft, "마이크", "필수", "대화를 녹음하면서 폰 안에서 바로 글자로 바꿔요. 음성 파일은 폰 밖으로 나가지 않아요.")
        PermissionItem(Icons.Outlined.Notifications, LologColors.AccentSoft, "알림", "선택", "새 이야기 카드가 생기거나 4컷만화가 완성되면 알려 드려요.")
        PrivacyNote("대화 텍스트는 외부 AI 서비스로 보내지 않고 LOLog 서버에서만 분석해요.")
        Spacer(Modifier.weight(1f))
        PrimaryButton("허용하고 시작하기", { micLauncher.launch(Manifest.permission.RECORD_AUDIO) })
    }
}

@Composable
private fun PermissionItem(icon: ImageVector, tint: Color, title: String, tag: String, body: String) {
    Row(Modifier.fillMaxWidth().softCard().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(tint), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = LologColors.Ink)
        }
        Column {
            Text(tag, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (tag == "필수") LologColors.Rec else LologColors.Muted)
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(body, fontSize = 13.sp, color = LologColors.Muted, lineHeight = 19.sp)
        }
    }
}

@Composable
fun PrivacyNote(text: String) {
    Row(
        Modifier.fillMaxWidth().clip(LologShape.sm).background(LologColors.Paper)
            .border(1.dp, LologColors.Line, LologShape.sm).padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Outlined.Shield, null, tint = LologColors.Muted, modifier = Modifier.size(16.dp))
        Text(text, fontSize = 12.sp, color = LologColors.Muted, textAlign = TextAlign.Start)
    }
}
