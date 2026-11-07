# 更新安装重构

应用最低 Android 8，更新渠道仍为 wanwdbb/hongguoTV GitHub Releases。参考 tsukiyo-zero/hongguoTV 的 AppUpdater 和 UpdateApkSignatures 安装流程，保持 GPL-3.0。

APK保存 cache/updates/update.apk，经 .part 下载、长度与SHA256校验后落盘；安装前再次验证元数据和当前签名。移除 WRITE_EXTERNAL_STORAGE、MANAGE_EXTERNAL_STORAGE、requestLegacyExternalStorage 和对应授权回调。旧公共Downloads文件不主动删除。

通过FileProvider只暴露updates缓存目录，使用FLAG_GRANT_READ_URI_PERMISSION和ClipData；先尝试ACTION_VIEW，再尝试常见Android/Google/MIUI安装器组件。不能把启动安装器视为已安装成功。不具备静默安装能力。不引入Android6/7旧文件URI兼容。

关闭自动更新可手动检查；选择稍后延后24小时；检查和下载失败30分钟后重试。仅前台空闲检查，播放/后台取消请求。默认每6小时检查。新方案与参考一致，不保留旧版计费网络限制和跳过版本选项。

验证：235项核心测试，覆盖下载中断、截断、超长、SHA256错误、缓存篡改和更新清单约束；Release构建/lint。真实厂商安装器与系统权限页面未现场验证。
