package com.lolog.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lolog.app.data.model.CardKind
import com.lolog.app.data.model.ComicStage
import com.lolog.app.ui.theme.LologColors
import com.lolog.app.ui.theme.LologShape
import com.lolog.app.ui.theme.LologText
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import coil.request.ImageRequest

/** 볼펜 노트 배경: 흰 바탕 + 28dp 간격 아주 옅은 줄 (B안) */
fun Modifier.noteLines(): Modifier = this
    .background(LologColors.Paper)
    .drawBehind {
        val gap = 28.dp.toPx()
        var y = 10.dp.toPx()
        while (y < size.height) {
            drawLine(LologColors.Rule, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            y += gap
        }
    }

/** 흰 카드 + 옅은 그림자 + 아주 옅은 테두리 */
fun Modifier.softCard(shape: RoundedCornerShape = LologShape.md): Modifier = this
    .shadow(3.dp, shape, ambientColor = Color(0x10191F28), spotColor = Color(0x10191F28))
    .clip(shape)
    .background(LologColors.Paper)
    .border(1.dp, Color(0xFFF0F1F4), shape) // 흰 바탕 위에서도 카드 테두리가 보이게

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LologTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로") }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
    )
}

/** 알약 모양 주 버튼 (11/10 확정: 높이 46, 기본 Accent 바탕 흰 글자, 꺼짐은 Canvas 바탕 회색 글자) */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    danger: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier.fillMaxWidth().height(46.dp),
        shape = LologShape.pill,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (danger) LologColors.Rec else LologColors.Accent,
            contentColor = LologColors.Paper,
            disabledContainerColor = if (loading) (if (danger) LologColors.Rec else LologColors.Accent).copy(alpha = 0.8f) else LologColors.Canvas,
            disabledContentColor = LologColors.Muted,
        ),
    ) {
        if (loading) {
            // 공용 ②: 버튼 로딩은 글자 대신 작은 점 세 개. 끝날 때까지 다시 못 눌러요
            TypingDots(color = LologColors.Paper, dot = 6.dp)
        } else {
            leading?.invoke()
            if (leading != null) Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** 보조 알약 버튼: 흰 바탕 + Line 테두리 1.5dp (높이 46) */
@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(46.dp),
        shape = LologShape.pill,
        border = androidx.compose.foundation.BorderStroke(1.5.dp, LologColors.Line),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = LologColors.Paper, contentColor = LologColors.Ink,
            disabledContainerColor = LologColors.Canvas, disabledContentColor = LologColors.Muted,
        ),
    ) { Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false) }
}

/** 작은 테두리 버튼 (시안의 link-btn) */
@Composable
fun SmallPillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(LologShape.pill)
            .border(1.dp, LologColors.Line, LologShape.pill)
            .background(LologColors.Paper)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) { Text(text, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
}

fun CardKind.dot(): Color = when (this) {
    CardKind.FUNNY -> LologColors.Laugh
    CardKind.ABSURD -> LologColors.Absurd
    CardKind.MEMORY -> LologColors.Memory
    CardKind.OTHER -> LologColors.Muted
}

fun CardKind.soft(): Color = when (this) {
    CardKind.FUNNY -> LologColors.LaughSoft
    CardKind.ABSURD -> LologColors.AbsurdSoft
    CardKind.MEMORY -> LologColors.MemorySoft
    CardKind.OTHER -> LologColors.Canvas
}

/** 종류 칩 글자 색: 웃김은 진한 노랑(Laugh를 Ink 쪽으로 어둡게), 황당 Absurd, 추억 MemoryText */
fun CardKind.text(): Color = when (this) {
    CardKind.FUNNY -> lerp(LologColors.Laugh, LologColors.Ink, 0.35f)
    CardKind.ABSURD -> LologColors.Absurd
    CardKind.MEMORY -> LologColors.MemoryText
    CardKind.OTHER -> LologColors.Muted
}

/**
 * 카드 종류 칩(11/10 확정): 알약 + 앞에 ● 점, 종류 연한 색 바탕 + 종류 글자 색.
 * [onCard] = 종류 색 카드 위에 놓을 때는 흰 바탕(에피소드 2열 카드).
 */
@Composable
fun KindChip(kind: CardKind, modifier: Modifier = Modifier, height: Dp = 22.dp, onCard: Boolean = false, suffix: String? = null) {
    Row(
        modifier
            .clip(LologShape.pill)
            .background(if (onCard) LologColors.Paper else kind.soft())
            .height(height)
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(kind.text()))
        Spacer(Modifier.width(4.dp))
        Text(
            if (suffix == null) kind.label else "${kind.label} · $suffix",
            fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = kind.text(),
        )
    }
}

/** 보관함 필터 칩 */
@Composable
fun FilterPill(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(LologShape.pill)
            .background(if (selected) LologColors.Ink else LologColors.Paper)
            .border(1.dp, if (selected) LologColors.Ink else LologColors.Line, LologShape.pill)
            .clickable(onClick = onClick)
            .height(32.dp)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = if (selected) LologColors.Paper else LologColors.Ink)
    }
}

/** 만화 상태 배지: 대기 / 만드는 중 / 완료 / 실패 */
@Composable
fun StageBadge(stage: ComicStage, modifier: Modifier = Modifier) {
    val (bg, fg) = when (stage) {
        ComicStage.DONE -> LologColors.MemorySoft to LologColors.MemoryText
        ComicStage.MAKING -> LologColors.AccentSoft to LologColors.Accent
        ComicStage.WAITING -> LologColors.Canvas to LologColors.Muted
        ComicStage.FAILED -> LologColors.RecSoft to LologColors.Rec
    }
    Row(
        modifier.clip(LologShape.pill).background(bg).height(24.dp).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(fg))
        Spacer(Modifier.width(5.dp))
        Text(stage.label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = fg)
    }
}

/** 화자 색: 화자 순서대로 노랑 · 민트 · 분홍 · 하늘 (11/10 확정) */
fun speakerColor(index: Int): Color =
    LologColors.Speakers[index.coerceAtLeast(0) % LologColors.Speakers.size]

/**
 * 화자 동그라미: 이름 첫 글자, 순서별 색.
 * [thumbnailUrl]이 있으면(화자에 그림을 붙였으면) 둥근 그림. [cacheKey]는 characterImage() 참고
 */
@Composable
fun SpeakerAvatar(name: String, index: Int, size: Dp = 32.dp, thumbnailUrl: String? = null, cacheKey: String? = null) {
    if (thumbnailUrl != null) {
        AsyncImage(
            model = if (cacheKey != null) characterImage(thumbnailUrl, cacheKey) else thumbnailUrl,
            contentDescription = name, contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(CircleShape).background(LologColors.Canvas),
        )
        return
    }
    val label = if (name.startsWith("화자")) name.removePrefix("화자") else name.take(1)
    Box(
        Modifier.size(size).clip(CircleShape).background(speakerColor(index)),
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = (size.value * 0.4f).sp, fontWeight = FontWeight.Bold, color = LologColors.Ink) }
}

/**
 * 화자 그림(기본 화자·프로필 캐릭터) 불러오기. 서명 주소가 10분마다 바뀌어서
 * 메모리·디스크 캐시를 주소 대신 [cacheKey](default-<id>, profile-<userId>)로 해요
 */
@Composable
fun characterImage(url: String, cacheKey: String): ImageRequest {
    val context = LocalContext.current
    return remember(url, cacheKey) {
        ImageRequest.Builder(context).data(url).memoryCacheKey(cacheKey).diskCacheKey(cacheKey).crossfade(true).build()
    }
}

/** 파란 볼펜 손글씨 메모 (메모에만 사용) */
@Composable
fun PenMemo(text: String, modifier: Modifier = Modifier, angle: Float = -3f) {
    Text(text, style = LologText.memo, modifier = modifier.rotate(angle))
}

/** 형광펜: 글자 아래쪽 42%에 노란 띠 */
fun Modifier.highlighter(): Modifier = drawBehind {
    val top = size.height * 0.58f
    drawRoundRect(
        LologColors.Highlight,
        topLeft = Offset(-2.dp.toPx(), top),
        size = Size(size.width + 4.dp.toPx(), size.height - top),
        cornerRadius = CornerRadius(2.dp.toPx()),
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = LologColors.Muted)
}

/** 입력 중 점 세 개 */
@Composable
fun TypingDots(color: Color = LologColors.Accent, dot: Dp = 9.dp) {
    val t = rememberInfiniteTransition(label = "dots")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(3) { i ->
            val a by t.animateFloat(
                initialValue = 0.25f, targetValue = 0.25f,
                animationSpec = infiniteRepeatable(
                    keyframes {
                        durationMillis = 1200
                        0.25f at 0
                        1f at 480
                        0.25f at 960
                    },
                    initialStartOffset = StartOffset(i * 200),
                ),
                label = "dot$i",
            )
            Box(Modifier.size(dot).alpha(a).clip(CircleShape).background(color))
        }
    }
}

/** 진행률이 없는 일(감지·만화)에 쓰는 움직이는 막대 */
@Composable
fun IndeterminateBar(modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        modifier = modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(8.dp)),
        color = LologColors.Accent,
        trackColor = LologColors.Line,
    )
}

/** 가운데 정렬 빈 화면/안내 */
@Composable
fun CenterMessage(title: String, body: String, modifier: Modifier = Modifier, icon: @Composable (() -> Unit)? = null) {
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        icon?.invoke()
        if (icon != null) Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = LologColors.Muted, textAlign = TextAlign.Center)
    }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { TypingDots() }
}

@Composable
fun ErrorBox(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(message, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        SmallPillButton("다시 시도", onRetry)
    }
}
