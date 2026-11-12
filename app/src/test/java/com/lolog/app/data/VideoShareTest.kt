package com.lolog.app.data

import com.lolog.app.data.remote.ApiClient
import com.lolog.app.util.VideoExport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 쇼츠 인스타그램 릴스 공유(#92): 인스타 확인 → 목업 → 받기(403이면 새 주소로 한 번 더) → 열기 순서를 본다. */
class VideoShareTest {

    private val mp4 = byteArrayOf(0, 0, 0, 24)

    /** 받은 주소를 적어 두는 가짜 다운로드 */
    private class FakeDownload(private val answer: (String) -> ApiClient.Download) : (String) -> ApiClient.Download {
        val calls = mutableListOf<String>()
        override fun invoke(url: String): ApiClient.Download { calls += url; return answer(url) }
    }

    /** 넘겨받은 파일을 적어 두는 가짜 열기 */
    private class FakeOpen : suspend (ByteArray) -> VideoExport.Result {
        var opened: ByteArray? = null
        override suspend fun invoke(bytes: ByteArray): VideoExport.Result { opened = bytes; return VideoExport.Result.SHARED }
    }

    @Test
    fun 인스타그램이_없으면_받기_전에_NO_INSTAGRAM() = runBlocking {
        val download = FakeDownload { ApiClient.Download.Ok(mp4) }
        val open = FakeOpen()
        val r = VideoExport.shareFlow("https://old", hasInstagram = { false }, download = download, freshUrl = { "https://new" }, open = open)
        assertEquals(VideoExport.Result.NO_INSTAGRAM, r)
        assertEquals(emptyList<String>(), download.calls)
        assertNull(open.opened)
    }

    @Test
    fun 목업_주소면_MOCK() = runBlocking {
        val download = FakeDownload { ApiClient.Download.Ok(mp4) }
        val open = FakeOpen()
        val r = VideoExport.shareFlow("mock://video/1", hasInstagram = { true }, download = download, freshUrl = { null }, open = open)
        assertEquals(VideoExport.Result.MOCK, r)
        assertEquals(emptyList<String>(), download.calls)
        assertNull(open.opened)
    }

    @Test
    fun 네트워크가_없으면_MOCK() = runBlocking {
        val r = VideoExport.shareFlow("https://old", hasInstagram = { true }, download = null, freshUrl = { null }, open = FakeOpen())
        assertEquals(VideoExport.Result.MOCK, r)
    }

    @Test
    fun 받으면_인스타그램을_연다() = runBlocking {
        val download = FakeDownload { ApiClient.Download.Ok(mp4) }
        val open = FakeOpen()
        val r = VideoExport.shareFlow("https://old", hasInstagram = { true }, download = download, freshUrl = { "https://new" }, open = open)
        assertEquals(VideoExport.Result.SHARED, r)
        assertEquals(listOf("https://old"), download.calls)
        assertArrayEquals(mp4, open.opened)
    }

    @Test
    fun 주소가_만료되면_새_주소로_한_번_더_받는다() = runBlocking {
        val download = FakeDownload { if (it == "https://old") ApiClient.Download.Expired else ApiClient.Download.Ok(mp4) }
        val open = FakeOpen()
        val r = VideoExport.shareFlow("https://old", hasInstagram = { true }, download = download, freshUrl = { "https://new" }, open = open)
        assertEquals(VideoExport.Result.SHARED, r)
        assertEquals(listOf("https://old", "https://new"), download.calls)
        assertArrayEquals(mp4, open.opened)
    }

    @Test
    fun 받지_못하면_DOWNLOAD_FAILED() = runBlocking {
        val download = FakeDownload { ApiClient.Download.Failed }
        val open = FakeOpen()
        val r = VideoExport.shareFlow("https://old", hasInstagram = { true }, download = download, freshUrl = { "https://new" }, open = open)
        assertEquals(VideoExport.Result.DOWNLOAD_FAILED, r)
        assertEquals(listOf("https://old"), download.calls)
        assertNull(open.opened)
    }

    @Test
    fun 새_주소로도_못_받으면_DOWNLOAD_FAILED() = runBlocking {
        val download = FakeDownload { ApiClient.Download.Expired }
        val r = VideoExport.shareFlow("https://old", hasInstagram = { true }, download = download, freshUrl = { "https://new" }, open = FakeOpen())
        assertEquals(VideoExport.Result.DOWNLOAD_FAILED, r)
        assertEquals(listOf("https://old", "https://new"), download.calls)
    }

    @Test
    fun 결과_문구() {
        assertEquals("영상을 받지 못했어요. 인터넷 연결을 확인해 주세요", VideoExport.message(VideoExport.Result.DOWNLOAD_FAILED, sharing = true))
        assertEquals("목업에서는 공유할 수 없어요", VideoExport.message(VideoExport.Result.MOCK, sharing = true))
        // 인스타로 넘어간 뒤 올렸는지는 알 수 없어서 띠를 띄우지 않아요. 인스타가 없으면 창으로 알려요
        assertNull(VideoExport.message(VideoExport.Result.SHARED, sharing = true))
        assertNull(VideoExport.message(VideoExport.Result.NO_INSTAGRAM, sharing = true))
        // 폰에 저장 문구는 그대로
        assertEquals("동영상에 저장했어요", VideoExport.message(VideoExport.Result.SAVED))
        assertEquals("목업에서는 저장할 수 없어요", VideoExport.message(VideoExport.Result.MOCK))
    }
}
