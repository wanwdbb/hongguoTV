# 红果 TV 接口文档

本文整理当前 Kotlin 原生版实际使用的全部网络接口，按「网站页面 → 官方 App API → 视频流 → 自动更新 → 局域网服务」分类。内容与代码保持一一对应，改接口时请同步更新本文件。

对应实现主要在：

- `core/src/main/kotlin/com/hongguotv/core/ContentRepository.kt`：网站页面、搜索、设备注册、扫码登录、账号资料、播放信息、弹幕
- `core/src/main/kotlin/com/hongguotv/core/Signer.kt`：App 接口签名
- `app/src/main/kotlin/com/hongguotv/nativeapp/AuthStore.kt`：登录会话的本地存档
- `core/src/main/kotlin/com/hongguotv/core/MediaCrypto.kt`：播放地址与视频分片解密
- `core/src/main/kotlin/com/hongguotv/core/RemoteVideo.kt`：视频 Range 读取
- `core/src/main/kotlin/com/hongguotv/core/AppUpdates.kt`：自动更新
- `core/src/main/kotlin/com/hongguotv/core/PhoneSearchServer.kt`、`LibraryTransferServer.kt`：局域网服务

## 2026-10-28 HAR4 协议更新

当前播放恢复修复版 0.9.0（7cb06f1）的 preload/v1/ 请求：固定格式未压缩 JSON、进程内稳定的独立播放身份、不附带账号凭据。支持直返数组 video_list、嵌套 video_meta/encrypt_info，只有缺少可用直返资源时才请求 fallback_api。网页详情失败时新增 video_detail/v1/ 备用；弹幕使用完整 7.3.9.32 设备版本参数。协议证据、兼容范围和验证边界见 [HAR4 核对](API_HAR4.md)。

## 1. 总览

| 用途 | 地址 | 方法 | 鉴权 |
| --- | --- | --- | --- |
| 网站内容页（首页/分类/榜单/详情/搜索兜底） | `https://hongguoduanju.com` | GET | 无，带 App UA |
| App 搜索 | `https://api5-normal-sinfonlinea.fqnovel.com/reading/bookapi/search/tab/v` | GET | 签名 + 设备参数 |
| 详情页相关作品 | `https://api5-normal-sinfonlinec.fqnovel.com/reading/bookapi/plan/v:version/` | GET | 签名 + 设备参数 |
| 设备注册 | `https://log5-applog.fqnovel.com/service/2/device_register/` | POST JSON | 无签名，固定查询参数 |
| 获取登录二维码 | `https://hongguoduanju.com/passport/web/get_qrcode/` | GET | 无，网页 Cookie |
| 轮询扫码状态 | `https://hongguoduanju.com/passport/web/check_qrconnect/` | GET | 网页 Cookie + 二维码 token |
| 扫码登录落地 | 状态响应里的 `redirect_url`（官方地址） | GET | 扫码会话 Cookie |
| 账号资料刷新 | `https://api5-normal-sinfonlineb.fqnovel.com/reading/user/info/v` | GET | 签名 + 登录 Cookie |
| 账号观看记录 | `https://api5-normal-sinfonlineb.fqnovel.com/reading/bookapi/read_history/list/v` | GET | 签名 + 登录 Cookie |
| 账号短剧收藏 | `https://api5-normal-sinfonlineb.fqnovel.com/reading/bookapi/bookshelf/video/list/v` | GET | 签名 + 登录 Cookie |
| 账号预约列表 | `https://api5-normal-sinfonlineb.fqnovel.com/reading/user/subscribe/list/v1/` | GET | 签名 + 登录 Cookie |
| 预约/取消预约 | `https://api5-normal-sinfonlineb.fqnovel.com/reading/bookapi/search/uncover_subscribe/v:version/` | POST JSON | 签名 + 登录 Cookie |
| 播放信息 | `https://api5-normal-sinfonlineb.fqnovel.com/novel/player/multi_video_model/preload/v1/` | POST JSON | 签名 + 设备参数 |
| 播放地址明细 | 播放信息里的 `fallback_api`（临时地址） | GET | 无签名 |
| 弹幕 | `https://api5-normal-sinfonlineb.fqnovel.com/novel/commentapi/comment/list/{video_id}/v1/` | POST JSON | 签名 + 设备参数 |
| 视频流 | 播放信息里的 `main_url`（CDN 动态域名） | GET Range | 无签名，需 Referer |
| 自动更新清单 | `https://github.com/wanwdbb/hongguoTV/releases/latest/download/update.json` | GET | 无 |
| 自动更新 APK | `https://github.com/wanwdbb/hongguoTV/releases/download/{tag}/{file}` | GET | 无 |
| 手机输入 | `http://<电视IP>:<随机端口>/HongGuoTV` | GET/POST | 固定 token，会话一次性 |
| 记录备份 | `http://<电视IP>:<随机端口>/<32位token>` | GET/POST | 随机 token，会话一次性 |

所有对外请求均为 HTTPS；只有局域网两个服务使用明文 HTTP（仅绑定局域网地址，且只在电视端对应窗口打开期间有效）。

## 2. 通用约定

### 2.1 HTTP 客户端

`ContentRepository` 使用 OkHttp，超时：连接 15 秒、读取 30 秒、整次调用 45 秒。

- 页面与普通 GET 使用 `VendorConstants.VIDEO_UA`；
- 带请求体的签名接口同样使用 `VIDEO_UA`；
- 无请求体的签名接口使用 `VendorConstants.APP_UA`；
- 响应体上限 16 MB，超过即报错；
- 所有请求可被 `RequestScope` 取消（切换页面、退出播放时中断，包括读取响应体阶段）。

UA 取值：

```
VIDEO_UA    = com.phoenix.read/71332 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)
APP_UA      = com.phoenix.read/72232 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3)
QR_UA       = Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36
```

### 2.2 设备身份

设备身份由 `DeviceIdentity` 持有，默认是进程内随机值：

- `install_id`：随机安装标识；
- `device_id`：随机设备标识。

App 首次启动时后台调用一次设备注册（见 4.1），成功后写入 SharedPreferences `native-device-v1` 的 `install_id` / `device_id`，之后搜索等 App 接口复用这份身份。播放单独保留 0.9.0 的进程级随机 device_id 和固定 iid，不受搜索注册结果影响，也不会因为播放空响应重新注册搜索身份。未注册设备调用搜索、弹幕等接口时，服务端可能返回 **HTTP 200 + 空响应体**，代码会转成中文错误提示。

### 2.3 App 接口签名

`Signer.sign(base, values, body)` 生成完整 URL 与请求头：

1. 取当前秒级时间戳 `ts`，毫秒时间戳 `_rticket`；
2. 把 `values + ts + _rticket` 拼成查询串；
3. 对 `_rticket` 做最多 128 次递增（每次 +1 ms），直到 `branch(query, body, ts) == 0`（接口的校验位约束）；
4. 计算各签名头。

请求参数示例：

```
?iid=...&device_id=...&aid=8662&app_name=novelread&...&ts=1730000000&_rticket=1730000000123
```

请求头：

| 请求头 | 值 |
| --- | --- |
| `User-Agent` | 有请求体时 `VIDEO_UA`，否则 `APP_UA` |
| `Accept` | `application/json; charset=utf-8,application/x-protobuf` |
| `x-ss-req-ticket` | `_rticket` |
| `x-tt-request-tag` | `t=0;n=0` |
| `sdk-version` | `2` |
| `passport-sdk-version` | `50561` |
| `x-xs-from-web` | `0` |
| `x-khronos` | `ts` |
| `x-ladon` | `base64(大端32位(ts))` |
| `x-argus` | `base64(小端32位(ts))` |
| `x-gorgon` | 由查询串、请求体、`ts` 与随机数计算，见 `Signer.gorgon()` |
| `x-helios` | 由 `ts` 计算，见 `Signer.helios()` |
| `x-medusa` | 由完整 URL、请求体、`ts` 计算，见 `Signer.medusa()` |
| `x-tt-dt` | 空串 |
| `Content-Type` | 有请求体时 `application/json; charset=UTF-8` |
| `x-vc-bdturing-sdk-version` | 有请求体时 `3.7.2.cn` |
| `x-ss-stub` | 有请求体时 `MD5(body)` 的大写十六进制 |

扫码登录使用红果网页 Passport 接口，不走 App 签名；请求带浏览器 UA、Referer 和网页 Cookie，见 4.6。

### 2.4 默认设备参数

`VendorConstants.device` 会被拼进搜索、播放、弹幕接口的查询串：

```json
{
  "iid": "<install_id>",
  "device_id": "<device_id>",
  "ac": "wifi",
  "channel": "update_64",
  "aid": "8662",
  "app_name": "novelread",
  "version_code": "71332",
  "version_name": "7.1.3.32",
  "device_platform": "android",
  "os": "android",
  "ssmix": "a",
  "device_type": "25053RT47C",
  "device_brand": "Redmi",
  "language": "zh",
  "os_api": "36",
  "os_version": "16",
  "manifest_version_code": "71332",
  "resolution": "1280*2772",
  "dpi": "520",
  "update_version_code": "71332",
  "host_abi": "arm64-v8a",
  "dragon_device_type": "phone",
  "pv_player": "71332",
  "compliance_status": "0",
  "need_personal_recommend": "1",
  "player_so_load": "1",
  "is_android_pad_screen": "0"
}
```

不同接口会覆盖版本字段（见各接口说明）。

### 2.5 错误语义

- HTTP 非 2xx → `IOException("内容请求失败（HTTP xxx）")`；
- 响应为空 → 按接口返回中文提示（如「搜索接口暂不可用」）；
- 业务 `code != 0` → 中文提示；
- 页面结构变化 → 「内容数据结构已变化」「xx格式已变化」等；
- 播放地址不是 http/https → 「播放地址格式异常」；
- 所有错误信息都直接显示在电视界面上，不暴露原始 JSON。

### 2.6 登录会话

登录使用红果网页的二维码流程，具体请求见 4.6。客户端侧约定：

- 二维码由 `get_qrcode` 返回，图片是 PNG data URI；二维码 token 只留在 `ContentRepository`，不交给界面、不写日志；
- 界面每 2 秒调用一次 `check_qrconnect`，状态为 `new` / `scanned` / `confirmed` / `expired`；
- 扫码确认后跟随官方 `redirect_url` 落地，收集二维码会话的全部 Cookie，再读取账号资料；
- 响应里的全部 `Set-Cookie` 与 `X-Tt-Token` 会合并进会话，包括 `sessionid`、`sid_guard`、`uid_tt`、`sid_tt`、`d_ticket` 等；
- 会话只附加到 `*.fqnovel.com` 官方接口，不会发给网站页面或 CDN；之后请求带 `Cookie` 与 `X-Tt-Token`；
- 账号资料接口暂时不可用时保留一个不含昵称的本地会话，界面显示通用登录提示；只有服务端明确未登录/过期才清空；
- 会话保存在应用私有 SharedPreferences `native-auth-v1`，不写日志、不出现在错误提示或提交文件里；
- 登录失败只影响账号页，匿名浏览、搜索、详情和播放继续可用；退出登录或确认会话失效时才清空存档。

## 3. 网站页面接口

站点：`https://hongguoduanju.com`。

这些页面是服务端渲染的 HTML，数据藏在 `<script>` 里的 `_ROUTER_DATA` 中。解析方式（`ContentRepository.extractRouter`）：

1. 找到 `_ROUTER_DATA` 标记；
2. 从其后第一个 `{` 开始做括号配对（跳过字符串与转义）；
3. 解析成 JSON，取 `loaderData`。

### 3.1 首页

```
GET https://hongguoduanju.com/category?tab=1&sort_type=1
GET https://hongguoduanju.com/category?tab=1&sort_type=1&page=2
```

- 不带 `page` 即第一页；
- 返回 `loaderData.category_page`（兼容 `category_$`）；
- 列表字段：`recommendList` 或 `categoryData.recommendList`；
- 分页：`pagination.totalPages`（上限 100）；
- 题材筛选：`selectorList[0].items[]`，字段 `selector_item_id`（slug）、`show_name`（显示名）。

「首页」直接使用这个接口；推荐页的候选池来自 3.2 的分类页，不调用本接口。

### 3.2 分类

```
GET https://hongguoduanju.com/category/{类型}
GET https://hongguoduanju.com/category/{类型}/{题材slug}
GET https://hongguoduanju.com/category/{类型}/{题材slug}?page=N
```

类型（`CatalogType`）：

| 类型 | 路径 |
| --- | --- |
| 真人剧 | `/category/real-drama` |
| 漫剧 | `/category/comic-drama` |
| AI 剧 | `/category/ai-drama` |
| 漫画 | `/category/comic` |

`题材slug` 只允许 `[a-z0-9-]{1,40}`，为空表示「全部」。响应结构与 3.1 相同。

推荐片单按类型抓取该接口：

- 混合模式：真人剧 + 漫剧 + AI 剧各一页；
- 指定类型：只抓该类型；
- 第一批会在有匹配兴趣题材时额外抓一个题材页（`discovery` 候选）；
- 起始页由会话种子决定（1–3 页），换一批时往后翻。

### 3.3 排行榜

```
GET https://hongguoduanju.com/rank/{slug}?page=N
```

| 榜单 | slug | 页面数据键 | rankKey |
| --- | --- | --- | --- |
| 红果热播榜 | `hot-drama` | `rank_hot-drama/page` | `hongguo` |
| 真人剧热播榜 | `hot-real-drama` | `rank_hot-real-drama/page` | `real` |
| AI 剧热播榜 | `hot-ai-drama` | `rank_hot-ai-drama/page` | `ai` |
| 漫剧热播榜 | `hot-comic-drama` | `rank_hot-comic-drama/page` | `comic` |

解析分两条路径：

1. 优先读 `<script data-fn-name="r"|"mergeLoaderData" data-fn-args=...>` 里的 `rankList`；
2. 拿不到时读页面已渲染的 `article[aria-labelledby^=rank-title-]` 卡片。

名次与热度都使用网站给出的值，不重新编号：

- `rank`：名次；
- `heatText`：热度文案；
- `statusTags[]`：状态标签（最多取 2 个）；
- `totalPages`：总页数；
- `updatedText`：更新时间文案。

### 3.4 详情

```
GET https://hongguoduanju.com/detail?series_id={剧集ID}
```

- `series_id` 只允许 1–30 位数字；
- 取 `loaderData.detail_page.seriesDetail`；
- `vid_list` 是分集视频 ID 数组（顺序即第 1 集、第 2 集…），必须非空、全为数字、无重复；
- 剧集字段：`series_id`、`series_name`、`series_cover`、`series_intro`、`episode_right_text`、`tags[].name`（或 `category_list`）；
- `celebrities[]` 是演员/主创：`celebrity_id`、`nickname`、`avatar`、`sub_title`，用于详情页的「作者页」入口；
- `celebrities` 为空时，再调用「4.3 官方详情主创」补出品方；真人剧已有演员时不会多发这次请求；
- 详情页的「相关作品」由官方接口补充，见「4.3.3 详情页相关作品」；该接口失败时详情页仍正常显示，只是隐藏相关作品区块。

### 3.5 演员/主创页（网站角色页）

```
GET https://hongguoduanju.com/character/{celebrity_id}
```

- `celebrity_id` 只允许 1–30 位数字；官方 `main_creator_users` 的 `user_id` 不是 `celebrity_id`，不能直接拼到这条地址；
- 取 `loaderData["character_(id)/page"]`；
- `userInfo`：`person_id`、`user_name`、`user_avatar`、`description`、`subtitle_list[]`（如「女演员 / 导演 / 编剧」）、`user_relation_info.fans_num`；
- `videoList[]` 是同一演员/主创的其他作品，字段与详情页的剧集卡片相同；作品列表复用详情页卡片解析。

### 3.6 出品方作者页（官方资料 + 官方作品列表）

网站没有单独的「作者」字段。漫剧/AI剧的出品方来自官方详情，主页按下面的流程拼：

1. 用 `user_id` 调「4.3.2 官方用户资料」取名称、头像、简介、角色和粉丝数；
2. 用同一个 `user_id` 调官方作者主页作品接口：

   ```
   POST https://api5-sinfnormal.fqnovel.com/novel/commentapi/user/profile/{user_id}/v1?{签名参数}
   Content-Type: application/json; charset=utf-8
   comment-source: 1506
   lc: 101

   {
     "comment_source": 1506,
     "count": 18,
     "cursor": "",
     "extra_param": {},
     "locate_post_id": "",
     "profile_user_type": 0,
     "sort": "time_desc",
     "sub_tab_type": 22,
     "tab_type": 23,
     "target_user_id": "{user_id}"
   }
   ```

   这个接口要使用 `version_code=73932`、`version_name=7.3.9.32`；旧版本参数会返回 `total=0` 的空列表。

3. 取 `data.data_list[].video`：`video_id` 就是短剧的 `series_id`，标题/封面/简介在 `common`，集数和角标在 `expand.episode_cnt`、`expand.sub_title_list`；
4. 首页 `data.hightlight.total` 是作品总数（例如 `143`）；后续页该字段会变成 `0`，不能用来判断结束。把 `data.common_list_info.cursor` 原样放进下一次请求的 `cursor`，直到 `has_more=false` 或返回空页（最多 100 页）。服务端每页条数不固定，不能按 offset 推算；
5. 按 `video_id` 去重；当前剧集也保留在作品列表中。

该接口只返回 `target_user_id` 本人的作品，不会像网站关键词搜索那样混入同名或相关剧集，因此作者页不再使用网站搜索。拿不到资料时退回官方详情里的昵称与头像。漫画分类的样本没有 `main_creator_users`，因此没有可用的作者入口，按钮自动隐藏。

### 3.7 网页搜索（兜底）

```
GET https://hongguoduanju.com/search/{URL编码的关键词}?page=N
```

只有在 App 搜索接口失败或结果为空、且内容类型是短剧时才会走这里：

- 关键词用 `Signer.encode` 编码（空格转 `%20`）；
- 取 `loaderData["search_(keyword)/page"]` 或 `search_page`；
- 列表字段 `searchList`；
- 普通分类/搜索兜底仍只用于短剧；作者页的出品方作品列表改用 `3.6` 的官方作者主页作品接口，不再依赖关键词搜索。

> 注意：网页搜索的 `page` 是独立分页，没有游标，翻页超过 100 页会被拒绝。

## 4. 官方 App API

### 4.1 设备注册

```
POST https://log5-applog.fqnovel.com/service/2/device_register/?aid=8662&version_code=71332&channel=update_64&package=com.phoenix.read&_rticket={毫秒时间戳}&use_store_region_cookie=1&okhttp_version=4.2.243.31-douyin
Content-Type: application/json; charset=utf-8
User-Agent: APP_UA
Accept: application/json
Accept-Language: zh-CN,zh;q=0.9
log-encode-type: gzip
x-ss-req-ticket: {毫秒时间戳}
x-vc-bdturing-sdk-version: 3.7.2.cn
Cookie: store-region=cn-zj; store-region-src=did;
```

请求体是官方 `ss_app_log` 的等价 JSON：

```json
{
  "magic_tag": "ss_app_log",
  "header": {
    "display_name": "红果免费短剧",
    "aid": 8662,
    "channel": "update_64",
    "package": "com.phoenix.read",
    "app_version": "7.1.3.32",
    "version_code": 71332,
    "os": "Android",
    "os_version": "16",
    "os_api": 36,
    "device_model": "25053RT47C",
    "device_brand": "Redmi",
    "device_manufacturer": "Redmi",
    "cpu_abi": "arm64-v8a",
    "resolution": "1280x2772",
    "density_dpi": 520,
    "language": "zh",
    "timezone": 8,
    "access": "wifi",
    "region": "CN",
    "tz_name": "Asia/Shanghai",
    "tz_offset": 28800,
    "device_platform": "android",
    "custom": { "host_bit": 64, "account_region": "cn", "dragon_device_type": "phone" },
    "...": "其余字段见 registrationPayload()"
  },
  "_gen_time": 1730000000000
}
```

响应不做完整 JSON 解析，只用正则提取：

```
"device_id": "<数字>"   （也兼容带引号）
"install_id": "<数字>"
```

任一缺失 → 「设备注册被拒绝」。成功后持久化到 `native-device-v1`，后续请求使用新身份。

### 4.2 搜索

```
GET https://api5-normal-sinfonlinea.fqnovel.com/reading/bookapi/search/tab/v?{签名参数}
```

在默认设备参数基础上覆盖版本字段：

```
version_code = 72232
manifest_version_code = 72232
update_version_code = 72232
pv_player = 72232
version_name = 7.2.2.32
```

业务参数：

| 参数 | 值 | 说明 |
| --- | --- | --- |
| `query` | 关键词 | 1–80 字符 |
| `count` | `20` | 每页条数 |
| `offset` | `(page-1)*20` | 无游标时的偏移 |
| `tab_type` | `11` / `19` | 11=短剧，19=漫剧 |
| `bookshelf_search_plan` | `4` | 固定 |
| `use_correct` | `false` | 固定 |
| `user_is_login` | `0` | 固定 |
| `bookstore_tab` | `16` | 固定 |
| `search_source` | `1` | 固定 |
| `search_source_id` | `clks###` | 固定 |
| `tab_name` | `feed` | 固定 |
| `is_first_enter_search` | `true` | 仅第一页 |
| `search_id` | 上一页响应 | 第二页起 |
| `passback` | 上一页响应 | 第二页起 |
| `last_search_page_query` | 关键词 | 第二页起 |

游标逻辑：

- 每个 `(类型, 关键词, 页码)` 缓存一份游标，TTL 5 分钟，最多 100 条；
- 第一页会把 `count=20` 的两批结果拼在一起（官方第一页只给少量结果）；
- `page > 1` 且没有游标 → 抛 `SearchSessionExpiredException`，界面提示从第 1 页重新搜索。

响应结构（截取关键字段）：

```json
{
  "code": 0,
  "search_tabs": [
    {
      "tab_type": 11,
      "has_more": true,
      "search_id": "...",
      "passback": "...",
      "next_offset": "20",
      "data": [
        {
          "lynx_data": "{\"cell_data\":[...]}",
          "video_data": [
            {
              "series_id": "123456",
              "title": "剧名",
              "cover": "https://...",
              "video_desc": "简介",
              "episode_cnt": 80,
              "rec_text": "全 80 集",
              "sub_title": "标签"
            }
          ],
          "search_high_light": { "title": { "text": "高亮后的剧名" } }
        }
      ]
    }
  ]
}
```

解析要点：

- 同时兼容 `code` 与 `Code`（部分接口返回大写）；
- 只取 `series_id` 为纯数字的卡片，合集/话题卡片直接跳过；
- 标题优先 `search_high_light.title.text`，否则 `video_data[0].title`；
- `has_more`、`search_id`、`passback`、`next_offset`（字符串）作为下一页游标；
- 空响应体直接抛「搜索接口暂不可用」，不再落到 JSON 解析错误。

### 4.3 详情主创与用户资料

#### 4.3.1 详情主创

```
POST https://api5-normal-sinfonlinea.fqnovel.com/novel/player/video_detail/v1/?{签名参数}
Content-Type: application/json; charset=utf-8
User-Agent: VIDEO_UA
```

版本字段与 4.2 相同，覆盖为 `72232` / `7.2.2.32`。请求体：

```json
{
  "series_id": "7696437705822981182",
  "biz_param": {
    "video_id_type": 1,
    "source": 5,
    "detail_page_version": 1
  }
}
```

- `video_id_type=1` 表示 `SeriesId`；`source=5` 表示 `FromDetailPage`，必须是整数，传字符串会返回 HTTP 400；
- 取 `data.video_data.main_creator_users[]`：`user_id`、`base_info.user_name`、`base_info.expand_user_avatar`（优先）或 `user_avatar`、`user_tag.sub_title_list[].text`（如「出品方」）、`user_relation.follow_user_num`；
- 同一用户可能重复出现，按 `user_id` 去重；只保留 `user_id` 为纯数字且名称为非空的项；
- 真人剧网站详情已有 `celebrities` 时不会调用；漫剧/AI剧通常在这里返回出品方，漫画样本没有该字段。

#### 4.3.2 官方用户资料

```
GET https://api5-normal-sinfonlinea.fqnovel.com/reading/user/profile/get/v1/?{签名参数}&user_id={用户ID}
```

同样使用 `72232` / `7.2.2.32` 版本字段，额外带 `user_id`。

- 取 `data.ugc_user`；
- `base_info`：`user_name`、`description`、`expand_user_avatar`（JPEG，优先）、`user_avatar`；
- `user_tag.sub_title_list[].text`：角色标签；
- `user_relation.follow_user_num`：粉丝数；
- 资料名称缺失时，作者页退回官方详情里的 `base_info.user_name`。

#### 4.3.3 详情页相关作品

```
GET https://api5-normal-sinfonlinec.fqnovel.com/reading/bookapi/plan/v:version/?{签名参数}
  &scene=10
  &from=detail_page_more_related
  &book_id={剧集ID}
  &device_level=3
  &limit=0
  &offset=0
  &pad_column_detail=4
  &session_uuid={随机 UUID}
```

- 必须使用 `version_code=73932`、`version_name=7.3.9.32`，旧版本不会返回相关作品；
- `book_id` 与网站详情的 `series_id` 相同；`from=detail_page_more_related` 决定返回详情页的关联片单；
- 取 `data[]` 中 `cell_name="相关作品"`（或 `show_type=547`）的 cell；
- 卡片在 `video_data[]`：`series_id`（打开详情用）、`title`、`cover`、`tag_info.text`（如「第2季」）、`sub_title_list[].content`（题材/播放量）；
- 解析时按 `series_id` 去重、过滤当前剧集和无效 ID；没有该 cell 时返回空列表；
- 没有可播放分集的预告条目（`vid` 为空且 `episode_cnt<=0`）保留在片单里并标记「预告」，点击只提示未上线，不进入详情；
- 接口失败不能阻断详情页，捕获异常后按「没有相关作品」处理。

### 4.4 播放信息与播放地址

#### 4.4.1 播放信息

```
POST https://api5-normal-sinfonlineb.fqnovel.com/novel/player/multi_video_model/preload/v1/?{签名参数}
Content-Type: application/json; charset=UTF-8
User-Agent: VIDEO_UA
```

请求体（`{video_id}` 为 1–30 位数字）：

```json
{
  "biz_param": {
    "detail_page_version": 0,
    "device_level": 3,
    "disable_digg_stat": false,
    "need_all_video_definition": true,
    "need_mp4_align": false,
    "use_os_player": false,
    "use_server_dns": false,
    "video_platform": 1024
  },
  "mixed_video_id_map": {
    "1004": ["{video_id}"]
  }
}
```

响应：

```json
{
  "Code": 0,
  "data": {
    "{video_id}": {
      "video_model": {
        "fallback_api": "https://..."
      }
    }
  }
}
```

- `code` / `Code` 必须为 0；
- `video_model` 可能是对象，也可能是 JSON 字符串（代码两者都兼容）；
- `fallback_api` 可能是字符串、对象里的 `fallback_api` 字段，或数组第一个元素；
- `fallback_api` 必须以 `https://` 开头；

历史 HAR4 抓包包含 player 路径、gzip 请求体和 dr_scene=player；这些是抓包证据，不是当前播放请求契约。当前实现恢复 0.9.0 的 preload 与未压缩请求体。新响应 video_list 是数组，编码/清晰度位于 video_meta，加密信息位于 encrypt_info。video_model.status=10 在本次抓包中是正常资源模型，不等同于顶层 API code。

#### 4.4.2 播放地址明细

```
GET {fallback_api}
User-Agent: VIDEO_UA
```

响应：

```json
{
  "video_info": {
    "data": {
      "key_seed": "Base64...",
      "video_list": {
        "1080p": {
          "main_url": "https://...",
          "codec_type": "h264",
          "quality_desc": "1080P",
          "height": "1080",
          "spade_a": "Base64..."
        }
      }
    }
  }
}
```

处理顺序：

1. 取 `video_info.data`，缺失 → 「没有播放资源」；
2. `video_list` 按 `main_url` 非空过滤，并排除 `codec_type` 为 `bytevc2` / `bvc2` 的清晰度（Android MP4 提取器可能只有声音没有画面）；
3. 清晰度数值从 `quality_desc` → `height` → `vheight` → `quality` → key 依次匹配 `2160/1440/1080/720/576/540/480/360`；`1920/1280/1024/854/640` 分别映射为 `1080/720/576/480/360`；
4. 选不超过 `maxQuality`（默认 1080）的最高清晰度；没有则取最低可用；
5. `key_seed` 非空时，用 `MediaCrypto.decryptUrl` 解密 `main_url`（SHA-512 派生密钥 + AES-CBC，去掉填充与尾部零字节）；
6. `spade_a` 非空时，用 `MediaCrypto.deriveKey` 派生分片解密密钥（AES-CTR）。

#### 4.4.3 视频流

```
GET {main_url}
User-Agent: VIDEO_UA
Referer: https://novel.snssdk.com/
Range: bytes={start}-{end}
Accept-Encoding: identity
```

要求：

- 必须返回 `206 Partial Content`；
- 必须带合法的 `Content-Range: bytes a-b/total`，且范围与请求一致；
- `total` 在一次播放内必须稳定，变化即「视频资源已变化」；
- 单次最多读 8 MB；
- 先读前 256 KB，解析 MP4 头；头跨段时继续读；
- 有 `spade_a` 时对样本做 AES-CTR 解密（`MediaCrypto.decrypt`）。

播放器不使用本地 HTTP 代理，直接把解密后的样本喂给 Media3。

### 4.5 弹幕

```
POST https://api5-normal-sinfonlineb.fqnovel.com/novel/commentapi/comment/list/{video_id}/v1/?{签名参数}
Content-Type: application/json; charset=UTF-8
User-Agent: VIDEO_UA
```

当前使用 recentOfficialValues 的完整设备参数；下列为其中的固定公共字段：

```
aid=8662
app_name=novelread
channel=update_64
device_platform=android
manifest_version_code=73932
update_version_code=73932
version_code=73932
version_name=7.3.9.32
```

请求体：

```json
{
  "comment_source": 601,
  "server_channel": 1000,
  "group_id": "{video_id}",
  "group_type": 30,
  "comment_type": 20,
  "sort": 1,
  "count": 90,
  "cursor": "",
  "aid": 8662,
  "compliance_status": 0,
  "business_param": {
    "book_id": "{series_id}",
    "start_offset_time": 0,
    "playlet_item_duration": 90000,
    "need_danmaku_guide_type": [1, 3, 4, 2]
  }
}
```

- `start_offset_time`：本段起点（毫秒），按 30 秒对齐；
- `playlet_item_duration`：本集总时长（毫秒），上限 24 小时。

响应（只列使用到的字段）：

```json
{
  "code": 0,
  "data": {
    "data_list": [
      {
        "comment": {
          "comment_id": "...",
          "common": {
            "group_id": "{video_id}",
            "status": "1",
            "content": { "text": "弹幕内容" }
          },
          "expand": { "offset_time": "12345" }
        }
      }
    ],
    "extra": { "next_query_danmaku_list_time": 30000 },
    "common_list_info": {
      "cursor": "{\"danmaku_count\":4428}"
    }
  }
}
```

解析规则：

1. `code` / `Code` 必须为 0；
2. `next_query_danmaku_list_time` 必须大于本段起点，否则报「弹幕时间段无效」；
3. 本段范围 `[start, min(next, duration))`；
4. 只保留 `group_id` 匹配、`status == "1"`、`offset_time` 落在本段内的评论；
5. `comment_id` 去重，单段最多 90 条；
6. 文本清理：去掉控制字符与 `\u2028/\u2029`，空白折叠，超过 180 字截断加 `…`；
7. 返回 `DanmakuPage(items, start, end, total)`，`total` 来自 `cursor` 里的 `danmaku_count`。

客户端行为：

- 每 30 秒一段，临近段尾自动预取下一段；
- 快进/拖动后会从新位置重新取段；
- 同 3 秒内相同文本合并为「文字 ♥ N」（`DanmakuPage.merge`）；
- 关掉弹幕后不再请求、不再绘制，开关保存在 `native-player-v1` 的 `danmaku`。

### 4.6 扫码登录

代码位置：`ContentRepository.createLoginQr()`、`pollLoginQr()`、`refreshUser()`；会话存档在 `app` 模块的 `AuthStore`。原手机号验证码接口已移除。

#### 4.6.1 获取二维码

```
GET https://hongguoduanju.com/passport/web/get_qrcode/?{查询参数}
User-Agent: QR_UA
Accept: application/json, text/plain, */*
Accept-Language: zh-CN,zh;q=0.9
Referer: https://hongguoduanju.com/
```

查询参数：

| 参数 | 值 |
| --- | --- |
| `aid` | `513755`（红果网页） |
| `next` / `request_host` | `https://hongguoduanju.com/` |
| `language` | `zh` |
| `account_sdk_source` | `web` |
| `passport_jssdk_version` / `p_js_v` | `2.4.13` |
| `passport_jssdk_type` | `normal` |
| `is_from_ttaccountsdk` | `1` |
| `p_js_t` / `p_zt` / `p_ver` / `p_bd` | `pro` / `3.3.5` / `1.0.29` / `1.0.1.20` |

网页捕获里还有 `account_sdk_source_info`、`msToken`、`a_bogus`；当前客户端不需要这些反爬字段也能取到二维码。响应 `data`：

| 字段 | 说明 |
| --- | --- |
| `token` | 一次性二维码 token，只用于后续轮询 |
| `qrcode` | PNG data URI，直接交给界面显示 |
| `expire_time` | 秒级过期时间 |
| `qrcode_index_url` | 手机扫码后的落页地址，含 token，不写入界面 |

`token`、`qrcode_index_url` 和图片内容都按登录凭据处理，不写日志或错误提示。

#### 4.6.2 轮询扫码状态

```
GET https://hongguoduanju.com/passport/web/check_qrconnect/?next=...&aid=513755&account_sdk_source=web&token={二维码 token}
Cookie: {get_qrcode 设置的 Cookie}
x-tt-passport-csrf-token: {Cookie 中的 passport_csrf_token}
```

响应 `data.status`：

| 状态 | 界面行为 |
| --- | --- |
| `new`（兼容等待态） | 继续轮询 |
| `scanned` | 提示「已扫码，请在手机上确认登录」 |
| `confirmed` | 跟随 `data.redirect_url`，建立会话 |
| `expired` | 提示过期并自动刷新二维码 |

字符串 `confirmed` 直接按确认处理；数值 `3` 或未知状态只有带 `redirect_url` 时才按确认，避免把过期态误判为登录成功。

#### 4.6.3 登录落地与账号资料

确认后对 `redirect_url` 发起 GET，再访问一次 `https://hongguoduanju.com/` 补全 Cookie。整个过程用独立的扫码 Cookie 存储，按域名/路径匹配；落地失败时保留轮询响应已经下发的 Cookie。

登录态至少需要 `sessionid`、`sessionid_ss`、`sid_tt` 或 `sid_guard` 之一。拿到 Cookie 后：

1. 先把 Cookie 与 `X-Tt-Token` 暂存为本地会话；用户 ID 优先取 `uid_tt` / `uid_tt_ss` / `sso_uid_tt` / `sid_tt`，没有时用 `sessionid` 的 UUID 哈希作为本地标识；
2. 调用 4.6.4 的账号资料接口；
3. 资料接口成功则更新昵称/头像/简介；暂时失败则保留通用登录提示，不伪造资料；
4. 服务端明确返回未登录/过期时清空临时会话并提示重新扫码。

#### 4.6.4 刷新账号资料

```
GET https://api5-normal-sinfonlineb.fqnovel.com/reading/user/info/v?{签名参数}
Cookie: {登录会话 Cookie}
X-Tt-Token: {登录响应头}
```

查询参数使用 `version_code=73932` / `version_name=7.3.9.32`，并带 `check_idfa=false`、`not_need_perm=false` 等兼容字段。响应 `code == 0` 时取 `data.user_name`、`data.avatar_url` 或 `data.expand_avatar_url`、`data.description`；手机号只保存脱敏值。刷新失败保留原资料并提示，确认未登录/过期时才清空 `native-auth-v1`。

资料响应里的数字 `data.req_id` 会保存为会话的 `accountId`，仅用于 4.7.2 的收藏请求，不展示、不写日志；接口未返回时保留会话中已有的值。

### 4.7 账号记录与预约

代码位置：`ContentRepository.accountHistory()`、`accountFavorites()`、`accountReservations()`、`setReservation()`；界面在「我的」的「最近观看 / 收藏 / 预约」分栏。

以下请求都使用 4.6 建立的登录会话：`Cookie` 与 `X-Tt-Token` 自动附加到 `*.fqnovel.com` 官方域名，查询串由 `Signer` 按 App 版本参数签名，并带 `lc: 101`、`x-ss-dp: 8662`。网络或单个接口失败只影响对应分栏，匿名浏览、搜索、详情和播放继续可用；服务端确认登录过期时统一清空会话并回到未登录状态。

#### 4.7.1 观看记录

```
GET https://api5-normal-sinfonlineb.fqnovel.com/reading/bookapi/read_history/list/v?{签名参数}
  &offset=0&limit=30&book_type={2|3|4|5}
  &query_soft_deleted=false&is_first_load=false
  &last_min_read_timestamp_ms=0&full_field=false
```

客户端按 `book_type=2/3/4/5` 分别请求后合并，`read_timestamp_ms` 倒序，按 `video_data.series_id`（缺失时用 `book_id_str`）去重。响应 `data.data_list[]` 中使用的字段：

| 字段 | 说明 |
| --- | --- |
| `video_data.series_id` | 优先作为详情页剧集 ID |
| `book_id_str` / `book_id` | 无嵌套 `series_id` 时的剧集 ID 兜底 |
| `book_name` / `video_data.series_title` | 标题 |
| `thumb_url` / `video_data.series_cover` | 封面 |
| `vid` / `vid_index` | 最近观看的视频 ID 与分集下标 |
| `current_play_position` / `duration` | 续播位置与时长，毫秒 |
| `read_timestamp_ms` | 最近观看时间，用于排序 |
| `episode_cnt`、`category_list` | 集数与题材，用于卡片角标 |

单个 `book_type` 失败不影响其它类型；全部失败才向界面报错。列表只读，不回写观看进度。

#### 4.7.2 短剧收藏

```
GET https://api5-normal-sinfonlineb.fqnovel.com/reading/bookapi/bookshelf/video/list/v?{签名参数}
  &target_user_id={accountId}
```

`target_user_id` 取自 4.6.4 的 `data.req_id`；资料里没有该值时不会猜测其它 ID，收藏页会提示先刷新账号资料。响应 `data.video_shelf_info[]`；收藏条目可能把剧集字段放在顶层、`video_data`、`series_info` 或 `book_info` 中，客户端会依次读取 `series_id` / `book_id`、标题、封面、简介、集数和题材标签。`data.max_bookshelf_cnt` 只表示容量，不参与列表展示。

列表只读；在电视端详情页收藏仍写入本机 `Library`，不会调用收藏写接口。

#### 4.7.3 预约列表

```
GET https://api5-normal-sinfonlineb.fqnovel.com/reading/user/subscribe/list/v1/?{签名参数}
  &offset=0&limit=30&is_online={false|true}
  &tab_type=13&subscribe_order_type=0&swipe_type=0
  &need_calendar_schema=false&subscribe_offset=0
```

客户端分别请求 `is_online=false` 和 `is_online=true`，合并后待上线优先、再按预计上线时间排序。响应 `data.subscribe_items[]`：

| 字段 | 说明 |
| --- | --- |
| `item_id` | 预约项 ID；详情、取消预约都使用它 |
| `item_type` | 短剧预约项为 `14`，取消时原样带回 |
| `name` / `cover` / `item_desc` | 标题、封面、简介 |
| `is_online` | 是否已上线；`false` 时没有可播放分集 |
| `has_subscribed` | 当前是否在预约中 |
| `expected_rec_text` | 上线时间提示文案 |
| `expected_publish_time` / `schedule_publish_time` / `publish_time` | 预计上线时间，用于排序 |
| `tag_info.text`、`categories` / `tags` | 题材与角标 |

待上线条目只进入预约详情页，显示预约/取消预约按钮，不请求播放信息。

#### 4.7.4 预约与取消预约

```
POST https://api5-normal-sinfonlineb.fqnovel.com/reading/bookapi/search/uncover_subscribe/v:version/?{签名参数}
Content-Type: application/json

{
  "item_id": 444,
  "item_type": 14,
  "op_type": 1,
  "item_id_str": "444",
  "wish_list_all_del": 0,
  "shark_param": {
    "enter_from": "ShortSeriesActivity",
    "previous_page": "MainFragmentActivity",
    "page_list": "MainFragmentActivity,ShortSeriesActivity"
  }
}
```

`op_type=1` 表示预约，`op_type=2` 表示取消；客户端每次只改一个预约项，`wish_list_all_del=0` 避免清空其它预约。响应 `code == 0` 即成功，界面随后刷新预约列表；失败时保留原状态并提示。HTTP 401/403 或服务端明确的未登录/过期信息会清空登录会话，未登录时不显示可写的预约入口。

## 5. 自动更新

### 5.1 更新清单

```
GET https://github.com/wanwdbb/hongguoTV/releases/latest/download/update.json
User-Agent: HongguoTV-Updater
Cache-Control: no-cache
```

加速顺序（逐个尝试，直连优先）：

1. `https://github.com/...`
2. `https://gh-proxy.com/https://github.com/...`
3. `https://ghfast.top/https://github.com/...`
4. `https://ghproxy.vip/https://github.com/...`

约束：

- 只有直连返回 404 才认定「尚无可用的自动更新版本」；镜像 404 只算本次尝试失败；
- 清单最大 64 KB；
- 只允许 HTTPS，且主机必须在白名单内：`github.com`、`release-assets.githubusercontent.com`、`objects.githubusercontent.com` 以及上述加速器主机；
- `update.json` 结构：

```json
{
  "schema": 1,
  "packageName": "com.hongguotv.nativeapp",
  "versionCode": 10014,
  "versionName": "0.9.13",
  "minSdk": 26,
  "tag": "kotlin-v0.9.13",
  "apk": {
    "name": "hongguotv-0.9.13-release.apk",
    "size": 2702621,
    "sha256": "819aabad...f32d86"
  },
  "notes": "更新说明（最多 1500 字）"
}
```

校验规则（`AppUpdate.parse`）：

| 字段 | 要求 |
| --- | --- |
| `schema` | 必须为 `1` |
| `packageName` | 必须为 `com.hongguotv.nativeapp` |
| `versionCode` | 1 ～ `Int.MAX_VALUE` |
| `versionName` | `[0-9][0-9A-Za-z.+-]{0,63}` |
| `minSdk` | 26 ～ 100 |
| `tag` | `kotlin-v[0-9][0-9A-Za-z.+-]{0,80}` |
| `apk.name` | `hongguotv-[0-9][0-9A-Za-z.+-]{0,80}-release\.apk` |
| `apk.size` | 1 ～ 80 MB |
| `apk.sha256` | 64 位小写十六进制 |
| `notes` | 可选，截断到 1500 字 |

### 5.2 APK 下载

```
GET https://github.com/wanwdbb/hongguoTV/releases/download/{tag}/{fileName}
```

- 同样按「直连 + 加速器」顺序重试；
- 下载前若本地已有同大小、同 SHA-256 的文件则直接复用；
- 流式写入 `.part` 文件，边下边校验长度与 SHA-256，完成后原子改名；
- 长度不符、哈希不符、超过 80 MB 都会中止；
- 取消后不会自动换镜像重试。

## 6. 局域网服务

两个服务都只绑定电视的局域网地址，使用随机端口。手机输入服务在窗口关闭时立即失效；记录备份服务最长 5 分钟。

### 6.1 手机输入搜索

| 项目 | 内容 |
| --- | --- |
| 地址 | `http://<电视IP>:<端口>/HongGuoTV` |
| GET | 返回输入表单页 |
| POST | `/HongGuoTV/submit`，`application/x-www-form-urlencoded`，唯一字段 `query`（1–80 字符） |
| 限制 | 固定 token；一次性会话；Host 必须精确匹配；Origin 只允许缺省、`null` 或本机地址；请求体上限 4 KB |
| 结果 | 成功后关闭服务并回调搜索关键词；重复提交返回 `410` |

### 6.2 记录备份与恢复

| 项目 | 内容 |
| --- | --- |
| 地址 | `http://<电视IP>:<端口>/<32位十六进制token>` |
| GET `/<token>` | 备份/恢复页面 |
| GET `/<token>/download` | 下载 `hongguotv-library.json`（`application/json; charset=utf-8`，一次性） |
| POST `/<token>/import` | `Content-Type: application/json`，请求体 ≤ 2 MB，需在电视上确认后合并 |
| 限制 | Host 精确匹配；Origin 必须缺省或等于本机地址；`Sec-Fetch-Site: cross-site` 拒绝；随机 token；下载限一次；上传成功后服务关闭；5 分钟过期 |

备份内容：收藏、观看位置、稍后看、隐藏推荐、搜索记录、播放设置；不包含平台账号。

## 7. 图片与封面

封面地址直接来自网站/搜索接口的 `series_cover` / `cover` 字段，经 `cleanUrl` 还原转义后由 `ArtworkLoader` 直接 GET：

- 不附加 Referer 或签名；
- 失败时回退到占位图；
- 播放器竖屏背景复用同一张封面（`CENTER_CROP` + 模糊）。

## 8. 典型调用流程

### 8.1 启动

```
启动进入推荐
  → 首次启动后台注册设备
  → 推荐：GET /category/{real-drama|comic-drama|ai-drama}?page=N
  → 打开「首页」：GET /category?tab=1&sort_type=1
```

### 8.2 搜索

```
GET search/tab/v（第 1 页，is_first_enter_search=true）
  → 取 search_id/passback/next_offset
  → 第 2 页带游标
  → 失败或空结果时，短剧回退网页 /search/{keyword}
```

### 8.3 播放

```
GET /detail?series_id=...（拿 vid_list）
  → POST multi_video_model/preload/v1/（未压缩 JSON，7.1.3.32，独立进程播放身份，无账号凭据）
  → 解析直返 video_list；无可用资源时 GET fallback_api
  → 解密 main_url（key_seed）
  → GET main_url（Range + Referer）
  → 有 spade_a 时按样本 AES-CTR 解密
```

### 8.4 弹幕

```
POST comment/list/{video_id}/v1/（start=0，duration=本集时长）
  → 播放到段尾前预取 next_query_danmaku_list_time 开始的下一段
  → 拖动进度后从新位置重新取段
```

### 8.5 更新

```
GET releases/latest/download/update.json（直连 → 加速器）
  → 比较 versionCode 与 minSdk
  → GET releases/download/{tag}/{file}
  → 校验大小与 SHA-256
  → 交给系统安装器
```

## 9. 已知限制与风险

- **网站结构随时可能变**：页面解析依赖 `_ROUTER_DATA`、`loaderData` 键名与字段名；字段缺失会给出中文错误而不是崩溃。
- **App 接口有风控**：未注册设备的搜索/弹幕可能返回空响应；身份失效时需要重新注册。
- **签名算法是移植实现**：`x-gorgon` / `x-helios` / `x-medusa` 随官方版本变化，升级后需重新对照。
- **视频清晰度**：`bytevc2` / `bvc2` 在部分 Android 设备上无画面，代码已主动跳过。
- **播放地址有时效**：`fallback_api` 与 `main_url` 都是临时地址，不能缓存复用；`total` 变化会中止当前播放。
- **更新镜像只影响速度**：APK 仍由 SHA-256 与安装器签名双重校验，镜像无法注入内容。
- **局域网服务仅限本机网络**：依赖随机端口、短时 token 与 Host/Origin 校验，不要暴露到公网。

## 10. 调试探针

`core` 里的 `Probe.kt` 提供命令行探针，便于在不启动界面的情况下验证接口：

```
--ranking            四个榜单逐页校验
--category           四个分类 + 首个题材 + 第二页
--recommend          推荐候选池
--search <关键词>    搜索（默认「总裁」）
--comic              搜索改用漫剧
--register           设备注册
--danmaku <剧集ID>   弹幕分段与总数
```

运行方式见 `core` 的 Gradle 配置；探针只读接口，不写本机记录。
