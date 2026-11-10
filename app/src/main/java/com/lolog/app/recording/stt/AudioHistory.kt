package com.lolog.app.recording.stt

/**
 * VAD에 넣은 소리(크기를 맞춘 뒤)를 최근 capacity 샘플만큼 담아 둔다. 인식 스레드 하나만 쓴다.
 * 위치는 VAD에 넣기 시작한 때부터 센 샘플 번호다.
 */
class AudioHistory(private val capacity: Int) {
    private val data = FloatArray(capacity)

    /** 지금까지 쓴 샘플 수. 다음에 쓸 위치이기도 하다. */
    var end = 0L
        private set

    /** 아직 남아 있는 가장 오래된 위치. */
    val oldest: Long get() = maxOf(0L, end - capacity)

    fun write(src: FloatArray) {
        for (i in src.indices) data[((end + i) % capacity).toInt()] = src[i]
        end += src.size
    }

    /** [from, to)를 꺼낸다. 이미 덮어쓴 앞부분은 빼고 남은 만큼만 준다. */
    fun copyRange(from: Long, to: Long): FloatArray {
        val start = maxOf(from, oldest)
        val stop = minOf(to, end)
        if (stop <= start) return FloatArray(0)
        return FloatArray((stop - start).toInt()) { data[((start + it) % capacity).toInt()] }
    }

    /**
     * [lo, hi) 안에서 frame 길이 구간의 에너지가 가장 작은 곳의 가운데를 돌려준다. 프레임은 lo부터 frame씩 센다.
     * 찾을 프레임이 없으면 hi를 돌려준다. (측정 코드 hard_split과 같은 방식)
     */
    fun quietestPoint(lo: Long, hi: Long, frame: Int): Long {
        var best = hi
        var bestEnergy = Double.MAX_VALUE
        var j = lo
        while (j + frame <= hi) {
            if (j >= oldest && j + frame <= end) {
                var sum = 0.0
                for (k in 0 until frame) {
                    val v = data[((j + k) % capacity).toInt()]
                    sum += v.toDouble() * v
                }
                val energy = sum / frame
                if (energy < bestEnergy) {
                    bestEnergy = energy
                    best = j + frame / 2
                }
            }
            j += frame
        }
        return best
    }
}
