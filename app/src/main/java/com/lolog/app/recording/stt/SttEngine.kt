package com.lolog.app.recording.stt

import android.content.res.AssetManager
import android.os.Process
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlin.concurrent.thread

/** 16kHz 모노 PCM을 주는 곳. 끝나면 read가 0 이하를 돌려준다. */
interface PcmSource {
    fun read(buffer: ShortArray): Int
    /** 입력을 멈춘다. 기다리던 read도 끝나게 한다. 다른 스레드에서 불러도 된다. */
    fun stop()
    /** read가 끝난 뒤에 자원을 놓는다. */
    fun release()
}

/** 인식 결과 하나. 시간은 녹음 시작부터 잰 값이다. */
data class SttSegment(val startMs: Long, val endMs: Long, val text: String, val decodeMs: Long)

/**
 * 입력 → 링버퍼 → VAD → SenseVoice. 음성은 메모리에만 잠깐 두고 인식 후 버린다.
 * 설정은 폰 측정(stt-compare)과 같고, 스레드는 측정에서 가장 빨랐던 2개다.
 */
class SttEngine(
    private val assets: AssetManager,
    private val source: PcmSource,
    private val onSegment: (SttSegment) -> Unit,
    private val onFinished: (error: Throwable?) -> Unit,
) {
    companion object {
        const val SAMPLE_RATE = 16000
        const val THREADS = 2
        private const val RING_SECONDS = 120 // 측정에서 한 구간이 최대 38.6초였다.
        private const val PRE_ROLL_MS = 300L // 구간 앞에 더 붙이는 소리. 말 앞부분이 잘리지 않게 한다.
        private const val VAD_WINDOW = 512
        private const val MS_PER_SECOND = 1000L
    }

    private val ring = AudioRingBuffer(SAMPLE_RATE * RING_SECONDS)
    @Volatile private var stopRequested = false

    /** 지금까지 받은 음성 길이. 묶음 길이(durationSec)를 잴 때 쓴다. */
    val positionMs: Long get() = ring.writePosition * MS_PER_SECOND / SAMPLE_RATE

    /** 덮어써서 잃은 음성 길이. 0이 아니면 인식이 밀린 것이다. */
    val droppedMs: Long get() = ring.droppedSamples * MS_PER_SECOND / SAMPLE_RATE

    fun start() {
        thread(name = "stt-worker") { run() }
    }

    /** 입력을 멈춘다. 남은 소리까지 인식한 뒤 onFinished가 불린다. */
    fun stop() {
        stopRequested = true
        source.stop()
    }

    private fun run() {
        var recognizer: OfflineRecognizer? = null
        var vad: Vad? = null
        var capture: Thread? = null
        var error: Throwable? = null
        try {
            // 마이크는 이미 켜져 있다. 모델을 불러오는 몇 초 동안의 말도 링버퍼에 담기게 먼저 읽기 시작한다.
            capture = startCapture()
            recognizer = OfflineRecognizer(
                assetManager = assets,
                config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                    modelConfig = OfflineModelConfig(
                        senseVoice = OfflineSenseVoiceModelConfig(
                            model = "model.int8.onnx",
                            language = "ko",
                            useInverseTextNormalization = false,
                        ),
                        tokens = "tokens.txt",
                        numThreads = THREADS,
                        provider = "cpu",
                    ),
                ),
            )
            vad = Vad(
                assetManager = assets,
                config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = "silero_vad.onnx",
                        threshold = 0.5f,
                        minSilenceDuration = 0.25f,
                        minSpeechDuration = 0.25f,
                        windowSize = VAD_WINDOW,
                        maxSpeechDuration = 20f,
                    ),
                    sampleRate = SAMPLE_RATE,
                    numThreads = 1,
                    provider = "cpu",
                ),
            )
            process(vad, recognizer)
        } catch (t: Throwable) {
            error = t
        } finally {
            stopRequested = true
            source.stop()
            ring.close()
            capture?.join()
            source.release()
            vad?.release()
            recognizer?.release()
            onFinished(error)
        }
    }

    private fun startCapture(): Thread = thread(name = "stt-capture") {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buffer = ShortArray(SAMPLE_RATE / 10)
        try {
            while (!stopRequested) {
                val n = source.read(buffer)
                if (n <= 0) break
                ring.write(buffer, n)
            }
        } finally {
            ring.close()
        }
    }

    private fun process(vad: Vad, recognizer: OfflineRecognizer) {
        val pcm = ShortArray(VAD_WINDOW)
        val samples = FloatArray(VAD_WINDOW)
        var fedToVad = 0L
        var lastEnd = 0L
        while (true) {
            val readStart = ring.nextReadPosition
            val n = ring.readBlocking(pcm)
            // 덮어쓰기가 있으면 읽기 위치가 건너뛰므로 VAD 번호와 링버퍼 번호의 차이를 새로 구한다.
            val offset = readStart - fedToVad
            if (n < VAD_WINDOW) break
            for (i in 0 until n) samples[i] = pcm[i] / 32768f
            vad.acceptWaveform(samples)
            fedToVad += n
            lastEnd = drain(vad, recognizer, offset, lastEnd)
        }
        vad.flush()
        drain(vad, recognizer, ring.nextReadPosition - fedToVad, lastEnd)
    }

    private fun drain(vad: Vad, recognizer: OfflineRecognizer, offset: Long, previousEnd: Long): Long {
        var lastEnd = previousEnd
        while (!vad.empty()) {
            val segment = vad.front()
            val start = segment.start + offset
            val end = start + segment.samples.size
            // 앞 구간의 끝을 넘지 않게 해서 같은 말이 두 번 나오지 않게 한다.
            val preRollStart = maxOf(start - PRE_ROLL_MS * SAMPLE_RATE / MS_PER_SECOND, lastEnd, 0L)
            val preRoll = ring.copyRange(preRollStart, (start - preRollStart).toInt()) ?: ShortArray(0)
            val audio = FloatArray(preRoll.size + segment.samples.size)
            for (i in preRoll.indices) audio[i] = preRoll[i] / 32768f
            segment.samples.copyInto(audio, preRoll.size)

            val decodeStart = System.nanoTime()
            val stream = recognizer.createStream()
            val text = try {
                stream.acceptWaveform(audio, SAMPLE_RATE)
                recognizer.decode(stream)
                recognizer.getResult(stream).text.trim()
            } finally {
                stream.release()
            }
            val decodeMs = (System.nanoTime() - decodeStart) / 1_000_000
            val audioStart = end - audio.size
            onSegment(SttSegment(audioStart * MS_PER_SECOND / SAMPLE_RATE, end * MS_PER_SECOND / SAMPLE_RATE, text, decodeMs))
            lastEnd = end
            vad.pop()
        }
        return lastEnd
    }
}
