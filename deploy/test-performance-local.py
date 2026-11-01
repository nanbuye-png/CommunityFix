"""Safety checks for the disposable drill; no Docker or real database access."""
import contextlib
import importlib.util
import io
import os
from pathlib import Path
import socket
import sys
import tempfile
import time
from types import SimpleNamespace
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("performance_local", Path(__file__).with_name("performance-local.py"))
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class PerformanceSafetyTest(unittest.TestCase):
    def test_invalid_parameters_never_access_database(self):
        with patch.object(sys, "argv", ["performance-local.py", "--users", "1"]), \
             patch.object(runner.backup, "Database") as database, contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit) as error:
                runner.main()
            self.assertEqual(2, error.exception.code)
            database.assert_not_called()

    def test_occupied_port_never_accesses_database(self):
        with socket.socket() as occupied:
            occupied.bind(("127.0.0.1", 0))
            port = occupied.getsockname()[1]
            with patch.object(sys, "argv", ["performance-local.py", "--port", str(port)]), \
                 patch.object(runner.backup, "Database") as database:
                with self.assertRaises(OSError):
                    runner.main()
                database.assert_not_called()

    def test_failed_grant_cleans_up_already_created_user_and_schema(self):
        queries = []

        class Database:
            def __init__(self, container):
                pass

            def query(self, schema, sql):
                queries.append(sql)
                if sql.startswith("GRANT"):
                    raise RuntimeError("Synthetic grant failure")
                return []

        with tempfile.TemporaryDirectory(prefix="cf-perf-safety-") as temporary, socket.socket() as probe:
            root = Path(temporary)
            source = root / "backend/src/main/source.txt"
            source.parent.mkdir(parents=True)
            source.write_text("synthetic")
            os.utime(source, (1, 1))
            jar = root / "backend/target/communityfix-backend-0.1.0.jar"
            jar.parent.mkdir(parents=True)
            jar.write_bytes(b"synthetic")
            probe.bind(("127.0.0.1", 0))
            port = probe.getsockname()[1]
            probe.close()
            with patch.object(runner, "ROOT", root), patch.object(runner.backup, "Database", Database), \
                 patch.object(runner.platform, "platform", return_value="synthetic"), \
                 patch.object(sys, "argv", ["performance-local.py", "--port", str(port)]), \
                 patch.object(runner.subprocess, "Popen") as process, contextlib.redirect_stdout(io.StringIO()):
                with self.assertRaisesRegex(RuntimeError, "Synthetic grant failure"):
                    runner.main()
                process.assert_not_called()
            self.assertEqual(5, len(queries))
            self.assertTrue(queries[0].startswith("CREATE DATABASE `cf_perf_"))
            self.assertTrue(queries[1].startswith("CREATE USER 'cf_perf_"))
            self.assertTrue(queries[3].startswith("DROP DATABASE `cf_perf_"))
            self.assertTrue(queries[4].startswith("DROP USER 'cf_perf_"))

    def test_monitor_failure_stops_workers_before_executor_waits(self):
        class Client:
            def __init__(self, port):
                pass

            def call(self, method, path, body=None):
                return 200, {"token": "synthetic", "records": [], "total": 1}, 1

            def close(self):
                pass

        class Database:
            def query(self, schema, sql):
                raise RuntimeError("Synthetic monitoring failure")

        args = SimpleNamespace(port=1, users=5, warmup=0, duration=3, think=.01)
        started = time.perf_counter()
        with patch.object(runner, "Client", Client):
            with self.assertRaisesRegex(RuntimeError, "Synthetic monitoring failure"):
                runner.measure(args, Database(), "synthetic", "synthetic", {10001: 1, 10002: 2, 10003: 3, 10004: 4}, Path("unused"))
        self.assertLess(time.perf_counter() - started, 1.5)


if __name__ == "__main__":
    unittest.main()
