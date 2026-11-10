package com.lolog.app.ui.screens.friends

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.lolog.app.ui.theme.LologColors

/*
 * 친구 화면들이 같이 쓰는 작은 조각.
 * 시안의 얼굴 그림은 예시예요. 친구의 프로필 캐릭터(character.thumbnailUrl)가 있으면 그 그림, 없으면 기본 사람 아이콘.
 */

/** 친구 동그라미 그림 */
@Composable
internal fun FriendAvatar(thumbnailUrl: String?, size: Dp = 44.dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(LologColors.Canvas),
        contentAlignment = Alignment.Center,
    ) {
        if (thumbnailUrl != null) {
            AsyncImage(
                model = thumbnailUrl, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.size(size),
            )
        } else {
            Icon(Icons.Outlined.Person, null, tint = LologColors.Muted, modifier = Modifier.size(size * 0.55f))
        }
    }
}


/** 친구 코드에 넣을 수 있는 글자만 남겨요: 영문·숫자, 소문자는 대문자로, 8자까지 */
internal fun cleanFriendCode(raw: String, max: Int): String =
    raw.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }.uppercase().take(max)
