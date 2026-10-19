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
 'notes':'排行榜按网站改造成红果热播榜、真人剧热播榜、AI 剧热播榜、漫剧热播榜四个榜单，名次、热度与状态标签直接来自网站；分类浏览支持四种内容类型加题材筛选；直连 GitHub 超时会自动改用加速线路；应用内自动检查和下载更新。'}
(out/'update.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
source=out/f'hongguotv-{tag}-source.zip'; run('git','archive','--format=zip',f'--prefix=hongguotv-{tag}/','--output',str(source),'HEAD')
notes=out/'release-notes.md'; notes.write_text(f'''Android 8.0+ 原生独立版，版本 {version}（versionCode {code}）。

自动检查、下载新版并校验签名，确认后由系统安装。首次安装本版后，后续更新无需手动传 APK。直连 GitHub 超时或失败时自动改走加速线路，安装包仍做长度与 SHA-256 校验。电视需允许本应用安装未知来源应用；普通 Android 8 仍需确认系统安装。

顶部「排行榜」按网站热播榜改造成四个榜单：红果热播榜、真人剧热播榜、AI 剧热播榜、漫剧热播榜，默认红果热播榜。名次、热度、题材与「新剧」等状态标签都来自网站，不重新编号；支持榜单切换、翻页与刷新，同一榜单 10 分钟内直接复用。

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
