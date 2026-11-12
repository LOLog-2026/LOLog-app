package com.lolog.app.ui.screens.rooms

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lolog.app.data.model.Room
import com.lolog.app.data.model.RoomMember
import com.lolog.app.data.model.RoomMessage
import com.lolog.app.data.model.RoomPost
import com.lolog.app.data.model.RoomRules
import com.lolog.app.data.model.subject
import com.lolog.app.ui.components.ChatBubble
import com.lolog.app.ui.components.ComicImage
import com.lolog.app.ui.components.TypingDots
import com.lolog.app.ui.theme.LologColors
import com.lolog.app.ui.theme.LologShape

// ── 24 대화 탭 ──────────────────────────────────────

/**
 * 가운데 회색 알약(만화를 올렸어요·좋아해요)과 댓글 말풍선. 댓글 위에는 그 만화 캡션을 붙이고, 캡션을 누르면 크게.
 * "댓글 달기"로 들어오면 입력 칸 위에 그 만화 띠([replyTo])가 붙어요. ✕를 누르면 그냥 대화.
 * 시안 10: 나간 멤버는 "?" 동그라미와 "나간 멤버", 숨긴 글은 "·" 동그라미와 "숨긴 글"(알림 줄은 그리지 않음),
 * 정지된 계정은 회색 얼굴과 이름 뒤 " · 정지된 계정"
 */
@Composable
internal fun RoomChat(
    room: Room,
    messages: List<RoomMessage>,
    replyTo: RoomPost?,
    sending: Boolean,
    onClearReply: () -> Unit,
    onCaption: (RoomPost) -> Unit,
    onSend: (text: String, done: () -> Unit) -> Unit,
) {
    val list = rememberLazyListState()
    var text by remember { mutableStateOf("") }
    // 숨긴 사람의 알림 줄(올림·좋아요)은 그리지 않아요. 댓글은 "숨긴 글" 자리로 남겨요
    val shown = remember(messages) { messages.filterNot { it.hidden && it !is RoomMessage.Comment } }
    // 새 줄이 생기면 맨 아래로
    LaunchedEffect(shown.size) { if (shown.isNotEmpty()) list.animateScrollToItem(shown.lastIndex) }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = list,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(shown, key = { it.id }) { m ->
                when (m) {
                    is RoomMessage.System -> NoticePill(m.text, heart = false)
                    is RoomMessage.Like -> NoticePill("${m.member.subject} 이 만화를 좋아해요", heart = true)
                    is RoomMessage.Comment -> if (m.hidden) ChatBubble(
                        name = "숨긴 글",
                        speakerIndex = 0,
                        text = "",
                        left = true,
                        avatar = { GrayDot("·", 26.dp) },
                        content = { Text("차단한 사람의 글이에요", fontSize = 13.5.sp, lineHeight = 20.sp, color = LologColors.Muted) },
                    ) else ChatBubble(
                        name = when {
                            m.author.left -> RoomMember.LEFT_NAME
                            m.author.suspended -> "${m.author.nickname} · 정지된 계정"
                            else -> m.author.nickname
                        },
                        speakerIndex = room.memberIndex(m.author),
                        text = m.text,
                        left = !m.author.isMe,
                        character = m.author.character(),
                        avatar = { MemberAvatar(m.author, room.memberIndex(m.author), 26.dp) },
                        content = {
                            Column {
                                m.post?.let { p ->
                                    // 숨긴 만화 캡션은 눌러도 크게 보지 않아요
                                    Caption(p, Modifier.then(if (p.hidden) Modifier else Modifier.clickable { onCaption(p) }).padding(bottom = 6.dp))
                                }
                                Text(m.text, fontSize = 13.5.sp, lineHeight = 20.sp, color = LologColors.Ink)
                            }
                        },
                    )
                }
            }
        }

        // 입력 칸
        Column(Modifier.fillMaxWidth().background(LologColors.Paper).navigationBarsPadding().imePadding()) {
            HorizontalDivider(thickness = 1.dp, color = LologColors.Line)
            replyTo?.let { p ->
                Row(
                    Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp).fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp)).background(LologColors.Canvas).padding(start = 8.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ComicImage(p.imageUrl, p.mockLines, Modifier.size(34.dp), small = true)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${ownerLabel(p)}에 댓글", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = LologColors.Ink)
                        Text(p.title, fontSize = 11.5.sp, color = LologColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = onClearReply) { Icon(Icons.Default.Close, "댓글 띠 닫기", tint = LologColors.Muted, modifier = Modifier.size(18.dp)) }
                }
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it.take(RoomRules.COMMENT_MAX) },
                    placeholder = { Text("댓글 쓰기…", fontSize = 14.sp, color = LologColors.Muted) },
                    maxLines = 4,
                    shape = LologShape.pill,
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = LologColors.Accent, unfocusedBorderColor = LologColors.Line,
                        focusedContainerColor = LologColors.Paper, unfocusedContainerColor = LologColors.Paper,
                    ),
                )
                Spacer(Modifier.width(8.dp))
                val canSend = text.isNotBlank() && !sending
                Box(
                    Modifier.size(44.dp).clip(CircleShape)
                        .background(if (canSend || sending) LologColors.Accent else LologColors.Canvas)
                        .clickable(enabled = canSend) { onSend(text) { text = "" } },
                    contentAlignment = Alignment.Center,
                ) {
                    if (sending) TypingDots(color = LologColors.Paper, dot = 4.dp)
                    else Icon(Icons.AutoMirrored.Filled.Send, "보내기", tint = if (canSend) LologColors.Paper else LologColors.Muted, modifier = Modifier.size(19.dp))
                }
            }
        }
    }
}

/** "준님 만화" / "내 만화" / "나간 멤버 만화" */
private fun ownerLabel(p: RoomPost) = when {
    p.author.isMe -> "내 만화"
    p.author.left -> "${RoomMember.LEFT_NAME} 만화"
    else -> "${p.author.nickname}님 만화"
}

/** 말풍선 위 작은 만화 캡션: 그림 + "준님 만화" + 제목 한 줄 */
@Composable
private fun Caption(post: RoomPost, modifier: Modifier = Modifier) {
    Row(
        modifier.widthIn(max = 200.dp).clip(RoundedCornerShape(10.dp)).background(LologColors.Paper).padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (post.hidden) GrayDot("·", 36.dp) else ComicImage(post.imageUrl, post.mockLines, Modifier.size(36.dp), small = true)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(if (post.hidden) "숨긴 글" else ownerLabel(post), fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = LologColors.Ink)
            if (!post.hidden) Text(post.title, fontSize = 11.sp, color = LologColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 가운데 회색 알약 한 줄 */
@Composable
private fun NoticePill(text: String, heart: Boolean) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(
            Modifier.clip(LologShape.pill).background(LologColors.Canvas).padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (heart) {
                Icon(Icons.Filled.Favorite, null, tint = LologColors.Muted, modifier = Modifier.size(11.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(text, fontSize = 11.5.sp, color = LologColors.Muted)
        }
    }
}
