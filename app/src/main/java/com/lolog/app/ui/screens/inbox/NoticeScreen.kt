package com.lolog.app.ui.screens.inbox

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lolog.app.ui.components.CenterMessage
import com.lolog.app.ui.components.LologTopBar
import com.lolog.app.ui.theme.LologColors
import com.lolog.app.util.dayLabel

// ── 공지사항 자세히 (시안 8번) ──────────────────────

/** 알림함 목록에서 받은 값으로 그려요(따로 조회 없음). [title]이 null이면 넘겨받은 값이 없는 것 */
@Composable
fun NoticeScreen(title: String?, body: String, createdAt: String?, onBack: () -> Unit) {
    Scaffold(containerColor = LologColors.Paper, topBar = { LologTopBar("공지사항", onBack) }) { padding ->
        if (title == null) {
            CenterMessage("공지사항을 찾지 못했어요", "알림함에서 다시 열어 주세요", Modifier.fillMaxSize().padding(padding))
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(padding)) {
            HorizontalDivider(thickness = 1.dp, color = LologColors.Line)
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp)) {
                Text(dayLabel(createdAt), fontSize = 12.sp, color = LologColors.Muted)
                Spacer(Modifier.height(6.dp))
                Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = LologColors.Ink, lineHeight = 28.sp)
                Spacer(Modifier.height(16.dp))
                // 줄바꿈 그대로
                Text(body, fontSize = 15.sp, color = LologColors.Ink, lineHeight = 24.sp)
            }
        }
    }
}
