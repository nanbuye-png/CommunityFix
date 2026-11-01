"""Reproducible, disposable CommunityFix acceptance environment (stdlib only).

Never reads deploy/.env. Each run has a random Compose project, private generated
credentials, and its own database/volumes. See docs/local-acceptance.md.
"""
import argparse
from datetime import datetime, timezone
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import socket
import subprocess
import sys
import time
import traceback
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
RUNS = ROOT / '.local' / 'acceptance'
COMPOSE = ROOT / 'deploy' / 'compose.acceptance.yml'
PORTS = dict(DB=13307, MINIO=19000, REGISTRY=18761, BACKEND=18080,
             BACKEND2=18082, GATEWAY=18081, WEB=18088, H5=18089)


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'deploy' / filename)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


def save(path, data):
    tmp = path.with_suffix('.tmp')
    tmp.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    tmp.replace(path)


def clean_env():
    prefixes = ('CF_', 'COMPOSE_', 'DB_', 'DEMO_', 'BOOTSTRAP_', 'JWT_', 'MINIO_',
                'STORAGE_', 'AI_', 'WECHAT_', 'SPRING_', 'COMMUNITYFIX_', 'EUREKA_',
                'SERVER_', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS')
    return {k: v for k, v in os.environ.items() if not k.startswith(prefixes)}


class Run:
    def __init__(self, name):
        if not re.fullmatch(r'cf-accept-[0-9a-f]{12}', name):
            raise ValueError('Expected generated cf-accept-<12 hex> run ID')
        self.name = name
        self.folder = RUNS / name
        if self.folder.resolve().parent != RUNS.resolve() or self.folder.is_symlink():
            raise ValueError('Run directory must stay inside acceptance root')
        self.state = json.loads((self.folder / 'state.json').read_text(encoding='utf-8'))
        if self.state.get('owner') != 'communityfix-acceptance-v1' or self.state.get('project') != name:
            raise ValueError('Invalid ownership marker')
        self.env = dict(line.split('=', 1) for line in (self.folder / 'config.env').read_text().splitlines() if line)
        if self.env.get('CF_PROJECT') != name or any('\n' in v for v in self.env.values()):
            raise ValueError('Invalid run configuration')
        for service in PORTS:
            value = self.env.get('CF_' + service + '_PORT', '')
            if not value.isdecimal() or not 1024 <= int(value) <= 65535:
                raise ValueError('Invalid acceptance port')

    def port(self, service):
        return int(self.env['CF_' + service.upper() + '_PORT'])

    def url(self, service='web'):
        return f'http://127.0.0.1:{self.port(service)}'

    def compose(self, *args, timeout=600, capture=False):
        cmd = ['docker', 'compose', '--env-file', str(self.folder / 'config.env'),
               '--project-name', self.name, '-f', str(COMPOSE)]
        if (self.folder / 'images.compose.json').exists():
            cmd += ['-f', str(self.folder / 'images.compose.json')]
        cmd += ['--profile', 'multi', *args]
        result = subprocess.run(cmd, cwd=ROOT, env=clean_env(), timeout=timeout,
                                stdout=subprocess.PIPE if capture else None, text=True)
        if result.returncode:
            raise RuntimeError('Compose ' + args[0] + ' failed; see command output')
        return result.stdout if capture else None

    def container(self, service):
        value = self.compose('ps', '-a', '-q', service, capture=True).strip()
        if not re.fullmatch(r'[0-9a-f]{12,64}', value):
            raise RuntimeError('Expected one container for ' + service)
        owner = subprocess.check_output(['docker', 'inspect', '--format',
            '{{index .Config.Labels "com.docker.compose.project"}}', value], text=True).strip()
        if owner != self.name:
            raise RuntimeError('Container does not belong to this acceptance run')
        return value

    def database(self):
        return module('backup_local', 'backup-local.py').Database(self.container('mysql'))

    def record(self, phase, result, **details):
        self.state['checks'][phase] = dict(result=result, at=datetime.now(timezone.utc).isoformat(), **details)
        save(self.folder / 'state.json', self.state)
        print(json.dumps(dict(phase=phase, result=result, **details), ensure_ascii=False), flush=True)

    def wait(self, service='web', seconds=240):
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            try:
                request = urllib.request.Request(self.url(service) + '/api/auth/login',
                    data=json.dumps(dict(username='admin', password=self.env['CF_PASSWORD'])).encode(),
                    headers={'Content-Type': 'application/json'})
                with urllib.request.urlopen(request, timeout=5) as response:
                    if json.load(response).get('data', {}).get('token'):
                        return
            except (OSError, ValueError):
                pass
            time.sleep(2)
        raise RuntimeError(service + ' did not become ready within deadline')


def initialize(offset=0, minio_image=None):
    name = 'cf-accept-' + uuid.uuid4().hex[:12]
    ports = {k: v + offset for k, v in PORTS.items()}
    for port in ports.values():
        if not 1024 <= port <= 65535:
            raise ValueError('Port outside permitted range')
        with socket.socket() as check:
            check.bind(('127.0.0.1', port))
    folder = RUNS / name
    folder.mkdir(parents=True, exist_ok=False)
    env = dict(CF_PROJECT=name, CF_PASSWORD=secrets.token_urlsafe(24), CF_JWT=secrets.token_urlsafe(48))
    env.update({'CF_' + k + '_PORT': str(v) for k, v in ports.items()})
    if minio_image:
        if not re.fullmatch(r'[a-zA-Z0-9./:_@-]+', minio_image):
            raise ValueError('Invalid MinIO image reference')
        env['CF_MINIO_IMAGE'] = minio_image
    (folder / 'config.env').write_text(''.join(k + '=' + v + '\n' for k, v in env.items()), encoding='utf-8')
    save(folder / 'state.json', dict(owner='communityfix-acceptance-v1', project=name,
        createdAt=datetime.now(timezone.utc).isoformat(), checks={}))
    print(name)
    return Run(name)


def build_frontends(run):
    env = clean_env()
    env.update(VITE_H5_API_BASE='/api', VITE_API_BASE=run.url() + '/api', VITE_WECHAT_ORDER_TEMPLATE_ID='')
    for directory, scripts in [('admin-web', ['build']), ('miniapp', ['typecheck', 'build:h5', 'build:mp-weixin'])]:
        for script in scripts:
            with (run.folder / (directory + '-' + script.replace(':', '-') + '.log')).open('wb') as log:
                result = subprocess.run(['npm.cmd' if os.name == 'nt' else 'npm', 'run', script],
                    cwd=ROOT / directory, env=env, stdout=log, stderr=subprocess.STDOUT, timeout=300)
            if result.returncode:
                raise RuntimeError(directory + ' ' + script + ' failed; inspect run log')
    run.record('frontends', 'PASS')


def fault(run):
    checks = module('acceptance_checks', 'acceptance_checks.py')
    client = checks.Client(run.url(), run.env['CF_PASSWORD'], 'resident')
    run.wait('backend2')
    start = time.monotonic()
    samples, healthy = [], 0
    try:
        run.compose('stop', 'backend')
        while time.monotonic() - start < 240:
            status, _ = client.call('GET', '/api/auth/me')
            samples.append(dict(seconds=round(time.monotonic() - start, 2), status=status))
            healthy = healthy + 1 if status == 200 else 0
            # Default Eureka/cache expiration may retain a dead endpoint initially.
            if healthy >= 30:
                break
            time.sleep(1)
        if healthy < 30:
            raise RuntimeError('Gateway did not sustain 30 successful requests after instance failure')
    finally:
        run.compose('start', 'backend')
        run.wait('backend')
    run.record('instanceFailure', 'PASS', observations=samples,
               observedRecoverySeconds=samples[-30]['seconds'], restart='PASS')


def export_report(run):
    """Export machine-readable evidence, never config.env or raw logs."""
    report = dict(project=run.name, generatedAt=datetime.now(timezone.utc).isoformat(), checks=run.state['checks'])
    text = json.dumps(report, ensure_ascii=False, indent=2)
    for key, value in run.env.items():
        if any(part in key for part in ('PASSWORD', 'JWT', 'SECRET')) and value:
            text = text.replace(value, '[REDACTED]')
    save(run.folder / 'report.json', json.loads(text))
    print(str(run.folder / 'report.json'))


def main():
    global RUNS
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['init', 'config', 'build', 'up', 'wait', 'verify', 'fault',
                                          'backup', 'restore', 'performance', 'down', 'status', 'report'])
    parser.add_argument('--run')
    parser.add_argument('--port-offset', type=int, default=0)
    parser.add_argument('--minio-image')
    parser.add_argument('--backup', type=Path)
    parser.add_argument('--short', action='store_true')
    parser.add_argument('--output-dir', type=Path, default=RUNS,
                        help='Acceptance run root; use the same directory for every command')
    args = parser.parse_args()
    RUNS = args.output_dir.resolve()
    if args.command == 'init':
        initialize(args.port_offset, args.minio_image)
        return 0
    if not args.run:
        parser.error('--run is required')
    run = Run(args.run)
    try:
        if args.command == 'config':
            run.compose('config', '--quiet')
            run.record('config', 'PASS')
        elif args.command == 'build':
            build_frontends(run)
            run.compose('build', 'registry', 'backend', 'gateway', timeout=1800)
            run.record('images', 'PASS')
        elif args.command == 'up':
            run.compose('up', '-d', 'mysql', 'redis', 'minio', 'mock', 'registry', 'backend', 'gateway', 'nginx')
            run.wait('backend')
            run.compose('up', '-d', 'backend2')
            run.wait('backend2')
            run.wait()
            run.record('startup', 'PASS')
        elif args.command == 'wait':
            run.wait()
            run.wait('backend2')
        elif args.command == 'verify':
            module('acceptance_checks', 'acceptance_checks.py').verify(run)
            run.record('verify', 'PASS')
        elif args.command == 'fault':
            fault(run)
        elif args.command in ('backup', 'restore'):
            storage = module('acceptance_storage', 'acceptance_storage.py')
            if args.command == 'backup':
                storage.backup(run)
            else:
                if not args.backup:
                    parser.error('--backup is required')
                storage.restore(run, args.backup)
        elif args.command == 'performance':
            module('acceptance_checks', 'acceptance_checks.py').performance(run, args.short)
        elif args.command == 'down':
            # A random project name, fixed config and ownership-checked containers
            # constrain all deletions to this run; never call global prune.
            for value in run.compose('ps', '-a', '--services', capture=True).split():
                run.container(value)
            run.compose('down', '--volumes', timeout=180)
            run.record('cleanup', 'PASS')
        elif args.command == 'report':
            export_report(run)
        else:
            print(json.dumps(run.state, ensure_ascii=False, indent=2))
    except Exception as error:
        (run.folder / (args.command + '-error.log')).write_text(traceback.format_exc(), encoding='utf-8')
        previous = run.state['checks'].get(args.command, {})
        details = {k: v for k, v in previous.items() if k not in ('result', 'at', 'error')}
        run.record(args.command, 'FAIL', error=type(error).__name__ + ': ' + str(error), **details)
        return 1
    return 0


if __name__ == '__main__':
    sys.modules['acceptance'] = sys.modules[__name__]
    raise SystemExit(main())
