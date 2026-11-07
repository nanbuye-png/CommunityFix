"""Evaluate real LLM conversations through an isolated backend and MySQL schema.

Only --run calls the configured supplier. Reports contain synthetic chat only.
"""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import time
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
QUESTIONS = [
    "hello",
    "你是谁？",
    "我喜欢散步和摄影，周末想放松一下，有什么简单的建议？",
    "结合我刚才说的两个爱好，帮我安排一个不花钱的下午。",
    "我们小区换灯泡一定免费吗？",
    "家里的灯坏了，平台上怎么报修？",
]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run", action="store_true")
    args = parser.parse_args()
    if not args.run:
        print(json.dumps(QUESTIONS, ensure_ascii=False))
        return
    spec = importlib.util.spec_from_file_location("acceptance", ROOT / "deploy/acceptance.py")
    acceptance = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(acceptance)
    cfg = {}
    allowed = {"AI_ENDPOINT", "AI_API_KEY", "AI_MODEL",
               "AI_SUPPORT_ENDPOINT", "AI_SUPPORT_API_KEY", "AI_SUPPORT_MODEL"}
    for line in (ROOT / "deploy/.env").read_text("utf-8-sig").splitlines():
        key, sep, value = line.partition("=")
        if sep and key in allowed:
            cfg[key] = value.strip()
    prefix = "AI_SUPPORT" if any(cfg.get(k) for k in allowed if k.startswith("AI_SUPPORT")) else "AI"
    if not all(cfg.get(prefix + suffix) for suffix in ("_ENDPOINT", "_API_KEY", "_MODEL")):
        raise RuntimeError("Incomplete model configuration")
    env = acceptance.clean_env()
    suffix = uuid.uuid4().hex
    folder = ROOT / ".local" / ("support-chat-" + suffix[:12])
    folder.mkdir()
    schema, user = "cf_chat_" + suffix, "cf_chat_" + suffix[:20]
    password = secrets.token_urlsafe(32)
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        port = sock.getsockname()[1]
    env.update(cfg)
    env.update(DB_URL=f"jdbc:mysql://127.0.0.1:3307/{schema}?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8",
               DB_USER=user, DB_PASSWORD=password, JWT_SECRET=secrets.token_urlsafe(48),
               BOOTSTRAP_USERNAME="admin", BOOTSTRAP_PASSWORD=password, DEMO_ENABLED="true",
               EUREKA_ENABLED="false", SERVER_ADDRESS="127.0.0.1", SERVER_PORT=str(port),
               STORAGE_MODE="local", STORAGE_DIRECTORY=str(folder / "uploads"))
    db = acceptance.module("backup", "backup-local.py").Database("communityfix-mysql-1")
    process = None
    created = granted = False
    report = {"result": "RUNNING", "responses": [], "cleanup": "PENDING"}

    def save():
        acceptance.save(folder / "report.json", report)

    def api(path, token=None, body=None):
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + token
        req = urllib.request.Request(f"http://127.0.0.1:{port}/api" + path, headers=headers,
            data=json.dumps(body).encode() if body is not None else None)
        with urllib.request.urlopen(req, timeout=20) as response:
            return json.load(response)["data"]

    try:
        with (folder / "build.log").open("wb") as log:
            subprocess.run(["mvn.cmd" if os.name == "nt" else "mvn", "-o", "-B",
                "-Dmaven.repo.local=" + str(ROOT / ".local/m2"), "-f", "backend/pom.xml",
                "-DskipTests", "package"], cwd=ROOT, env=acceptance.clean_env(),
                stdout=log, stderr=subprocess.STDOUT, check=True, timeout=180)
        db.query(None, f"CREATE DATABASE `{schema}` CHARACTER SET utf8mb4")
        created = True
        db.query(None, f"CREATE USER '{user}'@'%' IDENTIFIED BY '{password}'")
        granted = True
        db.query(None, f"GRANT ALL PRIVILEGES ON `{schema}`.* TO '{user}'@'%'")
        with (folder / "backend.log").open("wb") as log:
            process = subprocess.Popen(["java", "-Xmx512m", "-jar",
                str(ROOT / "backend/target/communityfix-backend-0.1.0.jar")],
                cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT,
                creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
            for _ in range(120):
                if process.poll() is not None:
                    raise RuntimeError("Backend exited")
                try:
                    with urllib.request.urlopen(f"http://127.0.0.1:{port}/actuator/health", timeout=1):
                        break
                except OSError:
                    time.sleep(.5)
            else:
                raise RuntimeError("Backend startup timed out")
            token = api("/auth/login", body={"username": "resident", "password": password})["token"]
            session = api("/support/sessions", token, {"role": "RESIDENT"})
            base = "/support/sessions/" + str(session["id"])
            last = 0
            for question in QUESTIONS:
                time.sleep(max(0, 13 - (time.monotonic() - last)))
                last = time.monotonic()
                message = api(base + "/messages", token, {"clientId": uuid.uuid4().hex, "content": question})
                after = int(message["seq"])
                for _ in range(4):
                    events = api(base + "/events?after=" + str(after), token)
                    replies = [m for m in events["messages"] if m["senderType"] == "AI"]
                    if replies:
                        break
                    after = int(events["cursor"])
                else:
                    raise RuntimeError("No reply received")
                reply = replies[0]
                report["responses"].append({"question": question, "answer": reply["content"],
                    "metadata": reply.get("metadata"), "elapsed_ms": round((time.monotonic()-last)*1000)})
                save()
                print(json.dumps(report["responses"][-1], ensure_ascii=False), flush=True)
            report["result"] = "PASS" if all(r["metadata"]["source"] == "AI" for r in report["responses"]) else "FAIL"
            # Content relevance is reviewed separately; success here proves provider-backed delivery.
    except Exception as error:
        report["result"] = "FAIL"
        # Exception text can include URLs; record only its type.
        report["failure_type"] = type(error).__name__
        raise
    finally:
        if process and process.poll() is None:
            process.terminate()
            process.wait(timeout=20)
        if granted:
            db.query(None, f"DROP USER '{user}'@'%'")
        if created:
            db.query(None, f"DROP DATABASE `{schema}`")
        report["cleanup"] = "PASS"
        save()
        print(json.dumps({"report": str(folder / "report.json"), "result": report["result"],
                          "cleanup": report["cleanup"]}), flush=True)
    if report["result"] != "PASS":
        raise SystemExit(1)


if __name__ == "__main__":
    main()
