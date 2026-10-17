# GitHub 自动发布与电视应用内更新

公开渠道：`wanwdbb/hongguoTV`。首次需要手动把带更新功能的正式 APK 安装到电视，此后不用逐次传文件。

## 电视上的行为

- 前台浏览时每 6 小时检查一次最新正式版；首次进入应用延迟 8 秒，避免抢首屏。播放或离开前台会取消更新请求，返回浏览页后可继续下载。
- 默认自动检查并下载，设置 → 版本与更新可以关闭，也可以手动检查。下载失败保留当前应用；部分文件清理，自动下载重试至少间隔 30 分钟。
- 下载完显示版本信息，选择「安装新版」。第一次需允许本应用安装未知来源应用，之后调用系统安装确认。普通 Android 8 没有静默安装权限，仍须确认。
- 选择「稍后」推迟该版本提醒一天；不会中断正在播放的视频。同版本、旧版本和要求更高 Android 版本的安装包不会自动安装。
- APK 写入应用私有缓存，仅临时授权系统安装程序读取。下载使用 HTTPS、文件长度和 SHA-256 校验，并验证 APK 实际包名、版本号、最低 SDK 和当前安装应用的签名；不在 APK 中保存 GitHub 令牌。

## 从推送到发布

1. 向 `wanwdbb/hongguoTV` 的 `main` 分支推送改动。
2. `.github/workflows/release.yml` 在 ubuntu runner 上执行 JVM 测试、Release lint 和正式签名构建。签名密钥从仓库 Actions Secrets 恢复。产物直接命名为 `hongguotv-<版本>-release.apk`（如 `hongguotv-0.9.0+1-release.apk`），同时作为 `hongguotv-apk` 构件上传，可在该次 Actions 运行页面直接下载，不必等发布完成。
3. 构建成功后，`kotlin-tv/distribution/publish-tv-update.py` 先创建草稿，上传同名 APK、源码 ZIP、`update.json` 与 `SHA256SUMS`，确认 GitHub 附件摘要一致，再正式发布并设为 latest。构建失败不替换旧版。
4. 电视通过 Release 的 `latest/download/update.json` 获取新版本，无需 GitHub 登录。

如果仓库是从别处 fork 来的，GitHub 默认不会在此类仓库里运行自动触发的工作流。首次需要打开仓库的 Actions 页面，点击「I understand my workflows, go ahead and enable them」，之后 `push` 才会自动构建；手动 `workflow_dispatch` 不受此限制。另外，用个人访问令牌推送新建或修改过的 `.github/workflows/` 文件时，令牌必须有 `workflow` 权限，否则推送会被拒绝。

## 签名配置

在 `wanwdbb/hongguoTV` 仓库的 Settings → Secrets and variables → Actions 添加以下 Repository secrets：

- `HONGGUOTV_KEYSTORE_B64`：发布密钥 `Pets.jks` 的 Base64 编码（单行，无换行）。
- `HONGGUOTV_STORE_PASSWORD`：keystore 口令。
- `HONGGUOTV_KEY_ALIAS`：`pets`。
- `HONGGUOTV_KEY_PASSWORD`：私钥口令（与 keystore 口令相同则填相同值）。

签名私钥只在运行签名构建的临时目录中恢复，任务结束删除；源码和 APK 均不包含这些凭据。不要把密钥提交进仓库，也不要在 APK 中嵌入 PAT。

本仓库发布签名与旧版公开发布签名不同：已安装旧签名 APK 的设备需要卸载后重装一次本仓库的正式包，之后才能通过应用内更新覆盖安装。

## 版本规则与本地构建

云端 `versionCode = 10000 + release workflow run_number`，显示版本为 `0.9.0+run_number`。同一次已发布构建的重跑不覆盖原 APK；旧构建重跑也不能取代更新的 latest。保留此工作流的递增编号，后续调整版本显示名称时仍维持 versionCode 单调递增。

本地未传环境变量时生成 0.9.0 / versionCode 9，供首次验证，产物为 `kotlin-tv/app/build/outputs/apk/release/hongguotv-0.9.0-release.apk`。复现云端源码时，应从该 Release 的 `update.json` 读取版本，传入 `HONGGUOTV_VERSION_CODE` 和 `HONGGUOTV_VERSION_NAME`；本地低版本号的 APK 无法覆盖较高云端版本。正式分发以云端签名包为准。

电视系统没有安装器、禁止未知来源或无法访问 GitHub 时，自动更新不能完成；旧版仍可继续使用。最低 API 26 不等于 Android 8 实体电视已验收。
