package com.lolog.app.ui.screens.rooms

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lolog.app.BuildConfig
import com.lolog.app.data.model.CharacterType
import com.lolog.app.data.model.RoomMember
import com.lolog.app.data.model.SpeakerCharacter
import com.lolog.app.ui.components.SpeakerAvatar
import com.lolog.app.ui.theme.LologColors

/*
 * 친구 방 화면들이 같이 쓰는 작은 조각.
 * 시안의 얼굴 그림은 자리 표시예요. 멤버의 프로필 캐릭터가 있으면 그 그림, 없으면 이름 첫 글자 동그라미(SpeakerAvatar).
 */

/** 친구 방은 명세·서버가 아직 없어서 목업(USE_MOCK=true)에서만 보여요 */
object Features {
    val ROOMS = BuildConfig.USE_MOCK
}

/** 멤버 그림을 화자 그림 모양으로. 말풍선(ChatBubble)에 넘길 때 써요 */
internal fun RoomMember.character(): SpeakerCharacter? =
    thumbnailUrl?.let { SpeakerCharacter(CharacterType.PROFILE, userId.toString(), nickname, it) }

/** 방 멤버 동그라미. 색은 방 안 멤버 순서([index])대로 */
@Composable
internal fun MemberAvatar(member: RoomMember, index: Int, size: Dp = 22.dp) {
    val c = member.character()
    SpeakerAvatar(member.nickname, index, size = size, thumbnailUrl = c?.thumbnailUrl, cacheKey = c?.cacheKey)
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
