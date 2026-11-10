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
 * 입력 → 링버퍼 → 크기 맞춤(AutoGain) → VAD → 20초 자르기 → SenseVoice. 음성은 메모리에만 잠깐 두고 인식 후 버린다.
 * VAD·크기 맞춤·자르기 설정은 오픈소스 데이터 측정(LOLog-ai#18, 명세 FR-REC-03)으로 정했다.
 * 스레드는 폰 측정에서 가장 빨랐던 2개다.
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
        // 모델을 불러오는 몇 초와 인식이 밀리는 동안의 입력을 담는다. 구간은 20초에서 자르므로 구간 길이와는 상관없다.
        private const val RING_SECONDS = 120
        private const val PRE_ROLL_MS = 300L // 구간 앞에 더 붙이는 소리. 말 앞부분이 잘리지 않게 한다.
        private const val VAD_WINDOW = 512
        private const val MS_PER_SECOND = 1000L
        private const val MIN_SPEECH_SECONDS = 0.25f
        // sherpa-onnx VAD의 maxSpeechDuration은 구간을 실제로 자르지 않는다(측정에서 132.8초 구간도 나왔다). 그래서 앱이 자른다.
        private const val MAX_SEGMENT_SAMPLES = SAMPLE_RATE * 20L
        private const val PRE_ROLL_SAMPLES = PRE_ROLL_MS * SAMPLE_RATE / MS_PER_SECOND
        // 자를 구간(최대 20초)과 앞 여유, VAD가 쉼을 확인하는 시간을 담고도 남는 길이.
        private const val HISTORY_SECONDS = 30
    }

    private val ring = AudioRingBuffer(SAMPLE_RATE * RING_SECONDS)
    // 아래 값은 인식 스레드만 쓴다. 위치는 모두 VAD에 넣은 샘플 번호다.
    private val history = AudioHistory(SAMPLE_RATE * HISTORY_SECONDS)
    private var speechStart: Long? = null // 지금 이어지는 말이 시작된 곳. 말이 없으면 null.
    private var sentUntil = 0L // 여기까지는 인식기로 보냈다.
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
                            // 켜고 끈 차이가 0.05%p 안이라 숫자가 읽기 쉬운 쪽을 골랐다.
                            useInverseTextNormalization = true,
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
                        threshold = 0.6f,
                        minSilenceDuration = 0.8f,
                        minSpeechDuration = MIN_SPEECH_SECONDS,
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
        val gain = AutoGain()
        var fedToVad = 0L
        while (true) {
            val readStart = ring.nextReadPosition
            val n = ring.readBlocking(pcm)
            // 덮어쓰기가 있으면 읽기 위치가 건너뛰므로 VAD 번호와 링버퍼 번호의 차이를 새로 구한다.
            val offset = readStart - fedToVad
            if (n < VAD_WINDOW) break
            for (i in 0 until n) samples[i] = pcm[i] / 32768f
            // 키운 소리를 VAD와 인식기에 같이 넘긴다. 앞 여유도 history에서 꺼내므로 같은 이득이 들어간다.
            gain.apply(samples)
            history.write(samples)
            vad.acceptWaveform(samples)
            fedToVad += n
            drain(vad, recognizer, offset)
            cutLongSpeech(vad, recognizer, offset)
        }
        vad.flush()
        drain(vad, recognizer, ring.nextReadPosition - fedToVad)
    }

    /** VAD가 끝낸 구간을 인식한다. 20초 자르기로 앞부분을 이미 보냈으면 남은 부분만 보낸다. */
    private fun drain(vad: Vad, recognizer: OfflineRecognizer, offset: Long) {
        while (!vad.empty()) {
            val segment = vad.front()
            val end = segment.start + segment.samples.size
            var start = maxOf(segment.start, sentUntil)
            while (end - start > MAX_SEGMENT_SAMPLES) start = cutAt(start, recognizer, offset)
            if (end > start) recognize(start, end, recognizer, offset)
            speechStart = null
            vad.pop()
        }
    }

    /** 말이 끊기지 않고 20초를 넘으면 VAD를 기다리지 않고 자른다. */
    private fun cutLongSpeech(vad: Vad, recognizer: OfflineRecognizer, offset: Long) {
        if (!vad.isSpeechDetected()) {
            speechStart = null
            return
        }
        // sherpa-onnx VAD는 말을 알아챈 순간 창 2개와 최소 말 길이만큼 앞을 구간 시작으로 잡는다. 같은 값으로 맞춘다.
        val detected = history.end - 2 * VAD_WINDOW - (MIN_SPEECH_SECONDS * SAMPLE_RATE).toLong()
        val begin = speechStart ?: maxOf(detected, sentUntil, 0L).also { speechStart = it }
        var start = maxOf(begin, sentUntil)
        while (history.end - start > MAX_SEGMENT_SAMPLES) start = cutAt(start, recognizer, offset)
    }

    /** start부터 10~20초 사이에서 소리가 가장 작은 32ms 프레임 가운데를 찾아 거기까지 인식한다. 자른 곳을 돌려준다. */
    private fun cutAt(start: Long, recognizer: OfflineRecognizer, offset: Long): Long {
        val cut = history.quietestPoint(start + MAX_SEGMENT_SAMPLES / 2, start + MAX_SEGMENT_SAMPLES, VAD_WINDOW)
        recognize(start, cut, recognizer, offset)
        return cut
    }

    private fun recognize(start: Long, end: Long, recognizer: OfflineRecognizer, offset: Long) {
        // 앞 구간의 끝을 넘지 않게 해서 같은 말이 두 번 나오지 않게 한다. 잘라 이은 구간은 앞 여유가 붙지 않는다.
        val from = maxOf(start - PRE_ROLL_SAMPLES, sentUntil, history.oldest)
        val audio = history.copyRange(from, end)
        sentUntil = end

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
        val audioStart = from + offset
        val audioEnd = end + offset
        onSegment(SttSegment(audioStart * MS_PER_SECOND / SAMPLE_RATE, audioEnd * MS_PER_SECOND / SAMPLE_RATE, text, decodeMs))
    }
}
