package com.lolog.app.ui.components

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.lolog.app.recording.RecordingState
import com.lolog.app.ui.navigation.LocalContainer
import com.lolog.app.ui.theme.LologColors
import com.lolog.app.ui.theme.LologShape
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/*
 * 기본 화자 목소리 미리 듣기 (characters.md FR-CHAR-03, docs PR #59)
 *  - 기본 화자 목록의 voiceUrl(10분 서명 주소)을 누를 때 MediaPlayer로 받아 재생해요. voiceUrl이 null이면 버튼이 없어요
 *  - 한 화면에 VoicePlayer 하나: 다른 목소리를 누르면 앞의 것은 멈춰요
 *  - 화면(시트)을 떠나거나 다른 앱으로 가면(ON_STOP) 멈춰요
 *  - 녹음 중(Recording·Uploading)에는 버튼을 회색으로 꺼요. 스피커 소리가 녹음에 들어가요(CardAudioPlayer와 같은 규칙)
 *  - 실패하면 서명 주소가 지났을 수 있어 freshUrl(기본 화자 목록 다시 받기)로 한 번 더 해 보고, 또 실패하면 onError
 */

/** 녹음 중 안내(시안 4). 버튼 아래에 한 번만 보여 줘요 */
const val VOICE_RECORDING_NOTE = "녹음 중에는 목소리를 들을 수 없어요. 스피커 소리가 녹음에 들어가요."

/** 목소리를 불러오지 못했을 때 스낵바 문구 */
const val VOICE_LOAD_FAILED = "목소리를 불러오지 못했어요"

/** 미리 듣기 인사 문장(목소리 파일이 읽는 문장) */
const val VOICE_GREETING = "\"안녕하세요, 반가워요\""

/** 한 화면에서 목소리 하나만 재생해요. [rememberVoicePlayer]로 만들어요 */
@Stable
class VoicePlayer internal constructor(private val scope: CoroutineScope, private val onError: () -> Unit) {
    /** 지금 재생 중(받는 중 포함)인 기본 화자 id */
    var playingId by mutableStateOf<String?>(null)
        private set

    /** 받는 중(아직 소리가 안 남) */
    var loading by mutableStateOf(false)
        private set

    /** 녹음 중이면 true. 버튼이 꺼져요 */
    var recording by mutableStateOf(false)
        internal set

    private var player: MediaPlayer? = null

    /** 누를 때: 같은 것이면 멈추고, 아니면 앞의 것을 멈추고 새로 재생해요 */
    fun toggle(id: String, url: String, freshUrl: suspend () -> String?) {
        if (recording) return
        if (playingId == id) { stop(); return }
        stop()
        playingId = id
        play(id, url, freshUrl, retried = false)
    }

    fun stop() {
        player?.let { mp ->
            mp.setOnErrorListener(null)
            mp.setOnCompletionListener(null)
            runCatching { mp.stop() }
            mp.release()
        }
        player = null
        playingId = null
        loading = false
    }

    private fun play(id: String, url: String, freshUrl: suspend () -> String?, retried: Boolean) {
        loading = true
        val mp = MediaPlayer()
        player = mp
        fun failed(cause: Any?) {
            if (player !== mp) return // 이미 멈췄거나 다른 걸 재생 중
            Log.w("LologVoice", "목소리를 재생하지 못했어요: $cause")
            mp.release()
            player = null
            if (retried) { playingId = null; loading = false; onError(); return }
            // 서명 주소(10분)가 지났을 수 있어요. 목록을 다시 받아 한 번 더
            scope.launch {
                val fresh = runCatching { freshUrl() }.getOrNull()
                if (playingId != id) return@launch
                if (fresh == null) { playingId = null; loading = false; onError() } else play(id, fresh, freshUrl, retried = true)
            }
        }
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            mp.setDataSource(url)
            mp.setOnPreparedListener {
                if (player !== it) return@setOnPreparedListener
                loading = false
                if (recording) stop() else it.start()
            }
            mp.setOnCompletionListener { if (player === it) stop() }
            mp.setOnErrorListener { _, what, extra -> failed("what=$what extra=$extra"); true }
            mp.prepareAsync()
        } catch (e: Exception) {
            failed(e)
        }
    }
}

/**
 * 화면 하나에 하나. 녹음이 시작되거나 다른 앱으로 가거나 이 화면이 사라지면 멈춰요.
 * @param onError 다시 해 봐도 못 불러왔을 때(스낵바 "목소리를 불러오지 못했어요")
 */
@Composable
fun rememberVoicePlayer(onError: () -> Unit): VoicePlayer {
    val scope = rememberCoroutineScope()
    val latestError by rememberUpdatedState(onError)
    val player = remember { VoicePlayer(scope) { latestError() } }

    val recorderState by LocalContainer.current.recorder.state.collectAsState()
    val recording = recorderState is RecordingState.Recording || recorderState is RecordingState.Uploading
    LaunchedEffect(recording) {
        player.recording = recording
        if (recording) player.stop()
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) player.stop() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    DisposableEffect(Unit) { onDispose { player.stop() } }
    return player
}

/**
 * 들어 보기 버튼. [url]이 null이면 아무것도 그리지 않아요.
 *  - 기본: 알약 "들어 보기"(스피커) → 누르면 코랄 "듣는 중"(멈춤), 다시 누르면 멈춤 (시안 1·2)
 *  - [compact]: 동그란 스피커 아이콘 → 코랄 동그라미 멈춤 (시안 6~8 목록·내 목소리 줄)
 *  - 녹음 중이면 회색으로 꺼져요(안내 문구는 [VoiceRecordingNote])
 * @param id 기본 화자 id. 같은 VoicePlayer 안에서 무엇이 재생 중인지 가려요
 * @param freshUrl 재생에 실패했을 때 기본 화자 목록을 다시 받아 이 화자의 새 voiceUrl을 돌려줘요
 */
@Composable
fun VoicePreviewButton(
    url: String?,
    id: String,
    player: VoicePlayer,
    freshUrl: suspend () -> String?,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    if (url == null) return
    val playing = player.playingId == id
    val enabled = !player.recording
    val onClick = { player.toggle(id, url, freshUrl) }
    if (compact) {
        Box(
            modifier.size(36.dp).clip(CircleShape)
                .background(if (playing) LologColors.Accent else if (enabled) LologColors.Paper else LologColors.Canvas)
                .border(1.dp, if (playing) LologColors.Accent else LologColors.Line, CircleShape)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            when {
                playing && player.loading -> CircularProgressIndicator(Modifier.size(14.dp), color = LologColors.Paper, strokeWidth = 2.dp)
                playing -> Icon(Icons.Filled.Stop, "멈춤", tint = LologColors.Paper, modifier = Modifier.size(16.dp))
                else -> Icon(
                    Icons.AutoMirrored.Outlined.VolumeUp, "들어 보기",
                    tint = if (enabled) LologColors.Ink else LologColors.Line, modifier = Modifier.size(18.dp),
                )
            }
        }
        return
    }
    Row(
        modifier.height(34.dp).clip(LologShape.pill)
            .background(if (playing) LologColors.Accent else if (enabled) LologColors.Paper else LologColors.Canvas)
            .border(1.dp, if (playing) LologColors.Accent else if (enabled) LologColors.Line else LologColors.Canvas, LologShape.pill)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (playing) {
            if (player.loading) {
                CircularProgressIndicator(Modifier.size(12.dp), color = LologColors.Paper, strokeWidth = 1.5.dp)
            } else {
                Icon(Icons.Filled.Stop, "멈춤", tint = LologColors.Paper, modifier = Modifier.size(14.dp))
            }
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Filled.GraphicEq, null, tint = LologColors.Paper, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("듣는 중", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = LologColors.Paper)
        } else {
            val tint = if (enabled) LologColors.Ink else LologColors.Muted.copy(alpha = 0.5f)
            Icon(Icons.AutoMirrored.Outlined.VolumeUp, null, tint = tint, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("들어 보기", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = tint)
        }
    }
}

/** 녹음 중일 때만 회색 안내 한 칸(시안 4) */
@Composable
fun VoiceRecordingNote(player: VoicePlayer, modifier: Modifier = Modifier) {
    if (!player.recording) return
    Text(
        VOICE_RECORDING_NOTE, fontSize = 12.sp, color = LologColors.Muted, lineHeight = 18.sp,
        modifier = modifier.fillMaxWidth().clip(LologShape.sm).background(LologColors.Canvas).padding(horizontal = 14.dp, vertical = 12.dp),
    )
}
