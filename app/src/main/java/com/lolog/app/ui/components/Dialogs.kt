package com.lolog.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.lolog.app.ui.theme.LologColors

/** 확인 창 (시안 18·22·23번, 카드 지우기·나가기 확인). 버튼은 [취소][확인] 가로로 놓아요. */
@Composable
fun LologDialog(
    title: String,
    body: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissText: String = "취소",
    danger: Boolean = false,
    loading: Boolean = false,
    eyebrow: (@Composable () -> Unit)? = null,
    /** 뒤로가기·창 밖 누르기로 닫을 때. 안 주면 [onDismiss](보조 버튼)와 같아요 */
    onDismissRequest: () -> Unit = onDismiss,
) {
    Dialog(onDismissRequest = onDismissRequest) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(LologColors.Paper, com.lolog.app.ui.theme.LologShape.dialog)
                .padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            eyebrow?.invoke()
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, lineHeight = 24.sp)
            Text(body, fontSize = 14.sp, color = LologColors.Muted, lineHeight = 21.sp)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 확정 디자인: [취소]는 흰 바탕 + 테두리 보조 버튼
                GhostButton(dismissText, onDismiss, Modifier.weight(1f))
                PrimaryButton(confirmText, onConfirm, danger = danger, loading = loading, modifier = Modifier.weight(1f))
            }
        }
    }
}

/**
 * 버튼 하나짜리 창. [LologDialog]와 같은 모양이에요(쇼츠 릴스 공유 시안 3·5번).
 * @param primary true면 코랄 버튼([확인]), false면 흰 바탕 보조 버튼([취소])
 * @param content 본문 아래에 넣을 것(진행 막대 등)
 */
@Composable
fun LologNoticeDialog(
    title: String,
    body: String,
    buttonText: String,
    onButton: () -> Unit,
    primary: Boolean = true,
    /** 뒤로가기·창 밖 누르기로 닫을 때. 안 주면 [onButton]과 같아요 */
    onDismissRequest: () -> Unit = onButton,
    content: (@Composable () -> Unit)? = null,
) {
    Dialog(onDismissRequest = onDismissRequest) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(LologColors.Paper, com.lolog.app.ui.theme.LologShape.dialog)
                .padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, lineHeight = 24.sp)
            Text(body, fontSize = 14.sp, color = LologColors.Muted, lineHeight = 21.sp)
            content?.invoke()
            Spacer(Modifier.height(8.dp))
            if (primary) PrimaryButton(buttonText, onButton) else GhostButton(buttonText, onButton)
        }
    }
}

/** 화면 아래 검은 토스트 (시안 14·19번) */
@Composable
fun LologSnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(state, modifier) { data ->
        Snackbar(
            snackbarData = data,
            containerColor = LologColors.Ink,
            contentColor = LologColors.Paper,
            shape = RoundedCornerShape(16.dp),
            actionColor = LologColors.Highlight,
        )
    }
}

