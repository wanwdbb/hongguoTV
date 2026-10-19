#!/usr/bin/env python3
"""Runs only in the public source repository after release validation succeeds."""
import hashlib, json, os, re, shutil, subprocess
from pathlib import Path
root=Path.cwd(); out=root/'outputs'; out.mkdir(exist_ok=True)
def run(*args): return subprocess.check_output(args,text=True).strip()
version=os.environ['HONGGUOTV_VERSION_NAME']; code=int(os.environ['HONGGUOTV_VERSION_CODE']); tag=os.environ['UPDATE_TAG']
assert re.fullmatch(r'0\.9\.\d+',version) and tag=='kotlin-v'+version
assert code==10001+int(version.split('.')[2])
apk=out/f'hongguotv-{version}-release.apk'; shutil.copy2(root/'kotlin-tv/app/build/outputs/apk/release'/apk.name,apk)
buildtools=Path(os.environ['ANDROID_HOME'])/'build-tools/36.0.0'
signature=run(str(buildtools/'apksigner'),'verify','--print-certs',str(apk))
assert '67e186e5674a9346dcf261457dec514c8bb0df9d99173f8cc8b404a98b0d677c' in signature
badging=run(str(buildtools/'aapt'),'dump','badging',str(apk))
assert f"versionCode='{code}'" in badging and f"versionName='{version}'" in badging and "sdkVersion:'26'" in badging
commit=run('git','rev-parse','HEAD')
manifest={'schema':1,'packageName':'com.hongguotv.nativeapp','versionCode':code,'versionName':version,'minSdk':26,'tag':tag,
 'sourceCommit':commit,'apk':{'name':apk.name,'size':apk.stat().st_size,'sha256':hashlib.sha256(apk.read_bytes()).hexdigest()},
 'notes':'推荐改成进入就自动连播的页面、不再显示卡片：顶部导航、本集信息与底部操作行随时可调出，↑/↓ 换一部、确认 暂停/播放、菜单 打开菜单、连按两次返回退出，切到别的页会自动保存并停止，「换一批」在后台换、不打断；推荐会提前缓存前后两部的详情与接下来要播的那一集，↑/↓ 换部更快；直连 GitHub 超时会自动改用加速线路；应用内自动检查和下载更新。'}
(out/'update.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
source=out/f'hongguotv-{version}-source.zip'; run('git','archive','--format=zip',f'--prefix=hongguotv-{version}/','--output',str(source),'HEAD')
notes=out/'release-notes.md'; notes.write_text(f'''Android 8.0+ 原生独立版，版本 {version}（versionCode {code}）。

自动检查、下载新版并校验签名，确认后由系统安装。首次安装本版后，后续更新无需手动传 APK。直连 GitHub 超时或失败时自动改走加速线路，安装包仍做长度与 SHA-256 校验。电视需允许本应用安装未知来源应用；普通 Android 8 仍需确认系统安装。

顶部新增「推荐」并作为启动默认页，顶部导航顺序为 推荐、首页、搜索、分类、排行榜、收藏、最近观看、设置。推荐参考混合内容池规则：默认真人剧 50%、漫剧 30%、AI 剧 20%，按收藏（每部 +4）与看过 30 秒以上（每部 +1）的题材兴趣排序，7 天内看过的降权、整剧看完的不再推荐，每批预留 20% 给没看过或陌生题材，单个类型抓取失败时由其它类型补足。

本版的推荐改成「进入即播」的连播页，不再显示视频卡片：打开应用或回到推荐会自动接着播（有记录就从上次看到的位置继续，否则从这一批的第一部开始），播放中按 ↑ 是上一部、↓ 是下一部，切走的那一部会先存下位置，返回时继续。一部剧的最后一集播完并且开着「自动连播」时，自动接推荐里的下一部；片单快看到头会在后台再续一批，「换一批」也在后台完成，当前这一部留在画面上，取到新的一批再切过去，所以推荐可以一直连着看。

画面上叠了一层参考版式的浮层：顶部是同一排导航入口，左上是本集信息（第 N 集、剧名、共 N 集、类型标签、简介），底部是操作行（暂停播放 / 下一集 / 合集 / 追剧 / 倍速 / 清晰度 / 更多设置 / 换一批）加进度条、时间与按键提示。浮层收起时 确认 暂停/播放、左右 快退/快进、↑/↓ 换一部、菜单 叫出浮层；浮层打开时方向键在导航与操作行之间移动、确认 执行、返回 收起；连按两次返回退出应用。播放约 4.5 秒后浮层自动隐藏，暂停中或浮层里有焦点时保持显示。切到首页、分类、排行榜等其它页会立即停止播放并保存位置，回到推荐接着看这一部。普通播放（首页、详情、收藏、最近观看）的界面与按键保持不变；播放失败时仍可一键「换下一部」。

本版给播放器加了预缓存：推荐连播播放稳定后，会在后台把前后两部的详情、续播的那一集（没看过的剧就是第一集）和它的首段视频先取好，按 ↑/↓ 换部时直接用手上这份内容，不再等详情接口、播放接口和首屏缓冲；一集快播完时会再刷新一次，自动接下一部时也已经就绪。预取只在非计费网络下进行，换一批、切换网络或退出播放时立即释放，内存占用只有几 MB；已经预取过的剧再次打开时，详情也不会重复请求。

设置 → 推荐里可切换「混合推荐 / 只看真人剧 / 只看漫剧 / 只看 AI 剧」，并可「重置兴趣」：收藏与观看记录保留，重置前的观看不再累计兴趣。隐藏过的剧不会出现在推荐里。

顶部「分类」按网站分类页浏览：真人剧、漫剧、AI 剧、漫画四种内容类型，真人剧 24 个题材、漫剧与 AI 剧各 8 个题材，片单直接显示并支持翻页；同一分类的片单 10 分钟内直接复用。

保留安静连播、完整竖版海报和所有本机记录。使用本仓库专用的发布签名；与旧版签名不同，已有旧签名安装需卸载后重装一次。

自动构建的源码提交：{commit}。测试与 Release lint 通过后才发布。

- APK：电视安装文件。
- source.zip（hongguotv-{version}-source.zip）：与 APK 对应的完整原生源码，GPL-3.0。
- update.json：电视更新元数据，含版本、文件长度与 SHA-256。
- SHA256SUMS：附件校验和。

复现发布构建时设置 HONGGUOTV_VERSION_CODE={code} 和 HONGGUOTV_VERSION_NAME={version}，并使用自己的签名配置。Android 8 实体电视兼容性需实机确认。
''')
checks=out/'SHA256SUMS'; checks.write_text(''.join(hashlib.sha256(p.read_bytes()).hexdigest()+'  '+p.name+'\n' for p in [apk,source,out/'update.json']))
# Rerunning a published build must not overwrite the APK installed on TVs.
existing=subprocess.run(['gh','release','view',tag,'--json','isDraft'],text=True,capture_output=True)
if existing.returncode==0:
 if not json.loads(existing.stdout)['isDraft']:
  print('This build is already published; leaving its assets unchanged.'); raise SystemExit(0)
else: run('gh','release','create',tag,'--target',commit,'--draft','--title',f'红果 TV {version}','--notes-file',str(notes))
assets=[apk,source,out/'update.json',checks]
run('gh','release','upload',tag,*map(str,assets),'--clobber')
info=json.loads(run('gh','release','view',tag,'--json','assets'))
for p in assets:
 a=next(a for a in info['assets'] if a['name']==p.name)
 assert a['state']=='uploaded' and a['size']==p.stat().st_size and a['digest']=='sha256:'+hashlib.sha256(p.read_bytes()).hexdigest()
# Concurrent older builds must never replace a newer release as latest.
latest=subprocess.run(['gh','release','view','--json','tagName'],text=True,capture_output=True)
make_latest=True
if latest.returncode==0:
 m=re.fullmatch(r'kotlin-v0\.9\.(\d+)',json.loads(latest.stdout)['tagName'])
 if m and int(m[1])>int(version.split('.')[2]): make_latest=False
run('gh','release','edit',tag,'--draft=false','--latest='+str(make_latest).lower(),'--notes-file',str(notes))
print(json.dumps({'tag':tag,'version_code':code,'sha256':manifest['apk']['sha256']}))
