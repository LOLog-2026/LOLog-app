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

/** 볼펜 노트 배경: 캔버스색 + 28dp 간격 옅은 줄 */
fun Modifier.noteLines(): Modifier = this
    .background(LologColors.Canvas)
    .drawBehind {
        val gap = 28.dp.toPx()
        var y = 10.dp.toPx()
        while (y < size.height) {
            drawLine(LologColors.Rule, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            y += gap
        }
    }

/** 흰 카드 + 옅은 그림자 */
fun Modifier.softCard(shape: RoundedCornerShape = LologShape.md): Modifier = this
    .shadow(2.dp, shape, ambientColor = Color(0x10191F28), spotColor = Color(0x10191F28))
    .clip(shape)
    .background(LologColors.Paper)

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

/** 알약 모양 주 버튼 (높이 52) */
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
        modifier = modifier.fillMaxWidth().height(52.dp),
        shape = LologShape.pill,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (danger) LologColors.Rec else LologColors.Accent,
            contentColor = LologColors.Paper,
            disabledContainerColor = if (loading) (if (danger) LologColors.Rec else LologColors.Accent).copy(alpha = 0.8f) else LologColors.Line,
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

@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(52.dp),
        shape = LologShape.pill,
        border = androidx.compose.foundation.BorderStroke(1.dp, LologColors.Line),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = LologColors.Paper, contentColor = LologColors.Ink),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
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

/** 카드 종류 칩: 작은 색 점 + 연한 배경, 살짝 기울임(볼펜 노트) */
@Composable
fun KindChip(kind: CardKind, modifier: Modifier = Modifier) {
    Row(
        modifier
            .rotate(-1f)
            .clip(RoundedCornerShape(4.dp))
            .background(kind.soft())
            .height(24.dp)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(kind.dot()))
        Spacer(Modifier.width(4.dp))
        Text(kind.label, style = MaterialTheme.typography.labelMedium)
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

private val avatarColors = listOf(LologColors.Laugh, LologColors.Memory, LologColors.Absurd, LologColors.AccentSoft)

/** 화자 점 색: 순서대로 주황 · 초록 · 빨강 · 파랑 (카드 고치기 시안) */
fun speakerColor(index: Int): Color =
    listOf(LologColors.Laugh, LologColors.Memory, LologColors.Absurd, LologColors.Accent)[index.coerceAtLeast(0) % 4]

/** 화자 동그라미: 이름 첫 글자, 순서별 색 */
@Composable
fun SpeakerAvatar(name: String, index: Int, size: Dp = 32.dp) {
    val label = if (name.startsWith("화자")) name.removePrefix("화자") else name.take(1)
    Box(
        Modifier.size(size).clip(CircleShape).background(avatarColors[index.coerceAtLeast(0) % avatarColors.size]),
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = (size.value * 0.4f).sp, fontWeight = FontWeight.Bold, color = LologColors.Ink) }
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
