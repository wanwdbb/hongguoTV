package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ContentRepositoryTest {
    private fun streamRepository(vararg variants: Triple<Int,String,String>) = repository { request ->
        if(request.url.host=="example.com") {
            val rows=JSONObject()
            variants.forEachIndexed { index,(height,codec,url) -> rows.put("video_$index",JSONObject().put("vheight",height).put("codec_type",codec).put("main_url",url)) }
            JSONObject().put("video_info",JSONObject().put("data",JSONObject().put("video_list",rows))).toString()
        } else """{"data":{"123":{"video_model":{"fallback_api":"https://example.com/stream"}}}}"""
    }

    @Test fun incompatibleByteVc2DoesNotBecomeAudioOnlyPlayback() {
        val repo=streamRepository(Triple(720,"bytevc2","https://example.com/unsupported"),Triple(1080,"bytevc1","https://example.com/hevc"))
        val result=repo.stream("123",720)
        assertEquals("https://example.com/hevc",result.url)
        assertEquals("1080P（兼容资源）",result.quality)
    }

    @Test fun compatibleLowerQualityWinsBeforeHigherResolutionFallback() {
        val repo=streamRepository(Triple(720,"bytevc2","https://example.com/unsupported"),Triple(480,"h264","https://example.com/avc"),Triple(1080,"bytevc1","https://example.com/hevc"))
        assertEquals("480P",repo.stream("123",720).quality)
    }

    @Test fun entirelyIncompatibleSourceFailsBeforeStartingAudio() {
        val repo=streamRepository(Triple(720,"ByteVC2","https://example.com/unsupported"),Triple(480,"bvc2","https://example.com/unsupported2"))
        assertThrows(IOException::class.java) { repo.stream("123",720) }
    }

    @Test fun legacyMissingCodecLabelKeepsQualityLimit() {
        val repo=streamRepository(Triple(720,"","https://example.com/legacy"),Triple(1080,"bytevc1","https://example.com/hevc"))
        assertEquals("720P",repo.stream("123",720).quality)
    }

    private fun repository(clock: () -> Long = System::currentTimeMillis, respond: (Request) -> String) = ContentRepository(
        OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(respond(chain.request()).toResponseBody()).build()
        }.build(),clock
    )

    private fun rankPayload(page: Int = 1, totalPages: Int = 2) = JSONObject()
        .put("isSuccess",true)
        .put("rankList",JSONArray().put(JSONObject().put("seriesId","123").put("title","测试漫剧")
            .put("cover","https:\\u002F\\u002Fexample.com\\u002Fcover.jpg")
            .put("episodeCount",12).put("tags",JSONArray().put("玄幻"))))
        .put("pagination",JSONObject().put("pageNum",page).put("totalPages",totalPages))

    private fun script(name: String, args: JSONArray) =
        "<script data-fn-name=\"$name\" data-fn-args=\"${args.toString().replace("&","&amp;").replace("\"","&quot;")}\"></script>"

    private fun currentRank(payload: JSONObject) = script("mergeLoaderData",JSONArray()
        .put("rank_hot-comic-drama/page").put(JSONArray().put(JSONObject().put("key","content")
            .put("routerDataFnName","p").put("routerDataFnArgs",JSONArray().put(payload.toString())))))

    @Test fun comicRankingPreservesSourceRankHeatAndUpdateRatherThanRenumbering() {
        val payload=rankPayload(2,5)
        payload.getJSONArray("rankList").getJSONObject(0).put("rank",23).put("heatText","5746万热度")
        val repo=repository { request ->
            assertEquals("/rank/hot-comic-drama",request.url.encodedPath)
            assertEquals("2",request.url.queryParameter("page"))
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":2,"updatedText":"9月22日已更新"}}};</script>"""+currentRank(payload)
        }
        val result=repo.comicRanking(2)
        assertNotNull(result.ranking)
        assertEquals(RankPosition(23,"5746万热度"),result.ranking!!.positions["123"])
        assertEquals(5,result.ranking.totalPages)
        assertEquals("9月22日已更新",result.ranking.updatedText)
        assertTrue(result.hasMore)
        assertFalse(result.items.single().toJson().has("rank"))
    }

    @Test fun missingRankIsNotInventedAndLastPageStops() {
        val result=repository { currentRank(rankPayload(5,5)) }.comicRanking(5)
        assertNotNull(result.ranking)
        assertEquals(RankPosition(null,""),result.ranking!!.positions["123"])
        assertFalse(result.hasMore)
    }

    @Test fun renderedRankingPreservesPageTwoRankAndHeat() {
        val repo=repository {
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":2,"updatedText":"今日已更新"}}};</script>
                <article aria-labelledby="rank-title-123"><span class="pc-badge-number-example">21</span>
                <h2 id="rank-title-123">漫剧</h2><p class="pc-metrics-example"><span>5746万热度</span></p></article>
                <nav aria-label="榜单分页"><a href="/rank/hot-comic-drama">1</a><span aria-current="page">2</span><a href="/rank/hot-comic-drama?page=5">5</a></nav>"""
        }
        val result=repo.comicRanking(2)
        assertNotNull(result.ranking)
        assertEquals(RankPosition(21,"5746万热度"),result.ranking!!.positions["123"])
        assertEquals(5,result.ranking.totalPages)
        assertEquals("今日已更新",result.ranking.updatedText)
    }

    @Test fun rankingRejectsUnexpectedPageInsteadOfShowingWrongRanks() {
        val repo=repository { currentRank(rankPayload(1,5)) }
        assertThrows(IOException::class.java) { repo.comicRanking(2) }
        assertThrows(IllegalArgumentException::class.java) { repo.comicRanking(0) }
    }

    private fun boardPayload(key: String, payload: JSONObject) = script("mergeLoaderData",JSONArray()
        .put(key).put(JSONArray().put(JSONObject().put("key","content")
            .put("routerDataFnName","p").put("routerDataFnArgs",JSONArray().put(payload.toString())))))

    @Test fun rankingBoardsReadTheirOwnPathKeyAndStatus() {
        for(board in RankBoard.entries) {
            val payload=rankPayload()
            payload.getJSONArray("rankList").getJSONObject(0).put("rank",7).put("heatText","1234万热度")
                .put("statusTags",JSONArray().put("新剧")).put("title","第\u0000十季")
            val repo=repository { request ->
                assertEquals(board.path,request.url.encodedPath)
                assertEquals("1",request.url.queryParameter("page"))
                """<script>_ROUTER_DATA={"loaderData":{"${board.pageKey}":{"rankKey":"${board.rankKey}","pageNum":1,"updatedText":"今日已更新"}}};</script>"""+
                    boardPayload(board.pageKey,payload)
            }
            val result=repo.ranking(board)
            assertEquals(RankPosition(7,"1234万热度","新剧"),result.ranking!!.positions["123"])
            assertEquals("第十季",result.items.single().title)
            assertEquals("今日已更新",result.ranking.updatedText)
        }
    }

    @Test fun rankingBoardKeepsAnotherBoardsPayloadOut() {
        val repo=repository { boardPayload("rank_hot-real-drama/page",rankPayload()) }
        assertThrows(IOException::class.java) { repo.ranking(RankBoard.COMIC) }
    }

    @Test fun aggregateBoardMatchesTheSitesOwnRankKey() {
        val repo=repository {
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-drama/page":{"rankKey":"hongguo","pageNum":1}}};</script>
                <article aria-labelledby="rank-title-123"><span class="pc-badge-number-example">1</span><h2 id="rank-title-123">聚宝仙盆</h2>
                <li class="pc-status-tag-example">新剧</li><p class="pc-metrics-example">8370万热度</p></article>
                <nav aria-label="榜单分页"><span aria-current="page">1</span><a href="/rank/hot-drama?page=5">5</a></nav>"""
        }
        val result=repo.ranking()
        assertEquals(RankPosition(1,"8370万热度","新剧"),result.ranking!!.positions["123"])
        assertEquals(5,result.ranking.totalPages)
        assertTrue(result.hasMore)
    }

    @Test fun rankBoardFallbackKeepsUnknownSelectionUsable() {
        assertEquals(RankBoard.HONGGUO,RankBoard.fromStored(null))
        assertEquals(RankBoard.HONGGUO,RankBoard.fromStored("gone"))
        assertEquals(RankBoard.AI,RankBoard.fromStored("hot-ai-drama"))
        assertEquals("AI剧热播榜",RankBoard.AI.label)
    }

    @Test fun cleanTextRemovesStreamedControlCharacters() {
        assertEquals("第五季",cleanText("第五\u0000\u0000季"))
        assertEquals("红果 热播",cleanText(" 红果\u3000 热播 "))
    }

    @Test fun renderedLastPageCountsCurrentPageWithoutAnAnchor() {        val repo=repository {
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":5}}};</script>
                <article aria-labelledby="rank-title-123"><span class="pc-badge-number-example">81</span><h2 id="rank-title-123">漫剧</h2></article>
                <nav aria-label="榜单分页"><a href="/rank/hot-comic-drama?page=4">4</a><span aria-current="page">5</span></nav>"""
        }
        val result=repo.comicRanking(5)
        assertEquals(5,result.ranking!!.totalPages)
        assertFalse(result.hasMore)
    }

    @Test fun comicHomeUsesDedicatedRankAndRealPagination() {
        val repo=repository { request ->
            assertEquals("/rank/hot-comic-drama",request.url.encodedPath)
            assertEquals("2",request.url.queryParameter("page"))
            currentRank(rankPayload(2,2))
        }
        val page=repo.home(2,ContentType.COMIC)
        assertEquals("测试漫剧",page.items.single().title)
        assertEquals("https://example.com/cover.jpg",page.items.single().cover)
        assertEquals("全 12 集",page.items.single().badge)
        assertFalse(page.hasMore)
    }

    @Test fun legacyRankIsStillSupported() {
        val repo=repository { script("r",JSONArray().put("rank_hot-comic-drama/page").put("content").put(rankPayload())) }
        assertTrue(repo.home(1,ContentType.COMIC).hasMore)
    }

    @Test fun comicHomeDoesNotUseUnrelatedRankData() {
        val repo=repository { script("r",JSONArray().put("rank_hot-real-drama/page").put("content").put(rankPayload())) }
        assertThrows(IOException::class.java) { repo.home(1,ContentType.COMIC) }
    }

    @Test fun rejectedComicRankRemainsAnError() {
        val repo=repository { currentRank(rankPayload().put("isSuccess",false)) }
        assertThrows(IOException::class.java) { repo.home(1,ContentType.COMIC) }
    }

    @Test fun renderedComicRankWorksWhenStreamedPayloadIsMissing() {
        val repo=repository {
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":2}}};</script>
                <article aria-labelledby="rank-title-123"><a href="/detail?series_id=123"><img src="https://example.com/a.jpg"></a>
                <h2 id="rank-title-123">测试 &amp; 漫剧</h2><p class="pc-description-example">简介</p><p class="pc-metrics-example">123万热度</p></article>
                <nav aria-label="榜单分页"><a href="/rank/hot-comic-drama?page=1">1</a><span aria-current="page">2</span><a href="/rank/hot-comic-drama?page=3">3</a></nav>"""
        }
        val page=repo.home(2,ContentType.COMIC)
        assertEquals("测试 & 漫剧",page.items.single().title)
        assertEquals("https://example.com/a.jpg",page.items.single().cover)
        assertTrue(page.hasMore)
    }

    @Test fun comicSearchCarriesCursorAndKeepsTypesAndQueriesSeparate() {
        val requests=mutableListOf<Request>()
        val repo=repository { request ->
            requests+=request
            JSONObject().put("code",0).put("search_tabs",JSONArray().put(searchTab(19,"999")
                .put("search_id","comic-session").put("passback","comic-cursor").put("next_offset",25))).toString()
        }
        repo.search("修仙",1,ContentType.COMIC)
        repo.search("修仙",2,ContentType.COMIC)
        assertEquals("comic-session",requests[1].url.queryParameter("search_id"))
        assertEquals("comic-cursor",requests[1].url.queryParameter("passback"))
        assertEquals("25",requests[1].url.queryParameter("offset"))
        assertThrows(SearchSessionExpiredException::class.java) { repo.search("重生",2,ContentType.COMIC) }
        assertEquals(3,requests.size)
    }

    @Test fun firstSearchPageMergesSecondBatchSoResultsAreNotTooFew() {
        val requests=mutableListOf<Request>()
        val repo=repository { request ->
            requests+=request
            if(requests.size==1) JSONObject().put("code",0).put("search_tabs",JSONArray().put(JSONObject()
                .put("tab_type",19).put("has_more",true).put("search_id","session").put("passback","6").put("next_offset",6)
                .put("data",JSONArray().put(JSONObject().put("video_data",JSONArray().put(JSONObject()
                    .put("series_id","101").put("title","第一页").put("episode_cnt",8))))))).toString()
            else JSONObject().put("code",0).put("search_tabs",JSONArray().put(JSONObject()
                .put("tab_type",19).put("has_more",false)
                .put("data",JSONArray().put(JSONObject().put("video_data",JSONArray().put(JSONObject()
                    .put("series_id","202").put("title","第二页").put("episode_cnt",8))))))).toString()
        }
        val page=repo.search("修仙",1,ContentType.COMIC)
        assertEquals(listOf("101","202"),page.items.map { it.id })
        assertFalse(page.hasMore)
        assertEquals(2,requests.size)
        // 第二页请求用第一页返回的游标，而不是凭空算出的 offset。
        assertEquals("6",requests[1].url.queryParameter("offset"))
        assertEquals("6",requests[1].url.queryParameter("passback"))
        assertEquals("session",requests[1].url.queryParameter("search_id"))
        assertEquals("0",requests[0].url.queryParameter("offset"))
        assertEquals("true",requests[0].url.queryParameter("is_first_enter_search"))
    }

    @Test fun expiredCursorRequestsFreshSearchInsteadOfDuplicateOffsetPage() {
        var now=0L
        var requests=0
        val repo=repository(clock={ now }) {
            requests++
            JSONObject().put("code",0).put("search_tabs",JSONArray().put(searchTab(19,"999")
                .put("search_id","session").put("passback","cursor"))).toString()
        }
        repo.search("修仙",1,ContentType.COMIC)
        now=300001L
        assertThrows(SearchSessionExpiredException::class.java) { repo.search("修仙",2,ContentType.COMIC) }
        assertEquals(2,requests)
        repo.search("修仙",1,ContentType.COMIC)
        repo.search("修仙",2,ContentType.COMIC)
        assertEquals(5,requests)
    }

    @Test fun shortHomeStillUsesCategoryAndStopsOnLastPage() {
        val repo=repository { request ->
            assertEquals("1",request.url.queryParameter("tab"))
            """<script>_ROUTER_DATA={"loaderData":{"category_$":{"recommendList":[{"series_id":"456","series_name":"测试短剧"}],"pagination":{"totalPages":1}}}};</script>"""
        }
        val result=repo.home(1,ContentType.SHORT)
        assertEquals("456",result.items.single().id)
        assertFalse(result.hasMore)
    }

    @Test fun categoryRequestsGenrePathAndParsesFiltersAndPagination() {
        val repo=repository { request ->
            assertEquals("/category/real-drama/romance",request.url.encodedPath)
            assertEquals("2",request.url.queryParameter("page"))
            """<script>_ROUTER_DATA={"loaderData":{"category_$":{"recommendList":[{"series_id":"456","series_name":"测试短剧","tags":[{"name":"爱情"}]}],
                "pagination":{"totalPages":3},"selectorList":[{"row_id":1,"row_name":"全部分类","items":[
                {"selector_item_id":"romance","show_name":"爱情","category_json_ids":["5000"]},
                {"selector_item_id":"period","show_name":"年代","category_json_ids":["5021"]}]}]}}};</script>"""
        }
        val result=repo.category(CatalogType.REAL,"romance",2)
        assertEquals("456",result.items.single().id)
        assertEquals("爱情",result.items.single().tags)
        assertEquals(listOf(CatalogGenre("romance","爱情"),CatalogGenre("period","年代")),result.genres)
        assertTrue(result.hasMore)
    }

    @Test fun categoryWithoutGenreUsesTypeRootAndStopsOnLastPage() {
        val repo=repository { request ->
            assertEquals("/category/comic",request.url.encodedPath)
            assertNull(request.url.queryParameter("page"))
            """<script>_ROUTER_DATA={"loaderData":{"category_page":{"recommendList":[{"series_id":"1","series_name":"漫画"}],"pagination":{"totalPages":1}}}};</script>"""
        }
        val result=repo.category(CatalogType.COMIC)
        assertEquals("漫画",result.items.single().title)
        assertTrue(result.genres.isEmpty())
        assertFalse(result.hasMore)
    }

    @Test fun categoryRejectsUnsafeGenreAndPage() {
        val repo=repository { """<script>_ROUTER_DATA={"loaderData":{"category_$":{"recommendList":[]}}};</script>""" }
        assertThrows(IllegalArgumentException::class.java) { repo.category(CatalogType.REAL,"../escape") }
        assertThrows(IllegalArgumentException::class.java) { repo.category(CatalogType.AI_DRAMA,"",101) }
    }

    @Test fun categoryTypeRemembersKnownValuesAndFallsBackForUnknown() {
        assertEquals(CatalogType.AI_DRAMA,CatalogType.fromStored("ai-drama"))
        assertEquals(CatalogType.COMIC_DRAMA,CatalogType.fromStored("comic-drama"))
        assertEquals(CatalogType.REAL,CatalogType.fromStored("no-such-type"))
        assertEquals(CatalogType.REAL,CatalogType.fromStored(null))
        assertEquals(listOf("真人剧","漫剧","AI剧","漫画"),CatalogType.entries.map { it.label })
        assertEquals(listOf("/category/real-drama","/category/comic-drama","/category/ai-drama","/category/comic"),CatalogType.entries.map { it.path })
    }

    private fun searchTab(type: Int,id: String) = JSONObject().put("tab_type",type).put("has_more",true)
        .put("data",JSONArray().put(JSONObject().put("video_data",JSONArray().put(JSONObject()
            .put("series_id",id).put("title","测试剧集").put("episode_cnt",8)))))

    @Test fun comicSearchRequestsAndSelectsOnlyComicTab() {
        var requests=0
        val repo=repository { request ->
            requests++
            assertEquals("19",request.url.queryParameter("tab_type"))
            assertEquals(if(requests==1) "0" else "20",request.url.queryParameter("offset"))
            assertEquals("修仙",request.url.queryParameter("query"))
            JSONObject().put("code",0).put("search_tabs",JSONArray()
                .put(searchTab(11,"111")).put(searchTab(19,"999"))).toString()
        }
        repo.search("修仙",1,ContentType.COMIC)
        assertEquals(listOf("999"),repo.search("修仙",2,ContentType.COMIC).items.map { it.id })
    }

    @Test fun emptyComicSearchDoesNotFallBackToShortResults() {
        var requests=0
        val repo=repository { requests++; """{"code":0,"search_tabs":[{"tab_type":19,"data":[],"has_more":false}]}""" }
        assertTrue(repo.search("无结果",1,ContentType.COMIC).items.isEmpty())
        assertEquals(1,requests)
    }

    @Test fun missingComicTabDoesNotFallBackToShortResults() {
        var requests=0
        val repo=repository { requests++; """{"code":0,"search_tabs":[{"tab_type":11,"data":[]}]}""" }
        assertThrows(IOException::class.java) { repo.search("修仙",1,ContentType.COMIC) }
        assertEquals(1,requests)
    }

    @Test fun shortSearchKeepsWebsiteFallback() {
        val paths=mutableListOf<String>()
        val repo=repository { request ->
            paths+=request.url.encodedPath
            if(paths.size==1) throw IOException("offline")
            """<script>_ROUTER_DATA={"loaderData":{"search_page":{"searchList":[{"series_id":"456","series_name":"测试短剧"}]}}}};</script>"""
        }
        assertEquals("456",repo.search("短剧",1,ContentType.SHORT).items.single().id)
        assertEquals(2,paths.size)
    }

    @Test fun unregisteredDeviceEmptySearchFallsBackToWebsiteForShortDramas() {
        val paths=mutableListOf<String>()
        val repo=repository { request ->
            paths+=request.url.encodedPath
            if(request.url.host.endsWith("fqnovel.com")) "" else
                """<script>_ROUTER_DATA={"loaderData":{"search_page":{"searchList":[{"series_id":"456","series_name":"测试短剧"}]}}}};</script>"""
        }
        assertEquals("456",repo.search("短剧",1,ContentType.SHORT).items.single().id)
        assertEquals(2,paths.size)
    }

    @Test fun unregisteredDeviceEmptyComicSearchReportsFriendlyError() {
        var requests=0
        val repo=repository { requests++; "" }
        val error=assertThrows(IOException::class.java) { repo.search("修仙",1,ContentType.COMIC) }
        assertEquals("搜索接口暂不可用",error.message)
        assertEquals(1,requests)
    }

    @Test fun deviceIdentityAcceptsOnlyNumericRegistrationResults() {
        assertFalse(DeviceIdentity.registered)
        assertFalse(DeviceIdentity.apply("install","123456"))
        assertFalse(DeviceIdentity.apply("123456","device"))
        val install=DeviceIdentity.installId; val device=DeviceIdentity.deviceId
        try {
            assertTrue(DeviceIdentity.apply(" 1234567 "," 7654321 "))
            assertEquals("1234567",DeviceIdentity.installId)
            assertEquals("7654321",DeviceIdentity.deviceId)
        } finally {
            // 单例是进程级的，恢复现场避免影响签名固定值等其它测试。
            DeviceIdentity.apply(install,device)
        }
        assertEquals(install,DeviceIdentity.installId)
        assertEquals(device,DeviceIdentity.deviceId)
    }

    @Test fun detailKeepsCastForAuthorPage() {
        val repo=repository {
            """<script>_ROUTER_DATA={"loaderData":{"detail_page":{"seriesDetail":{
                "series_id":"123","series_name":"测试短剧","series_cover":"https://example.com/cover.jpg",
                "series_intro":"简介","episode_cnt":12,"vid_list":["1","2"],
                "celebrities":[
                    {"celebrity_id":"760","nickname":"王志欣","avatar":"https:\\u002F\\u002Fexample.com\\u002Fa.jpg","sub_title":"饰 王妈"},
                    {"celebrity_id":"760","nickname":"重复","sub_title":""},
                    {"celebrity_id":"bad","nickname":"无效"},
                    {"celebrity_id":"761","nickname":""}
                ]}}}};</script>"""
        }
        val result=repo.detail("123")
        assertEquals(listOf("1","2"),result.episodes)
        assertEquals(listOf("王志欣"),result.people.map { it.name })
        assertEquals("https://example.com/a.jpg",result.people.single().avatar)
        assertEquals("饰 王妈",result.people.single().role)
    }

    @Test fun detailLoadsOfficialRelatedWorksCell() {
        var relatedCalls=0
        val repo=repository { request ->
            when(request.url.encodedPath) {
                "/detail" -> """<script>_ROUTER_DATA={"loaderData":{"detail_page":{"seriesDetail":{
                    "series_id":"123","series_name":"测试短剧","series_intro":"简介",
                    "episode_cnt":12,"vid_list":["1","2"],
                    "celebrities":[{"celebrity_id":"760","nickname":"王志欣"}]
                }}}};</script>"""
                "/reading/bookapi/plan/v:version/" -> {
                    relatedCalls++
                    assertEquals("GET",request.method)
                    assertEquals("10",request.url.queryParameter("scene"))
                    assertEquals("detail_page_more_related",request.url.queryParameter("from"))
                    assertEquals("123",request.url.queryParameter("book_id"))
                    assertEquals("73932",request.url.queryParameter("version_code"))
                    assertEquals("101",request.header("lc"))
                    """{"code":0,"data":[
                        {"cell_name":"相关作品","show_type":547,"video_data":[
                            {"series_id":"456","title":"爆笑修仙第二季","episode_cnt":12,
                             "cover":"https:\\u002F\\u002Fexample.com\\u002Fb.jpg",
                             "tag_info":{"text":"第2季"},
                             "sub_title_list":[{"content":"玄幻脑洞","data_type":3},{"content":"45.1万次播放","data_type":16}]},
                            {"series_id":"123","title":"当前剧集"},
                            {"series_id":"999","title":"第十季预告","episode_cnt":0,
                             "cover":"https:\\u002F\\u002Fexample.com\\u002Fpreview.jpg",
                             "tag_info":{"text":"第10季"}},
                            {"series_id":"bad","title":"无效"}
                        ]},
                        {"cell_name":"猜你喜欢","show_type":506,"video_data":[
                            {"series_id":"789","title":"不应采用"}
                        ]}
                    ]}"""
                }
                else -> error("unexpected ${request.url}")
            }
        }
        val result=repo.detail("123",includeRelated=true)
        assertEquals(1,relatedCalls)
        assertEquals(listOf("456","999"),result.related.map { it.id })
        assertEquals("爆笑修仙第二季",result.related.first().title)
        assertEquals("https://example.com/b.jpg",result.related.first().cover)
        assertEquals("第2季",result.related.first().badge)
        assertEquals("玄幻脑洞",result.related.first().tags)
        assertFalse(result.related.first().preview)
        assertEquals("第十季预告",result.related.last().title)
        assertEquals("https://example.com/preview.jpg",result.related.last().cover)
        assertEquals("第10季",result.related.last().badge)
        assertTrue(result.related.last().preview)
        assertTrue(Series.fromJson(result.related.last().toJson()).preview)
        assertTrue(repo.detail("123").related.isEmpty())
        assertEquals(1,relatedCalls)
    }

    @Test fun characterPageParsesProfileAndSameCreatorWorks() {
        val repo=repository { request ->
            assertEquals("/character/760",request.url.encodedPath)
            """<script>_ROUTER_DATA={"loaderData":{"character_(id)/page":{
                "person_id":"760","userInfo":{"person_id":"760","user_name":"王志欣",
                "user_avatar":"https:\\u002F\\u002Fexample.com\\u002Favatar.jpg","description":"原创剧情达人",
                "subtitle_list":["女演员","导演","编剧"],"user_relation_info":{"fans_num":216766}},
                "videoList":[
                    {"series_id":"456","series_title":"另一部作品","series_cover":"https:\\u002F\\u002Fexample.com\\u002Fb.jpg","series_intro":"简介","episode_cnt":45},
                    {"series_id":"bad","series_title":"无效"}
                ]}}};</script>"""
        }
        val result=repo.character("760")
        assertEquals("760",result.id)
        assertEquals("王志欣",result.name)
        assertEquals("https://example.com/avatar.jpg",result.avatar)
        assertEquals(listOf("女演员","导演","编剧"),result.roles)
        assertEquals(216766L,result.fans)
        assertEquals(listOf("456"),result.works.map { it.id })
        assertEquals("另一部作品",result.works.single().title)
        assertEquals("全 45 集",result.works.single().badge)
        assertThrows(IllegalArgumentException::class.java) { repo.character("../bad") }
    }

    @Test fun detailUsesOfficialCreatorsWhenWebsiteHasNoCast() {
        var officialCalls=0
        val repo=repository { request ->
            when(request.url.encodedPath) {
                "/detail" -> """<script>_ROUTER_DATA={"loaderData":{"detail_page":{"seriesDetail":{
                    "series_id":"123","series_name":"测试漫剧","series_intro":"简介",
                    "episode_cnt":12,"vid_list":["1","2"],"celebrities":[]
                }}}};</script>"""
                "/novel/player/video_detail/v1/" -> {
                    officialCalls++
                    assertEquals("POST",request.method)
                    """{"code":0,"data":{"video_data":{"main_creator_users":[
                        {"user_id":"409","base_info":{"user_id":"409","user_name":"陈柯文化","user_avatar":"https:\\u002F\\u002Fexample.com\\u002Favatar.jpg"},
                         "user_tag":{"sub_title_list":[{"text":"出品方"}]},"user_relation":{"follow_user_num":4}},
                        {"user_id":"409","base_info":{"user_name":"重复"}},
                        {"user_id":"bad","base_info":{"user_name":"无效"}}
                    ]}}}"""
                }
                else -> error("unexpected ${request.url}")
            }
        }
        val result=repo.detail("123")
        assertEquals(1,officialCalls)
        assertEquals(listOf("409"),result.people.map { it.id })
        assertEquals("陈柯文化",result.people.single().name)
        assertEquals("出品方",result.people.single().role)
        assertEquals(4L,result.people.single().fans)
        assertTrue(result.people.single().creator)
    }

    @Test fun detailWithoutCreatorDataKeepsAuthorEntryHidden() {
        var officialCalls=0
        val repo=repository { request ->
            when(request.url.encodedPath) {
                "/detail" -> """<script>_ROUTER_DATA={"loaderData":{"detail_page":{"seriesDetail":{
                    "series_id":"123","series_name":"测试漫画","episode_cnt":12,"vid_list":["1","2"],"celebrities":[]
                }}}};</script>"""
                "/novel/player/video_detail/v1/" -> {
                    officialCalls++
                    """{"code":0,"data":{"video_data":{"series_id":"123"}}}"""
                }
                else -> error("unexpected ${request.url}")
            }
        }
        val result=repo.detail("123")
        assertEquals(1,officialCalls)
        assertTrue(result.people.isEmpty())
    }

    @Test fun creatorPageLoadsOfficialProfileAndAllWorks() {
        val cursors=mutableListOf<String>()
        var workCalls=0
        val repo=repository { request ->
            when(request.url.encodedPath) {
                "/reading/user/profile/get/v1/" -> {
                    assertEquals("409",request.url.queryParameter("user_id"))
                    """{"code":0,"data":{"ugc_user":{"user_id":"409",
                        "base_info":{"user_name":"陈柯文化","expand_user_avatar":"https:\\u002F\\u002Fexample.com\\u002Fexpand.jpg",
                        "description":"原创动漫出品方"},
                        "user_tag":{"sub_title_list":[{"text":"出品方"}]},
                        "user_relation":{"follow_user_num":4}}}}"""
                }
                "/novel/commentapi/user/profile/409/v1" -> {
                    workCalls++
                    assertEquals("api5-sinfnormal.fqnovel.com",request.url.host)
                    assertEquals("POST",request.method)
                    assertEquals("73932",request.url.queryParameter("version_code"))
                    assertEquals("7.3.9.32",request.url.queryParameter("version_name"))
                    assertEquals("1506",request.header("comment-source"))
                    assertEquals("101",request.header("lc"))
                    val body=okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8()
                    val json=JSONObject(body)
                    assertEquals("409",json.optString("target_user_id"))
                    assertEquals(23,json.optInt("tab_type"))
                    assertEquals(22,json.optInt("sub_tab_type"))
                    assertEquals("time_desc",json.optString("sort"))
                    val cursor=json.optString("cursor")
                    cursors+=cursor
                    if(cursor.isEmpty()) """{"code":0,"data":{"hightlight":{"total":4,"name":"作品"},
                        "common_list_info":{"cursor":"next-1","has_more":true},"data_list":[
                        {"data_type":1,"video":{"video_id":"456","common":{"title":"作品一",
                         "cover":"https:\\u002F\\u002Fexample.com\\u002Fone.jpg","intro":"简介一"},
                         "expand":{"episode_cnt":12,"sub_title_list":[{"text":"年代"},{"text":"12集"}]}}},
                        {"data_type":1,"video":{"video_id":"123","common":{"title":"当前剧集"},
                         "expand":{"episode_cnt":4}}},
                        {"data_type":1,"video":{"video_id":"bad","common":{"title":"无效"}}}
                    ]}}"""
                    else """{"code":0,"data":{"hightlight":{"total":0},
                        "common_list_info":{"cursor":"{\"next_cursor\":null,\"has_more\":false}","has_more":false},"data_list":[
                        {"data_type":1,"video":{"video_id":"789","common":{"title":"作品二"},
                         "expand":{"episode_cnt":8}}},
                        {"data_type":1,"video":{"video_id":"790","common":{"title":"作品三"},
                         "expand":{"episode_cnt":6}}}
                    ]}}"""
                }
                else -> error("unexpected ${request.url}")
            }
        }
        val person=Person("409","陈柯文化","https://example.com/fallback.jpg","出品方",2,true)
        val result=repo.creatorPage(person)
        assertEquals("陈柯文化",result.name)
        assertEquals("https://example.com/expand.jpg",result.avatar)
        assertEquals("原创动漫出品方",result.description)
        assertEquals(listOf("出品方"),result.roles)
        assertEquals(4L,result.fans)
        assertEquals(2,workCalls)
        assertEquals(listOf("","next-1"),cursors)
        assertEquals(listOf("456","123","789","790"),result.works.map { it.id })
        assertEquals("作品一",result.works.first().title)
        assertEquals("12集",result.works.first().badge)
        assertEquals("年代",result.works.first().tags)
        assertEquals("简介一",result.works.first().description)
        assertThrows(IllegalArgumentException::class.java) { repo.creatorPage(person.copy(id="../bad")) }
    }
}
