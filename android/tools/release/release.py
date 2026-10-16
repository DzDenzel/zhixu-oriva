#!/usr/bin/env python3
"""Repeatable local releases. No clone unless verify --clone-check is selected."""
from pathlib import Path, PurePosixPath
import argparse, hashlib, io, json, os, re, shutil, struct, subprocess, sys
import urllib.error, urllib.parse, urllib.request, zipfile

class ReleaseError(RuntimeError): pass

def need(condition, message):
    if not condition: raise ReleaseError(message)

def sha256(path): return hashlib.sha256(Path(path).read_bytes()).hexdigest()

def git(root, *args):
    return subprocess.check_output(['git', *args], cwd=root).decode('utf8').strip()

def require_clean(root):
    need(not git(root, 'status', '--porcelain'), 'Please commit all public changes before building or packaging')
    need(git(root, 'branch', '--show-current') == 'main', 'Release from main')

def version(root):
    text = (root/'android/app/build.gradle.kts').read_text('utf8')
    name = re.search(r'versionName\s*=\s*"([^"]+)"', text)
    code = re.search(r'versionCode\s*=\s*(\d+)', text)
    need(name and code, 'Android version configuration missing')
    need(re.fullmatch(r'\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?', name[1]), 'Unsafe version name')
    return name[1], int(code[1])

def make_plan(root, api_base_url=None, maven='mvn', npm='npm'):
    root = Path(root).resolve(); name, code = version(root)
    wrapper = str(root/'android'/('gradlew.bat' if os.name == 'nt' else 'gradlew'))
    home = str(root/'android/.gradle-home')
    commands = [[wrapper, '-g', home, '--console=plain', 'clean', 'testReleaseUnitTest', 'lintRelease', ':app:assembleRelease', '-PPLANLOCK_USE_MOCK=false'],
                [wrapper, '--stop', '-g', home], [maven, '-B', 'clean', 'test', 'package'], [npm, 'ci'], [npm, 'run', 'verify']]
    if api_base_url: commands[0].append('-PPLANLOCK_API_BASE_URL='+api_base_url)
    return dict(root=str(root), version=name, versionCode=code, tag='v'+name,
                assets=str(root/'release'/('github-v'+name)), evidence=str(root/'work/releases'/('v'+name)), commands=commands)

def write_json(path, data):
    Path(path).write_text(json.dumps(data, ensure_ascii=False, indent=2)+'\n', encoding='utf8')

def execute(args, cwd, env, logfile=None):
    actual = list(map(str,args))
    if os.name == 'nt' and str(args[0]).lower().endswith(('.bat','.cmd')):
        need(not any(any(c in arg for c in '"\r\n%') for arg in actual),'Unsupported quote/newline/percent in batch argument')
        actual = 'cmd.exe /d /s /c "'+' '.join('"'+arg+'"' for arg in actual)+'"'
    p = subprocess.run(actual, cwd=cwd, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    try: output = p.stdout.decode('utf8')
    except UnicodeDecodeError: output = p.stdout.decode('gb18030', errors='replace')
    if logfile: Path(logfile).write_text(output, encoding='utf8')
    need(p.returncode == 0, f'Command failed ({Path(str(args[0])).name}); see {logfile or "terminal"}')
    return output

def audit_archive(z):
    forbidden = {'build','target','.gradle','.gradle-home','.android-home','.cxx','.git','.secrets','.plugin-signing','node_modules','work','Obsidian','__pycache__'}
    secret_patterns = [rb'-----BEGIN (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----', rb'\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})\b', rb'\bAKIA[A-Z0-9]{16}\b', rb'\bsk-(?:proj-|svcacct-)?[A-Za-z0-9_-]{40,}\b', rb'\beyJ[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{10,}\b', rb'(?i)-(?:storepass|keypass)\s+(?!\$|:|<)([A-Za-z0-9_-]{3,})', rb'(?i)(?:https?|jdbc:mysql)://[^\s/:]+:[^\s/@]+@']
    def scan(data, label, depth=0):
        need(not any(re.search(p, data) for p in secret_patterns), 'Sensitive content found: '+label)
        if data[:2] == b'PK':
            need(depth < 4, 'Nested archive requires manual review: '+label)
            with zipfile.ZipFile(io.BytesIO(data)) as nested:
                for item in nested.infolist():
                    if item.is_dir(): continue
                    need(item.file_size <= 50_000_000, 'Large archive member requires review: '+label)
                    scan(nested.read(item), label+'!'+item.filename, depth+1)
    count = 0
    for item in z.infolist():
        if item.is_dir(): continue
        path = PurePosixPath(item.filename); rel = PurePosixPath(*path.parts[1:]); count += 1
        readme_screenshots = {'docs/screenshots/today-ink.png', 'docs/screenshots/today-atlas.png', 'docs/screenshots/today-phosphor.png'}
        need(rel.as_posix() in {'README.md','LICENSE','.gitignore'} | readme_screenshots or rel.parts[0] in {'android','backend','deploy'} or rel.parts[:2] == ('.github','workflows'), 'Outside public boundary: '+str(rel))
        need(not forbidden.intersection(rel.parts) and rel.suffix.lower() not in {'.jks','.keystore','.p12','.pfx','.key','.pem','.pyc','.log'} and not rel.name.startswith('.env') and rel.name != 'local.properties', 'Private/generated file: '+str(rel))
        data=z.read(item); scan(data,str(rel))
        if rel.suffix.lower() in {'.yml','.yaml'}:
            for match in re.finditer(rb'(?im)^\s*(?:password|secret|token)\s*:\s*([^\r\n#]+)',data):
                value=match[1].strip().strip(b'\"\'')
                actions_secret = rel.parts[:2] == ('.github','workflows') and re.fullmatch(rb'\$\{\{\s*secrets\.[A-Z][A-Z0-9_]*\s*\}\}', value)
                need(not value or re.fullmatch(rb'\$\{[A-Z][A-Z0-9_]*\}',value) or actions_secret,'Plain credential/config fallback: '+str(rel))
        if str(rel)=='backend/planlock-app/src/main/resources/application.yml':
            for key in ['PLANLOCK_DB_PASSWORD','PLANLOCK_REDIS_PASSWORD','PLANLOCK_JWT_SECRET']:
                need(('${'+key+'}').encode() in data,'Required backend secret env missing: '+key)
    need(count > 0, 'Empty source archive')
    return count

def archive_source(root, commit, name, output):
    subprocess.run(['git','-c','core.autocrlf=false','archive','--format=zip','--prefix=zhixu-oriva-'+name+'/', '--output='+str(output),commit],cwd=root,check=True)
    with zipfile.ZipFile(output) as z: return audit_archive(z)

def check_build_evidence(record, commit, apk):
    need(record.get('commit') == commit, 'Build commit differs from current commit; rebuild')
    need(record.get('unsignedSHA256') == sha256(apk), 'Unsigned APK changed; rebuild')

def uleb(data, pos):
    value = shift = 0
    while True:
        byte = data[pos]; pos += 1; value |= (byte & 127) << shift
        if byte < 128: return value, pos
        shift += 7

def dex_config(data):
    ns, so = struct.unpack_from('<II', data, 0x38); nt, to = struct.unpack_from('<II', data, 0x40)
    nf, fo = struct.unpack_from('<II', data, 0x50); nc, co = struct.unpack_from('<II', data, 0x60)
    strings=[]
    for i in range(ns):
        pos=struct.unpack_from('<I',data,so+4*i)[0]; _,pos=uleb(data,pos)
        strings.append(data[pos:data.index(0,pos)].decode('utf8',errors='replace'))
    types=[strings[struct.unpack_from('<I',data,to+4*i)[0]] for i in range(nt)]
    for i in range(nc):
        row=struct.unpack_from('<8I',data,co+32*i)
        if types[row[0]] != 'Lcom/planlock/core/api/BuildConfig;': continue
        pos=row[6]; nstatic,pos=uleb(data,pos)
        for _ in range(3): _,pos=uleb(data,pos)
        fields=[]; idx=0
        for _ in range(nstatic):
            diff,pos=uleb(data,pos); _,pos=uleb(data,pos); idx+=diff
            _,typ,nam=struct.unpack_from('<HHI',data,fo+8*idx); fields.append((strings[nam],types[typ]))
        vals=[]; pos=row[7]
        if pos:
            n,pos=uleb(data,pos)
            for _ in range(n):
                tag=data[pos]; pos+=1; kind=tag&31; arg=tag>>5
                if kind==31: value=bool(arg)
                elif kind==30: value=None
                else:
                    value=int.from_bytes(data[pos:pos+arg+1],'little'); pos+=arg+1
                    if kind==23: value=strings[value]
                vals.append(value)
        return {name: vals[i] if i<len(vals) else False if typ=='Z' else None for i,(name,typ) in enumerate(fields)}

def apk_config(apk):
    with zipfile.ZipFile(apk) as z:
        for name in z.namelist():
            if re.fullmatch(r'classes\d*\.dex',name):
                config=dex_config(z.read(name))
                if config is not None: return config
    raise ReleaseError('APK BuildConfig missing')

def verify_checksums(directory):
    names=[]
    for line in (directory/'SHA256SUMS').read_text('utf8').splitlines():
        digest,name=line.split('  ',1)
        need(Path(name).name==name and '/' not in name and '\\' not in name, 'Unsafe asset name')
        need(name not in names and re.fullmatch('[0-9a-fA-F]{64}',digest), 'Invalid checksum entry')
        need(sha256(directory/name).lower()==digest.lower(), 'Checksum differs: '+name); names.append(name)
    need(bool(names),'Empty checksum file')
    return names

def ensure_asset(path, existing, upload):
    item=existing.get(path.name)
    if item is None: item=upload(path.name,path.read_bytes())
    need(item.get('name')==path.name and item.get('size')==path.stat().st_size and item.get('digest')=='sha256:'+sha256(path), 'Remote asset size/digest differs: '+path.name)
    return item

def check_remote_tag(refs, tag, commit):
    target=refs.get('refs/tags/'+tag+'^{}',refs.get('refs/tags/'+tag))
    need(target is None or target==commit, 'Existing remote tag differs; never overwrite a release tag')

class Github:
    def __init__(self, repo):
        need(re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+',repo), 'Invalid repository')
        self.base='https://api.github.com/repos/'+repo
    def call(self, url, method='GET', payload=None, missing_ok=False):
        need(urllib.parse.urlparse(url).hostname in {'api.github.com','uploads.github.com'}, 'Unexpected API host')
        token=os.environ.get('github_token') or os.environ.get('GITHUB_TOKEN'); need(token,'Set github_token or GITHUB_TOKEN')
        raw=isinstance(payload,bytes); data=payload if raw else json.dumps(payload,ensure_ascii=False).encode('utf8') if payload is not None else None
        headers={'Authorization':'Bearer '+token,'Accept':'application/vnd.github+json','X-GitHub-Api-Version':'2022-11-28','User-Agent':'ORIVA-release'}
        if data is not None: headers['Content-Type']='application/octet-stream' if raw else 'application/json'
        # Do not follow authenticated redirects to an unverified host.
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self,*args): return None
        opener=urllib.request.build_opener(NoRedirect())
        try:
            with opener.open(urllib.request.Request(url,data=data,headers=headers,method=method),timeout=120) as response: return json.load(response)
        except urllib.error.HTTPError as error:
            if error.code==404 and missing_ok: return None
            raise ReleaseError(f'GitHub HTTP {error.code} for {method} {urllib.parse.urlparse(url).path}') from None

def load_manifest(plan):
    directory=Path(plan['assets']); m=json.loads((directory/'release-manifest.json').read_text('utf8'))
    need(m['versionName']==plan['version'],'Asset version differs')
    names=verify_checksums(directory)
    expected={'zhixu-oriva-'+plan['version']+'.apk','zhixu-oriva-'+plan['version']+'-source.zip','oriva-release.cer','release-manifest.json'}
    need(set(names)==expected,'Release must contain APK, source ZIP, certificate and manifest')
    return directory,m,names+['SHA256SUMS']

def build(root, plan, args, env):
    require_clean(root); commit=git(root,'rev-parse','HEAD'); w=Path(plan['evidence']); w.mkdir(parents=True,exist_ok=True)
    need(not (Path(plan['assets'])/'release-manifest.json').exists(),'Version already packaged; increment Android version before a new build')
    need(args.java_home and args.sdk,'Set JAVA_HOME and ANDROID_HOME, or --java-home and --sdk')
    env.update(JAVA_HOME=str(Path(args.java_home).resolve()),ANDROID_HOME=str(Path(args.sdk).resolve()),ANDROID_USER_HOME=str(root/'android/.android-home'))
    env['PATH']=str(Path(args.java_home)/'bin')+os.pathsep+env['PATH']
    java=execute([str(Path(args.java_home)/'bin'/('java.exe' if os.name=='nt' else 'java')),'-version'],root,env,w/'java-version.log')
    need(re.search(r'version "21(?:\.|\")',java),'JDK21 required')
    old=w/'build-result.json'
    if old.exists(): old.rename(w/'previous-build-result.json') if not (w/'previous-build-result.json').exists() else old.unlink()
    labels=['android-release-build','gradle-stop','backend-build','sdk-npm-ci','sdk-verify']
    try: execute(plan['commands'][0],root/'android',env,w/(labels[0]+'.log'))
    finally: execute(plan['commands'][1],root/'android',env,w/(labels[1]+'.log'))
    for i in range(2,5): execute(plan['commands'][i],root/('backend' if i==2 else 'android/tools/plugin-sdk'),env,w/(labels[i]+'.log'))
    require_clean(root); need(git(root,'rev-parse','HEAD')==commit,'Commit changed during build')
    unsigned=root/'android/app/build/outputs/apk/release/app-release-unsigned.apk'
    write_json(w/'build-result.json',dict(commit=commit,unsignedSHA256=sha256(unsigned),commands=plan['commands'],apiBaseUrl=args.api_base_url))
    print('BUILD_VERIFIED',commit)

def package(root, plan, args, env):
    require_clean(root); commit=git(root,'rev-parse','HEAD'); w=Path(plan['evidence']); directory=Path(plan['assets'])
    need(not (directory/'release-manifest.json').exists(),'Already packaged; keep immutable assets or increment version')
    unsigned=root/'android/app/build/outputs/apk/release/app-release-unsigned.apk'
    record=json.loads((w/'build-result.json').read_text('utf8')); check_build_evidence(record,commit,unsigned)
    need(args.keystore and args.key_alias and args.certificate and args.cert_sha256,'Provide existing keystore, alias, public certificate and expected SHA256')
    need(bool(env.get('ORIVA_RELEASE_STOREPASS')),'Set ORIVA_RELEASE_STOREPASS locally')
    need(args.sdk and args.java_home,'Set Android SDK and JDK21 paths')
    env['JAVA_HOME']=args.java_home; tools=Path(args.sdk)/'build-tools'/args.build_tools
    signer=tools/('apksigner.bat' if os.name=='nt' else 'apksigner'); align=tools/('zipalign.exe' if os.name=='nt' else 'zipalign')
    certificate=Path(args.certificate); expected=args.cert_sha256.replace(':','').upper()
    need(sha256(certificate).upper()==expected,'Public certificate SHA256 differs')
    directory.mkdir(parents=True,exist_ok=True); apk=directory/('zhixu-oriva-'+plan['version']+'.apk'); aligned=w/'aligned.apk'
    execute([align,'-P','16','-f','4',unsigned,aligned],root,env,w/'zipalign.log')
    execute([signer,'sign','--ks',args.keystore,'--ks-key-alias',args.key_alias,'--ks-pass','env:ORIVA_RELEASE_STOREPASS','--key-pass','env:ORIVA_RELEASE_STOREPASS','--out',apk,aligned],root,env,w/'apksigner-sign.log')
    verify=execute([signer,'verify','--verbose','--print-certs',apk],root,env,w/'apksigner-verify.log')
    match=re.search(r'Signer #1 certificate SHA-256 digest:\s*([a-fA-F0-9]{64})',verify)
    need(match and match[1].upper()==expected,'APK signing certificate differs')
    need('Android Debug' not in verify,'Debug signing certificate forbidden')
    badging=execute([tools/('aapt.exe' if os.name=='nt' else 'aapt'),'dump','badging',apk],root,env,w/'aapt-badging.log')
    need("versionName='"+plan['version']+"'" in badging and "versionCode='"+str(plan['versionCode'])+"'" in badging and "name='com.planlock.app'" in badging and 'application-debuggable' not in badging,'APK version/application/release mismatch')
    config=apk_config(apk); need(config.get('USE_MOCK') is False and config.get('BUILD_TYPE')=='release','APK must use release real API')
    if record.get('apiBaseUrl'): need(config['API_BASE_URL']==record['apiBaseUrl'],'Built API endpoint differs')
    source=directory/('zhixu-oriva-'+plan['version']+'-source.zip'); count=archive_source(root,commit,plan['version'],source)
    shutil.copy2(certificate,directory/'oriva-release.cer')
    m=dict(product='知序 ORIVA',versionName=plan['version'],versionCode=plan['versionCode'],commit=commit,applicationId='com.planlock.app',buildType='release',useMock=False,apiBaseUrl=config['API_BASE_URL'],certificateSHA256=expected,sourceFiles=count,assets=[])
    for path in [apk,source,directory/'oriva-release.cer']: m['assets'].append(dict(name=path.name,size=path.stat().st_size,sha256=sha256(path).upper()))
    require_clean(root); need(git(root,'rev-parse','HEAD')==commit,'Commit changed during packaging')
    write_json(directory/'release-manifest.json',m)
    files=[apk,source,directory/'oriva-release.cer',directory/'release-manifest.json']
    (directory/'SHA256SUMS').write_text(''.join(sha256(p)+'  '+p.name+'\n' for p in files),encoding='utf8')
    retained=w/'retained'; retained.mkdir(exist_ok=True)
    for path in files+[directory/'SHA256SUMS']:
        shutil.copy2(path,retained/path.name); need(sha256(path)==sha256(retained/path.name),'Retained copy differs')
    print('PACKAGED',directory)

def find_release(api, tag, commit, statepath):
    if statepath.exists():
        state=json.loads(statepath.read_text('utf8'))
        need(state.get('commit')==commit and state.get('tag')==tag,'Saved release state differs')
        release=api.call(api.base+'/releases/'+str(int(state['id'])),missing_ok=True)
        if release is not None:return release
    release=api.call(api.base+'/releases/tags/'+tag,missing_ok=True)
    if release is not None:return release
    # Authenticated lists include drafts; also recover a lost create response/state file.
    for page in range(1,1001):
        releases=api.call(api.base+f'/releases?per_page=100&page={page}')
        for release in releases:
            if release['tag_name']==tag:return release
        if len(releases)<100:return None
    raise ReleaseError('Too many release pages; reconcile manually')

def publish(root, plan, args, env):
    require_clean(root); directory,m,names=load_manifest(plan); commit=m['commit']; tag=plan['tag']; w=Path(plan['evidence']); w.mkdir(parents=True,exist_ok=True)
    need(git(root,'rev-parse','HEAD')==commit,'Current commit differs from packaged source')
    need(args.notes,'Provide --notes with this version release scope and limitations')
    notes=Path(args.notes).read_text('utf8'); need(bool(notes.strip()),'Empty release notes')
    notes+='\n\nCommit: `'+commit+'`\n\nCertificate SHA-256: `'+m['certificateSHA256']+'`\n\n| File | Bytes | SHA-256 |\n|---|---:|---|\n'
    for name in names: notes+=f'| `{name}` | {(directory/name).stat().st_size} | `{sha256(directory/name).upper()}` |\n'
    remote=git(root,'remote','get-url','origin')
    need(remote in {'git@github.com:'+args.repo+'.git','https://github.com/'+args.repo+'.git','https://github.com/'+args.repo},'origin does not match selected public repository')
    refs={line.split()[1]:line.split()[0] for line in git(root,'ls-remote','origin').splitlines()}
    check_remote_tag(refs,tag,commit)
    local=git(root,'tag','--list',tag)
    if local: need(git(root,'rev-parse',tag+'^{}')==commit,'Existing local tag differs')
    api=Github(args.repo); release=find_release(api,tag,commit,w/'github-release-state.json')
    if release:
        need(release['tag_name']==tag and release['target_commitish'] in {commit,'main'},'Existing release differs')
        need(refs.get('refs/tags/'+tag+'^{}',refs.get('refs/tags/'+tag))==commit,'Release tag must point at packaged commit')
    execute(['git','push','-u','origin','main'],root,env,w/'git-push.log')
    if not local: execute(['git','tag','-a',tag,'-m','ORIVA '+plan['version']],root,env,w/'git-tag.log')
    execute(['git','push','origin',tag],root,env,w/'git-tag-push.log')
    if release is None:
        release=api.call(api.base+'/releases','POST',dict(tag_name=tag,target_commitish=commit,name='知序 ORIVA '+tag,body=notes,draft=True,prerelease='-' in plan['version']))
    write_json(w/'github-release-state.json',dict(id=release['id'],commit=commit,tag=tag))
    existing={item['name']:item for item in release['assets']}
    need(set(existing)<=set(names),'Unexpected assets exist; reconcile manually')
    if not release['draft']:
        need(set(existing)==set(names),'Published release asset set differs; never mutate published releases')
        for name in names: ensure_asset(directory/name,existing,lambda *_:None)
    else:
        for name in names:
            ensure_asset(directory/name,existing,lambda name,data:api.call(release['upload_url'].split('{')[0]+'?name='+urllib.parse.quote(name),'POST',data))
        release=api.call(api.base+'/releases/'+str(release['id']),'PATCH',dict(body=notes,draft=False,make_latest='false' if '-' in plan['version'] else 'true'))
    write_json(w/'github-published-release.json',release); print('PUBLISHED',release['html_url'])

def verify(root, plan, args, env):
    directory,m,names=load_manifest(plan); w=Path(plan['evidence']); w.mkdir(parents=True,exist_ok=True)
    url='https://api.github.com/repos/'+args.repo+'/releases/tags/'+plan['tag']
    with urllib.request.urlopen(urllib.request.Request(url,headers={'User-Agent':'ORIVA-public-verification'}),timeout=60) as response: release=json.load(response)
    need(not release['draft'] and release['tag_name']==plan['tag'],'Public release differs')
    assets={item['name']:item for item in release['assets']}; need(set(assets)==set(names),'Public asset set differs')
    downloads=w/'downloads'; downloads.mkdir(exist_ok=True)
    for name in names:
        target=downloads/name; item=assets[name]
        need(urllib.parse.urlparse(item['browser_download_url']).hostname=='github.com','Unexpected public download host')
        with urllib.request.urlopen(item['browser_download_url'],timeout=120) as response: target.write_bytes(response.read())
        need(target.read_bytes()==(directory/name).read_bytes(),'Downloaded asset differs: '+name)
        ensure_asset(target,{name:item},lambda *_:None)
    verify_checksums(downloads)
    remote=git(root,'ls-remote','https://github.com/'+args.repo+'.git','refs/tags/'+plan['tag'],'refs/tags/'+plan['tag']+'^{}')
    refs={line.split()[1]:line.split()[0] for line in remote.splitlines()}; check_remote_tag(refs,plan['tag'],m['commit']); need(bool(refs),'Remote release tag missing')
    source=downloads/('zhixu-oriva-'+plan['version']+'-source.zip'); expected=w/'expected-source.zip'
    archive_source(root,m['commit'],plan['version'],expected)
    need(source.read_bytes()==expected.read_bytes(),'Source ZIP differs from exact Git commit')
    if args.clone_check:
        clone=w/'recipient-clone'
        if not clone.exists(): execute(['git','clone','--depth','1','--branch',plan['tag'],'--single-branch','--config','core.autocrlf=false','https://github.com/'+args.repo+'.git',clone],root,env,w/'recipient-clone.log')
        need(git(clone,'rev-parse','HEAD')==m['commit'] and not git(clone,'status','--porcelain'),'Recipient clone differs')
        with zipfile.ZipFile(source) as z:
            for path in git(clone,'ls-files').splitlines(): need((clone/path).read_bytes()==z.read('zhixu-oriva-'+plan['version']+'/'+path),'Clone/source differ: '+path)
    write_json(w/'recipient-verification.json',dict(status='PASS',commit=m['commit'],assets=names,cloneCheck=args.clone_check,release=release['html_url']))
    print('PUBLIC_DOWNLOADS_VERIFIED',release['html_url'])

def parser():
    p=argparse.ArgumentParser(description=__doc__); p.add_argument('command',choices=['plan','build','package','publish','verify'])
    p.add_argument('--root',type=Path,default=Path(__file__).resolve().parents[3]); p.add_argument('--repo',default='DzDenzel/zhixu-oriva')
    p.add_argument('--api-base-url'); p.add_argument('--java-home',default=os.environ.get('JAVA_HOME')); p.add_argument('--sdk',default=os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT'))
    p.add_argument('--maven',default=shutil.which('mvn') or 'mvn'); p.add_argument('--npm',default=shutil.which('npm') or 'npm'); p.add_argument('--build-tools',default='36.0.0')
    p.add_argument('--keystore',default=os.environ.get('ORIVA_RELEASE_KEYSTORE')); p.add_argument('--key-alias',default=os.environ.get('ORIVA_RELEASE_KEY_ALIAS'))
    p.add_argument('--certificate',default=os.environ.get('ORIVA_RELEASE_CERTIFICATE')); p.add_argument('--cert-sha256',default=os.environ.get('ORIVA_RELEASE_CERT_SHA256'))
    p.add_argument('--notes'); p.add_argument('--clone-check',action='store_true'); return p

def main():
    args=parser().parse_args(); root=args.root.resolve(); env=os.environ.copy()
    if args.api_base_url:
        endpoint=urllib.parse.urlparse(args.api_base_url)
        need(endpoint.hostname and not any(c.isspace() for c in args.api_base_url),'API URL hostname required; whitespace forbidden')
        try: endpoint.port
        except ValueError: raise ReleaseError('Invalid API URL port') from None
        need(endpoint.scheme=='https' or (endpoint.scheme=='http' and endpoint.hostname=='10.0.2.2'),'Release URL must use HTTPS, except default emulator host')
        need(args.api_base_url.endswith('/') and not endpoint.username and not endpoint.password and not endpoint.query and not endpoint.fragment,'API URL must end in / and contain no credentials/query/fragment')
    need(re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+',args.repo),'Invalid repository')
    plan=make_plan(root,args.api_base_url,args.maven,args.npm)
    if args.command=='plan': print(json.dumps(plan,ensure_ascii=False,indent=2)); return
    lock=root/'work/releases/.release.lock'; lock.parent.mkdir(parents=True,exist_ok=True)
    try: fd=os.open(lock,os.O_CREAT|os.O_EXCL|os.O_WRONLY)
    except FileExistsError: raise ReleaseError('Another release is active or a stale lock needs inspection: '+str(lock)) from None
    try:
        with os.fdopen(fd,'w',encoding='utf8') as stream: json.dump(dict(pid=os.getpid(),command=args.command),stream)
        {'build':build,'package':package,'publish':publish,'verify':verify}[args.command](root,plan,args,env)
    finally: lock.unlink()

if __name__=='__main__':
    try: main()
    except (ReleaseError,OSError,ValueError,subprocess.CalledProcessError,zipfile.BadZipFile) as error:
        print('ERROR:',str(error),file=sys.stderr); sys.exit(1)
