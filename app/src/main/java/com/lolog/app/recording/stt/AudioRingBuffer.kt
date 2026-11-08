package com.lolog.app.recording.stt

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 마이크 스레드가 쓰고 인식 스레드가 읽는 고정 크기 음성 버퍼.
 * 위치는 녹음 시작부터 센 절대 샘플 번호다. 인식이 밀려 덮어쓰면 그 길이를 droppedSamples에 더한다.
 */
class AudioRingBuffer(private val capacity: Int) {
    private val data = ShortArray(capacity)
    private val lock = ReentrantLock()
    private val available = lock.newCondition()
    private var writePos = 0L
    private var readPos = 0L
    private var closed = false
    private var dropped = 0L

    val droppedSamples: Long get() = lock.withLock { dropped }
    val nextReadPosition: Long get() = lock.withLock { readPos }
    val writePosition: Long get() = lock.withLock { writePos }

    fun write(src: ShortArray, count: Int) {
        lock.withLock {
            for (i in 0 until count) {
                data[((writePos + i) % capacity).toInt()] = src[i]
            }
            writePos += count
            val oldest = writePos - capacity
            if (readPos < oldest) {
                dropped += oldest - readPos
                readPos = oldest
            }
            available.signalAll()
        }
    }

    /** dest를 가득 채울 때까지 기다린다. 닫힌 뒤 남은 양이 부족하면 읽은 만큼만 돌려준다. */
    fun readBlocking(dest: ShortArray): Int {
        lock.withLock {
            while (writePos - readPos < dest.size && !closed) available.await()
            val n = minOf(writePos - readPos, dest.size.toLong()).toInt()
            for (i in 0 until n) {
                dest[i] = data[((readPos + i) % capacity).toInt()]
            }
            readPos += n
            return n
        }
    }

    /** 이미 읽은 구간을 다시 꺼낸다. 덮어써졌거나 범위 밖이면 null. */
    fun copyRange(start: Long, count: Int): ShortArray? {
        lock.withLock {
            if (start < 0 || count < 0 || start < writePos - capacity || start + count > writePos) return null
            return ShortArray(count) { data[((start + it) % capacity).toInt()] }
        }
    }

    fun close() {
        lock.withLock {
            closed = true
            available.signalAll()
        }
    }
}
