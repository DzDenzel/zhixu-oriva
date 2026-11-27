#!/usr/bin/env python3
"""Repeatable local releases. No clone unless verify --clone-check is selected."""
from contextlib import contextmanager
from pathlib import Path, PurePosixPath
import argparse, base64, ctypes, hashlib, http.client, inspect, io, json, os, re, shlex, shutil, signal, socket, ssl, struct, subprocess, sys, tempfile, threading, time
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
PIPE_DRAIN_TIMEOUT_SECONDS = 3
CONTENT_RANGE_RE = re.compile(r'^bytes\s+(\d+)-(\d+)/(\d+|\*)$', re.IGNORECASE)

_REGISTERED_SECRETS: set[str] = set()
_CONTROLLED_ASKPASS_TOKENS: dict[str, str] = {}
_CONTROLLED_GIT_CONTEXTS: dict[str, dict[str, object]] = {}
_EXPLICIT_SECRET_KEYS = frozenset({'github_token', 'GITHUB_TOKEN', 'ORIVA_GIT_TOKEN', 'ORIVA_RELEASE_STOREPASS'})
_SECRET_KEY_EXACT_TOKENS = frozenset({'TOKEN', 'SECRET', 'PASSWORD', 'PASS', 'PASSWD', 'STOREPASS', 'KEYPASS', 'KEY', 'CREDENTIAL', 'CREDENTIALS', 'AUTH', 'PRIVATE_KEY', 'API_KEY'})
_NON_SECRET_NAME_TOKENS = frozenset({'SOCK', 'SOCKET', 'DIR', 'PATH', 'HOME', 'FILE', 'PROMPT', 'HOST', 'PORT', 'REPO', 'USER'})
_ESSENTIAL_GIT_RUNTIME_VARS = frozenset({
    'PATH', 'PATHEXT', 'SYSTEMROOT', 'WINDIR', 'COMSPEC', 'TEMP', 'TMP', 'TMPDIR',
    'LANG', 'LC_ALL', 'LC_CTYPE', 'TERM', 'TZ', 'MSYSTEM', 'CHERE_INVOKING',
    'PROGRAMFILES', 'PROGRAMFILES(X86)', 'COMMONPROGRAMFILES', 'PROGRAMDATA',
    'OS', 'NUMBER_OF_PROCESSORS', 'PROCESSOR_ARCHITECTURE', 'APPDATA', 'LOCALAPPDATA',
    'SYSTEMDRIVE',
})
_SSH_RUNTIME_VARS = frozenset({'HOME', 'USERPROFILE', 'HOMEDRIVE', 'HOMEPATH', 'SSH_AUTH_SOCK', 'SSH_AGENT_PID', 'GIT_SSH', 'GIT_SSH_COMMAND'})
_WIN_CREATE_NEW_PROCESS_GROUP = 0x00000200
_WIN_CREATE_SUSPENDED = 0x00000004


class ReleaseError(RuntimeError): pass


def need(condition, message):
    if not condition: raise ReleaseError(message)


def sha256(path): return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def register_secret(secret):
    if secret is None:
        return
    sval = str(secret)
    if len(sval) >= 3 and sval.lower() not in {'0', '1', 'true', 'false'}:
        _REGISTERED_SECRETS.add(sval)


def _is_secret_env_key(key):
    if key in _EXPLICIT_SECRET_KEYS:
        return True
    upper = str(key).upper()
    parts = set(re.split(r'[^A-Z0-9]+', upper)) - {''}
    if parts & _NON_SECRET_NAME_TOKENS:
        return False
    if 'PRIVATE_KEY' in upper or 'API_KEY' in upper:
        return True
    if parts & _SECRET_KEY_EXACT_TOKENS:
        return True
    return any(tok in upper for tok in ('TOKEN', 'SECRET', 'PASSWORD', 'STOREPASS', 'KEYPASS', 'CREDENTIAL'))


def _extract_secrets_from_env(env_map, min_len=3):
    found = []
    if not env_map:
        return ()
    for key, val in env_map.items():
        if not val:
            continue
        sval = str(val)
        if _is_secret_env_key(key) and len(sval) >= min_len and sval.lower() not in {'0', '1', 'true', 'false'}:
            found.append(sval)
            register_secret(sval)
    return tuple(found)


def _mixed_percent_regex(secret):
    if not secret or len(secret) < 3:
        return None
    enc = urllib.parse.quote(secret, safe='')
    if enc == secret and '+' not in secret and ' ' not in secret:
        return None
    parts = []
    for ch in secret:
        if ch == ' ':
            parts.append(r'(?: |\+|(?i:%20))')
        else:
            hex_b = ''.join(f'%{byte:02x}' for byte in ch.encode('utf-8'))
            parts.append(rf'(?:{re.escape(ch)}|(?i:{re.escape(hex_b)}))')
    return re.compile(''.join(parts))


def _collect_active_secrets(extra_secrets=()):
    values = set(_REGISTERED_SECRETS)
    for item in extra_secrets:
        if item: values.add(str(item))
    for item in _extract_secrets_from_env(os.environ, min_len=4):
        values.add(item)
    expanded = set(values)
    for val in values:
        expanded.add(base64.b64encode(('x-access-token:' + val).encode('utf8')).decode('ascii'))
    return tuple(sorted((v for v in expanded if v), key=len, reverse=True))


def redact_secrets(text, extra_secrets=()):
    if not text:
        return text
    cleaned = str(text)
    active = _collect_active_secrets(extra_secrets)
    for secret in active:
        cleaned = cleaned.replace(secret, '[REDACTED]')
        pat = _mixed_percent_regex(secret)
        if pat is not None:
            cleaned = pat.sub('[REDACTED]', cleaned)
    cleaned = re.sub(r'(?i)(Authorization\s*:\s*)(?:Bearer|Basic|token)\s+\S+', r'\1[REDACTED]', cleaned)
    cleaned = re.sub(r'(?i)((?:https?|ssh|jdbc:mysql)://)[^\s/@]+@', r'\1[REDACTED]@', cleaned)
    cleaned = re.sub(r'(?i)(https?://[^\s\'"<>?#]+)\?[^\s\'"<>#]*(?:#[^\s\'"<>]*)?', r'\1?[REDACTED]', cleaned)
    cleaned = re.sub(r'(?i)(https?://[^\s\'"<>?#]+)#[^\s\'"<>]*', r'\1#[REDACTED]', cleaned)
    return cleaned


def _apply_isolated_home_env(clean, home_dir):
    home_str = str(home_dir)
    clean['HOME'] = home_str
    clean['USERPROFILE'] = home_str
    clean['XDG_CONFIG_HOME'] = home_str
    clean['XDG_CACHE_HOME'] = home_str
    if os.name == 'nt':
        drive, tail = os.path.splitdrive(home_str)
        if drive:
            clean['HOMEDRIVE'] = drive
            clean['HOMEPATH'] = tail or '\\'
    else:
        clean.pop('HOMEDRIVE', None)
        clean.pop('HOMEPATH', None)


def sanitize_git_env(base_env, empty_gitconfig_path=None, empty_home_path=None, allow_controlled_askpass=False, keep_ssh_env=False):
    clean = {}
    ctx_meta = _CONTROLLED_GIT_CONTEXTS.get(str(empty_gitconfig_path)) if empty_gitconfig_path is not None else None
    effective_keep_ssh = bool(keep_ssh_env or (ctx_meta is not None and ctx_meta.get('keep_ssh_env')))
    allowed_keys = set(_ESSENTIAL_GIT_RUNTIME_VARS)
    if effective_keep_ssh:
        allowed_keys |= _SSH_RUNTIME_VARS
    for key, val in base_env.items():
        upper = key.upper()
        if (
            upper.startswith('GIT_TRACE')
            or upper in {
                'GIT_CURL_VERBOSE', 'GIT_REDACT_COOKIES', 'GIT_ASKPASS', 'SSH_ASKPASS',
                'GIT_CONFIG_PARAMETERS', 'GIT_CONFIG_COUNT', 'GIT_CONFIG',
                'GIT_CONFIG_GLOBAL', 'GIT_CONFIG_SYSTEM', 'GIT_CONFIG_NOSYSTEM',
                'GIT_SSL_NO_VERIFY', 'NETRC', 'CURL_CA_BUNDLE',
            }
            or upper.startswith('GCM_')
            or upper.startswith('GIT_CREDENTIAL_')
        ):
            continue
        if upper in allowed_keys or key in allowed_keys:
            clean[key] = val
    clean['GIT_TERMINAL_PROMPT'] = '0'
    clean['GIT_CONFIG_NOSYSTEM'] = '1'
    if empty_gitconfig_path is not None:
        clean['GIT_CONFIG_GLOBAL'] = str(empty_gitconfig_path)
    resolved_home = empty_home_path
    if resolved_home is None and ctx_meta is not None and not effective_keep_ssh:
        resolved_home = ctx_meta.get('empty_home')
    if resolved_home is not None:
        _apply_isolated_home_env(clean, resolved_home)
    if allow_controlled_askpass:
        candidate_askpass = base_env.get('GIT_ASKPASS')
        expected_cfg = _CONTROLLED_ASKPASS_TOKENS.get(candidate_askpass)
        if candidate_askpass and expected_cfg is not None and str(clean.get('GIT_CONFIG_GLOBAL', '')) == expected_cfg:
            clean['GIT_ASKPASS'] = candidate_askpass
            if 'ORIVA_GIT_HOME' in base_env and resolved_home is None:
                _apply_isolated_home_env(clean, base_env['ORIVA_GIT_HOME'])
            for k in ('ORIVA_GIT_TOKEN', 'ORIVA_GIT_EXPECTED_HOST', 'ORIVA_GIT_EXPECTED_USER', 'ORIVA_GIT_EXPECTED_REPO', 'ORIVA_GIT_EXPECTED_PORT'):
                if k in base_env:
                    clean[k] = base_env[k]
    return clean


def _remove_temp_dir_with_diag(path_obj, label, extra_secrets=(), raise_on_failure=True):
    cleanup_error = None
    for attempt in range(3):
        try:
            if path_obj.exists():
                shutil.rmtree(path_obj)
            cleanup_error = None
            break
        except OSError as exc:
            cleanup_error = exc
            time.sleep(0.05 * (attempt + 1))
    if cleanup_error is not None:
        msg = redact_secrets(f'Failed to remove temporary {label} {path_obj}: {cleanup_error}', extra_secrets)
        print('WARNING:', msg, file=sys.stderr)
        if raise_on_failure:
            raise ReleaseError(msg) from cleanup_error


@contextmanager
def isolated_git_env(base_env=None, anonymous=False, keep_ssh_env=False):
    cfg_dir = Path(tempfile.mkdtemp(prefix='oriva-git-cfg-'))
    in_body_exception = False
    raw_env = base_env if base_env is not None else os.environ
    extra_secrets = _extract_secrets_from_env(raw_env)
    cfg_key = None
    try:
        if os.name != 'nt':
            cfg_dir.chmod(0o700)
        empty_cfg = cfg_dir / 'empty.gitconfig'
        empty_cfg.write_bytes(b'')
        empty_home = cfg_dir / 'home'
        empty_home.mkdir(parents=True, exist_ok=True)
        eff_keep_ssh = bool(keep_ssh_env and not anonymous)
        eff_home = None if eff_keep_ssh else empty_home
        cfg_key = str(empty_cfg)
        _CONTROLLED_GIT_CONTEXTS[cfg_key] = {'empty_home': eff_home, 'keep_ssh_env': eff_keep_ssh}
        try:
            yield sanitize_git_env(
                raw_env,
                empty_gitconfig_path=empty_cfg,
                empty_home_path=eff_home,
                allow_controlled_askpass=not anonymous,
                keep_ssh_env=eff_keep_ssh,
            )
        except BaseException:
            in_body_exception = True
            raise
    finally:
        if cfg_key is not None:
            _CONTROLLED_GIT_CONTEXTS.pop(cfg_key, None)
        _remove_temp_dir_with_diag(cfg_dir, 'isolated Git config directory', extra_secrets=extra_secrets, raise_on_failure=not in_body_exception)


class _WinThreadEntry32(ctypes.Structure):
    _fields_ = [
        ('dwSize', ctypes.c_uint32),
        ('cntUsage', ctypes.c_uint32),
        ('th32ThreadID', ctypes.c_uint32),
        ('th32OwnerProcessID', ctypes.c_uint32),
        ('tpBasePri', ctypes.c_long),
        ('tpDeltaPri', ctypes.c_long),
        ('dwFlags', ctypes.c_uint32),
    ]


def _create_windows_job_for_process(proc):
    if os.name != 'nt':
        return None, None
    try:
        import ctypes.wintypes as wt
        k32 = ctypes.WinDLL('kernel32', use_last_error=True)
        k32.CreateJobObjectW.argtypes = [wt.LPVOID, wt.LPCWSTR]
        k32.CreateJobObjectW.restype = wt.HANDLE
        k32.AssignProcessToJobObject.argtypes = [wt.HANDLE, wt.HANDLE]
        k32.AssignProcessToJobObject.restype = wt.BOOL
        k32.TerminateJobObject.argtypes = [wt.HANDLE, wt.UINT]
        k32.TerminateJobObject.restype = wt.BOOL
        k32.CloseHandle.argtypes = [wt.HANDLE]
        k32.CloseHandle.restype = wt.BOOL

        job = k32.CreateJobObjectW(None, None)
        if not job:
            return None, f'CreateJobObjectW failed (winerr={ctypes.get_last_error()})'
        if not k32.AssignProcessToJobObject(job, int(proc._handle)):
            err = ctypes.get_last_error()
            k32.CloseHandle(job)
            return None, f'AssignProcessToJobObject failed (winerr={err})'
        return (k32, job), None
    except Exception as exc:
        return None, f'Windows job setup failed: {exc}'


def _resume_windows_process_threads(pid):
    import ctypes.wintypes as wt
    k32 = ctypes.WinDLL('kernel32', use_last_error=True)
    k32.CreateToolhelp32Snapshot.argtypes = [wt.DWORD, wt.DWORD]
    k32.CreateToolhelp32Snapshot.restype = wt.HANDLE
    k32.Thread32First.argtypes = [wt.HANDLE, ctypes.POINTER(_WinThreadEntry32)]
    k32.Thread32First.restype = wt.BOOL
    k32.Thread32Next.argtypes = [wt.HANDLE, ctypes.POINTER(_WinThreadEntry32)]
    k32.Thread32Next.restype = wt.BOOL
    k32.OpenThread.argtypes = [wt.DWORD, wt.BOOL, wt.DWORD]
    k32.OpenThread.restype = wt.HANDLE
    k32.ResumeThread.argtypes = [wt.HANDLE]
    k32.ResumeThread.restype = wt.DWORD
    k32.CloseHandle.argtypes = [wt.HANDLE]
    k32.CloseHandle.restype = wt.BOOL

    snap = k32.CreateToolhelp32Snapshot(0x00000004, 0)
    if not snap or snap == wt.HANDLE(-1).value:
        return f'CreateToolhelp32Snapshot failed (winerr={ctypes.get_last_error()})'
    resumed = 0
    try:
        te = _WinThreadEntry32(dwSize=ctypes.sizeof(_WinThreadEntry32))
        if k32.Thread32First(snap, ctypes.byref(te)):
            while True:
                if te.th32OwnerProcessID == pid:
                    th = k32.OpenThread(0x0002, False, te.th32ThreadID)
                    if th:
                        try:
                            if k32.ResumeThread(th) != 0xFFFFFFFF:
                                resumed += 1
                        finally:
                            k32.CloseHandle(th)
                if not k32.Thread32Next(snap, ctypes.byref(te)):
                    break
    finally:
        k32.CloseHandle(snap)
    if resumed == 0:
        return f'No suspended threads resumed for pid {pid}'
    return None


def _close_windows_job(job_info):
    if job_info is not None:
        k32, job = job_info
        try:
            k32.CloseHandle(job)
        except Exception:
            pass


def _terminate_process_tree(proc, job_info=None, attach_diag=None):
    """Terminate the full process tree even if the direct child already exited."""
    diags = []
    if attach_diag:
        diags.append(attach_diag)
    parent_alive = proc.poll() is None

    if os.name == 'nt':
        job_killed = False
        if job_info is not None:
            k32, job = job_info
            try:
                if k32.TerminateJobObject(job, 1):
                    job_killed = True
                else:
                    diags.append(f'TerminateJobObject failed (winerr={ctypes.get_last_error()})')
            except Exception as exc:
                diags.append(f'TerminateJobObject exception: {exc}')
        if parent_alive or not job_killed:
            try:
                tk = subprocess.run(
                    ['taskkill', '/F', '/T', '/PID', str(proc.pid)],
                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=10, check=False,
                )
                if tk.returncode != 0 and (parent_alive or not job_killed):
                    detail = (tk.stderr or tk.stdout or '').strip()
                    diags.append(f'taskkill rc={tk.returncode}: {detail}')
            except Exception as exc:
                diags.append(f'taskkill exception: {exc}')
        try:
            if proc.poll() is None:
                proc.kill()
        except Exception as exc:
            diags.append(f'proc.kill exception: {exc}')
    else:
        try:
            os.killpg(proc.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        except OSError as exc:
            diags.append(f'killpg failed: {exc}')
        try:
            proc.kill()
        except ProcessLookupError:
            pass
        except OSError as exc:
            diags.append(f'proc.kill failed: {exc}')
    return diags


class _PipeCollector:
    """Non-blocking daemon collector avoiding BufferedReader.close() deadlocks."""

    def __init__(self, stream):
        self.stream = stream
        self.chunks = []
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()

    def _run(self):
        try:
            fd = self.stream.fileno()
            while True:
                try:
                    data = os.read(fd, 8192)
                except OSError:
                    break
                if not data:
                    break
                self.chunks.append(data)
        finally:
            try:
                self.stream.close()
            except OSError:
                pass

    def join(self, timeout):
        self._thread.join(timeout=timeout)
        return not self._thread.is_alive()

    def data(self):
        return b''.join(list(self.chunks))


def _drain_after_termination(proc, collectors, drain_timeout=PIPE_DRAIN_TIMEOUT_SECONDS):
    diags = []
    deadline = time.monotonic() + drain_timeout
    all_drained = True
    for collector in collectors:
        if collector is None:
            continue
        rem = max(0.0, deadline - time.monotonic())
        if not collector.join(rem):
            all_drained = False
    if not all_drained:
        # Never call buffered .close() from main thread: daemon os.read holds pipe read until writer exits.
        diags.append(f'pipe drain timed out after {drain_timeout}s; detached daemon pipe reader to avoid stream lock deadlock')
    try:
        proc.wait(timeout=1)
    except subprocess.TimeoutExpired:
        diags.append('process did not finalize within 1s after tree termination')
    out_b = collectors[0].data() if len(collectors) >= 1 and collectors[0] is not None else b''
    err_b = collectors[1].data() if len(collectors) >= 2 and collectors[1] is not None else b''
    return out_b, err_b, diags


def _run_bounded_subprocess(actual_cmd, cwd, env, stderr_target, timeout=None, shell=False, extra_secrets=(), drain_timeout=PIPE_DRAIN_TIMEOUT_SECONDS):
    popen_kwargs = dict(
        cwd=cwd,
        env=env,
        stdout=subprocess.PIPE,
        stderr=stderr_target,
        shell=shell,
    )
    suspended_spawn = False
    if os.name == 'nt':
        popen_kwargs['creationflags'] = _WIN_CREATE_NEW_PROCESS_GROUP | _WIN_CREATE_SUSPENDED
        suspended_spawn = True
    else:
        popen_kwargs['start_new_session'] = True

    proc = subprocess.Popen(actual_cmd, **popen_kwargs)
    job_info, attach_diag = _create_windows_job_for_process(proc)
    if suspended_spawn:
        if job_info is None:
            try:
                proc.kill()
                proc.wait(timeout=1)
            except Exception:
                pass
            _close_windows_job(job_info)
            raise ReleaseError(redact_secrets(f'Failed to attach Windows Job Object before resume: {attach_diag}', extra_secrets))
        resume_diag = _resume_windows_process_threads(proc.pid)
        if resume_diag is not None:
            _terminate_process_tree(proc, job_info=job_info)
            _close_windows_job(job_info)
            try:
                proc.wait(timeout=1)
            except Exception:
                pass
            raise ReleaseError(redact_secrets(f'Failed to resume suspended process after Job bind: {resume_diag}', extra_secrets))

    out_collector = _PipeCollector(proc.stdout)
    err_collector = _PipeCollector(proc.stderr) if stderr_target == subprocess.PIPE else None
    collectors = (out_collector, err_collector)
    end_deadline = None if timeout is None else (time.monotonic() + timeout)
    try:
        try:
            proc.wait(timeout=timeout)
            for collector in collectors:
                if collector is None:
                    continue
                if end_deadline is None:
                    collector.join(None)
                else:
                    rem = max(0.0, end_deadline - time.monotonic())
                    if not collector.join(rem):
                        raise subprocess.TimeoutExpired(actual_cmd, timeout)
            return False, proc.returncode, out_collector.data(), (err_collector.data() if err_collector else b''), []
        except subprocess.TimeoutExpired:
            tree_diags = _terminate_process_tree(proc, job_info=job_info, attach_diag=attach_diag)
            out_b, err_b, drain_diags = _drain_after_termination(proc, collectors, drain_timeout=drain_timeout)
            return True, proc.returncode, out_b, err_b, tree_diags + drain_diags
    finally:
        _close_windows_job(job_info)
        for stream in (proc.stdout, proc.stderr):
            if stream is not None:
                # Only close if no collector thread is still blocked in os.read
                collector = out_collector if stream is proc.stdout else err_collector
                if collector is not None and not collector._thread.is_alive():
                    try:
                        stream.close()
                    except OSError:
                        pass


def _decode_bytes(raw):
    if not raw:
        return ''
    try:
        return raw.decode('utf8')
    except UnicodeDecodeError:
        return raw.decode('gb18030', errors='replace')


def _run_git_raw(root, *args, timeout=None, env=None, anonymous=False):
    @contextmanager
    def _env_ctx():
        if env is None:
            with isolated_git_env(os.environ, anonymous=anonymous) as iso_env:
                yield iso_env
        elif 'GIT_CONFIG_GLOBAL' not in env:
            with isolated_git_env(env, anonymous=anonymous) as iso_env:
                yield iso_env
        else:
            cfg_key = str(env['GIT_CONFIG_GLOBAL'])
            ctx_meta = _CONTROLLED_GIT_CONTEXTS.get(cfg_key)
            if anonymous:
                if ctx_meta is not None and ctx_meta.get('empty_home') is not None:
                    yield sanitize_git_env(
                        env,
                        empty_gitconfig_path=cfg_key,
                        empty_home_path=ctx_meta['empty_home'],
                        allow_controlled_askpass=False,
                        keep_ssh_env=False,
                    )
                else:
                    with isolated_git_env(env, anonymous=True) as iso_env:
                        yield iso_env
            elif ctx_meta is not None:
                yield sanitize_git_env(
                    env,
                    empty_gitconfig_path=cfg_key,
                    empty_home_path=ctx_meta.get('empty_home'),
                    allow_controlled_askpass=True,
                    keep_ssh_env=bool(ctx_meta.get('keep_ssh_env', False)),
                )
            else:
                yield sanitize_git_env(
                    env,
                    empty_gitconfig_path=cfg_key,
                    allow_controlled_askpass=True,
                )

    effective_timeout = timeout if timeout is not None else DEFAULT_GIT_TIMEOUT_SECONDS
    with _env_ctx() as effective_env:
        extra_secrets = _extract_secrets_from_env(effective_env)
        timed_out, rc, out_b, err_b, diags = _run_bounded_subprocess(
            ['git', *args],
            cwd=root,
            env=effective_env,
            stderr_target=subprocess.PIPE,
            timeout=effective_timeout,
            extra_secrets=extra_secrets,
        )
        if timed_out:
            diag_note = f' ({"; ".join(diags)})' if diags else ''
            cmd = args[0] if args else 'git'
            raise ReleaseError(redact_secrets(f'git {cmd} timed out after {effective_timeout}s{diag_note}', extra_secrets))
        return rc, out_b, err_b, extra_secrets


def git(root, *args, timeout=None, env=None, anonymous=False):
    rc, out_b, err_b, extra_secrets = _run_git_raw(root, *args, timeout=timeout, env=env, anonymous=anonymous)
    out = redact_secrets(_decode_bytes(out_b), extra_secrets)
    err = redact_secrets(_decode_bytes(err_b), extra_secrets)
    if rc != 0:
        raise ReleaseError(f'git {args[0] if args else ""} failed ({rc}): {err.strip() or out.strip()}')
    return out.strip()


def _call_git(root, *args, timeout=None, env=None, anonymous=False):
    params = inspect.signature(git).parameters
    kwargs = {}
    if timeout is not None and 'timeout' in params:
        kwargs['timeout'] = timeout
    if env is not None and 'env' in params:
        kwargs['env'] = env
    if anonymous and 'anonymous' in params:
        kwargs['anonymous'] = anonymous
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


def write_json_atomic(path, data):
    path = Path(path)
    tmp = path.with_name(f'.{path.name}.{os.getpid()}.tmp')
    try:
        tmp.write_text(json.dumps(data, ensure_ascii=False, indent=2)+'\n', encoding='utf8')
        os.replace(tmp, path)
    finally:
        if tmp.exists():
            tmp.unlink(missing_ok=True)


@contextmanager
def _execution_env_ctx(args, env):
    is_git_cmd = bool(args) and Path(str(args[0])).stem.lower() == 'git'
    if not is_git_cmd:
        yield env
        return
    if 'GIT_CONFIG_GLOBAL' not in env:
        with isolated_git_env(env, anonymous=False) as iso_env:
            yield iso_env
    else:
        cfg_key = str(env['GIT_CONFIG_GLOBAL'])
        ctx_meta = _CONTROLLED_GIT_CONTEXTS.get(cfg_key)
        if ctx_meta is not None:
            yield sanitize_git_env(
                env,
                empty_gitconfig_path=cfg_key,
                empty_home_path=ctx_meta.get('empty_home'),
                allow_controlled_askpass=True,
                keep_ssh_env=bool(ctx_meta.get('keep_ssh_env', False)),
            )
        else:
            yield sanitize_git_env(
                env,
                empty_gitconfig_path=cfg_key,
                allow_controlled_askpass=True,
            )


def execute(args, cwd, env, logfile=None, timeout=None):
    actual = list(map(str, args))
    shell = False
    if os.name == 'nt' and str(args[0]).lower().endswith(('.bat', '.cmd')):
        need(not any(any(c in arg for c in '"\r\n%') for arg in actual), 'Unsupported quote/newline/percent in batch argument')
        actual = 'cmd.exe /d /v:off /s /c "' + ' '.join('"' + arg + '"' for arg in actual) + '"'

    secrets = _extract_secrets_from_env(env)
    with _execution_env_ctx(args, env) as effective_env:
        timed_out, rc, raw, _, all_diags = _run_bounded_subprocess(
            actual,
            cwd=cwd,
            env=effective_env,
            stderr_target=subprocess.STDOUT,
            timeout=timeout,
            shell=shell,
            extra_secrets=secrets,
        )
        if timed_out:
            diag_line = f'[TREE_CLEANUP_DIAG: {"; ".join(all_diags)}]\n' if all_diags else ''
            output = redact_secrets(_decode_bytes(raw), secrets) + f'\n[TIMEOUT after {timeout}s]\n' + redact_secrets(diag_line, secrets)
            if logfile: Path(logfile).write_text(output, encoding='utf8')
            raise ReleaseError(f'Command timed out after {timeout}s ({Path(str(args[0])).name}); see {logfile or "terminal"}')

    output = redact_secrets(_decode_bytes(raw), secrets)
    if logfile: Path(logfile).write_text(output, encoding='utf8')
    need(rc == 0, f'Command failed ({Path(str(args[0])).name}); see {logfile or "terminal"}')
    return output


def _call_execute(args, cwd, env, logfile=None, timeout=None):
    params = inspect.signature(execute).parameters
    if timeout is not None and 'timeout' in params:
        return execute(args, cwd, env, logfile, timeout=timeout)
    return execute(args, cwd, env, logfile)


def audit_archive(z):
    """Audit public source archive and supported PK-prefixed nested ZIPs (including directory paths)."""
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

    def validate_member_rel(raw_name, parsed_rel, full_label, orig_name=None, is_dir=False):
        orig = raw_name if orig_name is None else orig_name
        need(
            bool(raw_name)
            and '\\' not in raw_name
            and '\\' not in orig
            and '\x00' not in raw_name
            and '\x00' not in orig
            and not raw_name.startswith('/')
            and not orig.startswith('/')
            and not re.match(r'^[A-Za-z]:', raw_name)
            and not re.match(r'^[A-Za-z]:', orig),
            'Unsafe or absolute archive path: ' + full_label,
        )
        raw_segs = [seg for seg in raw_name.split('/') if seg]
        orig_segs = [seg for seg in orig.split('/') if seg]
        need(
            '..' not in parsed_rel.parts
            and '..' not in raw_segs
            and '..' not in orig_segs
            and not parsed_rel.is_absolute()
            and not forbidden_lower.intersection(p.lower() for p in parsed_rel.parts)
            and (is_dir or parsed_rel.suffix.lower() not in forbidden_suffixes)
            and not parsed_rel.name.lower().startswith('.env')
            and parsed_rel.name.lower() != 'local.properties',
            'Private/unsafe archive member: ' + full_label,
        )

    def scan(data, label, depth=0):
        need(not any(re.search(p, data) for p in secret_patterns), 'Sensitive content found: '+label)
        # Recursive nested archive auditing explicitly covers ZIP payloads (PK-prefixed headers).
        if data[:2] == b'PK':
            need(depth < 4, 'Nested archive requires manual review: '+label)
            with zipfile.ZipFile(io.BytesIO(data)) as nested:
                for item in nested.infolist():
                    raw_nested = item.filename.rstrip('/') if item.is_dir() else item.filename
                    nested_path = PurePosixPath(raw_nested) if raw_nested else PurePosixPath('.')
                    validate_member_rel(
                        item.filename, nested_path, label + '!' + item.filename,
                        orig_name=getattr(item, 'orig_filename', item.filename),
                        is_dir=item.is_dir(),
                    )
                    if item.is_dir():
                        continue
                    need(item.file_size <= 50_000_000, 'Large archive member requires review: '+label)
                    scan(nested.read(item), label+'!'+item.filename, depth+1)

    count = 0
    readme_screenshots = {'docs/screenshots/today-ink.png', 'docs/screenshots/today-atlas.png', 'docs/screenshots/today-phosphor.png'}
    allowed_dirs = {'docs', 'docs/screenshots', '.github', '.github/workflows'}
    for item in z.infolist():
        normalized = item.filename.rstrip('/') if item.is_dir() else item.filename
        path = PurePosixPath(normalized) if normalized else PurePosixPath('.')
        orig = getattr(item, 'orig_filename', item.filename)
        if item.is_dir() and len(path.parts) <= 1 and '..' not in path.parts:
            validate_member_rel(item.filename, path, str(item.filename), orig_name=orig, is_dir=True)
            continue
        rel = PurePosixPath(*path.parts[1:]) if len(path.parts) >= 2 else path
        validate_member_rel(item.filename, rel, str(rel), orig_name=orig, is_dir=item.is_dir())
        if item.is_dir():
            need(
                rel.as_posix() in allowed_dirs or rel.parts[0] in {'android', 'backend', 'deploy'},
                'Outside public boundary: ' + str(rel),
            )
            continue
        count += 1
        need(rel.as_posix() in {'README.md','LICENSE','.gitignore'} | readme_screenshots or rel.parts[0] in {'android','backend','deploy'} or rel.parts[:2] == ('.github','workflows'), 'Outside public boundary: '+str(rel))
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
    git(
        root, '-c', 'core.autocrlf=false', 'archive', '--format=zip',
        '--prefix=zhixu-oriva-'+name+'/', '--output='+str(output), commit,
        timeout=DEFAULT_GIT_TIMEOUT_SECONDS, anonymous=True,
    )
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
        parsed = urllib.parse.urlsplit(str(url))
        scheme = (parsed.scheme or '').lower()
        host = (parsed.hostname or '').lower()
        port_part = f':{parsed.port}' if parsed.port is not None else ''
        return redact_secrets(f'{scheme}://{host}{port_part}{parsed.path}')
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
        register_secret(token)
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
            raise ReleaseError(f'GitHub HTTP {error.code} for {method} {sanitize_url_for_log(url)}') from None
        except urllib.error.URLError as error:
            raise ReleaseError(redact_secrets(f'GitHub API request failed for {method} {sanitize_url_for_log(url)}: {error.reason}', (token,))) from None


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

prompt = " ".join(sys.argv[1:]).strip()
match = re.fullmatch(r"(Username|Password) for '(https://[^']+)'(?::\\s*)?", prompt)
if not match:
    sys.stderr.write("Refused GIT_ASKPASS prompt with unexpected format\\n")
    sys.exit(1)

kind, raw_url = match.group(1), match.group(2)
parsed = urllib.parse.urlsplit(raw_url)
expected_host = os.environ.get("ORIVA_GIT_EXPECTED_HOST", "github.com").lower()
expected_user = os.environ.get("ORIVA_GIT_EXPECTED_USER", "x-access-token")
expected_repo = os.environ.get("ORIVA_GIT_EXPECTED_REPO", "").strip("/")
try:
    expected_port = int(os.environ.get("ORIVA_GIT_EXPECTED_PORT", "443"))
except ValueError:
    sys.stderr.write("Invalid ORIVA_GIT_EXPECTED_PORT\\n")
    sys.exit(1)
if not expected_repo:
    sys.stderr.write("Missing ORIVA_GIT_EXPECTED_REPO\\n")
    sys.exit(1)
try:
    port = parsed.port
except ValueError:
    sys.stderr.write("Refused GIT_ASKPASS malformed port\\n")
    sys.exit(1)

allowed_ports = (None, 443) if expected_port == 443 else (expected_port,)
if (
    parsed.scheme.lower() != "https"
    or (parsed.hostname or "").lower() != expected_host
    or port not in allowed_ports
    or parsed.query != ""
    or parsed.fragment != ""
    or parsed.password is not None
):
    sys.stderr.write("Refused GIT_ASKPASS request for unexpected origin\\n")
    sys.exit(1)

allowed_paths = {f"/{expected_repo}", f"/{expected_repo}.git"}
if parsed.path not in allowed_paths:
    sys.stderr.write("Refused GIT_ASKPASS request for unexpected repository path\\n")
    sys.exit(1)

if kind == "Username":
    if parsed.username not in (None, "", expected_user):
        sys.stderr.write("Refused GIT_ASKPASS username prompt for unexpected user\\n")
        sys.exit(1)
    print(expected_user)
elif kind == "Password":
    if parsed.username not in (None, expected_user):
        sys.stderr.write("Refused GIT_ASKPASS password request for unexpected user\\n")
        sys.exit(1)
    token = os.environ.get("ORIVA_GIT_TOKEN", "")
    if not token:
        sys.stderr.write("Missing ORIVA_GIT_TOKEN\\n")
        sys.exit(1)
    print(token)
"""


@contextmanager
def git_askpass_env(base_env, token, expected_host='github.com', expected_user='x-access-token', expected_repo='', expected_port=443):
    need(token, 'Set github_token or GITHUB_TOKEN for HTTPS git operation')
    need(isinstance(expected_port, int) and 1 <= expected_port <= 65535, 'Invalid expected_port')
    normalized_repo = (expected_repo or '').strip('/')
    need(bool(re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', normalized_repo)), 'Repository context required for controlled GIT_ASKPASS')
    register_secret(token)
    askpass_dir = Path(tempfile.mkdtemp(prefix='oriva-git-askpass-'))
    in_body_exception = False
    helper_str = None
    cfg_key = None
    try:
        if os.name != 'nt':
            askpass_dir.chmod(0o700)
        empty_cfg = askpass_dir / 'empty.gitconfig'
        empty_cfg.write_bytes(b'')
        py_script = askpass_dir / 'askpass.py'
        py_script.write_text(_ASKPASS_BODY, encoding='utf8', newline='\n')
        py_script.chmod(0o700)
        if os.name == 'nt':
            helper = askpass_dir / 'askpass.sh'
            helper.write_text(
                f'#!/bin/sh\nexec {shlex.quote(Path(sys.executable).as_posix())} {shlex.quote(py_script.as_posix())} "$@"\n',
                encoding='utf8',
                newline='\n',
            )
            helper.chmod(0o700)
            helper_str = helper.as_posix()
        else:
            helper_str = str(py_script)
        cfg_key = str(empty_cfg)
        _CONTROLLED_ASKPASS_TOKENS[helper_str] = cfg_key
        empty_home = askpass_dir / 'home'
        empty_home.mkdir(parents=True, exist_ok=True)
        _CONTROLLED_GIT_CONTEXTS[cfg_key] = {'empty_home': empty_home, 'keep_ssh_env': False}
        git_env = sanitize_git_env(base_env, empty_gitconfig_path=empty_cfg, empty_home_path=empty_home, allow_controlled_askpass=False)
        git_env['GIT_ASKPASS'] = helper_str
        git_env['ORIVA_GIT_HOME'] = str(empty_home)
        git_env['ORIVA_GIT_TOKEN'] = token
        git_env['ORIVA_GIT_EXPECTED_HOST'] = expected_host
        git_env['ORIVA_GIT_EXPECTED_USER'] = expected_user
        git_env['ORIVA_GIT_EXPECTED_REPO'] = normalized_repo
        git_env['ORIVA_GIT_EXPECTED_PORT'] = str(expected_port)
        try:
            yield git_env
        except BaseException:
            in_body_exception = True
            raise
    finally:
        if helper_str is not None:
            _CONTROLLED_ASKPASS_TOKENS.pop(helper_str, None)
        if cfg_key is not None:
            _CONTROLLED_GIT_CONTEXTS.pop(cfg_key, None)
        _remove_temp_dir_with_diag(askpass_dir, 'GIT_ASKPASS directory', extra_secrets=(token,), raise_on_failure=not in_body_exception)


def _parse_refs(output):
    refs = {}
    for line in output.splitlines():
        parts = line.split()
        if len(parts) >= 2:
            refs[parts[1]] = parts[0]
    return refs


def _audit_repo_git_config(root, git_env, timeout=DEFAULT_GIT_TIMEOUT_SECONDS):
    rc, cfg_out_b, cfg_err_b, extra_secrets = _run_git_raw(
        root, 'config', '--list', '-z', timeout=timeout, env=git_env, anonymous=True,
    )
    need(rc == 0, f'Cannot inspect isolated git config: {redact_secrets(_decode_bytes(cfg_err_b), extra_secrets)}')
    for raw_entry in cfg_out_b.split(b'\x00'):
        if not raw_entry:
            continue
        entry = _decode_bytes(raw_entry)
        key, _, val = entry.partition('\n')
        low_key = key.lower()
        if low_key.endswith('.insteadof') or low_key.endswith('.pushinsteadof'):
            raise ReleaseError(f'Git URL rewrite rule ({key}) is forbidden')
        if low_key == 'http.sslverify' or (low_key.startswith('http.') and low_key.endswith('.sslverify')):
            if val.strip().lower() not in {'true', '1', 'yes', 'on'}:
                raise ReleaseError(f'Git TLS verification downgrade ({key}={val}) is forbidden')
        if low_key == 'http.followredirects' or (low_key.startswith('http.') and low_key.endswith('.followredirects')):
            if val.strip().lower() not in {'false', '0', 'no', 'off'}:
                raise ReleaseError(f'Git HTTP redirect rule ({key}={val}) is forbidden')
        if low_key == 'http.extraheader' or (low_key.startswith('http.') and low_key.endswith('.extraheader')):
            raise ReleaseError(f'Git undeclared HTTP header ({key}) is forbidden')
        if low_key == 'http.cookiefile' or (low_key.startswith('http.') and low_key.endswith('.cookiefile')):
            raise ReleaseError(f'Git HTTP cookie file ({key}) is forbidden')
        if low_key == 'http.savecookies' or (low_key.startswith('http.') and low_key.endswith('.savecookies')):
            if val.strip().lower() not in {'false', '0', 'no', 'off'}:
                raise ReleaseError(f'Git HTTP cookie saving ({key}={val}) is forbidden')
        if low_key == 'core.sshcommand':
            raise ReleaseError(f'Git repository-level core.sshCommand ({val}) is forbidden')
        if low_key.startswith('credential.') and low_key != 'credential.usehttppath' and low_key.endswith('.helper'):
            raise ReleaseError(f'Git URL-scoped credential helper ({key}) is forbidden')


def _verify_origin_push_urls(root, repo, allowed_remotes, git_env, timeout=DEFAULT_GIT_TIMEOUT_SECONDS, ssh_mode=False):
    _audit_repo_git_config(root, git_env, timeout=timeout)

    rc_org, out_org_b, err_org_b, org_secrets = _run_git_raw(
        root, 'config', '--get-all', 'remote.origin.url', timeout=timeout, env=git_env, anonymous=True,
    )
    need(rc_org == 0, f'Cannot inspect remote.origin.url: {redact_secrets(_decode_bytes(err_org_b).strip(), org_secrets)}')
    fetch_urls = [line.strip() for line in _decode_bytes(out_org_b).splitlines() if line.strip()]
    need(len(fetch_urls) == 1, f'origin must have exactly one fetch URL, found {len(fetch_urls)}')
    need(fetch_urls[0] in allowed_remotes, f'origin URL does not match {repo}: {sanitize_url_for_log(fetch_urls[0])}')

    rc_push, out_push_b, err_push_b, push_secrets = _run_git_raw(
        root, 'remote', 'get-url', '--push', '--all', 'origin', timeout=timeout, env=git_env, anonymous=True,
    )
    need(rc_push == 0, f'Cannot inspect origin push URLs: {redact_secrets(_decode_bytes(err_push_b).strip(), push_secrets)}')
    push_urls = [line.strip() for line in _decode_bytes(out_push_b).splitlines() if line.strip()]
    need(len(push_urls) == 1, f'origin must have exactly one push URL, found {len(push_urls)}')
    need(push_urls[0] in allowed_remotes, f'origin push URL does not match {repo}: {sanitize_url_for_log(push_urls[0])}')
    if ssh_mode:
        need(
            push_urls[0].startswith('git@') or push_urls[0].startswith('ssh://'),
            f'SSH mode forbids non-SSH push URL: {sanitize_url_for_log(push_urls[0])}',
        )
    return fetch_urls[0], push_urls[0]


def publish(root, plan, args, env):
    require_clean(root); directory, m, names = load_manifest(plan); commit = m['commit']; tag = plan['tag']; w = Path(plan['evidence']); w.mkdir(parents=True, exist_ok=True)
    need(git(root, 'rev-parse', 'HEAD') == commit, 'Current commit differs from packaged source')
    need(args.notes, 'Provide --notes with this version release scope and limitations')
    notes = Path(args.notes).read_text('utf8'); need(bool(notes.strip()), 'Empty release notes')
    notes += '\n\nCommit: `' + commit + '`\n\nCertificate SHA-256: `' + m['certificateSHA256'] + '`\n\n| File | Bytes | SHA-256 |\n|---|---:|---|\n'
    for name in names: notes += f'| `{name}` | {(directory/name).stat().st_size} | `{sha256(directory/name).upper()}` |\n'

    allowed_remotes = {'git@github.com:'+args.repo+'.git', 'https://github.com/'+args.repo+'.git', 'https://github.com/'+args.repo}
    push_protocol = getattr(args, 'push_protocol', 'auto')
    git_timeout = getattr(args, 'git_timeout', DEFAULT_GIT_TIMEOUT_SECONDS)
    upload_timeout = getattr(args, 'upload_timeout', DEFAULT_UPLOAD_TIMEOUT_SECONDS)
    need(push_protocol in {'auto', 'ssh', 'https'}, 'Unsupported push protocol')
    need(git_timeout >= 1, 'git timeout must be positive')
    need(upload_timeout >= 1, 'upload timeout must be positive')

    https_url = 'https://github.com/' + args.repo + '.git'
    token = env.get('github_token') or env.get('GITHUB_TOKEN') or os.environ.get('github_token') or os.environ.get('GITHUB_TOKEN')
    register_secret(token)

    # Explicit SSH push mode trusts the host SSH client & agent; HTTPS push is isolated under controlled GIT_ASKPASS.
    with isolated_git_env(env, anonymous=True) as probe_env, isolated_git_env(env, anonymous=False, keep_ssh_env=True) as ssh_env:
        clean_env = probe_env
        remote = _call_git(root, 'remote', 'get-url', 'origin', env=clean_env, anonymous=True)
        need(remote in allowed_remotes, 'origin does not match selected public repository')
        _, effective_push_url = _verify_origin_push_urls(
            root, args.repo, allowed_remotes, clean_env, timeout=git_timeout, ssh_mode=(push_protocol == 'ssh'),
        )

        is_ssh_remote = remote.startswith('git@') or remote.startswith('ssh://')
        is_ssh_push = effective_push_url.startswith('git@') or effective_push_url.startswith('ssh://')
        use_https_askpass = False
        push_target = 'origin'

        if push_protocol == 'ssh':
            need(is_ssh_remote and is_ssh_push, 'origin is not an SSH remote/push target; cannot use --push-protocol ssh')
            refs = _parse_refs(_call_git(root, 'ls-remote', 'origin', timeout=git_timeout, env=ssh_env, anonymous=False))
        elif push_protocol == 'https':
            push_target = https_url
            use_https_askpass = True
            refs = None
        else:
            if is_ssh_remote and is_ssh_push:
                try:
                    refs = _parse_refs(_call_git(root, 'ls-remote', 'origin', timeout=min(SSH_PROBE_TIMEOUT_SECONDS, git_timeout), env=ssh_env, anonymous=False))
                except (ReleaseError, subprocess.CalledProcessError, OSError):
                    print('SSH remote probe failed; falling back to HTTPS GIT_ASKPASS')
                    _verify_origin_push_urls(root, args.repo, allowed_remotes, clean_env, timeout=git_timeout, ssh_mode=False)
                    push_target = https_url
                    use_https_askpass = True
                    refs = None
            else:
                push_target = https_url
                use_https_askpass = True
                refs = None

        def run_git_push_flow(current_refs, push_env):
            check_remote_tag(current_refs, tag, commit)
            local = _call_git(root, 'tag', '--list', tag, env=clean_env, anonymous=True)
            if local: need(_call_git(root, 'rev-parse', tag+'^{}', env=clean_env, anonymous=True) == commit, 'Existing local tag differs')
            api = Github(args.repo)
            if hasattr(api, 'upload_timeout'):
                api.upload_timeout = upload_timeout
            release = find_release(api, tag, commit, w/'github-release-state.json')
            if release:
                need(release['tag_name'] == tag and release['target_commitish'] in {commit, 'main'}, 'Existing release differs')
                need(current_refs.get('refs/tags/'+tag+'^{}', current_refs.get('refs/tags/'+tag)) == commit, 'Release tag must point at packaged commit')

            git_prefix = (
                ['git', '-c', 'credential.helper=', '-c', 'credential.useHttpPath=true', '-c', 'core.askPass=', '-c', 'http.cookieFile=', '-c', 'http.saveCookies=false', '-c', 'http.followRedirects=false', '-c', 'http.sslVerify=true']
                if use_https_askpass else ['git']
            )
            _call_execute(git_prefix + ['push', '-u', push_target, 'main'], root, push_env, w/'git-push.log', timeout=git_timeout)
            if not local: _call_execute(['git', 'tag', '-a', tag, '-m', 'ORIVA '+plan['version']], root, clean_env, w/'git-tag.log')
            _call_execute(git_prefix + ['push', push_target, tag], root, push_env, w/'git-tag-push.log', timeout=git_timeout)

            if release is None:
                release = api.call(api.base+'/releases', 'POST', dict(tag_name=tag, target_commitish=commit, name='知序 ORIVA '+tag, body=notes, draft=True, prerelease='-' in plan['version']))
            write_json_atomic(w/'github-release-state.json', dict(id=release['id'], commit=commit, tag=tag))
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
            require_https_url(release['html_url'], {'github.com'}, 'Release HTML URL', allow_query=False)
            safe_html_url = redact_secrets(sanitize_url_for_log(release['html_url']))
            release_evidence = dict(
                id=release.get('id'),
                tag_name=release.get('tag_name'),
                target_commitish=release.get('target_commitish'),
                draft=bool(release.get('draft', False)),
                html_url=safe_html_url,
                assets=[dict(name=it.get('name'), size=it.get('size'), digest=it.get('digest')) for it in release.get('assets', [])],
            )
            write_json_atomic(w/'github-published-release.json', release_evidence); print('PUBLISHED', safe_html_url)

        if use_https_askpass:
            with git_askpass_env(clean_env, token, expected_host='github.com', expected_user='x-access-token', expected_repo=args.repo) as askpass_env:
                if refs is None:
                    refs = _parse_refs(_call_git(
                        root, '-c', 'credential.helper=', '-c', 'credential.useHttpPath=true', '-c', 'core.askPass=', '-c', 'http.cookieFile=', '-c', 'http.saveCookies=false', '-c', 'http.followRedirects=false', '-c', 'http.sslVerify=true',
                        'ls-remote', push_target, timeout=git_timeout, env=askpass_env, anonymous=False,
                    ))
                run_git_push_flow(refs, askpass_env)
        else:
            run_git_push_flow(refs, ssh_env)


class _DeadlineSocketAborter:
    """Aborts any registered connection/response sockets immediately once deadline expires."""

    def __init__(self, deadline):
        self.deadline = deadline
        self.expired = False
        self.stage = 'DNS/TCP'
        self._lock = threading.Lock()
        self._sockets = set()
        self._timer = None
        rem = max(0.0, deadline - time.monotonic())
        if rem <= 0.0:
            self.expired = True
        else:
            self._timer = threading.Timer(rem, self.abort)
            self._timer.daemon = True
            self._timer.start()

    def set_stage(self, stage):
        with self._lock:
            self.stage = stage

    def track(self, sock):
        if sock is None:
            return
        should_abort = False
        with self._lock:
            if self.expired:
                should_abort = True
            else:
                self._sockets.add(sock)
        if should_abort:
            self._close_sock(sock)

    def untrack(self, sock):
        if sock is None:
            return
        with self._lock:
            self._sockets.discard(sock)

    @staticmethod
    def _close_sock(sock):
        try:
            # Call base socket.shutdown directly so SSLSocket doesn't block on close_notify or raise TypeError.
            socket.socket.shutdown(sock, socket.SHUT_RDWR)
        except (OSError, ValueError, TypeError):
            pass
        try:
            sock.close()
        except (OSError, ValueError):
            pass

    def abort(self):
        with self._lock:
            self.expired = True
            socks = list(self._sockets)
            self._sockets.clear()
        for sock in socks:
            self._close_sock(sock)

    def stop(self):
        timer = self._timer
        self._timer = None
        if timer is not None:
            timer.cancel()
        with self._lock:
            self._sockets.clear()


def _effective_conn_timeout(conn_timeout, aborter):
    rem = aborter.deadline - time.monotonic()
    if rem <= 0 or aborter.expired:
        raise TimeoutError('Download total timeout exceeded')
    if conn_timeout is None or conn_timeout is socket._GLOBAL_DEFAULT_TIMEOUT:
        return rem
    return max(0.001, min(float(conn_timeout), rem))


def _resolve_addrinfo_with_deadline(host, port, timeout_seconds):
    done = threading.Event()
    result = [None, None]

    def _worker():
        try:
            result[0] = socket.getaddrinfo(host, port, 0, socket.SOCK_STREAM)
        except BaseException as exc:
            result[1] = exc
        finally:
            done.set()

    thread = threading.Thread(target=_worker, daemon=True)
    thread.start()
    if not done.wait(timeout=max(0.001, timeout_seconds)):
        raise TimeoutError(f'DNS resolution timed out for {host}:{port}')
    if result[1] is not None:
        raise result[1]
    return result[0]


def _connect_socket_with_deadline(conn, aborter):
    aborter.set_stage('DNS/TCP')
    timeout_sec = _effective_conn_timeout(conn.timeout, aborter)
    infos = _resolve_addrinfo_with_deadline(conn.host, conn.port, timeout_sec)
    if not infos:
        raise OSError(f'getaddrinfo returns an empty list for {conn.host}:{conn.port}')
    last_exc = None
    for af, socktype, proto, _, sa in infos:
        sock = None
        try:
            rem = _effective_conn_timeout(conn.timeout, aborter)
            sock = socket.socket(af, socktype, proto)
            sock.settimeout(rem)
            if conn.source_address:
                sock.bind(conn.source_address)
            aborter.track(sock)
            sock.connect(sa)
            conn.sock = sock
            if conn._tunnel_host:
                conn._tunnel()
            return
        except BaseException as exc:
            last_exc = exc
            if sock is not None:
                aborter.untrack(sock)
                aborter._close_sock(sock)
    if last_exc is not None:
        raise last_exc


def _make_deadline_http_handlers(aborter):
    class _TrackedHTTPConnection(http.client.HTTPConnection):
        def connect(self):
            _connect_socket_with_deadline(self, aborter)

    class _TrackedHTTPSConnection(http.client.HTTPSConnection):
        def connect(self):
            _connect_socket_with_deadline(self, aborter)
            aborter.set_stage('TLS handshake')
            raw_sock = self.sock
            server_hostname = self._tunnel_host if self._tunnel_host else self.host
            try:
                raw_sock.settimeout(_effective_conn_timeout(self.timeout, aborter))
                ssl_sock = self._context.wrap_socket(
                    raw_sock,
                    server_hostname=server_hostname,
                    do_handshake_on_connect=False,
                )
            except BaseException:
                aborter.untrack(raw_sock)
                aborter._close_sock(raw_sock)
                self.sock = None
                raise
            aborter.untrack(raw_sock)
            aborter.track(ssl_sock)
            self.sock = ssl_sock
            try:
                ssl_sock.settimeout(_effective_conn_timeout(self.timeout, aborter))
                ssl_sock.do_handshake()
            except BaseException:
                aborter.untrack(ssl_sock)
                aborter._close_sock(ssl_sock)
                self.sock = None
                raise

    class _DeadlineHTTPHandler(urllib.request.HTTPHandler):
        def http_open(self, req):
            return self.do_open(_TrackedHTTPConnection, req)

    class _DeadlineHTTPSHandler(urllib.request.HTTPSHandler):
        def https_open(self, req):
            return self.do_open(_TrackedHTTPSConnection, req, context=self._context)

    return _DeadlineHTTPHandler(), _DeadlineHTTPSHandler()


class AllowedAssetRedirectHandler(urllib.request.HTTPRedirectHandler):
    MAX_REDIRECTS = 10

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

    def http_error_302(self, req, fp, code, msg, headers):
        if 'location' in headers:
            newurl = headers['location']
        elif 'uri' in headers:
            newurl = headers['uri']
        else:
            return None
        if self.deadline is not None and time.monotonic() >= self.deadline:
            fp.close()
            raise TimeoutError('Download total timeout exceeded during redirect')
        visited = dict(getattr(req, 'redirect_dict', {}))
        hop_count = getattr(req, '_oriva_redirect_count', 0) + 1
        target_for_visit = urllib.parse.urljoin(req.full_url, newurl)
        if hop_count > self.MAX_REDIRECTS or visited.get(target_for_visit, 0) >= 4:
            fp.close()
            raise urllib.error.HTTPError(req.full_url, code, f'Too many redirects ({self.MAX_REDIRECTS})', headers, fp)
        new_req = self.redirect_request(req, fp, code, msg, headers, newurl)
        if new_req is None:
            return None
        visited[target_for_visit] = visited.get(target_for_visit, 0) + 1
        new_req.redirect_dict = visited
        new_req._oriva_redirect_count = hop_count
        fp.close()
        hop_timeout = self.download_timeout
        if self.deadline is not None:
            rem = self.deadline - time.monotonic()
            if rem <= 0:
                raise TimeoutError('Download total timeout exceeded during redirect')
            hop_timeout = min(self.download_timeout, rem)
        new_req.timeout = hop_timeout
        return self.parent.open(new_req, timeout=hop_timeout)

    http_error_301 = http_error_303 = http_error_307 = http_error_308 = http_error_302


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
    if total_raw == '*':
        need(expected_size is None, f'Wildcard Content-Range total forbidden when expected size is known: {header_value!r}')
    else:
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


def _extract_response_sockets(response):
    socks = []
    candidates = [
        getattr(response, 'fp', None),
        getattr(getattr(response, 'fp', None), 'raw', None),
        getattr(getattr(response, 'raw', None), '_fp', None),
    ]
    for obj in candidates:
        sock = getattr(obj, '_sock', None)
        if sock is not None and sock not in socks:
            socks.append(sock)
    return socks


def _set_response_socket_timeout(response, timeout_seconds, aborter=None):
    for sock in _extract_response_sockets(response):
        if aborter is not None:
            aborter.track(sock)
        if timeout_seconds is not None and timeout_seconds > 0 and hasattr(sock, 'settimeout'):
            try:
                sock.settimeout(timeout_seconds)
            except OSError:
                pass


def _load_valid_part_meta(meta_path, expected_meta):
    if not meta_path.is_file():
        return False
    try:
        loaded = json.loads(meta_path.read_text('utf8'))
    except (OSError, ValueError):
        return False
    return isinstance(loaded, dict) and loaded == expected_meta


def download_asset(url, target, expected_size=None, expected_sha256=None,
                   download_timeout=DEFAULT_DOWNLOAD_TIMEOUT_SECONDS,
                   total_timeout=DEFAULT_DOWNLOAD_TOTAL_TIMEOUT_SECONDS, opener=None, sleep_fn=time.sleep):
    require_https_url(url, {'github.com'}, 'Public download URL', allow_query=False)
    need(download_timeout > 0 and total_timeout > 0, 'Download timeouts must be positive')

    part = target.with_name(target.name + '.part')
    meta_path = target.with_name(target.name + '.part.meta.json')
    expected_meta = {
        'url': redact_secrets(sanitize_url_for_log(url)),
        'expectedSize': expected_size,
        'expectedSha256': expected_sha256.upper() if expected_sha256 is not None else None,
    }
    deadline = time.monotonic() + total_timeout
    aborter = _DeadlineSocketAborter(deadline)
    if opener is None:
        http_h, https_h = _make_deadline_http_handlers(aborter)
        opener = urllib.request.build_opener(
            http_h,
            https_h,
            AllowedAssetRedirectHandler(deadline=deadline, download_timeout=download_timeout),
        )

    max_allowed = expected_size if expected_size is not None else MAX_ASSET_DOWNLOAD_BYTES

    def _cleanup_part():
        part.unlink(missing_ok=True)
        meta_path.unlink(missing_ok=True)

    try:
        for attempt in range(len(DOWNLOAD_BACKOFF_SECONDS) + 1):
            if part.exists():
                if not _load_valid_part_meta(meta_path, expected_meta):
                    _cleanup_part()
            offset = part.stat().st_size if part.exists() else 0
            if expected_size is not None and offset >= expected_size:
                _cleanup_part()
                offset = 0

            remaining = deadline - time.monotonic()
            if remaining <= 0 or aborter.expired:
                _cleanup_part()
                raise ReleaseError(f'Download total timeout ({total_timeout}s) exceeded before starting {target.name}')
            req_timeout = min(download_timeout, remaining)

            headers = {'User-Agent': 'ORIVA-public-verification'}
            if offset > 0:
                headers['Range'] = f'bytes={offset}-'
            req = urllib.request.Request(url, headers=headers)

            cur_stage = 'DNS/TCP'
            aborter.set_stage('DNS/TCP')
            try:
                with opener.open(req, timeout=req_timeout) as response:
                    _set_response_socket_timeout(response, min(download_timeout, max(0.001, deadline - time.monotonic())), aborter=aborter)
                    if time.monotonic() > deadline or aborter.expired:
                        raise TimeoutError(f'Download total timeout ({total_timeout}s) exceeded')
                    status = getattr(response, 'status', None)
                    if status is None:
                        status = response.getcode()
                    resp_headers = getattr(response, 'headers', {}) or {}
                    content_length = _parse_optional_content_length(resp_headers)

                    expected_span = None
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
                            expected_span = end - start + 1
                            if content_length is not None:
                                need(content_length == expected_span, f'Content-Length {content_length} mismatches Content-Range span')
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

                    if mode == 'wb':
                        write_json_atomic(meta_path, expected_meta)

                    aborter.set_stage('data read')
                    response_bytes_read = 0
                    read_fn = getattr(response, 'read1', None) or response.read
                    with open(part, mode) as stream:
                        while True:
                            rem_read = deadline - time.monotonic()
                            if rem_read <= 0 or aborter.expired:
                                raise TimeoutError(f'Download total timeout ({total_timeout}s) exceeded')
                            _set_response_socket_timeout(response, min(download_timeout, rem_read), aborter=aborter)
                            chunk = read_fn(DOWNLOAD_CHUNK_BYTES)
                            if time.monotonic() > deadline or aborter.expired:
                                raise TimeoutError(f'Download total timeout ({total_timeout}s) exceeded')
                            if not chunk:
                                break
                            response_bytes_read += len(chunk)
                            written += len(chunk)
                            if written > max_allowed:
                                raise ReleaseError(f'Downloaded stream exceeded allowed size ({max_allowed} bytes) for {target.name}')
                            if expected_span is not None and response_bytes_read > expected_span:
                                raise ReleaseError(f'HTTP 206 response exceeded declared Content-Range span ({expected_span} bytes) for {target.name}')
                            stream.write(chunk)

                    if expected_span is not None:
                        need(
                            response_bytes_read == expected_span,
                            f'HTTP 206 body length {response_bytes_read} mismatches Content-Range span {expected_span}',
                        )
                    if content_length is not None:
                        need(
                            response_bytes_read == content_length,
                            f'Response body length {response_bytes_read} mismatches Content-Length {content_length}',
                        )

                if time.monotonic() > deadline or aborter.expired:
                    raise TimeoutError(f'Download total timeout ({total_timeout}s) exceeded before completion')
                if expected_size is not None and written != expected_size:
                    raise http.client.IncompleteRead(b'', expected_size - written)
                if expected_sha256 is not None and sha256(part).upper() != expected_sha256.upper():
                    _cleanup_part()
                    raise ReleaseError(f'Downloaded asset checksum mismatch before promotion: {target.name}')
                if time.monotonic() > deadline or aborter.expired:
                    raise TimeoutError(f'Download total timeout ({total_timeout}s) exceeded after digest check before promotion')
                part.replace(target)
                meta_path.unlink(missing_ok=True)
                return
            except ReleaseError:
                _cleanup_part()
                raise
            except urllib.error.HTTPError as exc:
                cur_stage = aborter.stage
                exc_code = exc.code
                try:
                    exc.close()
                except Exception:
                    pass
                if exc_code == 416:
                    _cleanup_part()
                    last_error = ReleaseError(f'HTTP 416 restarting {target.name}')
                elif exc_code in {429, 500, 502, 503, 504}:
                    _cleanup_part()
                    last_error = ReleaseError(f'HTTP {exc_code} during {cur_stage} for {target.name}')
                else:
                    _cleanup_part()
                    raise ReleaseError(f'Download failed for {target.name} during {cur_stage}: HTTP {exc_code}') from None
            except urllib.error.URLError as exc:
                cur_stage = _classify_network_stage(exc, aborter.stage)
                last_error = ReleaseError(redact_secrets(f'Download failed for {target.name} during {cur_stage}: {exc.reason}'))
            except (TimeoutError, socket.timeout, http.client.IncompleteRead, ssl.SSLError, OSError) as exc:
                cur_stage = 'TLS handshake' if isinstance(exc, ssl.SSLError) else aborter.stage
                last_error = ReleaseError(redact_secrets(f'Download failed for {target.name} during {cur_stage}: {exc}'))

            if aborter.expired or time.monotonic() >= deadline:
                _cleanup_part()
                raise ReleaseError(f'Download total timeout ({total_timeout}s) exceeded for {target.name} during {cur_stage}: {last_error}') from None
            if attempt >= len(DOWNLOAD_BACKOFF_SECONDS):
                _cleanup_part()
                raise last_error
            remaining = deadline - time.monotonic()
            delay = DOWNLOAD_BACKOFF_SECONDS[attempt]
            if remaining <= 0 or delay >= remaining:
                _cleanup_part()
                raise ReleaseError(f'Download total timeout ({total_timeout}s) exceeded for {target.name} during {cur_stage}: {last_error}') from None
            sleep_fn(delay)
            if aborter.expired or time.monotonic() >= deadline:
                _cleanup_part()
                raise ReleaseError(f'Download total timeout ({total_timeout}s) exceeded for {target.name} during {cur_stage}: {last_error}') from None
    finally:
        aborter.stop()


def verify(root, plan, args, env):
    w = Path(plan['evidence']); w.mkdir(parents=True, exist_ok=True)
    report_path = w / 'recipient-verification.json'
    report_path.unlink(missing_ok=True)

    directory, m, names = load_manifest(plan)

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
    require_https_url(release['html_url'], {'github.com'}, 'Release HTML URL', allow_query=False)
    safe_html_url = redact_secrets(sanitize_url_for_log(release['html_url']))
    assets = {item['name']: item for item in release['assets']}; need(set(assets) == set(names), 'Public asset set differs')

    with isolated_git_env(env, anonymous=True) as clean_env:
        _audit_repo_git_config(root, clean_env, timeout=git_timeout)
        remote = _call_git(
            root, '-c', 'credential.helper=', '-c', 'core.askPass=', '-c', 'http.cookieFile=', '-c', 'http.saveCookies=false', '-c', 'http.followRedirects=false', '-c', 'http.sslVerify=true',
            'ls-remote', 'https://github.com/'+args.repo+'.git', 'refs/tags/'+plan['tag'], 'refs/tags/'+plan['tag']+'^{}',
            timeout=git_timeout, env=clean_env, anonymous=True,
        )
        refs = _parse_refs(remote); check_remote_tag(refs, plan['tag'], m['commit']); need(bool(refs), 'Remote release tag missing')

        if verify_mode == 'api-digest':
            for name in names:
                item = assets[name]; local_file = directory / name
                require_https_url(item['browser_download_url'], {'github.com'}, f'Asset URL ({name})', allow_query=False)
                need(item.get('size') == local_file.stat().st_size and item.get('digest') == 'sha256:' + sha256(local_file), 'Remote asset size/digest differs: ' + name)
            write_json_atomic(report_path, dict(
                status='PASS', verifyMode='api-digest', downloadVerified=False,
                commit=m['commit'], assets=names, cloneCheck=False, release=safe_html_url,
            ))
            print('PUBLIC_API_DIGEST_VERIFIED', safe_html_url)
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
            if clone.exists():
                _remove_temp_dir_with_diag(clone, 'existing recipient-clone directory')
            _call_execute(
                ['git', '-c', 'credential.helper=', '-c', 'core.askPass=', '-c', 'http.cookieFile=', '-c', 'http.saveCookies=false', '-c', 'http.followRedirects=false', '-c', 'http.sslVerify=true',
                 'clone', '--depth', '1', '--branch', plan['tag'], '--single-branch', '--config', 'core.autocrlf=false',
                 'https://github.com/'+args.repo+'.git', clone],
                root, clean_env, w/'recipient-clone.log', timeout=git_timeout,
            )
            need(_call_git(clone, 'rev-parse', 'HEAD', env=clean_env, anonymous=True) == m['commit'] and not _call_git(clone, 'status', '--porcelain', env=clean_env, anonymous=True), 'Recipient clone differs')
            with zipfile.ZipFile(source) as z:
                for path in _call_git(clone, 'ls-files', env=clean_env, anonymous=True).splitlines():
                    need((clone/path).read_bytes() == z.read('zhixu-oriva-'+plan['version']+'/'+path), 'Clone/source differ: '+path)
        write_json_atomic(report_path, dict(
            status='PASS', verifyMode='full', downloadVerified=True,
            commit=m['commit'], assets=names, cloneCheck=args.clone_check, release=safe_html_url,
        ))
        print('PUBLIC_DOWNLOADS_VERIFIED', safe_html_url)


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
