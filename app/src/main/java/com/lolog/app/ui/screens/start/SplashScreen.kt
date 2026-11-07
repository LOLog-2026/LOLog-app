package com.lolog.app.ui.screens.start

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lolog.app.ui.theme.LologColors
import kotlinx.coroutines.delay
import kotlin.math.sin

// ── 01 첫 화면(스플래시) ─────────────────────────────
// 사진: mockups/첫화면-진짜3D얼굴-시안.png
// 흰 바탕 가운데 노란 LOLog + 아래에서 3D 얼굴들이 흔들흔들 떠올라요(1.6초).

/** 첫 화면에 떠오르는 얼굴 하나 */
private data class FloatFace(
    val kind: String,   // cool·laugh·love·wow·tongue·joy
    val size: Dp,
    val x: Float,       // 화면 가로 위치(0~1, 얼굴 가운데)
    val startMs: Int,   // 이때 화면 아래에서 나타나요 (음수면 이미 올라오는 중)
    val spin: Boolean = false, // true면 한 바퀴 빙글, false면 고개만 좌우로
)

private val faces = listOf(
    FloatFace("cool", 120.dp, 0.26f, -450, spin = true),
    FloatFace("laugh", 100.dp, 0.74f, -250),
    FloatFace("love", 110.dp, 0.30f, 50),
    FloatFace("wow", 96.dp, 0.76f, 300),
    FloatFace("tongue", 104.dp, 0.22f, 600),
    FloatFace("joy", 118.dp, 0.72f, 850),
    FloatFace("cool", 94.dp, 0.40f, 1100),
)

private const val SPLASH_MS = 1_600L
private const val RISE_DP_PER_SEC = 430f

private val LogoYellow = Color(0xFFFFD028)
private val LogoEdge = Color(0xFFF0A800)
private val LogoDepth = listOf(0xFFF2A900, 0xFFE89E00, 0xFFDC9200, 0xFFCF8600, 0xFFC27A00).map { Color(it) }

@Composable
fun SplashScreen(onDone: () -> Unit) {
    LaunchedEffect(Unit) { delay(SPLASH_MS); onDone() }

    // 지난 시간(ms). 매 프레임 갱신
    var t by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val start = withFrameMillis { it }
        while (true) withFrameMillis { t = it - start }
    }
    // 글자는 0~0.3초에 90% → 100%
    val logoScale = 0.92f + 0.08f * (t / 300f).coerceIn(0f, 1f)

    BoxWithConstraints(Modifier.fillMaxSize().background(LologColors.Paper)) {
        val w = maxWidth
        val h = maxHeight
        val density = LocalDensity.current
        val hPx = with(density) { h.toPx() }
        val logoTop = hPx * 0.5f - with(density) { 70.dp.toPx() }
        val logoBottom = hPx * 0.5f + with(density) { 70.dp.toPx() }

        // 얼굴들 (글자 뒤)
        faces.forEachIndexed { j, f ->
            val ageMs = t - f.startMs
            if (ageMs < 0) return@forEachIndexed
            val a = ageMs / 1000f
            val sizePx = with(density) { f.size.toPx() }
            val centerY = hPx + sizePx / 2 - a * with(density) { RISE_DP_PER_SEC.dp.toPx() }
            if (centerY < -sizePx) return@forEachIndexed
            // 위쪽에서 서서히 사라지고, 글자 근처를 지날 땐 옅어져요
            var alpha = if (centerY < sizePx) (centerY / sizePx).coerceIn(0f, 1f) else 1f
            if (centerY in logoTop..logoBottom) alpha *= 0.3f
            if (alpha <= 0.02f) return@forEachIndexed

            val dx = sin(a * 5.5f + j) * 18f
            val tilt = sin(a * 4.5f + j * 2) * 12f
            val yaw = if (f.spin) ((a * 360f).toInt() % 360) else (sin(a * 4f + j) * 30f).toInt()
            val mouth = ((ageMs / 300) % 2).toInt() // 입 뻥긋·윙크 등은 0.3초마다 바뀜

            Box(
                Modifier
                    .offset(x = w * f.x - f.size / 2 + dx.dp, y = with(density) { (centerY - sizePx / 2).toDp() })
                    .size(f.size)
                    .alpha(alpha),
            ) {
                // 바닥 그림자
                Box(
                    Modifier.align(Alignment.BottomCenter).offset(y = 4.dp)
                        .size(width = f.size * 0.6f, height = 10.dp)
                        .background(
                            Brush.radialGradient(listOf(Color(0x30966400), Color.Transparent)),
                            shape = androidx.compose.foundation.shape.CircleShape,
                        ),
                )
                Image(
                    painter = painterResource(SplashFaceRes.get(f.kind, mouth, yaw)),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().rotate(tilt),
                )
            }
        }

        // 가운데 노란 LOLog (얼굴 위)
        Column(
            Modifier.align(Alignment.Center).graphicsLayer { scaleX = logoScale; scaleY = logoScale },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box {
                val base = TextStyle(fontSize = 66.sp, fontWeight = FontWeight.Black, letterSpacing = (-2).sp)
                // 아래로 두께감(5겹) → 테두리 → 노란 글자
                LogoDepth.forEachIndexed { i, c ->
                    Text("LOLog", style = base.copy(color = c), modifier = Modifier.offset(y = (i + 1).dp))
                }
                Text("LOLog", style = base.copy(color = LogoEdge, drawStyle = Stroke(width = with(density) { 3.dp.toPx() })))
                Text("LOLog", style = base.copy(color = LogoYellow))
            }
            Spacer(Modifier.height(12.dp))
            Text("흘려보낸 대화를 다시 꺼내 보는 곳", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF8B95A1))
        }

        Text(
            "대화는 폰과 LOLog 서버에서만 처리돼요",
            fontSize = 12.sp, color = Color(0xFFA4ABB5),
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 28.dp),
        )
    }
}
