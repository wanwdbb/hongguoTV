// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import org.json.JSONArray
import java.io.IOException
import java.util.concurrent.TimeUnit

internal fun JSONArray.objects() = (0 until length()).mapNotNull { optJSONObject(it) }
internal fun JSONObject.str(key: String) = if (isNull(key)) "" else optString(key, "").trim()
/** App 搜索接口：未注册设备会被风控挡下（HTTP 200 空响应），见 [ContentRepository.registerDevice]。 */
private const val SEARCH_API = "https://api5-normal-sinfonlinea.fqnovel.com/reading/bookapi/search/tab/v"
private const val REGISTER_API = "https://log5-applog.fqnovel.com/service/2/device_register/"
private const val COMMENT_API = "https://api5-normal-sinfonlineb.fqnovel.com/novel/commentapi/comment/list"
private const val CREATOR_WORKS_API = "https://api5-sinfnormal.fqnovel.com/novel/commentapi/user/profile"
const val DANMAKU_WINDOW = 30_000L
const val DANMAKU_MAX_DURATION = 86_400_000L
/** 官方搜索第一页只给 6 条（「综合」页），首页拼两页补足一屏。 */
private const val FIRST_PAGE_BATCHES = 2
internal fun cleanUrl(s: String) = s.replace("\\/","/").replace("\\u002F","/",ignoreCase=true).replace("\\u0026","&").replace("&amp;","&")
/** 网站流式 HTML 偶尔会在文本里留下 NUL 等控制字符，单行展示前先清理。 */
internal fun cleanText(s: String) = s.filter { it.code>=32 }.replace(Regex("[\\s\\u00a0\\u3000]+")," ").trim()
data class Series(val id: String, val title: String, val cover: String = "", val description: String = "", val badge: String = "", val tags: String = "") {
    fun toJson() = JSONObject().put("id",id).put("title",title).put("cover",cover).put("description",description).put("badge",badge).put("tags",tags)
    companion object { fun fromJson(o: JSONObject) = Series(o.str("id"),o.str("title"),o.str("cover"),o.str("description"),o.str("badge"),o.str("tags")) }
}
data class Person(val id: String, val name: String, val avatar: String = "", val role: String = "", val fans: Long = 0, val creator: Boolean = false)
/** 作者/主创页：同一演员或出品方的简介与作品列表。 */
data class CreatorPage(val id: String, val name: String, val avatar: String = "", val description: String = "", val roles: List<String> = emptyList(), val fans: Long = 0, val works: List<Series> = emptyList())
data class Detail(val series: Series, val episodes: List<String>, val people: List<Person> = emptyList())
data class RankPosition(val rank: Int?, val heat: String, val status: String = "")
data class ComicRanking(val positions: Map<String,RankPosition>, val totalPages: Int, val updatedText: String)
data class CatalogPage(val items: List<Series>, val hasMore: Boolean, val ranking: ComicRanking? = null, val genres: List<CatalogGenre> = emptyList())
data class StreamInfo(val url: String, val key: ByteArray?, val quality: String)
data class DeviceRegistration(val installId: String, val deviceId: String)
class SearchSessionExpiredException: IOException("搜索结果已过期，请从第 1 页重新搜索")

class ContentRepository(val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).callTimeout(45,TimeUnit.SECONDS).build(), private val clock: () -> Long = System::currentTimeMillis) {
    private val signer = Signer()
    private val site = "https://hongguoduanju.com"
    /** 官方接口统一使用较新的版本号；网站接口仍沿用 [VendorConstants.device]。 */
    private fun officialValues() = VendorConstants.device.toMutableMap().also {
        for(k in listOf("version_code","manifest_version_code","update_version_code","pv_player")) it[k]="72232"
        it["version_name"]="7.2.2.32"
    }
    /** 主页作品列表接口按版本号开量；旧版本会返回空列表，不能复用 [officialValues]。 */
    private fun creatorWorksValues() = VendorConstants.device.toMutableMap().also {
        for(k in listOf("version_code","manifest_version_code","update_version_code","pv_player")) it[k]="73932"
        it["version_name"]="7.3.9.32"
    }
    private data class SearchCursor(val searchId: String, val passback: String, val offset: Int?, val updatedAt: Long)
    private val searchCursors=linkedMapOf<Triple<ContentType,String,Int>,SearchCursor>()
    private fun text(url: String, signed: Signer.Request? = null, headers: Map<String,String> = emptyMap()): String {
        val request = Request.Builder().url(url).header("User-Agent",VendorConstants.VIDEO_UA).header("Accept-Language","zh-CN,zh;q=0.9")
        signed?.headers?.forEach { (k,v) -> request.header(k,v) }
        headers.forEach { (k,v) -> request.header(k,v) }
        signed?.body?.let { request.post(it.toRequestBody("application/json; charset=utf-8".toMediaType())) }
        return RequestScope.execute(http.newCall(request.build())) { response ->
            if (!response.isSuccessful) throw IOException("内容请求失败（HTTP ${response.code}）")
            val body = response.body ?: throw IOException("内容响应为空")
            if (body.contentLength() > 16*1024*1024) throw IOException("内容响应过大")
            val input=body.byteStream(); val out=java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192)
            while(out.size()<=16*1024*1024) { val n=input.read(buffer); if(n<0) break; out.write(buffer,0,n) }; val bytes=out.toByteArray()
            if(bytes.size > 16*1024*1024) throw IOException("内容响应过大")
            String(bytes,Charsets.UTF_8)
        }
    }
    private fun router(url: String) = extractRouter(text(url)).getJSONObject("loaderData")
    private fun card(item: JSONObject): Series {
        val o=item.optJSONObject("video_data") ?: item
        val count=o.optInt("episode_cnt",o.optJSONObject("series_episode_info")?.optInt("episode_cnt",0) ?: 0)
        val tags=o.optJSONArray("tags") ?: o.optJSONArray("category_list") ?: JSONArray()
        return Series(o.str("series_id"),o.str("series_name").ifEmpty { o.str("series_title").ifEmpty { item.str("name") } },cleanUrl(o.str("series_cover")),o.str("series_intro"),o.str("episode_right_text").ifEmpty { if(count>0) "全 ${count} 集" else "" },(0 until minOf(tags.length(),5)).joinToString(" · ") { tags.optJSONObject(it)?.str("name") ?: tags.optString(it) })
    }
    private fun valid(rows: List<Series>) = rows.filter { it.id.matches(Regex("[0-9]{1,30}")) && it.title.isNotBlank() }.distinctBy { it.id }
    /** 详情页的演员/主创；网站用 celebrity_id 打开同名角色页。 */
    private fun people(o: JSONObject): List<Person> {
        val raw=o.optJSONArray("celebrities") ?: return emptyList()
        return (0 until raw.length()).mapNotNull { index ->
            val item=raw.optJSONObject(index) ?: return@mapNotNull null
            val id=item.str("celebrity_id"); val name=item.str("nickname")
            if(!id.matches(Regex("[0-9]{1,30}")) || name.isBlank()) null
            else Person(id,name,cleanUrl(item.str("avatar")),item.str("sub_title"))
        }.distinctBy { it.id }
    }
    /**
     * 官方详情接口的主创用户：网站的 `celebrities` 只覆盖演员，
     * 漫剧/AI剧/漫画等分类通常只在这里给出出品方。
     */
    private fun officialCreators(id: String): List<Person> = runCatching {
        val body=JSONObject().put("series_id",id).put("biz_param",JSONObject()
            .put("video_id_type",1).put("source",5).put("detail_page_version",1)
        ).toString().toByteArray(Charsets.UTF_8)
        val signed=signer.sign("https://api5-normal-sinfonlinea.fqnovel.com/novel/player/video_detail/v1/",officialValues(),body)
        val root=JSONObject(text(signed.url,signed))
        if(root.optInt("code",-1)!=0) return@runCatching emptyList()
        val users=root.optJSONObject("data")?.optJSONObject("video_data")?.optJSONArray("main_creator_users") ?: return@runCatching emptyList()
        (0 until users.length()).mapNotNull { index ->
            val item=users.optJSONObject(index) ?: return@mapNotNull null
            val base=item.optJSONObject("base_info") ?: JSONObject()
            val userId=item.str("user_id").ifEmpty { base.str("user_id") }
            val name=base.str("user_name")
            if(!userId.matches(Regex("[0-9]{1,30}")) || name.isBlank()) return@mapNotNull null
            val roles=item.optJSONObject("user_tag")?.optJSONArray("sub_title_list")?.let { array ->
                (0 until array.length()).mapNotNull { at -> array.optJSONObject(at)?.str("text")?.takeIf { it.isNotEmpty() } }
            }.orEmpty()
            val fans=item.optJSONObject("user_relation")?.optLong("follow_user_num",0) ?: 0
            val avatar=base.str("expand_user_avatar").ifEmpty { base.str("user_avatar") }
            Person(userId,name,cleanUrl(avatar),roles.joinToString(" · "),fans,true)
        }.distinctBy { it.id }
    }.getOrDefault(emptyList())
    /** 角色页：网站 `/character/<id>`，直接复用详情卡片解析作品列表。 */
    fun character(id: String): CreatorPage {
        require(id.matches(Regex("[0-9]{1,30}")))
        val page=router("$site/character/$id").optJSONObject("character_(id)/page") ?: throw IOException("作者页暂不可用")
        val info=page.optJSONObject("userInfo") ?: JSONObject()
        val name=info.str("user_name").ifEmpty { throw IOException("作者信息暂不可用") }
        val roles=info.optJSONArray("subtitle_list")?.let { array ->
            (0 until array.length()).mapNotNull { index -> array.optString(index).trim().takeIf { it.isNotEmpty() } }
        }.orEmpty()
        val raw=page.optJSONArray("videoList") ?: JSONArray()
        val works=valid((0 until raw.length()).mapNotNull { index -> raw.optJSONObject(index)?.let(::card) })
        return CreatorPage(info.str("person_id").ifEmpty { id },name,cleanUrl(info.str("user_avatar")),
            cleanText(info.str("description")),roles,
            info.optJSONObject("user_relation_info")?.optLong("fans_num",0) ?: 0,works)
    }
    private data class CreatorProfile(val name: String, val avatar: String, val description: String, val roles: List<String>, val fans: Long)
    /** 官方用户资料：出品方/演员的资料页，和作品列表分开取。 */
    private fun creatorProfile(userId: String): CreatorProfile? = runCatching {
        val signed=signer.sign("https://api5-normal-sinfonlinea.fqnovel.com/reading/user/profile/get/v1/",
            officialValues().also { it["user_id"]=userId })
        val root=JSONObject(text(signed.url,signed))
        if(root.optInt("code",-1)!=0) return@runCatching null
        val data=root.optJSONObject("data") ?: return@runCatching null
        val user=data.optJSONObject("ugc_user") ?: return@runCatching null
        val base=user.optJSONObject("base_info") ?: JSONObject()
        val name=base.str("user_name").ifBlank { return@runCatching null }
        val roles=user.optJSONObject("user_tag")?.optJSONArray("sub_title_list")?.let { array ->
            (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.str("text")?.takeIf { it.isNotEmpty() } }
        }.orEmpty()
        val fans=user.optJSONObject("user_relation")?.optLong("follow_user_num",0)
            ?: data.optJSONObject("user_relation")?.optLong("follow_user_num",0) ?: 0
        CreatorProfile(name,cleanUrl(base.str("expand_user_avatar").ifEmpty { base.str("user_avatar") }),
            cleanText(base.str("description")),roles,fans)
    }.getOrNull()
    /**
     * 官方作者主页作品接口。和网站搜索不同，它按 user_id 返回该作者自己的全部作品，
     * 不会混入同名关键词的其它剧集；首屏 40 条，之后用 cursor 翻页。
     */
    private fun creatorWorksPage(userId: String, cursor: String): JSONObject {
        val body=JSONObject()
            .put("comment_source",1506)
            .put("count",18)
            .put("cursor",cursor)
            .put("extra_param",JSONObject())
            .put("locate_post_id","")
            .put("profile_user_type",0)
            .put("sort","time_desc")
            .put("sub_tab_type",22)
            .put("tab_type",23)
            .put("target_user_id",userId)
            .toString().toByteArray(Charsets.UTF_8)
        val signed=signer.sign("$CREATOR_WORKS_API/$userId/v1",creatorWorksValues(),body)
        val root=JSONObject(text(signed.url,signed,mapOf(
            "Accept" to "application/json; charset=utf-8,application/x-protobuf",
            "comment-source" to "1506","lc" to "101")))
        if(root.optInt("code",-1)!=0) throw IOException("作者作品暂不可用")
        return root.optJSONObject("data") ?: throw IOException("作者作品暂不可用")
    }
    private fun creatorWork(item: JSONObject): Series? {
        val video=item.optJSONObject("video") ?: return null
        val common=video.optJSONObject("common") ?: return null
        val id=video.str("video_id")
        val title=cleanText(common.str("title"))
        if(!id.matches(Regex("[0-9]{1,30}")) || title.isBlank()) return null
        val expand=video.optJSONObject("expand") ?: JSONObject()
        val count=expand.optInt("episode_cnt",0)
        val subs=expand.optJSONArray("sub_title_list")
        val texts=(0 until (subs?.length() ?: 0)).mapNotNull { index ->
            subs?.optJSONObject(index)?.str("text")?.takeIf { it.isNotBlank() }
        }
        val badge=texts.firstOrNull { it.contains("集") } ?: if(count>0) "${count}集" else ""
        val tags=texts.filter { !it.contains("集") }.take(3).joinToString(" · ")
        return Series(id,title,cleanUrl(common.str("cover")),cleanText(common.str("intro")),badge,tags)
    }
    /**
     * 逐页读取作者主页作品，直到 has_more=false 或空页；当前剧集也保留。
     * 服务端每页条数不固定，不能按 offset 推算，必须原样回传 cursor。
     */
    private fun creatorWorks(userId: String): List<Series> {
        val found=linkedMapOf<String,Series>()
        var cursor=""
        var page=0
        while(page<100) {
            var data: JSONObject?=null
            for(attempt in 0 until 3) {
                data=runCatching { creatorWorksPage(userId,cursor) }.getOrNull()
                if(data!=null) break
                // 偶发 5xx 时退避重试；cursor 不变，避免漏页。
                if(attempt<2) Thread.sleep(300L*(attempt+1))
            }
            val current=data ?: throw IOException("作者作品暂不可用")
            val rows=current.optJSONArray("data_list") ?: break
            if(rows.length()==0) break
            rows.objects().mapNotNull(::creatorWork).forEach { found[it.id]=it }
            val info=current.optJSONObject("common_list_info") ?: break
            val next=info.str("cursor")
            if(next.isBlank() || next==cursor || !info.optBoolean("has_more",false)) break
            cursor=next
            page++
            if(page<100) Thread.sleep(200)
        }
        return found.values.toList()
    }
    /** 漫剧/AI剧/漫画的出品方页：官方资料 + 官方作者主页的全部作品。 */
    fun creatorPage(person: Person): CreatorPage {
        require(person.id.matches(Regex("[0-9]{1,30}")))
        val profile=creatorProfile(person.id)
        val name=profile?.name?.ifBlank { person.name } ?: person.name
        if(name.isBlank()) throw IOException("作者信息暂不可用")
        val roles=profile?.roles?.ifEmpty { listOfNotNull(person.role.takeIf { it.isNotBlank() }) }
            ?: listOfNotNull(person.role.takeIf { it.isNotBlank() })
        return CreatorPage(person.id,name,profile?.avatar?.ifBlank { person.avatar } ?: person.avatar,
            profile?.description.orEmpty(),roles,profile?.fans?.takeIf { it>0 } ?: person.fans,
            creatorWorks(person.id))
    }
    /** 热播榜：网站 `/rank/<榜单>?page=N`，名次与热度都用网站给出的值。 */
    fun ranking(board: RankBoard = RankBoard.HONGGUO, page: Int = 1): CatalogPage {
        require(page in 1..100)
        return RankPage.parse(text("$site${board.path}?page=$page"),page,board)
    }
    /** 漫剧榜单：首页「漫剧」入口沿用。 */
    fun comicRanking(page: Int = 1) = ranking(RankBoard.COMIC,page)
    fun home(page: Int = 1, type: ContentType = ContentType.SHORT): CatalogPage {
        require(page in 1..100)
        if(type==ContentType.COMIC) return comicRanking(page)
        val section=categorySection(router("$site/category?tab=1&sort_type=1"+if(page>1) "&page=$page" else ""))
        val items=valid(categoryRows(section).objects().map(::card))
        return CatalogPage(items,items.isNotEmpty() && page<lastPage(section),null,categoryGenres(section))
    }
    /** 分类浏览：`全部` 用空题材，其余按网站的分类页地址抓取。 */
    fun category(type: CatalogType, genre: String = "", page: Int = 1): CatalogPage {
        require(page in 1..100)
        require(genre.isEmpty() || genre.matches(Regex("[a-z0-9-]{1,40}"))) { "分类题材格式错误" }
        val path=type.path+(if(genre.isEmpty()) "" else "/$genre")+(if(page>1) "?page=$page" else "")
        val section=categorySection(router("$site$path"))
        val items=valid(categoryRows(section).objects().map(::card))
        return CatalogPage(items,items.isNotEmpty() && page<lastPage(section),null,categoryGenres(section))
    }
    private fun categorySection(data: JSONObject): JSONObject =
        data.optJSONObject("category_page") ?: data.optJSONObject("category_$") ?: throw IOException("内容数据结构已变化")
    private fun categoryRows(section: JSONObject): JSONArray =
        section.optJSONArray("recommendList") ?: section.optJSONObject("categoryData")?.optJSONArray("recommendList")
        ?: throw IOException("内容数据暂不可用")
    private fun lastPage(section: JSONObject) = minOf(section.optJSONObject("pagination")?.optInt("totalPages",100) ?: 100,100)
    /** 分类页的题材筛选项（网站第一行筛选器），不额外补「全部」。 */
    private fun categoryGenres(section: JSONObject): List<CatalogGenre> {
        val row=(section.optJSONArray("selectorList") ?: return emptyList()).objects().firstOrNull() ?: return emptyList()
        return (row.optJSONArray("items") ?: return emptyList()).objects().mapNotNull { item ->
            val slug=item.str("selector_item_id"); val label=item.str("show_name")
            if(slug.isNotEmpty() && label.isNotEmpty()) CatalogGenre(slug,label) else null
        }
    }
    fun search(keyword: String, page: Int = 1, type: ContentType = ContentType.SHORT): CatalogPage {
        require(keyword.isNotBlank() && keyword.length<=80 && page in 1..100)
        // The website search does not constrain content type. Never use it for comics.
        if(type==ContentType.COMIC) return appSearch(keyword,page,type)
        try { val result=appSearch(keyword,page,type); if(result.items.isNotEmpty() || !result.hasMore) return result } catch (e: Exception) { if(e is SearchSessionExpiredException) throw e; /* Website fallback for short dramas only. */ }
        val data=router("$site/search/${Signer.encode(keyword)}?page=$page")
        val section=data.optJSONObject("search_(keyword)/page") ?: data.optJSONObject("search_page") ?: throw IOException("搜索数据暂不可用")
        val rows=section.optJSONArray("searchList") ?: throw IOException("搜索数据结构已变化")
        val items=valid(rows.objects().map(::card))
        return CatalogPage(items,items.isNotEmpty() && page<100)
    }
    private fun appSearch(keyword: String, page: Int, type: ContentType): CatalogPage {
        val cursor=synchronized(searchCursors) {
            val now=clock()
            searchCursors.entries.removeAll { now-it.value.updatedAt>300000 || (page==1 && it.key.first==type && it.key.second==keyword) }
            searchCursors[Triple(type,keyword,page-1)]
        }
        if(page>1 && cursor==null) throw SearchSessionExpiredException()
        val collected=linkedMapOf<String,Series>()
        var hasMore=false
        var next=cursor
        // 官方第一页只给 6 条（「综合」页），从第二页起每次 20 条；第一屏补一页，免得看起来"结果太少"。
        var batches=0
        while(batches < if(page==1) FIRST_PAGE_BATCHES else 1) {
            val batch=try { searchBatch(keyword,page,type,next) } catch (e: Exception) { if(batches==0) throw e else break }
            batch.items.forEach { collected[it.id]=it }
            hasMore=batch.hasMore
            next=batch.cursor
            batches++
            if(!hasMore || next.isExhausted()) break
        }
        synchronized(searchCursors) {
            next?.let { searchCursors[Triple(type,keyword,page)]=it }
            while(searchCursors.size>100) searchCursors.remove(searchCursors.keys.first())
        }
        return CatalogPage(valid(collected.values.toList()),hasMore && page<100)
    }
    private data class SearchBatch(val items: List<Series>, val hasMore: Boolean, val cursor: SearchCursor)
    private fun searchBatch(keyword: String, page: Int, type: ContentType, cursor: SearchCursor?): SearchBatch {
        val values=officialValues()
        values.putAll(linkedMapOf(
            "query" to keyword,"count" to "20","offset" to ((page-1)*20).toString(),"tab_type" to type.searchTab.toString(),
            "bookshelf_search_plan" to "4","use_correct" to "false","user_is_login" to "0","bookstore_tab" to "16",
            "search_source" to "1","search_source_id" to "clks###","tab_name" to "feed"
        ))
        if(cursor==null) values["is_first_enter_search"]="true"
        if(cursor!=null) {
            if(cursor.searchId.isNotBlank()) values["search_id"]=cursor.searchId
            if(cursor.passback.isNotBlank()) values["passback"]=cursor.passback
            cursor.offset?.takeIf { offset -> offset>=0 }?.let { offset -> values["offset"]=offset.toString() }
            values["last_search_page_query"]=keyword
        }
        val signed=signer.sign(SEARCH_API,values)
        val raw=text(signed.url,signed)
        // 未注册的设备会被风控挡下：HTTP 200、空响应体。
        if(raw.isBlank()) throw IOException("搜索接口暂不可用")
        val data=JSONObject(raw)
        if(data.optInt("code",-1)!=0) throw IOException("搜索接口暂不可用")
        val tabs=data.optJSONArray("search_tabs") ?: data.optJSONObject("data")?.optJSONArray("search_tabs") ?: throw IOException("搜索格式异常")
        val tab=tabs.objects().firstOrNull { it.optInt("tab_type")==type.searchTab } ?: throw IOException("${type.label}搜索暂不可用")
        val list=mutableListOf<Series>()
        for(cell in (tab.optJSONArray("data") ?: JSONArray()).objects()) {
            val cells=mutableListOf(cell)
            runCatching { JSONObject(cell.str("lynx_data")).optJSONArray("cell_data")?.objects() }.getOrNull()?.let { cells.addAll(it) }
            for(row in cells) {
                val video=row.optJSONArray("video_data")?.objects()?.firstOrNull() ?: continue
                // 合集、话题这类卡片没有 series_id，不能当剧集展示。
                val id=video.str("series_id")
                if(!id.matches(Regex("[0-9]{1,30}"))) continue
                val title=row.optJSONObject("search_high_light")?.optJSONObject("title")?.str("text").orEmpty().ifEmpty { video.str("title") }
                val count=video.optInt("episode_cnt")
                list+=Series(id,title,cleanUrl(video.str("cover").ifEmpty { video.str("cover_url") }),video.str("video_desc"),if(count>0) "全 $count 集" else video.str("rec_text"),video.str("sub_title"))
            }
        }
        return SearchBatch(list,tab.optBoolean("has_more",false),SearchCursor(tab.str("search_id"),tab.str("passback"),tab.str("next_offset").toIntOrNull(),clock()))
    }
    private fun SearchCursor?.isExhausted() = this==null || (searchId.isBlank() && passback.isBlank() && offset==null)
    /**
     * 设备注册：官方 App 启动时用一次，换取可以长期复用的 install_id/device_id。
     * 搜索等接口只对已注册设备返回数据，未注册时会被风控挡下（HTTP 200 空响应）。
     */
    fun registerDevice(): DeviceRegistration {
        val now=clock()
        val request=Request.Builder().url("$REGISTER_API?aid=8662&version_code=71332&channel=update_64&package=com.phoenix.read&_rticket=$now&use_store_region_cookie=1&okhttp_version=4.2.243.31-douyin")
            .header("User-Agent",VendorConstants.APP_UA)
            .header("Accept","application/json")
            .header("Accept-Language","zh-CN,zh;q=0.9")
            .header("log-encode-type","gzip")
            .header("x-ss-req-ticket",now.toString())
            .header("x-vc-bdturing-sdk-version","3.7.2.cn")
            .header("Cookie","store-region=cn-zj; store-region-src=did;")
            .post(registrationPayload().toByteArray(Charsets.UTF_8).toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        val raw=RequestScope.execute(http.newCall(request)) { response ->
            if(!response.isSuccessful) throw IOException("设备注册失败（HTTP ${response.code}）")
            response.body?.bytes()?.toString(Charsets.UTF_8).orEmpty()
        }
        val deviceId=Regex("\"device_id\"\\s*:\\s*\"?([0-9]{1,30})").find(raw)?.groupValues?.get(1)
        val installId=Regex("\"install_id\"\\s*:\\s*\"?([0-9]{1,30})").find(raw)?.groupValues?.get(1)
        if(deviceId==null || installId==null) throw IOException("设备注册被拒绝")
        return DeviceRegistration(installId,deviceId)
    }
    /** 官方 `ss_app_log` 注册包的等价 JSON，字段跟 App 的查询参数保持一致。 */
    private fun registrationPayload(): String {
        val now=clock()
        val androidId=(1..8).joinToString("") { "%02x".format(kotlin.random.Random.nextInt(256)) }
        val openudid=digest("MD5",androidId.toByteArray()).hex().let { it+digest("MD5",it.toByteArray()).hex().take(8) }
        val header=JSONObject()
            .put("display_name","红果免费短剧").put("aid",8662).put("channel","update_64").put("package","com.phoenix.read")
            .put("app_version","7.1.3.32").put("version_code",71332).put("update_version_code",71332).put("manifest_version_code",71332)
            .put("app_version_minor","7.1.3.32").put("sdk_version","3.7.0-rc.25-fanqie-xiaoshuo-opt").put("sdk_target_version",29)
            .put("git_hash","5b6a0d3").put("sdk_flavor","china").put("guest_mode",0).put("is_system_app",0)
            .put("pre_installed_channel","").put("not_request_sender",0).put("os","Android")
            .put("os_version","16").put("os_api",36)
            .put("device_model","25053RT47C").put("device_brand","Redmi").put("device_manufacturer","Redmi").put("cpu_abi","arm64-v8a")
            .put("release_build","BP2A.250605.031.A3").put("density_dpi",520).put("display_density","xxxhdpi").put("resolution","1280x2772")
            .put("language","zh").put("timezone",8).put("access","wifi").put("rom","1414").put("rom_version","BP2A.250605.031.A3")
            .put("cdid",java.util.UUID.randomUUID().toString())
            .put("sig_hash",ByteArray(16).also { kotlin.random.Random.nextBytes(it) }.hex())
            .put("openudid",openudid)
            .put("clientudid",java.util.UUID.randomUUID().toString())
            .put("ipv6_list",JSONArray().put(JSONObject().put("type","client_anpi").put("value","2409:8a00:1:1:2:3:4:5")))
            .put("region","CN").put("tz_name","Asia/Shanghai").put("tz_offset",28800).put("sim_serial_number",JSONArray())
            .put("oaid_may_support",false).put("req_id",java.util.UUID.randomUUID().toString()).put("device_platform","android")
            .put("custom",JSONObject().put("host_bit",64).put("account_region","cn").put("dragon_device_type","phone"))
            .put("apk_first_install_time",now-2_592_000_000L)
        return JSONObject().put("magic_tag","ss_app_log").put("header",header).put("_gen_time",now).toString()
    }
    fun detail(id: String): Detail {
        require(id.matches(Regex("[0-9]{1,30}")))
        val o=router("$site/detail?series_id=$id").optJSONObject("detail_page")?.optJSONObject("seriesDetail") ?: throw IOException("剧集详情暂不可用")
        val raw=o.optJSONArray("vid_list") ?: throw IOException("分集暂不可用")
        val episodes=(0 until raw.length()).map { raw.getString(it) }
        if(episodes.isEmpty() || episodes.any { !it.matches(Regex("[0-9]{1,30}")) } || episodes.distinct().size!=episodes.size) throw IOException("分集数据异常")
        val cast=people(o)
        // 真人剧已有演员页时保持原行为；网站没有主创时才请求官方详情补出品方。
        val creators=if(cast.isEmpty()) officialCreators(id) else emptyList()
        return Detail(card(o).copy(id=id),episodes,cast.ifEmpty { creators })
    }
    fun stream(id: String, maxQuality: Int = 1080): StreamInfo {
        require(id.matches(Regex("[0-9]{1,30}")))
        val payload="""{"biz_param":{"detail_page_version":0,"device_level":3,"disable_digg_stat":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024},"mixed_video_id_map":{"1004":["$id"]}}""".toByteArray()
        val signed=signer.sign("https://api5-normal-sinfonlineb.fqnovel.com/novel/player/multi_video_model/preload/v1/",VendorConstants.device,payload)
        val data=JSONObject(text(signed.url,signed))
        if(data.optInt("Code",data.optInt("code",0))!=0) throw IOException("播放接口暂不可用")
        val map=data.optJSONObject("data") ?: throw IOException("播放信息为空")
        val entry=map.optJSONObject(id) ?: map.keys().asSequence().mapNotNull { map.optJSONObject(it) }.firstOrNull() ?: throw IOException("播放信息为空")
        val model=entry.optJSONObject("video_model") ?: JSONObject(entry.str("video_model"))
        val raw=model.opt("fallback_api")
        val fallback=when(raw) { is JSONObject -> raw.str("fallback_api"); is JSONArray -> raw.optString(0); else -> runCatching { JSONObject(raw.toString()).str("fallback_api") }.getOrDefault(raw.toString()) }
        if(!fallback.startsWith("https://")) throw IOException("播放地址格式异常")
        val info=JSONObject(text(fallback)).optJSONObject("video_info")?.optJSONObject("data") ?: throw IOException("没有播放资源")
        val videoList=info.optJSONObject("video_list") ?: throw IOException("没有可用清晰度")
        // ByteVC2 resources can expose playable audio but no video track in Android's MP4 extractor.
        // Keep missing legacy codec labels usable, but never select a known incompatible variant.
        val rows=videoList.keys().asSequence().mapNotNull { k -> videoList.optJSONObject(k)?.let { k to it } }.filter {
            it.second.str("main_url").isNotEmpty() && it.second.str("codec_type").lowercase() !in setOf("bytevc2","bvc2")
        }.map { (k,o) -> Triple(qualityNumber(o.str("quality_desc").ifEmpty { o.str("height").ifEmpty { o.str("vheight").ifEmpty { o.str("quality").ifEmpty { k } } } }),k,o) }.sortedByDescending { it.first }.toList()
        val selected=rows.firstOrNull { it.first<=maxQuality } ?: rows.lastOrNull() ?: throw IOException("没有电视可播放的清晰度，请稍后再试或选择其他剧集")
        val item=selected.third; var url=cleanUrl(item.str("main_url")); val seed=info.str("key_seed")
        if(seed.isNotEmpty()) url=MediaCrypto.decryptUrl(url,decode64(seed))
        if(!url.startsWith("https://") && !url.startsWith("http://")) throw IOException("播放地址无效")
        val key=item.str("spade_a").takeIf { it.isNotEmpty() }?.let(MediaCrypto::deriveKey)
        val quality=if(selected.first>0) "${selected.first}P"+(if(selected.first>maxQuality) "（兼容资源）" else "") else "自动"
        return StreamInfo(url,key,quality)
    }
    /**
     * 弹幕：官方弹幕接口按时间段返回，`startMs` 用 30 秒对齐，`durationMs` 是本集总时长。
     * 返回的 [DanmakuPage.nextMs] 是下一个时间段的起点；接口对未注册设备会返回空，按失败处理。
     */
    fun danmaku(videoId: String, seriesId: String, startMs: Long, durationMs: Long): DanmakuPage {
        require(videoId.matches(Regex("[0-9]{1,30}")) && seriesId.matches(Regex("[0-9]{1,30}")))
        require(startMs>=0 && durationMs>startMs && durationMs<=DANMAKU_MAX_DURATION)
        val values=linkedMapOf(
            "aid" to "8662","app_name" to "novelread","channel" to "update_64",
            "device_platform" to "android","manifest_version_code" to "73532",
            "update_version_code" to "73532","version_code" to "73532","version_name" to "7.3.5.32"
        )
        val body=JSONObject()
            .put("comment_source",601).put("server_channel",1000).put("group_id",videoId).put("group_type",30)
            .put("comment_type",20).put("sort",1).put("count",90).put("cursor","").put("aid",8662).put("compliance_status",0)
            .put("business_param",JSONObject().put("book_id",seriesId).put("start_offset_time",startMs)
                .put("playlet_item_duration",durationMs).put("need_danmaku_guide_type",JSONArray().put(1).put(3).put(4).put(2)))
            .toString()
        val signed=signer.sign("$COMMENT_API/$videoId/v1/",values,body.toByteArray(Charsets.UTF_8))
        val raw=text(signed.url,signed)
        if(raw.isBlank()) throw IOException("弹幕接口暂不可用")
        val root=runCatching { JSONObject(raw) }.getOrElse { throw IOException("弹幕接口暂不可用") }
        val code=if(root.has("code")) root.optInt("code",0) else root.optInt("Code",0)
        if(code!=0) throw IOException("弹幕接口暂不可用")
        return parseDanmaku(root,videoId,startMs,durationMs)
    }
    private fun parseDanmaku(root: JSONObject, videoId: String, startMs: Long, durationMs: Long): DanmakuPage {
        val data=root.optJSONObject("data") ?: throw IOException("本集暂无弹幕")
        val next=data.optJSONObject("extra")?.opt("next_query_danmaku_list_time")?.toString()?.toLongOrNull()
            ?: throw IOException("弹幕时间段无效")
        if(next<=startMs) throw IOException("弹幕时间段无效")
        val end=minOf(next,durationMs)
        val total=runCatching {
            JSONObject(data.optJSONObject("common_list_info")?.str("cursor").orEmpty()).optInt("danmaku_count",0)
        }.getOrDefault(0)
        val rows=data.optJSONArray("data_list") ?: JSONArray()
        val seen=mutableSetOf<String>(); val items=mutableListOf<DanmakuComment>()
        for(row in rows.objects()) {
            val comment=row.optJSONObject("comment") ?: continue
            val common=comment.optJSONObject("common") ?: continue
            if(common.str("group_id")!=videoId || common.str("status")!="1") continue
            val position=comment.optJSONObject("expand")?.str("offset_time")?.toLongOrNull() ?: continue
            val id=comment.str("comment_id"); if(id.isEmpty() || id.length>120 || !seen.add(id)) continue
            val text=danmakuText(common.optJSONObject("content")?.str("text").orEmpty())
            if(text.isEmpty() || position<startMs || position>=end) continue
            items+=DanmakuComment(id,text,position)
            if(items.size==90) break
        }
        items.sortBy { it.timeMs }
        return DanmakuPage(items,startMs,end,total)
    }
    private fun danmakuText(raw: String): String {
        val clean=raw.filter { it.code>=32 && it!='\u2028' && it!='\u2029' }.replace(Regex("\\s+")," ").trim()
        // Keep the original `[偷笑]` placeholder; the renderer maps it to a Fluent Emoji image.
        return if(clean.length>180) clean.take(180)+"…" else clean
    }
    companion object {
        fun extractRouter(html: String): JSONObject {
            val marker=html.indexOf("_ROUTER_DATA"); if(marker<0) throw IOException("页面没有内容数据")
            val first=html.indexOf('{',marker); if(first<0) throw IOException("页面数据格式错误")
            var depth=0; var quoted=false; var escaped=false
            for(i in first until html.length) {
                val ch=html[i]
                if(quoted) { if(escaped) escaped=false else if(ch=='\\') escaped=true else if(ch=='"') quoted=false; continue }
                when(ch) { '"' -> quoted=true; '{','[' -> depth++; '}',']' -> { depth--; if(depth==0) return JSONObject(html.substring(first,i+1)) } }
            }
            throw IOException("页面数据不完整")
        }
        fun qualityNumber(value: String): Int = Regex("2160|1440|1080|720|576|540|480|360").find(value)?.value?.toInt() ?: mapOf("1920" to 1080,"1280" to 720,"1024" to 576,"854" to 480,"640" to 360)[value] ?: value.toIntOrNull() ?: 0
    }
}
