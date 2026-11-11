package com.lolog.app.recording.stt

/**
 * 글자 변환에 넣는 소리(크기를 맞춘 뒤)를 그대로 받아 가는 곳. 음성 저장을 켰을 때만 쓴다(명세 audio.md 2장 "시간 맞추기").
 * 모두 인식 스레드에서 부른다.
 */
interface AudioSink {
    /** 크기를 맞춘 소리. 값은 [-1, 1] */
    fun write(samples: FloatArray, count: Int)

    /** 인식이 밀려 건너뛴 소리 길이. 받는 쪽은 그만큼 무음을 채워 파일 안 위치와 글의 시간을 맞춘다. */
    fun skip(samples: Long)

    /** 입력이 끝났다. 남은 소리를 마저 쓰고 파일을 마감한다. */
    fun close()
}
