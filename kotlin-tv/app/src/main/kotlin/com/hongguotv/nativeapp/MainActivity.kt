// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.Base64
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.widget.*
import android.text.TextUtils
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.hongguotv.core.*
import java.util.concurrent.Executors
import kotlin.math.roundToInt

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MainActivity: Activity() {
    private companion object {
        const val TAB_RECOMMEND=0
        const val TAB_HOME=1
        const val TAB_SEARCH=2
        const val TAB_CATEGORY=3
        const val TAB_RANK=4
        const val TAB_FAVORITES=5
        const val TAB_HISTORY=6
        const val TAB_SETTINGS=7
        const val TAB_ACCOUNT=8
        const val TAB_RESERVATIONS=9
        /** 分类片单复用时长：这段时间内重复进入同一分类直接显示缓存。 */
        const val CATEGORY_CACHE_MS=10*60*1000L
        /** 推荐最多翻到的批次数与类型页数，避免一直翻到网站的空页。 */
        const val RECOMMEND_MAX_BATCH=8
        const val RECOMMEND_MAX_PAGE=5
    }
    private val bg=TvStyle.background; private val surface=TvStyle.surface
    private val accent=TvStyle.accent; private val white=TvStyle.text; private val muted=TvStyle.muted
    private val main=Handler(Looper.getMainLooper())
    private val io=Executors.newFixedThreadPool(3)
    private val pageWork=PageWork(io,main,{ !isDestroyed },{ foreground })
    private val repository=ContentRepository()
    private val accountHistorySync by lazy { AccountHistorySync(this,repository,::mergeCloudHistory) }
    private val networkCleanup=Executors.newSingleThreadExecutor()
    private val prefetchWorker=Executors.newSingleThreadExecutor()
    private val feedPrefetchWorker=Executors.newSingleThreadExecutor()
    private var prefetchHttp: okhttp3.OkHttpClient?=null
    private var playbackHttp: okhttp3.OkHttpClient?=null
    private val preparedNext=PreparedSlot<RemoteVideo>({ android.os.SystemClock.elapsedRealtime() })
    /** 推荐里前后两部的预取结果：换部时直接取用其中一部。 */
    private val preparedSeries=PreparedCache<FeedReady>(2,{ android.os.SystemClock.elapsedRealtime() },10*60*1000L)
    private var feedPrefetchJob: java.util.concurrent.Future<*>?=null
    /** 每次重新预取或换一批都加一，旧任务发现编号变了就立刻停下。 */
    private var feedPrefetchEpoch=0
    private var feedPrefetchMark=""
    private var prefetchJob: java.util.concurrent.Future<*>?=null
    private var prefetchScope: RequestScope?=null
    private var prefetchedFor=""
    private var readySince=0L
    private lateinit var artwork: ArtworkLoader
    /**
     * 预取好的一部推荐：详情、第一集和已经读到内存的首段视频。
     * 换到这一部时详情与播放接口都不用再等，播放器创建后直接出画面。
     */
    private inner class FeedReady(val detail: Detail,val index: Int,val quality: Int,val remote: RemoteVideo,val http: okhttp3.OkHttpClient): java.io.Closeable {
        override fun close() { runCatching { remote.close() }; closeTransport(http) }
    }
    private var initialCatalogPending=true
    private var restoreStoppedPage=false
    private var pendingDetail: Series?=null
    private var detailAutoplay=true
    private var libraryLoadError: Throwable?=null
    private var playerListener: Player.Listener?=null
    private lateinit var playbackTitle: TextView
    private var directPlayback=false
    /** 推荐连播：推荐页确认即播放，播放中 ↑/↓ 换上一部/下一部。 */
    private var feedMode=false
    private var feedIndex=0
    private var pendingFeedIndex: Int?=null
    private var feedBatchJob: java.util.concurrent.Future<*>?=null
    /** 推荐连播当前这一部的剧集 id：离开推荐再回来接着看这一部。 */
    private var feedSeriesId=""
    /** 正在后台换一批时非空，避免连按「换一批」重复请求。 */
    private var feedRefreshJob: java.util.concurrent.Future<*>?=null
    private lateinit var feedTop: LinearLayout
    private var feedTopReady=false
    private var feedBackAt=0L
    private var playbackHint: TextView?=null
    private var collection: String?=null
    private var collectionReturn: CatalogState?=null
    private var collectionFocus=""
    private var collectionBack: View?=null
    private val pageSize=20
    private var catalogScroll: ScrollView?=null
    private var catalogBody: LinearLayout?=null
    private var homePosition: HomeScreen.Position?=null
    private var catalogPageKey=""
    private val scrollPositions=mutableMapOf<String,Int>()
    private val catalogCards=linkedMapOf<String,View>()
    private val cardImages=linkedMapOf<String,Pair<ImageView,String>>()
    private var prefetchAttemptAt=0L
    private var prefetchAttempts=0
    private var homeScreen: HomeScreen?=null
    private var selectionPreview: SeriesPreview?=null
    private var homeFromCache=false
    private var homeUpdateCount=0
    private var launchStarted=android.os.SystemClock.elapsedRealtime()
    private val activityStarted=launchStarted
    private var homeContentLogged=false
    private lateinit var library: Library
    private lateinit var tvTools: TvTools
    private lateinit var updater: AppUpdater
    private lateinit var mediaSession: TvMediaSession
    private val sleepTimer=SleepTimer { android.os.SystemClock.elapsedRealtime() }
    private var sleepStopped=false
    private var playerView: PlayerView?=null
    /** 竖屏视频两侧的封面底，模糊后铺满，替代大片黑边。 */
    private var backdrop: ImageView?=null
    private var backdropCover=""
    /** 当前视频的宽高比（已考虑旋转），0 表示还没拿到画面尺寸。 */
    private var videoAspect=0f
    /** 是否为竖屏画面（决定推荐信息区宽度与封面底）。 */
    private var feedPortrait=false
    /** 弹幕层、开关，以及本次播放已经取到的时间段。 */
    private var danmakuView: DanmakuView?=null
    private var danmakuButton: TextView?=null
    private val danmakuPrefs by lazy { getSharedPreferences("native-player-v1",Context.MODE_PRIVATE) }
    private var danmakuEnabled=true
    private var danmakuSeriesId=""
    private var danmakuVideoId=""
    private var danmakuCacheVideo=""
    private var danmakuLoadedUntil=0L
    private var danmakuLoading=false
    private var danmakuFailedAt=0L
    private val danmakuItems=mutableListOf<DanmakuComment>()
    /** 本集已经取过的 30 秒段，拖动回看时直接复用，不再重复请求。 */
    private val danmakuCache=DanmakuCache()
    /** 弹幕显示偏好：字号档位 / 不透明度 / 速度档位（下标，见 danmakuSizeValues 等）。 */
    private var danmakuSize=1
    private var danmakuAlpha=100
    private var danmakuSpeed=1
    private var danmakuLines=1
    /** 自动降清晰度只对当前这一集生效；key 为「剧集ID:集下标」。 */
    private var qualityCap: Int?=null
    private var qualityCapKey=""
    private var autoFallbackTried=false
    private lateinit var favoriteMonitor: FavoriteMonitor
    private val favoriteBadges=mutableMapOf<String,TextView>()
    private var favoriteStatus: TextView?=null
    private var favoriteCheck: TextView?=null
    private var homeUpdates: TextView?=null
    /** 账号页的扫码登录状态；二维码 token 只留在 core，不进入界面状态。 */
    private var authBusy=false
    private var authRevision=0
    private var qrRevision=0
    private var qrCreating=false
    private var qrPollInFlight=false
    private var qrPollRevision=-1
    private var qrPollRunnable: Runnable?=null
    private var qrImageView: ImageView?=null
    private var qrStatusView: TextView?=null
    private var qrErrorView: TextView?=null
    private var qrRefreshButton: TextView?=null
    private val resumeCards=mutableListOf<View>()
    private var episodePanel: EpisodePanel?=null
    private val recovery=RecoveryBudget()
    private var retryPending=false
    private var autoRecovery=false
    private var startPosition=0L
    private var retryPosition=0L
    private var requestedAutoplay=true
    private var retryAutoplay=true
    private var foreground=false
    private lateinit var connectivity: ConnectivityManager
    private var networkRegistered=false
    private val networkCallback=object: ConnectivityManager.NetworkCallback() {
        private fun changed() { main.post { if(connectivity.isActiveNetworkMetered || !networkAvailable()) cancelPrefetch(); if(foreground && screen==Screen.PLAYER && playError && autoRecovery) scheduleRecovery() } }
        override fun onAvailable(network: Network) { changed() }
        override fun onLost(network: Network) { changed() }
        override fun onCapabilitiesChanged(network: Network,capabilities: NetworkCapabilities) { changed() }
        override fun onBlockedStatusChanged(network: Network,blocked: Boolean) { changed() }
    }
    private val retryRunnable=Runnable {
        retryPending=false
        if(foreground && screen==Screen.PLAYER && playError && autoRecovery) {
            if(!networkAvailable()) scheduleRecovery()
            else if(recovery.consume()) {
                android.util.Log.i("HongguoTV","Automatic playback recovery ${recovery.attempts}/3 at ${retryPosition}ms")
                playEpisode(episodeIndex,retryPosition,retryAutoplay,recovering=true)
            }
        }
    }
    private lateinit var root: FrameLayout
    private var generation=0
    private var screen=Screen.CATALOG
    private var tab=TAB_RECOMMEND
    private var page=1
    private var query=""
    private var catalog=emptyList<Series>()
    private var hasMore=false
    private var catalogFocus=""
    private data class CatalogState(val page: Int,val items: List<Series>,val hasMore: Boolean,val ranking: ComicRanking?)
    private var ranking: ComicRanking?=null
    private var rankRefresh: View?=null
    private var rankSubtitle: TextView?=null
    private val tabState=mutableMapOf<Int,CatalogState>()
    private val tabFocus=mutableMapOf<Int,String>()
    private val nav=mutableListOf<View>()
    private class NavGroup(val label: String,val tabs: List<Int>)
    /** 一级导航只留四个入口；同一组里的页面变成页内分栏，遥控器少走几步。 */
    private val navGroups=listOf(
        NavGroup("推荐",listOf(TAB_RECOMMEND)),
        NavGroup("搜索",listOf(TAB_SEARCH)),
        NavGroup("片库",listOf(TAB_HOME,TAB_CATEGORY,TAB_RANK)),
        NavGroup("我的",listOf(TAB_HISTORY,TAB_FAVORITES,TAB_RESERVATIONS,TAB_SETTINGS,TAB_ACCOUNT)))
    private val sectionTitles=mapOf(
        TAB_HOME to "首页",TAB_CATEGORY to "分类",TAB_RANK to "排行榜",
        TAB_ACCOUNT to "账号",TAB_HISTORY to "最近观看",TAB_FAVORITES to "收藏",
        TAB_RESERVATIONS to "预约",TAB_SETTINGS to "设置")
    private val sectionButtons=mutableMapOf<Int,TextView>()
    /** 每个一级入口上次停在哪个分栏，左右移出再移回来不会跳回第一栏。 */
    private val sectionMemory=mutableMapOf<Int,Int>()
    private val typeButtons=mutableMapOf<ContentType,View>()
    private data class CatalogCache(val items: List<Series>,val hasMore: Boolean,val ranking: ComicRanking?,val genres: List<CatalogGenre>,val at: Long)
    private val categoryTypeButtons=mutableMapOf<CatalogType,View>()
    private val categoryGenreButtons=mutableMapOf<String,View>()
    private var categoryGenreRow:LinearLayout?=null
    private var categoryType=CatalogType.REAL
    private var categoryGenre=""
    private var categoryGenres=emptyList<CatalogGenre>()
    private val categoryGenreCache=mutableMapOf<CatalogType,List<CatalogGenre>>()
    private val categoryPages=linkedMapOf<String,CatalogCache>()
    private val categoryPrefs by lazy { getSharedPreferences("native-category-v1",Context.MODE_PRIVATE) }
    private val rankBoardButtons=mutableMapOf<RankBoard,View>()
    private var rankBoard=RankBoard.HONGGUO
    private val rankPages=linkedMapOf<String,CatalogCache>()
    private val rankPrefs by lazy { getSharedPreferences("native-rank-v1",Context.MODE_PRIVATE) }
    private var recommendSession: RecommendSession?=null
    private var recommendSeed=""
    private var recommendRefresh: View?=null
    private var recommendMore: View?=null
    private var recommendLoading=false
    private var recommendSubtitle: TextView?=null
    /** 推荐卡片上的类型角标：某部剧来自哪个类型入口。 */
    private val recommendTypes=linkedMapOf<String,CatalogType>()
    /** 已知类型的剧集（浏览分类、榜单时顺带记录），用于按类型偏好调整混合权重。 */
    private val recommendTypeMap=linkedMapOf<String,CatalogType>()
    private var recommendTypesDirty=false
    private var recommendTypesSavedAt=0L
    /** 兴趣排行：收藏与看过的题材，用来挑兴趣题材的片单。 */
    private var recommendTags=emptyList<String>()
    private val recommendPrefs by lazy { getSharedPreferences("native-recommend-v1",Context.MODE_PRIVATE) }
    private var recommendMode=RecommendMode.MIXED
    private var recommendResetAt=0L
    private var searchInput: View?=null
    private var searchButton: View?=null
    private val searchActions=mutableListOf<View>()
    private var historyManage: View?=null
    private var reservationManage: View?=null
    private val remoteHistory=linkedMapOf<String,AccountHistoryItem>()
    private val remoteReservations=linkedMapOf<String,AccountReservation>()
    private val reservedIds=mutableSetOf<String>()
    private var reservationBusy=false
    private var previewSeries: Series?=null
    private var pendingRemoteEpisode=""
    private var pendingRemotePosition=0L
    private var pendingRemoteIndex=-1
    private var detail: Detail?=null
    private var episodeIndex=0
    private var group=0
    private var synopsisExpanded=false
    /** 从详情页进入的作者/主创页，以及返回时应恢复的详情页。 */
    private var character: CreatorPage?=null
    private var characterPerson: Person?=null
    private var characterReturnDetail: Detail?=null
    private var detailFromCharacter=false
    private var characterFocus=""
    private var player: ExoPlayer?=null
    private var video: RemoteVideo?=null
    private var playbackReady=false
    private var pausedForLifecycle=false
    private lateinit var hud: LinearLayout
    private lateinit var playbackText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var controls: LinearLayout
    private var panel=false
    private var transportPlay: TextView?=null
    private var sleepButton: TextView?=null
    private var speedDialog: AlertDialog?=null
    private var pendingSeek: Long?=null
    private var playError=false
    private var lastSaved=0L
    private val bufferingWatchdog=com.hongguotv.core.BufferingWatchdog()
    private var quality=""
    private var feedQualityButton: TextView?=null
    private val tick=object: Runnable {
        override fun run() {
            if(!foreground || isDestroyed) return
            if(screen==Screen.PLAYER) {
                if(sleepTimer.poll()) stopForSleep()
                val buffering=player?.playbackState==Player.STATE_BUFFERING && requestedAutoplay && !pausedForLifecycle && episodePanel==null && !playError && !sleepStopped
                if(bufferingWatchdog.poll(android.os.SystemClock.elapsedRealtime(),buffering)) {
                    android.util.Log.w("HongguoTV","Buffering exceeded 30s; refreshing stream at preserved position")
                    video?.close()
                    playerFailure(java.net.SocketTimeoutException("视频缓冲超时"))
                }
                if(hud.visibility==View.VISIBLE) updatePlaybackText()
                maybePrefetch()
                maybePrefetchFeed()
                val now=android.os.SystemClock.elapsedRealtime()
                if(player?.isPlaying==true && now-lastSaved>5000) { saveProgress(); lastSaved=now }
            }
            main.postDelayed(this,1000)
        }
    }
    private val hideHud=Runnable { if(screen==Screen.PLAYER && !panel && player?.isPlaying==true && !playError) hidePlaybackOverlay() }
    private val seekRunnable=Runnable { pendingSeek?.let { player?.seekTo(it) }; pendingSeek=null }
    private fun dp(value: Number)=(value.toFloat()*resources.displayMetrics.density).toInt()
    private fun widthDp()=resources.displayMetrics.widthPixels/resources.displayMetrics.density
    private fun lp(w: Int=LinearLayout.LayoutParams.MATCH_PARENT,h: Int=LinearLayout.LayoutParams.WRAP_CONTENT)=LinearLayout.LayoutParams(w,h)
    private fun rounded(color: Int,border: Int=Color.TRANSPARENT)=TvStyle.shape(this,color,border)
    private fun text(value: String,size: Float=16f,color: Int=white)=TextView(this).apply { text=value; textSize=size; setTextColor(color); includeFontPadding=false }
    private fun column()=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
    private fun row()=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
    private fun focusStyle(view: View,selected: Boolean=false) {
        view.id=View.generateViewId(); view.isFocusable=true; view.isFocusableInTouchMode=true
        fun paint(focused: Boolean) { view.background=rounded(if(focused) TvStyle.raised else if(selected) Color.rgb(61,39,34) else surface,if(focused) accent else Color.TRANSPARENT) }
        paint(false); view.setOnFocusChangeListener { _,focused -> paint(focused) }
    }
    private fun button(label: String,selected: Boolean=false,onClick: ()->Unit): TextView = text(label,15f).apply {
        gravity=Gravity.CENTER; setPadding(dp(14),dp(10),dp(14),dp(10)); minHeight=dp(42); focusStyle(this,selected); setOnClickListener { onClick() }
    }
    private fun addButton(parent: LinearLayout,label: String,selected: Boolean=false,onClick: ()->Unit): TextView {
        val view=button(label,selected,onClick); parent.addView(view,lp().apply { width=LinearLayout.LayoutParams.WRAP_CONTENT; rightMargin=dp(8) }); return view
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        library=Library(this); artwork=ArtworkLoader(this); tvTools=TvTools(this); root=FrameLayout(this).apply { setBackgroundColor(bg) }; setContentView(root)
        updater=AppUpdater(this) { foreground && library.isLoaded && screen==Screen.CATALOG && !tvTools.showing }
        connectivity=getSystemService(ConnectivityManager::class.java)
        runCatching { connectivity.registerDefaultNetworkCallback(networkCallback); networkRegistered=true }
        favoriteMonitor=FavoriteMonitor(library,repository) { refreshFavoriteLabels() }
        mediaSession=TvMediaSession(this,{ if(foreground && screen==Screen.PLAYER) requestPlayback(true) },{ if(foreground && screen==Screen.PLAYER) requestPlayback(false) },{ position -> if(foreground && screen==Screen.PLAYER) seekTo(position) },{ direction -> skipEpisode(direction) },{ if(foreground && screen==Screen.PLAYER) returnToDetail() })
        message(base(),"正在读取本机记录…")
        restoreCategory(); restoreRankBoard(); restoreRecommend()
        danmakuEnabled=danmakuPrefs.getBoolean("danmaku",true)
        danmakuSize=danmakuPrefs.getInt("danmakuSize",1).coerceIn(0,2)
        danmakuAlpha=danmakuPrefs.getInt("danmakuAlpha",100).let { if(it in listOf(60,80,100)) it else 100 }
        danmakuSpeed=danmakuPrefs.getInt("danmakuSpeed",1).coerceIn(0,2)
        danmakuLines=danmakuPrefs.getInt("danmakuLines",1).coerceIn(0,2)
        DeviceIdentityStore.restore(this)
        DeviceIdentityStore.ensure(this,repository,io)
        AuthStore.restore(this,repository)
        loadLibrary()
    }
    private fun showLibraryError(problem: Throwable) {
        val body=base(); error(body,problem) { message(base(),"正在读取本机记录…"); loadLibrary() }
    }
    private fun loadLibrary() {
        libraryLoadError=null
        library.loadAsync(onFailure={ problem ->
            if(!isDestroyed) { libraryLoadError=problem; if(foreground) showLibraryError(problem) }
        }) {
            if(!isDestroyed) { accountHistorySync.resume(library.history()); if(foreground) { showCatalog(load=true); favoriteMonitor.check() } }
        }
    }
    private fun base(title: String?=null): LinearLayout {
        root.removeAllViews(); root.setBackgroundColor(bg)
        val container=column(); val horizontal=dp(widthDp()*.05f); val vertical=(resources.displayMetrics.heightPixels*.05f).toInt()
        container.setPadding(horizontal,vertical,horizontal,vertical); root.addView(container,FrameLayout.LayoutParams(-1,-1))
        if(title!=null) { val heading=row(); addButton(heading,"‹ 返回") { goBack() }; heading.addView(text(title,22f).apply { setTypeface(null,Typeface.BOLD) },lp(0,dp(44)).apply { weight=1f }); container.addView(heading) }
        return container
    }
    private fun loadCover(view: ImageView,url: String) {
        var ancestor: View?=view
        var focused=false
        while(ancestor!=null && ancestor!==root) {
            if(ancestor.isFocused) { focused=true; break }
            ancestor=ancestor.parent as? View
        }
        artwork.load(view,url,if(focused) ArtworkLoader.PRIORITY_FOCUSED else ArtworkLoader.PRIORITY_VISIBLE)
    }
    private fun cancelWork() = pageWork.cancel()
    private fun invalidatePage() {
        generation++; cancelWork(); stopQrLogin(); artwork.cancelPage()
    }
    private fun stopQrPoll() {
        qrPollRunnable?.let { main.removeCallbacks(it) }
        qrPollRunnable=null
    }
    private fun stopQrLogin() {
        qrRevision++
        stopQrPoll()
        qrCreating=false
        qrPollInFlight=false
        qrPollRevision=-1
        qrImageView=null; qrStatusView=null; qrErrorView=null; qrRefreshButton=null
        repository.cancelLoginQr()
    }
    private fun releaseCatalogViews() {
        homeScreen?.let { homePosition=it.capturePosition() }
        catalogScroll?.let { scrollPositions[catalogPageKey]=it.scrollY }
        catalogBody=null
        homeScreen=null; selectionPreview=null; catalogScroll=null
        nav.clear(); typeButtons.clear(); searchInput=null; searchButton=null; searchActions.clear()
        sectionButtons.clear()
        categoryTypeButtons.clear(); categoryGenreButtons.clear(); categoryGenreRow=null; rankBoardButtons.clear()
        recommendRefresh=null; recommendMore=null; recommendSubtitle=null
        historyManage=null; reservationManage=null; rankRefresh=null; rankSubtitle=null; collectionBack=null; favoriteBadges.clear()
        favoriteStatus=null; favoriteCheck=null; homeUpdates=null; resumeCards.clear()
        catalogCards.clear(); cardImages.clear()
    }
    private fun <T> work(action: ()->T,done: (T)->Unit,failed: (Throwable)->Unit) {
        pageWork.submit(action,done,failed) { result -> if(result is RemoteVideo) result.close() }
    }
    private fun message(parent: LinearLayout,label: String) { parent.addView(text(label,17f,muted).apply { setPadding(0,dp(28),0,dp(18)) }) }
    private fun error(parent: LinearLayout,problem: Throwable,retry: ()->Unit) {
        message(parent,"暂时无法加载，请检查网络后重试。")
        val detail=if(problem is java.net.UnknownHostException) "无法连接内容网站" else if(problem is java.net.SocketTimeoutException) "连接超时" else problem.message.orEmpty().replace(Regex("https?://\\S+"),"[地址]").take(160)
        parent.addView(text(detail,13f,muted)); addButton(parent,"重试",onClick=retry).requestFocus()
    }
    private fun switchTab(next: Int,section: Boolean=false,keepNav: Boolean=false) {
        if(next==tab && screen==Screen.CATALOG && collection==null) {
            if(section) sectionButtons[tab]?.requestFocus() else if(keepNav) nav.getOrNull(tab)?.requestFocus()
            return
        }
        leaveCollection()
        // 从推荐连播切到别的页：先把这一部的位置存下来并放掉播放器。
        if(next!=TAB_RECOMMEND && screen==Screen.PLAYER && feedMode) { saveProgress(); library.flush(); releasePlayer() }
        tabState[tab]=CatalogState(page,catalog,hasMore,ranking); tabFocus[tab]=catalogFocus
        tab=next; val saved=tabState[next]; page=saved?.page ?: 1; catalog=saved?.items ?: emptyList(); hasMore=saved?.hasMore ?: false; ranking=saved?.ranking; catalogFocus=tabFocus[next].orEmpty()
        val remote=next==TAB_HOME || next==TAB_RANK || next==TAB_CATEGORY || next==TAB_RECOMMEND ||
            (next==TAB_SEARCH && query.isNotBlank()) ||
            (next in listOf(TAB_HISTORY,TAB_FAVORITES,TAB_RESERVATIONS) && repository.currentSession()!=null)
        showCatalog(load=remote && catalog.isEmpty(),focusNav=keepNav,focusSection=section)
    }
    private fun switchContentType(type: ContentType) {
        if(library.contentType==type) return
        leaveCollection()
        (searchInput as? EditText)?.let { query=it.text.toString().trim() }
        library.contentType=type
        for(index in listOf(TAB_HOME,TAB_SEARCH)) { tabState.remove(index); tabFocus.remove(index) }
        releaseCatalogViews()
        page=1; catalog=emptyList(); hasMore=false; ranking=null; catalogFocus=""; homePosition=null
        showCatalog(load=(tab==TAB_HOME || query.isNotBlank()),focusType=true)
    }
    private fun categoryKey(page: Int=this.page)="${categoryType.storedValue}|$categoryGenre|$page"
    private fun categoryFocusTarget(): View = categoryGenreButtons[categoryGenre] ?: categoryTypeButtons[categoryType] ?: nav[TAB_CATEGORY]
    /** 记住选择，下次打开仍停在同一个类型与题材。 */
    private fun rememberCategory() = categoryPrefs.edit().putString("type",categoryType.storedValue).putString("genre",categoryGenre).apply()
    private fun restoreCategory() {
        categoryType=CatalogType.fromStored(categoryPrefs.getString("type",null))
        categoryGenre=categoryPrefs.getString("genre","").orEmpty()
        categoryGenres=categoryGenreCache[categoryType].orEmpty()
    }
    private fun rankKey(page: Int=this.page)="${rankBoard.slug}|$page"
    /** 记住榜单，下次打开排行榜仍停在同一个榜单。 */
    private fun rememberRankBoard() = rankPrefs.edit().putString("board",rankBoard.slug).apply()
    private fun restoreRankBoard() { rankBoard=RankBoard.fromStored(rankPrefs.getString("board",null)) }
    /** 记住推荐模式与兴趣重置时间，下次启动继续使用。 */
    private fun rememberRecommend() = recommendPrefs.edit().putString("mode",recommendMode.storedValue).putLong("resetAt",recommendResetAt).apply()
    private fun restoreRecommend() {
        recommendMode=RecommendMode.fromStored(recommendPrefs.getString("mode",null))
        recommendResetAt=recommendPrefs.getLong("resetAt",0)
        runCatching { org.json.JSONObject(recommendPrefs.getString("types","{}").orEmpty()) }.onSuccess { saved ->
            saved.keys().forEach { id -> CatalogType.fromStored(saved.optString(id)).let { type -> if(saved.optString(id).isNotEmpty()) recommendTypeMap[id]=type } }
        }
    }
    /** 顺带记住「这部属于哪个类型」，用来判断用户的类型偏好。 */
    private fun rememberTypes(items: List<Series>,type: CatalogType) {
        var added=0
        items.forEach { item -> if(recommendTypeMap.put(item.id,type)==null) added++ }
        if(added>0) {
            while(recommendTypeMap.size>800) recommendTypeMap.remove(recommendTypeMap.keys.first())
            recommendTypesDirty=true
        }
        if(recommendTypesDirty && System.currentTimeMillis()-recommendTypesSavedAt>60_000) flushRecommendTypes()
    }
    private fun flushRecommendTypes() {
        if(!recommendTypesDirty) return
        recommendTypesDirty=false; recommendTypesSavedAt=System.currentTimeMillis()
        val saved=org.json.JSONObject(); recommendTypeMap.forEach { (id,type) -> saved.put(id,type.storedValue) }
        recommendPrefs.edit().putString("types",saved.toString()).apply()
    }
    /** 新会话：重新随机种子、重新读一遍兴趣画像，同分内容因此换序。 */
    private fun newRecommendSession() {
        recommendSeed=java.util.UUID.randomUUID().toString().replace("-","").take(10)
        recommendTypes.clear()
        if(!library.isLoaded) { recommendSession=RecommendSession(recommendSeed,recommendMode); recommendTags=emptyList(); return }
        val now=System.currentTimeMillis()
        val profile=RecommendProfile.of(library.favorites(),library.history(),library.watchedIds(),now,recommendTypeMap,recommendResetAt)
        recommendSession=RecommendSession(recommendSeed,recommendMode,profile,now)
        recommendTags=profile.interests.entries.filter { it.value>0 }.sortedByDescending { it.value }.map { it.key }
    }
    /** 会话种子决定从第几页开始读各类型片单：会话之间看到的内容不会完全一样。 */
    private fun recommendStartPage()=1+Math.floorMod(recommendSeed.hashCode(),3)
    /**
     * 一次抓取一批候选：每个类型一页分类片单，第一批再补上兴趣题材的片单。
     * 单个类型失败时由其它类型补足，全部失败才报错。在后台线程执行，不碰本机记录。
     */
    private fun recommendPool(batch: Int,mode: RecommendMode,tags: List<String>,startPage: Int,hidden: Set<String>): List<RecommendCandidate> {
        val types=mode.type?.let { listOf(it) } ?: listOf(CatalogType.REAL,CatalogType.COMIC_DRAMA,CatalogType.AI_DRAMA)
        val candidates=mutableListOf<RecommendCandidate>()
        var failed=0
        types.forEach { type ->
            val page=(startPage+batch-1).coerceIn(1,RECOMMEND_MAX_PAGE)
            var fetched: CatalogPage?=null
            try { fetched=repository.category(type,page=page) } catch(problem: Exception) {
                if(page>1) fetched=runCatching { repository.category(type) }.getOrNull()
                if(fetched==null) failed++
            }
            val loaded=fetched
            if(loaded==null) return@forEach
            candidates+=loaded.items.filterNot { it.id in hidden }.map { RecommendCandidate(it,type) }
            if(batch==1 && tags.isNotEmpty()) {
                val genre=loaded.genres.firstOrNull { it.label in tags }
                if(genre!=null) runCatching { repository.category(type,genre.slug) }
                    .onSuccess { extra -> candidates+=extra.items.filterNot { it.id in hidden }.map { RecommendCandidate(it,type,discovery=true) } }
            }
        }
        if(candidates.isEmpty()) throw java.io.IOException("推荐来源暂时无法连接")
        return candidates
    }
    /** 把一批推荐并进片单：界面加载与推荐连播后台续批共用。 */
    private fun mergeRecommendBatch(batch: Int,candidates: List<RecommendCandidate>): Boolean {
        val session=recommendSession ?: return false
        candidates.groupBy { it.type }.forEach { (type,rows) -> rememberTypes(rows.map { it.series },type) }
        candidates.forEach { candidate -> recommendTypes[candidate.series.id]=candidate.type }
        val items=session.append(candidates).map { it.series }
        catalog=if(batch<=1) items else catalog+items
        hasMore=items.size>=RecommendSession.DEFAULT_LIMIT && batch<RECOMMEND_MAX_BATCH
        page=batch
        recommendLoading=false
        return items.isNotEmpty()
    }
    /** 把一批推荐续到片单后面，并重画卡片（含类型角标）。 */
    private fun applyRecommendBatch(batch: Int,candidates: List<RecommendCandidate>,body: LinearLayout,focusNav: Boolean,focusType: Boolean) {
        if(recommendSession==null) { catalog=emptyList(); hasMore=false; body.removeAllViews(); catalogGrid(body,focusNav,focusType); return }
        mergeRecommendBatch(batch,candidates)
        recommendSubtitle?.text=recommendSubtitleText()
        val keepNav=nav.any { it.hasFocus() }
        body.removeAllViews(); catalogGrid(body,keepNav || focusNav,focusType)
    }
    /** 推荐加载失败：已经看到内容时保留片单，只提示这一次没续上。 */
    private fun recommendFailed(batch: Int,problem: Throwable,body: LinearLayout) {
        recommendLoading=false
        if(catalog.isNotEmpty()) {
            if(batch>1) page=(batch-1).coerceAtLeast(1)
            Toast.makeText(this,"暂时加载不出更多推荐，请稍后再试",Toast.LENGTH_LONG).show()
            showCatalog()
            return
        }
        body.removeAllViews()
        error(body,problem) { showCatalog(true) }
        recommendRefresh?.requestFocus()
    }
    /** 推荐页副标题：当前模式、配比与画像状态。 */
    private fun recommendSubtitleText(): String {
        val weights=recommendSession?.weights
        val parts=mutableListOf(if(recommendMode==RecommendMode.MIXED && weights!=null)
            listOf(CatalogType.REAL to "真人剧",CatalogType.COMIC_DRAMA to "漫剧",CatalogType.AI_DRAMA to "AI剧")
                .joinToString(" · ") { (type,label) -> "$label ${((weights[type] ?: 0.0)*100).roundToInt()}%" }
        else if(recommendMode==RecommendMode.MIXED) "真人剧 50% · 漫剧 30% · AI剧 20%" else "只看${recommendMode.label}")
        parts+=if(recommendTags.isEmpty()) "先按各类型热门推荐" else "已参考你的收藏与观看"
        if(catalog.isNotEmpty()) parts+="已推荐 ${catalog.size} 部"
        parts+="确认直接播放，播放中 ↑/↓ 换一部"
        return parts.joinToString(" · ")
    }
    private fun showRecommendModePicker(anchor: TextView) {
        val modes=RecommendMode.entries
        val labels=modes.map { mode -> if(mode==RecommendMode.MIXED) "混合推荐 · 真人剧 50% / 漫剧 30% / AI剧 20%" else "只看${mode.label}" }
        tvTools.choose("推荐模式",labels,anchor,{ index ->
            if(recommendMode!=modes[index]) {
                recommendMode=modes[index]; rememberRecommend()
                tabState.remove(TAB_RECOMMEND); tabFocus.remove(TAB_RECOMMEND)
                Toast.makeText(this,"下次进入推荐按「${modes[index].label}」排列",Toast.LENGTH_SHORT).show()
            }
            anchor.text="推荐模式  ·  ${recommendMode.label}"
        })
    }
    private fun resetRecommendInterest(anchor: TextView) {
        tvTools.confirm("重置兴趣","清空按观看记录累计的兴趣，收藏与观看记录本身仍会保留。",anchor) {
            recommendResetAt=System.currentTimeMillis(); rememberRecommend()
            tabState.remove(TAB_RECOMMEND); tabFocus.remove(TAB_RECOMMEND)
            anchor.text="重置兴趣  ·  已重置"
            Toast.makeText(this,"已重置推荐兴趣，下次进入推荐重新累计",Toast.LENGTH_LONG).show()
        }
    }
    /** 切换榜单：整页重来，命中缓存时立即显示。 */
    private fun switchRankBoard(board: RankBoard) {
        if(rankBoard==board) return
        leaveCollection()
        rankBoard=board
        rememberRankBoard(); tabState.remove(TAB_RANK); tabFocus.remove(TAB_RANK)
        page=1; catalog=emptyList(); hasMore=false; ranking=null; catalogFocus=""
        showCatalog(load=true)
    }
    /** 进入新的类型或题材：整页重来，但同一分类的片单在短时间内直接复用。 */
    private fun switchCategoryType(type: CatalogType) {
        if(categoryType==type) return
        leaveCollection()
        categoryType=type; categoryGenre=""
        categoryGenres=categoryGenreCache[type].orEmpty()
        rememberCategory(); tabState.remove(TAB_CATEGORY); tabFocus.remove(TAB_CATEGORY)
        page=1; catalog=emptyList(); hasMore=false; catalogFocus=""
        showCatalog(load=true)
    }
    private fun switchCategoryGenre(slug: String) {
        if(categoryGenre==slug) return
        leaveCollection()
        categoryGenre=slug
        rememberCategory(); tabState.remove(TAB_CATEGORY); tabFocus.remove(TAB_CATEGORY)
        page=1; catalog=emptyList(); hasMore=false; catalogFocus=""
        showCatalog(load=true)
    }
    /** 题材筛选行：第一个固定是「全部」，网站的题材会随时补齐。 */
    private fun renderCategoryGenres() {
        val line=categoryGenreRow ?: return
        val focusedSlug=categoryGenreButtons.entries.firstOrNull { it.value===currentFocus }?.key
        if(categoryGenre.isNotEmpty() && categoryGenres.none { it.slug==categoryGenre }) categoryGenre=""
        line.removeAllViews(); categoryGenreButtons.clear()
        (listOf(CatalogGenre("","全部"))+categoryGenres).forEach { genre ->
            val view=button(genre.label,genre.slug==categoryGenre) { switchCategoryGenre(genre.slug) }
            view.isSelected=genre.slug==categoryGenre; view.tag=genre.slug
            view.textSize=13f; view.minHeight=dp(34); view.setPadding(dp(12),dp(7),dp(12),dp(7))
            view.nextFocusUpId=categoryTypeButtons[categoryType]?.id ?: nav[TAB_CATEGORY].id
            line.addView(view,lp().apply { width=LinearLayout.LayoutParams.WRAP_CONTENT; rightMargin=dp(8) })
            categoryGenreButtons[genre.slug]=view
        }
        if(categoryGenres.isEmpty()) line.addView(text("正在读取题材…",12f,muted).apply { setPadding(dp(4),dp(8),0,0) })
        if(focusedSlug!=null) categoryGenreButtons[focusedSlug]?.requestFocus()
    }
    /** 片单刷新后把焦点放回原来那张卡片或那个题材按钮。 */
    private fun restoreCategoryFocus(tag: String) {
        (catalogCards[tag] ?: categoryGenreButtons[tag] ?: categoryTypeButtons.values.firstOrNull { it.tag==tag })?.requestFocus()
    }
    /** 榜单刷新后把焦点放回原来的榜单按钮。 */
    private fun restoreRankFocus(tag: String) {
        (catalogCards[tag] ?: rankBoardButtons.values.firstOrNull { it.tag==tag } ?: rankRefresh)?.requestFocus()
    }
    private fun leaveCollection() {
        if(collection==null) return
        collection=null
        collectionReturn?.let { page=it.page; catalog=it.items; hasMore=it.hasMore; ranking=it.ranking }
        catalogFocus=collectionFocus; collectionReturn=null
    }
    private fun showCollection(kind: String) {
        collectionReturn=CatalogState(page,catalog,hasMore,ranking); collectionFocus=catalogFocus
        collection=kind; page=1; catalogFocus=""; showCatalog()
    }
    private fun accountTab()=tab==TAB_HISTORY || tab==TAB_FAVORITES || tab==TAB_RESERVATIONS
    /** 已登录时历史/收藏/预约改读红果账号；未登录仍保留本机历史与收藏。 */
    private fun accountRemote()=accountTab() && repository.currentSession()!=null
    private fun localCatalog()=((tab==TAB_FAVORITES || tab==TAB_HISTORY) && !accountRemote()) || collection!=null
    /** 需要联网翻页的片单页（推荐、首页、搜索、排行榜、分类）。 */
    private fun remotePaged()=tab==TAB_HOME || tab==TAB_SEARCH || tab==TAB_RANK || tab==TAB_CATEGORY || tab==TAB_RECOMMEND
    private fun localItems(): List<Series> = when(collection) {
        "hot" -> collectionReturn?.items.orEmpty().filterNot { it.id in library.hidden() }
        "later" -> library.later()
        "updates" -> library.favorites().filter { (library.favoriteUpdate(it.id)?.added ?: 0)>0 }
        "resume" -> library.history().filterNot { library.watched(it.series.id) }.map { it.series }
        else -> if(tab==TAB_FAVORITES) library.favorites() else library.history().map { it.series }
    }
    /** 顶部导航：四个分组入口（推荐/搜索/片库/我的），片单页与推荐连播页共用同一排。 */
    private fun buildNav(container: LinearLayout,divider: Boolean=true): LinearLayout {
        val top=row().apply { setPadding(0,0,0,dp(8)) }
        val brand=row()
        brand.addView(ImageView(this).apply { setImageResource(com.hongguotv.nativeapp.R.drawable.app_icon) },lp(dp(28),dp(28)).apply { rightMargin=dp(9) })
        brand.addView(text("红果",22f).apply { typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL) })
        top.addView(brand,lp(dp(120),dp(44)))
        sectionButtons.clear()
        navGroups.indexOfFirst { tab in it.tabs }.takeIf { it>=0 }?.let { sectionMemory[it]=tab }
        val buttons=navGroups.mapIndexed { index,group ->
            val active=tab in group.tabs
            // 再次进入分组时停在原来的分栏，第一次进入用该组的第一栏。
            val target=if(active) tab else sectionMemory[index] ?: group.tabs.first()
            val item=addButton(top,group.label,active) { switchTab(target) }
            fun paint(focused: Boolean) {
                item.setTextColor(if(focused) bg else if(active) accent else muted)
                item.background=rounded(if(focused) white else if(active) Color.rgb(48,32,29) else Color.TRANSPARENT)
                item.setTypeface(null,if(focused || active) Typeface.BOLD else Typeface.NORMAL)
            }
            paint(false)
            item.setOnFocusChangeListener { _,focused ->
                paint(focused)
                // 焦点移到别的入口就切过去（推荐是播放页，仍然只按确认进）；按下键自然落到新页的分栏。
                // 推荐连播的浮层导航也在 player 页，必须允许从浮层直接切走，否则遥控器左右只能移动焦点。
                val canSwitch=screen==Screen.CATALOG || (screen==Screen.PLAYER && feedMode)
                if(focused && canSwitch && target!=tab && group.tabs.first()!=TAB_RECOMMEND) {
                    main.post { if((screen==Screen.CATALOG || (screen==Screen.PLAYER && feedMode)) && target!=tab && item.hasFocus()) switchTab(target,keepNav=true) }
                }
            }
            item
        }
        // nav[tab] 仍按页签下标取「所属分组」的按钮，页面里原有的焦点接线不用改。
        nav.clear(); repeat(TAB_RESERVATIONS+1) { nav+=buttons.first() }
        navGroups.forEachIndexed { index,group -> group.tabs.forEach { nav[it]=buttons[index] } }
        container.addView(top)
        if(divider) container.addView(View(this).apply { setBackgroundColor(TvStyle.outline) },lp(-1,dp(1)).apply { bottomMargin=dp(6) })
        return top
    }
    /** 分组内的分栏（首页/分类/排行榜、最近观看/收藏/预约/设置/账号）：确认切换，顶部始终只有四个入口。 */
    private fun buildSections(container: LinearLayout) {
        val group=navGroups.firstOrNull { tab in it.tabs } ?: return
        if(group.tabs.size<2) return
        val bar=row().apply { setPadding(0,dp(2),0,dp(6)) }
        bar.addView(text("分栏",12f,muted),lp(dp(42),-2))
        group.tabs.forEach { target ->
            val chip=addButton(bar,sectionTitles[target].orEmpty(),target==tab) { switchTab(target,section=true) }
            chip.textSize=13f; chip.minHeight=dp(34); chip.setPadding(dp(12),dp(7),dp(12),dp(7))
            chip.nextFocusUpId=nav.getOrNull(target)?.id ?: View.NO_ID
            // 分栏按「页签」走：焦点移过去就切换，不用再按一次确认；左右比较分栏最顺手。
            chip.setOnFocusChangeListener { _,focused ->
                chip.background=rounded(if(focused) TvStyle.raised else if(target==tab) Color.rgb(61,39,34) else surface,if(focused) accent else Color.TRANSPARENT)
                if(focused && screen==Screen.CATALOG && target!=tab) main.post { if(screen==Screen.CATALOG && target!=tab && chip.hasFocus()) switchTab(target,section=true) }
            }
            sectionButtons[target]=chip
        }
        container.addView(bar)
    }
    /** 导航的下键先落到分栏，分栏的下键再进片单；分栏的上键回导航。 */
    private fun wireSections() {
        val chip=sectionButtons[tab] ?: return
        val down=nav.getOrNull(tab)?.nextFocusDownId ?: View.NO_ID
        if(down!=View.NO_ID && down!=chip.id) chip.nextFocusDownId=down
        nav.forEach { it.nextFocusDownId=chip.id }
        // 片单上方的第一条（类型/题材/榜单/管理按钮）往上先回分栏，再往上才到导航。
        (typeButtons.values+categoryTypeButtons.values+rankBoardButtons.values+listOfNotNull(favoriteCheck,historyManage,reservationManage))
            .forEach { it.nextFocusUpId=chip.id }
    }
    private fun showCatalog(load: Boolean=false,focusNav: Boolean=false,focusType: Boolean=false,focusSection: Boolean=false) {
        initialCatalogPending=false; restoreStoppedPage=false; pendingDetail=null; previewSeries=null
        character=null; characterPerson=null; characterReturnDetail=null; detailFromCharacter=false
        val keepSection=sectionButtons.values.any { it.hasFocus() } || (focusSection && !focusNav)
        // 推荐页现在就是连播页：不再显示卡片，进入推荐立刻自动播放。
        if(tab==TAB_RECOMMEND && collection==null) { enterFeed(); return }
        feedMode=false; pendingFeedIndex=null
        // Never cache a new page number with the previous page's results while a request is pending.
        if(load) {
            val cached=if(tab==TAB_HOME && page==1) library.cachedHome(library.contentType) else null
            catalog=cached?.items ?: emptyList(); hasMore=cached?.hasMore ?: false; ranking=null; homeFromCache=cached!=null
        }
        invalidatePage(); screen=Screen.CATALOG; releaseCatalogViews(); val container=base()
        if(foreground && library.isLoaded && !favoriteMonitor.running) main.post { if(foreground && screen==Screen.CATALOG) favoriteMonitor.check() }
        val top=buildNav(container)
        buildSections(container)
        if(tab==TAB_ACCOUNT) {
            val scroll=ScrollView(this); val body=column(); scroll.addView(body)
            container.addView(scroll,lp(-1,0).apply { weight=1f })
            val first=account(body)
            wireSections()
            if(keepSection) sectionButtons[tab]?.requestFocus()
            else if(focusNav) nav[tab].requestFocus()
            else first.requestFocus()
            return
        }
        if(tab==TAB_SETTINGS) {
            val scroll=ScrollView(this); val body=column(); scroll.addView(body); container.addView(scroll,lp(-1,0).apply { weight=1f })
            settings(body); wireSections()
            if(keepSection) sectionButtons[tab]?.requestFocus() else if(focusNav) nav[tab].requestFocus()
            return
        }
        if((tab==TAB_HOME || tab==TAB_SEARCH)) {
            val types=row().apply { setPadding(0,dp(8),0,dp(6)) }
            types.addView(text("类型",12f,muted),lp(dp(42),-2))
            ContentType.entries.forEach { type ->
                typeButtons[type]=addButton(types,type.label,library.contentType==type) { switchContentType(type) }
                    .apply { isSelected=library.contentType==type; nextFocusUpId=nav[tab].id }
            }
            if(tab==TAB_HOME) types.addView(text("长按确认 · 管理片单",12f,muted).apply { setPadding(dp(14),0,0,0) })
            container.addView(types)
            nav.forEach { it.nextFocusDownId=typeButtons.getValue(library.contentType).id }
        }
        if(tab==TAB_CATEGORY) {
            val types=row().apply { setPadding(0,dp(8),0,dp(4)) }
            types.addView(text("内容",12f,muted),lp(dp(42),-2))
            CatalogType.entries.forEach { type ->
                val view=addButton(types,type.label,type==categoryType) { switchCategoryType(type) }
                view.isSelected=type==categoryType; view.nextFocusUpId=nav[tab].id
                view.tag="type:${type.storedValue}"
                view.textSize=13f; view.minHeight=dp(34); view.setPadding(dp(12),dp(7),dp(12),dp(7))
                categoryTypeButtons[type]=view
            }
            types.addView(Space(this),lp(0,1).apply { weight=1f })
            addButton(types,"刷新片单") { categoryPages.clear(); page=1; catalogFocus=""; showCatalog(true) }.apply { nextFocusUpId=nav[tab].id }
            container.addView(types)
            val bar=HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false; clipToPadding=false }
            val line=row(); bar.addView(line); categoryGenreRow=line
            renderCategoryGenres()
            container.addView(bar,lp(-1,-2).apply { bottomMargin=dp(4) })
            nav.forEach { it.nextFocusDownId=categoryFocusTarget().id }
        }
        if(tab==TAB_SEARCH) {
            val searchRow=row(); val input=EditText(this).apply { id=View.generateViewId(); hint="输入${library.contentType.label}名称或关键词"; setText(query); textSize=16f; setTextColor(white); setHintTextColor(muted); isSingleLine=true; maxLines=1; filters=arrayOf(android.text.InputFilter.LengthFilter(80)); imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH }
            searchRow.addView(input,lp(0,dp(45)).apply { weight=1f; rightMargin=dp(10) })
            fun search() { val next=input.text.toString().trim(); if(next.isBlank()) { input.requestFocus(); return }; runSearch(next) }
            searchInput=input; searchButton=addButton(searchRow,"搜索") { search() }; input.nextFocusUpId=typeButtons.getValue(library.contentType).id; searchButton?.nextFocusUpId=input.nextFocusUpId
            lateinit var recent: TextView
            recent=addButton(searchRow,"历史") { showSearchHistory(recent) }; recent.nextFocusUpId=input.nextFocusUpId
            lateinit var phone: TextView
            phone=addButton(searchRow,"手机输入") { (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken,0); tvTools.phoneInput(phone) { runSearch(it) } }; phone.nextFocusUpId=input.nextFocusUpId
            searchActions.addAll(listOf(recent,phone))
            typeButtons.values.forEach { it.nextFocusDownId=input.id }
            input.setOnEditorActionListener { _,_,_-> search(); true }; container.addView(searchRow)
        } else if(tab==TAB_RANK) {
            val heading=row().apply { setPadding(0,dp(8),0,dp(6)) }
            heading.addView(text(rankBoard.label,25f).apply { setTypeface(null,Typeface.BOLD) },lp(0,-2).apply { weight=1f })
            rankRefresh=addButton(heading,"刷新榜单") { rankPages.clear(); page=1; catalogFocus=""; showCatalog(true) }.apply { nextFocusUpId=nav[tab].id }
            container.addView(heading)
            val boards=row().apply { setPadding(0,0,0,dp(4)) }
            boards.addView(text("榜单",12f,muted),lp(dp(42),-2))
            RankBoard.entries.forEach { board ->
                val view=addButton(boards,board.label,board==rankBoard) { switchRankBoard(board) }
                view.isSelected=board==rankBoard; view.nextFocusUpId=nav[tab].id
                view.tag="rank:${board.slug}"
                view.textSize=13f; view.minHeight=dp(34); view.setPadding(dp(12),dp(7),dp(12),dp(7))
                rankBoardButtons[board]=view
            }
            boards.addView(Space(this),lp(0,1).apply { weight=1f })
            container.addView(boards)
            rankSubtitle=text(if(load) "正在读取最新榜单…" else ranking?.updatedText?.ifBlank { "来源：${rankBoard.label}" } ?: "来源：${rankBoard.label}",13f,muted)
                .apply { setPadding(0,0,0,dp(8)) }
            container.addView(rankSubtitle)
            nav.forEach { it.nextFocusDownId=(rankBoardButtons[rankBoard] ?: rankRefresh!!).id }
        } else if(accountTab()) {
            val signedIn=repository.currentSession()!=null
            val heading=row().apply { setPadding(0,dp(14),0,dp(6)) }
            val title=when {
                tab==TAB_RESERVATIONS -> "我的预约"
                signedIn && tab==TAB_FAVORITES -> "账号收藏"
                signedIn -> "账号观看记录"
                tab==TAB_FAVORITES -> "我的收藏"
                else -> "接着上次看"
            }
            heading.addView(text(title,25f).apply { setTypeface(null,Typeface.BOLD) },lp(0,-2).apply { weight=1f })
            if(tab==TAB_FAVORITES) {
                if(signedIn) favoriteCheck=addButton(heading,"刷新收藏") { showCatalog(true) }.apply { nextFocusUpId=nav[tab].id }
                else favoriteCheck=addButton(heading,"检查更新") { favoriteMonitor.check(force=true) }.apply { nextFocusUpId=nav[tab].id }
            }
            if(tab==TAB_HISTORY) {
                if(signedIn) historyManage=addButton(heading,"刷新记录") { showCatalog(true) }.apply { nextFocusUpId=nav[tab].id }
                else if(library.history().isNotEmpty()) {
                    historyManage=addButton(heading,"管理记录") {
                        val rows=library.history()
                        tvTools.choose("选择要管理的观看记录",rows.map { it.series.title },historyManage,{ index -> manageHistory(rows[index].series,historyManage) },"清空记录") {
                            tvTools.confirm("清空观看记录","将删除本机所有观看进度，收藏会保留。",historyManage) { library.clearHistory(); catalogFocus=""; showCatalog() }
                        }
                    }.apply { nextFocusUpId=nav[tab].id }
                }
            }
            if(tab==TAB_RESERVATIONS && signedIn) {
                historyManage=addButton(heading,"刷新预约") { showCatalog(true) }.apply { nextFocusUpId=nav[tab].id }
                reservationManage=addButton(heading,"取消预约") { chooseReservationToCancel(reservationManage) }.apply { nextFocusUpId=nav[tab].id }
            }
            container.addView(heading)
            val status=when {
                tab==TAB_RESERVATIONS && !signedIn -> "登录后可查看账号预约和取消预约"
                signedIn -> "来自红果账号"
                tab==TAB_FAVORITES -> favoriteMonitor.status
                else -> "本机记录"
            }
            favoriteStatus=text(status,13f,muted).apply { setPadding(0,0,0,dp(6)) }; container.addView(favoriteStatus)
        }
        wireSections()
        val body=column(); catalogBody=body; container.addView(body,lp(-1,0).apply { weight=1f })
        if(localCatalog()) {
            catalog=localItems(); ranking=null; page=page.coerceIn(1,maxOf(1,(catalog.size+pageSize-1)/pageSize)); hasMore=page*pageSize<catalog.size
        } else if(tab==TAB_RESERVATIONS && repository.currentSession()==null) {
            catalog=emptyList(); hasMore=false; ranking=null
        }
        // 同一分类或榜单的片单在短时间内直接复用，过期或未命中才请求网站。
        var fetching=load
        if(load && tab==TAB_CATEGORY) {
            val cached=categoryPages[categoryKey()]?.takeIf { System.currentTimeMillis()-it.at<=CATEGORY_CACHE_MS }
            if(cached!=null) {
                catalog=cached.items; hasMore=cached.hasMore
                if(cached.genres.isNotEmpty() && cached.genres!=categoryGenres) { categoryGenres=cached.genres; renderCategoryGenres() }
                fetching=false
            } else { catalog=emptyList(); hasMore=false }
        }
        if(load && tab==TAB_RANK) {
            val cached=rankPages[rankKey()]?.takeIf { System.currentTimeMillis()-it.at<=CATEGORY_CACHE_MS }
            if(cached!=null) { catalog=cached.items; hasMore=cached.hasMore; ranking=cached.ranking; fetching=false }
            else { catalog=emptyList(); hasMore=false; ranking=null }
        }
        // 推荐第一页（或「换一批」）开一次新会话；往后是往已经看到的片单后面续。
        if(tab==TAB_RECOMMEND) {
            recommendLoading=load && page>1
            if(load && page<=1) { newRecommendSession(); catalog=emptyList(); hasMore=false }
        }
        if(fetching && accountRemote()) {
            val requestTab=tab
            message(body,"正在读取红果账号记录…")
            if(keepSection) sectionButtons[tab]?.requestFocus() else nav[tab].requestFocus()
            work({
                when(requestTab) {
                    TAB_HISTORY -> repository.accountResumeHistory()
                    TAB_FAVORITES -> repository.accountFavorites()
                    else -> repository.accountReservations()
                }
            }, { result ->
                if(requestTab!=tab || !accountRemote()) return@work
                when(requestTab) {
                    TAB_HISTORY -> {
                        repository.currentSession()?.let { mergeCloudHistory(it,result.history) }
                        remoteHistory.clear()
                        result.history.forEach { remoteHistory[it.series.id]=it }
                    }
                    TAB_RESERVATIONS -> {
                        remoteReservations.clear(); reservedIds.clear()
                        result.reservations.forEach { item ->
                            remoteReservations[item.series.id]=item
                            if(item.subscribed) reservedIds+=item.series.id
                        }
                    }
                    else -> Unit
                }
                catalog=result.items; hasMore=false; ranking=null; page=1
                val keepNav=nav.any { it.hasFocus() }
                body.removeAllViews(); catalogGrid(body,keepNav,false)
            }, { problem ->
                if(requestTab!=tab) return@work
                if(accountSessionExpired(problem)) {
                    clearAccountSession()
                    Toast.makeText(this,"登录状态已失效，请重新登录",Toast.LENGTH_LONG).show()
                    catalog=emptyList(); hasMore=false; showCatalog()
                } else {
                    catalog=emptyList(); hasMore=false
                    body.removeAllViews(); error(body,problem) { showCatalog(true) }
                    nav.getOrNull(tab)?.requestFocus()
                }
            })
        } else if(fetching) {
            if(tab==TAB_HOME) catalogGrid(body,focusNav,focusType,loading=true)
            else if(tab==TAB_RECOMMEND && catalog.isNotEmpty()) catalogGrid(body,false,false,loading=true)
            else {
                message(body,"正在加载…")
                if(keepSection) sectionButtons[tab]?.requestFocus()
                else if(focusType) typeButtons[library.contentType]?.requestFocus()
                else if(tab==TAB_CATEGORY) categoryFocusTarget().requestFocus()
                else if(tab==TAB_RANK) rankBoardButtons[rankBoard]?.requestFocus()
                else nav[tab].requestFocus()
            }
            val requestTab=tab; val requestPage=page; val requestQuery=query; val requestType=library.contentType
            val requestCategory=categoryType; val requestGenre=categoryGenre; val requestBoard=rankBoard
            val requestMode=recommendMode; val requestTags=recommendTags; val requestStartPage=recommendStartPage()
            val requestHidden=if(library.isLoaded) library.hidden() else emptySet()
            if(requestTab==TAB_RECOMMEND) {
                work({ recommendPool(requestPage,requestMode,requestTags,requestStartPage,requestHidden) },
                    { candidates -> applyRecommendBatch(requestPage,candidates,body,focusNav,focusType) },
                    { problem -> recommendFailed(requestPage,problem,body) })
            } else work({
                when(requestTab) {
                    TAB_HOME -> repository.home(requestPage,requestType)
                    TAB_RANK -> repository.ranking(requestBoard,requestPage)
                    TAB_CATEGORY -> repository.category(requestCategory,requestGenre,requestPage)
                    else -> repository.search(requestQuery,requestPage,requestType)
                }
            }, { result ->
                if(requestTab==TAB_HOME && requestPage==1) library.cacheHome(requestType,result.items,result.hasMore)
                if(requestTab==TAB_CATEGORY) {
                    val focusedTag=currentFocus?.tag?.toString()
                    val genres=result.genres.ifEmpty { categoryGenres }
                    if(result.genres.isNotEmpty()) categoryGenreCache[requestCategory]=result.genres
                    categoryPages["${requestCategory.storedValue}|$requestGenre|$requestPage"]=CatalogCache(result.items,result.hasMore,null,genres,System.currentTimeMillis())
                    while(categoryPages.size>40) categoryPages.remove(categoryPages.keys.first())
                    rememberTypes(result.items,requestCategory)
                    catalog=result.items; hasMore=result.hasMore; categoryGenres=genres
                    val keepNav=nav.any { it.hasFocus() }
                    renderCategoryGenres()
                    body.removeAllViews(); catalogGrid(body,keepNav,false)
                    if(focusedTag!=null) restoreCategoryFocus(focusedTag)
                } else if(requestTab==TAB_HOME && requestPage==1 && (homeFromCache || tvTools.showing || updater.showing)) {
                    homeScreen?.refresh?.text="热门已更新 · 按确认查看"
                    homeScreen?.refresh?.setOnClickListener { catalog=result.items; hasMore=result.hasMore; ranking=result.ranking; showCatalog() }
                } else {
                    catalog=result.items; hasMore=result.hasMore; ranking=result.ranking
                    if(requestTab==TAB_RANK) {
                        rankPages["${requestBoard.slug}|$requestPage"]=CatalogCache(result.items,result.hasMore,result.ranking,emptyList(),System.currentTimeMillis())
                        while(rankPages.size>24) rankPages.remove(rankPages.keys.first())
                        requestBoard.type?.let { type -> rememberTypes(result.items,type) }
                    }
                    val focusedTag=if(requestTab==TAB_RANK) currentFocus?.tag?.toString() else null
                    val keepNav=nav.any { it.hasFocus() }; val keepType=typeButtons.values.any { it.hasFocus() }
                    rankSubtitle?.text=ranking?.updatedText?.ifBlank { "来源：${requestBoard.label}" } ?: "来源：${requestBoard.label}"
                    body.removeAllViews(); catalogGrid(body,keepNav || focusNav,keepType || focusType)
                    if(focusedTag!=null) restoreRankFocus(focusedTag)
                }
            }, { problem ->
                if(requestTab==TAB_CATEGORY && requestGenre.isNotEmpty() && problem.message?.contains("404")==true) {
                    // 网站的题材可能已下线，退回「全部」再读一次。
                    categoryGenre=""; categoryGenres=categoryGenreCache[requestCategory].orEmpty(); rememberCategory()
                    catalog=emptyList(); hasMore=false; catalogFocus=""
                    showCatalog(true); return@work
                }
                if(requestTab==TAB_HOME && requestPage==1) {
                    homeScreen?.refresh?.text=if(catalog.isNotEmpty()) "当前显示上次内容 · 联网后按确认刷新" else "热门暂时无法加载 · 按确认重试"
                    homeScreen?.refresh?.setOnClickListener { showCatalog(true) }
                    return@work
                }
                catalog=emptyList(); hasMore=false; ranking=null; rankSubtitle?.text="榜单加载失败"
                if(problem is SearchSessionExpiredException) {
                    page=1; catalogFocus=""
                    Toast.makeText(this,"搜索结果已过期，已回到第 1 页刷新",Toast.LENGTH_LONG).show()
                    showCatalog(true)
                } else {
                    body.removeAllViews()
                    error(body,problem) { showCatalog(true) }
                    if(resumeCards.isNotEmpty()) resumeCards.first().requestFocus()
                }
            })
        } else catalogGrid(body,focusNav,focusType)
        // 从分栏切过来时把焦点留在分栏上，方便继续左右比较或按下键进片单。
        if(keepSection) sectionButtons[tab]?.requestFocus()
    }
    private fun catalogGrid(body: LinearLayout,focusNav: Boolean,focusType: Boolean=false,loading: Boolean=false) {
        val keepSection=sectionButtons[tab]?.hasFocus()==true
        if(tab==TAB_HOME && page==1 && collection==null) { renderHome(body,focusNav,focusType,loading,keepSection); return }
        resumeCards.clear(); catalogCards.clear(); cardImages.clear(); collectionBack=null
        if(collection!=null) {
            val heading=row()
            collectionBack=addButton(heading,"‹ 返回首页") { leaveCollection(); showCatalog() }.apply {
                nextFocusUpId=typeButtons[library.contentType]?.id ?: nav[tab].id
                nextFocusDownId=id
            }
            heading.addView(text(when(collection) { "later" -> "稍后看"; "updates" -> "收藏有更新"; "hot" -> "热门发现"; else -> "接着看" },22f))
            body.addView(heading)
            typeButtons.values.forEach { it.nextFocusDownId=collectionBack!!.id }
        }
        catalogPageKey=when {
            tab==TAB_CATEGORY -> "cat:${categoryType.storedValue}:$categoryGenre:$page"
            tab==TAB_RANK -> "rank:${rankBoard.slug}:$page"
            else -> "$tab:${collection.orEmpty()}:$page:$query"
        }
        val preview=SeriesPreview(this); selectionPreview=preview; body.addView(preview)
        val scroll=ScrollView(this).apply { isFillViewport=false; isVerticalScrollBarEnabled=false; clipToPadding=false }; catalogScroll=scroll
        val list=column(); scroll.addView(list); body.addView(scroll,lp(-1,0).apply { weight=1f })
        var restoringScroll=true
        val displayed=when {
            accountRemote() -> catalog
            localCatalog() -> catalog.drop((page-1)*pageSize).take(pageSize)
            tab==TAB_HOME || tab==TAB_RECOMMEND -> catalog.filterNot { it.id in library.hidden() }
            else -> catalog.take(30)
        }
        if(displayed.isEmpty()) {
            message(list,if(loading) "正在加载推荐…" else when(tab) {
                TAB_SEARCH -> if(query.isBlank()) "输入关键词，用遥控器确认搜索" else "没有找到相关${library.contentType.label}，换个关键词试试"
                TAB_FAVORITES -> if(accountRemote()) "红果账号里还没有收藏" else "在剧集详情中选择收藏，喜欢的剧就会出现在这里"
                TAB_HISTORY -> if(accountRemote()) "红果账号里还没有观看记录" else "播放过的剧集会自动保存在这里"
                TAB_RESERVATIONS -> if(repository.currentSession()==null) "登录红果账号后，就能查看预约和取消预约" else "还没有预约，打开待上线剧集的详情即可预约"
                TAB_CATEGORY -> "这个分类暂时没有内容，换个题材或类型看看"
                TAB_RANK -> "这个榜单暂时没有内容，换个榜单或点「刷新榜单」再试"
                TAB_RECOMMEND -> "暂时没有可推荐的内容，点「换一批」再试"
                else -> "本页没有更多内容"
            })
            var emptyAction: View?=null
            if(tab==TAB_RECOMMEND) {
                emptyAction=addButton(list,"换一批") { catalog=emptyList(); hasMore=false; page=1; catalogFocus=""; showCatalog(true) }
            } else if(tab==TAB_RESERVATIONS) {
                emptyAction=if(repository.currentSession()==null)
                    addButton(list,"去登录") { switchTab(TAB_ACCOUNT) }
                else addButton(list,"去首页看看") { switchTab(TAB_HOME) }
            } else {
            if((remotePaged() || localCatalog()) && page>1) addButton(list,"上一页") { page--; showCatalog(!localCatalog()) }
            if((remotePaged() || localCatalog()) && hasMore) addButton(list,"下一页") { page++; showCatalog(!localCatalog()) }
            }
            if(resumeCards.isNotEmpty()) {
                typeButtons.values.forEach { it.nextFocusDownId=resumeCards.first().id }
                resumeCards.forEach { it.nextFocusDownId=it.id }
            }
            if(tab==TAB_RANK) rankRefresh?.let { refresh -> rankBoardButtons.values.forEach { it.nextFocusDownId=refresh.id }; refresh.nextFocusDownId=rankBoardButtons[rankBoard]?.id ?: nav[tab].id }
            if(tab==TAB_RESERVATIONS && emptyAction!=null) {
                nav.forEach { it.nextFocusDownId=emptyAction!!.id }
                emptyAction!!.nextFocusUpId=sectionButtons[tab]?.id ?: nav[tab].id
            }
            if(focusType) typeButtons[library.contentType]?.requestFocus() else if(collectionBack!=null && !focusNav) collectionBack?.requestFocus() else if(tab==TAB_RANK) (rankBoardButtons[rankBoard] ?: rankRefresh)?.requestFocus() else if(tab==TAB_RECOMMEND) (recommendRefresh ?: nav[tab]).requestFocus() else if(tab==TAB_RESERVATIONS && emptyAction!=null) emptyAction!!.requestFocus() else if(focusNav || resumeCards.isEmpty()) nav[tab].requestFocus() else resumeCards.first().requestFocus()
            if(keepSection) sectionButtons[tab]?.requestFocus()
            return
        }
        val cards=mutableListOf<View>(); val count=6; val gap=dp(14); val width=((resources.displayMetrics.widthPixels*.9f-gap*(count-1))/count).toInt()
        displayed.chunked(count).forEach { items ->
            val line=row(); line.gravity=Gravity.TOP
            items.forEachIndexed { columnIndex,item ->
                val card=column(); card.setPadding(dp(4),dp(4),dp(4),dp(9)); focusStyle(card); card.contentDescription=item.title; card.tag=item.id; catalogCards[item.id]=card
                val position=if(tab==TAB_RANK) ranking?.positions?.get(item.id) else null
                val rankBadge=if(tab==TAB_RANK) listOfNotNull(position?.status?.takeIf { it.isNotBlank() },position?.heat?.takeIf { it.isNotBlank() }).joinToString(" · ").ifBlank { "热度暂无" } else ""
                val artwork=FrameLayout(this); card.addView(artwork,lp(-1,((width-dp(8))/TvStyle.POSTER_ASPECT).toInt()))
                val image=ImageView(this).apply { scaleType=ImageView.ScaleType.FIT_CENTER; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }; artwork.addView(image,FrameLayout.LayoutParams(-1,-1)); loadCover(image,item.cover); cardImages[item.id]=image to item.cover
                if(tab==TAB_RANK) {
                    val rankLabel=position?.rank?.let { "第 $it 名" } ?: "名次暂无"
                    artwork.addView(text(rankLabel,15f).apply { setTypeface(null,Typeface.BOLD); setPadding(dp(8),dp(5),dp(8),dp(5)); background=rounded(if((position?.rank ?: Int.MAX_VALUE)<=3) accent else Color.rgb(28,31,39)) },FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.START))
                    card.contentDescription="$rankLabel，${item.title}，$rankBadge"
                }
                val recommendType=if(tab==TAB_RECOMMEND) recommendTypes[item.id] else null
                if(recommendType!=null) {
                    artwork.addView(text(recommendType.label,14f).apply { typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL); setPadding(dp(8),dp(4),dp(8),dp(4)); background=rounded(Color.rgb(28,31,39)) },FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.END))
                    card.contentDescription="${recommendType.label}，${item.title}，${item.badge}"
                }
                card.addView(text(item.title,14f).apply { maxLines=2; minLines=2; ellipsize=TextUtils.TruncateAt.END; setPadding(dp(3),dp(7),dp(3),0) },lp(-1,-2).apply { weight=1f })
                val remote=if(tab==TAB_HISTORY && accountRemote()) remoteHistory[item.id] else null
                val reservation=if(tab==TAB_RESERVATIONS) remoteReservations[item.id] else null
                val progress=if(tab==TAB_HISTORY || collection=="resume") library.progress(item.id) else null
                val badgeText=when {
                    tab==TAB_FAVORITES && accountRemote() -> item.badge.ifBlank { "红果收藏" }
                    tab==TAB_FAVORITES -> library.favoriteLabel(item.id)
                    tab==TAB_HISTORY && accountRemote() -> remote?.let(::remoteHistoryLabel) ?: item.badge
                    tab==TAB_HISTORY -> if(progress!=null) if(library.watched(item.id)) "整剧已看完" else "第 ${progress.episodeIndex+1} 集 · ${formatTime(progress.position)}" else item.badge
                    tab==TAB_RESERVATIONS -> reservation?.let {
                        if(it.online) listOf("已上线",it.series.tags).filter { tag -> tag.isNotBlank() }.joinToString(" · ") else it.series.badge
                    } ?: item.badge
                    tab==TAB_RANK -> rankBadge
                    else -> item.badge
                }
                val badge=text(badgeText,12f,if(tab==TAB_RANK || tab==TAB_FAVORITES || tab==TAB_RESERVATIONS) accent else muted).apply { maxLines=1; minLines=1; minHeight=dp(19); ellipsize=TextUtils.TruncateAt.END; setPadding(dp(3),0,0,0) }
                card.addView(badge,lp(-1,-2)); if(tab==TAB_FAVORITES && !accountRemote()) favoriteBadges[item.id]=badge
                card.setOnClickListener {
                    catalogFocus=item.id
                    when {
                        tab==TAB_RESERVATIONS -> if(reservation?.online==true) openDetail(item,false,true)
                            else openPreview(item,reservation)
                        tab==TAB_HISTORY && accountRemote() -> remote?.let {
                            openDetail(item,true,true,false,it.episodeId,it.position,it.episodeIndex)
                        } ?: openDetail(item,true)
                        else -> openDetail(item,tab==TAB_HISTORY || collection=="resume" || collection=="updates")
                    }
                }
                card.setOnLongClickListener { catalogFocus=item.id; quickActions(item,card); true }
                card.setOnKeyListener { _,key,event -> if(isMenuKey(key)) { if(event.action==KeyEvent.ACTION_UP) { catalogFocus=item.id; quickActions(item,card) }; true } else false }
                card.setOnFocusChangeListener { _,focused -> card.background=TvStyle.card(this,focused); if(focused) { catalogFocus=item.id; loadCover(image,item.cover); preview.show(item,when { tab==TAB_RANK -> listOfNotNull(position?.rank?.let { "第 $it 名" },rankBadge).filter { it.isNotBlank() }.joinToString(" · "); recommendType!=null -> listOf(recommendType.label,selectionStatus(item)).filter { it.isNotBlank() }.joinToString(" · "); else -> selectionStatus(item) }); if(!restoringScroll) scroll.post { scroll.smoothScrollTo(0,when { line.top<scroll.scrollY -> line.top; line.bottom>scroll.scrollY+scroll.height -> (line.bottom-scroll.height).coerceAtLeast(0); else -> scroll.scrollY }) } } }
                // The row measures its tallest card, then stretches siblings to keep badges aligned.
                line.addView(card,lp(width,-1).apply { if(columnIndex<count-1) rightMargin=gap }); cards+=card
            }
            list.addView(line,lp(-1,-2).apply { bottomMargin=dp(10) })
        }
        val paging=row(); paging.gravity=Gravity.CENTER
        var prev: View?=null; var next: View?=null
        if(tab==TAB_RECOMMEND) {
            // 推荐是不断往后续的片单：只有「加载更多」，「换一批」在页面标题上。
            val busy=recommendLoading
            recommendMore=addButton(paging,if(busy) "正在加载…" else "加载更多") { if(hasMore && !busy) { catalogFocus="@more"; page++; showCatalog(true) } }.apply { isEnabled=hasMore && !busy; isFocusable=hasMore && !busy; alpha=if(hasMore && !busy) 1f else .4f }
            paging.addView(text("已推荐 ${displayed.size} 部",14f,muted).apply { gravity=Gravity.CENTER },lp(dp(135),dp(44)))
            list.addView(paging,lp(-1,dp(50)))
        } else if(remotePaged() || localCatalog()) {
            prev=addButton(paging,"上一页") { if(page>1) { page--; catalogFocus=""; showCatalog(!localCatalog()) } }.apply { isEnabled=page>1; isFocusable=page>1; alpha=if(page>1) 1f else .4f }
            paging.addView(text(if(localCatalog()) "第 $page / ${maxOf(1,(catalog.size+pageSize-1)/pageSize)} 页" else if(tab==TAB_RANK && ranking!=null) "第 $page / ${ranking!!.totalPages} 页" else "第 $page 页",14f,muted).apply { gravity=Gravity.CENTER },lp(dp(if(tab==TAB_RANK) 135 else 95),dp(44)))
            next=addButton(paging,"下一页") { if(hasMore) { page++; catalogFocus=""; showCatalog(!localCatalog()) } }.apply { isEnabled=hasMore; isFocusable=hasMore; alpha=if(hasMore) 1f else .4f }
            if(collection=="hot" && !hasMore && collectionReturn?.hasMore==true) {
                next=addButton(paging,"下一批热门") { collection=null; collectionReturn=null; page=2; catalogFocus=""; showCatalog(true) }
            }
            list.addView(paging,lp(-1,dp(50)))
        }
        cards.forEachIndexed { index,card ->
            card.nextFocusLeftId=if(index%count==0) card.id else cards[index-1].id
            card.nextFocusRightId=if(index%count==count-1 || index==cards.lastIndex) card.id else cards[index+1].id
            card.nextFocusUpId=if(index<count) (searchInput?.id ?: collectionBack?.id ?: typeButtons[library.contentType]?.id ?: categoryGenreButtons[categoryGenre]?.id ?: rankBoardButtons[rankBoard]?.id ?: recommendRefresh?.id ?: rankRefresh?.id ?: favoriteCheck?.id ?: reservationManage?.id ?: historyManage?.id ?: resumeCards.getOrNull(minOf(index,2))?.id ?: nav[tab].id) else cards[index-count].id
            card.nextFocusDownId=if(index+count<cards.size) cards[index+count].id else if(index/count<cards.lastIndex/count) cards.last().id else next?.takeIf { it.isFocusable }?.id ?: prev?.takeIf { it.isFocusable }?.id ?: recommendMore?.takeIf { it.isFocusable }?.id ?: card.id
        }
        nav.forEach { it.nextFocusDownId=typeButtons[library.contentType]?.id ?: rankBoardButtons[rankBoard]?.id ?: recommendRefresh?.id ?: rankRefresh?.id ?: favoriteCheck?.id ?: historyManage?.id ?: reservationManage?.id ?: (if(tab==TAB_CATEGORY) categoryFocusTarget().id else cards.first().id) }
        if(tab==TAB_CATEGORY) {
            categoryGenreButtons.values.forEach { it.nextFocusDownId=cards.first().id }
            categoryTypeButtons.values.forEach { it.nextFocusDownId=categoryFocusTarget().id }
        }
        if(tab==TAB_RANK) rankBoardButtons.values.forEach { it.nextFocusDownId=cards.first().id }
        rankRefresh?.nextFocusDownId=(if(tab==TAB_RANK) rankBoardButtons[rankBoard] else null)?.id ?: cards.first().id
        recommendRefresh?.nextFocusDownId=cards.first().id
        recommendMore?.nextFocusUpId=cards.last().id
        recommendMore?.nextFocusDownId=cards.first().id
        historyManage?.nextFocusDownId=if(tab==TAB_RESERVATIONS && reservationManage!=null) reservationManage!!.id else cards.first().id
        reservationManage?.nextFocusDownId=cards.first().id
        reservationManage?.nextFocusUpId=historyManage?.id ?: View.NO_ID
        favoriteCheck?.nextFocusDownId=cards.first().id
        collectionBack?.nextFocusDownId=cards.first().id
        resumeCards.forEachIndexed { index,v -> v.nextFocusDownId=cards[minOf(index,cards.lastIndex)].id }
        if(resumeCards.isNotEmpty()) cards.take(count).forEachIndexed { index,v -> v.nextFocusUpId=resumeCards[minOf(index,resumeCards.lastIndex)].id }
        else homeUpdates?.let { updates -> updates.nextFocusUpId=typeButtons[library.contentType]?.id ?: nav[TAB_HOME].id; updates.nextFocusDownId=cards.first().id; cards.take(count).forEach { it.nextFocusUpId=updates.id } }
        typeButtons.values.forEach { it.nextFocusDownId=searchInput?.id ?: collectionBack?.id ?: resumeCards.firstOrNull()?.id ?: homeUpdates?.id ?: cards.first().id }
        searchInput?.nextFocusDownId=cards.first().id
        searchButton?.nextFocusDownId=cards[minOf(4,cards.lastIndex)].id
        searchActions.forEach { it.nextFocusDownId=cards[minOf(4,cards.lastIndex)].id }
        wireSections()
        if(focusType) typeButtons[library.contentType]?.requestFocus() else if(focusNav) nav[tab].requestFocus()
        else if(catalogFocus=="@more") (recommendMore?.takeIf { it.isFocusable } ?: cards.first()).requestFocus()
        else if(resumeCards.isNotEmpty() && (catalogFocus.isEmpty() || catalogFocus.startsWith("resume:"))) (resumeCards.firstOrNull { it.tag==catalogFocus } ?: resumeCards.first()).requestFocus()
        else cards[displayed.indexOfFirst { it.id==catalogFocus }.coerceAtLeast(0)].requestFocus()
        if(keepSection) sectionButtons[tab]?.requestFocus()
        val savedScroll=scrollPositions[catalogPageKey]
        scroll.post {
            if(catalogScroll!==scroll) return@post
            cards.forEach { view ->
                val card=view as LinearLayout
                val image=card.getChildAt(0)
                val textHeight=card.paddingTop+card.paddingBottom+(1 until card.childCount).sumOf { card.getChildAt(it).measuredHeight }
                val target=minOf(((width-dp(8))/TvStyle.POSTER_ASPECT).toInt(),(scroll.height-textHeight-dp(4)).coerceAtLeast(dp(48)))
                if(image.layoutParams.height!=target) image.layoutParams=image.layoutParams.apply { height=target }
            }
            scroll.post settle@{
                if(catalogScroll!==scroll) return@settle
                savedScroll?.let { scroll.scrollTo(0,it) }
                (cards.firstOrNull { it.hasFocus() }?.parent as? View)?.let { line ->
                    val y=when {
                        line.top<scroll.scrollY -> line.top
                        line.bottom>scroll.scrollY+scroll.height -> if(line.height>=scroll.height) line.top else line.bottom-scroll.height
                        else -> scroll.scrollY
                    }
                    scroll.scrollTo(0,y.coerceAtLeast(0))
                }
                restoringScroll=false
            }
        }
    }
    private fun remoteHistoryLabel(item: AccountHistoryItem): String {
        val episode="第 ${item.episodeIndex+1} 集"
        return if(item.position>0) "$episode · ${formatTime(item.position)}" else episode
    }
    private fun selectionStatus(series: Series): String {
        val progress=library.progress(series.id)
        val remote=remoteHistory[series.id]
        val reservation=remoteReservations[series.id]
        return listOf(
            if(library.favorite(series.id)) "已收藏" else "",
            if(library.queued(series.id)) "稍后看" else "",
            if(series.id in reservedIds) "已预约" else "",
            when {
                accountRemote() && tab==TAB_RESERVATIONS && reservation!=null -> if(reservation.online) "已上线" else reservation.series.badge
                accountRemote() && tab==TAB_FAVORITES -> "账号收藏"
                accountRemote() && remote!=null -> remoteHistoryLabel(remote)
                library.watched(series.id) -> "整剧已看完"
                progress!=null -> "上次第 ${progress.episodeIndex+1} 集 · ${formatTime(progress.position)}"
                else -> ""
            }
        ).filter { it.isNotBlank() }.joinToString(" · ")
    }
    private fun renderHome(body: LinearLayout,focusNav: Boolean,focusType: Boolean,loading: Boolean,focusSection: Boolean=false) {
        homeUpdateCount=library.updatedFavorites()
        val preview=SeriesPreview(this); selectionPreview=preview; body.addView(preview)
        val top: View=typeButtons.getValue(library.contentType)
        val home=HomeScreen(this,top,::loadCover,{ item,label -> preview.show(item,if(label.startsWith("第 ")) selectionStatus(item) else listOf(label,selectionStatus(item)).filter { it.isNotBlank() }.joinToString(" · ")) },
            { item,resume -> openDetail(item,resume) },::quickActions,{ catalogFocus=it })
        homeScreen=home; body.addView(home,lp(-1,0).apply { weight=1f })
        val hot=catalog.filterNot { it.id in library.hidden() }
        home.render(homeShelves(),catalogFocus,!focusNav && !focusType && !focusSection,
            if(hasMore) { { page=2; catalogFocus=""; showCatalog(true) } } else null,homePosition)
        home.refresh.text=if(loading) if(homeFromCache) "已显示上次内容 · 正在更新热门…" else "正在更新热门…" else "刷新热门"
        home.refresh.setOnClickListener { showCatalog(true) }
        typeButtons.values.forEach { it.nextFocusDownId=home.firstId() }
        wireSections()
        if(focusSection) sectionButtons[tab]?.requestFocus()
        else if(focusNav) nav[TAB_HOME].requestFocus() else if(focusType) typeButtons.getValue(library.contentType).requestFocus()
        if(hot.isNotEmpty() && !homeContentLogged) {
            homeContentLogged=true
            body.post { android.util.Log.i("HongguoTV","Home content ready cached=$homeFromCache elapsedMs=${android.os.SystemClock.elapsedRealtime()-activityStarted}") }
        }
        if(launchStarted>0) {
            val started=launchStarted; launchStarted=0
            body.post { android.util.Log.i("HongguoTV","Home displayed cached=$homeFromCache elapsedMs=${android.os.SystemClock.elapsedRealtime()-started}") }
        }
    }
    private fun homeShelves(): List<HomeScreen.Shelf> {
        val recent=library.history().filterNot { library.watched(it.series.id) }
        val updates=library.favorites().filter { (library.favoriteUpdate(it.id)?.added ?: 0)>0 }
        val later=library.later(); val hidden=library.hidden(); val hot=catalog.filterNot { it.id in hidden }
        return listOf(
            HomeScreen.Shelf("resume","接着看",recent.take(8).map { p -> HomeScreen.Entry(p.series,
                "第 ${p.episodeIndex+1} 集 · ${if(p.completed) "接着看下一集" else formatTime(p.position)}",true,
                if(p.duration>0) (p.position*100/p.duration).toInt().coerceIn(0,100) else null) },
                if(recent.size>8) { { showCollection("resume") } } else null),
            HomeScreen.Shelf("updates","收藏有更新",updates.take(8).map { HomeScreen.Entry(it,library.favoriteLabel(it.id),true) },
                if(updates.size>8) { { showCollection("updates") } } else null),
            HomeScreen.Shelf("later","稍后看",later.take(10).map { HomeScreen.Entry(it,"稍后看 · 长按管理") },
                if(later.size>10) { { showCollection("later") } } else null),
            HomeScreen.Shelf("hot","热门发现 · ${library.contentType.label}",hot.take(10).map { HomeScreen.Entry(it,it.badge.ifBlank { "查看剧集" }) },
                if(hot.size>10) { { showCollection("hot") } } else null))
    }
    private fun refreshLibraryCards(changedId: String,removed: Boolean=false) {
        if(screen!=Screen.CATALOG) return
        val home=homeScreen
        if(home!=null) { home.updateShelves(homeShelves(),requestFocus=true); home.refreshSelection(); return }
        if(removed && (localCatalog() || tab==TAB_HOME || tab==TAB_RECOMMEND)) {
            val oldIds=catalogCards.keys.toList(); val index=oldIds.indexOf(changedId).coerceAtLeast(0)
            if(localCatalog()) {
                catalog=localItems(); page=page.coerceIn(1,maxOf(1,(catalog.size+pageSize-1)/pageSize)); hasMore=page*pageSize<catalog.size
            }
            val remaining=if(localCatalog()) catalog.drop((page-1)*pageSize).take(pageSize) else catalog.filterNot { it.id in library.hidden() }
            catalogFocus=remaining.getOrNull(index.coerceAtMost((remaining.size-1).coerceAtLeast(0)))?.id.orEmpty()
            val y=catalogScroll?.scrollY ?: 0; scrollPositions[catalogPageKey]=y
            artwork.cancelPage(); favoriteBadges.clear()
            catalogBody?.let { body -> body.removeAllViews(); catalogGrid(body,false) }
        } else {
            catalog.firstOrNull { it.id==changedId }?.let { selectionPreview?.show(it,selectionStatus(it)) }
            favoriteBadges[changedId]?.text=library.favoriteLabel(changedId)
        }
    }
    private fun quickActions(series: Series,anchor: View) {
        if(tab==TAB_RESERVATIONS) {
            val reservation=remoteReservations[series.id]
            val labels=mutableListOf(if(reservation?.online==true) "查看详情" else "查看预约详情")
            labels+=if(series.id in reservedIds) "取消预约" else "预约"
            labels+="刷新预约"
            tvTools.choose(series.title,labels,anchor,{ choice ->
                when(choice) {
                    0 -> if(reservation?.online==true) openDetail(series,false,true) else openPreview(series,reservation)
                    1 -> if(series.id in reservedIds) {
                        tvTools.confirm("取消预约","确定取消《${series.title}》的预约？",anchor) { toggleReservation(series,reservation) }
                    } else toggleReservation(series,reservation)
                    2 -> showCatalog(true)
                }
            })
            return
        }
        if(accountRemote()) {
            val remote=remoteHistory[series.id]
            val labels=mutableListOf(if(tab==TAB_HISTORY && remote!=null) "继续观看" else "直接播放","查看剧集详情")
            tvTools.choose(series.title,labels,anchor,{ choice ->
                if(choice==0) {
                    if(tab==TAB_HISTORY && remote!=null)
                        openDetail(series,true,true,false,remote.episodeId,remote.position,remote.episodeIndex)
                    else openDetail(series,true)
                } else openDetail(series,false)
            })
            return
        }
        val labels=mutableListOf(if(library.progress(series.id)!=null) "继续观看" else "直接播放",
            if(library.favorite(series.id)) "取消收藏" else "收藏这部剧",
            if(library.queued(series.id)) "移出稍后看" else "加入稍后看","查看剧集详情")
        if(tab==TAB_HOME || tab==TAB_RECOMMEND) labels+="不在推荐中显示"
        if(tab==TAB_HISTORY) labels+="管理观看记录"
        tvTools.choose(series.title,labels,anchor,{ choice ->
            when(choice) {
                0 -> openDetail(series,true)
                1 -> { val added=library.toggle(series); Toast.makeText(this,if(added) "已收藏" else "已取消收藏",Toast.LENGTH_SHORT).show(); refreshLibraryCards(series.id,!added && (tab==TAB_FAVORITES || collection=="updates")); if(added) favoriteMonitor.check() }
                2 -> { val added=library.toggleLater(series); Toast.makeText(this,if(added) "已加入稍后看" else "已移出稍后看",Toast.LENGTH_SHORT).show(); refreshLibraryCards(series.id,!added && collection=="later") }
                3 -> openDetail(series)
                4 -> if(tab==TAB_HISTORY) manageHistory(series,anchor) else { library.hide(series.id); Toast.makeText(this,"已隐藏热门推荐，可在设置中恢复",Toast.LENGTH_SHORT).show(); refreshLibraryCards(series.id,true) }
            }
        })
    }
    private fun refreshFavoriteLabels() {
        if(screen!=Screen.CATALOG || !library.isLoaded) return
        favoriteBadges.forEach { (id,label) -> val value=library.favoriteLabel(id); if(label.text.toString()!=value) label.text=value }
        if(!accountRemote()) {
            favoriteStatus?.text=favoriteMonitor.status
            favoriteCheck?.text=if(favoriteMonitor.running) "正在检查…" else "检查更新"
        } else if(tab==TAB_FAVORITES) {
            favoriteStatus?.text="来自红果账号"
            favoriteCheck?.text="刷新收藏"
        }
        homeUpdates?.text="收藏更新 ${library.updatedFavorites()} 部"
        val home=homeScreen
        if(screen==Screen.CATALOG && tab==TAB_HOME && page==1 && collection==null && home!=null && library.updatedFavorites()!=homeUpdateCount) {
            // A stable resume key can be restored without moving the user's selection.
            if(!tvTools.showing && !updater.showing && currentFocus?.tag?.toString()?.startsWith("resume:")==true) {
                homeUpdateCount=library.updatedFavorites(); home.updateShelves(homeShelves())
            } else {
                home.refresh.text="收藏更新 ${library.updatedFavorites()} 部 · 按确认查看"
                home.refresh.setOnClickListener { showCatalog() }
            }
        }
    }
    private fun authMessage(problem: Throwable)=problem.message.orEmpty()
        .replace(Regex("https?://\\S+"),"[地址]").take(160).ifBlank { "请检查网络后重试" }
    /** 账号页：未登录显示扫码入口，已登录显示资料与退出入口。返回首次进入时应聚焦的控件。 */
    private fun account(parent: LinearLayout): View {
        parent.addView(text("账号",25f).apply {
            typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL); setPadding(0,dp(15),0,dp(7))
        })
        val session=repository.currentSession()
        if(session==null) parent.addView(text("使用红果 App 扫码登录；登录后可读取账号资料、观看记录、收藏与预约。",13f,muted).apply {
            setPadding(0,0,0,dp(18))
        })
        return if(session!=null) accountSignedIn(parent,session) else accountSignedOut(parent)
    }
    private fun accountSignedIn(parent: LinearLayout,session: AuthSession): View {
        stopQrLogin()
        authBusy=false
        val card=row().apply { gravity=Gravity.TOP; setPadding(dp(20),dp(20),dp(20),dp(20)); background=rounded(surface) }
        val avatar=ImageView(this).apply {
            scaleType=ImageView.ScaleType.FIT_CENTER; background=rounded(bg)
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        card.addView(avatar,lp(dp(104),dp(104)).apply { rightMargin=dp(22) })
        if(session.user.avatar.isNotBlank()) loadCover(avatar,session.user.avatar)
        val info=column(); card.addView(info,lp(0,-2).apply { weight=1f })
        val profileMissing=session.user.name.isBlank()
        info.addView(text(if(profileMissing) "已扫码登录" else session.user.name,24f).apply {
            setTypeface(null,Typeface.BOLD); maxLines=1; ellipsize=TextUtils.TruncateAt.END
        })
        info.addView(text(
            when {
                session.user.mobile.isNotBlank() -> session.user.mobile
                profileMissing -> "账号资料暂不可用，可尝试刷新"
                else -> "红果 App 扫码登录"
            },14f,muted
        ).apply {
            setPadding(0,dp(6),0,dp(6))
        })
        val meta=mutableListOf<String>()
        if(session.user.followers>0) meta+="粉丝 ${session.user.followers}"
        if(session.user.following>0) meta+="关注 ${session.user.following}"
        meta+="登录信息仅保存在本机"
        info.addView(text(meta.joinToString("  ·  "),12f,muted))
        if(session.user.description.isNotBlank()) {
            info.addView(text(session.user.description,14f,muted).apply {
                maxLines=3; ellipsize=TextUtils.TruncateAt.END; setPadding(0,dp(8),0,0)
            })
        }
        parent.addView(card,lp(-1,-2))
        val actions=row().apply { setPadding(0,dp(16),0,0) }
        lateinit var refresh: TextView
        refresh=addButton(actions,"刷新资料") { refreshAccount(refresh) }
        lateinit var logout: TextView
        logout=addButton(actions,"退出登录") {
            tvTools.confirm("退出登录","退出后本机收藏和观看进度仍会保留。",logout) {
                clearAccountSession(); showCatalog()
            }
        }
        parent.addView(actions)
        parent.addView(text("观看进度自动同步到当前账号；网络失败会保留待同步记录。",13f,muted).apply { setPadding(0,dp(12),0,0) })
        val up=sectionButtons[tab]?.id ?: nav[tab].id
        refresh.nextFocusUpId=up; logout.nextFocusUpId=up
        refresh.nextFocusRightId=logout.id; logout.nextFocusLeftId=refresh.id
        refresh.nextFocusDownId=refresh.id; logout.nextFocusDownId=logout.id
        nav.forEach { it.nextFocusDownId=refresh.id }
        return refresh
    }
    private fun accountSessionExpired(problem: Throwable): Boolean {
        val message=authMessage(problem)
        return message.contains("未登录") || message.contains("请先登录") || message.contains("登录已过期") ||
            message.contains("HTTP 401") || message.contains("HTTP 403")
    }
    private fun clearAccountRecords() {
        remoteHistory.clear(); remoteReservations.clear(); reservedIds.clear()
        for(index in listOf(TAB_HISTORY,TAB_FAVORITES,TAB_RESERVATIONS)) {
            tabState.remove(index); tabFocus.remove(index)
        }
    }
    private fun clearAccountSession() {
        repository.logout(); AuthStore.clear(this); authRevision++
        clearAccountRecords()
    }
    /** 预约/取消预约共用一条路径；成功后刷新当前页或预约详情。 */
    private fun toggleReservation(series: Series,reservation: AccountReservation?,anchor: TextView?=null) {
        if(reservationBusy) return
        if(repository.currentSession()==null) {
            Toast.makeText(this,"登录红果账号后才能预约",Toast.LENGTH_SHORT).show()
            switchTab(TAB_ACCOUNT)
            return
        }
        val active=series.id !in reservedIds
        val itemType=reservation?.itemType ?: 14
        val oldLabel=anchor?.text?.toString().orEmpty()
        reservationBusy=true; anchor?.text="处理中…"
        io.execute {
            val result=runCatching { repository.setReservation(series.id,active,itemType) }
            main.post {
                reservationBusy=false
                if(isDestroyed) return@post
                if(anchor!=null && anchor.isAttachedToWindow) anchor.text=oldLabel
                result.onSuccess {
                    if(active) {
                        reservedIds+=series.id
                        remoteReservations[series.id]=reservation?.copy(subscribed=true)
                            ?: AccountReservation(series,itemType,false,0,true,"")
                    } else {
                        reservedIds-=series.id; remoteReservations.remove(series.id)
                    }
                    Toast.makeText(this,if(active) "已预约《${series.title}》" else "已取消预约",Toast.LENGTH_SHORT).show()
                    when {
                        screen==Screen.PREVIEW && previewSeries?.id==series.id ->
                            openPreview(series,remoteReservations[series.id] ?: reservation)
                        screen==Screen.DETAIL && detail?.series?.id==series.id -> showDetail(false)
                        screen==Screen.CATALOG && tab==TAB_RESERVATIONS -> showCatalog(true)
                    }
                }.onFailure { problem ->
                    if(accountSessionExpired(problem)) {
                        clearAccountSession()
                        Toast.makeText(this,"登录状态已失效，请重新登录",Toast.LENGTH_LONG).show()
                        if(screen==Screen.CATALOG || screen==Screen.PREVIEW || screen==Screen.DETAIL) showCatalog()
                    } else Toast.makeText(this,authMessage(problem),Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    private fun chooseReservationToCancel(anchor: View?) {
        if(repository.currentSession()==null) {
            Toast.makeText(this,"登录红果账号后才能管理预约",Toast.LENGTH_SHORT).show()
            switchTab(TAB_ACCOUNT); return
        }
        val rows=remoteReservations.values.filter { it.subscribed }
        if(rows.isEmpty()) {
            tvTools.info("取消预约","当前账号没有可取消的预约。",anchor)
            return
        }
        tvTools.choose("选择要取消的预约",rows.map { it.series.title },anchor,{ index ->
            val item=rows.getOrNull(index) ?: return@choose
            tvTools.confirm("取消预约","确定取消《${item.series.title}》的预约？",anchor) {
                toggleReservation(item.series,item)
            }
        },"刷新预约") { showCatalog(true) }
    }
    private fun refreshAccount(anchor: TextView) {
        if(authBusy) return
        authBusy=true; anchor.text="正在刷新…"
        val revision=authRevision
        io.execute {
            val result=runCatching { repository.refreshUser() }
            main.post {
                authBusy=false
                if(isDestroyed || revision!=authRevision) return@post
                anchor.text="刷新资料"
                result.onSuccess { session ->
                    AuthStore.save(this,session)
                    Toast.makeText(this,"账号资料已更新",Toast.LENGTH_SHORT).show()
                    if(screen==Screen.CATALOG && tab==TAB_ACCOUNT) showCatalog()
                }.onFailure { problem ->
                    val message=authMessage(problem)
                    if(accountSessionExpired(problem)) {
                        clearAccountSession()
                        Toast.makeText(this,"登录状态已失效，请重新登录",Toast.LENGTH_LONG).show()
                        if(screen==Screen.CATALOG) showCatalog()
                    } else Toast.makeText(this,message,Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    private fun accountSignedOut(parent: LinearLayout): View {
        stopQrLogin()
        val narrow=resources.displayMetrics.widthPixels<dp(600)
        val card=column().apply { setPadding(dp(20),dp(18),dp(20),dp(20)); background=rounded(surface) }
        val content=if(narrow) column().apply { gravity=Gravity.CENTER_HORIZONTAL }
        else row().apply { gravity=Gravity.TOP }
        val qrSize=if(narrow) dp(200) else dp(250)
        val qrFrame=FrameLayout(this).apply {
            background=rounded(Color.WHITE); setPadding(dp(8),dp(8),dp(8),dp(8))
        }
        val qr=ImageView(this).apply {
            scaleType=ImageView.ScaleType.FIT_CENTER; contentDescription="红果登录二维码"
        }
        qrFrame.addView(qr,FrameLayout.LayoutParams(-1,-1))
        content.addView(qrFrame,lp(qrSize,qrSize).apply { if(!narrow) rightMargin=dp(24) })
        val info=column()
        info.addView(text("扫码登录",20f).apply { setTypeface(null,Typeface.BOLD) })
        info.addView(text("打开红果 App，用扫一扫扫描二维码，然后在手机上确认登录。",13f,muted).apply {
            setPadding(0,dp(6),0,0)
        })
        val status=text("正在获取二维码…",14f,accent).apply { setPadding(0,dp(12),0,0) }
        info.addView(status)
        val error=text("",13f,accent).apply { visibility=View.GONE; setPadding(0,dp(8),0,0) }
        info.addView(error)
        val refresh=button("刷新二维码") { startQrLogin() }
        refresh.minHeight=dp(46); refresh.setPadding(dp(14),0,dp(14),0)
        info.addView(refresh,lp(dp(180),dp(46)).apply { topMargin=dp(14) })
        if(narrow) content.addView(info,lp(-1,-2).apply { topMargin=dp(14) })
        else content.addView(info,lp(0,-2).apply { weight=1f })
        card.addView(content)
        parent.addView(card,lp(-1,-2))
        qrImageView=qr; qrStatusView=status; qrErrorView=error; qrRefreshButton=refresh
        val up=sectionButtons[tab]?.id ?: nav[tab].id
        refresh.nextFocusUpId=up; refresh.nextFocusDownId=refresh.id
        refresh.nextFocusLeftId=refresh.id; refresh.nextFocusRightId=refresh.id
        nav.forEach { it.nextFocusDownId=refresh.id }
        startQrLogin()
        return refresh
    }
    private fun startQrLogin() {
        if(qrCreating || screen!=Screen.CATALOG || tab!=TAB_ACCOUNT || repository.currentSession()!=null) return
        val revision=++qrRevision
        stopQrPoll()
        qrCreating=true
        qrPollInFlight=false; qrPollRevision=-1
        qrImageView?.setImageDrawable(null)
        qrStatusView?.text="正在获取二维码…"
        qrErrorView?.apply { text=""; visibility=View.GONE }
        io.execute {
            val result=runCatching { repository.createLoginQr() }
            main.post {
                if(revision==qrRevision) qrCreating=false
                if(isDestroyed || revision!=qrRevision || screen!=Screen.CATALOG || tab!=TAB_ACCOUNT) return@post
                result.onSuccess { qr ->
                    if(showQrImage(qr.image)) {
                        qrStatusView?.text="请使用红果 App 扫描二维码"
                        scheduleQrPoll(revision)
                    } else {
                        qrStatusView?.text="二维码加载失败"
                        qrErrorView?.apply { text="二维码图片无法显示，请刷新重试"; visibility=View.VISIBLE }
                        qrRefreshButton?.requestFocus()
                    }
                }.onFailure { problem ->
                    qrStatusView?.text="二维码加载失败"
                    qrErrorView?.apply { text=authMessage(problem); visibility=View.VISIBLE }
                    qrRefreshButton?.requestFocus()
                }
            }
        }
    }
    private fun scheduleQrPoll(revision: Int) {
        stopQrPoll()
        if(revision!=qrRevision) return
        val tick=Runnable { pollQr(revision) }
        qrPollRunnable=tick
        main.postDelayed(tick,2000L)
    }
    private fun pollQr(revision: Int) {
        if(revision!=qrRevision || screen!=Screen.CATALOG || tab!=TAB_ACCOUNT || repository.currentSession()!=null) return
        if(qrPollInFlight && qrPollRevision==revision) return
        qrPollInFlight=true; qrPollRevision=revision
        io.execute {
            val result=runCatching { repository.pollLoginQr() }
            main.post {
                if(qrPollRevision==revision) qrPollInFlight=false
                if(isDestroyed || revision!=qrRevision || screen!=Screen.CATALOG || tab!=TAB_ACCOUNT) return@post
                result.onSuccess { poll ->
                    when(poll.status) {
                        LoginQrStatus.NEW -> {
                            qrStatusView?.text="请使用红果 App 扫描二维码"
                            scheduleQrPoll(revision)
                        }
                        LoginQrStatus.SCANNED -> {
                            qrStatusView?.text="已扫码，请在手机上确认登录"
                            scheduleQrPoll(revision)
                        }
                        LoginQrStatus.EXPIRED -> {
                            qrStatusView?.text="二维码已过期，正在刷新…"
                            main.postDelayed({
                                if(revision==qrRevision && screen==Screen.CATALOG && tab==TAB_ACCOUNT) startQrLogin()
                            },800)
                        }
                        LoginQrStatus.CONFIRMED -> {
                            val session=poll.session
                            if(session==null) {
                                qrStatusView?.text="登录状态读取失败"
                                qrErrorView?.apply { text="请刷新二维码重试"; visibility=View.VISIBLE }
                                qrRefreshButton?.apply { isEnabled=true; alpha=1f; requestFocus() }
                            } else {
                                AuthStore.save(this,session)
                                accountHistorySync.resume(library.history())
                                clearAccountRecords()
                                authRevision++
                                qrRevision++
                                stopQrPoll()
                                repository.cancelLoginQr()
                                Toast.makeText(this,"登录成功",Toast.LENGTH_SHORT).show()
                                if(screen==Screen.CATALOG) {
                                    if(accountTab()) { catalog=emptyList(); hasMore=false; showCatalog(load=true) }
                                    else showCatalog()
                                }
                            }
                        }
                    }
                }.onFailure { problem ->
                    qrStatusView?.text="二维码状态读取失败"
                    qrErrorView?.apply { text=authMessage(problem); visibility=View.VISIBLE }
                    qrRefreshButton?.apply { isEnabled=true; alpha=1f; requestFocus() }
                }
            }
        }
    }
    private fun showQrImage(dataUri: String): Boolean {
        val payload=dataUri.substringAfter(',',dataUri)
        if(payload.length>2_000_000) return false
        val bytes=runCatching { Base64.decode(payload,Base64.DEFAULT) }.getOrNull() ?: return false
        val bitmap=runCatching { BitmapFactory.decodeByteArray(bytes,0,bytes.size) }.getOrNull() ?: return false
        qrImageView?.setImageBitmap(bitmap)
        return true
    }
    private fun settings(parent: LinearLayout) {
        parent.addView(text("按你的习惯播放",25f).apply { typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL); setPadding(0,dp(15),0,dp(7)) })
        parent.addView(text("偏好自动保存，下一部剧继续使用。",13f,muted).apply { setPadding(0,0,0,dp(18)) })
        val groups=row().apply { gravity=Gravity.TOP }
        val playback=column(); val device=column(); val recommend=column()
        listOf(playback,device,recommend).forEachIndexed { i,group ->
            group.setPadding(dp(16),dp(16),dp(16),dp(16)); group.background=rounded(surface)
            groups.addView(group,lp(0,-2).apply { weight=1f; if(i<2) rightMargin=dp(16) })
        }
        parent.addView(groups)
        fun heading(group: LinearLayout,title: String,subtitle: String) {
            group.addView(text(title,18f).apply { setTypeface(null,Typeface.BOLD) })
            group.addView(text(subtitle,12f,muted).apply { setPadding(0,dp(5),0,dp(15)) })
        }
        fun setting(group: LinearLayout,label: String,action: ()->Unit): TextView {
            val view=button(label,onClick=action).apply {
                gravity=Gravity.CENTER_VERTICAL; setPadding(dp(13),dp(11),dp(13),dp(11))
                background=rounded(bg)
                setOnFocusChangeListener { _,focused -> background=rounded(if(focused) TvStyle.raised else bg,if(focused) accent else Color.TRANSPARENT) }
            }
            group.addView(view,lp(-1,-2).apply { bottomMargin=dp(8) })
            return view
        }
        heading(playback,"播放偏好","所有剧集共用，播放中也可调整")
        heading(device,"片单与设备","保管记录，保持应用更新")
        heading(recommend,"推荐","按收藏与观看混合三类内容")
        lateinit var qualityButton: TextView
        qualityButton=setting(playback,"默认清晰度  ·  ${library.maxQuality}P") { showQualityPicker(qualityButton,false) }
        lateinit var defaultSpeed: TextView
        defaultSpeed=setting(playback,"默认倍速  ·  ${PlaybackSpeed.label(library.playbackSpeed)}") { showSpeedPicker(defaultSpeed,false) }
        lateinit var frame: TextView
        frame=setting(playback,"画面模式  ·  ${library.frameMode.label}") { showFramePicker(frame,false) }
        lateinit var autoNextButton: TextView
        autoNextButton=setting(playback,"自动连播  ·  ${if(library.autoNext) "开启" else "关闭"}") {
            library.autoNext=!library.autoNext
            autoNextButton.text="自动连播  ·  ${if(library.autoNext) "开启" else "关闭"}"
        }
        lateinit var danmakuSetting: TextView
        danmakuSetting=setting(playback,"弹幕设置  ·  ${danmakuSummary()}") { showDanmakuSettings(danmakuSetting) }
        lateinit var backup: TextView
        backup=setting(device,"手机备份与恢复") {
            runCatching { LibraryBackup.encode(library.snapshot()) }.onSuccess { encoded ->
                tvTools.libraryTransfer(encoded,backup) { data,restoreSettings ->
                    favoriteMonitor.stop(); library.restore(data,restoreSettings)
                    tabState.clear(); tabFocus.clear(); catalogFocus=""; page=1
                    showCatalog(); favoriteMonitor.check()
                    Toast.makeText(this,"记录已合并恢复",Toast.LENGTH_LONG).show()
                }
            }.onFailure { tvTools.info("无法生成备份","本机记录暂时无法导出，请重试。",backup) }
        }
        lateinit var hidden: TextView
        hidden=setting(device,"恢复隐藏推荐  ·  ${library.hidden().size} 部") {
            library.unhideAll(); hidden.text="恢复隐藏推荐  ·  0 部"; Toast.makeText(this,"热门推荐已恢复",Toast.LENGTH_SHORT).show()
        }
        lateinit var updates: TextView
        updates=setting(device,"版本与更新  ·  ${BuildConfig.VERSION_NAME}") { updater.show(updates) }
        val license=setting(device,"开源许可") {
            AlertDialog.Builder(this).setTitle("开源许可").setMessage("本原生版以 GPL-3.0 发布。\n内容协议与加密处理移植自 drpys（22261ad）。\nAndroidX Media3 / OkHttp：Apache-2.0\nKotlin：Apache-2.0\nBouncy Castle：MIT\n完整源码和许可证见 GitHub：wanwdbb/hongguoTV。").setPositiveButton("关闭",null).show()
        }
        lateinit var recommendModeButton: TextView
        recommendModeButton=setting(recommend,"推荐模式  ·  ${recommendMode.label}") { showRecommendModePicker(recommendModeButton) }
        lateinit var resetInterest: TextView
        resetInterest=setting(recommend,"重置兴趣  ·  ${if(recommendResetAt>0) "已重置" else "未重置"}") { resetRecommendInterest(resetInterest) }
        val left=listOf(qualityButton,defaultSpeed,frame,autoNextButton,danmakuSetting); val right=listOf(backup,hidden,updates,license)
        val recommendColumn=listOf(recommendModeButton,resetInterest)
        val columns=listOf(left,right,recommendColumn)
        columns.forEach { items -> items.forEachIndexed { index,view ->
            view.nextFocusUpId=items.getOrNull(index-1)?.id ?: nav[tab].id
            view.nextFocusDownId=items.getOrNull(index+1)?.id ?: view.id
        } }
        columns.forEachIndexed { columnIndex,items -> items.forEachIndexed { rowIndex,view ->
            view.nextFocusLeftId=columns.getOrNull(columnIndex-1)?.let { it.getOrNull(rowIndex) ?: it.lastOrNull() }?.id ?: view.id
            view.nextFocusRightId=columns.getOrNull(columnIndex+1)?.let { it.getOrNull(rowIndex) ?: it.firstOrNull() }?.id ?: view.id
        } }
        nav.forEach { it.nextFocusDownId=qualityButton.id }
        parent.addView(text("红果 TV  /  ${BuildConfig.VERSION_NAME}    ·    本机保存进度，登录后自动同步观看记录",12f,muted).apply { setPadding(dp(2),dp(18),0,dp(10)) })
        nav[tab].requestFocus()
    }
    private fun runSearch(value: String,type: ContentType=library.contentType) {
        val clean=value.trim(); if(clean.isEmpty() || clean.length>80) return
        (searchInput as? EditText)?.let { (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(it.windowToken,0) }
        if(library.contentType!=type) { library.contentType=type; for(index in listOf(TAB_HOME,TAB_SEARCH)) { tabState.remove(index); tabFocus.remove(index) } }
        library.rememberSearch(clean,type); query=clean; tab=TAB_SEARCH; page=1; catalogFocus=""; showCatalog(true)
    }
    private fun showSearchHistory(anchor: View) {
        val history=library.searches()
        if(history.isEmpty()) { tvTools.info("搜索历史","搜索过的剧名会保存在这里，最多保留 20 条。",anchor); return }
        tvTools.choose("搜索历史",history.map { "${it.type.label} · ${it.query}" },anchor,{ index -> runSearch(history[index].query,history[index].type) },"清空历史") {
            tvTools.confirm("清空搜索历史","清除本机保存的搜索词？",anchor) { library.clearSearches() }
        }
    }
    private fun manageHistory(series: Series,anchor: View?) {
        tvTools.choose(series.title,listOf("继续观看",if(library.watched(series.id)) "恢复未看状态" else "标记整剧已看","删除这条记录"),anchor,{ index ->
            when(index) {
                0 -> openDetail(series,true)
                1 -> { library.setWatched(series.id,!library.watched(series.id)); catalogFocus=series.id; showCatalog() }
                2 -> tvTools.confirm("删除观看记录","删除《${series.title}》的本机观看进度？收藏会保留。",anchor) { library.removeHistory(series.id); showCatalog() }
            }
        })
    }
    private fun mergeCloudHistory(session: AuthSession,rows: List<AccountHistoryItem>) {
        if(isDestroyed || !library.isLoaded || repository.currentSession()!=session) return
        // A cloud pull must not seek or overwrite an already running playback session.
        val activeId=detail?.series?.id.takeIf { screen==Screen.PLAYER && player!=null }
        val changed=library.mergeRemote(rows.filterNot { it.series.id==activeId })
        accountHistorySync.adoptRemote(changed.map { it.series.id },session)
        rows.forEach { row ->
            val previous=remoteHistory[row.series.id]
            if(previous==null || row.readAt>previous.readAt) remoteHistory[row.series.id]=row
        }
        if(changed.isNotEmpty()) {
            library.flush()
            if(screen==Screen.CATALOG && tab==TAB_HOME) homeScreen?.updateShelves(homeShelves())
            android.util.Log.i("HongguoTV","Cloud history merged records="+changed.size)
        }
    }
    private fun cloudResume(series: Series): Pair<AuthSession?,AccountPage?> {
        val session=repository.currentSession()
        return session to session?.let { repository.accountResumeHistory(it,listOf(series)) }
    }
    private fun continueLatest(data: Detail,autoplay: Boolean=true) {
        work({ cloudResume(data.series) }, { (session,cloud) ->
            if(session!=null && repository.currentSession()!=session) return@work
            if(session!=null && cloud!=null) mergeCloudHistory(session,cloud.history)
            val saved=library.progress(data.series.id)
            val target=ResumePlayback.target(data.episodes,saved?.episodeId,saved?.position ?: 0,saved?.completed==true)
            playEpisode(target.index,target.position,autoplay,saveCurrent=false)
        }, { Toast.makeText(this,"续播进度暂时无法确认，请联网后重试",Toast.LENGTH_LONG).show() })
    }
    private fun openDetail(series: Series,resume: Boolean=false,autoplay: Boolean=true,fromCharacter: Boolean=false,remoteEpisodeId: String="",remotePosition: Long=0,remoteEpisodeIndex: Int=-1) {
        pendingDetail=series; detailAutoplay=autoplay
        pendingRemoteEpisode=remoteEpisodeId; pendingRemotePosition=remotePosition; pendingRemoteIndex=remoteEpisodeIndex
        if(!fromCharacter) { character=null; characterPerson=null; characterReturnDetail=null }
        detailFromCharacter=fromCharacter
        invalidatePage(); releaseCatalogViews(); screen=Screen.DETAIL; directPlayback=resume; detail=null; synopsisExpanded=false
        val body=base(series.title); message(body,"正在加载剧集…")
        work({ Pair(repository.detail(series.id),cloudResume(series)) }, { (result,cloudState) ->
            val (session,cloud)=cloudState
            if(session!=null && repository.currentSession()!=session) return@work
            if(session!=null && cloud!=null) mergeCloudHistory(session,cloud.history)
            detail=result.copy(series=result.series.copy(bookType=series.bookType.takeIf { it in 2..5 } ?: result.series.bookType)); library.observeFavorite(series.id,result.episodes.size,System.currentTimeMillis(),acknowledge=true)
            val progress=library.progress(series.id)
            val remoteIndex=when {
                pendingRemoteEpisode.isNotBlank() -> result.episodes.indexOf(pendingRemoteEpisode)
                pendingRemoteIndex in result.episodes.indices -> pendingRemoteIndex
                else -> -1
            }
            val target=ResumePlayback.target(result.episodes,progress?.episodeId,progress?.position ?: 0,progress?.completed==true)
            episodeIndex=target.index; group=episodeIndex/20
            if(resume) playEpisode(target.index,target.position,autoplay=detailAutoplay) else showDetail(false)
        }, { problem -> error(body,problem) { openDetail(series,resume,detailAutoplay,fromCharacter,remoteEpisodeId,remotePosition,remoteEpisodeIndex) } })
    }
    /**
     * 推荐页就是播放页：进入推荐立刻自动播放（没有卡片），播放中 ↑/↓ 换上一部/下一部。
     * 连播按推荐片单顺序走，切走的那一部会先存下播放位置。
     */
    private fun enterFeed() {
        feedMode=true; directPlayback=true; requestedAutoplay=true
        val index=catalog.indexOfFirst { it.id==feedSeriesId }
        if(index>=0) { feedIndex=index; catalogFocus=catalog[index].id; playFeedSeries(catalog[index]); return }
        loadFeedSession()
    }
    /** 换一批：重新开一次推荐会话，取到第一批就从第一部开始播。 */
    private fun loadFeedSession() {
        feedMode=true; directPlayback=true
        clearFeedPrefetch()
        if(screen==Screen.PLAYER) { saveProgress(); library.flush(); releasePlayer() }
        catalog=emptyList(); hasMore=false; page=1; catalogFocus=""; feedSeriesId=""; pendingFeedIndex=null
        showFeedLoading("正在为你挑选内容…")
        newRecommendSession()
        val mode=recommendMode; val tags=recommendTags; val startPage=recommendStartPage()
        val hidden=if(library.isLoaded) library.hidden() else emptySet()
        work({ recommendPool(1,mode,tags,startPage,hidden) }, { candidates ->
            if(!feedMode) return@work
            mergeRecommendBatch(1,candidates)
            val first=catalog.firstOrNull()
            if(first==null) { showFeedError(java.io.IOException("推荐来源暂时没有内容")); return@work }
            feedIndex=0; catalogFocus=first.id; feedSeriesId=first.id; playFeedSeries(first)
        }, { problem -> if(feedMode) showFeedError(problem) })
    }
    /** 推荐还没取到片单时的过渡页：不显示任何卡片，取到就直接开播。 */
    private fun showFeedLoading(label: String) {
        invalidatePage(); releaseCatalogViews(); screen=Screen.CATALOG; feedMode=true
        val container=base(); buildNav(container)
        val body=column(); container.addView(body,lp(-1,0).apply { weight=1f })
        message(body,label)
        nav.getOrNull(tab)?.requestFocus()
    }
    private fun showFeedError(problem: Throwable) {
        invalidatePage(); releaseCatalogViews(); screen=Screen.CATALOG; feedMode=true
        val container=base(); buildNav(container)
        val body=column(); container.addView(body,lp(-1,0).apply { weight=1f })
        error(body,problem) { if(feedMode) enterFeed() }
        currentFocus?.let { retry -> nav.forEach { it.nextFocusDownId=retry.id } }
    }
    /** 切到推荐里的另一部：先存当前这部的位置，再取详情并从上次看到的地方继续。 */
    private fun playFeedSeries(series: Series) {
        feedSeriesId=series.id
        if(screen==Screen.PLAYER && player!=null && detail?.series?.id==series.id && !playError) return
        saveProgress(); library.flush(); player?.pause()
        if(screen!=Screen.PLAYER) Toast.makeText(this,"正在打开《${series.title}》…",Toast.LENGTH_SHORT).show()
        if(screen==Screen.PLAYER && ::playbackText.isInitialized) { playbackText.text="正在切换到《${series.title}》…"; showHud() }
        val ready=preparedSeries.take(series.id)
        val known=ready?.detail ?: detail?.takeIf { it.series.id==series.id }
        work({ Pair(known ?: repository.detail(series.id),cloudResume(series)) }, { (result,cloudState) ->
            val (session,cloud)=cloudState
            if(session!=null && repository.currentSession()!=session) { ready?.close(); return@work }
            if(session!=null && cloud!=null) mergeCloudHistory(session,cloud.history)
            startFeedEpisode(series,result,ready)
        }, { problem ->
            ready?.close()
            if(feedMode) Toast.makeText(this,"《${series.title}》暂时打不开，按 ↑/↓ 换一部",Toast.LENGTH_LONG).show()
            if(screen==Screen.PLAYER && ::playbackText.isInitialized) showHud()
        })
    }
    /** 详情已经拿到：从上次的位置开播；预取的那一集对得上就直接用，省掉播放接口和首屏缓冲。 */
    private fun startFeedEpisode(series: Series,data: Detail,ready: FeedReady?) {
        detail=data.copy(series=data.series.copy(bookType=series.bookType.takeIf { it in 2..5 } ?: data.series.bookType)); library.observeFavorite(series.id,data.episodes.size,System.currentTimeMillis(),acknowledge=true)
        val progress=library.progress(series.id)
        val target=ResumePlayback.target(data.episodes,progress?.episodeId,progress?.position ?: 0,progress?.completed==true)
        val usable=ready?.takeIf { it.index==target.index && it.quality==library.maxQuality }
        if(usable==null) ready?.close()
        playEpisode(target.index,target.position,autoplay=true,saveCurrent=false,ready=usable)
    }
    /** ↑/↓ 换部：超出已经取到的片单时先续一批。 */
    private fun switchFeed(delta: Int) {
        if(!feedMode) return
        val target=feedIndex+delta
        if(target<0) { Toast.makeText(this,"已经是推荐的上一部",Toast.LENGTH_SHORT).show(); return }
        if(target>=catalog.size) {
            pendingFeedIndex=target
            if(!hasMore) finishFeed()
            else { if(feedBatchJob==null) Toast.makeText(this,"正在加载更多推荐…",Toast.LENGTH_SHORT).show(); appendFeedBatch() }
            return
        }
        feedIndex=target; catalogFocus=catalog[target].id
        playFeedSeries(catalog[target])
    }
    /** 续批到达后再切：用户连按 ↓ 时不会白白丢掉这一次按键。 */
    private fun handlePendingFeed() {
        val pending=pendingFeedIndex ?: return
        pendingFeedIndex=null
        if(!feedMode || screen!=Screen.PLAYER) return
        if(pending<catalog.size) { feedIndex=pending; catalogFocus=catalog[pending].id; playFeedSeries(catalog[pending]) }
        else if(hasMore) { pendingFeedIndex=pending; appendFeedBatch() }
        else finishFeed()
    }
    /** 一批推荐看完：自动换一批新的接着播，所以推荐可以一直看下去。 */
    private fun finishFeed() {
        pendingFeedIndex=null
        refreshFeedSession()
    }
    /**
     * 换一批：在后台取新的一批再切过去，当前这一部先停在画面上不拆播放器，
     * 所以换一批不会把正在看的内容打断成一张空白加载页。
     */
    private fun refreshFeedSession() {
        if(feedRefreshJob!=null) return
        requestedAutoplay=false; player?.pause()
        clearFeedPrefetch()
        if(screen==Screen.PLAYER && ::playbackText.isInitialized) { playbackText.text="正在为你换一批…"; showHud() }
        newRecommendSession()
        val mode=recommendMode; val tags=recommendTags; val startPage=recommendStartPage()
        val hidden=if(library.isLoaded) library.hidden() else emptySet()
        feedRefreshJob=io.submit {
            val candidates=runCatching { recommendPool(1,mode,tags,startPage,hidden) }.getOrNull()
            main.post {
                feedRefreshJob=null
                if(!foreground || !feedMode || screen!=Screen.PLAYER) return@post
                if(candidates==null || candidates.isEmpty()) {
                    if(::playbackText.isInitialized) { playbackText.text="暂时没有更多推荐 · 可打开菜单选「换一批」再试"; showHud() }
                    return@post
                }
                mergeRecommendBatch(1,candidates)
                val next=catalog.firstOrNull() ?: return@post
                feedIndex=0; pendingFeedIndex=null; catalogFocus=next.id; feedSeriesId=next.id
                playFeedSeries(next)
            }
        }
    }
    /** 后台续一批推荐：不打断当前播放，失败就等下一次切换再试。 */
    private fun appendFeedBatch() {
        if(feedBatchJob!=null || !hasMore || recommendSession==null) return
        val batch=page+1; val session=recommendSession; val mode=recommendMode; val tags=recommendTags; val startPage=recommendStartPage()
        val hidden=if(library.isLoaded) library.hidden() else emptySet()
        feedBatchJob=io.submit {
            val result=runCatching { recommendPool(batch,mode,tags,startPage,hidden) }
            main.post {
                feedBatchJob=null
                if(isDestroyed || recommendSession!==session) return@post
                result.onSuccess { candidates ->
                    mergeRecommendBatch(batch,candidates)
                    if(screen==Screen.CATALOG && tab==TAB_RECOMMEND) showCatalog()
                    handlePendingFeed()
                }
            }
        }
    }
    private fun playbackHintText()=if(feedMode) "确认 暂停/播放    ↑/↓ 换一部    左右 快退/快进    菜单 打开菜单    返回 收起菜单" else "确认 暂停/播放    左右 快退/快进    ↓ 选集    ↑ 更多    返回 收起 / 退出"
    private fun showDetail(focusEpisode: Boolean) {
        invalidatePage(); releaseCatalogViews(); screen=Screen.DETAIL; val data=detail ?: return showCatalog()
        val body=base("剧集详情")
        val scroll=ScrollView(this).apply { isVerticalScrollBarEnabled=false }; val content=column(); scroll.addView(content); body.addView(scroll,lp(-1,0).apply { weight=1f })
        val hero=row(); hero.gravity=Gravity.TOP; hero.setPadding(0,dp(12),0,dp(12))
        val image=ImageView(this).apply { scaleType=ImageView.ScaleType.FIT_CENTER }; hero.addView(image,lp(dp(123),dp(174)).apply { rightMargin=dp(24) }); loadCover(image,data.series.cover)
        val info=column(); hero.addView(info,lp(0,-2).apply { weight=1f })
        info.addView(text(data.series.title,26f).apply { setTypeface(null,Typeface.BOLD); maxLines=2; ellipsize=TextUtils.TruncateAt.END })
        info.addView(text("${data.episodes.size} 集  ·  ${data.series.tags}",13f,muted).apply { maxLines=1; setPadding(0,dp(8),0,dp(8)) })
        val description=data.series.description.ifBlank { "选择剧集开始观看" }
        val synopsis=text(description,15f,muted).apply { maxLines=if(synopsisExpanded) 20 else 2; ellipsize=TextUtils.TruncateAt.END }
        info.addView(synopsis)
        val actions=row(); actions.setPadding(0,dp(12),0,0); info.addView(actions)
        val progress=library.progress(data.series.id)
        val resumeIndex=progress?.let { data.episodes.indexOf(it.episodeId).takeIf { n -> n>=0 } } ?: 0
        val resumePosition=progress?.takeIf { !it.completed && resumeIndex==it.episodeIndex }?.position ?: 0
        val watched=library.watched(data.series.id)
        val play=addButton(actions,if(watched) "重新观看" else if(progress!=null && !progress.completed) "继续第 ${resumeIndex+1} 集" else "开始观看") { if(watched) playEpisode(0,0) else continueLatest(data) }
        lateinit var favorite: TextView
        favorite=addButton(actions,if(library.favorite(data.series.id)) "已收藏" else "收藏") {
            favoriteMonitor.stop()
            val saved=library.toggle(data.series)
            if(saved) library.observeFavorite(data.series.id,data.episodes.size,System.currentTimeMillis(),acknowledge=true)
            favorite.text=if(saved) "已收藏" else "收藏"
        }
        lateinit var synopsisButton: TextView
        synopsisButton=addButton(actions,if(synopsisExpanded) "收起简介" else "完整简介") {
            synopsisExpanded=!synopsisExpanded
            synopsis.maxLines=if(synopsisExpanded) 20 else 2
            synopsisButton.text=if(synopsisExpanded) "收起简介" else "完整简介"
            synopsisButton.post { synopsisButton.requestRectangleOnScreen(android.graphics.Rect(0,0,synopsisButton.width,synopsisButton.height),false) }
        }
        lateinit var authorButton: TextView
        authorButton=addButton(actions,"作者页") { detail?.people?.takeIf { it.isNotEmpty() }?.let { choosePeople(it,authorButton) } }
        authorButton.visibility=if(data.people.isEmpty()) View.GONE else View.VISIBLE
        authorButton.contentDescription="进入作者页，查看同一作者的作品列表"
        if(data.series.id in reservedIds) {
            addButton(actions,"取消预约") { toggleReservation(data.series,remoteReservations[data.series.id]) }
        }
        content.addView(hero)
        val groupRow=row(); groupRow.setPadding(0,dp(3),0,dp(5))
        val maxGroup=data.episodes.lastIndex/20; group=group.coerceIn(0,maxGroup)
        val previous=addButton(groupRow,"‹ 上一组") { if(group>0) { group--; showDetail(true) } }.apply { isEnabled=group>0; isFocusable=group>0; alpha=if(group>0) 1f else .4f }
        groupRow.addView(text("第 ${group*20+1}—${minOf((group+1)*20,data.episodes.size)} 集",16f).apply { gravity=Gravity.CENTER },lp(dp(190),dp(42)))
        addButton(groupRow,"下一组 ›") { if(group<maxGroup) { group++; showDetail(true) } }.apply { isEnabled=group<maxGroup; isFocusable=group<maxGroup; alpha=if(group<maxGroup) 1f else .4f }
        lateinit var jump: TextView
        jump=addButton(groupRow,"跳转集数") { tvTools.episodePicker(data.episodes.size,episodeIndex,jump) { target -> episodeIndex=target; group=target/20; showDetail(true) } }
        content.addView(groupRow)
        val episodes=mutableListOf<View>()
        (group*20 until minOf((group+1)*20,data.episodes.size)).toList().chunked(10).forEach { numbers ->
            val line=row()
            numbers.forEach { index -> val item=button("${index+1}",index==episodeIndex) { playEpisode(index,if(progress?.episodeId==data.episodes[index] && !progress.completed) progress.position else 0) }; item.contentDescription="第 ${index+1} 集"; line.addView(item,lp(0,dp(42)).apply { weight=1f; rightMargin=dp(6) }); episodes+=item }
            repeat(10-numbers.size) { line.addView(Space(this),lp(0,dp(42)).apply { weight=1f; rightMargin=dp(6) }) }
            content.addView(line,lp(-1,dp(49)))
        }
        val relatedContent=column(); content.addView(relatedContent)
        fun renderRelated(related: List<Series>) {
        relatedContent.removeAllViews()
        val relatedCards=mutableListOf<View>()
        if(related.isNotEmpty()) {
            relatedContent.addView(text("相关作品",18f).apply { setTypeface(null,Typeface.BOLD); setPadding(0,dp(18),0,dp(8)) })
            val relatedScroll=HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled=false
                isFocusable=false
                descendantFocusability=ViewGroup.FOCUS_AFTER_DESCENDANTS
            }
            val relatedRow=row(); relatedRow.setPadding(0,dp(2),0,dp(2)); relatedScroll.addView(relatedRow,lp(-2,-2))
            val visible=5; val gap=dp(14)
            val width=((resources.displayMetrics.widthPixels*.9f-gap*(visible-1))/visible).toInt()
            related.forEachIndexed { index,item ->
                val card=column(); card.setPadding(dp(4),dp(4),dp(4),dp(9)); focusStyle(card)
                card.contentDescription=item.title+(if(item.badge.isNotBlank()) " ${item.badge}" else "")+(if(item.preview) " 预告" else ""); card.tag=item.id
                val artwork=FrameLayout(this); card.addView(artwork,lp(-1,((width-dp(8))/TvStyle.POSTER_ASPECT).toInt()))
                val image=ImageView(this).apply { scaleType=ImageView.ScaleType.FIT_CENTER; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
                artwork.addView(image,FrameLayout.LayoutParams(-1,-1)); loadCover(image,item.cover)
                if(item.badge.isNotBlank()) {
                    val badge=text(item.badge,12f,white).apply { gravity=Gravity.CENTER; setPadding(dp(8),dp(3),dp(8),dp(3)); background=rounded(Color.argb(178,0,0,0)) }
                    artwork.addView(badge,FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.END).apply { topMargin=dp(6); rightMargin=dp(6) })
                }
                if(item.preview) {
                    val label=text("预告",12f,white).apply { gravity=Gravity.CENTER; setPadding(dp(8),dp(3),dp(8),dp(3)); background=rounded(Color.argb(178,0,0,0)) }
                    artwork.addView(label,FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM or Gravity.START).apply { leftMargin=dp(6); bottomMargin=dp(6) })
                }
                card.addView(text(item.title,14f).apply { maxLines=2; minLines=2; ellipsize=TextUtils.TruncateAt.END; setPadding(dp(3),dp(7),dp(3),0) },lp(-1,-2).apply { weight=1f })
                card.setOnClickListener {
                    if(item.preview) openPreview(item,remoteReservations[item.id])
                    else openDetail(item,false,true)
                }
                card.setOnFocusChangeListener { _,focused -> card.background=TvStyle.card(this,focused); if(focused) loadCover(image,item.cover) }
                relatedRow.addView(card,lp(width,-1).apply { if(index<related.lastIndex) rightMargin=gap }); relatedCards+=card
            }
            relatedContent.addView(relatedScroll,lp(-1,-2))
        }
        episodes.forEachIndexed { i,v -> v.nextFocusLeftId=if(i%10==0) v.id else episodes[i-1].id; v.nextFocusRightId=if(i%10==9 || i==episodes.lastIndex) v.id else episodes[i+1].id; if(i>=10) v.nextFocusUpId=episodes[i-10].id; v.nextFocusDownId=if(i+10<episodes.size) episodes[i+10].id else relatedCards.firstOrNull()?.id ?: v.id }
        relatedCards.forEachIndexed { index,card ->
            card.nextFocusLeftId=if(index==0) card.id else relatedCards[index-1].id
            card.nextFocusRightId=if(index==relatedCards.lastIndex) card.id else relatedCards[index+1].id
            card.nextFocusUpId=episodes.lastOrNull()?.id ?: play.id
            card.nextFocusDownId=card.id
        }
        }
        renderRelated(data.related)
        if(focusEpisode) episodes.getOrNull((episodeIndex-group*20).coerceIn(0,episodes.lastIndex))?.requestFocus() else play.requestFocus()
        if(!data.extrasLoaded) work({ repository.enrichDetail(data) }, { enriched ->
            detail=enriched
            authorButton.visibility=if(enriched.people.isEmpty()) View.GONE else View.VISIBLE
            renderRelated(enriched.related)
            if(enriched.extrasFailed) addButton(content,"重试附加信息") { detail=enriched.copy(extrasLoaded=false); showDetail(false) }
        }, { problem ->
            android.util.Log.w("HongguoTV","Detail extras failed: "+problem.javaClass.simpleName)
            addButton(content,"重试附加信息") { showDetail(false) }
        })
    }
    /** 待上线内容没有分集，详情页只展示预约信息与预约/取消按钮。 */
    private fun openPreview(series: Series,reservation: AccountReservation?=null) {
        if(reservation!=null && series.id in reservedIds) remoteReservations[series.id]=reservation
        previewSeries=series
        invalidatePage(); releaseCatalogViews(); screen=Screen.PREVIEW
        val body=base("预约详情")
        val scroll=ScrollView(this).apply { isVerticalScrollBarEnabled=false }
        val content=column(); scroll.addView(content); body.addView(scroll,lp(-1,0).apply { weight=1f })
        val hero=row().apply { gravity=Gravity.TOP; setPadding(0,dp(12),0,dp(12)) }
        val image=ImageView(this).apply { scaleType=ImageView.ScaleType.FIT_CENTER }
        hero.addView(image,lp(dp(160),dp(220)).apply { rightMargin=dp(24) }); loadCover(image,series.cover)
        val info=column(); hero.addView(info,lp(0,-2).apply { weight=1f })
        info.addView(text(series.title,27f).apply { setTypeface(null,Typeface.BOLD); maxLines=2; ellipsize=TextUtils.TruncateAt.END })
        val meta=listOfNotNull(
            series.badge.takeIf { it.isNotBlank() },
            series.tags.takeIf { it.isNotBlank() }
        ).joinToString("  ·  ")
        if(meta.isNotBlank()) info.addView(text(meta,14f,accent).apply { setPadding(0,dp(10),0,dp(8)) })
        info.addView(text(series.description.ifBlank { "这部内容尚未上线，预约后上线时会提醒你。" },15f,muted).apply {
            maxLines=6; ellipsize=TextUtils.TruncateAt.END
        })
        val status=listOfNotNull(
            series.badge.takeIf { it.isNotBlank() },
            if(series.id in reservedIds) "已预约，上线后会提醒你" else "尚未上线，可以先预约"
        ).joinToString("  ·  ")
        info.addView(text(status,14f,if(series.id in reservedIds) accent else muted).apply { setPadding(0,dp(12),0,0) })
        content.addView(hero)
        val actions=row().apply { setPadding(0,dp(14),0,dp(8)) }
        lateinit var first: TextView
        if(repository.currentSession()==null) {
            first=addButton(actions,"登录后可预约") { switchTab(TAB_ACCOUNT) }
            actions.addView(text("登录后可在账号页查看预约记录",13f,muted).apply { setPadding(dp(10),0,0,0) })
        } else {
            first=addButton(actions,if(series.id in reservedIds) "取消预约" else "预约") {
                toggleReservation(series,remoteReservations[series.id],first)
            }
            if(reservation?.expectedText?.isNotBlank()==true) {
                actions.addView(text(reservation.expectedText,13f,muted).apply { setPadding(dp(12),0,0,0) })
            }
        }
        val home=addButton(actions,"去首页看看") { switchTab(TAB_HOME) }
        content.addView(actions)
        val heading=body.getChildAt(0) as? LinearLayout
        val back=heading?.getChildAt(0) as? View
        back?.nextFocusDownId=first.id; first.nextFocusUpId=back?.id ?: View.NO_ID
        first.nextFocusRightId=home.id; home.nextFocusLeftId=first.id; home.nextFocusUpId=first.nextFocusUpId
        first.requestFocus()
    }
    /** 详情页的「作者页」入口：一个人直接进入，多个人先选择。 */
    private fun choosePeople(people: List<Person>,anchor: View) {
        if(people.size==1) { openCharacter(people.first()); return }
        val labels=people.map { person -> person.role.takeIf { it.isNotBlank() }?.let { "${person.name} · $it" } ?: person.name }
        tvTools.choose("选择要查看的作者/主创",labels,anchor,{ index -> people.getOrNull(index)?.let(::openCharacter) })
    }
    /** 作者/主创页：同一演员或主创的作品列表。 */
    private fun openCharacter(person: Person) {
        invalidatePage(); releaseCatalogViews()
        screen=Screen.CHARACTER; character=null; characterPerson=person; characterFocus=""
        characterReturnDetail=detail
        val body=base("作者页"); message(body,"正在加载 ${person.name} 的作品…")
        work({ if(person.creator) repository.creatorPage(person) else repository.character(person.id) },
            { result -> character=result; showCharacter() },
            { problem -> error(body,problem) { openCharacter(person) } })
    }
    private fun showCharacter() {
        invalidatePage(); releaseCatalogViews(); screen=Screen.CHARACTER
        val data=character
        if(data==null) { showDetail(false); return }
        val body=base()
        val heading=row(); val back=addButton(heading,"‹ 返回") { goBack() }
        heading.addView(text("作者页",22f).apply { setTypeface(null,Typeface.BOLD) },lp(0,dp(44)).apply { weight=1f })
        body.addView(heading)
        val scroll=ScrollView(this).apply { isVerticalScrollBarEnabled=false }; val content=column(); scroll.addView(content); body.addView(scroll,lp(-1,0).apply { weight=1f })
        val header=row(); header.gravity=Gravity.TOP; header.setPadding(0,dp(8),0,dp(12))
        val avatar=ImageView(this).apply { scaleType=ImageView.ScaleType.FIT_CENTER; background=rounded(surface) }
        header.addView(avatar,lp(dp(116),dp(116)).apply { rightMargin=dp(20) }); if(data.avatar.isNotBlank()) loadCover(avatar,data.avatar)
        val info=column(); header.addView(info,lp(0,-2).apply { weight=1f })
        info.addView(text(data.name,26f).apply { setTypeface(null,Typeface.BOLD); maxLines=1; ellipsize=TextUtils.TruncateAt.END })
        val roles=data.roles.ifEmpty { listOfNotNull(characterPerson?.role?.takeIf { it.isNotBlank() }) }
        val meta=(if(roles.isEmpty()) emptyList() else listOf(roles.joinToString(" · ")))+listOfNotNull(data.fans.takeIf { it>0 }?.let { "${it} 粉丝" })
        if(meta.isNotEmpty()) info.addView(text(meta.joinToString("  ·  "),13f,muted).apply { setPadding(0,dp(6),0,dp(6)) })
        if(data.description.isNotBlank()) info.addView(text(data.description,15f,muted).apply { maxLines=4; ellipsize=TextUtils.TruncateAt.END })
        info.addView(text("作品 ${data.works.size}",18f).apply { setTypeface(null,Typeface.BOLD); setPadding(0,dp(10),0,0) })
        content.addView(header)
        val list=column(); content.addView(list)
        val cards=mutableListOf<View>()
        val count=6; val gap=dp(14); val width=((resources.displayMetrics.widthPixels*.9f-gap*(count-1))/count).toInt()
        data.works.chunked(count).forEach { items ->
            val line=row(); line.gravity=Gravity.TOP
            items.forEachIndexed { columnIndex,item ->
                val card=column(); card.setPadding(dp(4),dp(4),dp(4),dp(9)); focusStyle(card); card.contentDescription=item.title; card.tag=item.id
                val artwork=FrameLayout(this); card.addView(artwork,lp(-1,((width-dp(8))/TvStyle.POSTER_ASPECT).toInt()))
                val image=ImageView(this).apply { scaleType=ImageView.ScaleType.FIT_CENTER; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
                artwork.addView(image,FrameLayout.LayoutParams(-1,-1)); loadCover(image,item.cover)
                card.addView(text(item.title,14f).apply { maxLines=2; minLines=2; ellipsize=TextUtils.TruncateAt.END; setPadding(dp(3),dp(7),dp(3),0) },lp(-1,-2).apply { weight=1f })
                card.addView(text(item.badge,12f,muted).apply { maxLines=1; minLines=1; minHeight=dp(19); ellipsize=TextUtils.TruncateAt.END; setPadding(dp(3),0,0,0) },lp(-1,-2))
                card.setOnClickListener { characterFocus=item.id; openDetail(item,false,true,true) }
                card.setOnFocusChangeListener { _,focused -> card.background=TvStyle.card(this,focused); if(focused) { characterFocus=item.id; loadCover(image,item.cover) } }
                line.addView(card,lp(width,-1).apply { if(columnIndex<count-1) rightMargin=gap }); cards+=card
            }
            repeat(count-items.size) { line.addView(Space(this),lp(width,-1).apply { rightMargin=gap }) }
            list.addView(line,lp(-1,-2).apply { bottomMargin=dp(10) })
        }
        cards.forEachIndexed { index,card ->
            card.nextFocusLeftId=if(index%count==0) card.id else cards[index-1].id
            card.nextFocusRightId=if(index%count==count-1 || index==cards.lastIndex) card.id else cards[index+1].id
            card.nextFocusUpId=if(index<count) back.id else cards[index-count].id
            card.nextFocusDownId=if(index+count<cards.size) cards[index+count].id else card.id
        }
        val target=cards.firstOrNull { it.tag==characterFocus } ?: cards.firstOrNull()
        back.nextFocusDownId=target?.id ?: back.id
        if(data.works.isEmpty()) {
            message(list,"这个作者暂时没有作品。")
            val returnButton=addButton(list,"返回详情") { goBack() }
            back.nextFocusDownId=returnButton.id; returnButton.nextFocusUpId=back.id
            returnButton.requestFocus()
        } else {
            target?.requestFocus()
        }
    }
    private fun playEpisode(index: Int,position: Long=0,autoplay: Boolean=true,recovering: Boolean=false,saveCurrent: Boolean=true,ready: FeedReady?=null) {
        updater.interrupt()
        val data=detail ?: return
        bufferingWatchdog.reset()
        val playbackStarted=android.os.SystemClock.elapsedRealtime()
        val reuse=screen==Screen.PLAYER && player!=null && playerView!=null && !recovering && !playError
        library.setWatched(data.series.id,false)
        val selectedIndex=index.coerceIn(0,data.episodes.lastIndex)
        val qualityKey="${data.series.id}:$selectedIndex"
        if(qualityCapKey!=qualityKey) { qualityCap=null; autoFallbackTried=false }
        val maxQuality=qualityCap ?: library.maxQuality
        val prefetched=ready?.remote ?: if(position==0L && !recovering) preparedNext.take("${data.episodes[selectedIndex]}:$maxQuality") else null
        val claimedHttp=when { ready!=null -> ready.http; prefetched!=null -> prefetchHttp.also { prefetchHttp=null }; else -> null }
        if(saveCurrent) saveProgress(); library.flush(); invalidatePage(); favoriteMonitor.stop(); releaseCatalogViews()
        if(reuse) {
            cancelRecovery(); cancelPrefetch(); tvTools.close()
            episodePanel?.dismiss(); episodePanel=null; speedDialog?.dismiss(); speedDialog=null
            playerListener?.let { player?.removeListener(it) }; playerListener=null
            player?.stop(); player?.clearMediaItems()
            video?.close(); video=null; closeTransport(playbackHttp); playbackHttp=null
            main.removeCallbacks(hideHud); main.removeCallbacks(seekRunnable); pendingSeek=null
        } else releasePlayer()
        artwork.cancelPage(clearMemory=true)
        backdropCover="" // cancelPage 会把封面底一起解绑，切集/切片后要重新加载
        playbackHttp=claimedHttp; video=prefetched
        var firstReady=true; var firstFrame=true
        if(!recovering) recovery.reset()
        autoRecovery=false; readySince=0; startPosition=position.coerceAtLeast(0); requestedAutoplay=autoplay
        if(autoplay) sleepStopped=false
        screen=Screen.PLAYER; episodeIndex=selectedIndex; group=episodeIndex/20
        panel=false; playError=false; playbackReady=false; quality=""; pausedForLifecycle=!autoplay
        player?.playWhenReady=requestedAutoplay
        prefetchAttempts=0; prefetchAttemptAt=0
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if(!reuse) {
            root.removeAllViews(); root.setBackgroundColor(Color.BLACK)
            backdropCover=""
            val cover=coverBackdrop()
            backdrop=cover; root.addView(cover,FrameLayout.LayoutParams(-1,-1))
            val view=PlayerView(this).apply {
                useController=false; resizeMode=frameResizeMode(); isFocusable=false
                setKeepContentOnPlayerReset(true); setShutterBackgroundColor(Color.BLACK); setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            }
            playerView=view; root.addView(view,FrameLayout.LayoutParams(-1,-1))
            val layer=DanmakuView(this).apply {
                positionProvider={ player?.currentPosition ?: 0L }
                playingProvider={ player?.isPlaying==true }
            }
            applyDanmakuStyle(layer)
            danmakuView=layer; root.addView(layer,FrameLayout.LayoutParams(-1,-1))
            if(feedMode) {
                // 推荐连播的顶部浮层：导航与剧集信息，跟底部 hud 一起出现与收起。
                feedTop=column().apply { background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(Color.argb(225,11,15,21),Color.argb(150,11,15,21),Color.TRANSPARENT)) }
                feedTopReady=true; root.addView(feedTop,FrameLayout.LayoutParams(-1,-2,Gravity.TOP))
            } else feedTopReady=false
            hud=column().apply { setPadding(dp(widthDp()*.05f),dp(20),dp(widthDp()*.05f),(resources.displayMetrics.heightPixels*.05f).toInt()); background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(Color.TRANSPARENT,Color.argb(240,11,15,21))) }
            playbackTitle=text("",23f).apply { maxLines=1; ellipsize=TextUtils.TruncateAt.END; setTypeface(null,Typeface.BOLD) }; hud.addView(playbackTitle)
            playbackText=text("",15f,muted).apply { setPadding(0,dp(10),0,dp(9)) }; hud.addView(playbackText)
            progressBar=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply { max=1000; progressTintList=android.content.res.ColorStateList.valueOf(accent); progressBackgroundTintList=android.content.res.ColorStateList.valueOf(surface) }; hud.addView(progressBar,lp(-1,dp(4)))
            playbackHint=text(playbackHintText(),13f,muted); hud.addView(playbackHint!!,lp(-1,-2).apply { topMargin=dp(12) })
            controls=column().apply { setPadding(0,dp(12),0,0); visibility=View.GONE }; hud.addView(controls)
            root.addView(ScrollView(this).apply { isVerticalScrollBarEnabled=false; addView(hud) },FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
        }
        playbackTitle.text="${data.series.title}  ·  第 ${episodeIndex+1} 集"+(if(feedMode) "  ·  推荐连播" else "")
        playbackTitle.visibility=if(feedMode) View.GONE else View.VISIBLE
        playbackHint?.text=playbackHintText()
        playbackText.text="正在获取播放地址…"; progressBar.progress=0; progressBar.visibility=View.VISIBLE; hidePlaybackOverlay()
        if(feedMode) { renderFeedChrome(); showHud() } else { controls.removeAllViews(); controls.visibility=View.GONE; transportPlay=null; sleepButton=null }
        syncMediaSession()
        val requestedId=data.episodes[episodeIndex]; val ticket=generation
        startDanmaku(requestedId,data.series.id)
        work({ prefetched ?: RemoteVideo(repository.http,repository.stream(requestedId,maxQuality)).prepare() }, { remote ->
            video=remote; quality=remote.info.quality
            feedQualityButton?.text="清晰度 ${currentQualityLabel()}"
            val p=player ?: run {
                // Time thresholds must win over the byte cap, otherwise high-bitrate rebuffering can wait for the cap.
                val load=DefaultLoadControl.Builder().setBufferDurationsMs(15000,30000,1000,2000).setTargetBufferBytes(12*1024*1024).setPrioritizeTimeOverSizeThresholds(true).build()
                val renderers=androidx.media3.exoplayer.DefaultRenderersFactory(this).setEnableDecoderFallback(true)
                ExoPlayer.Builder(this,renderers).setLoadControl(load).build().also {
                    player=it; playerView?.player=it
                    it.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),true)
                    it.setHandleAudioBecomingNoisy(true)
                }
            }
            p.setPlaybackSpeed(library.playbackSpeed)
            val listener=object: Player.Listener {
                private fun active()=player===p && generation==ticket && p.currentMediaItem?.mediaId==requestedId
                override fun onPlaybackStateChanged(state: Int) {
                    if(!active()) return
                    if(state!=Player.STATE_BUFFERING) bufferingWatchdog.reset()
                    if(state==Player.STATE_READY) {
                        if(!p.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO)) {
                            playerFailure(PlaybackException("资源没有可解码的视频轨道",null,PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))
                            return
                        }
                        playbackReady=true
                        main.removeCallbacks(danmakuTick); main.post(danmakuTick)
                        if(firstReady) { firstReady=false; readySince=android.os.SystemClock.elapsedRealtime(); android.util.Log.i("HongguoTV","Playback ready episode=${episodeIndex+1} preloaded=${prefetched!=null} reused=$reuse elapsedMs=${readySince-playbackStarted}") }
                        if(hud.visibility==View.VISIBLE) updatePlaybackText()
                    }
                    syncMediaSession()
                    if(state==Player.STATE_ENDED) {
                        saveProgress(true)
                        if(sleepTimer.episodeEnded()) stopForSleep()
                        else if(foreground && requestedAutoplay && !pausedForLifecycle && library.autoNext && episodeIndex<data.episodes.lastIndex) playEpisode(episodeIndex+1)
                        else if(foreground && requestedAutoplay && !pausedForLifecycle && library.autoNext && feedMode) switchFeed(1)
                        else { requestedAutoplay=false; p.pause(); playbackText.text="本集已结束"; showPanel() }
                    }
                }
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if(!active() || videoSize.height<=0) return
                    val ratio=videoSize.width*videoSize.pixelWidthHeightRatio/videoSize.height
                    val aspect=if(videoSize.unappliedRotationDegrees%180==0) ratio else 1f/ratio
                    android.util.Log.i("HongguoTV","Video size ${videoSize.width}x${videoSize.height} aspect=$aspect")
                    applyVideoFrame(aspect)
                }
                override fun onRenderedFirstFrame() {
                    if(active() && firstFrame) {
                        firstFrame=false
                        android.util.Log.i("HongguoTV","Playback first frame episode=${episodeIndex+1} preloaded=${prefetched!=null} reused=$reuse elapsedMs=${android.os.SystemClock.elapsedRealtime()-playbackStarted}")
                    }
                }
                override fun onIsPlayingChanged(playing: Boolean) { if(active()) { if(hud.visibility==View.VISIBLE) showHud(); syncMediaSession() } }
                override fun onPlaybackParametersChanged(parameters: PlaybackParameters) { if(active()) syncMediaSession() }
                override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo,newPosition: Player.PositionInfo,reason: Int) { if(active()) { if(reason==Player.DISCONTINUITY_REASON_SEEK) resetDanmaku(); syncMediaSession() } }
                override fun onPlayerError(error: PlaybackException) { if(active()) playerFailure(error) }
            }
            playerListener=listener; p.addListener(listener)
            val source=ProgressiveMediaSource.Factory { VideoDataSource(remote) }.createMediaSource(MediaItem.Builder().setMediaId(requestedId).setUri("hongguotv://episode/$requestedId").setMimeType(MimeTypes.VIDEO_MP4).build())
            p.setMediaSource(source); p.seekTo(position.coerceAtLeast(0)); p.prepare(); p.playWhenReady=foreground && requestedAutoplay && !pausedForLifecycle && episodePanel==null
        }, { problem -> playerFailure(problem) })
    }
    private fun closeTransport(http: okhttp3.OkHttpClient?) {
        if(http==null) return
        // Closing an idle TLS connection can write close_notify; keep it off the UI thread.
        networkCleanup.execute { http.dispatcher.cancelAll(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown() }
    }
    private fun cancelPrefetch() {
        prefetchScope?.cancel(); prefetchScope=null
        preparedNext.clear(); prefetchJob?.cancel(true); prefetchJob=null
        closeTransport(prefetchHttp); prefetchHttp=null; prefetchedFor=""
        cancelFeedPrefetch()
    }
    /** 停下正在准备的相邻剧集；已经准备好的留着，换回来还能直接用。 */
    private fun cancelFeedPrefetch() {
        feedPrefetchEpoch++; feedPrefetchJob?.cancel(true); feedPrefetchJob=null; feedPrefetchMark=""
    }
    /** 换一批或重新开一次推荐会话时，丢掉上一批的相邻预取。 */
    private fun clearFeedPrefetch() { cancelFeedPrefetch(); preparedSeries.clear() }
    /**
     * 推荐连播的预缓存：播放稳定后把前后两部的详情、续播的那一集和它的首段视频取好，
     * ↑/↓ 换部时省掉详情、播放接口和首屏缓冲，切换几乎没有等待。
     * 每部只在开始播和快播完时各准备一次，长时间挂着也不会反复消耗流量。
     */
    private fun maybePrefetchFeed() {
        if(!feedMode || screen!=Screen.PLAYER || playError || !playbackReady || !foreground) return
        val p=player ?: return
        if(!p.isPlaying || connectivity.isActiveNetworkMetered || !networkAvailable()) return
        if(readySince==0L || android.os.SystemClock.elapsedRealtime()-readySince<3000) return
        if(p.bufferedPosition-p.currentPosition<5000) return
        val stage=if(PrefetchPolicy.nearEnd(p.currentPosition,p.duration,p.playbackParameters.speed)) "end" else "start"
        val mark="${detail?.series?.id}:$feedIndex:$stage"
        if(mark==feedPrefetchMark || feedPrefetchJob?.isDone==false) return
        val neighbors=listOf(feedIndex+1,feedIndex-1).filter { it in catalog.indices }.map { catalog[it] }
        if(neighbors.isEmpty()) return
        feedPrefetchMark=mark
        val epoch=++feedPrefetchEpoch
        val quality=library.maxQuality
        val progress=neighbors.associate { it.id to library.progress(it.id) }
        android.util.Log.i("HongguoTV","Feed prefetch start neighbors=${neighbors.size} quality=$quality stage=$stage")
        feedPrefetchJob=feedPrefetchWorker.submit { prefetchFeedNeighbors(epoch,neighbors,quality,progress) }
    }
    /** 按顺序准备相邻两部；编号变了（已换部或退出推荐）就立刻停下并放掉连接。 */
    private fun prefetchFeedNeighbors(epoch: Int,neighbors: List<Series>,quality: Int,progress: Map<String,BackupProgress?>) {
        for(series in neighbors) {
            if(epoch!=feedPrefetchEpoch) return
            if(preparedSeries.fresh(series.id)) continue
            val http=okhttp3.OkHttpClient.Builder().connectTimeout(8,java.util.concurrent.TimeUnit.SECONDS).readTimeout(12,java.util.concurrent.TimeUnit.SECONDS).callTimeout(20,java.util.concurrent.TimeUnit.SECONDS).build()
            try {
                val source=ContentRepository(http)
                val data=source.detail(series.id)
                if(epoch!=feedPrefetchEpoch) { closeTransport(http); return }
                val saved=progress[series.id]
                val target=ResumePlayback.target(data.episodes,saved?.episodeId,saved?.position ?: 0,saved?.completed==true)
                val remote=RemoteVideo(http,source.stream(data.episodes[target.index],quality)).also { it.warm() }
                if(epoch!=feedPrefetchEpoch) { remote.close(); closeTransport(http); return }
                preparedSeries.put(series.id,FeedReady(data,target.index,quality,remote,http))
                android.util.Log.i("HongguoTV","Feed neighbor prepared series=${series.id} episode=${target.index+1} quality=$quality")
            } catch(problem: Exception) {
                android.util.Log.w("HongguoTV","Feed neighbor failed series=${series.id}: ${problem.message}")
                closeTransport(http)
            }
        }
    }
    private fun maybePrefetch() {
        val p=player ?: return; val data=detail ?: return
        if(!foreground || !p.isPlaying || !library.autoNext || playError || sleepStopped || episodeIndex>=data.episodes.lastIndex) return
        if(!PrefetchPolicy.nearEnd(p.currentPosition,p.duration,p.playbackParameters.speed)) return
        val id=data.episodes[episodeIndex+1]; val quality=library.maxQuality; val key="$id:$quality"
        if(preparedNext.isFresh(key) || prefetchJob?.isDone==false) return
        val now=android.os.SystemClock.elapsedRealtime()
        if(readySince==0L || now-readySince<3000 || p.bufferedPosition-p.currentPosition<5000) return
        if(prefetchAttempts>=3 || (prefetchAttempts>0 && now-prefetchAttemptAt<15_000) || connectivity.isActiveNetworkMetered || !networkAvailable()) return
        cancelPrefetch(); prefetchedFor=key; prefetchAttemptAt=now; prefetchAttempts++
        val ticket=preparedNext.begin(key)
        val http=okhttp3.OkHttpClient.Builder().connectTimeout(8,java.util.concurrent.TimeUnit.SECONDS).readTimeout(15,java.util.concurrent.TimeUnit.SECONDS).callTimeout(20,java.util.concurrent.TimeUnit.SECONDS).build()
        val source=ContentRepository(http); prefetchHttp=http
        val scope=RequestScope(); prefetchScope=scope
        prefetchJob=prefetchWorker.submit {
            var remote: RemoteVideo?=null
            try {
                val prepared=scope.run {
                    RemoteVideo(http,source.stream(id,quality)).also { remote=it; it.warm() }
                }
                if(preparedNext.complete(ticket,key,prepared)) android.util.Log.i("HongguoTV","Next episode prepared near end")
            } catch(_: Exception) { remote?.close() }
        }
    }
    private fun networkAvailable(): Boolean = connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)==true
    private fun currentPosition(): Long = pendingSeek ?: player?.currentPosition?.takeIf { playbackReady } ?: startPosition
    private fun cancelRecovery() { main.removeCallbacks(retryRunnable); retryPending=false; autoRecovery=false }
    private fun scheduleRecovery() {
        if(!foreground || !playError || !autoRecovery || retryPending) return
        if(!recovery.remaining) { autoRecovery=false; playbackText.text="已自动重试 3 次，仍无法播放。可手动重试或切换清晰度。"; return }
        if(!networkAvailable()) { playbackText.text="网络已断开，联网后自动续播 · 第 ${episodeIndex+1} 集 ${formatTime(retryPosition)}"; return }
        val delay=recovery.delayMillis ?: return
        playbackText.text="${if(retryAutoplay) "准备恢复播放" else "准备恢复暂停位置"} · ${delay/1000f} 秒后重试 ${recovery.attempts+1}/3 · ${formatTime(retryPosition)}"
        retryPending=true; main.postDelayed(retryRunnable,delay)
    }
    private fun playerFailure(problem: Throwable) {
        retryPosition=currentPosition().coerceAtLeast(0); retryAutoplay=requestedAutoplay && !sleepStopped
        saveProgress(); cancelRecovery(); cancelPrefetch()
        val decoding=problem is PlaybackException && problem.errorCode in 3000..4999
        // 解码不了 1080P 时先自动降到 720P 试一次；只影响当前这一集，不改全局默认清晰度。
        val currentQuality=qualityCap ?: library.maxQuality
        if(decoding && !autoFallbackTried && currentQuality>720) {
            autoFallbackTried=true; qualityCap=720; qualityCapKey="${detail?.series?.id.orEmpty()}:$episodeIndex"
            Toast.makeText(this,"当前清晰度无法解码，已自动改用 720P 重试",Toast.LENGTH_SHORT).show()
            playEpisode(episodeIndex,retryPosition,retryAutoplay)
            return
        }
        playError=true; player?.pause(); progressBar.visibility=View.GONE; hud.visibility=View.VISIBLE; controls.removeAllViews(); controls.visibility=View.VISIBLE; panel=true
        val retryable=if(problem is PlaybackException) problem.errorCode in 2000..2999 || problem.errorCode==PlaybackException.ERROR_CODE_TIMEOUT else problem is java.io.IOException
        playbackText.text=if(decoding) "电视无法解码当前视频，可尝试更低清晰度。" else "播放失败，可重试或切换清晰度。"
        val actions=row(); controls.addView(actions)
        addButton(actions,"立即重试") { playEpisode(episodeIndex,retryPosition,retryAutoplay) }.requestFocus()
        addButton(actions,"改用 720P") {
            qualityCap=720; qualityCapKey="${detail?.series?.id.orEmpty()}:$episodeIndex"; autoFallbackTried=true
            Toast.makeText(this,"本次播放改用 720P，默认清晰度不变",Toast.LENGTH_SHORT).show()
            playEpisode(episodeIndex,retryPosition,retryAutoplay)
        }
        if(feedMode) addButton(actions,"换下一部") { panel=false; switchFeed(1) }
        addButton(actions,if(feedMode) "返回推荐" else "返回选集") { if(feedMode) { directPlayback=true; returnToDetail() } else returnToDetail(true) }
        if(retryable && !sleepStopped) {
            addButton(controls,"停止自动重试") { cancelRecovery(); playbackText.text="自动重试已停止，观看位置已保留。" }
            autoRecovery=true; scheduleRecovery()
        }
        android.util.Log.w("HongguoTV","Playback failure: ${problem.javaClass.simpleName}"+(if(problem is PlaybackException) " code=${problem.errorCodeName}" else ""))
    }
    private fun hidePlaybackOverlay() {
        main.removeCallbacks(hideHud)
        panel=false; controls.clearFocus()
        if(!feedMode) controls.visibility=View.GONE
        hud.visibility=View.GONE; if(feedTopReady) feedTop.visibility=View.GONE
    }
    private fun showHud() {
        if(screen!=Screen.PLAYER) return
        hud.visibility=View.VISIBLE; if(feedTopReady) feedTop.visibility=View.VISIBLE
        if(feedMode && !playError) controls.visibility=View.VISIBLE
        updatePlaybackText(); main.removeCallbacks(hideHud)
        if(!panel && player?.isPlaying==true) main.postDelayed(hideHud,4500)
    }
    /**
     * 推荐连播的浮层内容：顶部导航与剧集信息，底部操作行。每次换集/换部都重建。
     * 浮层与 hud 同步出现；菜单打开时（panel=true）焦点落在导航上。
     */
    private fun renderFeedChrome() {
        val data=detail ?: return
        if(!feedTopReady) return
        feedTop.removeAllViews(); nav.clear(); buildNav(feedTop,false)
        val info=column().apply { setPadding(dp(widthDp()*.05f),dp(6),dp(24),dp(14)) }
        info.addView(text("第 ${episodeIndex+1} 集",13f,bg).apply { setPadding(dp(11),dp(5),dp(11),dp(5)); background=rounded(accent) },lp(-2,-2))
        info.addView(text(data.series.title,25f).apply { setTypeface(null,Typeface.BOLD); maxLines=1; ellipsize=TextUtils.TruncateAt.END; setPadding(0,dp(9),0,0) })
        val meta=mutableListOf("第 ${episodeIndex+1} 集","共 ${data.episodes.size} 集")
        if(data.series.badge.isNotBlank()) meta+=data.series.badge
        info.addView(text(meta.joinToString("  ·  "),13f,muted).apply { setPadding(0,dp(7),0,0) })
        val tags=feedTagText(data.series.tags)
        if(tags.isNotBlank()) info.addView(text(tags,13f,accent).apply { maxLines=1; ellipsize=TextUtils.TruncateAt.END; setPadding(0,dp(7),0,0) })
        if(data.series.description.isNotBlank()) info.addView(text(data.series.description,14f,muted).apply { maxLines=2; ellipsize=TextUtils.TruncateAt.END; setPadding(0,dp(7),0,0) })
        feedTop.addView(info,lp(feedInfoWidth(),-2))
        buildFeedActions(data)
        if(panel) nav.getOrNull(TAB_RECOMMEND)?.requestFocus()
    }
    /** 网站给的题材用「·」分隔，连播浮层里按标签样子展示。 */
    private fun feedTagText(tags: String)=tags.split(" · ",",","，","/").map { it.trim() }.filter { it.isNotEmpty() }.take(5).joinToString("  ") { "#$it" }
    /** 推荐连播底部操作行：暂停播放 / 下一集 / 合集 / 追剧 / 倍速 / 清晰度 / 更多设置 / 换一批。 */
    private fun buildFeedActions(data: Detail) {
        controls.removeAllViews(); feedQualityButton=null
        val bar=HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false; clipToPadding=false }
        val actions=row(); bar.addView(actions); controls.addView(bar,lp(-1,-2))
        transportPlay=addButton(actions,if(player?.playWhenReady==true) "暂停播放" else "继续播放") { togglePlayback(); hidePlaybackOverlay() }
        addButton(actions,"下一集") { skipEpisode(1) }
        val shortTitle=if(data.series.title.length>8) data.series.title.take(8)+"…" else data.series.title
        lateinit var collection: TextView
        collection=addButton(actions,"合集：$shortTitle｜全${data.episodes.size}集") { showEpisodePanel(collection) }
        lateinit var favorite: TextView
        favorite=addButton(actions,if(library.favorite(data.series.id)) "已追剧" else "追剧") {
            favoriteMonitor.stop()
            val saved=library.toggle(data.series)
            if(saved) library.observeFavorite(data.series.id,data.episodes.size,System.currentTimeMillis(),acknowledge=true)
            favorite.text=if(saved) "已追剧" else "追剧"
            Toast.makeText(this,if(saved) "已追剧，收藏里有更新会提醒" else "已取消追剧",Toast.LENGTH_SHORT).show()
        }
        danmakuButton=addButton(actions,if(danmakuEnabled) "弹幕开" else "弹幕关") { toggleDanmaku() }
        lateinit var speed: TextView
        speed=addButton(actions,"倍速 ${PlaybackSpeed.label(library.playbackSpeed)}") { showSpeedPicker(speed) }
        lateinit var qualityAction: TextView
        qualityAction=addButton(actions,"清晰度 ${currentQualityLabel()}") { showQualityPicker(qualityAction,true) }
        feedQualityButton=qualityAction
        lateinit var more: TextView
        more=addButton(actions,"更多设置") {
            tvTools.choose("播放设置",listOf("倍速 ${PlaybackSpeed.label(library.playbackSpeed)}","清晰度 ${currentQualityLabel()}","画面 ${library.frameMode.label}","定时 ${sleepTimer.label()}","弹幕设置","从头播放"),more,{ choice ->
                main.post {
                    if(screen==Screen.PLAYER && more.isAttachedToWindow) when(choice) {
                        0 -> showSpeedPicker(more)
                        1 -> showQualityPicker(more,true)
                        2 -> showFramePicker(more,true)
                        3 -> showSleepPicker(more)
                        4 -> showDanmakuSettings(more)
                        5 -> { seekTo(0); hidePlaybackOverlay() }
                    }
                }
            })
        }.apply { tag="playback-settings" }
        addButton(actions,"换一批") { refreshFeedSession() }
        controls.visibility=View.VISIBLE
        val up=nav.getOrNull(TAB_RECOMMEND)?.id ?: View.NO_ID
        for(index in 0 until actions.childCount) actions.getChildAt(index).nextFocusUpId=up
        nav.forEach { it.nextFocusDownId=transportPlay?.id ?: View.NO_ID }
    }
    /** 打开推荐连播的菜单：浮层可聚焦，左右选导航，下进操作行。 */
    private fun revealFeedChrome() {
        if(screen!=Screen.PLAYER || !feedMode || episodePanel!=null || tvTools.showing) return
        panel=true; showHud(); controls.visibility=View.VISIBLE
        (nav.getOrNull(TAB_RECOMMEND) ?: transportPlay)?.requestFocus()
    }
    private fun updatePlaybackText() {
        transportPlay?.text=if(feedMode) if(player?.playWhenReady==true) "暂停播放" else "继续播放" else if(player?.playWhenReady==true) "暂停" else "播放"
        sleepButton?.text="定时 ${sleepTimer.label()}"
        if(screen!=Screen.PLAYER || playError) return
        if(sleepStopped) { playbackText.text="定时停止已生效 · 观看位置已保存 · 按播放可继续"; return }
        if(!playbackReady) { playbackText.text=(if(!requestedAutoplay) "准备后保持暂停 · " else "")+if(video==null) "正在获取播放地址…" else "正在缓冲…"; return }
        val p=player ?: return
        val duration=p.duration.coerceAtLeast(0); val position=pendingSeek ?: p.currentPosition
        val state=when { p.playbackState==Player.STATE_BUFFERING -> "缓冲中"; p.playbackState==Player.STATE_ENDED -> "本集已结束"; !p.playWhenReady -> "已暂停"; else -> "正在播放" }
        playbackText.text="$state  ·  ${formatTime(position)} / ${formatTime(duration)}  ·  $quality  ·  ${PlaybackSpeed.label(p.playbackParameters.speed)}"+(if(sleepTimer.active) "  ·  定时 ${sleepTimer.label()}" else "")
        progressBar.progress=if(duration>0) (position*1000/duration).toInt().coerceIn(0,1000) else 0
    }
    private fun requestPlayback(play: Boolean) {
        requestedAutoplay=play; pausedForLifecycle=!play
        if(play) sleepStopped=false
        if(!play) { player?.pause(); retryAutoplay=false; saveProgress() }
        else if(playError) { playEpisode(episodeIndex,retryPosition,true); return }
        else if(episodePanel==null) { if(player?.playbackState==Player.STATE_ENDED) player?.seekTo(0); player?.play() }
        if(!play) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        showHud(); updatePlaybackText(); syncMediaSession(); if(panel && episodePanel==null) showPanel()
    }
    private fun togglePlayback()=requestPlayback(!(if(playbackReady) player?.playWhenReady ?: requestedAutoplay else requestedAutoplay))
    private fun seekTo(position: Long) {
        val p=player ?: return; if(!playbackReady || p.duration<=0) return
        main.removeCallbacks(seekRunnable); pendingSeek=null
        p.seekTo(position.coerceIn(0,(p.duration-500).coerceAtLeast(0))); resetDanmaku(); updatePlaybackText(); syncMediaSession(); showHud()
    }
    private fun skipEpisode(direction: Int) {
        if(!foreground || screen!=Screen.PLAYER) return
        val count=detail?.episodes?.size ?: return
        val target=episodeIndex+direction
        if(target in 0 until count) playEpisode(target)
        // 推荐连播时，媒体键的上一集/下一集在片头片尾顺势换成上一部/下一部。
        else if(feedMode) switchFeed(direction)
    }
    private fun syncMediaSession() {
        if(!foreground || screen!=Screen.PLAYER) { mediaSession.deactivate(); return }
        val data=detail ?: return; val p=player
        val state=when {
            playError -> android.media.session.PlaybackState.STATE_ERROR
            sleepStopped || !requestedAutoplay || (p!=null && !p.playWhenReady) -> android.media.session.PlaybackState.STATE_PAUSED
            p==null || !playbackReady || p.playbackState==Player.STATE_BUFFERING -> android.media.session.PlaybackState.STATE_BUFFERING
            p.playbackState==Player.STATE_ENDED -> android.media.session.PlaybackState.STATE_STOPPED
            p.isPlaying -> android.media.session.PlaybackState.STATE_PLAYING
            else -> android.media.session.PlaybackState.STATE_PAUSED
        }
        val feedPrevious=feedMode && feedIndex>0
        val feedNext=feedMode && (feedIndex+1<catalog.size || hasMore)
        mediaSession.update(data.series.title,episodeIndex,p?.duration?.coerceAtLeast(0) ?: 0,currentPosition(),library.playbackSpeed,state,episodeIndex>0 || feedPrevious,episodeIndex<data.episodes.lastIndex || feedNext)
    }
    private fun stopForSleep() {
        if(screen!=Screen.PLAYER) return
        sleepStopped=true; requestedAutoplay=false; retryAutoplay=false; pausedForLifecycle=true
        cancelRecovery(); player?.pause(); saveProgress()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        playbackText.text="定时停止已生效 · 观看位置已保存 · 按播放可继续"
        showHud(); if(!playError && episodePanel==null) showPanel(); syncMediaSession()
        Toast.makeText(this,"定时停止已生效",Toast.LENGTH_LONG).show()
    }
    private fun showSleepPicker(anchor: TextView) {
        val labels=listOf("关闭定时停止","15 分钟后","30 分钟后","60 分钟后","90 分钟后","本集播完","再播完 3 集","再播完 5 集")
        tvTools.choose("定时停止 · ${sleepTimer.label()}",labels,anchor,{ choice ->
            when(choice) { 0 -> sleepTimer.cancel(); in 1..4 -> sleepTimer.afterMinutes(listOf(15,30,60,90)[choice-1]); else -> sleepTimer.afterEpisodes(listOf(1,3,5)[choice-5]) }
            if(anchor.tag!="playback-settings") anchor.text="定时 ${sleepTimer.label()}"; updatePlaybackText(); showHud()
        })
    }
    private fun frameResizeMode()=when(library.frameMode) {
        VideoFrameMode.FIT -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        VideoFrameMode.ZOOM -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        VideoFrameMode.FILL -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
    }
    /** 竖屏视频两侧铺封面：放大后模糊并压暗，替代大片黑边。 */
    private fun coverBackdrop()=ImageView(this).apply {
        visibility=View.GONE
        scaleType=ImageView.ScaleType.CENTER_CROP
        alpha=.42f
        // Android 12 起直接交给 GPU 做高斯模糊；更低版本靠小图放大已经足够柔和。
        if(android.os.Build.VERSION.SDK_INT>=31) setRenderEffect(android.graphics.RenderEffect.createBlurEffect(60f,60f,android.graphics.Shader.TileMode.CLAMP))
    }
    /**
     * 竖屏视频按原始比例收窄播放器并居中，两侧交给模糊封面；
     * 横屏或「等比铺满」「拉伸铺满」时保持整屏，横屏本来就没有黑边。
     */
    private fun applyVideoFrame(aspect: Float=videoAspect) {
        if(aspect<=0f) return
        videoAspect=aspect
        val portrait=aspect<1f && library.frameMode==VideoFrameMode.FIT
        // 菜单打开时不重建浮层，免得把焦点从操作行抢回导航。
        if(portrait!=feedPortrait) { feedPortrait=portrait; if(feedMode && feedTopReady && !panel) renderFeedChrome() }
        val view=playerView ?: return
        val metrics=resources.displayMetrics
        if(!portrait) {
            view.layoutParams=FrameLayout.LayoutParams(-1,-1)
            backdrop?.visibility=View.GONE
            syncDanmakuFrame()
            return
        }
        view.layoutParams=FrameLayout.LayoutParams((metrics.heightPixels*aspect).roundToInt().coerceIn(dp(120),metrics.widthPixels),metrics.heightPixels,Gravity.CENTER)
        syncDanmakuFrame()
        val cover=backdrop ?: return
        val url=detail?.series?.cover.orEmpty()
        if(url!=backdropCover) {
            backdropCover=url
            if(url.isBlank()) cover.setImageDrawable(null) else artwork.load(cover,url,ArtworkLoader.PRIORITY_VISIBLE,dp(200),dp(200))
        }
        cover.visibility=View.VISIBLE
    }
    /** 弹幕只铺在画面区域内：竖屏收窄时跟着播放器一起收窄。 */
    private fun syncDanmakuFrame() {
        val layer=danmakuView ?: return
        val params=playerView?.layoutParams as? FrameLayout.LayoutParams ?: return
        layer.layoutParams=FrameLayout.LayoutParams(params.width,params.height,params.gravity)
    }
    /** 竖屏时信息区只占左侧模糊封面那一条，不压到画面上。 */
    private fun feedInfoWidth(): Int {
        val metrics=resources.displayMetrics
        if(!feedPortrait) return dp(widthDp()*.58f)
        val videoWidth=(metrics.heightPixels*videoAspect).roundToInt().coerceAtMost(metrics.widthPixels)
        return (metrics.widthPixels-videoWidth).coerceAtLeast(dp(200))
    }
    private fun showFramePicker(anchor: TextView,inPlayer: Boolean) {
        tvTools.choose("全局画面模式",listOf("完整画面 · 保留比例与全部内容","等比铺满 · 会裁掉部分画面与字幕","拉伸铺满 · 画面比例会改变"),anchor,{ index ->
            library.frameMode=VideoFrameMode.entries[index]; playerView?.resizeMode=frameResizeMode(); applyVideoFrame()
            if(anchor.tag!="playback-settings") anchor.text=(if(inPlayer) "画面 " else "画面模式  ·  ")+library.frameMode.label
        })
    }
    private fun seek(direction: Int,repeat: Int) {
        val p=player ?: return; if(!playbackReady) return
        val duration=p.duration; if(duration<=0) return
        val step=if(repeat>4) 30000 else 10000
        pendingSeek=((pendingSeek ?: p.currentPosition)+direction*step).coerceIn(0,(duration-500).coerceAtLeast(0))
        main.removeCallbacks(seekRunnable); main.postDelayed(seekRunnable,250); updatePlaybackText(); showHud()
    }
    private fun showPanel() {
        if(playError) return
        if(feedMode) { revealFeedChrome(); return }
        panel=true; showHud(); controls.visibility=View.VISIBLE; controls.removeAllViews()
        val transport=row(); controls.addView(transport)
        val play=addButton(transport,if(player?.playWhenReady==true) "暂停" else "播放") { togglePlayback() }.also { transportPlay=it }
        addButton(transport,"上一集") { if(episodeIndex>0) playEpisode(episodeIndex-1) }.apply { isEnabled=episodeIndex>0; isFocusable=episodeIndex>0; alpha=if(episodeIndex>0) 1f else .4f }
        addButton(transport,"下一集") { if(episodeIndex<(detail?.episodes?.lastIndex ?: 0)) playEpisode(episodeIndex+1) }.apply { val enabled=episodeIndex<(detail?.episodes?.lastIndex ?: 0); isEnabled=enabled; isFocusable=enabled; alpha=if(enabled) 1f else .4f }
        val options=row().apply { setPadding(0,dp(8),0,0) }; controls.addView(options)
        lateinit var episodes: TextView
        episodes=addButton(options,"选集") { showEpisodePanel(episodes) }
        danmakuButton=addButton(options,if(danmakuEnabled) "弹幕开" else "弹幕关") { toggleDanmaku() }
        lateinit var more: TextView
        more=addButton(options,"播放设置") {
            tvTools.choose("播放设置",listOf("倍速 ${PlaybackSpeed.label(library.playbackSpeed)}","清晰度 ${currentQualityLabel()}","画面 ${library.frameMode.label}","定时 ${sleepTimer.label()}","弹幕设置","从头播放"),more,{ choice ->
                main.post {
                    if(screen==Screen.PLAYER && more.isAttachedToWindow) when(choice) {
                        0 -> showSpeedPicker(more)
                        1 -> showQualityPicker(more,true)
                        2 -> showFramePicker(more,true)
                        3 -> showSleepPicker(more)
                        4 -> showDanmakuSettings(more)
                        5 -> { seekTo(0); hidePanel() }
                    }
                }
            })
        }.apply { tag="playback-settings" }
        play.requestFocus()
    }
    private fun showSpeedPicker(anchor: TextView,inPlayer: Boolean=true) {
        if(speedDialog!=null) return
        val speeds=PlaybackSpeed.options
        val selected=speeds.indexOf(library.playbackSpeed)
        val dialog=AlertDialog.Builder(this).setTitle("全局默认倍速")
            .setSingleChoiceItems(speeds.map(PlaybackSpeed::label).toTypedArray(),selected) { popup,index ->
                library.playbackSpeed=speeds[index]
                if(inPlayer) player?.setPlaybackSpeed(library.playbackSpeed)
                if(anchor.tag!="playback-settings") anchor.text=(if(inPlayer) "倍速 " else "默认倍速  ·  ")+PlaybackSpeed.label(library.playbackSpeed)
                if(inPlayer) updatePlaybackText()
                popup.dismiss()
            }.setNegativeButton("取消",null).create()
        speedDialog=dialog
        dialog.setOnDismissListener {
            speedDialog=null
            if(anchor.isAttachedToWindow) { anchor.requestFocus(); if(inPlayer) showHud() }
        }
        dialog.show(); dialog.listView.setSelection(selected); dialog.listView.requestFocus()
    }
    /** 实际片源清晰度；还没取到地址时先显示本次请求的上限。 */
    private fun currentQualityLabel(): String = quality.trim().ifEmpty { "${qualityCap ?: library.maxQuality}P" }
    private fun showQualityPicker(anchor: TextView,inPlayer: Boolean) {
        val options=PlaybackQuality.options
        val title=if(inPlayer) "全局默认清晰度 · 当前 ${currentQualityLabel()}" else "全局默认清晰度"
        val dialog=AlertDialog.Builder(this).setTitle(title)
            .setSingleChoiceItems(options.map { "${it}P" }.toTypedArray(),options.indexOf(library.maxQuality)) { popup,index ->
                val target=options[index]; val changed=library.maxQuality!=target || qualityCap!=null
                library.maxQuality=target; qualityCap=null; qualityCapKey=""; autoFallbackTried=false; popup.dismiss()
                if(!inPlayer) anchor.text="默认清晰度  ·  ${target}P"
                if(inPlayer && changed) playEpisode(episodeIndex,currentPosition(),player?.playWhenReady ?: requestedAutoplay)
            }.setNegativeButton("取消",null).create()
        dialog.setOnDismissListener { if(anchor.isAttachedToWindow) anchor.requestFocus() }
        dialog.show()
    }
    private fun showEpisodePanel(anchor: View) {
        val data=detail ?: return
        if(episodePanel!=null) return
        val ticket=generation
        val overlayWasVisible=hud.visibility==View.VISIBLE
        requestedAutoplay=player?.playWhenReady ?: requestedAutoplay
        player?.pause(); saveProgress()
        var resumeOnClose=true
        val next=EpisodePanel(this,data.episodes.size,episodeIndex,
            { current,view,located -> tvTools.episodePicker(data.episodes.size,current,view,located) },
            { selected ->
                if(selected==episodeIndex) episodePanel?.dismiss()
                else { resumeOnClose=false; episodePanel?.dismiss(); playEpisode(selected) }
            },
            {
                episodePanel=null
                if(resumeOnClose && foreground && generation==ticket && screen==Screen.PLAYER) {
                    if(requestedAutoplay && !sleepStopped && !pausedForLifecycle) player?.play()
                    if(anchor.isAttachedToWindow) anchor.requestFocus()
                    if(overlayWasVisible) showHud() else hidePlaybackOverlay()
                }
            })
        episodePanel=next; next.show()
    }
    private fun hidePanel() { if(playError) return; panel=false; controls.visibility=View.GONE; controls.clearFocus(); showHud() }
    private fun formatTime(millis: Long): String { val seconds=(millis.coerceAtLeast(0)/1000); return "%02d:%02d".format(seconds/60,seconds%60) }
    private fun saveProgress(completed: Boolean=false) {
        val p=player ?: return; val data=detail ?: return; if(!playbackReady || episodeIndex !in data.episodes.indices) return
        // 切换剧集或切换推荐时播放器可能还停在上一部：不要把它写成这一部的进度。
        if(p.currentMediaItem?.mediaId!=data.episodes[episodeIndex]) return
        val progress=WatchProgress(data.series,data.episodes[episodeIndex],episodeIndex,p.currentPosition.coerceAtLeast(0),p.duration.coerceAtLeast(0),completed || p.playbackState==Player.STATE_ENDED,System.currentTimeMillis())
        library.save(progress)
        accountHistorySync.offer(progress,data.series.bookType,data.episodes.size)
    }
    private fun releasePlayer() {
        bufferingWatchdog.reset()
        cancelRecovery(); cancelPrefetch(); mediaSession.deactivate(); tvTools.close()
        val wasForeground=foreground; foreground=false
        val oldPanel=episodePanel; episodePanel=null; oldPanel?.dismiss(); foreground=wasForeground
        speedDialog?.dismiss(); speedDialog=null
        main.removeCallbacks(hideHud); main.removeCallbacks(seekRunnable); pendingSeek=null
        main.removeCallbacks(danmakuTick)
        playerListener?.let { player?.removeListener(it) }; playerListener=null
        video?.close(); video=null; playerView?.player=null; playerView=null; player?.release(); player=null; playbackReady=false; transportPlay=null; sleepButton=null
        backdrop=null; backdropCover=""; videoAspect=0f; feedPortrait=false
        danmakuView=null; danmakuButton=null; danmakuItems.clear(); danmakuLoadedUntil=0; danmakuVideoId=""; danmakuLoading=false; danmakuFailedAt=0
        closeTransport(playbackHttp); playbackHttp=null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    private val danmakuSizeLabels=listOf("小","中","大")
    private val danmakuSizeValues=listOf(16f,20f,24f)
    private val danmakuAlphaValues=listOf(60,80,100)
    private val danmakuSpeedLabels=listOf("慢","标准","快")
    private val danmakuSpeedValues=listOf(16_000L,12_000L,9_000L)
    private val danmakuLineValues=listOf(3,5,7)

    private fun applyDanmakuStyle(view: DanmakuView?=danmakuView) {
        view?.configure(danmakuSizeValues[danmakuSize],danmakuAlpha,danmakuSpeedValues[danmakuSpeed],danmakuLineValues[danmakuLines])
    }
    private fun danmakuSummary()="字号${danmakuSizeLabels[danmakuSize]} · ${danmakuAlpha}% · ${danmakuSpeedLabels[danmakuSpeed]} · ${danmakuLineValues[danmakuLines]}行"
    private fun showDanmakuSettings(anchor: TextView) {
        val content=column().apply { setPadding(dp(22),dp(10),dp(22),dp(4)) }
        lateinit var sizeButton: TextView
        lateinit var alphaButton: TextView
        lateinit var speedButton: TextView
        lateinit var lineButton: TextView
        fun refresh() {
            if(anchor.tag!="playback-settings") anchor.text="弹幕设置  ·  ${danmakuSummary()}"
            sizeButton.text="字号  ·  ${danmakuSizeLabels[danmakuSize]}"
            alphaButton.text="不透明度  ·  ${danmakuAlpha}%"
            speedButton.text="滚动速度  ·  ${danmakuSpeedLabels[danmakuSpeed]}"
            lineButton.text="最多行数  ·  ${danmakuLineValues[danmakuLines]} 行"
        }
        fun changed(block: ()->Unit) {
            block()
            danmakuPrefs.edit().putInt("danmakuSize",danmakuSize).putInt("danmakuAlpha",danmakuAlpha)
                .putInt("danmakuSpeed",danmakuSpeed).putInt("danmakuLines",danmakuLines).apply()
            refresh(); applyDanmakuStyle()
        }
        content.addView(text("按确认切换，立即生效并记住；推荐连播和普通播放共用。",13f,muted).apply { setPadding(dp(2),0,dp(2),dp(10)) })
        sizeButton=button("") { changed { danmakuSize=(danmakuSize+1)%danmakuSizeLabels.size } }
        alphaButton=button("") { changed { danmakuAlpha=danmakuAlphaValues[(danmakuAlphaValues.indexOf(danmakuAlpha)+1)%danmakuAlphaValues.size] } }
        speedButton=button("") { changed { danmakuSpeed=(danmakuSpeed+1)%danmakuSpeedLabels.size } }
        lineButton=button("") { changed { danmakuLines=(danmakuLines+1)%danmakuLineValues.size } }
        listOf(sizeButton,alphaButton,speedButton,lineButton).forEach { view ->
            content.addView(view,lp().apply { bottomMargin=dp(8) })
        }
        refresh()
        val dialog=AlertDialog.Builder(this).setTitle("弹幕设置").setView(content).setNegativeButton("关闭",null).create()
        dialog.setOnDismissListener {
            if(anchor.isAttachedToWindow) { anchor.requestFocus(); if(screen==Screen.PLAYER) showHud() }
        }
        dialog.show(); sizeButton.requestFocus()
    }
    /** 换集/换部：清掉上一集的弹幕，等播放器就绪后按当前进度重新取。 */
    private fun startDanmaku(videoId: String,seriesId: String) {
        if(danmakuCacheVideo!=videoId) {
            danmakuCache.clear(); danmakuCacheVideo=videoId
            danmakuView?.clearSprites()
        }
        danmakuVideoId=videoId; danmakuSeriesId=seriesId
        danmakuItems.clear(); danmakuLoadedUntil=0; danmakuLoading=false; danmakuFailedAt=0
        danmakuView?.clear(); danmakuView?.visibility=View.INVISIBLE
        updateDanmakuButton()
        main.removeCallbacks(danmakuTick)
        if(danmakuEnabled) main.post(danmakuTick)
    }
    private val danmakuTick=object: Runnable {
        override fun run() {
            if(screen!=Screen.PLAYER || !foreground || !danmakuEnabled) return
            pumpDanmaku(); applyDanmakuVisibility(); danmakuView?.invalidate()
            main.postDelayed(this,400)
        }
    }
    /** 弹幕按 30 秒一段取：先补齐当前进度所在的一段，快播完时预取下一段。 */
    private fun pumpDanmaku() {
        val p=player ?: return
        if(!playbackReady || playError || video==null || danmakuLoading) return
        val position=p.currentPosition.coerceAtLeast(0)
        val duration=p.duration.takeIf { it>0 && it!=androidx.media3.common.C.TIME_UNSET } ?: (position+600_000L)
        if(duration<=position) return
        val start=when {
            danmakuLoadedUntil==0L -> position/DANMAKU_WINDOW*DANMAKU_WINDOW
            position>=danmakuLoadedUntil-5_000 -> danmakuLoadedUntil
            else -> return
        }
        if(danmakuFailedAt>0 && android.os.SystemClock.elapsedRealtime()-danmakuFailedAt<15_000) return
        // 本集播完时下一段起点会等于总时长，别再去请求空区间。
        if(start>=duration) return
        loadDanmaku(start,duration)
    }
    private fun loadDanmaku(start: Long,duration: Long) {
        val videoId=danmakuVideoId; val seriesId=danmakuSeriesId
        if(videoId.isEmpty() || seriesId.isEmpty() || screen!=Screen.PLAYER) return
        danmakuCache.get(start)?.let { cached -> applyDanmakuPage(cached); return }
        danmakuLoading=true
        io.execute {
            val result=runCatching { repository.danmaku(videoId,seriesId,start,duration) }
            main.post {
                if(isDestroyed) return@post
                danmakuLoading=false
                if(screen!=Screen.PLAYER || danmakuVideoId!=videoId) return@post
                result.onSuccess { page ->
                    danmakuCache.put(page)
                    applyDanmakuPage(page)
                    android.util.Log.i("HongguoTV","Danmaku start=$start next=${page.nextMs} items=${page.items.size} total=${page.total} loaded=${danmakuItems.size}")
                }.onFailure { problem ->
                    danmakuFailedAt=android.os.SystemClock.elapsedRealtime()
                    android.util.Log.w("HongguoTV","Danmaku failed start=$start: ${problem.message}")
                    if(danmakuItems.isEmpty()) applyDanmakuVisibility()
                }
            }
        }
    }
    /** 合并一段弹幕并重排轨道；并发请求回来得晚时，已经覆盖过的区间不回退。 */
    private fun applyDanmakuPage(page: DanmakuPage) {
        danmakuFailedAt=0
        if(page.nextMs<=danmakuLoadedUntil) return
        val known=danmakuItems.mapTo(HashSet()) { it.id }
        danmakuItems+=page.items.filter { it.id !in known }
        // 长剧集只留最近的一段，避免整集弹幕一直占着内存。
        if(danmakuItems.size>600) danmakuItems.subList(0,danmakuItems.size-600).clear()
        danmakuLoadedUntil=page.nextMs
        danmakuView?.submit(DanmakuPage.merge(danmakuItems))
        applyDanmakuVisibility()
    }
    /** 推荐浮层打开时先让弹幕退场：导航和剧名区域不叠字。 */
    private fun applyDanmakuVisibility() {
        val view=danmakuView ?: return
        val occupied=feedMode && hud.visibility==View.VISIBLE
        val show=danmakuEnabled && screen==Screen.PLAYER && danmakuItems.isNotEmpty() && !occupied
        view.visibility=if(show) View.VISIBLE else View.INVISIBLE
    }
    /** 拖动进度后重新按新位置取弹幕，避免把上一段的弹幕留在画面上。 */
    private fun resetDanmaku() {
        if(!danmakuEnabled) return
        danmakuItems.clear(); danmakuView?.clear(); danmakuLoadedUntil=0; danmakuFailedAt=0
        main.removeCallbacks(danmakuTick); if(foreground) main.post(danmakuTick)
    }
    private fun toggleDanmaku() {
        danmakuEnabled=!danmakuEnabled
        danmakuPrefs.edit().putBoolean("danmaku",danmakuEnabled).apply()
        if(danmakuEnabled) { danmakuLoadedUntil=0; danmakuFailedAt=0; main.removeCallbacks(danmakuTick); main.post(danmakuTick) }
        else { danmakuItems.clear(); danmakuView?.clear() }
        applyDanmakuVisibility(); updateDanmakuButton(); showHud()
        Toast.makeText(this,if(danmakuEnabled) "弹幕已打开" else "弹幕已关闭",Toast.LENGTH_SHORT).show()
    }
    private fun updateDanmakuButton() { danmakuButton?.text=if(danmakuEnabled) "弹幕开" else "弹幕关" }
    private fun returnToDetail(forceDetail: Boolean=false) {
        qualityCap=null; qualityCapKey=""; autoFallbackTried=false
        sleepTimer.cancel(); sleepStopped=false; saveProgress(); library.flush(); invalidatePage(); releasePlayer()
        if(directPlayback && !forceDetail) showCatalog() else { directPlayback=false; showDetail(true) }
    }
    private fun goBack() {
        if(!library.isLoaded) { finish(); return }
        when(screen) {
            Screen.PLAYER -> if(feedMode) feedBack() else if(hud.visibility==View.VISIBLE && !playError) hidePlaybackOverlay() else returnToDetail()
            Screen.DETAIL -> if(detailFromCharacter && character!=null) showCharacter() else showCatalog()
            Screen.PREVIEW -> showCatalog()
            Screen.CHARACTER -> {
                val back=characterReturnDetail
                character=null; characterPerson=null; characterReturnDetail=null; detailFromCharacter=false
                if(back!=null) { detail=back; showDetail(false) } else showCatalog()
            }
            else -> when {
                collection!=null -> { leaveCollection(); showCatalog() }
                sectionButtons.values.any { it.hasFocus() } -> nav.getOrNull(tab)?.requestFocus()
                nav.any { it.hasFocus() } -> if(tab!=TAB_HOME) { switchTab(TAB_HOME); nav.getOrNull(TAB_HOME)?.requestFocus() } else finish()
                else -> nav.getOrNull(tab)?.requestFocus()
            }
        }
    }
    /**
     * 推荐连播的返回：菜单开着就先收起菜单，菜单收起时按一次先把菜单叫出来；
     * 两次连续按（2.5 秒内）则退出应用，所以返回键不会在观看时直接把播放关掉。
     */
    private fun feedBack() {
        if(playError) { directPlayback=true; returnToDetail(); return }
        val now=android.os.SystemClock.elapsedRealtime()
        val recent=feedBackAt!=0L && now-feedBackAt<2500
        if(hud.visibility==View.VISIBLE) {
            hidePlaybackOverlay(); feedBackAt=0
            if(recent) { saveProgress(); library.flush(); finish() }
            return
        }
        if(recent) { feedBackAt=0; saveProgress(); library.flush(); finish(); return }
        feedBackAt=now
        Toast.makeText(this,"再按一次返回键退出应用",Toast.LENGTH_SHORT).show()
        revealFeedChrome()
    }
    @Deprecated("TV remote back is handled through the activity")
    override fun onBackPressed()=goBack()
    /** 不同遥控器把「菜单」映射成不同按键：MENU、INFO、内容菜单或红/蓝功能键。 */
    private fun isMenuKey(key: Int)=key==KeyEvent.KEYCODE_MENU || key==KeyEvent.KEYCODE_INFO ||
        key==KeyEvent.KEYCODE_TV_CONTENTS_MENU || key==KeyEvent.KEYCODE_PROG_RED || key==KeyEvent.KEYCODE_PROG_BLUE
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if(screen==Screen.PLAYER) {
            val key=event.keyCode
            if(key==KeyEvent.KEYCODE_BACK) { if(event.action==KeyEvent.ACTION_UP) goBack(); return true }
            if(key in listOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,KeyEvent.KEYCODE_MEDIA_PLAY,KeyEvent.KEYCODE_MEDIA_PAUSE,KeyEvent.KEYCODE_MEDIA_NEXT,KeyEvent.KEYCODE_MEDIA_PREVIOUS,KeyEvent.KEYCODE_MEDIA_STOP,KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,KeyEvent.KEYCODE_MEDIA_REWIND)) {
                if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0) when(key) {
                    KeyEvent.KEYCODE_MEDIA_PLAY -> requestPlayback(true)
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> requestPlayback(false)
                    KeyEvent.KEYCODE_MEDIA_NEXT -> skipEpisode(1)
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> skipEpisode(-1)
                    KeyEvent.KEYCODE_MEDIA_STOP -> returnToDetail()
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seek(1,0)
                    KeyEvent.KEYCODE_MEDIA_REWIND -> seek(-1,0)
                    else -> togglePlayback()
                }
                return true
            }
            if(isMenuKey(key)) {
                // 推荐连播用菜单键开关浮层菜单；普通播放沿用原来的设置面板。
                if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0) { if(feedMode) { if(panel) hidePlaybackOverlay() else revealFeedChrome() } else if(!panel) showPanel() }
                return true
            }
            if(!panel) {
                if(key in listOf(KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN)) {
                    if(event.action==KeyEvent.ACTION_DOWN) when(key) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> seek(-1,event.repeatCount)
                        KeyEvent.KEYCODE_DPAD_RIGHT -> seek(1,event.repeatCount)
                        KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER -> if(event.repeatCount==0) togglePlayback() else if(feedMode) revealFeedChrome() else showPanel()
                        KeyEvent.KEYCODE_DPAD_DOWN -> if(event.repeatCount==0) { if(feedMode && episodePanel==null) switchFeed(1) else showEpisodePanel(hud) }
                        KeyEvent.KEYCODE_DPAD_UP -> if(event.repeatCount==0) { if(feedMode && episodePanel==null) switchFeed(-1) else showPanel() }
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if(!library.isLoaded) return
        when(screen) {
            Screen.PLAYER -> { val position=currentPosition(); val autoplay=player?.playWhenReady==true && !pausedForLifecycle; playEpisode(episodeIndex,position,autoplay) }
            Screen.DETAIL -> if(detail!=null) showDetail(false)
            Screen.CHARACTER -> if(character!=null) showCharacter() else if(detail!=null) showDetail(false) else showCatalog()
            else -> showCatalog()
        }
    }
    override fun onResume() {
        super.onResume(); foreground=true; main.removeCallbacks(tick); main.post(tick)
        updater.resume()
        if(library.isLoaded) {
            if(initialCatalogPending) showCatalog(load=true)
            else if(restoreStoppedPage) {
                restoreStoppedPage=false
                when(screen) {
                    Screen.PLAYER -> detail?.let { continueLatest(it,autoplay=false) } ?: showCatalog()
                    Screen.DETAIL -> if(detail!=null) showDetail(false) else pendingDetail?.let { openDetail(it,directPlayback,autoplay=false,fromCharacter=detailFromCharacter) } ?: showCatalog()
                    Screen.CHARACTER -> if(character!=null) showCharacter() else if(detail!=null) showDetail(false) else showCatalog()
                    else -> showCatalog(load=!localCatalog() && catalog.isEmpty() && (tab==TAB_HOME || tab==TAB_RANK || tab==TAB_CATEGORY || tab==TAB_RECOMMEND || (tab==TAB_SEARCH && query.isNotBlank())))
                }
            } else {
                pageWork.resume()
            }
            if(screen==Screen.CATALOG) favoriteMonitor.check()
            if(screen==Screen.PLAYER) syncMediaSession()
        } else libraryLoadError?.let(::showLibraryError)
        if(library.isLoaded) accountHistorySync.resume(library.history())
    }
    override fun onPause() {
        updater.pause()
        foreground=false; main.removeCallbacks(tick); cancelRecovery(); cancelPrefetch(); mediaSession.deactivate()
        sleepTimer.cancel(); favoriteMonitor.stop(); detailAutoplay=false; requestedAutoplay=false; pausedForLifecycle=true
        player?.pause(); saveProgress(); accountHistorySync.flush(); flushRecommendTypes(); library.flush(); super.onPause()
    }
    override fun onStop() {
        restoreStoppedPage=true; generation++; cancelWork(); stopQrLogin(); tvTools.close(); main.removeCallbacks(danmakuTick)
        if(screen==Screen.PLAYER) { startPosition=currentPosition(); saveProgress(); releasePlayer() }
        accountHistorySync.flush()
        artwork.cancelPage(clearMemory=true); library.flush(); super.onStop()
    }
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if(::artwork.isInitialized) artwork.trimMemory(level)
        if(level>=android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) { cancelPrefetch(); preparedSeries.clear() }
    }
    override fun onDestroy() {
        generation++; cancelWork(); stopQrLogin()
        if(networkRegistered) connectivity.unregisterNetworkCallback(networkCallback)
        favoriteMonitor.destroy(); tvTools.destroy(); updater.destroy(); saveProgress(); releasePlayer(); library.close(); accountHistorySync.close()
        artwork.close(); releaseCatalogViews(); mediaSession.release(); main.removeCallbacksAndMessages(null)
        io.shutdownNow(); prefetchWorker.shutdownNow(); feedPrefetchWorker.shutdownNow(); preparedSeries.clear(); networkCleanup.shutdown()
        Thread({ repository.http.dispatcher.cancelAll(); repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown() },"hongguotv-network-cleanup").start()
        super.onDestroy()
    }
}
