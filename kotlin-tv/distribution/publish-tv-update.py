#!/usr/bin/env python3
"""Runs only in the public source repository after release validation succeeds."""
import hashlib, json, os, re, shutil, subprocess
from pathlib import Path
root=Path.cwd(); out=root/'outputs'; out.mkdir(exist_ok=True)
def run(*args): return subprocess.check_output(args,text=True).strip()
def readme_section(version):
    """更新说明以 README 的「## <版本> 新增」为准，不再写死在脚本里。"""
    readme=(root/'kotlin-tv/README.md').read_text(encoding='utf-8')
    def grab(v):
        m=re.search(rf'^## {re.escape(v)} 新增\s*$(.*?)(?=^## |\Z)',readme,re.M|re.S)
        return [ln.rstrip() for ln in m[1].strip().splitlines()] if m else []
    lines=grab(version)
    if not lines:
        # 这一版还没写 README 时退回最近一版，至少不会把上一年的文案发出去。
        found=re.findall(r'^## (0\.9\.\d+) 新增\s*$',readme,re.M)
        if found: lines=grab(found[0]); print(f'README has no "## {version} 新增"; using {found[0]} notes.')
    return [ln for ln in lines if ln.strip()]
def note_paragraph(version):
    """update.json 的 notes 会被塞进一行状态文本，所以压成一段话。"""
    items=[]
    for ln in readme_section(version):
        text=ln.strip()
        if not text.startswith('- '): continue
        items.append(re.sub(r'\s+',' ',text[2:].replace('**','').replace('`','')).strip().rstrip('。；'))
    return '；'.join(items)+'。' if items else ''
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
changelog=readme_section(version)
manifest={'schema':1,'packageName':'com.hongguotv.nativeapp','versionCode':code,'versionName':version,'minSdk':26,'tag':tag,
 'sourceCommit':commit,'apk':{'name':apk.name,'size':apk.stat().st_size,'sha256':hashlib.sha256(apk.read_bytes()).hexdigest()},
 'notes':note_paragraph(version) or f'红果 TV {version} 更新。'}
(out/'update.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
source=out/f'hongguotv-{version}-source.zip'; run('git','archive','--format=zip',f'--prefix=hongguotv-{version}/','--output',str(source),'HEAD')
changelog_md='\n'.join(changelog) if changelog else f'版本 {version} 更新。'
notes=out/'release-notes.md'; notes.write_text(f'''Android 8.0+ 原生独立版，版本 {version}（versionCode {code}）。

自动检查、下载新版并校验签名，确认后由系统安装。首次安装本版后，后续更新无需手动传 APK。直连 GitHub 超时或失败时自动改走加速线路，安装包仍做长度与 SHA-256 校验。电视需允许本应用安装未知来源应用；普通 Android 8 仍需确认系统安装。

{changelog_md}

设置 → 推荐里可切换「混合推荐 / 只看真人剧 / 只看漫剧 / 只看 AI 剧」，并可「重置兴趣」：收藏与观看记录保留，重置前的观看不再累计兴趣。隐藏过的剧不会出现在推荐里。

片库里的「分类」按网站分类页浏览：真人剧、漫剧、AI 剧、漫画四种内容类型，真人剧 24 个题材、漫剧与 AI 剧各 8 个题材，片单直接显示并支持翻页；同一分类的片单 10 分钟内直接复用。

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
