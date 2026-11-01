"""Back up the local MySQL + private-file demo; restore only into a new drill schema.

Uses Docker's bundled MySQL tools. Credentials stay inside the MySQL container.
All application writers and cleanup jobs must be stopped during backup creation.
This tool intentionally does not restore into an existing/application database.
"""
import argparse
from datetime import datetime, timezone
import gzip
import hashlib
import json
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import sys
import tempfile
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
IDENTIFIER = re.compile(r"[a-zA-Z][a-zA-Z0-9_]{0,63}\Z")
OBJECT_KEY = re.compile(r"[a-zA-Z0-9][a-zA-Z0-9.-]*\Z")


def identifier(value):
    if not IDENTIFIER.fullmatch(value):
        raise ValueError("Invalid database/table identifier")
    return value


def digest(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


class Database:
    def __init__(self, container):
        if not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9_.-]*", container):
            raise ValueError("Invalid container name")
        self.container = container

    def execute(self, program, args, *, source=None, destination=None, sql=None, credentials=None):
        # Constant shell script; all parameters are separate argv entries, never interpolated.
        auth_script = 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec "$@"'
        username = "root"
        with tempfile.TemporaryFile() as private_input:
            if credentials:
                username, password = credentials
                auth_script = 'IFS= read -r MYSQL_PWD; export MYSQL_PWD; exec "$@"'
                private_input.write((password + "\n").encode("utf-8"))
            if sql is not None:
                private_input.write(sql.encode("utf-8"))
            elif source:
                shutil.copyfileobj(source, private_input)
            private_input.seek(0)
            command = ["docker", "exec", "-i", self.container, "sh", "-c", auth_script, "cf-backup", program,
                       "--user=" + username, "--default-character-set=utf8mb4", *args]
            result = subprocess.run(command, stdin=private_input, stdout=destination or subprocess.PIPE,
                                    stderr=subprocess.PIPE, timeout=300)
        if result.returncode:
            # MySQL stderr may contain SQL or resident data. Do not print it.
            raise RuntimeError(f"{program} failed (exit {result.returncode}); check container access and MySQL state")
        return result.stdout

    def query(self, schema, sql):
        args = ["--batch", "--raw", "--skip-column-names"]
        if schema:
            args.append(identifier(schema))
        result = self.execute("mysql", args, sql=sql).decode("utf-8").strip()
        return [line.split("\t") for line in result.splitlines()] if result else []

    def dump(self, schema, path, *, data_only=False):
        args = ["--single-transaction", "--quick", "--no-tablespaces", "--set-gtid-purged=OFF",
                "--hex-blob", "--tz-utc", "--skip-comments", "--skip-dump-date", "--order-by-primary"]
        if data_only:
            args += ["--no-create-info", "--skip-triggers", "--skip-add-locks", "--skip-disable-keys"]
        args.append(identifier(schema))  # Deliberately omit --databases: dump has no USE/CREATE DATABASE.
        with path.open("wb") as output:
            self.execute("mysqldump", args, destination=output)

    def fingerprint(self, schema):
        with tempfile.TemporaryDirectory(prefix="cf-fingerprint-") as temporary:
            path = Path(temporary) / "rows.sql"
            self.dump(schema, path, data_only=True)
            return digest(path)

    def counts(self, schema):
        names = [identifier(row[0]) for row in self.query(schema, "SHOW TABLES")]
        return {name: int(self.query(schema, f"SELECT COUNT(*) FROM `{name}`")[0][0]) for name in names}

    def references(self, schema):
        return [{"key": row[0], "size": int(row[1])} for row in self.query(
            schema, "SELECT object_key,size_bytes FROM file_asset WHERE status='ATTACHED' ORDER BY object_key")]


def inventory(directory):
    result = {}
    if directory.is_symlink() or not directory.is_dir():
        raise ValueError("Private-file directory must be a real directory")
    for path in sorted(directory.iterdir()):
        if path.is_symlink() or not path.is_file() or not OBJECT_KEY.fullmatch(path.name):
            raise ValueError("Unexpected private-file entry; symbolic links and nested paths are not allowed")
        result[path.name] = {"size": path.stat().st_size, "sha256": digest(path)}
    return result


def verify_references(references, files):
    for item in references:
        if item["key"] not in files or files[item["key"]]["size"] != item["size"]:
            raise RuntimeError("An attached database image is missing or has an unexpected size")


def backup(args, db):
    if not args.writers_stopped:
        raise ValueError("Stop ALL application writers and schedulers, then pass --writers-stopped")
    schema = identifier(args.database)
    source = args.files.resolve(strict=True)
    parent = args.output.resolve()
    if source == parent or source in parent.parents:
        raise ValueError("Backup output cannot be inside the private-file directory")
    started = time.monotonic()
    before_files = inventory(source)
    references = db.references(schema)
    verify_references(references, before_files)
    before_data = db.fingerprint(schema)
    counts = db.counts(schema)
    name = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8]
    folder = parent / name
    folder.mkdir(parents=True, exist_ok=False)
    # No manifest is published until the complete snapshot and consistency checks succeed.
    with tempfile.TemporaryDirectory(prefix="cf-dump-", dir=folder) as temporary:
        raw = Path(temporary) / "database.sql"
        db.dump(schema, raw)
        with raw.open("rb") as input_file, gzip.open(folder / "database.sql.gz", "wb") as output_file:
            shutil.copyfileobj(input_file, output_file)
    objects = folder / "files"
    objects.mkdir()
    for name in before_files:
        shutil.copyfile(source / name, objects / name)
    if inventory(source) != before_files or inventory(objects) != before_files or db.fingerprint(schema) != before_data:
        raise RuntimeError("Source changed during backup; no valid manifest was published. Stop all writers and retry")
    migrations = db.query(schema, "SELECT version,checksum,success FROM flyway_schema_history ORDER BY installed_rank")
    manifest = {"format": 1, "createdAt": datetime.now(timezone.utc).isoformat(), "sourceDatabase": schema,
                "mysqlVersion": db.query(None, "SELECT VERSION()")[0][0], "storage": "local", "writersStopped": True,
                "dumpSha256": digest(folder / "database.sql.gz"), "dataSha256": before_data,
                "tableCounts": counts, "migrations": migrations, "files": before_files,
                "attachedReferences": references, "durationSeconds": round(time.monotonic() - started, 3)}
    write_json(folder / "manifest.json", manifest)
    print(json.dumps({"backup": str(folder), "tables": len(counts), "files": len(before_files),
                      "durationSeconds": manifest["durationSeconds"]}))


def restore_drill(args, db):
    started = time.monotonic()
    folder = args.backup.resolve(strict=True)
    manifest = json.loads((folder / "manifest.json").read_text(encoding="utf-8"))
    if manifest.get("format") != 1 or manifest.get("storage") != "local":
        raise ValueError("Unsupported backup format/storage")
    dump = folder / "database.sql.gz"
    if dump.is_symlink() or digest(dump) != manifest["dumpSha256"]:
        raise ValueError("Database dump integrity check failed")
    if inventory(folder / "files") != manifest["files"]:
        raise ValueError("Private-file integrity check failed")
    verify_references(manifest["attachedReferences"], manifest["files"])
    # No caller-supplied database target: only a newly generated drill schema can be mutated/dropped.
    schema = "cf_restore_" + uuid.uuid4().hex
    username = "cf_drill_" + uuid.uuid4().hex[:20]
    password = secrets.token_urlsafe(32)
    created = False
    user_created = False
    try:
        db.query(None, f"CREATE DATABASE `{schema}` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci")
        created = True
        db.query(None, f"CREATE USER '{username}'@'localhost' IDENTIFIED BY '{password}'")
        user_created = True
        # The SQL import cannot access the live schema, even if a backup was tampered with.
        db.query(None, f"GRANT ALL PRIVILEGES ON `{schema}`.* TO '{username}'@'localhost'")
        with tempfile.TemporaryDirectory(prefix="cf-restore-") as temporary:
            raw = Path(temporary) / "database.sql"
            with gzip.open(dump, "rb") as input_file, raw.open("wb") as output_file:
                shutil.copyfileobj(input_file, output_file)
            # Reject schema switches even for a supplied manifest/dump. No routines or triggers in this project.
            with raw.open("rb") as input_file:
                for line in input_file:
                    if re.match(rb"\s*(USE\s|(?:CREATE|DROP|ALTER)\s+DATABASE\s)", line, re.IGNORECASE):
                        raise ValueError("Dump contains a database switch; refusing restore")
            with raw.open("rb") as input_file:
                db.execute("mysql", ["--binary-mode", schema], source=input_file, credentials=(username, password))
            counts = db.counts(schema)
            if counts != manifest["tableCounts"] or db.fingerprint(schema) != manifest["dataSha256"]:
                raise RuntimeError("Restored table counts or row fingerprint differ from backup")
            if db.references(schema) != manifest["attachedReferences"]:
                raise RuntimeError("Restored attachment references differ from backup")
            restored_files = Path(temporary) / "files"
            shutil.copytree(folder / "files", restored_files)
            if inventory(restored_files) != manifest["files"]:
                raise RuntimeError("Restored private-file bytes differ from backup")
        report = {"verifiedAt": datetime.now(timezone.utc).isoformat(), "result": "PASS", "restoreSchema": schema,
                  "tables": len(counts), "files": len(manifest["files"]),
                  "attachedReferences": len(manifest["attachedReferences"]),
                  "durationSeconds": round(time.monotonic() - started, 3),
                  "checks": ["dump SHA-256", "all table counts", "all row fingerprint", "attachment references", "all file SHA-256"],
                  "scope": "Isolated local restore; no application scheduler or external message delivery started"}
    finally:
        if user_created:
            db.query(None, f"DROP USER '{username}'@'localhost'")
        if created:
            if not re.fullmatch(r"cf_restore_[0-9a-f]{32}", schema):
                raise RuntimeError("Unexpected drill database; refuse cleanup")
            db.query(None, f"DROP DATABASE `{schema}`")
    report["temporarySchemaRemoved"] = True
    write_json(folder / "restore-report.json", report)
    print(json.dumps(report))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--container", default="communityfix-mysql-1")
    commands = parser.add_subparsers(dest="command", required=True)
    create = commands.add_parser("backup")
    create.add_argument("--database", default="communityfix")
    create.add_argument("--files", type=Path, default=ROOT / ".local" / "uploads")
    create.add_argument("--output", type=Path, default=ROOT / ".local" / "backups")
    create.add_argument("--writers-stopped", action="store_true")
    drill = commands.add_parser("restore-drill")
    drill.add_argument("backup", type=Path)
    args = parser.parse_args()
    try:
        db = Database(args.container)
        (backup if args.command == "backup" else restore_drill)(args, db)
    except (ValueError, RuntimeError, OSError, subprocess.SubprocessError, KeyError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
