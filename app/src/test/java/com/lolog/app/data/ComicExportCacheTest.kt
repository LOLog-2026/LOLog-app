package com.lolog.app.data

import com.lolog.app.data.remote.ApiClient
import com.lolog.app.util.ComicExport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 저장·공유할 때 Coil 디스크 캐시에 있는 그림을 먼저 쓰고, 없을 때만 받는지 본다(#35). */
class ComicExportCacheTest {

    private val cachedPng = byteArrayOf(1, 2, 3)
    private val serverPng = byteArrayOf(9, 9)

    /** 받은 주소를 적어 두는 가짜 다운로드 */
    private class FakeDownload(private val answer: (String) -> ApiClient.Download) : (String) -> ApiClient.Download {
        val calls = mutableListOf<String>()
        override fun invoke(url: String): ApiClient.Download { calls += url; return answer(url) }
    }

    @Test
    fun 캐시에_있으면_다운로드를_부르지_않는다() = runBlocking {
        val download = FakeDownload { ApiClient.Download.Ok(serverPng) }
        val bytes = ComicExport.fetchPng("https://old", cached = { cachedPng }, download = download, freshUrl = { "https://new" })
        assertArrayEquals(cachedPng, bytes)
        assertEquals(emptyList<String>(), download.calls)
    }

    @Test
    fun 캐시에_없으면_받는다() = runBlocking {
        val download = FakeDownload { ApiClient.Download.Ok(serverPng) }
        val bytes = ComicExport.fetchPng("https://old", cached = { null }, download = download, freshUrl = { "https://new" })
        assertArrayEquals(serverPng, bytes)
        assertEquals(listOf("https://old"), download.calls)
    }

    @Test
    fun 주소가_만료되면_새_주소로_한_번만_더_받는다() = runBlocking {
        val download = FakeDownload { if (it == "https://old") ApiClient.Download.Expired else ApiClient.Download.Ok(serverPng) }
        val bytes = ComicExport.fetchPng("https://old", cached = { null }, download = download, freshUrl = { "https://new" })
        assertArrayEquals(serverPng, bytes)
        assertEquals(listOf("https://old", "https://new"), download.calls)
    }

    @Test
    fun 받지_못하면_null() = runBlocking {
        val download = FakeDownload { ApiClient.Download.Failed }
        assertNull(ComicExport.fetchPng("https://old", cached = { null }, download = download, freshUrl = { null }))
        assertEquals(listOf("https://old"), download.calls)
    }
}
