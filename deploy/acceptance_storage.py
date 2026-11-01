"""Coordinated database/object backup and restore into a NEW acceptance project."""
import gzip
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import time
import uuid
from acceptance import ROOT, Run, initialize, module, save


def s3(run):
    return module('acceptance_s3', 'acceptance_s3.py').S3(run.url('minio'), 'acceptance', run.env['CF_PASSWORD'])


def safe_keys(items):
    for key in items:
        if not re.fullmatch(r'[a-zA-Z0-9][a-zA-Z0-9.-]*', key) or key in ('.', '..'):
            raise ValueError('Unexpected object key; refuse filesystem mapping')


def tree_hashes(folder):
    result = {}
    for path in sorted(folder.rglob('*')):
        if path.is_symlink():
            raise ValueError('Static artifact contains a symbolic link')
        if path.is_file():
            result[path.relative_to(folder).as_posix()] = hashlib.sha256(path.read_bytes()).hexdigest()
    if not result:
        raise ValueError('Static artifact is empty')
    return result


def backup(run):
    folder = run.folder / ('backup-' + uuid.uuid4().hex[:12])
    folder.mkdir()
    db, store = run.database(), s3(run)
    start = time.monotonic()
    run.compose('stop', 'backend', 'backend2')
    try:
        before, rows = store.inventory(), db.fingerprint('communityfix')
        safe_keys(before)
        refs = db.references('communityfix')
        module('backup_local', 'backup-local.py').verify_references(refs, before)
        files = folder / 'objects'
        files.mkdir()
        for key, entry in before.items():
            content, _ = store.request('GET', key)
            if hashlib.sha256(content).hexdigest() != entry['sha256']:
                raise RuntimeError('Object changed during backup')
            (files / key).write_bytes(content)
        raw = folder / 'database.sql'
        db.dump('communityfix', raw)
        with raw.open('rb') as src, gzip.open(folder / 'database.sql.gz', 'wb') as dst:
            shutil.copyfileobj(src, dst)
        raw.unlink()
        if store.inventory() != before or db.fingerprint('communityfix') != rows:
            raise RuntimeError('Source changed; backup has no valid manifest')
        images = {}
        for service in ('backend', 'backend2', 'registry', 'gateway', 'minio', 'mysql', 'redis', 'nginx', 'mock'):
            image = subprocess.check_output(['docker', 'inspect', '--format', '{{.Image}}', run.container(service)], text=True).strip()
            tag = run.name + '-' + service + ':' + folder.name
            subprocess.run(['docker', 'tag', image, tag], check=True)
            images[service] = dict(id=image, tag=tag)
        shutil.copyfile(run.folder / 'config.env', folder / 'config.env')
        static = {}
        for name, source in [('admin', ROOT / 'admin-web/dist'), ('h5', ROOT / 'miniapp/dist/build/h5')]:
            original = tree_hashes(source)
            shutil.copytree(source, folder / ('web-' + name))
            static[name] = tree_hashes(folder / ('web-' + name))
            if static[name] != original:
                raise RuntimeError('Static artifacts changed during backup')
        manifest = dict(format=1, owner=run.name, storage='minio', files=before,
            tableCounts=db.counts('communityfix'), rowSha256=rows, references=refs, images=images,
            migrations=db.query('communityfix', 'SELECT version,checksum,success FROM flyway_schema_history ORDER BY installed_rank'),
            static=static, dumpSha256=hashlib.sha256((folder / 'database.sql.gz').read_bytes()).hexdigest())
        save(folder / 'manifest.json', manifest)
    finally:
        run.compose('start', 'backend', 'backend2')
        run.wait('backend')
        run.wait('backend2')
    run.record('backup', 'PASS', folder=str(folder), objects=len(before), durationSeconds=round(time.monotonic()-start, 3))


def validate_backup(run, folder):
    folder = folder.resolve(strict=True)
    if folder.parent != run.folder.resolve() or not re.fullmatch(r'backup-[0-9a-f]{12}', folder.name):
        raise ValueError('Backup must belong to the selected run')
    manifest = json.loads((folder / 'manifest.json').read_text())
    if manifest.get('format') != 1 or manifest.get('storage') != 'minio' or manifest.get('owner') != run.name:
        raise ValueError('Invalid backup ownership or format')
    if hashlib.sha256((folder / 'database.sql.gz').read_bytes()).hexdigest() != manifest['dumpSha256']:
        raise ValueError('Database dump hash mismatch')
    safe_keys(manifest['files'])
    for name in ('admin', 'h5'):
        if tree_hashes(folder / ('web-' + name)) != manifest['static'][name]:
            raise ValueError('Static artifact hash mismatch')
    for key, expected in manifest['files'].items():
        path = folder / 'objects' / key
        if path.is_symlink() or path.stat().st_size != expected['size'] or hashlib.sha256(path.read_bytes()).hexdigest() != expected['sha256']:
            raise ValueError('Object hash mismatch')
    return folder, manifest


def restore(run, folder):
    folder, manifest = validate_backup(run, folder)
    # Separate generated project, ports and volumes. No online target option.
    restored = initialize(offset=100, minio_image=manifest['images']['minio']['tag'])
    values = restored.env
    private = dict(line.split('=', 1) for line in (folder / 'config.env').read_text().splitlines())
    for key in ('CF_PASSWORD', 'CF_JWT'):
        values[key] = private[key]
    (restored.folder / 'config.env').write_text(''.join(k + '=' + v + '\n' for k, v in values.items()))
    for entry in manifest['images'].values():
        actual = subprocess.check_output(['docker', 'image', 'inspect', '--format', '{{.Id}}', entry['tag']], text=True).strip()
        if actual != entry['id']:
            raise ValueError('Snapshot image tag was changed')
    overrides = {key: {'image': entry['tag']} for key, entry in manifest['images'].items()}
    overrides['nginx']['volumes'] = [dict(type='bind', source=str(folder / ('web-' + name)),
        target='/web/' + name, read_only=True) for name in ('admin', 'h5')]
    save(restored.folder / 'images.compose.json', {'services': overrides})
    start = time.monotonic()
    try:
        restored.compose('up', '-d', 'mysql', 'minio')
        db = restored.database()
        for _ in range(90):
            try:
                db.query('communityfix', 'SELECT 1')
                s3(restored).request('PUT')
                break
            except Exception:
                time.sleep(2)
        else:
            raise RuntimeError('Restore infrastructure did not become ready')
        with gzip.open(folder / 'database.sql.gz', 'rb') as source:
            raw = source.read()
        if re.search(rb'^\s*(USE\s|(?:CREATE|DROP|ALTER)\s+DATABASE\s)', raw, re.I | re.M):
            raise ValueError('Dump contains a database switch')
        # Application user has rights only to this NEW database.
        db.execute('mysql', ['--binary-mode', 'communityfix'], sql=raw.decode('utf-8'),
                   credentials=('communityfix', private['CF_PASSWORD']))
        store = s3(restored)
        for key, entry in manifest['files'].items():
            store.request('PUT', key, (folder / 'objects' / key).read_bytes(), content_type=entry['contentType'])
        if db.counts('communityfix') != manifest['tableCounts'] or db.fingerprint('communityfix') != manifest['rowSha256']:
            raise RuntimeError('Restored database differs')
        if db.references('communityfix') != manifest['references'] or store.inventory() != manifest['files']:
            raise RuntimeError('Restored objects or references differ')
        restored.compose('up', '-d', '--no-build', 'registry', 'mock', 'redis', 'backend', 'gateway', 'nginx')
        restored.wait('backend')
        restored.wait()
        checks = module('acceptance_checks', 'acceptance_checks.py')
        resident = checks.Client(restored.url(), private['CF_PASSWORD'], 'resident')
        checks.Client(restored.url(), private['CF_PASSWORD'], 'admin')
        attachments = 0
        for row in db.query('communityfix', "SELECT f.id,f.object_key FROM file_asset f JOIN sys_user u ON u.id=f.uploader_id WHERE u.username='resident' AND f.status='ATTACHED'"):
            url = resident.ok('GET', '/api/files/' + row[0] + '/access-url')['url']
            if hashlib.sha256(checks.download(url)).hexdigest() != manifest['files'][row[1]]['sha256']:
                raise RuntimeError('Restored attachment API hash mismatch')
            attachments += 1
        if not attachments:
            raise RuntimeError('No attached resident image exercised the restored API')
        restored.record('restore', 'PASS', objects=len(manifest['files']), tables=len(manifest['tableCounts']), attachmentApiHashes=attachments)
        run.record('restore', 'PASS', restoredRun=restored.name, attachmentApiHashes=attachments,
                   durationSeconds=round(time.monotonic()-start, 3))
    finally:
        restored.compose('down', '--volumes')
        restored.record('cleanup', 'PASS')
