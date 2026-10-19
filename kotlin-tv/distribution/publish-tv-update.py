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
 'notes':'推荐页确认卡片直接播放，播放中 ↑/↓ 换上一部/下一部，切走的剧会存下位置、回来接着看；一部播完自动接下一部，片单快到头会在后台续批；播放失败可一键「换下一部」；直连 GitHub 超时会自动改用加速线路；应用内自动检查和下载更新。'}
(out/'update.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
source=out/f'hongguotv-{tag}-source.zip'; run('git','archive','--format=zip',f'--prefix=hongguotv-{tag}/','--output',str(source),'HEAD')
notes=out/'release-notes.md'; notes.write_text(f'''Android 8.0+ 原生独立版，版本 {version}（versionCode {code}）。

自动检查、下载新版并校验签名，确认后由系统安装。首次安装本版后，后续更新无需手动传 APK。直连 GitHub 超时或失败时自动改走加速线路，安装包仍做长度与 SHA-256 校验。电视需允许本应用安装未知来源应用；普通 Android 8 仍需确认系统安装。

顶部新增「推荐」并作为启动默认页，顶部导航顺序为 推荐、首页、搜索、分类、排行榜、收藏、最近观看、设置。推荐参考混合内容池规则：默认真人剧 50%、漫剧 30%、AI 剧 20%，按收藏（每部 +4）与看过 30 秒以上（每部 +1）的题材兴趣排序，7 天内看过的降权、整剧看完的不再推荐，每批预留 20% 给没看过或陌生题材；卡面右上角标出类型，「换一批」换一个随机种子重新排，「加载更多」按批续在后面且同一会话不重复，单个类型抓取失败时由其它类型补足。

本版的推荐改成直接连播：在推荐页确认卡片就直接开始播放（有记录时从上次看到的位置继续），播放中按 ↑ 是上一部、↓ 是下一部，切走的那一部会先存下位置，返回时继续；一部剧的最后一集播完并且开着「自动连播」时，自动接推荐里的下一部，片单快看到头会在后台再续一批。连播时选集、倍速等操作改到菜单键（或长按确认），普通播放（首页、详情、收藏、最近观看）按键不变；播放失败时多一个「换下一部」，可以跳过打不开的剧。想看简介或管理卡片，长按确认或按菜单键即可。

设置 → 推荐里可切换「混合推荐 / 只看真人剧 / 只看漫剧 / 只看 AI 剧」，并可「重置兴趣」：收藏与观看记录保留，重置前的观看不再累计兴趣。隐藏过的剧不会出现在推荐里。

顶部「分类」按网站分类页浏览：真人剧、漫剧、AI 剧、漫画四种内容类型，真人剧 24 个题材、漫剧与 AI 剧各 8 个题材，片单直接显示并支持翻页；同一分类的片单 10 分钟内直接复用。

保留安静连播、完整竖版海报和所有本机记录。使用本仓库专用的发布签名；与旧版签名不同，已有旧签名安装需卸载后重装一次。

自动构建的源码提交：{commit}。测试与 Release lint 通过后才发布。

- APK：电视安装文件。
- source.zip：与 APK 对应的完整原生源码，GPL-3.0。
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
