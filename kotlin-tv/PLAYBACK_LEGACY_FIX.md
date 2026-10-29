# 播放兼容修复验证

## 基准与实现

- 用户确认可用基准是修复版 0.9.0，提交 `7cb06f1eaa9249d744d726e0122ff69fdd9403d1`。原仓库 N3urda/hongguoTV 的 0.8.0 旧 batch 链路不能作为可用基准。
- 恢复 `multi_video_model/preload/v1/`、0.9.0 原始 JSON 字节、7.1.3.32 参数及原有签名算法。停止新签名兼容调查。
- 播放 device_id 在进程启动时随机生成并保持稳定，iid 使用 0.9.0 固定值；请求参数及签名内部设备字段取同一播放上下文。
- 搜索注册仍使用持久化 DeviceIdentity，播放不再因空响应注册或替换它。登录状态继续供账号及历史接口使用，播放和地址解析不附带登录凭据。
- 保留严格分集匹配、可用直返模型解析、电视不兼容编码过滤、缓冲恢复、按分集和位置取更远进度、更新目录 `/storage/emulated/0/Downloads`。

## 本地验证

- 191 项核心测试通过；签名固定向量、播放请求字节/摘要、账号隔离、搜索身份变化不影响播放均覆盖。
- `:app:assembleRelease :app:lintRelease` 成功。
- 候选版本：0.9.21-legacy，versionCode 10022。
- 证书 SHA256：`67e186e5674a9346dcf261457dec514c8bb0df9d99173f8cc8b404a98b0d677c`。
- APK SHA256：`94e0e01671c47ca05b3a3470740d6d611f39171de5730e353e23d2c1fe5f7ab2`。
- MuMu 127.0.0.1:5559 覆盖安装保留数据。最新 release 进程 PID 2243，23:08:27 第一集首帧 692ms，23:10:47 第二集预加载首帧 127ms，23:11:08 第三集首帧 1581ms，实际截图确认视频画面。

## 验证边界

首帧、头部及 Range 成功不代表持续播放稳定。早期候选曾在数分钟后返回空响应，因此必须继续进行持续播放、多个进程及多分集验证；以补充结果为准，不将该候选直接标为彻底修复。
后续设备诊断检测到已有账号会话（session_present=true），两轮检查均保持 session_unchanged=true；未重新执行登录操作。电视实机尚未验证。

## 补充运行结果（2026-10-29）

- release PID 2243 从 23:08:27 播放至 23:13:36，5 分钟以上，涉及第 1–5 集；自动续播、上一集、下一集均有首帧证据，期间未记录播放失败。
- 两个独立诊断进程各对 3 集请求两轮，共 12 次播放模型及视频头/Range 读取全部成功。第二轮耗时较长，属于需要继续观察的网络延迟，不能仅凭成功结果称无加载问题。
- 两轮均 automatic_registrations=0、identity_unchanged=true、session_present=true、session_unchanged=true。
- 诊断结束后恢复原证书 release 0.9.21-legacy，PID 29716 于 23:15:44 成功首帧（1882×1080），本次耗时约 10.25 秒，说明启动加载延迟仍有出现。
- 证据保存在工作目录 `.legacy-fix/latest-playback.log`、`route-probe-process1.txt`、`route-probe-process2.txt`、`restarted-playback.log`、`latest-screen.png`。

结论：0.9.0 播放契约恢复后的本地候选通过上述有限测试，尚不能保证长期及电视实机不再出现间歇性失败。候选未发布。
`n- 恢复后的 release 在 23:18:25 自动续播第二集，1924×1080，预加载首帧 112ms，尚无播放失败日志。
