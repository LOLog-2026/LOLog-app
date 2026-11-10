package com.lolog.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lolog.app.data.model.CardKind
import com.lolog.app.ui.theme.LologColors
import com.lolog.app.ui.theme.LologShape

/*
 * 11/10 팀 확정 디자인 공용 부품
 *  - 상태 띠(StatusStrip): 홈 마이크 아래 "찾는 중 / 끝남"
 *  - 말풍선(ChatBubble): 카드 보기·그리는 중의 대사
 *  - 큰 종류 칩(KindFilterPill): 에피소드 위 칸
 *  - 아래 시트(LologSheet): 위쪽 모서리 22dp
 *  알약 버튼(PrimaryButton·GhostButton)과 종류 칩(KindChip)은 Basics.kt, 확인 창은 Dialogs.kt에 있어요.
 */

private val stripShape = RoundedCornerShape(14.dp)

/**
 * 상태 띠. [searching]이면 연노랑 바탕 + 코랄 점 3개, 아니면(끝남) MemorySoft 바탕 + 오른쪽 "보기 →".
 * [title]이 바뀌면 살짝 흐려졌다 나타나요.
 */
@Composable
fun StatusStrip(
    searching: Boolean,
    title: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    onView: (() -> Unit)? = null,
) {
    val bg = if (searching) LologColors.AccentSoft else LologColors.MemorySoft
    val line = if (searching) LologColors.Highlight.copy(alpha = 0.45f) else LologColors.Memory.copy(alpha = 0.5f)
    Row(
        modifier
            .fillMaxWidth()
            .clip(stripShape)
            .background(bg)
            .border(1.5.dp, line, stripShape)
            .then(if (!searching && onView != null) Modifier.clickable(onClick = onView) else Modifier)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (searching) {
            TypingDots(color = LologColors.Accent, dot = 5.dp)
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            AnimatedContent(
                targetState = title,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "strip-title",
            ) { t ->
                Text(t, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = if (searching) LologColors.Ink else LologColors.MemoryText)
            }
            if (sub != null) Text(sub, fontSize = 11.sp, color = LologColors.Muted)
        }
        if (!searching && onView != null) {
            Text("보기 →", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = LologColors.MemoryText)
        }
    }
}

private val bubbleShape = RoundedCornerShape(16.dp)

/**
 * 말풍선. [left]면 Canvas 바탕 + 왼쪽 26dp 이름 동그라미 + 위에 작은 이름,
 * 오른쪽이면 AccentSoft 바탕 + 오른쪽 이름 동그라미. 최대 너비는 화면의 60%.
 */
@Composable
fun ChatBubble(
    name: String,
    speakerIndex: Int,
    text: String,
    left: Boolean,
    modifier: Modifier = Modifier,
    content: (@Composable () -> Unit)? = null,
) {
    val maxW = (LocalConfiguration.current.screenWidthDp * 0.6f).dp
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = if (left) Arrangement.Start else Arrangement.End,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (left) {
            SpeakerAvatar(name, speakerIndex, size = 26.dp)
            Spacer(Modifier.width(8.dp))
        }
        Column(horizontalAlignment = if (left) Alignment.Start else Alignment.End) {
            if (left) {
                Text(name, fontSize = 10.5.sp, color = LologColors.Muted, modifier = Modifier.padding(start = 4.dp, bottom = 3.dp))
            }
            Box(
                Modifier
                    .widthIn(max = maxW)
                    .clip(bubbleShape)
                    .background(if (left) LologColors.Canvas else LologColors.AccentSoft)
                    .padding(horizontal = 13.dp, vertical = 9.dp),
            ) {
                if (content != null) content() else Text(text, fontSize = 13.5.sp, lineHeight = 20.sp, color = LologColors.Ink)
            }
        }
        if (!left) {
            Spacer(Modifier.width(8.dp))
            SpeakerAvatar(name, speakerIndex, size = 26.dp)
        }
    }
}

/** 에피소드 위 칸의 큰 종류 칩(높이 26). 고른 것은 Ink 바탕 흰 글자, 나머지는 각 종류 색. [kind] null = "전체" */
@Composable
fun KindFilterPill(text: String, kind: CardKind?, selected: Boolean, onClick: () -> Unit) {
    val bg = when {
        selected -> LologColors.Ink
        kind == null -> LologColors.Canvas
        else -> kind.soft()
    }
    val fg = when {
        selected -> LologColors.Paper
        kind == null -> LologColors.Ink
        else -> kind.text()
    }
    Box(
        Modifier
            .clip(LologShape.pill)
            .background(bg)
            .clickable(onClick = onClick)
            .height(26.dp)
            .padding(horizontal = 13.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = fg) }
}

/** 아래 시트: 흰 바탕, 위쪽 모서리 22dp */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LologSheet(
    onDismiss: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = LologShape.sheet,
        containerColor = LologColors.Paper,
        content = content,
    )
}
