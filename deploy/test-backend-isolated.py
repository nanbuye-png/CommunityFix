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
               EUREKA_ENABLED='false', CF_INTEGRATION='true')
    report, created, granted = {'result': 'FAIL'}, False, False
    try:
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
