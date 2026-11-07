// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core
import java.io.IOException
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
class SearchWorkspaceTest {
    private fun repository(body: String)=ContentRepository(OkHttpClient.Builder().addInterceptor { chain ->
        assertEquals("/api/search/result",chain.request().url.encodedPath)
        assertEquals("谁说",chain.request().url.queryParameter("query"))
        assertNull(chain.request().header("Cookie"))
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody()).build()
    }.build())
    @Test fun allCatalogSearchUsesServerPagingAndDeduplicates() {
        val body="""{"pageNum":1,"hasMore":false,"searchList":[{"video_data":{"series_id":"123","series_name":"测试剧"}},{"video_data":{"series_id":"123","series_name":"测试剧"}}]}"""
        val page=repository(body).searchAll("谁说")
        assertEquals(listOf("123"),page.items.map { it.id }); assertFalse(page.hasMore)
    }
    @Test fun invalidPageAndMissingRowsAreNotEmptySuccess() {
        assertThrows(IOException::class.java) { repository("""{"pageNum":2,"searchList":[]}""").searchAll("谁说") }
        assertThrows(IOException::class.java) { repository("{}").searchAll("谁说") }
    }
}
