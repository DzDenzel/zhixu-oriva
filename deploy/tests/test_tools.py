"""Script behavior tests with a fake Docker CLI, not container acceptance evidence."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
PWSH = shutil.which('pwsh') or shutil.which('powershell')
# Windows' system bash.exe may only be a WSL installer stub; use the project's Git Bash.
BASH = ('G:/Git/bin/bash.exe' if os.name == 'nt' and Path('G:/Git/bin/bash.exe').exists()
        else shutil.which('bash'))


class InstallTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='oriva-tools-')
        self.addCleanup(self.temp.cleanup)
        self.home = Path(self.temp.name)
        self.kit = self.home / 'kit'
        shutil.copytree(ROOT / 'deploy', self.kit)
        (self.home / 'backend').mkdir()
        (self.home / 'backend' / 'Dockerfile').write_text('# Fake build context; Docker CLI is a test double.\n', encoding='utf-8')
        self.bin = self.home / 'bin'
        self.bin.mkdir()
        self.log = self.home / 'commands.jsonl'
        fake = self.bin / 'fake.py'
        fake.write_text('''import json,os,sys
from pathlib import Path
a=sys.argv[1:]
with open(os.environ['ORIVA_TEST_LOG'],'a',encoding='utf-8') as f: f.write(json.dumps(a)+'\\n')
if os.environ.get('ORIVA_TEST_FAIL') and 'up' in a: sys.exit(19)
if a[:2]==['manifest','inspect']:
 state=os.environ.get('ORIVA_TEST_TAG','existing')
 if state=='existing': print('{}');sys.exit(0)
 print('manifest unknown' if state=='missing' else 'authorization denied',file=sys.stderr);sys.exit(1)
if a[:2]==['compose','version']: print(os.environ.get('ORIVA_TEST_COMPOSE_VERSION','2.30.3'))
elif 'ps' in a: print(os.environ.get('ORIVA_TEST_EXISTING',''))
elif a[:2]==['volume','ls']:
 if '--filter' not in a: print(os.environ.get('ORIVA_TEST_UNLABELED_VOLUMES',''))
elif '--json' in a: print(json.dumps({'status':'UP','initialized':os.environ.get('ORIVA_TEST_INITIALIZED')=='true'}))
elif 'mysqldump' in ' '.join(a): print('-- test dump; no real database accessed')
if 'cp' in a:
 i=a.index('cp')
 if a[i+1].startswith('mysql:'):
  Path(a[i+2]).write_bytes('-- 中文备份\\nSELECT 1;\\n'.encode('utf-8'))
if os.environ.get('ORIVA_TEST_CAPTURE_ENV'):
 Path(os.environ['ORIVA_TEST_CAPTURE_ENV']).write_text(json.dumps({k:os.environ.get(k) for k in ['ORIVA_PORT','PLANLOCK_DB_PASSWORD']}),encoding='utf-8')
''', encoding='utf-8')
        import sys
        (self.bin / 'docker.cmd').write_text(f'@"{sys.executable}" "{fake}" %*\r\n', encoding='utf-8')
        (self.bin / 'docker').write_text(f'#!/bin/sh\nexec "{Path(sys.executable).as_posix()}" "{fake.as_posix()}" "$@"\n', encoding='utf-8')
        (self.bin / 'docker').chmod(0o700)
        self.env = os.environ.copy()
        self.env.update(PATH=str(self.bin)+os.pathsep+self.env['PATH'], ORIVA_TEST_LOG=str(self.log))

    def invoke(self, platform, *args):
        if platform == 'ps1':
            if not PWSH: self.skipTest('PowerShell not available')
            command = [PWSH, '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', str(self.kit / 'install.ps1'), *args]
        else:
            if not BASH: self.skipTest('POSIX shell not available')
            command = [BASH, str(self.kit / 'install.sh'), *args]
        return subprocess.run(command, env=self.env, text=True, capture_output=True, encoding='utf-8', errors='replace')

    def check_idempotence(self, platform):
        args = ['-Build'] if platform == 'ps1' else ['--build']
        first = self.invoke(platform, *args)
        self.assertEqual(first.returncode, 0, first.stdout+first.stderr)
        data = (self.kit / '.env').read_bytes()
        if os.name == 'nt' and platform == 'ps1':
            acl_env = self.env.copy()
            acl_env['ORIVA_TEST_ACL_PATH'] = str(self.kit / '.env')
            probe = '''$ErrorActionPreference='Stop'
$file=New-Object System.IO.FileInfo($env:ORIVA_TEST_ACL_PATH)
if($PSVersionTable.PSVersion.Major -ge 6){$acl=[System.IO.FileSystemAclExtensions]::GetAccessControl($file,[System.Security.AccessControl.AccessControlSections]::Access)}else{$acl=$file.GetAccessControl([System.Security.AccessControl.AccessControlSections]::Access)}
$sid=[System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value
if(-not $acl.AreAccessRulesProtected){throw 'Secret permissions inherit from parent'}
$rules=$acl.GetAccessRules($true,$true,[System.Security.Principal.SecurityIdentifier])
if($rules.Count -ne 1 -or $rules[0].IdentityReference.Value -ne $sid){throw 'Secret permissions include another identity'}'''
            acl = subprocess.run([PWSH, '-NoProfile', '-Command', probe], env=acl_env, text=True, capture_output=True, encoding='utf-8', errors='replace')
            self.assertEqual(acl.returncode, 0, acl.stdout+acl.stderr)
        elif os.name != 'nt':
            import stat
            self.assertEqual(stat.S_IMODE((self.kit / '.env').stat().st_mode), 0o600)
        entries = dict(line.split('=', 1) for line in data.decode('utf-8-sig').splitlines() if line and not line.startswith('#'))
        for key in ['PLANLOCK_JWT_SECRET', 'PLANLOCK_DB_PASSWORD', 'PLANLOCK_REDIS_PASSWORD', 'PLANLOCK_SETUP_TOKEN']:
            self.assertGreaterEqual(len(bytes.fromhex(entries[key])), 32)
        self.assertEqual(len({entries[k] for k in entries if k.endswith('PASSWORD') or k.endswith('SECRET') or k.endswith('TOKEN')}), 5)
        self.assertIn('/setup', first.stdout)
        self.assertNotIn(entries['PLANLOCK_DB_PASSWORD'], first.stdout+first.stderr)
        self.assertNotIn(entries['PLANLOCK_JWT_SECRET'], first.stdout+first.stderr)
        self.env['ORIVA_TEST_INITIALIZED'] = 'true'
        second = self.invoke(platform, *args)
        self.assertEqual(second.returncode, 0, second.stdout+second.stderr)
        self.assertEqual(hashlib.sha256(data).digest(), hashlib.sha256((self.kit / '.env').read_bytes()).digest())
        self.assertNotIn(entries['PLANLOCK_SETUP_TOKEN'], second.stdout+second.stderr)
        calls = [json.loads(x) for x in self.log.read_text().splitlines()]
        self.assertTrue(any('up' in x and '--wait' in x for x in calls))
        self.assertFalse(any('down' in x or '-v' in x for x in calls))

    def test_powershell_install_reuses_secrets_and_never_resets_data(self): self.check_idempotence('ps1')
    def test_shell_install_reuses_secrets_and_never_resets_data(self): self.check_idempotence('sh')

    def test_invalid_argument_fails_before_env_or_docker_changes(self):
        for platform, arg in [('ps1','-Bogus'), ('sh','--bogus')]:
            with self.subTest(platform=platform):
                result = self.invoke(platform, arg)
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse((self.kit / '.env').exists())

    def test_compose_failure_is_not_reported_as_ready(self):
        self.env['ORIVA_TEST_FAIL'] = '1'
        result = self.invoke('ps1', '-Build')
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn('/setup', result.stdout)

    def test_existing_malformed_env_is_not_replaced(self):
        (self.kit / '.env').write_text('PLANLOCK_JWT_SECRET=short\n', encoding='utf-8')
        before = (self.kit / '.env').read_bytes()
        for platform in ['ps1','sh']:
            with self.subTest(platform=platform):
                result = self.invoke(platform)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(before, (self.kit / '.env').read_bytes())

    def test_restore_refuses_existing_target_and_does_not_start_database(self):
        backup = self.home / 'backup'
        backup.mkdir()
        (backup / '.env').write_text('not a real backup\n', encoding='utf-8')
        target = self.home / 'existing'
        target.mkdir()
        (target / 'keep.txt').write_text('existing user data', encoding='utf-8')
        command = [PWSH, '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', str(self.kit / 'restore.ps1'), '-BackupDirectory', str(backup), '-TargetDirectory', str(target), '-ProjectName', 'oriva-test-restore']
        result = subprocess.run(command, env=self.env, capture_output=True, text=True, encoding='utf-8', errors='replace')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual((target / 'keep.txt').read_text(), 'existing user data')
        self.assertIn('fresh target', result.stdout+result.stderr)
        calls = self.log.read_text() if self.log.exists() else ''
        self.assertNotIn('"up"', calls)

    def test_restore_requires_backup_checksums_before_creating_target(self):
        backup = self.home / 'backup'
        backup.mkdir()
        (backup / '.env').write_text('not a real backup\n', encoding='utf-8')
        target = self.home / 'new-target'
        command = [PWSH, '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', str(self.kit / 'restore.ps1'), '-BackupDirectory', str(backup), '-TargetDirectory', str(target), '-ProjectName', 'oriva-test-restore']
        result = subprocess.run(command, env=self.env, capture_output=True, text=True, encoding='utf-8', errors='replace')
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(target.exists())
        self.assertIn('SHA256SUMS', result.stdout+result.stderr)

    def test_env_file_wins_over_unrelated_development_shell_environment(self):
        for platform in ['ps1', 'sh']:
            with self.subTest(platform=platform):
                capture = self.home / 'captured-env.json'
                self.env['ORIVA_TEST_CAPTURE_ENV'] = str(capture)
                self.env['ORIVA_PORT'] = '19999'
                self.env['PLANLOCK_DB_PASSWORD'] = 'stale-development-password'
                result = self.invoke(platform)
                self.assertEqual(result.returncode, 0, result.stdout+result.stderr)
                actual = json.loads(capture.read_text())
                self.assertEqual(actual['ORIVA_PORT'], '8080')
                self.assertNotEqual(actual['PLANLOCK_DB_PASSWORD'], 'stale-development-password')

    def test_backup_restore_retains_utf8_and_secrets_in_fresh_project(self):
        result = self.invoke('ps1')
        self.assertEqual(result.returncode, 0, result.stdout+result.stderr)
        self.env['ORIVA_TEST_INITIALIZED'] = 'true'
        backup = self.home / 'private-backup'
        result = subprocess.run([PWSH, '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', str(self.kit/'backup.ps1'), '-OutputDirectory', str(backup)], env=self.env, capture_output=True, text=True, encoding='utf-8', errors='replace')
        self.assertEqual(result.returncode, 0, result.stdout+result.stderr)
        self.assertEqual((backup/'database.sql').read_bytes(), '-- 中文备份\nSELECT 1;\n'.encode('utf-8'))
        target = self.home / 'restored'
        result = subprocess.run([PWSH, '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', str(self.kit/'restore.ps1'), '-BackupDirectory', str(backup), '-TargetDirectory', str(target), '-ProjectName', 'oriva-restore-test'], env=self.env, capture_output=True, text=True, encoding='utf-8', errors='replace')
        self.assertEqual(result.returncode, 0, result.stdout+result.stderr)
        original = (backup/'.env').read_text()
        restored = (target/'.env').read_text()
        self.assertEqual(restored, original.replace('COMPOSE_PROJECT_NAME=oriva\n', 'COMPOSE_PROJECT_NAME=oriva-restore-test\n').replace('ORIVA_PORT=8080\n', 'ORIVA_PORT=8081\n'))

    def test_restore_refuses_same_named_unlabeled_volumes_before_any_write(self):
        # A pre-existing volume must never become an import target just because
        # it lacks Compose ownership labels. Removing the name check breaks this.
        result = self.invoke('ps1')
        self.assertEqual(result.returncode, 0, result.stdout+result.stderr)
        backup = self.home / 'private-backup'
        self.env['ORIVA_TEST_INITIALIZED'] = 'true'
        result = subprocess.run([PWSH, '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', str(self.kit/'backup.ps1'), '-OutputDirectory', str(backup)], env=self.env, capture_output=True, text=True, encoding='utf-8', errors='replace')
        self.assertEqual(result.returncode, 0, result.stdout+result.stderr)
        for platform in ['ps1', 'sh']:
            for volume in ['oriva-restore-test_mysql-data', 'oriva-restore-test_redis-data']:
                with self.subTest(platform=platform, volume=volume):
                    target = self.home / ('unlabeled-'+platform+'-'+volume)
                    self.env['ORIVA_TEST_UNLABELED_VOLUMES'] = volume
                    self.log.write_text('', encoding='utf-8')
                    command = ([PWSH, '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', str(self.kit/'restore.ps1'), '-BackupDirectory', str(backup), '-TargetDirectory', str(target), '-ProjectName', 'oriva-restore-test'] if platform=='ps1' else [BASH, str(self.kit/'restore.sh'), str(backup), str(target), 'oriva-restore-test'])
                    result = subprocess.run(command, env=self.env, capture_output=True, text=True, encoding='utf-8', errors='replace')
                    self.assertNotEqual(result.returncode, 0, result.stdout+result.stderr)
                    self.assertFalse(target.exists(), 'Preflight must not create any restore files')
                    calls = [json.loads(line) for line in self.log.read_text().splitlines()]
                    self.assertFalse(any('up' in call or 'cp' in call or 'exec' in call for call in calls), 'An existing volume must not be started, mounted or imported into')

    def test_old_compose_patch_refused_before_generating_secrets(self):
        self.env['ORIVA_TEST_COMPOSE_VERSION'] = '2.24.3'
        for platform in ['ps1', 'sh']:
            with self.subTest(platform=platform):
                result = self.invoke(platform)
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse((self.kit/'.env').exists())

    def test_minimum_and_newer_compose_versions_are_accepted(self):
        for version in ['2.24.4', '2.25.0', '5.5.1']:
            self.env['ORIVA_TEST_COMPOSE_VERSION'] = version
            for platform in ['ps1', 'sh']:
                with self.subTest(version=version, platform=platform):
                    result = self.invoke(platform)
                    self.assertEqual(result.returncode, 0, result.stdout+result.stderr)

    def test_publication_refuses_existing_tags_and_registry_errors(self):
        if not BASH: self.skipTest('POSIX shell not available')
        for state, expected in [('existing',1),('missing',0),('denied',1)]:
            with self.subTest(state=state):
                self.env['ORIVA_TEST_TAG']=state
                result=subprocess.run([BASH,str(self.kit/'require-new-image-tag.sh'),'ghcr.io/example/backend:2.1.0'],env=self.env,capture_output=True,text=True,encoding='utf-8',errors='replace')
                self.assertEqual(result.returncode,expected,result.stdout+result.stderr)

    def test_shell_installer_lists_private_candidates_and_detection_fallback(self):
        if not BASH: self.skipTest('POSIX shell not available')
        fake_ip = self.bin / 'ip'
        fake_ip.write_text("#!/bin/sh\nprintf '%s\\n' '2: eth0 inet 10.2.3.4/24 scope global' '3: eth1 inet 192.168.5.6/24 scope global' '4: eth2 inet 172.31.2.3/16 scope global' '5: eth3 inet 172.32.1.2/16 scope global' '6: eth4 inet 203.0.113.1/24 scope global'\n", encoding='utf-8')
        fake_ip.chmod(0o700)
        result = self.invoke('sh')
        self.assertEqual(result.returncode, 0, result.stdout+result.stderr)
        for address in ['10.2.3.4', '192.168.5.6', '172.31.2.3']:
            self.assertIn('LAN candidate: http://'+address+':8080/setup', result.stdout)
        self.assertNotIn('172.32.1.2', result.stdout)
        self.assertNotIn('203.0.113.1', result.stdout)
        fake_ip.write_text('#!/bin/sh\nexit 1\n', encoding='utf-8')
        result = self.invoke('sh', '--show-setup')
        self.assertEqual(result.returncode, 0, result.stdout+result.stderr)
        self.assertIn('ip addr', result.stdout)

    def test_shell_filters_virtual_interfaces_and_recommends_default_route_first(self):
        fake_ip = self.bin / 'ip'
        fake_ip.write_text('''#!/bin/sh
case "$*" in
  *route*) printf '%s\\n' 'default via 192.168.5.1 dev eth1 metric 100';;
  *) printf '%s\\n' '2: eth0 inet 10.2.3.4/24 scope global' '3: eth1 inet 192.168.5.6/24 scope global' '4: docker0 inet 172.17.0.1/16 scope global' '5: br-deadbeef inet 172.18.0.1/16 scope global' '6: veth123@if2 inet 10.3.0.1/24 scope global' '7: virbr0 inet 192.168.122.1/24 scope global' '8: cni0 inet 10.4.0.1/24 scope global' '9: flannel.1 inet 10.5.0.1/24 scope global' '10: tailscale0 inet 10.6.0.1/24 scope global';;
esac
''', encoding='utf-8')
        fake_ip.chmod(0o700)
        result = self.invoke('sh')
        self.assertEqual(result.returncode, 0, result.stdout+result.stderr)
        self.assertIn('LAN candidate: http://192.168.5.6:8080/setup (recommended)', result.stdout)
        self.assertLess(result.stdout.index('192.168.5.6'), result.stdout.index('10.2.3.4'))
        for address in ['172.17.0.1','172.18.0.1','10.3.0.1','192.168.122.1','10.4.0.1','10.5.0.1','10.6.0.1']:
            self.assertNotIn(address, result.stdout)

    def test_powershell_filters_virtual_adapters_and_recommends_gateway_first(self):
        first = self.invoke('ps1')
        self.assertEqual(first.returncode, 0, first.stdout+first.stderr)
        script = str(self.kit/'install.ps1').replace("'", "''")
        fixture = '''
function Get-NetIPAddress { param($AddressFamily,$ErrorAction)
  1..5 | ForEach-Object { [pscustomobject]@{IPAddress=('192.168.'+$_+'.2'); InterfaceIndex=$_; InterfaceAlias='adapter'} }
}
function Get-NetAdapter { param($ErrorAction)
  [pscustomobject]@{InterfaceIndex=1; HardwareInterface=$true; InterfaceDescription='Ethernet'; Name='Ethernet'; Status='Up'}
  [pscustomobject]@{InterfaceIndex=2; HardwareInterface=$true; InterfaceDescription='Wi-Fi'; Name='Wi-Fi'; Status='Up'}
  3..5 | ForEach-Object { [pscustomobject]@{InterfaceIndex=$_; HardwareInterface=$false; InterfaceDescription='VMware Virtual Ethernet Adapter'; Name='VMnet'; Status='Up'} }
}
function Get-NetRoute { param($AddressFamily,$DestinationPrefix,$ErrorAction)
  [pscustomobject]@{InterfaceIndex=2; NextHop='192.168.2.1'; RouteMetric=10}
}
'''
        result = subprocess.run([PWSH,'-NoProfile','-Command',fixture+"; & '"+script+"' -ShowSetup"], env=self.env, capture_output=True, text=True, encoding='utf-8', errors='replace')
        self.assertEqual(result.returncode, 0, result.stdout+result.stderr)
        self.assertIn('LAN candidate: http://192.168.2.2:8080/setup (recommended)', result.stdout)
        self.assertLess(result.stdout.index('192.168.2.2'),result.stdout.index('192.168.1.2'))
        for number in [3,4,5]: self.assertNotIn('192.168.'+str(number)+'.2',result.stdout)

    def test_powershell_installer_lists_private_candidates_and_detection_fallback(self):
        if not PWSH: self.skipTest('PowerShell not available')
        script = str(self.kit/'install.ps1').replace("'", "''")
        for fail in [False, True]:
            fixture = "throw 'unavailable'" if fail else "'10.2.3.4','192.168.5.6','172.31.2.3','172.32.1.2','203.0.113.1' | ForEach-Object { [pscustomobject]@{IPAddress=$_; InterfaceIndex=1} }"
            command = "function Get-NetAdapter { param($ErrorAction) [pscustomobject]@{InterfaceIndex=1;HardwareInterface=$true;Status='Up';Name='Ethernet';InterfaceDescription='Ethernet'} }; function Get-NetRoute { param($AddressFamily,$DestinationPrefix,$ErrorAction) }; function Get-NetIPAddress { param($AddressFamily,$ErrorAction) "+fixture+" }; & '"+script+"' -ShowSetup"
            # Create the environment once, then isolate discovery from actual host networking.
            first = self.invoke('ps1')
            self.assertEqual(first.returncode, 0, first.stdout+first.stderr)
            result = subprocess.run([PWSH,'-NoProfile','-Command',command], env=self.env,capture_output=True,text=True,encoding='utf-8',errors='replace')
            self.assertEqual(result.returncode, 0, result.stdout+result.stderr)
            if fail:
                self.assertIn('ipconfig', result.stdout)
            else:
                for address in ['10.2.3.4','192.168.5.6','172.31.2.3']:
                    self.assertIn('LAN candidate: http://'+address+':8080/setup', result.stdout)
                self.assertNotIn('172.32.1.2', result.stdout)
                self.assertNotIn('203.0.113.1', result.stdout)


if __name__ == '__main__': unittest.main()
