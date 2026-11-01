"""HTTP workflows, multi-instance checks and gateway load for acceptance.py."""
import argparse
import base64
from datetime import datetime, timezone
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
import platform
import struct
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zlib
from acceptance import ROOT, module, save

def png_chunk(kind, data):
    return struct.pack('!I', len(data)) + kind + data + struct.pack('!I', zlib.crc32(kind + data))


PNG = (b'\x89PNG\r\n\x1a\n' + png_chunk(b'IHDR', struct.pack('!2I5B', 1, 1, 8, 2, 0, 0, 0))
       + png_chunk(b'IDAT', zlib.compress(b'\x00\xff\x00\x00')) + png_chunk(b'IEND', b''))


def download(url):
    with urllib.request.urlopen(url, timeout=30) as response:
        return response.read()


class Client:
    def __init__(self, base, password, username):
        self.base, self.token = base, None
        session = self.ok('POST', '/api/auth/login', dict(username=username, password=password))
        self.token = session['token']

    def call(self, method, path, body=None, key=None, raw=None, content_type='application/json'):
        headers = {'Content-Type': content_type}
        if self.token:
            headers['Authorization'] = 'Bearer ' + self.token
        if key:
            headers['Idempotency-Key'] = key
        data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
        request = urllib.request.Request(self.base + path, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=45) as response:
                return response.status, json.load(response)
        except urllib.error.HTTPError as response:
            try:
                value = json.load(response)
            except ValueError:
                value = {}
            return response.code, value
        except OSError:
            return 599, {}

    def ok(self, method, path, body=None, key=None):
        code, value = self.call(method, path, body, key)
        if code != 200 or value.get('code') != 200:
            raise AssertionError(f'{method} {path}: HTTP {code}, error {value.get("errorCode")}')
        return value['data']

    def upload(self, data=PNG):
        boundary = 'cf' + uuid.uuid4().hex
        raw = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="test.png"\r\n'
               'Content-Type: image/png\r\n\r\n').encode() + data + f'\r\n--{boundary}--\r\n'.encode()
        return self.call('POST', '/api/files', raw=raw, content_type='multipart/form-data; boundary=' + boundary)


def verify(run):
    password = run.env['CF_PASSWORD']
    clients = {name: Client(run.url(), password, name) for name in ('admin', 'property', 'resident', 'worker', 'resident2', 'property2')}
    resident, prop, worker = (clients[k] for k in ('resident', 'property', 'worker'))
    first = Client(run.url('backend'), password, 'resident')
    second = Client(run.url('backend2'), password, 'resident')
    db = run.database()
    # Only the generated acceptance DB is used for deterministic fixtures.
    room = db.query('communityfix', "SELECT room_id FROM resident_room_binding b JOIN sys_user u ON u.id=b.user_id WHERE u.username='resident' AND b.status='APPROVED' LIMIT 1")[0][0]
    worker_id = db.query('communityfix', "SELECT w.id FROM worker_profile w JOIN sys_user u ON u.id=w.user_id WHERE u.username='worker'")[0][0]
    checks = []
    def passed(name):
        checks.append(name)
        save(run.folder / 'checks-progress.json', dict(passed=checks))
    def action(client, order, action, **extra):
        return client.ok('POST', '/api/repair/orders/' + order['id'] + '/' + action,
                         dict(version=order['version'], **extra), uuid.uuid4().hex)
    def body(attachments=None):
        return dict(roomId=room, categoryId='1', title='Acceptance plumbing', description='Synthetic repair only',
                    priority='NORMAL', attachmentIds=attachments or [])

    for service in ('backend', 'backend2', 'gateway', 'registry'):
        assert json.loads(download(run.url(service) + '/actuator/health'))['status'] == 'UP'
    assert subprocess.check_output(['docker', 'exec', run.container('redis'), 'redis-cli', 'ping'], text=True).strip() == 'PONG'
    for service in ('web', 'h5'):
        assert b'<html' in download(run.url(service)).lower()
    request = urllib.request.Request(run.url('registry') + '/eureka/apps/COMMUNITYFIX-SERVICE', headers={'Accept': 'application/json'})
    with urllib.request.urlopen(request, timeout=5) as response:
        instances = json.load(response)['application']['instance']
        assert len(instances) == 2 and all(i['status'] == 'UP' for i in instances)
    passed('service health, two registered instances and both frontend entrypoints')

    for name, expected in [('admin', 'SUPER_ADMIN'), ('property', 'ADMIN'), ('resident', 'RESIDENT'), ('worker', 'WORKER')]:
        assert expected in clients[name].ok('GET', '/api/auth/me')['roles']
    passed('four-role login via nginx/gateway')
    with ThreadPoolExecutor(2) as pool:
        results = list(pool.map(lambda c: c.upload(), [first, second]))
    assert all(code == 200 for code, _ in results), results
    ids = [value['data']['attachmentId'] for _, value in results]
    url = resident.ok('GET', '/api/files/' + ids[0] + '/access-url')['url']
    assert urllib.parse.urlsplit(url).netloc == urllib.parse.urlsplit(run.url()).netloc, 'Internal image URL leaked'
    assert download(url) == PNG
    assert download(second.ok('GET', '/api/files/' + ids[0] + '/access-url')['url']) == PNG
    assert clients['resident2'].call('GET', '/api/files/' + ids[0] + '/access-url')[0] == 404
    parsed = urllib.parse.urlsplit(url)
    for query in ('expires=1&signature=bad', 'expires=' + dict(urllib.parse.parse_qsl(parsed.query))['expires'] + '&signature=bad'):
        assert resident.call('GET', parsed.path + '?' + query)[0] == 403
    for invalid in (b'', b'not a PNG', b'x' * (5 * 1024 * 1024 + 1)):
        assert resident.upload(invalid)[0] in (400, 413)
    store = module('acceptance_storage', 'acceptance_storage.py').s3(run)
    key = db.query('communityfix', 'SELECT object_key FROM file_asset WHERE id=' + str(int(ids[0])))[0][0]
    try:
        download(run.url('minio') + '/acceptance/' + key)
        raise AssertionError('Object bucket permits anonymous download')
    except urllib.error.HTTPError as error:
        assert error.code == 403
    passed('concurrent first upload, shared private bytes, URL origin, signatures, invalid files')

    order = resident.ok('POST', '/api/repair/orders', body([ids[0]]), uuid.uuid4().hex)
    for name in ('resident2', 'property2'):
        assert clients[name].call('GET', '/api/repair/orders/' + order['id'])[0] == 404
    assert clients['admin'].call('GET', '/api/repair/orders/' + order['id'])[0] == 403
    order = action(prop, order, 'review', approved=True)
    order = action(prop, order, 'assign', workerId=worker_id)
    order = action(worker, order, 'accept')
    order = action(worker, order, 'start')
    order = action(worker, order, 'finish', content='First synthetic result')
    order = action(resident, order, 'rework', reason='Synthetic rework')
    assert order['roundNo'] == 2
    order = action(prop, order, 'assign', workerId=worker_id)
    order = action(worker, order, 'accept')
    order = action(worker, order, 'start')
    order = action(worker, order, 'finish', content='Final synthetic result')
    order = action(resident, order, 'confirm')
    assert order['status'] == 'CLOSED'
    resident.ok('POST', '/api/repair/orders/' + order['id'] + '/evaluations', dict(score=5, content='Synthetic evaluation'), uuid.uuid4().hex)
    complaint = resident.ok('POST', '/api/repair/orders/' + order['id'] + '/complaints', dict(content='Synthetic complaint'), uuid.uuid4().hex)
    for version, status in [(0, 'PROCESSING'), (1, 'CLOSED')]:
        prop.ok('POST', '/api/complaints/' + complaint['id'] + '/process', dict(version=version, status=status, resolution='Synthetic handling'))
    assert len(prop.ok('GET', '/api/complaints/' + complaint['id'])['logs']) == 3
    passed('full workflow, rework, evaluation, complaint and role isolation')

    name = 'qa' + uuid.uuid4().hex[:16]
    code, registered = resident.call('POST', '/api/auth/register', dict(username=name, password=password, nickname='Acceptance resident'))
    assert code == 200
    new = Client(run.url(), password, name)
    assert new.call('POST', '/api/repair/orders', body(), uuid.uuid4().hex)[0] == 400
    binding = new.ok('POST', '/api/room-bindings', dict(roomId=room))
    prop.ok('POST', '/api/room-bindings/' + binding['id'] + '/review', dict(approved=True))
    created = new.ok('POST', '/api/repair/orders', body(), uuid.uuid4().hex)
    action(new, created, 'cancel', reason='Acceptance complete')
    passed('new resident binding approval gates repairs')

    idem = uuid.uuid4().hex
    with ThreadPoolExecutor(2) as pool:
        results = list(pool.map(lambda c: c.ok('POST', '/api/repair/orders', body(), idem), [first, second]))
    assert results[0]['id'] == results[1]['id']
    assert int(db.query('communityfix', 'SELECT COUNT(*) FROM repair_order_log WHERE order_id=' + results[0]['id'])[0][0]) == 1
    action(resident, results[0], 'cancel', reason='Acceptance duplicate complete')
    with ThreadPoolExecutor(10) as pool:
        limits = list(pool.map(lambda i: (first if i % 2 else second).call('POST', '/api/ai/repair-analysis', dict(description='Synthetic leaking pipe')), range(10)))
    assert sorted(code for code, _ in limits) == [200] * 5 + [429] * 5, limits
    assert all(value['data']['source'] == 'AI' for code, value in limits if code == 200), 'Mock AI did not return a valid suggestion'
    passed('two-process shared idempotency and AI rate limit')

    capacity = int(db.query('communityfix', 'SELECT max_active_orders FROM worker_profile WHERE id=' + worker_id)[0][0])
    pending = [action(prop, resident.ok('POST', '/api/repair/orders', body(), uuid.uuid4().hex), 'review', approved=True) for _ in range(2)]
    p1, p2 = (Client(run.url(service), password, 'property') for service in ('backend', 'backend2'))
    try:
        db.query('communityfix', 'UPDATE worker_profile SET max_active_orders=1 WHERE id=' + worker_id)
        with ThreadPoolExecutor(2) as pool:
            futures = [pool.submit(client.call, 'POST', '/api/repair/orders/' + order['id'] + '/assign',
                dict(version=order['version'], workerId=worker_id), uuid.uuid4().hex) for client, order in zip((p1, p2), pending)]
            assert sorted(f.result()[0] for f in futures) == [200, 409]
    finally:
        db.query('communityfix', f'UPDATE worker_profile SET max_active_orders={capacity} WHERE id={worker_id}')
        for order in pending:
            current = prop.ok('GET', '/api/repair/orders/' + order['id'])
            if current['status'] == 'ASSIGNED':
                current = action(prop, current, 'revoke', reason='Acceptance capacity complete')
            action(resident, current, 'cancel', reason='Acceptance capacity complete')
    overdue = resident.ok('POST', '/api/repair/orders', body(), uuid.uuid4().hex)
    db.query('communityfix', "UPDATE repair_order SET response_started_at=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 120 MINUTE),response_deadline_at=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 MINUTE) WHERE id=" + overdue['id'])
    for _ in range(20):
        count = int(db.query('communityfix', 'SELECT COUNT(*) FROM sla_warning WHERE order_id=' + overdue['id'])[0][0])
        if count:
            break
        time.sleep(1)
    time.sleep(6)
    assert int(db.query('communityfix', 'SELECT COUNT(*) FROM sla_warning WHERE order_id=' + overdue['id'])[0][0]) == 1
    action(resident, overdue, 'cancel', reason='Acceptance SLA complete')
    passed('two-process worker capacity contention and SLA deduplication')

    # Force an unused image eligible for cleanup; scheduler delay is configured
    # only in the disposable acceptance deployment.
    temp_id = str(int(ids[1]))
    temp_key = db.query('communityfix', 'SELECT object_key FROM file_asset WHERE id=' + temp_id)[0][0]
    db.query('communityfix', 'UPDATE file_asset SET expires_at=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE id=' + temp_id)
    for _ in range(20):
        if db.query('communityfix', 'SELECT status FROM file_asset WHERE id=' + temp_id)[0][0] == 'DELETED':
            break
        time.sleep(1)
    else:
        raise AssertionError('Expired temporary object not cleaned')
    assert temp_key not in store.inventory()
    assert download(resident.ok('GET', '/api/files/' + ids[0] + '/access-url')['url']) == PNG
    passed('temporary cleanup preserves attached file')
    missing = resident.upload()[1]['data']['attachmentId']
    missing_key = db.query('communityfix', 'SELECT object_key FROM file_asset WHERE id=' + str(int(missing)))[0][0]
    store.request('DELETE', missing_key)
    missing_url = urllib.parse.urlsplit(resident.ok('GET', '/api/files/' + missing + '/access-url')['url'])
    assert resident.call('GET', missing_url.path + '?' + missing_url.query)[0] == 503
    db.query('communityfix', 'UPDATE file_asset SET expires_at=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE id=' + str(int(missing)))
    try:
        run.compose('stop', 'minio')
        content_url = urllib.parse.urlsplit(resident.ok('GET', '/api/files/' + ids[0] + '/access-url')['url'])
        assert resident.call('GET', content_url.path + '?' + content_url.query)[0] == 503
        assert resident.upload()[0] == 503
    finally:
        run.compose('start', 'minio')
    for _ in range(20):
        try:
            assert download(resident.ok('GET', '/api/files/' + ids[0] + '/access-url')['url']) == PNG
            break
        except (OSError, AssertionError):
            time.sleep(1)
    else:
        raise AssertionError('MinIO recovery failed')
    passed('storage outage maps to 503 and recovers')
    run.record('httpAcceptance', 'PASS', checks=checks, restoredAttachmentId=ids[0])


def performance(run, short):
    # Guard against corrupting populated workflow fixtures. Run on a new,
    # dedicated acceptance project and never accept an arbitrary API URL/schema.
    db = run.database()
    if int(db.query('communityfix', 'SELECT COUNT(*) FROM repair_order')[0][0]):
        raise ValueError('Performance requires a NEW acceptance run with no orders')
    perf = module('performance_local', 'performance-local.py')
    args = argparse.Namespace(port=run.port('web'), users=100, rows=100000,
        warmup=15 if short else 120, duration=45 if short else 600, think=.2)
    run.wait()
    run.wait('backend2')
    for _ in range(60):
        req = urllib.request.Request(run.url('registry') + '/eureka/apps/COMMUNITYFIX-SERVICE', headers={'Accept': 'application/json'})
        with urllib.request.urlopen(req, timeout=5) as response:
            instances = json.load(response)['application']['instance']
        if isinstance(instances, list) and len(instances) == 2 and all(i['status'] == 'UP' for i in instances):
            break
        time.sleep(2)
    else:
        raise RuntimeError('Two healthy instances must be registered before load')
    environment = dict(platform=platform.platform(), logicalCpus=os.cpu_count(),
        docker=json.loads(subprocess.check_output(['docker', 'info', '--format', '{{json .}}'], text=True)),
        backendHeap='128m..512m each', gatewayHeap='64m..256m', hikariPoolPerBackend=10)
    environment['docker'] = {k: environment['docker'][k] for k in ('NCPU', 'MemTotal', 'ServerVersion')}
    evidence = dict(startedAt=datetime.now(timezone.utc).isoformat(), environment=environment,
        runnerSha256={name: hashlib.sha256((ROOT / 'deploy' / name).read_bytes()).hexdigest()
                      for name in ('acceptance.py', 'acceptance_checks.py', 'performance-local.py')},
        imageIds={s: subprocess.check_output(['docker', 'inspect', '--format', '{{.Image}}', run.container(s)], text=True).strip()
                  for s in ('backend', 'backend2', 'gateway', 'nginx', 'mysql')})
    details = perf.seed(db, 'communityfix', args.rows, args.users)
    result = perf.measure(args, db, 'communityfix', run.env['CF_PASSWORD'], details, run.folder)
    result.update(evidence, finishedAt=datetime.now(timezone.utc).isoformat(),
                  finalOrders=int(db.query('communityfix', 'SELECT COUNT(*) FROM repair_order')[0][0]))
    save(run.folder / 'performance.json', dict(configuration=vars(args), formalDuration=not short, **result))
    run.record('performance', 'PASS' if result['thresholdsMet'] else 'FAIL',
               topology='Nginx -> Gateway -> two Eureka-discovered backends -> isolated MySQL',
               configuration=vars(args), formalDuration=not short, **result)
    if not result['thresholdsMet']:
        raise AssertionError('Performance thresholds failed')
