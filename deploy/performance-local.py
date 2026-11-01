"""Run a disposable MySQL + HTTP performance drill without reading deployment secrets.

Only a newly generated schema/user are created or dropped. Requires the existing
MySQL container, its published loopback port, Java 21 and a freshly packaged jar.
"""
import argparse
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import hashlib
import http.client
import importlib.util
import json
import math
import os
from pathlib import Path
import platform
import secrets
import socket
import subprocess
import threading
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("backup_local", ROOT / "deploy/backup-local.py")
backup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(backup)


def save(path, value):
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def percentile(values, fraction):
    return round(values[max(0, math.ceil(len(values) * fraction) - 1)], 2) if values else None


class Client:
    def __init__(self, port):
        self.port, self.connection, self.token = port, None, None

    def close(self):
        if self.connection:
            self.connection.close()
        self.connection = None

    def call(self, method, path, body=None):
        if self.connection is None:
            self.connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=30)
        headers = {"Content-Type": "application/json"}
        if self.token:
            headers["Authorization"] = "Bearer " + self.token
        if method == "POST" and path.startswith("/api/repair/orders"):
            headers["Idempotency-Key"] = uuid.uuid4().hex
        started = time.perf_counter()
        try:
            self.connection.request(method, path, json.dumps(body) if body is not None else None, headers)
            response = self.connection.getresponse()
            data = json.loads(response.read())
            status = response.status
            if status == 200 and path.startswith("/api/") and data.get("code") != 200:
                status = 599
            return status, data.get("data", data), (time.perf_counter() - started) * 1000
        except (OSError, http.client.HTTPException, ValueError):
            self.close()
            return 599, {}, (time.perf_counter() - started) * 1000


def seed(db, schema, rows, users):
    residents = [10000 + i for i in range(users) if i % 5 != 0]
    people = ",".join(f"({10000+i},'perf{i}',@password,'Synthetic {i}')" for i in range(users))
    roles = ",".join(f"({10000+i},{3 if i % 5 == 0 else 1})" for i in range(users))
    scopes = ",".join(f"({10000+i},1)" for i in range(users) if i % 5 == 0)
    slots = ",".join(f"({i},{value})" for i, value in enumerate(residents))
    sql = f"""
SET @password=(SELECT password FROM sys_user WHERE username='resident');
INSERT INTO sys_user(id,username,password,nickname) VALUES {people};
INSERT INTO sys_user_role(user_id,role_id) VALUES {roles};
INSERT INTO admin_community(user_id,community_id) VALUES {scopes};
CREATE TEMPORARY TABLE perf_residents(slot INT PRIMARY KEY,user_id BIGINT);
INSERT INTO perf_residents VALUES {slots};
INSERT INTO resident_room_binding(user_id,room_id,status,reviewer_id)
 SELECT user_id,1,'APPROVED',(SELECT id FROM sys_user WHERE username='property') FROM perf_residents;
CREATE TABLE perf_digits(n INT PRIMARY KEY);
INSERT INTO perf_digits VALUES (0),(1),(2),(3),(4),(5),(6),(7),(8),(9);
CREATE TEMPORARY TABLE sequence_numbers(n INT PRIMARY KEY);
INSERT INTO sequence_numbers SELECT a.n+10*b.n+100*c.n+1000*d.n+10000*e.n+100000*f.n
 FROM perf_digits a CROSS JOIN perf_digits b CROSS JOIN perf_digits c CROSS JOIN perf_digits d CROSS JOIN perf_digits e CROSS JOIN perf_digits f
 WHERE a.n+10*b.n+100*c.n+1000*d.n+10000*e.n+100000*f.n < {rows};
INSERT INTO repair_order(id,order_no,resident_id,community_id,room_id,category_id,title,description,
 priority,status,status_changed_at,response_started_at,response_deadline_at,sla_rule_id,
 response_minutes_snapshot,completion_minutes_snapshot,created_at,closed_at)
 SELECT s.n+1,CONCAT('PERF',LPAD(s.n+1,24,'0')),r.user_id,1,1,1+MOD(s.n,4),
 'Synthetic performance order','Synthetic data only','NORMAL',IF(MOD(s.n,10)<8,'CLOSED','SUBMITTED'),
 UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 365 DAY),
 (SELECT id FROM sla_rule WHERE priority='NORMAL'),120,1440,
 DATE_SUB(UTC_TIMESTAMP(3),INTERVAL MOD(s.n,30) DAY),
 IF(MOD(s.n,10)<8,UTC_TIMESTAMP(3),NULL)
 FROM sequence_numbers s JOIN perf_residents r ON r.slot=MOD(s.n,{len(residents)});
ANALYZE TABLE repair_order;
DROP TABLE perf_digits;
"""
    # This input contains synthetic data only. Keep useful MySQL diagnostics
    # here while the shared backup helper continues hiding real resident data.
    result = subprocess.run(["docker", "exec", "-i", db.container, "sh", "-c",
                             'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec "$@"', "cf-perf", "mysql",
                             "--user=root", "--default-character-set=utf8mb4", schema],
                            input=sql.encode(), stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=300)
    if result.returncode:
        raise RuntimeError("Synthetic seed failed: " + result.stderr.decode("utf-8", errors="replace"))
    assert int(db.query(schema, "SELECT COUNT(*) FROM repair_order")[0][0]) == rows
    return {user: slot + 1 for slot, user in enumerate(residents)}


def measure(args, db, schema, password, details, folder):
    clients = []
    for i in range(args.users):
        client = Client(args.port)
        status, data, _ = client.call("POST", "/api/auth/login", {"username": f"perf{i}", "password": password})
        if status != 200 or not data.get("token"):
            raise RuntimeError("Synthetic user login failed")
        client.token = data["token"]
        clients.append(client)
    gate = threading.Barrier(args.users + 1)
    stop = threading.Event()
    window = {}
    connection_samples = []

    def virtual_user(i):
        client, samples, created = clients[i], [], None
        property_user = i % 5 == 0
        detail = 1 if property_user else details[10000 + i]
        gate.wait()
        step = 0
        try:
            while not stop.is_set() and time.perf_counter() < window["end"]:
                position = step % 10
                body, method = None, "GET"
                if position < (5 if property_user else 4):
                    name, path = "list", "/api/repair/orders?page=1&size=20"
                elif position < (8 if property_user else 6):
                    name, path = "detail", f"/api/repair/orders/{detail}"
                elif property_user and position == 8:
                    name, path = "statistics", "/api/statistics/overview"
                elif not property_user and position == 6:
                    name, path = "notifications", "/api/notifications?page=1&size=20"
                elif not property_user and position == 7:
                    name, method, path = "create", "POST", "/api/repair/orders"
                    body = {"roomId": "1", "categoryId": "1", "title": "Synthetic load", "description": "Disposable drill", "priority": "NORMAL", "attachmentIds": []}
                elif not property_user and position == 8 and created:
                    name, method, path = "cancel", "POST", f"/api/repair/orders/{created['id']}/cancel"
                    body = {"version": created["version"], "reason": "Disposable drill"}
                else:
                    name, path = "me", "/api/auth/me"
                sent = time.perf_counter()
                status, data, elapsed = client.call(method, path, body)
                if status == 200:
                    valid = isinstance(data, dict)
                    if name == "detail":
                        valid = valid and str(data.get("id")) == str(detail)
                    elif name == "list":
                        valid = valid and isinstance(data.get("records"), list) and int(data.get("total", 0)) > 0
                    elif name in ("create", "cancel"):
                        valid = valid and data.get("id") is not None and data.get("status") == ("SUBMITTED" if name == "create" else "CANCELED")
                    elif name == "me":
                        valid = valid and str(data.get("id")) == str(10000 + i)
                    if not valid:
                        status = 599
                if name == "create":
                    created = data if status == 200 else None
                if name == "cancel":
                    created = None
                # Count requests initiated during the measurement window, including
                # their complete latency even when they finish after its deadline.
                if sent >= window["start"]:
                    samples.append((name, status, elapsed))
                step += 1
                stop.wait(args.think)
        finally:
            client.close()
        return samples

    try:
        with ThreadPoolExecutor(max_workers=args.users) as executor:
            futures = [executor.submit(virtual_user, i) for i in range(args.users)]
            beginning = time.perf_counter()
            window.update(start=beginning + args.warmup, end=beginning + args.warmup + args.duration)
            gate.wait()
            try:
                while time.perf_counter() < window["end"]:
                    connections = db.query(None, "SELECT COUNT(*) FROM information_schema.processlist WHERE DB='" + schema + "'")
                    connection_samples.append(int(connections[0][0]))
                    save(folder / "progress.json", {"elapsedSeconds": round(time.perf_counter()-beginning), "warmupSeconds": args.warmup, "durationSeconds": args.duration, "databaseConnections": connection_samples[-1]})
                    stop.wait(min(10, max(0, window["end"]-time.perf_counter())))
                samples = [sample for future in futures for sample in future.result()]
            except BaseException:
                # Signal workers before executor shutdown waits for them.
                stop.set()
                raise
    finally:
        stop.set()
        for client in clients:
            client.close()
    grouped = defaultdict(list)
    for name, status, elapsed in samples:
        grouped[name].append((status, elapsed))

    def summarize(items):
        values = sorted(item[1] for item in items)
        statuses = Counter(str(item[0]) for item in items)
        failures = sum(item[0] != 200 for item in items)
        return {"requests": len(items), "httpStatuses": dict(statuses), "unexpectedFailures": failures,
                "errorRate": round(failures/len(items), 6) if items else 1,
                "p50Ms": percentile(values, .5), "p95Ms": percentile(values, .95), "p99Ms": percentile(values, .99),
                "requestsPerSecond": round(len(items)/args.duration, 2)}

    # This scenario never deliberately generates 403/409/429, so all non-200s
    # are unexpected. Do not hide capacity or authorization failures as expected.
    report = {"overall": summarize([(s, ms) for _, s, ms in samples]),
              "endpoints": {name: summarize(items) for name, items in sorted(grouped.items())},
              "databaseConnectionSamples": connection_samples}
    report["thresholdsMet"] = bool(samples) and all(
        item["p95Ms"] < 2000 and item["errorRate"] < .01 for item in [report["overall"], *report["endpoints"].values()])
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--container", default="communityfix-mysql-1")
    parser.add_argument("--db-port", type=int, default=3307)
    parser.add_argument("--port", type=int, default=18080)
    parser.add_argument("--rows", type=int, default=100000)
    parser.add_argument("--users", type=int, default=100)
    parser.add_argument("--warmup", type=int, default=120)
    parser.add_argument("--duration", type=int, default=600)
    parser.add_argument("--think", type=float, default=.2, help="Seconds between requests per virtual user")
    args = parser.parse_args()
    if not (5 <= args.users <= 200 and args.users <= args.rows <= 1000000 and args.warmup >= 0 and args.duration > 0 and args.think >= 0):
        parser.error("Invalid load parameters")
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", args.port))
    jar = ROOT / "backend/target/communityfix-backend-0.1.0.jar"
    if not jar.is_file():
        raise RuntimeError("Package the backend jar first")
    source_files = sorted((ROOT / "backend/src/main").rglob("*"))
    newest_source = max(p.stat().st_mtime for p in source_files if p.is_file())
    if newest_source > jar.stat().st_mtime:
        raise RuntimeError("Backend jar is stale; package it again")
    run = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8]
    folder = ROOT / ".local/performance" / run
    folder.mkdir(parents=True)
    runner_source = Path(__file__).read_bytes()
    (folder / "runner.py").write_bytes(runner_source)
    db = backup.Database(args.container)
    schema, username, password = "cf_perf_" + uuid.uuid4().hex, "cf_perf_" + uuid.uuid4().hex[:20], secrets.token_urlsafe(32)
    report = {"run": run, "startedAt": datetime.now(timezone.utc).isoformat(), "configuration": vars(args),
              "environment": {"platform": platform.platform(), "logicalCpus": os.cpu_count(), "processor": os.environ.get("PROCESSOR_IDENTIFIER"), "javaHeap": "256m..1024m", "hikariMaximumPoolSize": 10, "topology": "HTTP loopback -> single Java backend -> Docker MySQL; generator shares host"},
              "jarSha256": hashlib.sha256(jar.read_bytes()).hexdigest(),
              "runnerSha256": hashlib.sha256(runner_source).hexdigest(),
              "temporaryResources": {"database": schema, "user": username},
              "sourceSha256": hashlib.sha256(b"".join(p.relative_to(ROOT).as_posix().encode() + p.read_bytes() for p in source_files if p.is_file())).hexdigest(),
              "syntheticData": "80% CLOSED / 20% SUBMITTED, one community, 30-day dates, 80% resident / 20% property users; no uploaded files or seeded history"}
    process, schema_created, user_created, failed = None, False, False, False
    try:
        db.query(None, f"CREATE DATABASE `{schema}` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci")
        schema_created = True
        db.query(None, f"CREATE USER '{username}'@'%' IDENTIFIED BY '{password}'")
        user_created = True
        db.query(None, f"GRANT ALL PRIVILEGES ON `{schema}`.* TO '{username}'@'%'")
        environment = {k: v for k, v in os.environ.items() if not k.startswith(("AI_", "WECHAT_", "SPRING_", "COMMUNITYFIX_", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"))}
        environment.update(DB_URL=f"jdbc:mysql://127.0.0.1:{args.db_port}/{schema}?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8", DB_USER=username, DB_PASSWORD=password,
                           JWT_SECRET=secrets.token_urlsafe(48), BOOTSTRAP_USERNAME="admin", BOOTSTRAP_PASSWORD=password,
                           DEMO_ENABLED="true", STORAGE_MODE="local", STORAGE_DIRECTORY=str(folder / "uploads"),
                           EUREKA_ENABLED="false", SERVER_PORT=str(args.port), SERVER_ADDRESS="127.0.0.1", API_DOCS_ENABLED="false")
        with (folder / "backend.log").open("wb") as log:
            process = subprocess.Popen(["java", "-Xms256m", "-Xmx1024m", "-jar", str(jar)], cwd=ROOT, env=environment,
                                       stdout=log, stderr=subprocess.STDOUT, creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
            client = Client(args.port)
            try:
                for _ in range(120):
                    status, _, _ = client.call("GET", "/actuator/health")
                    if status == 200:
                        # Health may turn UP just before ApplicationRunner commits.
                        login, _, _ = client.call("POST", "/api/auth/login", {"username": "resident", "password": password})
                        if login == 200:
                            break
                    if process.poll() is not None:
                        raise RuntimeError("Drill backend exited; inspect its local log")
                    time.sleep(1)
                else:
                    raise RuntimeError("Drill backend did not become ready")
            finally:
                client.close()
            print(json.dumps({"phase": "seed", "report": str(folder / "report.json")}), flush=True)
            details = seed(db, schema, args.rows, args.users)
            report["migrations"] = db.query(schema, "SELECT version,checksum,success FROM flyway_schema_history ORDER BY installed_rank")
            report["mysqlVersion"] = db.query(None, "SELECT VERSION()")[0][0]
            print(json.dumps({"phase": "load", "users": args.users, "rows": args.rows}), flush=True)
            report.update(measure(args, db, schema, password, details, folder))
            report["finalOrders"] = int(db.query(schema, "SELECT COUNT(*) FROM repair_order")[0][0])
            report["formalDuration"] = args.rows >= 100000 and args.users == 100 and args.warmup >= 120 and args.duration >= 600
            report["result"] = "PASS" if report["thresholdsMet"] else "FAIL"
    except BaseException as error:
        failed = True
        report["result"] = "ERROR"
        report["error"] = type(error).__name__
        raise
    finally:
        if process and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)
        try:
            if schema_created:
                db.query(None, f"DROP DATABASE `{schema}`")
            if user_created:
                db.query(None, f"DROP USER '{username}'@'%'")
            report["cleanup"] = "PASS"
        except Exception:
            report["cleanup"] = "FAIL"
            failed = True
        report["finishedAt"] = datetime.now(timezone.utc).isoformat()
        save(folder / "report.json", report)
        print(json.dumps({"report": str(folder / "report.json"), "result": report.get("result"), "cleanup": report["cleanup"]}), flush=True)
    return 1 if failed or report["result"] != "PASS" else 0


if __name__ == "__main__":
    raise SystemExit(main())
