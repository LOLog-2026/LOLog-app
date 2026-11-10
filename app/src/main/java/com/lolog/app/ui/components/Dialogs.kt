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
) {
    Dialog(onDismissRequest = onDismiss) {
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
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f).height(46.dp),
                    shape = com.lolog.app.ui.theme.LologShape.pill,
                    colors = ButtonDefaults.buttonColors(containerColor = LologColors.Canvas, contentColor = LologColors.Ink),
                ) { Text(dismissText, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                PrimaryButton(confirmText, onConfirm, danger = danger, loading = loading, modifier = Modifier.weight(1f))
            }
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

