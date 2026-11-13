package com.lolog.app.ui.screens.rooms

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lolog.app.data.model.Room
import com.lolog.app.data.model.RoomMember
import com.lolog.app.data.model.RoomMessage
import com.lolog.app.data.model.RoomPost
import com.lolog.app.data.model.RoomRules
import com.lolog.app.ui.components.ComicImage
import com.lolog.app.ui.components.LologSheet
import com.lolog.app.ui.components.LologSnackbarHost
import com.lolog.app.ui.components.TypingDots
import com.lolog.app.ui.theme.LologColors
import com.lolog.app.ui.theme.LologShape
import com.lolog.app.util.relativeTime
import java.time.OffsetDateTime

// ── 방 만화 댓글 창 (11/12 시안 8~11) ─────────────────

/**
 * 받아 둔 방 대화 중 이 만화([postId])에 단 댓글만, 오래된 것이 위.
 * 만화별 댓글 주소가 없어서 GET messages(방 대화)에서 골라요(서버 변경 없음)
 */
internal fun commentsFor(messages: List<RoomMessage>, postId: Long): List<RoomMessage.Comment> =
    messages.filterIsInstance<RoomMessage.Comment>().filter { it.postId == postId }.sortedBy { it.id }

/** 댓글을 놓을 쪽. 내 댓글만 오른쪽, 숨긴 글·나간 멤버·정지된 계정·남의 댓글은 왼쪽 */
internal enum class CommentSide { LEFT, RIGHT }

internal fun commentSide(c: RoomMessage.Comment): CommentSide =
    if (c.author.isMe && !c.hidden) CommentSide.RIGHT else CommentSide.LEFT

/** 같은 사람이 이어 쓴 댓글로 보는 간격 */
internal const val COMMENT_GROUP_MS = 60_000L

/**
 * [prev] 바로 다음 [c]가 같은 사람이 1분 안에 이어 쓴 댓글이면 true. 둘째부터 사진·이름을 생략해요.
 * 숨긴 글은 늘 따로 보여 주고, 시간을 읽지 못하면 이어 쓴 것으로 보지 않아요
 */
internal fun continuesFrom(prev: RoomMessage.Comment?, c: RoomMessage.Comment): Boolean {
    if (prev == null || prev.hidden || c.hidden || prev.author.userId != c.author.userId) return false
    val a = epochMs(prev.at) ?: return false
    val b = epochMs(c.at) ?: return false
    return b - a in 0..COMMENT_GROUP_MS
}

private fun epochMs(iso: String): Long? = runCatching { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrNull()

/** 받아 둔 댓글이 만화의 댓글 수보다 적고 그 앞 대화가 더 있으면 "이전 댓글 더 보기" */
internal fun showOlderComments(loaded: Int, commentCount: Int, hasOlder: Boolean): Boolean =
    loaded < commentCount && hasOlder

/**
 * 만화 팝업 위에 올라오는 댓글 창: 위 작은 만화·"댓글 n", 가운데 이 만화 댓글, 아래 입력 칸.
 * 보내기는 방 대화 탭과 같은 요청(postId = 이 만화). 오류 띠는 창 위에 보이도록 [snackbar]로 띄워요
 */
@Composable
internal fun RoomCommentSheet(
    room: Room,
    post: RoomPost,
    messages: List<RoomMessage>,
    hasOlder: Boolean,
    loadingOlder: Boolean,
    onLoadOlder: () -> Unit,
    sending: Boolean,
    onSend: (text: String, done: () -> Unit) -> Unit,
    snackbar: SnackbarHostState,
    onDismiss: () -> Unit,
) {
    val comments = remember(messages, post.id) { commentsFor(messages, post.id) }
    val showOlder = showOlderComments(comments.size, post.commentCount, hasOlder)
    var text by remember(post.id) { mutableStateOf("") }
    val list = rememberLazyListState()
    // 새 댓글이 맨 아래에 붙으면 맨 아래로
    LaunchedEffect(comments.lastOrNull()?.id) { if (comments.isNotEmpty()) list.animateScrollToItem(list.layoutInfo.totalItemsCount - 1) }

    LologSheet(onDismiss = onDismiss) {
        Box(Modifier.fillMaxWidth().fillMaxHeight(0.8f)) {
            Column(Modifier.fillMaxSize()) {
                // 위: 작은 만화 + "댓글 n" + "제목 · 올린 사람"
                Row(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    ComicImage(post.imageUrl, post.mockLines, Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)), small = true)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("댓글 ${post.commentCount}", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = LologColors.Ink)
                        Text(
                            "${post.title} · ${post.author.displayName}", fontSize = 12.sp, color = LologColors.Muted,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                HorizontalDivider(thickness = 1.dp, color = LologColors.Line)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        state = list,
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        if (showOlder || loadingOlder) {
                            item(key = "older") {
                                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    if (loadingOlder) {
                                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LologColors.Muted)
                                    } else {
                                        Text(
                                            "이전 댓글 더 보기", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = LologColors.Muted,
                                            modifier = Modifier.clip(LologShape.pill).clickable(onClick = onLoadOlder).padding(horizontal = 10.dp, vertical = 4.dp),
                                        )
                                    }
                                }
                            }
                        }
                        itemsIndexed(comments, key = { _, c -> c.id }) { i, c ->
                            CommentRow(room, c, continued = continuesFrom(comments.getOrNull(i - 1), c))
                        }
                    }
                    if (comments.isEmpty() && !loadingOlder) {
                        Column(Modifier.align(Alignment.Center).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("아직 댓글이 없어요", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = LologColors.Ink)
                            Spacer(Modifier.height(4.dp))
                            Text("이 만화에 첫 댓글을 남겨 보세요", fontSize = 12.sp, color = LologColors.Muted, textAlign = TextAlign.Center)
                        }
                    }
                }
                // 아래: 알약 입력 칸 + 코랄 "보내기"
                HorizontalDivider(thickness = 1.dp, color = LologColors.Line)
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = text, onValueChange = { text = it.take(RoomRules.COMMENT_MAX) },
                        placeholder = { Text("이 만화에 댓글 쓰기…", fontSize = 14.sp, color = LologColors.Muted) },
                        maxLines = 4,
                        shape = LologShape.pill,
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = LologColors.Accent, unfocusedBorderColor = LologColors.Line,
                            focusedContainerColor = LologColors.Paper, unfocusedContainerColor = LologColors.Paper,
                        ),
                    )
                    Spacer(Modifier.width(8.dp))
                    // 앞뒤 공백을 빼고 비었거나 보내는 중이면 흐리게(두 번 안 눌려요)
                    val canSend = text.isNotBlank() && !sending
                    Box(
                        Modifier.height(44.dp).widthIn(min = 64.dp).clip(LologShape.pill)
                            .background(if (canSend || sending) LologColors.Accent else LologColors.Canvas)
                            .clickable(enabled = canSend) { onSend(text) { text = "" } }
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (sending) TypingDots(color = LologColors.Paper, dot = 4.dp)
                        else Text("보내기", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (canSend) LologColors.Paper else LologColors.Muted)
                    }
                }
            }
            LologSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp))
        }
    }
}

/** 댓글 말풍선: 방 대화 탭과 같은 색(남 = Canvas, 나 = AccentSoft), 둥근 10dp, 최대 화면의 75% */
private val commentBubble = RoundedCornerShape(10.dp)

@Composable
private fun commentMaxWidth() = (LocalConfiguration.current.screenWidthDp * 0.75f).dp

/** 댓글 한 줄. 놓는 쪽은 [commentSide]. [continued]면 같은 사람이 이어 쓴 것이라 사진·이름을 생략해요 */
@Composable
private fun CommentRow(room: Room, c: RoomMessage.Comment, continued: Boolean) {
    when (commentSide(c)) {
        CommentSide.RIGHT -> MyCommentRow(c)
        CommentSide.LEFT -> OtherCommentRow(room, c, continued)
    }
}

/** 남의 댓글(왼쪽): 사진 · 이름 · 시간, 아래 회색 말풍선. 숨긴 사람 댓글은 대화 탭의 "숨긴 글" 모양 */
@Composable
private fun OtherCommentRow(room: Room, c: RoomMessage.Comment, continued: Boolean) {
    Row(verticalAlignment = Alignment.Top) {
        // 이어 쓴 댓글은 사진 자리만 비워 둬 말풍선 줄을 맞춰요
        when {
            continued -> Spacer(Modifier.width(28.dp))
            c.hidden -> GrayDot("·", 28.dp)
            else -> MemberAvatar(c.author, room.memberIndex(c.author), 28.dp)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            if (!continued) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when {
                            c.hidden -> "숨긴 글"
                            c.author.left -> RoomMember.LEFT_NAME
                            c.author.suspended -> "${c.author.nickname} · 정지된 계정"
                            else -> c.author.nickname
                        },
                        fontSize = 13.sp, fontWeight = FontWeight.Bold, color = LologColors.Ink,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(relativeTime(c.at), fontSize = 11.sp, color = LologColors.Muted)
                }
                Spacer(Modifier.height(3.dp))
            }
            if (c.hidden) {
                Text("차단한 사람의 글이에요", fontSize = 13.5.sp, lineHeight = 20.sp, color = LologColors.Muted)
            } else {
                Text(
                    c.text, fontSize = 13.5.sp, lineHeight = 20.sp, color = LologColors.Ink,
                    modifier = Modifier.widthIn(max = commentMaxWidth()).clip(commentBubble).background(LologColors.Canvas)
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
        }
    }
}

/** 내 댓글(오른쪽): 사진·이름 없이 연한 말풍선, 시간은 말풍선 왼쪽 아래 */
@Composable
private fun MyCommentRow(c: RoomMessage.Comment) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.Bottom) {
        Text(relativeTime(c.at), fontSize = 11.sp, color = LologColors.Muted)
        Spacer(Modifier.width(6.dp))
        Text(
            c.text, fontSize = 13.5.sp, lineHeight = 20.sp, color = LologColors.Ink,
            modifier = Modifier.widthIn(max = commentMaxWidth()).clip(commentBubble).background(LologColors.AccentSoft)
                .padding(horizontal = 12.dp, vertical = 7.dp),
        )
    }
}
