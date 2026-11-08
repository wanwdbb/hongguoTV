# GitHub 更新下载线路

设置 → 应用与更新 → GitHub 下载线路。

- 自动：保持原行为，先直连，失败再按内置代理顺序尝试。
- 直连 GitHub：不尝试代理。
- 代理 gh-proxy.com / ghfast.top / ghproxy.vip：直接使用选中代理，不先等待直连，不暗中切换其他线路。

检查更新清单和下载APK均使用所选线路，偏好保存于app_updates/github_route。切换线路取消旧请求、清除重试等待；可重新检查或下载。文件长度、SHA256、包名、版本和Pets签名校验保持有效。只允许内置HTTPS代理。未进行代理速度排名或设备现场验收。

236项核心测试通过，新增覆盖直连、指定代理跳过直连、自动顺序及未知配置回退。构建日志 outputs/android-build-update-route.log。
