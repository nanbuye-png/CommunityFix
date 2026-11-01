"""Guard checks: no Docker or database needed."""
import importlib.util
import hashlib
import json
import os
from pathlib import Path
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('acceptance', Path(__file__).with_name('acceptance.py'))
acceptance = importlib.util.module_from_spec(spec)
spec.loader.exec_module(acceptance)


class AcceptanceGuards(unittest.TestCase):
    def test_online_project_and_path_traversal_are_rejected_before_docker(self):
        for name in ('communityfix', '../communityfix', 'cf-accept-../../live', 'cf-accept-ABCDEF123456'):
            with self.assertRaises(ValueError):
                acceptance.Run(name)

    def test_real_provider_and_demo_credentials_do_not_leak_into_children(self):
        with patch.dict(os.environ, {'WECHAT_APP_SECRET': 'real', 'AI_API_KEY': 'real',
                                    'DB_PASSWORD': 'real', 'DEMO_RESIDENT_PASSWORD': 'real',
                                    'COMPOSE_PROJECT_NAME': 'communityfix', 'CF_PROJECT': 'communityfix'}):
            actual = acceptance.clean_env()
            for key in ('WECHAT_APP_SECRET', 'AI_API_KEY', 'DB_PASSWORD', 'DEMO_RESIDENT_PASSWORD',
                        'COMPOSE_PROJECT_NAME', 'CF_PROJECT'):
                self.assertNotIn(key, actual)

    def test_missing_owner_and_changed_project_are_rejected(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(acceptance, 'RUNS', Path(folder)):
            name = 'cf-accept-012345abcdef'
            root = Path(folder) / name
            root.mkdir()
            (root / 'state.json').write_text(json.dumps(dict(owner='wrong', project=name)))
            with self.assertRaises(ValueError):
                acceptance.Run(name)
            (root / 'state.json').write_text(json.dumps(dict(owner='communityfix-acceptance-v1', project=name)))
            (root / 'config.env').write_text('CF_PROJECT=communityfix\n')
            with self.assertRaises(ValueError):
                acceptance.Run(name)

    def test_unsafe_object_keys_cannot_escape_backup_directory(self):
        import sys
        with patch.dict(sys.modules, {'acceptance': acceptance}):
            storage = acceptance.module('acceptance_storage', 'acceptance_storage.py')
        for key in ('../live', '/abs', 'a/b', '..', 'C:\\live', 'a\\b'):
            with self.assertRaises(ValueError):
                storage.safe_keys({key: {}})

    def test_s3_client_refuses_external_endpoints(self):
        s3 = acceptance.module('acceptance_s3', 'acceptance_s3.py')
        for endpoint in ('https://s3.amazonaws.com', 'http://example.com', 'http://127.0.0.1:9000/path'):
            with self.assertRaises(ValueError):
                s3.S3(endpoint, 'test', 'test')

    def test_corrupt_backup_refused_before_starting_restore(self):
        import sys
        with patch.dict(sys.modules, {'acceptance': acceptance}):
            storage = acceptance.module('acceptance_storage', 'acceptance_storage.py')
        for damaged in ('database.sql.gz', 'objects/image.png', 'web-admin/index.html', 'web-h5/index.html'):
            with self.subTest(damaged=damaged), tempfile.TemporaryDirectory() as directory:
                run = SimpleNamespace(folder=Path(directory), name='cf-accept-012345abcdef')
                folder = run.folder / 'backup-012345abcdef'
                for name in ('objects', 'web-admin', 'web-h5'):
                    (folder / name).mkdir(parents=True)
                for name in ('database.sql.gz', 'objects/image.png', 'web-admin/index.html', 'web-h5/index.html'):
                    (folder / name).write_bytes(b'original')
                digest = hashlib.sha256(b'original').hexdigest()
                manifest = dict(format=1, storage='minio', owner=run.name, dumpSha256=digest,
                    files={'image.png': dict(size=8, sha256=digest)},
                    static={key: {'index.html': digest} for key in ('admin', 'h5')})
                (folder / 'manifest.json').write_text(json.dumps(manifest))
                storage.validate_backup(run, folder)
                (folder / damaged).write_bytes(b'corrupt')
                with patch.object(storage, 'initialize') as initialize:
                    with self.assertRaises(ValueError):
                        storage.restore(run, folder)
                    initialize.assert_not_called()


if __name__ == '__main__':
    unittest.main()
