package com.lolog.app.ui.components

import com.lolog.app.data.model.COMIC_HEIGHT
import com.lolog.app.data.model.COMIC_WIDTH
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.lolog.app.ui.theme.LologColors

private val panelColors = listOf(Color(0xFFFFF4DB), Color(0xFFE7ECFF), Color(0xFFE1F5F1), Color(0xFFFFE9E6))

/**
 * 완성된 4컷 한 장. 컷 배치는 세로 4칸이라 이미지가 세로로 길어요(명세: 800×3152).
 * 서버가 준 width/height 비율(ratio)만큼 자리를 먼저 잡고 회색으로 채워 둬요. 그림이 들어와도 화면이 출렁이지 않아요.
 * 목업 주소(mock://)면 대사로 임시 그림을 그려요.
 * small = true(목록 썸네일)면 1컷 정사각형만 보여요. 명세엔 썸네일이 없어서 imageUrl 위쪽 첫 칸을 잘라 보여요.
 */
@Composable
fun ComicImage(
    imageUrl: String?,
    mockLines: List<String>,
    modifier: Modifier = Modifier,
    small: Boolean = false,
    ratio: Float = COMIC_RATIO,
    /** 그림을 못 받았을 때(서명 주소 10분 만료 → 403). 화면이 만화를 다시 조회해 새 주소를 받아요 */
    onLoadError: () -> Unit = {},
) {
    val shape = RoundedCornerShape(if (small) 8.dp else 16.dp)
    val boxRatio = if (small) 1f else ratio
    if (imageUrl != null && !imageUrl.startsWith("mock://")) {
        Box(modifier.aspectRatio(boxRatio).clip(shape).background(LologColors.Line), contentAlignment = Alignment.Center) {
            if (!small) Text("불러오는 중…", fontSize = 13.sp, color = LologColors.Muted)
            AsyncImage(
                model = imageUrl,
                contentDescription = "4컷만화",
                contentScale = if (small) ContentScale.Crop else ContentScale.FillWidth,
                alignment = Alignment.TopCenter,
                onError = { onLoadError() },
                modifier = Modifier.fillMaxSize(),
            )
        }
        return
    }
    Column(
        modifier.aspectRatio(boxRatio).clip(shape),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (i in 0 until if (small) 1 else 4) {
            Box(Modifier.weight(1f).fillMaxWidth().background(panelColors[i])) {
                Figure(Modifier.align(Alignment.BottomCenter))
                if (!small) {
                    mockLines.getOrNull(i)?.let { line ->
                        Text(
                            line,
                            fontSize = 13.sp, fontWeight = FontWeight.Medium, lineHeight = 17.sp,
                            maxLines = 2,
                            modifier = Modifier
                                .align(if (i % 2 == 0) Alignment.TopStart else Alignment.TopEnd)
                                .padding(10.dp)
                                .fillMaxWidth(0.7f)
                                .shadow(1.dp, RoundedCornerShape(12.dp))
                                .background(LologColors.Paper, RoundedCornerShape(12.dp))
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                    Text(
                        "${i + 1}", fontSize = 10.sp, color = LologColors.Paper,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                            .background(Color(0x73000000), RoundedCornerShape(3.dp)).padding(horizontal = 5.dp),
                    )
                }
            }
        }
    }
}

/** 세로 4칸 한 장의 가로:세로(명세 800×3152) */
const val COMIC_RATIO = COMIC_WIDTH.toFloat() / COMIC_HEIGHT

/** 임시 그림의 사람 실루엣 */
@Composable
private fun Figure(modifier: Modifier) {
    Column(modifier.fillMaxHeight(0.55f).fillMaxWidth(0.4f), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth(0.5f).aspectRatio(1f).border(1.8.dp, LologColors.Ink, RoundedCornerShape(50)))
        Box(
            Modifier.fillMaxWidth().weight(1f).padding(top = 3.dp)
                .border(1.8.dp, LologColors.Ink, RoundedCornerShape(topStart = 50.dp, topEnd = 50.dp)),
        )
    }
}

/** 대기·만드는 중·실패 자리 (시안 15번 pending) */
@Composable
fun ComicPlaceholder(text: String, failed: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(if (failed) Color(0xFFFFF7F8) else LologColors.Paper)
            .border(2.dp, if (failed) LologColors.RecSoft else LologColors.Line, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (!failed) { TypingDots(dot = 7.dp); Spacer(Modifier.height(8.dp)) }
            Text(text, fontSize = 12.sp, color = if (failed) LologColors.Rec else LologColors.Muted)
        }
    }
}
