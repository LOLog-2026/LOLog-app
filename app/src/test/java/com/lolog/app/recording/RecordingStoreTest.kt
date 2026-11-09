package com.lolog.app.recording

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecordingStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private fun store() = RecordingStore(folder.root)
    private fun dir() = folder.root.listFiles()!!.single()

    @Test
    fun 묶음은_문장과_시간을_녹음_시작_기준으로_담는다() {
        val store = store()
        val rec = store.open("2026-11-10T12:30:00+09:00")
        rec.append(1000, 2000, "하나")
        rec.append(3000, 4000, "  ")
        assertEquals(1, rec.sealPart(3_600_000, last = false))
        rec.append(3_601_000, 3_602_000, "둘")
        assertEquals(2, rec.sealPart(4_000_000, last = true))

        val parts = store.pendingParts()
        assertEquals(listOf(1, 2), parts.map { it.request.partNo })
        assertEquals(listOf("하나"), parts[0].request.sentences.map { it.text })
        assertEquals(3600, parts[0].request.durationSec)
        assertFalse(parts[0].request.isLast)
        assertEquals(3_601_000, parts[1].request.sentences[0].startMs)
        assertEquals(400, parts[1].request.durationSec)
        assertTrue(parts[1].request.isLast)
        assertEquals(parts[0].request.clientRecordingId, parts[1].request.clientRecordingId)
        assertEquals("2026-11-10T12:30:00+09:00", parts[1].request.startedAt)
    }

    @Test
    fun 문장이_없어도_마지막_묶음은_만든다() {
        val store = store()
        store.open("t").sealPart(5000, last = true)
        val part = store.pendingParts().single()
        assertTrue(part.request.isLast)
        assertTrue(part.request.sentences.isEmpty())
    }

    @Test
    fun 다_올리면_폴더를_지우고_앞_묶음만_올리면_남긴다() {
        val store = store()
        val rec = store.open("t")
        rec.sealPart(1000, last = false)
        rec.sealPart(2000, last = true)
        val parts = store.pendingParts()
        store.markUploaded(parts[0])
        assertTrue(dir().exists())
        assertEquals(1, store.pendingParts().size)
        store.markUploaded(parts[1])
        assertEquals(0, folder.root.listFiles()!!.size)
    }

    @Test
    fun 죽은_녹음은_쌓인_문장으로_마감하고_깨진_마지막_줄은_버린다() {
        val store = store()
        val rec = store.open("t")
        rec.append(1000, 2000, "남은 글")
        File(dir(), "current.jsonl").appendText("{\"seq\":2,\"startMs\":30")

        RecordingStore(folder.root).closeAbandoned()

        val part = store.pendingParts().single()
        assertTrue(part.request.isLast)
        assertEquals(listOf("남은 글"), part.request.sentences.map { it.text })
        assertEquals(2, part.request.durationSec)
    }

    @Test
    fun 묶음을_쓴_뒤_죽어도_문장이_두_번_올라가지_않는다() {
        val store = store()
        val rec = store.open("t")
        rec.append(1000, 2000, "앞")
        rec.sealPart(3000, last = false)
        val current = File(dir(), "current.jsonl")
        // 묶음과 meta는 썼지만 current를 비우기 전에 죽은 상태
        current.writeText("{\"seq\":1,\"startMs\":1000,\"endMs\":2000,\"text\":\"앞\"}\n")

        RecordingStore(folder.root).closeAbandoned()

        val parts = store.pendingParts()
        assertEquals(listOf(1, 2), parts.map { it.request.partNo })
        assertTrue(parts[1].request.sentences.isEmpty())
    }

    @Test
    fun 묶음만_쓰고_meta를_올리기_전에_죽어도_번호가_겹치지_않는다() {
        val store = store()
        val rec = store.open("t")
        rec.append(1000, 2000, "앞")
        rec.sealPart(3000, last = false)
        val meta = File(dir(), "meta.json")
        val before = meta.readText().replace("\"nextPartNo\":2", "\"nextPartNo\":1").replace("\"sealedSeq\":1", "\"sealedSeq\":0").replace("\"partStartMs\":3000", "\"partStartMs\":0")
        meta.writeText(before)
        File(dir(), "current.jsonl").writeText("{\"seq\":1,\"startMs\":1000,\"endMs\":2000,\"text\":\"앞\"}\n")

        RecordingStore(folder.root).closeAbandoned()

        val parts = store.pendingParts()
        assertEquals(listOf(1, 2), parts.map { it.request.partNo })
        assertEquals(listOf("앞"), parts[0].request.sentences.map { it.text })
        assertTrue(parts[1].request.sentences.isEmpty())
    }

    @Test
    fun 아무것도_올리지_않은_녹음만_없앨_수_있다() {
        val store = store()
        assertTrue(store.open("t").discardIfUnsent())
        assertTrue(folder.root.listFiles()!!.isEmpty())

        val spoken = store.open("t")
        spoken.append(0, 1000, "말")
        assertFalse(spoken.discardIfUnsent())

        val sent = store.open("t")
        sent.sealPart(1000, last = false)
        assertFalse(sent.discardIfUnsent())
    }

    @Test
    fun 로그아웃하면_남은_녹음을_모두_지운다() {
        val store = store()
        val done = store.open("2026-11-10T12:30:00+09:00")
        done.append(0, 1000, "올리지 못한 글")
        done.sealPart(1000, last = true)
        val open = store.open("2026-11-10T13:00:00+09:00")
        open.append(0, 1000, "녹음 중인 글")
        open.discard()
        store.deleteAll()
        assertTrue(store.pendingParts().isEmpty())
        assertTrue(folder.root.listFiles()!!.isEmpty())
    }
}
