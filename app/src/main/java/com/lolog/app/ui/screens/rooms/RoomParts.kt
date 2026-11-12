package com.lolog.app.ui.screens.rooms

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lolog.app.data.model.CharacterType
import com.lolog.app.data.model.RoomMember
import com.lolog.app.data.model.SpeakerCharacter
import com.lolog.app.ui.components.SpeakerAvatar
import com.lolog.app.ui.theme.LologColors

/*
 * 친구 방 화면들이 같이 쓰는 작은 조각.
 * 시안의 얼굴 그림은 자리 표시예요. 멤버의 프로필 캐릭터가 있으면 그 그림, 없으면 이름 첫 글자 동그라미(SpeakerAvatar).
 */

/** 친구 방 화면 켜기. 목업과 실서버(lolog-api #51) 모두 켜요 */
object Features {
    val ROOMS = true
}

/** 멤버 그림을 화자 그림 모양으로. 말풍선(ChatBubble)에 넘길 때 써요 */
internal fun RoomMember.character(): SpeakerCharacter? =
    thumbnailUrl?.let { SpeakerCharacter(CharacterType.PROFILE, userId.toString(), nickname, it) }

/**
 * 방 멤버 동그라미. 색은 방 안 멤버 순서([index])대로.
 * 나간 멤버는 회색 동그라미에 "?", 정지된 계정은 얼굴을 회색으로
 */
@Composable
internal fun MemberAvatar(member: RoomMember, index: Int, size: Dp = 22.dp) {
    if (member.left) {
        GrayDot("?", size)
        return
    }
    val c = member.character()
    Box(if (member.suspended) Modifier.grayscale() else Modifier) {
        SpeakerAvatar(member.nickname, index, size = size, thumbnailUrl = c?.thumbnailUrl, cacheKey = c?.cacheKey)
    }
}

/** 회색 동그라미에 글자 하나(나간 멤버 "?", 숨긴 글 "·") */
@Composable
internal fun GrayDot(label: String, size: Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(LologColors.Canvas), contentAlignment = Alignment.Center) {
        Text(label, fontSize = (size.value * 0.45f).sp, fontWeight = FontWeight.Bold, color = LologColors.Muted)
    }
}

/** 흑백으로 그려요(정지된 계정의 얼굴) */
internal fun Modifier.grayscale(): Modifier = drawWithContent {
    val paint = Paint().apply { colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) }
    drawIntoCanvas { canvas ->
        canvas.saveLayer(Rect(Offset.Zero, size), paint)
        drawContent()
        canvas.restore()
    }
}

/** 멤버 얼굴을 겹쳐서(최대 [max]개). 나는 빼고 다른 멤버부터 보여요 */
@Composable
internal fun AvatarStack(members: List<RoomMember>, size: Dp = 26.dp, max: Int = 3) {
    val shown = members.withIndex().filter { !it.value.isMe }.take(max)
    Box(Modifier.width(size + (size * 0.62f) * (shown.size - 1).coerceAtLeast(0)).height(size)) {
        shown.forEachIndexed { i, (index, m) ->
            Box(
                Modifier
                    .offset(x = size * 0.62f * i)
                    .border(1.5.dp, LologColors.Paper, CircleShape)
                    .padding(1.dp),
                contentAlignment = Alignment.Center,
            ) { MemberAvatar(m, index, size - 2.dp) }
        }
    }
}
