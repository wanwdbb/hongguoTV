# 更新流程对齐参考项目（2026-11-08）

当前实现对照 tsukiyo-zero/hongguoTV main 的 AppUpdater.kt / AppUpdates.kt，并于本次修改时在线复核，参考文件未变化。

## 行为
- 自动更新默认开启。每次启动应用后等待8秒检查一次，不受上次检查6小时限制；若启动时正在推荐播放，仅检查版本信息，下载和安装提示仍等待浏览空闲。随后每15秒判断一次常规更新条件。
- 仅前台片库/搜索/设置等 CATALOG 页面、没有其他工具弹窗且无更新任务时执行自动检查。
- 距上次尝试检查达到6小时才联网检查；时钟回拨也会重新检查。下载失败后，已发现的版本等待30分钟重试。
- 进入播放、换集或应用暂停会取消更新任务；播放时不下载、不弹窗，启动版本检查除外。
- 有兼容新版时自动下载到私有缓存，校验大小、SHA256、包名、版本、签名；校验后显示安装确认。手动更新页通过“检查更新”触发，完成后按钮变为“安装新版”。
- 自动弹窗仍使用参考项目的版本与更新页面，保留自动检查开关；选择“稍后”将该版本提醒延迟24小时。同一进程对同一版本不重复自动提示。
- 用户确认后才启动系统安装器，保留原包名 com.hongguotv.nativeapp 与 Pets 签名。

## GitHub 源适配
1. 查询 https://api.github.com/repos/wanwdbb/hongguoTV/releases/latest。
2. 拒绝草稿和预发布，检查版本标签及 update.json 资产。
3. 读取该标签下 releases/download/{tag}/update.json，校验清单标签与发行版相符。
4. 比较 versionCode、minSdk，按清单下载同一仓库下的APK。

检查API、清单和APK共用用户选择的线路。自动模式按直连及代理顺序尝试，指定代理只使用所选线路；保留三条代理供选择，不保证所有第三方代理支持GitHub API或始终可用。

## 验证
新增正式发行版、预发布/草稿拒绝、标签路径和清单标签一致性测试。构建日志 outputs/android-build-reference-update.log；模拟器结果见 outputs/emulator-debug/reference-update-test.md。

本次按用户要求推送并通过GitHub自动构建发布。
