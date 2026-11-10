package com.lolog.app.ui.screens.rooms

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lolog.app.data.model.Room
import com.lolog.app.data.model.RoomPost
import com.lolog.app.ui.components.ComicImage
import com.lolog.app.ui.theme.LologColors
import com.lolog.app.ui.theme.LologShape
import com.lolog.app.util.relativeTime
import kotlin.math.roundToInt

/** 떠오른 칸 뒤·팝업 바깥의 어두운 바탕 */
private val Dim = Color(0x99191F28)

/** 아무것도 하지 않는 누르기(카드 안을 눌러도 바깥 누르기로 닫히지 않게) */
@Composable
private fun Modifier.swallowClicks(): Modifier =
    clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}

// ── 22 누르면 크게 ──────────────────────────────────

/**
 * 어두운 바탕 위 흰 카드: 올린 사람·제목·시간, 4컷 세로 그림(카드 안에서 스크롤), 하트·댓글 수.
 * 그림은 내 만화 화면과 같은 ComicImage. 바깥을 누르거나 ✕로 닫아요
 */
@Composable
internal fun ComicPopup(room: Room, post: RoomPost, onLike: () -> Unit, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose)
                .systemBarsPadding()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.End) {
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "닫기", tint = Color.White) }
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(LologColors.Paper).swallowClicks(),
                ) {
                    Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        MemberAvatar(post.author, room.memberIndex(post.author), 28.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(post.title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = LologColors.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${if (post.author.isMe) "내 만화" else post.author.nickname} · ${relativeTime(post.postedAt)}",
                                fontSize = 12.sp, color = LologColors.Muted,
                            )
                        }
                    }
                    Box(
                        Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        ComicImage(post.imageUrl, post.mockLines, Modifier.fillMaxWidth(0.78f))
                    }
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.clip(LologShape.pill).clickable(onClick = onLike).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            LikeCount(post, 18, 14)
                        }
                        Spacer(Modifier.width(10.dp))
                        Icon(Icons.Outlined.ChatBubbleOutline, null, tint = LologColors.Muted, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("${post.commentCount}", fontSize = 14.sp, color = LologColors.Muted)
                        Spacer(Modifier.weight(1f))
                        Text("아래로 내려 4컷 보기", fontSize = 12.sp, color = LologColors.Muted)
                    }
                }
            }
        }
    }
}

// ── 23 꾹 누르기 ────────────────────────────────────

/**
 * 누른 칸을 같은 자리에 살짝 크게 다시 그리고 뒤는 어둡게.
 * 칸 위에 "좋아요" 알약, 칸 아래(자리가 없으면 위)에 메뉴. 바깥을 누르면 닫혀요
 */
@Composable
internal fun PressedOverlay(
    room: Room,
    post: RoomPost,
    bounds: Rect,
    onDismiss: () -> Unit,
    onLike: () -> Unit,
    onComment: () -> Unit,
    onSave: () -> Unit,
    onZoom: () -> Unit,
    onRemove: () -> Unit,
) {
    val density = LocalDensity.current
    var screenW by remember { mutableIntStateOf(0) }
    var screenH by remember { mutableIntStateOf(0) }
    var menuH by remember { mutableIntStateOf(0) }
    val gap = with(density) { 10.dp.roundToPx() }
    Box(
        Modifier.fillMaxSize()
            .onGloballyPositioned { screenW = it.size.width; screenH = it.size.height }
            .background(Dim)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
    ) {
        // 떠오른 칸
        ComicCell(
            room, post,
            modifier = Modifier
                .offset { IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt()) }
                .size(with(density) { bounds.width.toDp() }, with(density) { bounds.height.toDp() })
                .graphicsLayer { scaleX = 1.04f; scaleY = 1.04f }
                .shadow(12.dp, RoundedCornerShape(14.dp)),
        )
        // 칸 위 "좋아요" 알약
        Row(
            Modifier
                .offset { IntOffset(bounds.left.roundToInt() + gap, (bounds.top - gap * 4).roundToInt().coerceAtLeast(0)) }
                .shadow(6.dp, LologShape.pill)
                .clip(LologShape.pill)
                .background(LologColors.Paper)
                .clickable(onClick = onLike)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (post.likedByMe) Icons.Outlined.FavoriteBorder else Icons.Filled.Favorite, null,
                tint = LologColors.Accent, modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(5.dp))
            Text(if (post.likedByMe) "좋아요 취소" else "좋아요", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = LologColors.Accent)
        }
        // 칸 아래 메뉴. 아래에 자리가 없으면 칸 위로
        val below = bounds.bottom.roundToInt() + gap
        val menuTop = if (menuH == 0 || below + menuH <= screenH - gap) below else (bounds.top.roundToInt() - gap * 5 - menuH).coerceAtLeast(gap)
        // 오른쪽 칸이면 메뉴가 화면 밖으로 나가지 않게 왼쪽으로 당겨요
        val menuW = with(density) { 180.dp.roundToPx() }
        val menuLeft = bounds.left.roundToInt().coerceAtMost(screenW - menuW - gap).coerceAtLeast(gap)
        Column(
            Modifier
                .offset { IntOffset(menuLeft, menuTop) }
                .width(180.dp)
                .onGloballyPositioned { menuH = it.size.height }
                .shadow(8.dp, RoundedCornerShape(14.dp))
                .clip(RoundedCornerShape(14.dp))
                .background(LologColors.Paper),
        ) {
            MenuRow(Icons.Outlined.ChatBubbleOutline, "댓글 달기", LologColors.Ink, onComment)
            HorizontalDivider(thickness = 1.dp, color = LologColors.Line)
            MenuRow(Icons.Outlined.Download, "내 폰에 저장", LologColors.Ink, onSave)
            HorizontalDivider(thickness = 1.dp, color = LologColors.Line)
            MenuRow(Icons.Outlined.Visibility, "크게 보기", LologColors.Ink, onZoom)
            if (post.author.isMe) {
                HorizontalDivider(thickness = 1.dp, color = LologColors.Line)
                MenuRow(Icons.Outlined.Delete, "방에서 내리기", LologColors.Rec, onRemove)
            }
        }
    }
}

@Composable
private fun MenuRow(icon: ImageVector, text: String, color: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = color)
    }
}
