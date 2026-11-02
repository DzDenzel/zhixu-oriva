#!/usr/bin/env python3
"""CI-only fresh install of a pushed digest on a native Linux arm64 runner.

Uses the public installer and APIs, never builds an image. Only this run's random
Compose project and private temporary directory are removed at completion.
"""
import argparse
import json
import os
from pathlib import Path
import platform
import re
import secrets
import shutil
import subprocess
import tempfile
from urllib.request import ProxyHandler, Request, build_opener
import uuid


ROOT = Path(__file__).resolve().parents[1]


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', required=True, help='Repository@sha256:digest, never a tag')
    parser.add_argument('--version', required=True)
    parser.add_argument('--revision', required=True)
    parser.add_argument('--output-dir', required=True, type=Path)
    args = parser.parse_args()
    require(re.fullmatch(r'[^\s@]+@sha256:[0-9a-f]{64}', args.image), 'An exact image digest is required')
    args.output_dir.mkdir(parents=True, exist_ok=False)
    os.umask(0o077)
    redactions = []

    def redact(text):
        for value in redactions:
            text = text.replace(value, '[REDACTED]')
        return text

    def save(name, value):
        (args.output_dir / name).write_text(json.dumps(value, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')

    def run(command, log=None, timeout=120):
        result = subprocess.run(command, capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=timeout)
        if log:
            (args.output_dir / log).write_text(redact(result.stdout + result.stderr), encoding='utf-8')
        require(result.returncode == 0, 'Command failed: ' + command[0] + ('; see ' + log if log else ''))
        return result.stdout

    result = {'status': 'FAIL', 'platform': 'linux/arm64', 'image': args.image,
              'version': args.version, 'sourceCommit': args.revision, 'buildUsed': False}
    with tempfile.TemporaryDirectory(prefix='oriva-arm64-ci-') as temporary:
        kit = Path(temporary)
        project = 'oriva-arm64-ci-' + uuid.uuid4().hex[:12]
        compose = ['docker', 'compose', '--env-file', str(kit / '.env'), '-f',
                   str(kit / 'docker-compose.yml'), '--project-name', project]
        deployment_started = False
        try:
            require(platform.system() == 'Linux' and platform.machine() in ('aarch64', 'arm64'),
                    'Verification must run on native Linux arm64, not QEMU on an amd64 host')
            engine = run(['docker', 'info', '--format', '{{.OSType}}/{{.Architecture}}']).strip()
            require(engine in ('linux/aarch64', 'linux/arm64'), 'Docker engine is not native Linux arm64')
            result['hostArchitecture'] = platform.machine()
            result['dockerPlatform'] = engine
            run(['docker', 'pull', '--platform', 'linux/arm64', args.image], 'pull.log', timeout=300)
            image = json.loads(run(['docker', 'image', 'inspect', args.image]))[0]
            require(image['Os'] == 'linux' and image['Architecture'] == 'arm64', 'Pulled image is not Linux arm64')
            labels = image['Config']['Labels']
            require(labels['org.opencontainers.image.version'] == args.version, 'OCI version does not match tag')
            require(labels['org.opencontainers.image.revision'] == args.revision, 'OCI revision does not match source')
            require('PLANLOCK_VERSION=' + args.version in image['Config']['Env'], 'Runtime version does not match tag')
            save('image.json', {'reference': args.image, 'id': image['Id'], 'os': image['Os'],
                                'architecture': image['Architecture'], 'labels': labels})
            for name in ('install.sh', 'common.sh', 'docker-compose.yml'):
                shutil.copy2(ROOT / 'deploy' / name, kit / name)
            # Preconfigure only a new directory: pin the just-pushed digest instead
            # of the installer's public default tag. All services and volumes are fresh.
            values = {'COMPOSE_PROJECT_NAME': project, 'ORIVA_IMAGE': args.image,
                      'ORIVA_BIND_ADDRESS': '127.0.0.1', 'ORIVA_PORT': '18080', 'ORIVA_TIMEZONE': 'UTC',
                      'PLANLOCK_PUBLIC_PLUGINS_CATALOG_URL': '', 'ORIVA_DOMAIN': ''}
            for key in ('MYSQL_ROOT_PASSWORD', 'PLANLOCK_DB_PASSWORD', 'PLANLOCK_REDIS_PASSWORD',
                        'PLANLOCK_JWT_SECRET', 'PLANLOCK_SETUP_TOKEN'):
                values[key] = secrets.token_hex(32)
                redactions.append(values[key])
            (kit / '.env').write_text(''.join(key + '=' + value + '\n' for key, value in values.items()), encoding='utf-8')
            (kit / '.env').chmod(0o600)
            require(not run(['docker', 'ps', '-aq', '--filter', 'label=com.docker.compose.project=' + project]).strip(),
                    'Compose project already has containers')
            require(not run(['docker', 'volume', 'ls', '-q', '--filter', 'label=com.docker.compose.project=' + project]).strip(),
                    'Compose project already has volumes')
            result['freshProject'] = project
            deployment_started = True
            run(['sh', str(kit / 'install.sh')], 'installer.log', timeout=600)

            def service_proof():
                services = {}
                for service in ('mysql', 'redis', 'backend'):
                    container_id = run(compose + ['ps', '-q', service]).strip()
                    require(bool(container_id), service + ' container is missing')
                    container = json.loads(run(['docker', 'inspect', container_id]))[0]
                    state = container['State']
                    require(state['Running'] and state.get('Health', {}).get('Status') == 'healthy', service + ' is not healthy')
                    service_image = json.loads(run(['docker', 'image', 'inspect', container['Image']]))[0]
                    require(service_image['Os'] == 'linux' and service_image['Architecture'] == 'arm64', service + ' is not Linux arm64')
                    if service == 'backend':
                        require(container['Image'] == image['Id'], 'Installer did not deploy the exact pulled digest')
                    services[service] = {'health': state['Health']['Status'], 'imageId': container['Image'],
                                         'platform': 'linux/arm64'}
                return services

            save('services-before.json', service_proof())
            base = 'http://127.0.0.1:18080'
            opener = build_opener(ProxyHandler({}))

            def request(path, payload=None, token=None, html=False):
                headers = {}
                if token:
                    headers['Authorization'] = 'Bearer ' + token
                data = None
                if payload is not None:
                    headers['Content-Type'] = 'application/json'
                    data = json.dumps(payload).encode('utf-8')
                with opener.open(Request(base + path, data=data, headers=headers), timeout=30) as response:
                    require(response.status == 200, 'Unexpected HTTP status for ' + path)
                    body = response.read().decode('utf-8')
                    if html:
                        require(response.headers.get_content_type() == 'text/html', 'Setup page is not HTML')
                        return body
                    return json.loads(body)

            def api(path, payload=None, token=None):
                body = request(path, payload, token)
                require(body.get('code') == 0, 'API rejected ' + path)
                return body['data']

            migration_files = list((ROOT / 'backend/planlock-app/src/main/resources/db/migration').glob('V*__*.sql'))
            versions = sorted((p.name.split('__', 1)[0][1:] for p in migration_files),
                              key=lambda version: tuple(int(part) for part in version.split('.')))
            require(bool(versions), 'No source migrations found')
            health = request('/api/v1/health')
            require(health['status'] == 'UP' and health['version'] == args.version, 'Fresh health or version mismatch')
            require(health['migrationVersion'] == versions[-1] and health['initialized'] is False,
                    'Fresh deployment migration or initialization state mismatch')
            save('health-before.json', health)
            history = run(compose + ['exec', '-T', 'mysql', 'sh', '-c',
                          'MYSQL_PWD="$MYSQL_PASSWORD" mysql --user=oriva --database=planlock --batch --skip-column-names '
                          '--execute="SELECT version, success FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank"'])
            rows = [line.split('\t') for line in history.splitlines()]
            require(rows == [[version, '1'] for version in versions], 'Migration history does not match all source versions')
            save('migration-history.json', [{'version': row[0], 'success': True} for row in rows])
            page = request('/setup', html=True)
            require(all(field in page for field in ('setupToken', 'account', 'password', 'accessUrl')), 'Setup form fields missing')
            (args.output_dir / 'setup.html').write_text(page, encoding='utf-8')
            account = 'arm64-ci@example.org'
            password = secrets.token_hex(20)
            redactions.append(password)
            setup = request('/api/v1/setup', {'setupToken': values['PLANLOCK_SETUP_TOKEN'],
                            'instanceLabel': 'ORIVA arm64 CI', 'account': account,
                            'password': password, 'accessUrl': base})
            require(setup['connection']['apiBaseUrl'] == base + '/api/v1/', 'Initialization connection URL mismatch')
            require(setup['qrDataUrl'].startswith('data:image/png;base64,'), 'Initialization QR missing')
            save('connection.json', setup['connection'])
            login = api('/api/v1/auth/login', {'account': account, 'password': password,
                        'deviceUid': project, 'deviceName': 'Native arm64 CI', 'platform': 'android'})
            token = login['token']
            require(bool(token) and bool(login['userId']), 'Owner login did not return a session')
            redactions.append(token)
            plan = {'clientId': str(uuid.uuid4()), 'name': 'Native arm64 deployment smoke',
                    'type': '学习', 'startTime': '09:00:00', 'endTime': '10:00:00', 'remindAdvanceMin': 0}
            created = api('/api/v1/plans', plan, token)
            require(bool(created['id']) and created['name'] == plan['name'], 'Plan creation response mismatch')
            plans = api('/api/v1/plans', token=token)
            readback = [item for item in plans if item['id'] == created['id']]
            require(len(readback) == 1 and readback[0] == created, 'Created plan did not read back unchanged')
            save('plan-smoke.json', {'login': 'PASS', 'create': created, 'read': readback[0]})
            after = request('/api/v1/health')
            require(after['status'] == 'UP' and after['initialized'] is True and after['version'] == args.version
                    and after['migrationVersion'] == versions[-1] and after['instanceId'] == health['instanceId'],
                    'Initialized health state mismatch')
            save('health-after.json', after)
            result['services'] = service_proof()
            result.update(status='PASS', imageId=image['Id'], migrationVersion=versions[-1],
                          setupPage=True, initialization=True, login=True, planCreatedAndRead=True)
        except Exception as error:
            result['error'] = redact(str(error))
            raise
        finally:
            if deployment_started:
                try:
                    run(compose + ['logs', '--no-color'], 'services.log')
                finally:
                    # Never stop the daemon or touch another project. These volumes
                    # were allocated only for this disposable fresh-install smoke.
                    try:
                        run(compose + ['down', '--volumes', '--remove-orphans'], 'cleanup.log')
                        result['isolatedProjectRemoved'] = True
                    except Exception as error:
                        result.update(status='FAIL', cleanupError=redact(str(error)))
                        save('result.json', result)
                        raise
                    finally:
                        save('result.json', result)
            else:
                save('result.json', result)
    print(json.dumps(result, ensure_ascii=False))


if __name__ == '__main__':
    main()
