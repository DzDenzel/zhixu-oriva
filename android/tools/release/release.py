#!/usr/bin/env python3
"""Repeatable local releases. No clone unless verify --clone-check is selected."""
from contextlib import contextmanager
from pathlib import Path, PurePosixPath
import argparse, base64, hashlib, http.client, inspect, io, json, os, re, shlex, shutil, signal, socket, ssl, struct, subprocess, sys, tempfile, time
import urllib.error, urllib.parse, urllib.request, zipfile

ALLOWED_DOWNLOAD_HOSTS = frozenset({
    'github.com',
    'objects.githubusercontent.com',
    'release-assets.githubusercontent.com',
})
ALLOWED_API_HOSTS = frozenset({'api.github.com', 'uploads.github.com'})
SENSITIVE_REDIRECT_HEADERS = frozenset({
    'authorization',
    'cookie',
    'cookie2',
    'proxy-authorization',
    'x-api-key',
})
DOWNLOAD_CHUNK_BYTES = 256 * 1024
MAX_ASSET_DOWNLOAD_BYTES = 512 * 1024 * 1024
DOWNLOAD_BACKOFF_SECONDS = (2, 4, 8, 16)
DEFAULT_GIT_TIMEOUT_SECONDS = 120
SSH_PROBE_TIMEOUT_SECONDS = 15
DEFAULT_UPLOAD_TIMEOUT_SECONDS = 900
DEFAULT_DOWNLOAD_TIMEOUT_SECONDS = 60
DEFAULT_DOWNLOAD_TOTAL_TIMEOUT_SECONDS = 300
CONTENT_RANGE_RE = re.compile(r'^bytes\s+(\d+)-(\d+)/(\d+|\*)$', re.IGNORECASE)


class ReleaseError(RuntimeError): pass


def need(condition, message):
    if not condition: raise ReleaseError(message)


def sha256(path): return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def _collect_active_secrets(extra_secrets=()):
    values = set()
    for item in extra_secrets:
        if item: values.add(str(item))
    for key in ('github_token', 'GITHUB_TOKEN', 'ORIVA_GIT_TOKEN', 'ORIVA_RELEASE_STOREPASS'):
        val = os.environ.get(key)
        if val: values.add(val)
    expanded = set(values)
    for val in values:
        expanded.add(base64.b64encode(('x-access-token:' + val).encode('utf8')).decode('ascii'))
    return tuple(sorted((v for v in expanded if v), key=len, reverse=True))


def redact_secrets(text, extra_secrets=()):
    if not text:
        return text
    cleaned = str(text)
    for secret in _collect_active_secrets(extra_secrets):
        cleaned = cleaned.replace(secret, '[REDACTED]')
    cleaned = re.sub(r'(?i)(Authorization\s*:\s*)(?:Bearer|Basic|token)\s+\S+', r'\1[REDACTED]', cleaned)
    cleaned = re.sub(r'(?i)((?:https?|jdbc:mysql)://)[^\s/:]+:[^\s/@]+@', r'\1[REDACTED]@', cleaned)
    return cleaned


def sanitize_git_env(base_env):
    clean = {}
    for key, val in base_env.items():
        upper = key.upper()
        if (
            upper.startswith('GIT_TRACE')
            or upper in {'GIT_CURL_VERBOSE', 'GIT_REDACT_COOKIES', 'GIT_ASKPASS', 'SSH_ASKPASS',
                         'GIT_CONFIG_PARAMETERS', 'GIT_CONFIG_COUNT', 'GIT_CONFIG', 'GIT_CONFIG_GLOBAL'}
            or upper.startswith('GCM_')
            or upper.startswith('GIT_CREDENTIAL_')
        ):
            continue
        clean[key] = val
    clean['GIT_TRACE'] = '0'
    clean['GIT_TRACE2'] = '0'
    clean['GIT_TRACE_CURL'] = '0'
    clean['GIT_CURL_VERBOSE'] = '0'
    clean['GIT_TERMINAL_PROMPT'] = '0'
    return clean


def _terminate_process_tree(proc):
    if proc.poll() is not None:
        return
    if os.name == 'nt':
        try:
            subprocess.run(
                ['taskkill', '/F', '/T', '/PID', str(proc.pid)],
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=10, check=False,
            )
        except Exception:
            pass
    else:
        try:
            os.killpg(proc.pid, signal.SIGKILL)
        except Exception:
            pass
    try:
        proc.kill()
    except Exception:
        pass


def _decode_bytes(raw):
    if not raw:
        return ''
    try:
        return raw.decode('utf8')
    except UnicodeDecodeError:
        return raw.decode('gb18030', errors='replace')


def git(root, *args, timeout=None, env=None):
    popen_kwargs = dict(
        cwd=root,
        env=env,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if os.name == 'nt':
        popen_kwargs['creationflags'] = getattr(subprocess, 'CREATE_NEW_PROCESS_GROUP', 0)
    else:
        popen_kwargs['start_new_session'] = True

    proc = subprocess.Popen(['git', *args], **popen_kwargs)
    try:
        out_b, err_b = proc.communicate(timeout=timeout)
    except subprocess.TimeoutExpired:
        _terminate_process_tree(proc)
        out_b, err_b = proc.communicate()
        cmd = args[0] if args else 'git'
        raise ReleaseError(f'git {cmd} timed out after {timeout}s') from None

    out = redact_secrets(_decode_bytes(out_b))
    err = redact_secrets(_decode_bytes(err_b))
    if proc.returncode != 0:
        raise ReleaseError(f'git {args[0] if args else ""} failed ({proc.returncode}): {err.strip() or out.strip()}')
    return out.strip()


def _call_git(root, *args, timeout=None, env=None):
    params = inspect.signature(git).parameters
    kwargs = {}
    if timeout is not None and 'timeout' in params:
        kwargs['timeout'] = timeout
    if env is not None and 'env' in params:
        kwargs['env'] = env
    return git(root, *args, **kwargs)


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


def make_plan(root, api_base_url=None, maven='mvn', npm='npm', maven_repo=None, maven_settings=None):
    root = Path(root).resolve(); name, code = version(root)
    wrapper = str(root/'android'/('gradlew.bat' if os.name == 'nt' else 'gradlew'))
    home = str(root/'android/.gradle-home')
    repo_path_obj = (Path(maven_repo) if maven_repo else root/'work/maven-repo').resolve()
    try:
        rel_parts = repo_path_obj.relative_to(root).parts
    except ValueError:
        rel_parts = ()
    if rel_parts and rel_parts[0].lower() in {'android', 'backend', 'deploy', '.github'}:
        raise ReleaseError('Maven repository path must stay outside public source directories: ' + str(repo_path_obj))
    repo_path = str(repo_path_obj)
    maven_cmd = [maven, '-B', '-ntp', '-Dmaven.repo.local=' + repo_path]
    if maven_settings:
        maven_cmd.extend(['-s', str(Path(maven_settings).resolve())])
    maven_cmd.extend(['clean', 'test', 'package'])
    commands = [
        [wrapper, '-g', home, '--console=plain', 'clean', 'testReleaseUnitTest', 'lintRelease', ':app:assembleRelease', '-PPLANLOCK_USE_MOCK=false'],
        [wrapper, '--stop', '-g', home],
        maven_cmd,
        [npm, 'ci'],
        [npm, 'run', 'verify'],
    ]
    if api_base_url: commands[0].append('-PPLANLOCK_API_BASE_URL='+api_base_url)
    return dict(
        root=str(root), version=name, versionCode=code, tag='v'+name, mavenRepo=repo_path,
        assets=str(root/'release'/('github-v'+name)), evidence=str(root/'work/releases'/('v'+name)),
        commands=commands,
    )


def write_json(path, data):
    Path(path).write_text(json.dumps(data, ensure_ascii=False, indent=2)+'\n', encoding='utf8')


def execute(args, cwd, env, logfile=None, timeout=None):
    actual = list(map(str, args))
    shell = False
    if os.name == 'nt' and str(args[0]).lower().endswith(('.bat', '.cmd')):
        need(not any(any(c in arg for c in '"\r\n%') for arg in actual), 'Unsupported quote/newline/percent in batch argument')
        actual = 'cmd.exe /d /v:off /s /c "' + ' '.join('"' + arg + '"' for arg in actual) + '"'
    popen_kwargs = dict(
        cwd=cwd,
        env=env,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        shell=shell,
    )
    if os.name == 'nt':
        popen_kwargs['creationflags'] = getattr(subprocess, 'CREATE_NEW_PROCESS_GROUP', 0)
    else:
        popen_kwargs['start_new_session'] = True

    secrets = (env.get('ORIVA_GIT_TOKEN'), env.get('GITHUB_TOKEN'), env.get('github_token'), env.get('ORIVA_RELEASE_STOREPASS'))
    proc = subprocess.Popen(actual, **popen_kwargs)
    try:
        raw, _ = proc.communicate(timeout=timeout)
    except subprocess.TimeoutExpired:
        _terminate_process_tree(proc)
        raw, _ = proc.communicate()
        output = redact_secrets(_decode_bytes(raw), secrets) + f'\n[TIMEOUT after {timeout}s]\n'
        if logfile: Path(logfile).write_text(output, encoding='utf8')
        raise ReleaseError(f'Command timed out after {timeout}s ({Path(str(args[0])).name}); see {logfile or "terminal"}') from None

    output = redact_secrets(_decode_bytes(raw), secrets)
    if logfile: Path(logfile).write_text(output, encoding='utf8')
    need(proc.returncode == 0, f'Command failed ({Path(str(args[0])).name}); see {logfile or "terminal"}')
    return output


def _call_execute(args, cwd, env, logfile=None, timeout=None):
    params = inspect.signature(execute).parameters
    if timeout is not None and 'timeout' in params:
        return execute(args, cwd, env, logfile, timeout=timeout)
    return execute(args, cwd, env, logfile)


def audit_archive(z):
    forbidden = {
        'build', 'target', '.gradle', '.gradle-home', '.android-home', '.cxx', '.git',
        '.secrets', '.plugin-signing', '.m2', 'node_modules', 'work', 'Obsidian', '__pycache__',
    }
    forbidden_lower = {item.lower() for item in forbidden}
    forbidden_suffixes = {'.jks', '.keystore', '.p12', '.pfx', '.key', '.pem', '.pyc', '.log'}
    secret_patterns = [
        rb'-----BEGIN (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----',
        rb'\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})\b',
        rb'\bAKIA[A-Z0-9]{16}\b',
        rb'\bsk-(?:proj-|svcacct-)?[A-Za-z0-9_-]{40,}\b',
        rb'\beyJ[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{10,}\b',
        rb'(?i)-(?:storepass|keypass)\s+(?!\$|:|<)([A-Za-z0-9_-]{3,})',
        rb'(?i)(?:https?|jdbc:mysql)://[^\s/:]+:[^\s/@]+@',
    ]
    def scan(data, label, depth=0):
        need(not any(re.search(p, data) for p in secret_patterns), 'Sensitive content found: '+label)
        if data[:2] == b'PK':
            need(depth < 4, 'Nested archive requires manual review: '+label)
            with zipfile.ZipFile(io.BytesIO(data)) as nested:
                for item in nested.infolist():
                    if item.is_dir(): continue
                    nested_path = PurePosixPath(item.filename)
                    need('..' not in nested_path.parts and not forbidden_lower.intersection(p.lower() for p in nested_path.parts), 'Private/unsafe nested archive entry: '+label+'!'+item.filename)
                    need(nested_path.suffix.lower() not in forbidden_suffixes, 'Private nested archive file: '+label+'!'+item.filename)
                    need(item.file_size <= 50_000_000, 'Large archive member requires review: '+label)
                    scan(nested.read(item), label+'!'+item.filename, depth+1)
    count = 0
    for item in z.infolist():
        if item.is_dir(): continue
        path = PurePosixPath(item.filename); rel = PurePosixPath(*path.parts[1:]); count += 1
        readme_screenshots = {'docs/screenshots/today-ink.png', 'docs/screenshots/today-atlas.png', 'docs/screenshots/today-phosphor.png'}
        need(rel.as_posix() in {'README.md','LICENSE','.gitignore'} | readme_screenshots or rel.parts[0] in {'android','backend','deploy'} or rel.parts[:2] == ('.github','workflows'), 'Outside public boundary: '+str(rel))
        need(not forbidden_lower.intersection(p.lower() for p in rel.parts) and rel.suffix.lower() not in forbidden_suffixes and not rel.name.lower().startswith('.env') and rel.name.lower() != 'local.properties', 'Private/generated file: '+str(rel))
        data = z.read(item); scan(data, str(rel))
        if rel.suffix.lower() in {'.yml','.yaml'}:
            for match in re.finditer(rb'(?im)^\s*(?:password|secret|token)\s*:\s*([^\r\n#]+)', data):
                value = match[1].strip().strip(b'\"\'')
                actions_secret = rel.parts[:2] == ('.github','workflows') and re.fullmatch(rb'\$\{\{\s*secrets\.[A-Z][A-Z0-9_]*\s*\}\}', value)
                need(not value or re.fullmatch(rb'\$\{[A-Z][A-Z0-9_]*\}', value) or actions_secret, 'Plain credential/config fallback: '+str(rel))
        if str(rel) == 'backend/planlock-app/src/main/resources/application.yml':
            for key in ['PLANLOCK_DB_PASSWORD','PLANLOCK_REDIS_PASSWORD','PLANLOCK_JWT_SECRET']:
                need(('${'+key+'}').encode() in data, 'Required backend secret env missing: '+key)
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
    strings = []
    for i in range(ns):
        pos = struct.unpack_from('<I', data, so+4*i)[0]; _, pos = uleb(data, pos)
        strings.append(data[pos:data.index(0, pos)].decode('utf8', errors='replace'))
    types = [strings[struct.unpack_from('<I', data, to+4*i)[0]] for i in range(nt)]
    for i in range(nc):
        row = struct.unpack_from('<8I', data, co+32*i)
        if types[row[0]] != 'Lcom/planlock/core/api/BuildConfig;': continue
        pos = row[6]; nstatic, pos = uleb(data, pos)
        for _ in range(3): _, pos = uleb(data, pos)
        fields = []; idx = 0
        for _ in range(nstatic):
            diff, pos = uleb(data, pos); _, pos = uleb(data, pos); idx += diff
            _, typ, nam = struct.unpack_from('<HHI', data, fo+8*idx); fields.append((strings[nam], types[typ]))
        vals = []; pos = row[7]
        if pos:
            n, pos = uleb(data, pos)
            for _ in range(n):
                tag = data[pos]; pos += 1; kind = tag & 31; arg = tag >> 5
                if kind == 31: value = bool(arg)
                elif kind == 30: value = None
                else:
                    value = int.from_bytes(data[pos:pos+arg+1], 'little'); pos += arg + 1
                    if kind == 23: value = strings[value]
                vals.append(value)
        return {name: vals[i] if i < len(vals) else False if typ == 'Z' else None for i, (name, typ) in enumerate(fields)}


def apk_config(apk):
    with zipfile.ZipFile(apk) as z:
        for name in z.namelist():
            if re.fullmatch(r'classes\d*\.dex', name):
                config = dex_config(z.read(name))
                if config is not None: return config
    raise ReleaseError('APK BuildConfig missing')


def verify_checksums(directory):
    names = []
    for line in (directory/'SHA256SUMS').read_text('utf8').splitlines():
        digest, name = line.split('  ', 1)
        need(Path(name).name == name and '/' not in name and '\\' not in name, 'Unsafe asset name')
        need(name not in names and re.fullmatch('[0-9a-fA-F]{64}', digest), 'Invalid checksum entry')
        need(sha256(directory/name).lower() == digest.lower(), 'Checksum differs: '+name); names.append(name)
    need(bool(names), 'Empty checksum file')
    return names


def ensure_asset(path, existing, upload):
    item = existing.get(path.name)
    if item is None: item = upload(path.name, path.read_bytes())
    need(item.get('name') == path.name and item.get('size') == path.stat().st_size and item.get('digest') == 'sha256:'+sha256(path), 'Remote asset size/digest differs: '+path.name)
    return item


def check_remote_tag(refs, tag, commit):
    target = refs.get('refs/tags/'+tag+'^{}', refs.get('refs/tags/'+tag))
    need(target is None or target == commit, 'Existing remote tag differs; never overwrite a release tag')


def sanitize_url_for_log(url):
    try:
        parsed = urllib.parse.urlsplit(url)
        host = parsed.hostname or ''
        port_part = f':{parsed.port}' if parsed.port is not None else ''
        return f'{parsed.scheme}://{host}{port_part}{parsed.path}'
    except ValueError:
        return '<malformed-url>'


def require_https_url(url, allowed_hosts, label, allow_query=False):
    need(isinstance(url, str) and bool(url.strip()) and not any(c.isspace() for c in url), f'{label} must be a non-empty URL without whitespace')
    try:
        parsed = urllib.parse.urlsplit(url)
        port = parsed.port
    except ValueError:
        raise ReleaseError(f'Malformed port in {label}: {sanitize_url_for_log(url)}') from None
    scheme = (parsed.scheme or '').lower()
    host = (parsed.hostname or '').lower()
    need(scheme == 'https', f'{label} must use HTTPS: {sanitize_url_for_log(url)}')
    need(host in allowed_hosts, f'Untrusted host in {label}: {sanitize_url_for_log(url)}')
    need(parsed.username is None and parsed.password is None and '@' not in parsed.netloc, f'Embedded credentials forbidden in {label}: {sanitize_url_for_log(url)}')
    need(port in (None, 443), f'Non-standard port forbidden in {label}: {sanitize_url_for_log(url)}')
    need(parsed.fragment == '', f'URL fragment forbidden in {label}: {sanitize_url_for_log(url)}')
    if not allow_query:
        need(parsed.query == '', f'Unexpected query string in {label}: {sanitize_url_for_log(url)}')
    return parsed


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args): return None


class Github:
    def __init__(self, repo, timeout=60, upload_timeout=DEFAULT_UPLOAD_TIMEOUT_SECONDS):
        need(re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repo), 'Invalid repository')
        self.base = 'https://api.github.com/repos/' + repo
        self.timeout = timeout
        self.upload_timeout = upload_timeout

    def call(self, url, method='GET', payload=None, missing_ok=False, timeout=None):
        require_https_url(url, ALLOWED_API_HOSTS, 'GitHub API URL', allow_query=True)
        token = os.environ.get('github_token') or os.environ.get('GITHUB_TOKEN'); need(token, 'Set github_token or GITHUB_TOKEN')
        raw = isinstance(payload, bytes)
        data = payload if raw else json.dumps(payload, ensure_ascii=False).encode('utf8') if payload is not None else None
        effective_timeout = timeout if timeout is not None else (self.upload_timeout if raw else self.timeout)
        headers = {'Authorization': 'Bearer ' + token, 'Accept': 'application/vnd.github+json', 'X-GitHub-Api-Version': '2022-11-28', 'User-Agent': 'ORIVA-release'}
        if data is not None: headers['Content-Type'] = 'application/octet-stream' if raw else 'application/json'
        opener = urllib.request.build_opener(NoRedirect())
        try:
            with opener.open(urllib.request.Request(url, data=data, headers=headers, method=method), timeout=effective_timeout) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            if error.code == 404 and missing_ok: return None
            raise ReleaseError(f'GitHub HTTP {error.code} for {method} {urllib.parse.urlparse(url).path}') from None


def load_manifest(plan):
    directory = Path(plan['assets']); m = json.loads((directory/'release-manifest.json').read_text('utf8'))
    need(m['versionName'] == plan['version'], 'Asset version differs')
    names = verify_checksums(directory)
    expected = {'zhixu-oriva-'+plan['version']+'.apk', 'zhixu-oriva-'+plan['version']+'-source.zip', 'oriva-release.cer', 'release-manifest.json'}
    need(set(names) == expected, 'Release must contain APK, source ZIP, certificate and manifest')
    return directory, m, names + ['SHA256SUMS']


def prepare_backend_env(env):
    backend_env = env.copy()
    backend_env.pop('MAVEN_ARGS', None)
    opts = backend_env.get('MAVEN_OPTS', '').strip()
    if '-Dfile.encoding=' not in opts:
        backend_env['MAVEN_OPTS'] = (opts + ' -Dfile.encoding=UTF-8').strip()
    return backend_env


def build(root, plan, args, env):
    require_clean(root); commit = git(root, 'rev-parse', 'HEAD'); w = Path(plan['evidence']); w.mkdir(parents=True, exist_ok=True)
    need(not (Path(plan['assets'])/'release-manifest.json').exists(), 'Version already packaged; increment Android version before a new build')
    need(args.java_home and args.sdk, 'Set JAVA_HOME and ANDROID_HOME, or --java-home and --sdk')
    if getattr(args, 'maven_settings', None):
        need(Path(args.maven_settings).is_file(), 'Missing Maven settings file: ' + str(args.maven_settings))
    Path(plan['mavenRepo']).mkdir(parents=True, exist_ok=True)
    env.update(JAVA_HOME=str(Path(args.java_home).resolve()), ANDROID_HOME=str(Path(args.sdk).resolve()), ANDROID_USER_HOME=str(root/'android/.android-home'))
    env['PATH'] = str(Path(args.java_home)/'bin') + os.pathsep + env['PATH']
    java = execute([str(Path(args.java_home)/'bin'/('java.exe' if os.name == 'nt' else 'java')), '-version'], root, env, w/'java-version.log')
    need(re.search(r'version "21(?:\.|\")', java), 'JDK21 required')
    old = w/'build-result.json'
    if old.exists(): old.rename(w/'previous-build-result.json') if not (w/'previous-build-result.json').exists() else old.unlink()
    labels = ['android-release-build', 'gradle-stop', 'backend-build', 'sdk-npm-ci', 'sdk-verify']
    try: execute(plan['commands'][0], root/'android', env, w/(labels[0]+'.log'))
    finally: execute(plan['commands'][1], root/'android', env, w/(labels[1]+'.log'))
    backend_env = prepare_backend_env(env)
    execute(plan['commands'][2], root/'backend', backend_env, w/(labels[2]+'.log'))
    execute(plan['commands'][3], root/'android/tools/plugin-sdk', env, w/(labels[3]+'.log'))
    execute(plan['commands'][4], root/'android/tools/plugin-sdk', env, w/(labels[4]+'.log'))
    require_clean(root); need(git(root, 'rev-parse', 'HEAD') == commit, 'Commit changed during build')
    unsigned = root/'android/app/build/outputs/apk/release/app-release-unsigned.apk'
    write_json(w/'build-result.json', dict(commit=commit, unsignedSHA256=sha256(unsigned), commands=plan['commands'], apiBaseUrl=args.api_base_url, mavenRepo=plan['mavenRepo']))
    print('BUILD_VERIFIED', commit)


def package(root, plan, args, env):
    require_clean(root); commit = git(root, 'rev-parse', 'HEAD'); w = Path(plan['evidence']); directory = Path(plan['assets'])
    need(not (directory/'release-manifest.json').exists(), 'Already packaged; keep immutable assets or increment version')
    unsigned = root/'android/app/build/outputs/apk/release/app-release-unsigned.apk'
    record = json.loads((w/'build-result.json').read_text('utf8')); check_build_evidence(record, commit, unsigned)
    need(args.keystore and args.key_alias and args.certificate and args.cert_sha256, 'Provide existing keystore, alias, public certificate and expected SHA256')
    need(bool(env.get('ORIVA_RELEASE_STOREPASS')), 'Set ORIVA_RELEASE_STOREPASS locally')
    need(args.sdk and args.java_home, 'Set Android SDK and JDK21 paths')
    env['JAVA_HOME'] = args.java_home; tools = Path(args.sdk)/'build-tools'/args.build_tools
    signer = tools/('apksigner.bat' if os.name == 'nt' else 'apksigner'); align = tools/('zipalign.exe' if os.name == 'nt' else 'zipalign')
    certificate = Path(args.certificate); expected = args.cert_sha256.replace(':', '').upper()
    need(sha256(certificate).upper() == expected, 'Public certificate SHA256 differs')
    directory.mkdir(parents=True, exist_ok=True); apk = directory/('zhixu-oriva-'+plan['version']+'.apk'); aligned = w/'aligned.apk'
    execute([align, '-P', '16', '-f', '4', unsigned, aligned], root, env, w/'zipalign.log')
    execute([signer, 'sign', '--ks', args.keystore, '--ks-key-alias', args.key_alias, '--ks-pass', 'env:ORIVA_RELEASE_STOREPASS', '--key-pass', 'env:ORIVA_RELEASE_STOREPASS', '--out', apk, aligned], root, env, w/'apksigner-sign.log')
    verify_out = execute([signer, 'verify', '--verbose', '--print-certs', apk], root, env, w/'apksigner-verify.log')
    match = re.search(r'Signer #1 certificate SHA-256 digest:\s*([a-fA-F0-9]{64})', verify_out)
    need(match and match[1].upper() == expected, 'APK signing certificate differs')
    need('Android Debug' not in verify_out, 'Debug signing certificate forbidden')
    badging = execute([tools/('aapt.exe' if os.name == 'nt' else 'aapt'), 'dump', 'badging', apk], root, env, w/'aapt-badging.log')
    need("versionName='"+plan['version']+"'" in badging and "versionCode='"+str(plan['versionCode'])+"'" in badging and "name='com.planlock.app'" in badging and 'application-debuggable' not in badging, 'APK version/application/release mismatch')
    config = apk_config(apk); need(config.get('USE_MOCK') is False and config.get('BUILD_TYPE') == 'release', 'APK must use release real API')
    if record.get('apiBaseUrl'): need(config['API_BASE_URL'] == record['apiBaseUrl'], 'Built API endpoint differs')
    source = directory/('zhixu-oriva-'+plan['version']+'-source.zip'); count = archive_source(root, commit, plan['version'], source)
    shutil.copy2(certificate, directory/'oriva-release.cer')
    m = dict(product='知序 ORIVA', versionName=plan['version'], versionCode=plan['versionCode'], commit=commit, applicationId='com.planlock.app', buildType='release', useMock=False, apiBaseUrl=config['API_BASE_URL'], certificateSHA256=expected, sourceFiles=count, assets=[])
    for path in [apk, source, directory/'oriva-release.cer']: m['assets'].append(dict(name=path.name, size=path.stat().st_size, sha256=sha256(path).upper()))
    require_clean(root); need(git(root, 'rev-parse', 'HEAD') == commit, 'Commit changed during packaging')
    write_json(directory/'release-manifest.json', m)
    files = [apk, source, directory/'oriva-release.cer', directory/'release-manifest.json']
    (directory/'SHA256SUMS').write_text(''.join(sha256(p)+'  '+p.name+'\n' for p in files), encoding='utf8')
    retained = w/'retained'; retained.mkdir(exist_ok=True)
    for path in files + [directory/'SHA256SUMS']:
        shutil.copy2(path, retained/path.name); need(sha256(path) == sha256(retained/path.name), 'Retained copy differs')
    print('PACKAGED', directory)


def find_release(api, tag, commit, statepath):
    if statepath.exists():
        state = json.loads(statepath.read_text('utf8'))
        need(state.get('commit') == commit and state.get('tag') == tag, 'Saved release state differs')
        release = api.call(api.base+'/releases/'+str(int(state['id'])), missing_ok=True)
        if release is not None: return release
    release = api.call(api.base+'/releases/tags/'+tag, missing_ok=True)
    if release is not None: return release
    # Authenticated lists include drafts; also recover a lost create response/state file.
    for page in range(1, 1001):
        releases = api.call(api.base+f'/releases?per_page=100&page={page}')
        for release in releases:
            if release['tag_name'] == tag: return release
        if len(releases) < 100: return None
    raise ReleaseError('Too many release pages; reconcile manually')


_ASKPASS_BODY = """#!/usr/bin/env python3
import os, re, sys, urllib.parse

prompt = " ".join(sys.argv[1:])
match = re.search(r"for\\s+'([^']+)'", prompt)
if not match:
    sys.stderr.write("Refused GIT_ASKPASS prompt without target URL\\n")
    sys.exit(1)

parsed = urllib.parse.urlsplit(match.group(1))
expected_host = os.environ.get("ORIVA_GIT_EXPECTED_HOST", "github.com").lower()
expected_user = os.environ.get("ORIVA_GIT_EXPECTED_USER", "x-access-token")
try:
    port = parsed.port
except ValueError:
    sys.stderr.write("Refused GIT_ASKPASS malformed port\\n")
    sys.exit(1)

if parsed.scheme.lower() != "https" or (parsed.hostname or "").lower() != expected_host or port not in (None, 443):
    sys.stderr.write("Refused GIT_ASKPASS request for unexpected origin\\n")
    sys.exit(1)

lower_prompt = prompt.lower()
if lower_prompt.startswith("username for "):
    print(expected_user)
elif lower_prompt.startswith("password for "):
    if (parsed.username or "") != expected_user:
        sys.stderr.write("Refused GIT_ASKPASS password request for unexpected user\\n")
        sys.exit(1)
    token = os.environ.get("ORIVA_GIT_TOKEN", "")
    if not token:
        sys.stderr.write("Missing ORIVA_GIT_TOKEN\\n")
        sys.exit(1)
    print(token)
else:
    sys.stderr.write("Refused unknown GIT_ASKPASS prompt type\\n")
    sys.exit(1)
"""


@contextmanager
def git_askpass_env(base_env, token, expected_host='github.com', expected_user='x-access-token'):
    need(token, 'Set github_token or GITHUB_TOKEN for HTTPS git operation')
    askpass_dir = Path(tempfile.mkdtemp(prefix='oriva-git-askpass-'))
    in_body_exception = False
    try:
        if os.name != 'nt':
            askpass_dir.chmod(0o700)
        py_script = askpass_dir / 'askpass.py'
        py_script.write_text(_ASKPASS_BODY, encoding='utf8')
        py_script.chmod(0o700)
        if os.name == 'nt':
            helper = askpass_dir / 'askpass.sh'
            helper.write_text(
                f'#!/bin/sh\nexec {shlex.quote(Path(sys.executable).as_posix())} {shlex.quote(py_script.as_posix())} "$@"\n',
                encoding='utf8',
            )
            helper.chmod(0o700)
            helper_str = helper.as_posix()
        else:
            helper_str = str(py_script)
        git_env = sanitize_git_env(base_env)
        git_env['GIT_ASKPASS'] = helper_str
        git_env['ORIVA_GIT_TOKEN'] = token
        git_env['ORIVA_GIT_EXPECTED_HOST'] = expected_host
        git_env['ORIVA_GIT_EXPECTED_USER'] = expected_user
        try:
            yield git_env
        except BaseException:
            in_body_exception = True
            raise
    finally:
        cleanup_error = None
        for attempt in range(3):
            try:
                if askpass_dir.exists():
                    shutil.rmtree(askpass_dir)
                cleanup_error = None
                break
            except OSError as exc:
                cleanup_error = exc
                time.sleep(0.1 * (attempt + 1))
        if cleanup_error is not None:
            msg = f'Failed to remove temporary GIT_ASKPASS directory {askpass_dir}: {cleanup_error}'
            print('WARNING:', msg, file=sys.stderr)
            if not in_body_exception:
                raise ReleaseError(msg) from cleanup_error


def _parse_refs(output):
    refs = {}
    for line in output.splitlines():
        parts = line.split()
        if len(parts) >= 2:
            refs[parts[1]] = parts[0]
    return refs


def _verify_origin_push_urls(root, repo, allowed_remotes):
    probe = subprocess.run(['git', 'config', '--get-all', 'remote.origin.url'], cwd=root, capture_output=True, text=True)
    if probe.returncode != 0 or not probe.stdout.strip():
        return  # Synthetic/mocked git environment without real .git/config remote.
    push_probe = subprocess.run(['git', 'remote', 'get-url', '--push', '--all', 'origin'], cwd=root, capture_output=True, text=True)
    need(push_probe.returncode == 0, 'Cannot inspect origin push URLs')
    push_urls = [line.strip() for line in push_probe.stdout.splitlines() if line.strip()]
    need(len(push_urls) == 1, f'origin must have exactly one push URL, found {len(push_urls)}')
    need(push_urls[0] in allowed_remotes, f'origin push URL does not match {repo}: {push_urls[0]}')
    rewrite = subprocess.run(
        ['git', 'config', '--get-regexp', r'^url\..*\.(insteadOf|pushInsteadOf)$'],
        cwd=root, capture_output=True, text=True,
    )
    if rewrite.returncode == 0:
        for line in rewrite.stdout.splitlines():
            if 'github.com' in line.lower():
                raise ReleaseError('Git url.*.insteadOf / pushInsteadOf rewrite targeting github.com is forbidden during release')


def publish(root, plan, args, env):
    require_clean(root); directory, m, names = load_manifest(plan); commit = m['commit']; tag = plan['tag']; w = Path(plan['evidence']); w.mkdir(parents=True, exist_ok=True)
    need(git(root, 'rev-parse', 'HEAD') == commit, 'Current commit differs from packaged source')
    need(args.notes, 'Provide --notes with this version release scope and limitations')
    notes = Path(args.notes).read_text('utf8'); need(bool(notes.strip()), 'Empty release notes')
    notes += '\n\nCommit: `' + commit + '`\n\nCertificate SHA-256: `' + m['certificateSHA256'] + '`\n\n| File | Bytes | SHA-256 |\n|---|---:|---|\n'
    for name in names: notes += f'| `{name}` | {(directory/name).stat().st_size} | `{sha256(directory/name).upper()}` |\n'

    allowed_remotes = {'git@github.com:'+args.repo+'.git', 'https://github.com/'+args.repo+'.git', 'https://github.com/'+args.repo}
    remote = git(root, 'remote', 'get-url', 'origin')
    need(remote in allowed_remotes, 'origin does not match selected public repository')
    _verify_origin_push_urls(root, args.repo, allowed_remotes)

    push_protocol = getattr(args, 'push_protocol', 'auto')
    git_timeout = getattr(args, 'git_timeout', DEFAULT_GIT_TIMEOUT_SECONDS)
    upload_timeout = getattr(args, 'upload_timeout', DEFAULT_UPLOAD_TIMEOUT_SECONDS)
    need(push_protocol in {'auto', 'ssh', 'https'}, 'Unsupported push protocol')
    need(git_timeout >= 1, 'git timeout must be positive')
    need(upload_timeout >= 1, 'upload timeout must be positive')

    https_url = 'https://github.com/' + args.repo + '.git'
    is_ssh_remote = remote.startswith('git@') or remote.startswith('ssh://')
    use_https_askpass = False
    push_target = 'origin'
    clean_env = sanitize_git_env(env)

    if push_protocol == 'ssh':
        need(is_ssh_remote, 'origin is not an SSH remote; cannot use --push-protocol ssh')
        refs = _parse_refs(_call_git(root, 'ls-remote', 'origin', timeout=git_timeout, env=clean_env))
    elif push_protocol == 'https':
        push_target = https_url
        use_https_askpass = True
        refs = None
    else:
        if is_ssh_remote:
            try:
                refs = _parse_refs(_call_git(root, 'ls-remote', 'origin', timeout=min(SSH_PROBE_TIMEOUT_SECONDS, git_timeout), env=clean_env))
            except (ReleaseError, subprocess.CalledProcessError, OSError):
                print('SSH remote probe failed; falling back to HTTPS GIT_ASKPASS')
                push_target = https_url
                use_https_askpass = True
                refs = None
        else:
            push_target = https_url
            use_https_askpass = True
            refs = None

    token = env.get('github_token') or env.get('GITHUB_TOKEN') or os.environ.get('github_token') or os.environ.get('GITHUB_TOKEN')

    def run_git_push_flow(current_refs, push_env):
        check_remote_tag(current_refs, tag, commit)
        local = git(root, 'tag', '--list', tag)
        if local: need(git(root, 'rev-parse', tag+'^{}') == commit, 'Existing local tag differs')
        api = Github(args.repo)
        if hasattr(api, 'upload_timeout'):
            api.upload_timeout = upload_timeout
        release = find_release(api, tag, commit, w/'github-release-state.json')
        if release:
            need(release['tag_name'] == tag and release['target_commitish'] in {commit, 'main'}, 'Existing release differs')
            need(current_refs.get('refs/tags/'+tag+'^{}', current_refs.get('refs/tags/'+tag)) == commit, 'Release tag must point at packaged commit')

        git_prefix = ['git', '-c', 'credential.helper=', '-c', 'http.followRedirects=false'] if use_https_askpass else ['git']
        _call_execute(git_prefix + ['push', '-u', push_target, 'main'], root, push_env, w/'git-push.log', timeout=git_timeout)
        if not local: _call_execute(['git', 'tag', '-a', tag, '-m', 'ORIVA '+plan['version']], root, clean_env, w/'git-tag.log')
        _call_execute(git_prefix + ['push', push_target, tag], root, push_env, w/'git-tag-push.log', timeout=git_timeout)

        if release is None:
            release = api.call(api.base+'/releases', 'POST', dict(tag_name=tag, target_commitish=commit, name='知序 ORIVA '+tag, body=notes, draft=True, prerelease='-' in plan['version']))
        write_json(w/'github-release-state.json', dict(id=release['id'], commit=commit, tag=tag))
        existing = {item['name']: item for item in release['assets']}
        need(set(existing) <= set(names), 'Unexpected assets exist; reconcile manually')
        if not release['draft']:
            need(set(existing) == set(names), 'Published release asset set differs; never mutate published releases')
            for name in names: ensure_asset(directory/name, existing, lambda *_: None)
        else:
            upload_kwargs = {'timeout': upload_timeout} if 'timeout' in inspect.signature(api.call).parameters else {}
            for name in names:
                ensure_asset(
                    directory/name,
                    existing,
                    lambda asset_name, data: api.call(release['upload_url'].split('{')[0]+'?name='+urllib.parse.quote(asset_name), 'POST', data, **upload_kwargs),
                )
            release = api.call(api.base+'/releases/'+str(release['id']), 'PATCH', dict(body=notes, draft=False, make_latest='false' if '-' in plan['version'] else 'true'))
        write_json(w/'github-published-release.json', release); print('PUBLISHED', release['html_url'])

    if use_https_askpass:
        with git_askpass_env(clean_env, token, expected_host='github.com', expected_user='x-access-token') as askpass_env:
            if refs is None:
                refs = _parse_refs(_call_git(root, '-c', 'credential.helper=', '-c', 'http.followRedirects=false', 'ls-remote', push_target, timeout=git_timeout, env=askpass_env))
            run_git_push_flow(refs, askpass_env)
    else:
        run_git_push_flow(refs, clean_env)


class AllowedAssetRedirectHandler(urllib.request.HTTPRedirectHandler):
    def __init__(self, deadline=None, download_timeout=DEFAULT_DOWNLOAD_TIMEOUT_SECONDS):
        self.deadline = deadline
        self.download_timeout = download_timeout

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if self.deadline is not None and time.monotonic() >= self.deadline:
            raise TimeoutError('Download total timeout exceeded during redirect')
        target = urllib.parse.urljoin(req.full_url, newurl)
        require_https_url(target, ALLOWED_DOWNLOAD_HOSTS, 'Asset redirect', allow_query=True)
        new_req = super().redirect_request(req, fp, code, msg, headers, target)
        if new_req is None:
            return None
        for hdrs in (new_req.headers, getattr(new_req, 'unredirected_hdrs', {})):
            for key in list(hdrs.keys()):
                if key.lower() in SENSITIVE_REDIRECT_HEADERS:
                    del hdrs[key]
        if self.deadline is not None:
            rem = self.deadline - time.monotonic()
            if rem <= 0:
                raise TimeoutError('Download total timeout exceeded during redirect')
            new_req.timeout = min(self.download_timeout, rem)
        return new_req


def _classify_network_stage(exc, default_stage):
    reason = getattr(exc, 'reason', exc)
    text = f'{type(reason).__name__}: {reason}'.lower()
    if isinstance(reason, ssl.SSLError) or 'ssl' in text or 'tls' in text or 'handshake' in text or 'certificate' in text:
        return 'TLS handshake'
    return default_stage


def _parse_content_range(header_value, expected_start, expected_size):
    need(bool(header_value), 'HTTP 206 response missing Content-Range header')
    match = CONTENT_RANGE_RE.fullmatch(header_value.strip())
    need(match is not None, f'Malformed Content-Range header: {header_value!r}')
    start = int(match.group(1))
    end = int(match.group(2))
    total_raw = match.group(3)
    need(start == expected_start and end >= start, f'Content-Range mismatch: expected start {expected_start}, got {header_value!r}')
    if total_raw != '*':
        total = int(total_raw)
        need(total > end, f'Invalid Content-Range total in {header_value!r}')
        if expected_size is not None:
            need(total == expected_size, f'Content-Range total {total} differs from expected {expected_size}')
    return start, end


def _parse_optional_content_length(headers):
    raw = headers.get('Content-Length') if headers else None
    if raw is None:
        return None
    need(raw.strip().isdigit(), f'Invalid Content-Length header: {raw!r}')
    return int(raw.strip())


def download_asset(url, target, expected_size=None, expected_sha256=None,
                   download_timeout=DEFAULT_DOWNLOAD_TIMEOUT_SECONDS,
                   total_timeout=DEFAULT_DOWNLOAD_TOTAL_TIMEOUT_SECONDS, opener=None, sleep_fn=time.sleep):
    require_https_url(url, {'github.com'}, 'Public download URL', allow_query=False)
    need(download_timeout >= 1 and total_timeout >= 1, 'Download timeouts must be positive')

    part = target.with_name(target.name + '.part')
    deadline = time.monotonic() + total_timeout
    if opener is None:
        opener = urllib.request.build_opener(AllowedAssetRedirectHandler(deadline=deadline, download_timeout=download_timeout))

    max_allowed = expected_size if expected_size is not None else MAX_ASSET_DOWNLOAD_BYTES

    for attempt in range(len(DOWNLOAD_BACKOFF_SECONDS) + 1):
        offset = part.stat().st_size if part.exists() else 0
        if expected_size is not None and offset > expected_size:
            part.unlink()
            offset = 0
        if expected_size is not None and offset == expected_size:
            if expected_sha256 is not None and sha256(part).upper() == expected_sha256.upper():
                if time.monotonic() > deadline:
                    raise ReleaseError(f'Download total timeout ({total_timeout}s) exceeded before promoting {target.name}')
                part.replace(target)
                return
            part.unlink()
            offset = 0

        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise ReleaseError(f'Download total timeout ({total_timeout}s) exceeded before starting {target.name}')
        req_timeout = min(download_timeout, remaining)

        headers = {'User-Agent': 'ORIVA-public-verification'}
        if offset > 0:
            headers['Range'] = f'bytes={offset}-'
        req = urllib.request.Request(url, headers=headers)

        stage = 'DNS/TCP'
        try:
            with opener.open(req, timeout=req_timeout) as response:
                if time.monotonic() > deadline:
                    raise TimeoutError(f'Download total timeout ({total_timeout}s) exceeded')
                status = getattr(response, 'status', None)
                if status is None:
                    status = response.getcode()
                resp_headers = getattr(response, 'headers', {}) or {}
                content_length = _parse_optional_content_length(resp_headers)

                if offset == 0:
                    need(status == 200, f'Expected HTTP 200 for full download of {target.name}, got {status}')
                    need(not resp_headers.get('Content-Range'), f'Unexpected Content-Range on HTTP 200 for {target.name}')
                    if expected_size is not None and content_length is not None:
                        need(content_length == expected_size, f'Content-Length {content_length} differs from expected {expected_size}')
                    mode = 'wb'
                    written = 0
                else:
                    if status == 206:
                        start, end = _parse_content_range(resp_headers.get('Content-Range', ''), offset, expected_size)
                        if content_length is not None:
                            need(content_length == (end - start + 1), f'Content-Length {content_length} mismatches Content-Range span')
                        mode = 'ab'
                        written = offset
                    elif status == 200:
                        need(not resp_headers.get('Content-Range'), f'Unexpected Content-Range on HTTP 200 for {target.name}')
                        if expected_size is not None and content_length is not None:
                            need(content_length == expected_size, f'Content-Length {content_length} differs from expected {expected_size}')
                        mode = 'wb'
                        written = 0
                    else:
                        raise ReleaseError(f'Unexpected HTTP {status} resuming {target.name}')

                stage = 'data read'
                with open(part, mode) as stream:
                    while True:
                        if time.monotonic() > deadline:
                            raise TimeoutError(f'Download total timeout ({total_timeout}s) exceeded')
                        chunk = response.read(DOWNLOAD_CHUNK_BYTES)
                        if time.monotonic() > deadline:
                            raise TimeoutError(f'Download total timeout ({total_timeout}s) exceeded')
                        if not chunk:
                            break
                        written += len(chunk)
                        if written > max_allowed:
                            raise ReleaseError(f'Downloaded stream exceeded allowed size ({max_allowed} bytes) for {target.name}')
                        stream.write(chunk)

            if time.monotonic() > deadline:
                raise TimeoutError(f'Download total timeout ({total_timeout}s) exceeded before completion')
            if expected_size is not None and written != expected_size:
                raise http.client.IncompleteRead(b'', expected_size - written)
            if expected_sha256 is not None and sha256(part).upper() != expected_sha256.upper():
                part.unlink(missing_ok=True)
                raise ReleaseError(f'Downloaded asset checksum mismatch before promotion: {target.name}')
            part.replace(target)
            return
        except urllib.error.HTTPError as exc:
            if exc.code == 416 and part.exists():
                part.unlink()
                last_error = ReleaseError(f'HTTP 416 restarting {target.name}')
            elif exc.code in {429, 500, 502, 503, 504}:
                last_error = ReleaseError(f'HTTP {exc.code} during {stage} for {target.name}')
            else:
                raise ReleaseError(f'Download failed for {target.name} during {stage}: HTTP {exc.code}') from None
        except urllib.error.URLError as exc:
            stage = _classify_network_stage(exc, stage)
            last_error = ReleaseError(f'Download failed for {target.name} during {stage}: {exc.reason}')
        except (TimeoutError, socket.timeout, http.client.IncompleteRead, ssl.SSLError, OSError) as exc:
            if isinstance(exc, ssl.SSLError):
                stage = 'TLS handshake'
            last_error = ReleaseError(f'Download failed for {target.name} during {stage}: {exc}')

        if attempt >= len(DOWNLOAD_BACKOFF_SECONDS):
            raise last_error
        remaining = deadline - time.monotonic()
        delay = min(DOWNLOAD_BACKOFF_SECONDS[attempt], max(0.0, remaining))
        if delay <= 0:
            raise ReleaseError(f'Download total timeout ({total_timeout}s) exceeded for {target.name} during {stage}: {last_error}')
        sleep_fn(delay)


def verify(root, plan, args, env):
    directory, m, names = load_manifest(plan); w = Path(plan['evidence']); w.mkdir(parents=True, exist_ok=True)
    report_path = w / 'recipient-verification.json'
    report_path.unlink(missing_ok=True)

    verify_mode = getattr(args, 'verify_mode', 'full')
    download_timeout = getattr(args, 'download_timeout', DEFAULT_DOWNLOAD_TIMEOUT_SECONDS)
    download_total_timeout = getattr(args, 'download_total_timeout', DEFAULT_DOWNLOAD_TOTAL_TIMEOUT_SECONDS)
    git_timeout = getattr(args, 'git_timeout', DEFAULT_GIT_TIMEOUT_SECONDS)
    need(verify_mode in {'full', 'api-digest'}, 'Unsupported verify mode')
    if verify_mode == 'api-digest':
        need(not getattr(args, 'clone_check', False), '--clone-check requires --verify-mode full')

    url = 'https://api.github.com/repos/' + args.repo + '/releases/tags/' + plan['tag']
    require_https_url(url, {'api.github.com'}, 'Public release metadata URL', allow_query=False)
    meta_opener = urllib.request.build_opener(NoRedirect())
    with meta_opener.open(urllib.request.Request(url, headers={'User-Agent': 'ORIVA-public-verification'}), timeout=60) as response:
        release = json.load(response)
    need(not release['draft'] and release['tag_name'] == plan['tag'], 'Public release differs')
    assets = {item['name']: item for item in release['assets']}; need(set(assets) == set(names), 'Public asset set differs')

    clean_env = sanitize_git_env(env)
    remote = _call_git(
        root, '-c', 'credential.helper=', '-c', 'http.followRedirects=false',
        'ls-remote', 'https://github.com/'+args.repo+'.git', 'refs/tags/'+plan['tag'], 'refs/tags/'+plan['tag']+'^{}',
        timeout=git_timeout, env=clean_env,
    )
    refs = _parse_refs(remote); check_remote_tag(refs, plan['tag'], m['commit']); need(bool(refs), 'Remote release tag missing')

    if verify_mode == 'api-digest':
        for name in names:
            item = assets[name]; local_file = directory / name
            require_https_url(item['browser_download_url'], {'github.com'}, f'Asset URL ({name})', allow_query=False)
            need(item.get('size') == local_file.stat().st_size and item.get('digest') == 'sha256:' + sha256(local_file), 'Remote asset size/digest differs: ' + name)
        write_json(report_path, dict(
            status='PASS', verifyMode='api-digest', downloadVerified=False,
            commit=m['commit'], assets=names, cloneCheck=False, release=release['html_url'],
        ))
        print('PUBLIC_API_DIGEST_VERIFIED', release['html_url'])
        return

    downloads = w/'downloads'; downloads.mkdir(exist_ok=True)
    for name in names:
        target = downloads/name; item = assets[name]; local_file = directory/name
        require_https_url(item['browser_download_url'], {'github.com'}, f'Asset URL ({name})', allow_query=False)
        download_asset(
            item['browser_download_url'], target,
            expected_size=local_file.stat().st_size,
            expected_sha256=sha256(local_file).upper(),
            download_timeout=download_timeout, total_timeout=download_total_timeout,
        )
        need(target.read_bytes() == local_file.read_bytes(), 'Downloaded asset differs: '+name)
        ensure_asset(target, {name: item}, lambda *_: None)
    verify_checksums(downloads)
    source = downloads/('zhixu-oriva-'+plan['version']+'-source.zip'); expected = w/'expected-source.zip'
    archive_source(root, m['commit'], plan['version'], expected)
    need(source.read_bytes() == expected.read_bytes(), 'Source ZIP differs from exact Git commit')
    if args.clone_check:
        clone = w/'recipient-clone'
        if not clone.exists(): _call_execute(['git', '-c', 'credential.helper=', '-c', 'http.followRedirects=false', 'clone', '--depth', '1', '--branch', plan['tag'], '--single-branch', '--config', 'core.autocrlf=false', 'https://github.com/'+args.repo+'.git', clone], root, clean_env, w/'recipient-clone.log', timeout=git_timeout)
        need(git(clone, 'rev-parse', 'HEAD') == m['commit'] and not git(clone, 'status', '--porcelain'), 'Recipient clone differs')
        with zipfile.ZipFile(source) as z:
            for path in git(clone, 'ls-files').splitlines(): need((clone/path).read_bytes() == z.read('zhixu-oriva-'+plan['version']+'/'+path), 'Clone/source differ: '+path)
    write_json(report_path, dict(
        status='PASS', verifyMode='full', downloadVerified=True,
        commit=m['commit'], assets=names, cloneCheck=args.clone_check, release=release['html_url'],
    ))
    print('PUBLIC_DOWNLOADS_VERIFIED', release['html_url'])


def parser():
    p = argparse.ArgumentParser(description=__doc__); p.add_argument('command', choices=['plan', 'build', 'package', 'publish', 'verify'])
    p.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[3]); p.add_argument('--repo', default='DzDenzel/zhixu-oriva')
    p.add_argument('--api-base-url'); p.add_argument('--java-home', default=os.environ.get('JAVA_HOME')); p.add_argument('--sdk', default=os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT'))
    p.add_argument('--maven', default=shutil.which('mvn') or 'mvn'); p.add_argument('--npm', default=shutil.which('npm') or 'npm'); p.add_argument('--build-tools', default='36.0.0')
    p.add_argument('--maven-repo', type=Path); p.add_argument('--maven-settings', type=Path)
    p.add_argument('--keystore', default=os.environ.get('ORIVA_RELEASE_KEYSTORE')); p.add_argument('--key-alias', default=os.environ.get('ORIVA_RELEASE_KEY_ALIAS'))
    p.add_argument('--certificate', default=os.environ.get('ORIVA_RELEASE_CERTIFICATE')); p.add_argument('--cert-sha256', default=os.environ.get('ORIVA_RELEASE_CERT_SHA256'))
    p.add_argument('--notes'); p.add_argument('--clone-check', action='store_true')
    p.add_argument('--push-protocol', choices=['auto', 'ssh', 'https'], default='auto')
    p.add_argument('--git-timeout', type=int, default=DEFAULT_GIT_TIMEOUT_SECONDS)
    p.add_argument('--upload-timeout', type=int, default=DEFAULT_UPLOAD_TIMEOUT_SECONDS)
    p.add_argument('--download-timeout', type=int, default=DEFAULT_DOWNLOAD_TIMEOUT_SECONDS)
    p.add_argument('--download-total-timeout', type=int, default=DEFAULT_DOWNLOAD_TOTAL_TIMEOUT_SECONDS)
    p.add_argument('--verify-mode', choices=['full', 'api-digest'], default='full')
    return p


def main():
    args = parser().parse_args(); root = args.root.resolve(); env = os.environ.copy()
    if args.api_base_url:
        endpoint = urllib.parse.urlparse(args.api_base_url)
        need(endpoint.hostname and not any(c.isspace() for c in args.api_base_url), 'API URL hostname required; whitespace forbidden')
        try: endpoint.port
        except ValueError: raise ReleaseError('Invalid API URL port') from None
        need(endpoint.scheme == 'https' or (endpoint.scheme == 'http' and endpoint.hostname == '10.0.2.2'), 'Release URL must use HTTPS, except default emulator host')
        need(args.api_base_url.endswith('/') and not endpoint.username and not endpoint.password and not endpoint.query and not endpoint.fragment, 'API URL must end in / and contain no credentials/query/fragment')
    need(re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', args.repo), 'Invalid repository')
    need(args.git_timeout >= 1 and args.upload_timeout >= 1 and args.download_timeout >= 1 and args.download_total_timeout >= 1, 'Timeouts must be positive')
    plan = make_plan(root, args.api_base_url, args.maven, args.npm, maven_repo=args.maven_repo, maven_settings=args.maven_settings)
    if args.command == 'plan': print(json.dumps(plan, ensure_ascii=False, indent=2)); return
    lock = root/'work/releases/.release.lock'; lock.parent.mkdir(parents=True, exist_ok=True)
    try: fd = os.open(lock, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
    except FileExistsError: raise ReleaseError('Another release is active or a stale lock needs inspection: '+str(lock)) from None
    try:
        with os.fdopen(fd, 'w', encoding='utf8') as stream: json.dump(dict(pid=os.getpid(), command=args.command), stream)
        {'build': build, 'package': package, 'publish': publish, 'verify': verify}[args.command](root, plan, args, env)
    finally: lock.unlink()


if __name__ == '__main__':
    try: main()
    except (ReleaseError, OSError, ValueError, subprocess.CalledProcessError, zipfile.BadZipFile) as error:
        print('ERROR:', redact_secrets(str(error)), file=sys.stderr); sys.exit(1)
