"""Regression checks for upgrading an existing developer environment without secret rotation."""
import pathlib
import shutil
import subprocess
import sys
import tempfile
import unittest

class InitDevTest(unittest.TestCase):
    def test_upgrade_preserves_existing_values_and_is_idempotent(self):
        with tempfile.TemporaryDirectory() as folder:
            root = pathlib.Path(folder)
            script = root / 'init-dev.py'
            shutil.copyfile(pathlib.Path(__file__).with_name('init-dev.py'), script)
            target = root / '.env'
            original = 'DB_PASSWORD=synthetic-db\nREDIS_PASSWORD=synthetic-redis\nRABBITMQ_PASSWORD=""\n# keep this comment\n'
            target.write_text(original, encoding='utf-8')
            subprocess.run([sys.executable,str(script)],check=True,capture_output=True)
            self.assertEqual(original,target.read_text(encoding='utf-8'))
            subprocess.run([sys.executable,str(script),'--upgrade'],check=True,capture_output=True)
            updated = target.read_text(encoding='utf-8')
            self.assertIn('DB_PASSWORD=synthetic-db\nREDIS_PASSWORD=synthetic-redis\n',updated)
            self.assertNotIn('RABBITMQ_PASSWORD=""',updated)
            self.assertIn('# keep this comment',updated)
            subprocess.run([sys.executable,str(script),'--upgrade'],check=True,capture_output=True)
            self.assertEqual(updated,target.read_text(encoding='utf-8'))

if __name__ == '__main__': unittest.main()
