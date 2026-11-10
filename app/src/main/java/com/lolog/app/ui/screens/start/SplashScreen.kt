package com.lolog.app.ui.screens.start

import com.lolog.app.R
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
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
// 흰 바탕 + 아래에서 3D 얼굴들이 흔들흔들 떠올라요. 가운데 로고는 펜으로 그리듯 나타나요(4초, 11/8).

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

private const val SPLASH_MS = 4_000L

/** 얼굴 움직임 빠르기. 처음 1.6초 판을 4초로 늘리면서 같은 비율(1.6/4.0)로 천천히 */
private const val FACE_SLOW = 0.4f
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

    BoxWithConstraints(Modifier.fillMaxSize().background(LologColors.Paper)) {
        val w = maxWidth
        val h = maxHeight
        val density = LocalDensity.current
        val hPx = with(density) { h.toPx() }
        val logoTop = hPx * 0.5f - with(density) { 70.dp.toPx() }
        val logoBottom = hPx * 0.5f + with(density) { 70.dp.toPx() }

        // 얼굴들 (글자 뒤)
        val ft = (t * FACE_SLOW).toLong()
        faces.forEachIndexed { j, f ->
            val ageMs = ft - f.startMs
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

        // 가운데 로고(코랄 마크 + LOLog 글자, 얼굴 위). 펜으로 그리듯 하나씩 나타나요
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            DrawnLogoMark(t, Modifier.width(132.dp))
            Spacer(Modifier.height(14.dp))
            // LOLog 글자: 왼쪽부터 써 내려가요
            val word = phase(t, 2_500, 3_300)
            Image(
                painterResource(R.drawable.logo_word), contentDescription = "LOLog",
                modifier = Modifier.height(40.dp).drawWithContent {
                    clipRect(right = size.width * word) { this@drawWithContent.drawContent() }
                },
            )
            Spacer(Modifier.height(14.dp))
            Text(
                "흘려보낸 대화를 다시 꺼내 보는 곳", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF8B95A1),
                modifier = Modifier.alpha(phase(t, 3_100, 3_500)),
            )
        }

        Text(
            "대화는 폰과 LOLog 서버에서만 처리돼요",
            fontSize = 12.sp, color = Color(0xFFA4ABB5),
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 28.dp),
        )
    }
}

/** start~end(ms) 사이에서 0 → 1, 부드럽게 */
private fun phase(t: Long, start: Int, end: Int): Float =
    FastOutSlowInEasing.transform(((t - start).toFloat() / (end - start)).coerceIn(0f, 1f))

/**
 * 로고 마크를 펜으로 그리듯 보여 줘요. 마크를 조각 4개로 나눈 그림을 겹쳐 두고 차례로 드러내요.
 *  ① 말풍선: 꼬리에서 시작해 시계 방향으로 한 바퀴  ② 네모 틀: 왼쪽 위에서 시계 방향으로 한 바퀴
 *  ③ 파란 선: 왼쪽에서 오른쪽으로  ④ 재생 삼각형: 톡 커지며 나타남
 * 조각 그림 res/drawable-nodpi/logo_mark_{bubble,frame,wave,play}.png 은 logo_mark.png를 잘라 만든 거예요(합치면 같은 그림).
 */
@Composable
private fun DrawnLogoMark(t: Long, modifier: Modifier) {
    val bubble = phase(t, 200, 1_300)
    val frame = phase(t, 1_000, 2_100)
    val wave = phase(t, 1_900, 2_400)
    val play = phase(t, 2_300, 2_700)
    Box(modifier.aspectRatio(480f / 415f)) {
        // 그림 안 위치는 0~1 비율(가로, 세로)
        SweepLayer(R.drawable.logo_mark_bubble, bubble, cx = 0.26f, cy = 0.55f, startDeg = 100f)
        SweepLayer(R.drawable.logo_mark_frame, frame, cx = 0.62f, cy = 0.47f, startDeg = -140f)
        Image(
            painterResource(R.drawable.logo_mark_wave), null,
            Modifier.fillMaxSize().drawWithContent {
                clipRect(right = size.width * (0.43f + 0.22f * wave)) { this@drawWithContent.drawContent() }
            },
        )
        Image(
            painterResource(R.drawable.logo_mark_play), null,
            Modifier.fillMaxSize().graphicsLayer {
                val k = 0.6f + 0.4f * play
                scaleX = k; scaleY = k; alpha = play
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.71f, 0.49f)
            },
        )
    }
}

/** 가운데(cx, cy)에서 부채꼴을 넓혀 가며 그림을 드러내요. p: 0 → 1 */
@Composable
private fun SweepLayer(res: Int, p: Float, cx: Float, cy: Float, startDeg: Float) {
    Image(
        painterResource(res), null,
        Modifier.fillMaxSize().drawWithContent {
            when {
                p <= 0f -> Unit
                p >= 1f -> drawContent()
                else -> {
                    val c = Offset(size.width * cx, size.height * cy)
                    val r = size.maxDimension * 1.5f
                    val path = Path().apply {
                        moveTo(c.x, c.y)
                        arcTo(Rect(c, r), startDeg, 360f * p, forceMoveTo = false)
                        close()
                    }
                    clipPath(path) { this@drawWithContent.drawContent() }
                }
            }
        },
    )
}
