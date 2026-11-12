package com.lolog.app.ui.badges

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.lolog.app.data.model.BadgeKind
import com.lolog.app.data.model.PushPayload
import com.lolog.app.data.model.PushType
import com.lolog.app.ui.navigation.LocalContainer
import com.lolog.app.ui.theme.LologColors

/** 그 화면을 열었으면 새 소식 점을 꺼요(POST /api/me/badges/{kind}/read, inbox.md 4장) */
@Composable
fun SeenBadge(kind: BadgeKind) {
    val badges = LocalContainer.current.badges
    LaunchedEffect(kind) { badges.seen(kind) }
}

/**
 * 앱이 켜져 있을 때 [types] 푸시가 오면 [action]을 불러요(보고 있는 화면 새로 고치기, #67 7번).
 * types가 비면 모든 푸시.
 */
@Composable
fun OnPush(vararg types: PushType, action: suspend (PushPayload) -> Unit) {
    val repo = LocalContainer.current.repository
    val latest by rememberUpdatedState(action)
    val wanted = types.toSet()
    LaunchedEffect(Unit) {
        repo.pushEvents.collect { p -> if (wanted.isEmpty() || p.type in wanted) latest(p) }
    }
}

/** 새 소식 점: 종 버튼과 같은 7dp 코랄 동그라미 */
@Composable
fun NewDot(modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Box(modifier.size(7.dp).clip(CircleShape).background(LologColors.Accent))
}
