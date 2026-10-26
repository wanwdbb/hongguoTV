// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import org.json.JSONObject
import org.json.JSONArray
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

internal fun JSONArray.objects() = (0 until length()).mapNotNull { optJSONObject(it) }
internal fun JSONObject.str(key: String) = if (isNull(key)) "" else optString(key, "").trim()
/** App 搜索接口：未注册设备会被风控挡下（HTTP 200 空响应），见 [ContentRepository.registerDevice]。 */
private const val SEARCH_API = "https://api5-normal-sinfonlinea.fqnovel.com/reading/bookapi/search/tab/v"
private const val REGISTER_API = "https://log5-applog.fqnovel.com/service/2/device_register/"
private const val COMMENT_API = "https://api5-normal-sinfonlineb.fqnovel.com/novel/commentapi/comment/list"
private const val CREATOR_WORKS_API = "https://api5-sinfnormal.fqnovel.com/novel/commentapi/user/profile"
private const val RELATED_API = "https://api5-normal-sinfonlinec.fqnovel.com/reading/bookapi/plan/v:version/"
private const val QR_CREATE_API = "https://hongguoduanju.com/passport/web/get_qrcode/"
private const val QR_CHECK_API = "https://hongguoduanju.com/passport/web/check_qrconnect/"
private const val QR_WEB = "https://hongguoduanju.com/"
private const val QR_AID = "513755"
private const val QR_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
private const val USER_INFO_API = "https://api5-normal-sinfonlineb.fqnovel.com/reading/user/info/v"
private const val HISTORY_API = "https://api5-normal-sinfonlineb.fqnovel.com/reading/bookapi/read_history/list/v"
private const val FAVORITES_API = "https://api5-normal-sinfonlineb.fqnovel.com/reading/bookapi/bookshelf/video/list/v"
private const val RESERVATIONS_API = "https://api5-normal-sinfonlineb.fqnovel.com/reading/user/subscribe/list/v1/"
private const val RESERVATION_API = "https://api5-normal-sinfonlineb.fqnovel.com/reading/bookapi/search/uncover_subscribe/v:version/"
const val DANMAKU_WINDOW = 30_000L
const val DANMAKU_MAX_DURATION = 86_400_000L
/** 官方搜索第一页只给 6 条（「综合」页），首页拼两页补足一屏。 */
private const val FIRST_PAGE_BATCHES = 2
internal fun cleanUrl(s: String) = s.replace("\\/","/").replace("\\u002F","/",ignoreCase=true).replace("\\u0026","&").replace("&amp;","&")
/** 网站流式 HTML 偶尔会在文本里留下 NUL 等控制字符，单行展示前先清理。 */
internal fun cleanText(s: String) = s.filter { it.code>=32 }.replace(Regex("[\\s\\u00a0\\u3000]+")," ").trim()
data class Series(val id: String, val title: String, val cover: String = "", val description: String = "", val badge: String = "", val tags: String = "", val preview: Boolean = false, val bookType: Int = 0) {
    fun toJson() = JSONObject().put("id",id).put("title",title).put("cover",cover).put("description",description).put("badge",badge).put("tags",tags).put("preview",preview).put("bookType",bookType)
    companion object { fun fromJson(o: JSONObject) = Series(o.str("id"),o.str("title"),o.str("cover"),o.str("description"),o.str("badge"),o.str("tags"),o.optBoolean("preview",false),o.optInt("bookType",0)) }
}
data class Person(val id: String, val name: String, val avatar: String = "", val role: String = "", val fans: Long = 0, val creator: Boolean = false)
/** 作者/主创页：同一演员或出品方的简介与作品列表。 */
data class CreatorPage(val id: String, val name: String, val avatar: String = "", val description: String = "", val roles: List<String> = emptyList(), val fans: Long = 0, val works: List<Series> = emptyList())
data class Detail(val series: Series, val episodes: List<String>, val people: List<Person> = emptyList(), val related: List<Series> = emptyList(), val extrasLoaded: Boolean = false, val extrasFailed: Boolean = false)
data class RankPosition(val rank: Int?, val heat: String, val status: String = "")
data class ComicRanking(val positions: Map<String,RankPosition>, val totalPages: Int, val updatedText: String)
data class CatalogPage(val items: List<Series>, val hasMore: Boolean, val ranking: ComicRanking? = null, val genres: List<CatalogGenre> = emptyList())
data class StreamInfo(val url: String, val key: ByteArray?, val quality: String)
data class DeviceRegistration(val installId: String, val deviceId: String)
/** 扫码登录二维码；只把图片和过期时间交给界面，二维码 token 留在 repository 内。 */
data class LoginQr(val image: String, val expiresAt: Long) {
    override fun toString() = "LoginQr(expiresAt=$expiresAt,image=${image.length}chars)"
}
enum class LoginQrStatus { NEW, SCANNED, CONFIRMED, EXPIRED }
data class LoginQrPoll(val status: LoginQrStatus, val session: AuthSession? = null) {
    override fun toString() = "LoginQrPoll(status=$status,session=${if(session==null) "none" else "***"})"
}
/** 红果账号资料；`mobile` 始终是客户端脱敏后的号码。 */
data class AuthUser(
    val id: String,
    val name: String,
    val avatar: String = "",
    val mobile: String = "",
    val description: String = "",
    val followers: Long = 0,
    val following: Long = 0,
    val newUser: Boolean = false,
    /** 红果账号的数字用户 ID；只用于收藏接口，不展示、不写日志。 */
    val accountId: String = ""
) {
    fun toJson() = JSONObject().put("id",id).put("name",name).put("avatar",avatar).put("mobile",mobile)
        .put("description",description).put("followers",followers).put("following",following).put("newUser",newUser)
        .put("accountId",accountId)
    companion object {
        fun fromJson(o: JSONObject): AuthUser? {
            val id=o.str("id"); val name=o.str("name")
            if(id.isBlank() && name.isBlank()) return null
            return AuthUser(id,name,cleanUrl(o.str("avatar")),o.str("mobile"),o.str("description"),
                o.optLong("followers",0),o.optLong("following",0),o.optBoolean("newUser",false),o.str("accountId"))
        }
    }
}
/** 账号观看记录；`episodeId` / `episodeIndex` 用于从账号进度继续播放。 */
data class AccountHistoryItem(
    val series: Series,
    val episodeId: String = "",
    val episodeIndex: Int = 0,
    val position: Long = 0,
    val duration: Long = 0,
    val readAt: Long = 0
)
/** 账号预约记录；`itemId` 同时用于取消预约，`online` 为 false 时没有可播放剧集。 */
data class AccountReservation(
    val series: Series,
    val itemType: Int = 14,
    val online: Boolean = false,
    val expectedAt: Long = 0,
    val subscribed: Boolean = true,
    val expectedText: String = ""
)
/** 账号记录页的统一返回，避免三个列表页各自维护一套类型。 */
data class AccountPage(
    val items: List<Series>,
    val history: List<AccountHistoryItem> = emptyList(),
    val reservations: List<AccountReservation> = emptyList()
)
/** 登录会话：服务端下发的 Cookie 与 X-Tt-Token；只保存在应用私有存储，不写日志。 */
data class AuthSession(val cookies: Map<String,String>, val token: String, val user: AuthUser) {
    fun toJson() = JSONObject().put("cookies",JSONObject().also { target -> cookies.forEach { (k,v) -> target.put(k,v) } })
        .put("token",token).put("user",user.toJson())
    override fun toString() = "AuthSession(user=${user.name},cookies=${cookies.size},token=${if(token.isBlank()) "none" else "***"})"
    companion object {
        fun fromJson(o: JSONObject): AuthSession? {
            val user=o.optJSONObject("user")?.let(AuthUser::fromJson) ?: return null
            val cookies=o.optJSONObject("cookies")?.let { target ->
                target.keys().asSequence().associateWith { target.optString(it,"") }.filterValues { it.isNotBlank() }
            }.orEmpty()
            return AuthSession(cookies,o.str("token"),user)
        }
    }
}
class SearchSessionExpiredException: IOException("搜索结果已过期，请从第 1 页重新搜索")

class ContentRepository(val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).callTimeout(45,TimeUnit.SECONDS).build(), private val clock: () -> Long = System::currentTimeMillis) {
    private val signer = Signer()
    private val site = "https://hongguoduanju.com"
    private val authLock=Any()
    private val authCookies=linkedMapOf<String,String>()
    @Volatile private var authToken=""
    @Volatile private var authUser: AuthUser?=null
    /** 扫码登录的一次性会话；二维码 token 不离开 repository，也不进入日志。 */
    private val qrLock=Any()
    private var qrGeneration=0
    private var qrSession: QrSession?=null
    private class QrSession(val client: OkHttpClient,val store: QrCookieStore,val qrToken: String) {
        @Volatile var appToken=""
        override fun toString() = "QrSession(appToken=${if(appToken.isBlank()) "none" else "***"})"
    }
    /** 手动维护扫码会话 Cookie，避免依赖 OkHttp 的全局 CookieJar，也便于单测检查请求头。 */
    private class QrCookieStore {
        private val cookies=mutableListOf<Cookie>()
        @Synchronized fun save(response: Response) {
            val url=response.request.url
            response.headers("Set-Cookie").forEach { raw ->
                val cookie=runCatching { Cookie.parse(url,raw) }.getOrNull() ?: return@forEach
                cookies.removeAll { it.name==cookie.name && it.domain==cookie.domain && it.path==cookie.path }
                if(cookie.expiresAt>System.currentTimeMillis()) cookies.add(cookie)
            }
        }
        @Synchronized fun header(url: HttpUrl): String = cookies
            .filter { it.expiresAt>System.currentTimeMillis() && it.matches(url) }
            .joinToString("; ") { "${it.name}=${it.value}" }
        @Synchronized fun snapshot(): Map<String,String> = cookies
            .filter { it.expiresAt>System.currentTimeMillis() }
            .associate { it.name to it.value }
    }
    /** 官方接口统一使用较新的版本号；网站接口仍沿用 [VendorConstants.device]。 */
    private fun officialValues() = VendorConstants.device.toMutableMap().also {
        for(k in listOf("version_code","manifest_version_code","update_version_code","pv_player")) it[k]="72232"
        it["version_name"]="7.2.2.32"
    }
    /** 作者作品、相关作品等新版接口按版本号开量；旧版本会返回空数据，不能复用 [officialValues]。 */
    private fun recentOfficialValues() = VendorConstants.device.toMutableMap().also {
        for(k in listOf("version_code","manifest_version_code","update_version_code","pv_player")) it[k]="73932"
        it["version_name"]="7.3.9.32"
    }
    private data class SearchCursor(val searchId: String, val passback: String, val offset: Int?, val updatedAt: Long)
    private val searchCursors=linkedMapOf<Triple<ContentType,String,Int>,SearchCursor>()
    private fun responseText(response: Response): String {
        val body = response.body ?: throw IOException("内容响应为空")
        if (body.contentLength() > 16*1024*1024) throw IOException("内容响应过大")
        val input=body.byteStream(); val out=java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192)
        while(out.size()<=16*1024*1024) { val n=input.read(buffer); if(n<0) break; out.write(buffer,0,n) }; val bytes=out.toByteArray()
        if(bytes.size > 16*1024*1024) throw IOException("内容响应过大")
        return String(bytes,Charsets.UTF_8)
    }
    private fun authRequestHeaders(): Map<String,String> {
        val snapshot=synchronized(authLock) { if(authUser==null) null else cookieHeader(authCookies) to authToken }
        if(snapshot==null) return emptyMap()
        val (cookie,token)=snapshot
        return buildMap {
            if(cookie.isNotBlank()) put("Cookie",cookie)
            if(token.isNotBlank()) put("X-Tt-Token",token)
        }
    }
    private fun isOfficialHost(url: String) = runCatching {
        val host=URI(url).host?.lowercase() ?: return@runCatching false
        host=="fqnovel.com" || host.endsWith(".fqnovel.com")
    }.getOrDefault(false)
    private fun text(url: String, signed: Signer.Request? = null, headers: Map<String,String> = emptyMap()): String {
        val request = Request.Builder().url(url).header("User-Agent",VendorConstants.VIDEO_UA).header("Accept-Language","zh-CN,zh;q=0.9")
        signed?.headers?.forEach { (k,v) -> request.header(k,v) }
        if(isOfficialHost(url)) authRequestHeaders().forEach { (k,v) -> request.header(k,v) }
        headers.forEach { (k,v) -> request.header(k,v) }
        signed?.body?.let { request.post(it.toRequestBody("application/json; charset=utf-8".toMediaType())) }
        return RequestScope.execute(http.newCall(request.build())) { response ->
            if (!response.isSuccessful) throw IOException("内容请求失败（HTTP ${response.code}）")
            responseText(response)
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
    private fun officialCreators(id: String): List<Person> {
        val body=JSONObject().put("series_id",id).put("biz_param",JSONObject()
            .put("video_id_type",1).put("source",5).put("detail_page_version",1)
        ).toString().toByteArray(Charsets.UTF_8)
        val signed=signer.sign("https://api5-normal-sinfonlinea.fqnovel.com/novel/player/video_detail/v1/",officialValues(),body)
        val root=JSONObject(text(signed.url,signed))
        if(root.optInt("code",-1)!=0) return emptyList()
        val users=root.optJSONObject("data")?.optJSONObject("video_data")?.optJSONArray("main_creator_users") ?: return emptyList()
        return (0 until users.length()).mapNotNull { index ->
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
    }
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
        val signed=signer.sign("$CREATOR_WORKS_API/$userId/v1",recentOfficialValues(),body)
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
    private fun relatedWork(item: JSONObject): Series? {
        val nested=item.optJSONObject("video_detail")?.optJSONArray("video_detail_list")?.optJSONObject(0)
        val id=item.str("series_id").ifEmpty { nested?.str("series_id").orEmpty() }
        val title=cleanText(item.str("title").ifEmpty { item.str("series_title") })
        if(!id.matches(Regex("[0-9]{1,30}")) || title.isBlank()) return null
        // 预告片没有可播放分集，网站详情会返回 404/500；保留卡片，但在界面上标记为预告。
        val vid=item.str("vid").ifEmpty { nested?.str("vid").orEmpty() }
        val count=item.optInt("episode_cnt",nested?.optInt("episode_cnt",0) ?: 0)
        val preview=vid.isEmpty() && count<=0
        val badge=cleanText(item.optJSONObject("tag_info")?.str("text").orEmpty())
        val subs=item.optJSONArray("sub_title_list")
        val tags=(0 until (subs?.length() ?: 0)).mapNotNull { index ->
            val row=subs?.optJSONObject(index) ?: return@mapNotNull null
            if(row.optInt("data_type",0)==16) null else cleanText(row.str("content")).takeIf { it.isNotBlank() }
        }.take(3).joinToString(" · ")
        return Series(id,title,cleanUrl(item.str("cover").ifEmpty { item.str("series_cover") }),
            cleanText(item.str("series_intro")),badge,tags,preview)
    }
    /**
     * 详情页「相关作品」：官方详情页的 more_related 片单，返回同一系列的其他季/关联剧。
     * 该接口需要 7.3.9 版本号；旧版本或未注册设备会返回空数据。
     */
    fun related(id: String): List<Series> {
        require(id.matches(Regex("[0-9]{1,30}")))
        val values=recentOfficialValues().also {
            it["scene"]="10"; it["from"]="detail_page_more_related"; it["book_id"]=id
            it["new_search_middle_page"]="false"; it["bookstore_tab_type"]="0"
            it["query_history_removed"]="false"; it["user_is_login"]="0"; it["bookstore_tab"]="0"
            it["search_middle_page_version"]="0"; it["pad_column_detail"]="4"
            it["hot_word_exchange"]="false"; it["device_level"]="3"
            it["limit"]="0"; it["offset"]="0"
            it["session_uuid"]=java.util.UUID.randomUUID().toString()
            it["total_chapter_num"]="0"; it["current_chapter_num"]="0"
            it["last_consume_interval"]="0"; it["last_search_page_interval"]="0"
            it["extra_params"]=""; it["last_book_consume_time"]="0"; it["post_id"]="0"
            it["is_horizontal_screen"]="false"; it["reader_single_col_from_ip_strategy"]="false"
        }
        val signed=signer.sign(RELATED_API,values)
        val root=JSONObject(text(signed.url,signed,mapOf("lc" to "101")))
        if(root.optInt("code",-1)!=0) throw IOException("相关作品暂不可用")
        val cells=root.optJSONArray("data")?.objects().orEmpty()
        val cell=cells.firstOrNull { it.str("cell_name")=="相关作品" || it.optInt("show_type",0)==547 }
            ?: return emptyList()
        val rows=cell.optJSONArray("video_data") ?: return emptyList()
        return valid(rows.objects().mapNotNull(::relatedWork)).filter { it.id!=id }
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
        val items=valid(categoryRows(section).objects().map(::card)).map { it.copy(bookType=2) }
        return CatalogPage(items,items.isNotEmpty() && page<lastPage(section),null,categoryGenres(section))
    }
    /** 分类浏览：`全部` 用空题材，其余按网站的分类页地址抓取。 */
    fun category(type: CatalogType, genre: String = "", page: Int = 1): CatalogPage {
        require(page in 1..100)
        require(genre.isEmpty() || genre.matches(Regex("[a-z0-9-]{1,40}"))) { "分类题材格式错误" }
        val path=type.path+(if(genre.isEmpty()) "" else "/$genre")+(if(page>1) "?page=$page" else "")
        val section=categorySection(router("$site$path"))
        val items=valid(categoryRows(section).objects().map(::card)).map { it.copy(bookType=when(type) { CatalogType.REAL -> 2; CatalogType.COMIC_DRAMA -> 3; else -> 0 }) }
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
        try { val result=appSearch(keyword,page,type); if(result.items.isNotEmpty() || !result.hasMore) return result } catch (e: Exception) { RequestScope.ensureActive(); if(e is SearchSessionExpiredException) throw e; /* Website fallback for short dramas only. */ }
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
            val batch=try { searchBatch(keyword,page,type,next) } catch (e: Exception) { RequestScope.ensureActive(); if(batches==0) throw e else break }
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
        return SearchBatch(list.map { it.copy(bookType=if(type==ContentType.SHORT) 2 else 3) },tab.optBoolean("has_more",false),SearchCursor(tab.str("search_id"),tab.str("passback"),tab.str("next_offset").toIntOrNull(),clock()))
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
    /**
     * 恢复登录会话。Cookie 与 X-Tt-Token 只保存在应用私有存储；
     * 这里只负责把它们挂回后续官方接口，不写日志、不进入错误信息。
     */
    fun restoreSession(session: AuthSession?) {
        synchronized(authLock) {
            authCookies.clear()
            if(session==null) {
                authToken=""; authUser=null
            } else {
                authCookies.putAll(session.cookies)
                authToken=session.token; authUser=session.user
            }
        }
    }
    fun currentSession(): AuthSession? = synchronized(authLock) {
        authUser?.let { AuthSession(authCookies.toMap(),authToken,it) }
    }
    fun logout() = restoreSession(null)
    /**
     * 获取红果网页扫码登录二维码。网页接口只返回 PNG data URI 和一次性 token；
     * token 留给 [pollLoginQr] 使用，不返回给界面。
     */
    fun createLoginQr(): LoginQr {
        val client=http.newBuilder().followRedirects(false).followSslRedirects(false).build()
        val store=QrCookieStore()
        val generation=synchronized(qrLock) {
            qrGeneration++
            qrSession=null
            qrGeneration
        }
        val (root,appToken)=qrJson(client,store,qrUrl(QR_CREATE_API,""),"")
        val data=root.optJSONObject("data") ?: throw IOException(qrError(root))
        val token=data.str("token")
        val image=data.str("qrcode")
        if(token.isBlank() || image.isBlank()) throw IOException(qrError(root))
        val expireSeconds=data.optLong("expire_time",0)
        val expiresAt=if(expireSeconds>0) expireSeconds*1000 else clock()+180_000
        val imageData=qrImage(image)
        val session=QrSession(client,store,token).also { it.appToken=appToken }
        synchronized(qrLock) {
            if(qrGeneration!=generation) throw IOException("二维码已失效，请刷新")
            qrSession=session
        }
        return LoginQr(imageData,expiresAt)
    }
    /** 轮询扫码状态；确认后跟随官方 redirect_url，收集 Cookie 并建立登录会话。 */
    fun pollLoginQr(): LoginQrPoll {
        val session=synchronized(qrLock) { qrSession } ?: throw IOException("二维码已失效，请刷新")
        val (root,headerToken)=qrJson(session.client,session.store,qrUrl(QR_CHECK_API,session.qrToken),session.appToken)
        if(headerToken.isNotBlank()) session.appToken=headerToken
        val data=root.optJSONObject("data") ?: throw IOException(qrError(root))
        val extra=data.optJSONObject("extra")
        val redirect=cleanUrl(data.str("redirect_url").ifEmpty { data.str("redirectUrl") }
            .ifEmpty { extra?.str("redirect_url").orEmpty() })
        val status=qrStatus(data.str("status"),redirect.isNotBlank())
            ?: throw IOException(qrError(root).takeIf { it!="二维码服务暂时不可用" } ?: "二维码状态异常，请刷新重试")
        if(status==LoginQrStatus.CONFIRMED) {
            var token=session.appToken
            if(redirect.isNotBlank()) token=runCatching { followQrRedirects(session.client,session.store,redirect,token) }.getOrDefault(token)
            // 落地页可能再补一次 Cookie；失败不覆盖已拿到的登录态。
            token=runCatching { followQrRedirects(session.client,session.store,QR_WEB,token) }.getOrDefault(token)
            val cookies=session.store.snapshot()
            if(!hasLoginCookie(cookies)) throw IOException("登录状态未返回，请刷新二维码重试")
            if(synchronized(qrLock) { qrSession!==session }) throw IOException("二维码已失效，请刷新")
            val auth=completeQrLogin(cookies,token)
            synchronized(qrLock) { if(qrSession===session) qrSession=null }
            return LoginQrPoll(LoginQrStatus.CONFIRMED,auth)
        }
        if(status==LoginQrStatus.EXPIRED) {
            synchronized(qrLock) { if(qrSession===session) qrSession=null }
            return LoginQrPoll(LoginQrStatus.EXPIRED)
        }
        return LoginQrPoll(status)
    }
    fun cancelLoginQr() = synchronized(qrLock) { qrGeneration++; qrSession=null }
    /** 用已登录会话刷新昵称/头像；失败时保留原资料，由界面提示重新登录。 */
    fun refreshUser(): AuthSession {
        if(currentSession()==null) throw IOException("请先登录")
        val signed=signer.sign(USER_INFO_API,recentOfficialValues().also {
            it["check_idfa"]="false"; it["not_need_perm"]="false"; it["is_bind_douyin"]="false"
            it["enable_preference_v709"]="false"; it["code"]="0"; it["need_perm_from_master"]="false"
        })
        val root=runCatching { JSONObject(text(signed.url,signed)) }.getOrElse { throw IOException("账号资料返回异常") }
        if(root.optInt("code",-1)!=0) throw IOException(authError(root))
        val data=root.optJSONObject("data") ?: throw IOException("账号资料为空")
        val current=currentSession() ?: throw IOException("请先登录")
        val accountId=listOf(data.str("req_id"),data.str("biz_user_id"),data.str("user_id"))
            .firstOrNull { it.matches(Regex("[0-9]{1,30}")) }.orEmpty()
        val user=current.user.copy(
            name=data.str("user_name").ifEmpty { current.user.name },
            avatar=cleanUrl(data.str("expand_avatar_url").ifEmpty { data.str("avatar_url") }).ifEmpty { current.user.avatar },
            description=cleanText(data.str("description")).ifEmpty { current.user.description },
            mobile=maskMobile(data.str("mobile")).ifEmpty { current.user.mobile },
            accountId=accountId.ifEmpty { current.user.accountId }
        )
        synchronized(authLock) { authUser=user }
        return currentSession() ?: throw IOException("账号资料为空")
    }
    /** 账号接口统一要求 `lc` 头；签名仍由 [Signer] 负责。 */
    private fun accountHeaders() = mapOf("lc" to "101","x-ss-dp" to "8662")
    private fun responseCode(root: JSONObject) = when {
        root.has("code") -> root.optInt("code",-1)
        root.has("Code") -> root.optInt("Code",-1)
        else -> 0
    }
    private fun accountJson(url: String,values: Map<String,String>,session: AuthSession?=null): JSONObject {
        val signed=signer.sign(url,values)
        val headers=accountHeaders()+if(session==null) emptyMap() else mapOf("Cookie" to cookieHeader(session.cookies),"X-Tt-Token" to session.token)
        val raw=text(signed.url,signed,headers)
        val root=runCatching { JSONObject(raw) }.getOrElse { throw IOException("账号记录返回异常") }
        if(responseCode(root)!=0) throw IOException(authError(root))
        return root
    }
    /** Resolve legacy local records through an exact-ID match, never infer type from title keywords. */
    fun historyBookType(series: Series): Int {
        if(series.bookType in 2..5) return series.bookType
        for(type in listOf(ContentType.SHORT,ContentType.COMIC)) {
            RequestScope.ensureActive()
            val page=search(series.title,1,type)
            page.items.firstOrNull { it.id==series.id }?.bookType?.takeIf { it in 2..5 }?.let { return it }
        }
        return 0
    }
    /** Captured official read_history/update/v JSON schema; position is milliseconds, vid_index is one-based. */
    fun uploadHistory(session: AuthSession,rows: List<HistoryCheckpoint>) {
        require(rows.isNotEmpty() && rows.size<=20)
        ensureHistorySession(session)
        val body=JSONObject().put("update_datas",JSONArray(rows.map { it.json() })).toString().toByteArray(Charsets.UTF_8)
        val signed=signer.sign("https://api5-normal-sinfonlineb.fqnovel.com/reading/bookapi/read_history/update/v",recentOfficialValues(),body)
        // Explicit captured-session headers prevent a concurrent account switch from changing the recipient.
        val headers=accountHeaders()+mapOf("Cookie" to cookieHeader(session.cookies),"X-Tt-Token" to session.token)
        val raw=text(signed.url,signed,headers)
        val result=try { JSONObject(raw) } catch(_: Exception) { throw IOException("播放记录同步返回异常") }
        if(!result.has("code") && !result.has("Code")) throw IOException("播放记录同步未确认")
        if(responseCode(result)!=0) throw IOException(authError(result))
    }
    data class VideoProgress(val seriesId: String,val episodeId: String,val episodeIndex: Int,val position: Long,val readAt: Long)
    fun accountVideoProgress(session: AuthSession = currentSession() ?: throw IOException("请先登录")): List<VideoProgress> {
        ensureHistorySession(session)
        val values=recentOfficialValues().also { it["limit"]="0"; it["book_types"]="2,3,4"; it["last_min_read_timestamp_ms_list"]="" }
        val root=accountJson("https://api5-normal-sinfonlineb.fqnovel.com/reading/bookapi/read_progress/list/v",values,session)
        ensureHistorySession(session)
        if(!root.has("code") && !root.has("Code")) throw IOException("续播进度读取未确认")
        return root.optJSONArray("video_data")?.objects().orEmpty().mapNotNull { row ->
            val id=row.str("series_id"); val episode=row.str("vid")
            if(id.isBlank() || episode.isBlank()) null else VideoProgress(id,episode,(row.optInt("vid_index",1)-1).coerceAtLeast(0),
                row.optLong("current_play_time",0).coerceAtLeast(0),row.optLong("last_watch_time",0))
        }
    }
    /** Read both stores: history is presentation metadata, progress is an independent resume source. */
    fun accountResumeHistory(session: AuthSession = currentSession() ?: throw IOException("请先登录"),known: List<Series> = emptyList()): AccountPage {
        val history=accountHistory(requireAllTypes=true,session=session)
        val progress=accountVideoProgress(session)
        val rows=history.history.associateBy { it.series.id }.toMutableMap()
        val metadata=known.associateBy { it.id }
        progress.forEach { remote ->
            val old=rows[remote.seriesId]
            val series=old?.series ?: metadata[remote.seriesId] ?: return@forEach
            val order=old?.let { WatchProgressOrder.compare(remote.episodeIndex,remote.position,it.episodeIndex,it.position) } ?: 1
            if(order>0 || (order==0 && remote.readAt>(old?.readAt ?: 0L))) rows[remote.seriesId]=AccountHistoryItem(series,remote.episodeId,remote.episodeIndex,
                remote.position,if(old?.episodeId==remote.episodeId) old.duration else 0,remote.readAt)
        }
        ensureHistorySession(session)
        val sorted=rows.values.sortedByDescending { it.readAt }
        return AccountPage(sorted.map { it.series },history=sorted)
    }
    fun uploadVideoProgress(session: AuthSession,rows: List<HistoryCheckpoint>) {
        require(rows.isNotEmpty() && rows.size<=20)
        ensureHistorySession(session)
        val body=JSONObject().put("books",JSONArray(rows.map { it.progressJson() })).toString().toByteArray(Charsets.UTF_8)
        val signed=signer.sign("https://api5-normal-sinfonlineb.fqnovel.com/reading/bookapi/read_progress/upload/v",recentOfficialValues(),body)
        val headers=accountHeaders()+mapOf("Cookie" to cookieHeader(session.cookies),"X-Tt-Token" to session.token)
        val root=try { JSONObject(text(signed.url,signed,headers)) } catch(_: Exception) { throw IOException("续播进度返回异常") }
        if(!root.has("code") && !root.has("Code")) throw IOException("续播进度上传未确认")
        if(responseCode(root)!=0) throw IOException(authError(root))
    }
    fun uploadPlaybackCheckpoint(session: AuthSession,rows: List<HistoryCheckpoint>) {
        uploadHistory(session,rows)
        uploadVideoProgress(session,rows)
    }
    private fun ensureHistorySession(session: AuthSession) {
        val active=currentSession()
        if(active?.user?.id!=session.user.id || active.token!=session.token || active.cookies!=session.cookies)
            throw IOException("登录账号已变化，停止同步")
    }
    private fun firstText(o: JSONObject,vararg keys: String): String {
        for(key in keys) { val value=o.str(key); if(value.isNotBlank()) return value }
        return ""
    }
    /** 兼容布尔、0/1 与 "true"/"false" 三种字段写法。 */
    private fun accountBool(o: JSONObject,key: String,default: Boolean=false): Boolean {
        if(!o.has(key) || o.isNull(key)) return default
        return when(val value=o.opt(key)) {
            is Boolean -> value
            is Number -> value.toInt()!=0
            is String -> value.equals("true",ignoreCase=true) || value=="1"
            else -> default
        }
    }
    private fun numericId(vararg values: String) = values.firstOrNull { it.matches(Regex("[0-9]{1,30}")) }.orEmpty()
    /** 接口既可能返回数组，也可能把记录放在对象里；统一展开成行列表。 */
    private fun accountRows(data: JSONObject,vararg keys: String): List<JSONObject> {
        for(key in keys) {
            data.optJSONArray(key)?.let { return it.objects() }
            data.optJSONObject(key)?.let { holder ->
                val nested=holder.optJSONArray("list") ?: holder.optJSONArray("items") ?: holder.optJSONArray("data_list")
                if(nested!=null) return@let nested.objects()
                if(firstText(holder,"book_id","series_id","item_id","id").isNotBlank()) return@let listOf(holder)
                if(holder.length()>0) return@let holder.keys().asSequence().mapNotNull { holder.optJSONObject(it) }.toList()
            }
        }
        return emptyList()
    }
    private fun accountTagList(o: JSONObject,vararg keys: String): List<String> {
        for(key in keys) {
            val array=o.optJSONArray(key) ?: continue
            val values=(0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let { firstText(it,"name","text","content","title") }
                    ?: array.optString(index).trim().takeIf { it.isNotEmpty() }
            }.filter { it.isNotBlank() }
            if(values.isNotEmpty()) return values
        }
        return emptyList()
    }
    /**
     * 账号观看记录。官方客户端按 `book_type` 分请求，短剧/漫剧/其他类型需要合并；
     * 这里按最近阅读时间排序并按剧集去重，避免同一部剧在多个类型里重复出现。
     */
    fun accountHistory(requireAllTypes: Boolean = false,session: AuthSession = currentSession() ?: throw IOException("请先登录")): AccountPage {
        ensureHistorySession(session)
        var succeeded=false
        var failure: Throwable?=null
        val records=linkedMapOf<String,AccountHistoryItem>()
        for(bookType in listOf("2","3","4","5")) {
            ensureHistorySession(session)
            val values=recentOfficialValues().also { map ->
                map["offset"]="0"; map["limit"]="30"; map["book_type"]=bookType
                map["query_soft_deleted"]="false"; map["is_first_load"]="false"
                map["last_min_read_timestamp_ms"]="0"; map["full_field"]="false"
            }
            val root=try { accountJson(HISTORY_API,values,session) } catch(problem: Exception) {
                RequestScope.ensureActive()
                failure=failure ?: problem
                continue
            }
            succeeded=true
            val data=root.optJSONObject("data") ?: continue
            for(row in data.optJSONArray("data_list")?.objects().orEmpty()) {
                val item=accountHistoryItem(row,bookType.toInt()) ?: continue
                val previous=records[item.series.id]
                if(previous==null || item.readAt>previous.readAt) records[item.series.id]=item
            }
        }
        ensureHistorySession(session)
        if(requireAllTypes && failure!=null) throw failure
        if(!succeeded) throw failure ?: IOException("账号记录暂时不可用")
        val history=records.values.sortedByDescending { it.readAt }
        return AccountPage(history.map { it.series },history=history)
    }
    private fun accountHistoryItem(row: JSONObject,bookType: Int): AccountHistoryItem? {
        if(accountBool(row,"is_delete")) return null
        val video=row.optJSONObject("video_data")
        val id=numericId(
            video?.str("series_id").orEmpty(),video?.str("book_id_str").orEmpty(),row.str("book_id_str"),row.str("book_id"),
            video?.str("book_id").orEmpty()
        )
        val title=cleanText(firstText(video ?: JSONObject(),"series_title","title","book_name")
            .ifEmpty { firstText(row,"series_title","book_name","title","name") })
        if(id.isBlank() || title.isBlank()) return null
        val cover=cleanUrl(firstText(row,"thumb_url","cover","cover_url","image_url")
            .ifEmpty { firstText(video ?: JSONObject(),"series_cover","cover","thumb_url") })
        val description=cleanText(firstText(video ?: JSONObject(),"series_intro","description","abstract")
            .ifEmpty { firstText(row,"series_intro","book_abstract","description") })
        val count=video?.optInt("episode_cnt",0)?.takeIf { it>0 } ?: row.optInt("episode_cnt",0)
        val update=row.optJSONObject("update_info")?.let { firstText(it,"update_tag_text","tag_text") }.orEmpty()
        val badge=update.ifEmpty {
            firstText(row,"episode_right_text").ifEmpty { if(count>0) "全 $count 集" else "" }
        }
        val tags=accountTagList(row,"category_list","tags").joinToString(" · ")
        val series=Series(id,title,cover,description,badge,tags,preview=false,bookType=bookType)
        return AccountHistoryItem(
            series=series,
            episodeId=row.str("vid").ifEmpty { row.str("video_id") },
            episodeIndex=(row.optInt("vid_index",1)-1).coerceAtLeast(0),
            position=row.optLong("current_play_position",0).coerceAtLeast(0),
            duration=row.optLong("duration",0).coerceAtLeast(0),
            readAt=row.optLong("read_timestamp_ms",0)
        )
    }
    /** 账号收藏：短剧收藏在 `bookshelf/video/list/v` 的 `video_shelf_info` 中。 */
    fun accountFavorites(): AccountPage {
        val session=currentSession() ?: throw IOException("请先登录")
        val userId=session.user.accountId
        if(userId.isBlank()) throw IOException("账号资料缺少用户标识，请到账号页刷新资料后重试")
        val values=recentOfficialValues().also { map ->
            map["server_time"]=(clock()/1000).toString()
            map["target_user_id"]=userId
        }
        val root=accountJson(FAVORITES_API,values)
        val data=root.optJSONObject("data") ?: return AccountPage(emptyList())
        val items=accountRows(data,"video_shelf_info","video_shelf","book_list","data_list","books")
            .mapNotNull(::accountShelfItem).distinctBy { it.id }
        return AccountPage(items)
    }
    private fun accountShelfItem(row: JSONObject): Series? {
        val nested=row.optJSONObject("video_data") ?: row.optJSONObject("series_info")
            ?: row.optJSONObject("book_info") ?: row
        val id=numericId(
            firstText(nested,"series_id","book_id_str","book_id","item_id","id"),
            firstText(row,"book_id_str","series_id","book_id","item_id","id")
        )
        val title=cleanText(firstText(nested,"series_title","book_name","title","name")
            .ifEmpty { firstText(row,"series_title","book_name","title","name") })
        if(id.isBlank() || title.isBlank()) return null
        val cover=cleanUrl(firstText(nested,"series_cover","cover","thumb_url","cover_url","image_url")
            .ifEmpty { firstText(row,"series_cover","cover","thumb_url","cover_url","image_url") })
        val description=cleanText(firstText(nested,"series_intro","description","abstract")
            .ifEmpty { firstText(row,"series_intro","description","abstract") })
        val count=nested.optInt("episode_cnt",row.optInt("episode_cnt",0))
        val update=firstText(nested,"episode_right_text","update_tag_text","rec_text")
        val badge=update.ifEmpty { if(count>0) "全 $count 集" else "" }
        val tags=accountTagList(nested,"tags","category_list").ifEmpty {
            accountTagList(row,"tags","category_list")
        }.joinToString(" · ")
        return Series(id,title,cover,description,badge,tags,preview=false)
    }
    /** 账号预约：待上线与已上线分别请求，合并后按上线状态和预计时间排序。 */
    fun accountReservations(): AccountPage {
        currentSession() ?: throw IOException("请先登录")
        var succeeded=false
        var failure: Throwable?=null
        val records=linkedMapOf<String,AccountReservation>()
        for(online in listOf(false,true)) {
            val values=recentOfficialValues().also { map ->
                map["offset"]="0"; map["limit"]="30"; map["is_online"]=online.toString()
                map["tab_type"]="13"; map["subscribe_order_type"]="0"; map["swipe_type"]="0"
                map["need_calendar_schema"]="false"; map["subscribe_offset"]="0"
            }
            val root=try { accountJson(RESERVATIONS_API,values) } catch(problem: Exception) {
                failure=failure ?: problem
                continue
            }
            succeeded=true
            val data=root.optJSONObject("data") ?: continue
            for(row in accountRows(data,"subscribe_items","items","list")) {
                val item=accountReservationItem(row) ?: continue
                records[item.series.id]=item
            }
        }
        if(!succeeded) throw failure ?: IOException("预约记录暂时不可用")
        val reservations=records.values.sortedWith(
            compareBy<AccountReservation> { it.online }
                .thenBy { if(it.expectedAt>0) it.expectedAt else Long.MAX_VALUE }
                .thenBy { it.series.title }
        )
        return AccountPage(reservations.map { it.series },reservations=reservations)
    }
    private fun accountReservationItem(row: JSONObject): AccountReservation? {
        val id=numericId(row.str("item_id").ifEmpty { row.str("item_id_str") },row.str("series_id"),row.str("book_id"))
        val title=cleanText(firstText(row,"name","title","book_name","series_name"))
        if(id.isBlank() || title.isBlank()) return null
        val cover=cleanUrl(firstText(row,"cover","thumb_url","cover_url","image_url"))
        val description=cleanText(firstText(row,"item_desc","description","summary"))
        val online=accountBool(row,"is_online")
        val expectedAt=row.optLong("expected_publish_time",
            row.optLong("schedule_publish_time",row.optLong("publish_time",0)))
        val expectedText=cleanText(firstText(row,"expected_rec_text","publish_time_text","rec_text"))
        val tag=cleanText(row.optJSONObject("tag_info")?.let { firstText(it,"text","name") }.orEmpty())
        val tags=accountTagList(row,"categories","tags","sub_title_list").ifEmpty {
            listOfNotNull(tag.takeIf { it.isNotBlank() })
        }.joinToString(" · ")
        val badge=listOf(
            expectedText.ifBlank { if(online) "已上线" else "待上线" },
            tag
        ).filter { it.isNotBlank() }.distinct().joinToString(" · ")
        val series=Series(id,title,cover,description,badge,tags,preview=!online)
        return AccountReservation(
            series=series,
            itemType=row.optInt("item_type",14),
            online=online,
            expectedAt=expectedAt,
            subscribed=accountBool(row,"has_subscribed",true),
            expectedText=expectedText
        )
    }
    /**
     * 预约/取消预约。官方请求用 `op_type=1` 表示预约、`2` 表示取消，
     * `item_type=14` 对应短剧预约项；`wish_list_all_del=0` 避免清空其它预约。
     */
    fun setReservation(itemId: String, active: Boolean, itemType: Int = 14): Boolean {
        if(!active && itemId.isBlank()) throw IllegalArgumentException("预约信息为空")
        require(itemId.matches(Regex("[0-9]{1,30}"))) { "预约信息无效" }
        require(itemType in 1..99) { "预约类型无效" }
        currentSession() ?: throw IOException("请先登录")
        val numericId=itemId.toLongOrNull() ?: throw IllegalArgumentException("预约信息无效")
        val body=JSONObject()
            .put("item_id",numericId)
            .put("item_type",itemType)
            .put("op_type",if(active) 1 else 2)
            .put("item_id_str",itemId)
            .put("wish_list_all_del",0)
            .put("shark_param",JSONObject()
                .put("enter_from","ShortSeriesActivity")
                .put("previous_page","MainFragmentActivity")
                .put("page_list","MainFragmentActivity,ShortSeriesActivity"))
        val signed=signer.sign(RESERVATION_API,recentOfficialValues(),body.toString().toByteArray(Charsets.UTF_8))
        val raw=text(signed.url,signed,accountHeaders())
        val root=runCatching { JSONObject(raw) }.getOrElse { throw IOException("预约服务返回异常") }
        if(responseCode(root)!=0) throw IOException(authError(root))
        return active
    }
    fun reserve(itemId: String,itemType: Int = 14)=setReservation(itemId,true,itemType)
    fun cancelReservation(itemId: String,itemType: Int = 14)=setReservation(itemId,false,itemType)
    private fun qrUrl(base: String,token: String): String {
        val values=linkedMapOf(
            "aid" to QR_AID,
            "next" to QR_WEB,
            "language" to "zh",
            "account_sdk_source" to "web",
            "request_host" to QR_WEB,
            "passport_jssdk_version" to "2.4.13",
            "passport_jssdk_type" to "normal",
            "is_from_ttaccountsdk" to "1",
            "p_js_v" to "2.4.13",
            "p_js_t" to "pro",
            "p_zt" to "3.3.5",
            "p_ver" to "1.0.29",
            "p_bd" to "1.0.1.20"
        )
        if(token.isNotBlank()) values["token"]=token
        return base+"?"+Signer.encode(values)
    }
    private fun qrJson(client: OkHttpClient,store: QrCookieStore,url: String,appToken: String): Pair<JSONObject,String> {
        val httpUrl=url.toHttpUrlOrNull() ?: throw IOException("二维码地址异常")
        val builder=Request.Builder().url(httpUrl)
            .header("User-Agent",QR_UA)
            .header("Accept","application/json, text/plain, */*")
            .header("Accept-Language","zh-CN,zh;q=0.9")
            .header("Referer",QR_WEB)
            .get()
        val cookie=store.header(httpUrl)
        if(cookie.isNotBlank()) builder.header("Cookie",cookie)
        val csrf=store.snapshot()["passport_csrf_token"].orEmpty()
        if(csrf.isNotBlank()) builder.header("x-tt-passport-csrf-token",csrf)
        if(appToken.isNotBlank()) builder.header("X-Tt-Token",appToken)
        return RequestScope.execute(client.newCall(builder.build())) { response ->
            store.save(response)
            if(!response.isSuccessful) throw IOException("二维码服务请求失败（HTTP ${response.code}）")
            val root=runCatching { JSONObject(responseText(response)) }.getOrElse { throw IOException("二维码服务返回异常") }
            root to response.header("X-Tt-Token").orEmpty()
        }
    }
    private fun followQrRedirects(client: OkHttpClient,store: QrCookieStore,start: String,appToken: String): String {
        var current=start.toHttpUrlOrNull() ?: throw IOException("扫码登录跳转地址异常")
        var token=appToken
        repeat(6) {
            val builder=Request.Builder().url(current)
                .header("User-Agent",QR_UA)
                .header("Accept","text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
                .header("Accept-Language","zh-CN,zh;q=0.9")
                .header("Referer",QR_WEB)
                .get()
            val cookie=store.header(current)
            if(cookie.isNotBlank()) builder.header("Cookie",cookie)
            val next=RequestScope.execute(client.newCall(builder.build())) { response ->
                store.save(response)
                val headerToken=response.header("X-Tt-Token").orEmpty()
                if(headerToken.isNotBlank()) token=headerToken
                if(response.code in 300..399) {
                    val location=response.header("Location").orEmpty()
                    if(location.isBlank()) throw IOException("扫码登录跳转地址异常")
                    current.resolve(location) ?: throw IOException("扫码登录跳转地址异常")
                } else {
                    if(!response.isSuccessful) throw IOException("扫码登录落地失败（HTTP ${response.code}）")
                    null
                }
            }
            if(next==null) return token
            current=next
        }
        throw IOException("扫码登录跳转次数过多")
    }
    private fun qrStatus(raw: String,hasRedirect: Boolean): LoginQrStatus? {
        return when(raw.trim().lowercase()) {
            "new","waiting","0","1" -> LoginQrStatus.NEW
            "scanned","2" -> LoginQrStatus.SCANNED
            "confirmed" -> LoginQrStatus.CONFIRMED
            "expired","4","5" -> LoginQrStatus.EXPIRED
            "3" -> if(hasRedirect) LoginQrStatus.CONFIRMED else null
            else -> if(hasRedirect) LoginQrStatus.CONFIRMED else null
        }
    }
    private fun hasLoginCookie(cookies: Map<String,String>) = listOf("sessionid","sessionid_ss","sid_tt","sid_guard")
        .any { !cookies[it].isNullOrBlank() }
    /**
     * 扫码确认后先建立临时会话，再尝试读取账号资料。资料接口暂时不可用时保留一个
     * 不含昵称的本地会话，界面显示通用登录提示；只有服务端明确未登录才清空。
     */
    private fun completeQrLogin(cookies: Map<String,String>,token: String): AuthSession {
        val id=qrUserId(cookies)
        synchronized(authLock) {
            authCookies.clear()
            authCookies.putAll(cookies)
            authToken=token
            authUser=AuthUser(id,"")
        }
        return runCatching { refreshUser() }.getOrElse { problem ->
            if(authExpired(problem)) {
                restoreSession(null)
                throw problem
            }
            currentSession() ?: throw IOException("登录状态保存失败")
        }
    }
    private fun qrUserId(cookies: Map<String,String>): String {
        for(key in listOf("uid_tt","uid_tt_ss","sso_uid_tt","sid_tt")) {
            cookies[key]?.takeIf { it.isNotBlank() }?.let { return it }
        }
        val session=cookies["sessionid"].takeUnless { it.isNullOrBlank() }
            ?: cookies["sessionid_ss"].orEmpty()
        if(session.isBlank()) throw IOException("登录状态未返回")
        // 仅用于本地会话标识，不显示、不写日志；避免把 sessionid 本身存成 id。
        return java.util.UUID.nameUUIDFromBytes(session.toByteArray(Charsets.UTF_8)).toString()
    }
    private fun authExpired(problem: Throwable): Boolean {
        val text=problem.message.orEmpty()
        return text.contains("未登录") || text.contains("请先登录") || text.contains("登录已过期") ||
            text.contains("HTTP 401") || text.contains("HTTP 403")
    }
    private fun qrImage(value: String): String {
        val raw=value.trim()
        if(raw.startsWith("data:image/")) return raw
        if(raw.contains("://")) throw IOException("二维码图片格式异常")
        require(raw.isNotBlank()) { "二维码图片为空" }
        return "data:image/png;base64,$raw"
    }
    private fun qrError(root: JSONObject): String {
        val data=root.optJSONObject("data")
        val description=data?.str("description").orEmpty()
        val message=root.str("message").orEmpty()
        val code=data?.optInt("error_code",0) ?: 0
        val text=description.ifBlank { message }.takeUnless { it.equals("success",ignoreCase=true) }.orEmpty()
        return when {
            text.isNotBlank() -> redactSensitive(text).take(160)
            code!=0 -> "二维码服务返回错误（$code）"
            else -> "二维码服务暂时不可用"
        }
    }
    private fun maskMobile(value: String): String {
        val digits=value.filter { it.isDigit() }
        val eleven=when {
            digits.length==11 -> digits
            digits.length==13 && digits.startsWith("86") -> digits.substring(2)
            digits.length>11 -> digits.takeLast(11)
            else -> ""
        }
        return if(eleven.length==11) "${eleven.take(3)}******${eleven.takeLast(2)}" else ""
    }
    private fun cookieHeader(cookies: Map<String,String>) = cookies.entries
        .filter { it.value.isNotBlank() }.joinToString("; ") { (key,value) -> "$key=$value" }
    private fun authError(root: JSONObject): String {
        val data=root.optJSONObject("data")
        val description=data?.str("description").orEmpty()
        val message=root.str("message").orEmpty()
        val code=data?.optInt("error_code",0) ?: 0
        val text=description.ifBlank { message }.takeUnless { it.equals("success",ignoreCase=true) }.orEmpty()
        return when {
            text.isNotBlank() -> redactSensitive(text).take(160)
            code!=0 -> "账号服务返回错误（$code）"
            else -> "账号服务暂时不可用"
        }
    }
    /** 错误提示可能来自服务端；把手机号、二维码 token 和会话键值一并遮掉。 */
    private fun redactSensitive(text: String) = redactPhone(text.replace(
        Regex("(?i)(token|sessionid(?:_ss)?|sid_tt|sid_guard|uid_tt(?:_ss)?|d_ticket)=([^&;\\s]+)")
    ) { "${it.groupValues[1]}=***" })
    private fun redactPhone(text: String) = text.replace(Regex("(?<!\\d)(1[3-9]\\d{9})(?!\\d)")) {
        "${it.value.take(3)}******${it.value.takeLast(2)}"
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
    /** [includeRelated] 只在详情页使用；推荐流和预取不需要额外请求相关作品。 */
    fun detail(id: String, includeRelated: Boolean = false): Detail {
        require(id.matches(Regex("[0-9]{1,30}")))
        val o=router("$site/detail?series_id=$id").optJSONObject("detail_page")?.optJSONObject("seriesDetail") ?: throw IOException("剧集详情暂不可用")
        val raw=o.optJSONArray("vid_list") ?: throw IOException("分集暂不可用")
        val episodes=(0 until raw.length()).map { raw.getString(it) }
        if(episodes.isEmpty() || episodes.any { !it.matches(Regex("[0-9]{1,30}")) } || episodes.distinct().size!=episodes.size) throw IOException("分集数据异常")
        val cast=people(o)
        val basic=Detail(card(o).copy(id=id),episodes,cast)
        return if(includeRelated) enrichDetail(basic) else basic
    }
    /** Optional sections never delay the episode list or direct playback. */
    fun enrichDetail(detail: Detail): Detail {
        RequestScope.ensureActive()
        var failed=false
        val creators=if(detail.people.isEmpty()) optionalSection("creators",emptyList<Person>(),{ failed=true }) { officialCreators(detail.series.id) } else detail.people
        RequestScope.ensureActive()
        val related=optionalSection("related",emptyList<Series>(),{ failed=true }) { related(detail.series.id) }
        RequestScope.ensureActive()
        return detail.copy(people=creators,related=related,extrasLoaded=true,extrasFailed=failed)
    }
    private fun <T> optionalSection(section: String,fallback: T,onFailure: () -> Unit,action: () -> T): T = try { action() }
    catch(problem: Exception) {
        RequestScope.ensureActive()
        onFailure()
        // Error types only: never log response bodies, account data or signed URLs.
        System.err.println("HongguoTV optional_section_failed section=$section type="+problem.javaClass.simpleName)
        fallback
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
