package com.lolog.app.ui.components

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lolog.app.data.model.COMIC_LIMIT_PER_CARD
import com.lolog.app.data.model.ErrorCodes
import com.lolog.app.data.model.RoomErrorCodes
import com.lolog.app.data.model.RoomRules
import com.lolog.app.data.model.VIDEO_LIMIT_PER_CARD
import com.lolog.app.data.repo.LologException
import com.lolog.app.ui.theme.LologColors

/*
 * 공용 부품 시안(mockups/공용-부품-시안.png)
 *  ① 처음 열 때 로딩: 회색 카드 뼈대(SkeletonList)
 *  ② 버튼 로딩: 글자 대신 점 세 개(PrimaryButton loading)
 *  ③ 가벼운 실패: 아래 스낵바 + "다시 시도"
 *  ④ 목록 못 불러옴: 가운데 안내 + 다시 시도(ListErrorState)
 *  ⑤⑥ 빈 화면: 아이콘 + 제목 + 설명 + 손글씨 한 줄 + 버튼(EmptyState)
 *  ⑦⑧ 오프라인: 위쪽 옅은 회색 띠(OfflineBar)
 */

// ── 오류 문장 표 (공용) ──────────────────────────────

/** 서버 오류 이름 → 사용자에게 보여 줄 문장(로그인 사진의 오류 문장 표). 모르는 이름이면 서버 message, 그것도 없으면 "문제가 생겼어요…" */
fun errorText(e: Throwable): String {
    val ex = e as? LologException ?: return "잠시 후 다시 시도해 주세요"
    return when (ex.code) {
        ErrorCodes.COMIC_LIMIT -> "이 카드는 ${COMIC_LIMIT_PER_CARD}번 다 그렸어요"
        ErrorCodes.ACCOUNT_EXISTS -> "이미 가입된 계정이에요. 처음 가입한 방법으로 로그인해 주세요"
        // 계정 합치기가 없어서 방법(탈퇴 뒤 연결)까지 알려요(requirements FR-AUTH-06, 11-13)
        ErrorCodes.SOCIAL_ALREADY_LINKED -> "이미 다른 LOLog 계정에 연결된 소셜 계정이에요. 그 계정을 탈퇴한 뒤 연결할 수 있어요"
        ErrorCodes.CARD_NOT_FOUND, ErrorCodes.COMIC_NOT_FOUND -> "지워졌거나 찾을 수 없어요"
        ErrorCodes.INVALID_CREDENTIALS -> "이메일 또는 비밀번호가 맞지 않아요"
        ErrorCodes.ACCOUNT_SUSPENDED -> "정지된 계정이에요."
        ErrorCodes.ACCOUNT_BANNED -> "이 계정으로는 가입하거나 연결할 수 없어요"
        // functional-spec FR-AUTH-06 예외 문장 그대로
        ErrorCodes.LAST_LOGIN_METHOD -> "다른 로그인 방법을 먼저 연결해 주세요"
        ErrorCodes.FORBIDDEN -> "관리자만 할 수 있어요"
        ErrorCodes.INVALID_REQUEST, ErrorCodes.VALIDATION_FAILED -> "입력한 내용을 확인해 주세요"
        ErrorCodes.UNKNOWN_SPEAKER -> "화자를 다시 골라 주세요"
        ErrorCodes.RECORDING_NOT_FOUND -> "지워졌거나 찾을 수 없어요"
        ErrorCodes.INVALID_TOKEN, ErrorCodes.UNAUTHORIZED -> "다시 로그인해 주세요"
        ErrorCodes.INTERNAL_ERROR -> "잠시 후 다시 시도해 주세요"
        // 서버는 만화를 그리는 중과 쇼츠 목소리를 만드는 중 둘 다 이 코드라 둘 다 맞는 문구로(앱이 정함)
        ErrorCodes.CARD_LOCKED -> "이 카드로 만화나 쇼츠를 만드는 중이에요"
        ErrorCodes.COMIC_IN_PROGRESS -> "지금 그리는 만화가 끝나면 다시 그릴 수 있어요"
        ErrorCodes.VIDEO_IN_PROGRESS -> "지금 만드는 쇼츠가 끝나면 다시 만들 수 있어요"
        ErrorCodes.VIDEO_LIMIT -> "이 카드로는 쇼츠를 ${VIDEO_LIMIT_PER_CARD}번까지 만들 수 있어요"
        ErrorCodes.VIDEO_NOT_FOUND -> "지워졌거나 찾을 수 없는 쇼츠예요"
        ErrorCodes.TOKEN_EXPIRED -> "다시 로그인해 주세요"
        ErrorCodes.NETWORK -> "인터넷 연결을 확인하고 다시 시도해 주세요"
        // 화자 그림 (문구는 명세에 없어서 프론트가 정함)
        ErrorCodes.DUPLICATE_CHARACTER -> "이 그림은 다른 화자에 이미 붙어 있어요"
        ErrorCodes.CHARACTER_NOT_READY -> "이 친구의 캐릭터를 지금은 쓸 수 없어요"
        ErrorCodes.UNKNOWN_CHARACTER -> "이 캐릭터를 찾지 못했어요. 다시 골라 주세요"
        ErrorCodes.CHARACTER_IN_PROGRESS -> "캐릭터를 그리는 중이에요. 다 되면 다시 만들 수 있어요"
        ErrorCodes.CHARACTER_LIMIT -> "캐릭터는 하루에 3번까지 만들 수 있어요. 내일 다시 해 주세요"
        ErrorCodes.PAYLOAD_TOO_LARGE, "HTTP_413" -> "사진이 너무 커요. 다른 사진을 골라 주세요"
        // 친구 방 (코드는 rooms.md 오류 코드, 문구는 프론트가 정함)
        RoomErrorCodes.ROOM_POST_EXISTS -> "이미 이 방에 올린 만화예요"
        RoomErrorCodes.ROOM_POST_NOT_MINE -> "올린 사람만 내릴 수 있어요"
        RoomErrorCodes.ROOM_MEMBER_LIMIT -> "나를 포함해 ${RoomRules.MEMBER_MAX}명까지 들어갈 수 있어요"
        RoomErrorCodes.ROOM_NOT_FOUND -> "방을 찾지 못했어요"
        RoomErrorCodes.ROOM_POST_NOT_FOUND -> "지워졌거나 찾을 수 없어요"
        RoomErrorCodes.ROOM_LIMIT -> "방은 ${RoomRules.ROOM_MAX}개까지 들어갈 수 있어요"
        RoomErrorCodes.ROOM_OWNER_ONLY -> "방장만 할 수 있어요"
        RoomErrorCodes.ROOM_INVITE_NOT_FOUND -> "이미 답했거나 취소된 초대예요"
        RoomErrorCodes.NEW_OWNER_REQUIRED -> "새 방장을 골라야 나갈 수 있어요"
        // 서버의 message는 개발자용이라 화면에 그대로 띄우지 않아요(api-dto ErrorResponse)
        else -> if (ex.code.startsWith("HTTP_5")) "잠시 후 다시 시도해 주세요" else "문제가 생겼어요. 잠시 후 다시 시도해 주세요"
    }
}

// ── ① 처음 열 때 로딩 ────────────────────────────────

/** 깜빡이는 회색 막대 */
@Composable
private fun SkeletonBar(width: Float, height: Dp, alpha: Float) {
    Box(
        Modifier.fillMaxWidth(width).height(height).alpha(alpha)
            .clip(RoundedCornerShape(6.dp)).background(LologColors.Line),
    )
}

/** 목록을 불러오는 동안 회색 카드 뼈대. 하얀 빈 화면이 안 보이게 */
@Composable
fun SkeletonList(modifier: Modifier = Modifier, count: Int = 5) {
    val t = rememberInfiniteTransition(label = "skeleton")
    val a by t.animateFloat(0.55f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "a")
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(count) {
            Column(Modifier.fillMaxWidth().softCard().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SkeletonBar(0.22f, 10.dp, a)
                SkeletonBar(0.75f, 12.dp, a)
                SkeletonBar(0.45f, 10.dp, a)
            }
        }
    }
}

// ── ④ 목록 못 불러옴 ────────────────────────────────

@Composable
fun ListErrorState(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "목록을 불러오지 못했어요",
    body: String = "잠시 후 다시 시도해 주세요",
) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(LologColors.RecSoft), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Info, null, tint = LologColors.Rec, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(body, fontSize = 13.sp, color = LologColors.Muted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(18.dp))
        PrimaryButton("다시 시도", onRetry, modifier = Modifier.width(120.dp).height(44.dp))
    }
}

// ── ⑤⑥ 빈 화면 ─────────────────────────────────────

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    memo: String,
    buttonText: String,
    onButton: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    /** 있으면 버튼 두 개: 왼쪽 흰 보조 버튼(이 글자) + 오른쪽 코랄 [buttonText] */
    secondaryText: String? = null,
    onSecondary: () -> Unit = {},
) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(LologColors.AccentSoft), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = LologColors.Accent, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(body, fontSize = 13.sp, color = LologColors.Muted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(14.dp))
        PenMemo(memo)
        Spacer(Modifier.height(14.dp))
        if (secondaryText != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton(secondaryText, onSecondary, modifier = Modifier.weight(1f).height(44.dp))
                PrimaryButton(buttonText, onButton, modifier = Modifier.weight(1f).height(44.dp))
            }
        } else if (primary) PrimaryButton(buttonText, onButton, modifier = Modifier.widthIn(min = 140.dp).height(44.dp))
        else GhostButton(buttonText, onButton, modifier = Modifier.widthIn(min = 140.dp).height(44.dp))
    }
}

// ── 목록 끝 ─────────────────────────────────────────

/** 다음 20개를 불러오는 중: 파란 점 세 개 */
@Composable
fun LoadMoreDots() {
    Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) { TypingDots(dot = 6.dp) }
}

/** 마지막 페이지(nextCursor = null) */
@Composable
fun ListEnd(text: String = "— 다 봤어요 —") {
    Text(
        text, fontSize = 12.sp, color = LologColors.Muted, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(12.dp),
    )
}

// ── ⑦⑧ 오프라인 ────────────────────────────────────

/** 인터넷 연결 상태. 끊기면 false, 다시 연결되면 true */
@Composable
fun rememberIsOnline(): State<Boolean> {
    val context = LocalContext.current
    val cm = remember { context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }
    val online = remember {
        mutableStateOf(cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true)
    }
    DisposableEffect(cm) {
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { online.value = true }
            override fun onLost(network: Network) {
                online.value = cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            }
        }
        cm.registerDefaultNetworkCallback(cb)
        onDispose { runCatching { cm.unregisterNetworkCallback(cb) } }
    }
    return online
}

/** 위쪽 옅은 회색 띠. 전에 불러온 목록은 그대로 보여 주고, 다시 연결되면 사라져요 */
@Composable
fun OfflineBar(visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible, modifier) {
        Row(
            Modifier.fillMaxWidth().background(Color(0xFFE9ECEF)).statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Info, null, tint = LologColors.Muted, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text("인터넷 연결이 끊겼어요", fontSize = 12.sp, color = LologColors.Ink)
        }
    }
}
