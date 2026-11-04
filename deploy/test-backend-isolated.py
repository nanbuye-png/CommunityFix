"""Run every backend test against a fresh, randomly named MySQL schema.

This deliberately does not use run-backend.ps1 -Test (that targets the demo DB).
"""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import secrets
import subprocess
import shutil
import time
import uuid
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('acceptance', ROOT / 'deploy/acceptance.py')
acceptance = importlib.util.module_from_spec(spec)
spec.loader.exec_module(acceptance)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--container', default='communityfix-mysql-1')
    parser.add_argument('--db-port', type=int, default=3307)
    parser.add_argument('--output-dir', type=Path, default=ROOT / '.local/acceptance')
    args = parser.parse_args()
    folder = args.output_dir.resolve() / ('tests-' + uuid.uuid4().hex[:12])
    folder.mkdir(parents=True)
    db = acceptance.module('backup_local', 'backup-local.py').Database(args.container)
    schema, user, password = 'cf_test_' + uuid.uuid4().hex, 'cf_test_' + uuid.uuid4().hex[:20], secrets.token_urlsafe(32)
    env = acceptance.clean_env()
    env.update(DB_URL=f'jdbc:mysql://127.0.0.1:{args.db_port}/{schema}?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8',
               DB_USER=user, DB_PASSWORD=password, JWT_SECRET=secrets.token_urlsafe(48),
               BOOTSTRAP_USERNAME='admin', BOOTSTRAP_PASSWORD=password, DEMO_ENABLED='true',
               STORAGE_MODE='local', STORAGE_DIRECTORY=str(folder / 'uploads'),
               EUREKA_ENABLED='false', CF_INTEGRATION='true', COMMUNITYFIX_SUPPORT_JOBS_ENABLED='false')
    redis_name = 'cf-test-redis-' + uuid.uuid4().hex[:12]
    redis_password = secrets.token_urlsafe(32)
    rabbit_name = 'cf-test-rabbit-' + uuid.uuid4().hex[:12]
    rabbit_password = secrets.token_urlsafe(32)
    rabbit_created = False
    report, created, granted, redis_created = {'result': 'FAIL'}, False, False, False
    try:
        subprocess.run(['docker', 'run', '-d', '--name', rabbit_name, '--hostname', 'rabbit-test', '-p', '127.0.0.1::5672',
                        '-e', 'RABBITMQ_DEFAULT_USER=test', '-e', 'RABBITMQ_DEFAULT_PASS=' + rabbit_password,
                        'rabbitmq:4.2-management-alpine'], check=True, capture_output=True, timeout=180)
        rabbit_created = True
        rabbit_port = subprocess.check_output(['docker', 'port', rabbit_name, '5672/tcp'], text=True, timeout=15).strip().rsplit(':', 1)[1]
        for attempt in range(60):
            ready = subprocess.run(['docker', 'exec', '--user', 'rabbitmq', rabbit_name, 'rabbitmq-diagnostics', '-q', 'check_port_connectivity'], capture_output=True, timeout=10)
            if ready.returncode == 0:
                break
            time.sleep(1)
        else:
            diagnostic = subprocess.run(['docker', 'logs', '--tail', '50', rabbit_name], capture_output=True)
            (folder / 'rabbit-startup.log').write_bytes((ready.stdout + ready.stderr + diagnostic.stdout + diagnostic.stderr).replace(rabbit_password.encode(), b'[redacted]'))
            raise RuntimeError('Isolated RabbitMQ did not become ready')
        env.update(CF_RABBIT_INTEGRATION='true', CF_RABBIT_PORT=rabbit_port, CF_RABBIT_PASSWORD=rabbit_password,
                   RABBITMQ_ENABLED='false')
        subprocess.run(['docker', 'run', '-d', '--name', redis_name, '-p', '127.0.0.1::6379',
                        'redis:7.4.2-alpine', 'redis-server', '--requirepass', redis_password],
                       check=True, capture_output=True, timeout=120)
        redis_created = True
        port = subprocess.check_output(['docker', 'port', redis_name, '6379/tcp'], text=True, timeout=15).strip().rsplit(':', 1)[1]
        for attempt in range(30):
            ping = subprocess.run(['docker', 'exec', '-e', 'REDISCLI_AUTH=' + redis_password,
                                   redis_name, 'redis-cli', 'ping'], capture_output=True, text=True, timeout=5)
            if ping.returncode == 0 and ping.stdout.strip() == 'PONG':
                break
            time.sleep(0.2)
        else:
            raise RuntimeError('Isolated Redis did not become ready')
        env.update(CF_REDIS_INTEGRATION='true', CF_REDIS_PORT=port, CF_REDIS_PASSWORD=redis_password,
                   REDIS_ENABLED='false')
        db.query(None, f'CREATE DATABASE `{schema}` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci')
        created = True
        db.query(None, f"CREATE USER '{user}'@'%' IDENTIFIED BY '{password}'")
        granted = True
        db.query(None, f"GRANT ALL PRIVILEGES ON `{schema}`.* TO '{user}'@'%'")
        started = time.time()
        with (folder / 'tests.log').open('wb') as log:
            result = subprocess.run(['mvn.cmd' if os.name == 'nt' else 'mvn', '-B',
                '-Dmaven.repo.local=' + str(ROOT / '.local/m2'), '-f', str(ROOT / 'backend/pom.xml'),
                'test'],
                cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT, timeout=600)
        totals = dict(tests=0, failures=0, errors=0, skipped=0)
        (folder / 'surefire').mkdir()
        for path in (ROOT / 'backend/target/surefire-reports').glob('TEST-*.xml'):
            if path.stat().st_mtime < started:
                continue
            shutil.copyfile(path, folder / 'surefire' / path.name)
            for key in totals:
                totals[key] += int(ET.parse(path).getroot().attrib.get(key, 0))
        report['tests'] = totals
        if result.returncode or totals['tests'] < 43 or any(totals[k] for k in ('failures', 'errors', 'skipped')):
            raise RuntimeError('Backend tests failed or were skipped; see ' + str(folder / 'tests.log'))
        report['result'] = 'PASS'
    finally:
        try:
            if rabbit_created:
                subprocess.run(['docker', 'rm', '-fv', rabbit_name], check=True, capture_output=True, timeout=30)
            if redis_created:
                subprocess.run(['docker', 'rm', '-fv', redis_name], check=True, capture_output=True, timeout=30)
            if granted:
                db.query(None, f"DROP USER '{user}'@'%'")
            if created:
                db.query(None, f'DROP DATABASE `{schema}`')
            report['cleanup'] = 'PASS'
        finally:
            acceptance.save(folder / 'report.json', report)
            print(json.dumps(dict(report=str(folder / 'report.json'), **report)))


if __name__ == '__main__':
    main()
