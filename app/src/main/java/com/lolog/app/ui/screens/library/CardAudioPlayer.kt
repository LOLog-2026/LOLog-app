package com.lolog.app.ui.screens.library

import android.media.MediaPlayer
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.lolog.app.data.model.AudioStatus
import com.lolog.app.data.model.CardDetail
import com.lolog.app.recording.RecordingState
import com.lolog.app.ui.components.softCard
import com.lolog.app.ui.navigation.LocalContainer
import com.lolog.app.ui.theme.LologColors
import com.lolog.app.util.ReplayFiles
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * 순간 다시 듣기 (replay.md 1~3장). 카드 자세히의 종류 표시 아래, 대사 위에 둬요.
 *  - READY: 재생·멈춤 버튼 + 재생 바(끌어서 위치 옮기기) + 지난 시간 / 전체 길이(audio.durationMs)
 *  - PENDING: 안내 문구 한 줄
 *  - NONE: 아무것도 안 보여 줘요
 *  - 처음 누를 때 파일을 통째로 받아 임시 폴더에 두고 MediaPlayer로 재생해요. 화면을 나가면 멈추고 파일을 지워요
 *  - 다른 앱으로 가면(ON_STOP) 멈춰요. 백그라운드에서는 재생하지 않아요
 *  - 녹음 중에는 재생 버튼을 꺼 둬요(스피커 소리가 지금 녹음에 들어가요)
 */
@Composable
fun CardAudioSection(card: CardDetail, freshUrl: suspend () -> String?, modifier: Modifier = Modifier) {
    when (card.audio.status) {
        AudioStatus.NONE -> Unit
        AudioStatus.PENDING -> Row(modifier.fillMaxWidth().softCard().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Headphones, null, tint = LologColors.Muted, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            // 문구는 replay.md 1장 그대로
            Text("음성이 아직 올라오지 않았어요. 올라오면 여기서 들을 수 있어요", fontSize = 12.5.sp, color = LologColors.Muted)
        }
        AudioStatus.READY -> AudioPlayer(card, freshUrl, modifier)
    }
}

private enum class Phase { IDLE, LOADING, READY, ERROR }

@Composable
private fun AudioPlayer(card: CardDetail, freshUrl: suspend () -> String?, modifier: Modifier) {
    val context = LocalContext.current
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val durationMs = card.audio.durationMs ?: 0L
    val recorderState by container.recorder.state.collectAsState()
    val recording = recorderState is RecordingState.Recording || recorderState is RecordingState.Uploading

    var phase by remember { mutableStateOf(Phase.IDLE) }
    var playing by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf<Float?>(null) } // 끄는 중인 위치(0~1)
    val player = remember { mutableStateOf<MediaPlayer?>(null) }
    val file = remember { mutableStateOf<File?>(null) }

    fun pause() {
        player.value?.takeIf { it.isPlaying }?.pause()
        playing = false
    }

    fun start() {
        val mp = player.value ?: return
        mp.start()
        playing = true
    }

    // 받은 파일로 MediaPlayer를 만들어요. 로컬 파일이라 바로 준비돼요
    fun open(f: File): Boolean = runCatching {
        val mp = MediaPlayer()
        try {
            mp.setDataSource(f.path)
            mp.prepare()
        } catch (e: Exception) {
            mp.release()
            throw e
        }
        mp.setOnCompletionListener { playing = false; positionMs = 0; it.seekTo(0) }
        player.value = mp
        if (positionMs > 0) mp.seekTo(positionMs.toInt())
    }.onFailure { Log.w("LologReplay", "재생을 준비하지 못했어요", it) }.isSuccess

    fun toggle() {
        when {
            playing -> pause()
            player.value != null -> start()
            phase == Phase.LOADING -> Unit
            else -> scope.launch {
                phase = Phase.LOADING
                val url = card.audio.url
                val result = if (url == null) ReplayFiles.Result.Failed
                else ReplayFiles.download(context, card.id, url, durationMs, container.network, freshUrl)
                val ok = result is ReplayFiles.Result.Ok && open(result.file).also { file.value = result.file }
                phase = if (ok) Phase.READY else Phase.ERROR
                if (ok && !recording) start()
            }
        }
    }

    // 재생 중에는 0.2초마다 위치를 읽어 재생 바를 옮겨요
    LaunchedEffect(playing) {
        while (playing) {
            player.value?.let { positionMs = it.currentPosition.toLong().coerceAtMost(durationMs) }
            delay(200)
        }
    }
    // 녹음이 시작되면 멈춰요
    LaunchedEffect(recording) { if (recording) pause() }
    // 다른 앱으로 가면 멈춰요
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) pause() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    // 카드 화면을 나가면 놓고 임시 파일을 지워요
    DisposableEffect(Unit) {
        onDispose {
            player.value?.release()
            player.value = null
            file.value?.delete()
        }
    }

    Column(modifier.fillMaxWidth().softCard().padding(start = 10.dp, end = 14.dp, top = 8.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val enabled = !recording
            Box(
                Modifier.size(40.dp).clip(CircleShape)
                    .background(if (enabled) LologColors.Accent else LologColors.Line)
                    .clickable(enabled = enabled) { toggle() },
                contentAlignment = Alignment.Center,
            ) {
                if (phase == Phase.LOADING) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = LologColors.Paper, strokeWidth = 2.dp)
                } else {
                    Icon(
                        if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (playing) "멈춤" else "다시 듣기",
                        tint = LologColors.Paper, modifier = Modifier.size(22.dp),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                val fraction = dragging ?: if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
                Slider(
                    value = fraction.coerceIn(0f, 1f),
                    onValueChange = { dragging = it },
                    onValueChangeFinished = {
                        val to = ((dragging ?: 0f) * durationMs).toLong()
                        dragging = null
                        positionMs = to
                        // 아직 안 받았으면 위치만 기억했다가 받은 뒤 그 자리부터 재생해요
                        player.value?.seekTo(to.toInt())
                    },
                    enabled = !recording,
                    colors = SliderDefaults.colors(
                        thumbColor = LologColors.Accent, activeTrackColor = LologColors.Accent, inactiveTrackColor = LologColors.Line,
                    ),
                    modifier = Modifier.height(28.dp),
                )
                Row {
                    val shown = dragging?.let { (it * durationMs).toLong() } ?: positionMs
                    Text(mmss(shown), fontSize = 11.sp, color = LologColors.Muted, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    Text(mmss(durationMs), fontSize = 11.sp, color = LologColors.Muted)
                }
            }
        }
        when {
            recording -> Note("녹음 중에는 들을 수 없어요")
            phase == Phase.ERROR -> Note("음성을 불러오지 못했어요. 다시 눌러 주세요", error = true)
        }
    }
}

@Composable
private fun Note(text: String, error: Boolean = false) {
    Text(
        text, fontSize = 11.sp, color = if (error) LologColors.Rec else LologColors.Muted,
        modifier = Modifier.padding(start = 48.dp, top = 2.dp),
    )
}

/** 1:05처럼. 한 시간이 넘을 일은 없어요(카드 구간은 길어야 몇 분) */
private fun mmss(ms: Long): String {
    val sec = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(sec / 60, sec % 60)
}
