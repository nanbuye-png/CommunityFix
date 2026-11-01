"""Repackage a tested backend on the exact runtime of an owned local backup.

Useful when a registry is unavailable. Never fetches an alternative base image.
Run test-backend-isolated.py first; this packages with tests already completed.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
from acceptance import ROOT, RUNS, Run, save
from acceptance_storage import validate_backup


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run', required=True)
    parser.add_argument('--backup', type=Path, required=True)
    parser.add_argument('--output-dir', type=Path, default=RUNS)
    args = parser.parse_args()
    import acceptance
    acceptance.RUNS = args.output_dir.resolve()
    run = Run(args.run)
    _, manifest = validate_backup(run, args.backup)
    base = manifest['images']['backend']
    actual = subprocess.check_output(['docker', 'image', 'inspect', '--format', '{{.Id}}', base['tag']], text=True).strip()
    if actual != base['id']:
        raise ValueError('Backup runtime image changed')
    folder = run.folder / 'backend-rebuild'
    folder.mkdir(exist_ok=True)
    with (folder / 'package.log').open('wb') as log:
        subprocess.run(['mvn.cmd' if os.name == 'nt' else 'mvn', '-o', '-B',
            '-Dmaven.repo.local=' + str(ROOT / '.local/m2'), '-f', str(ROOT / 'backend/pom.xml'),
            'package', '-DskipTests'], check=True, stdout=log, stderr=subprocess.STDOUT)
    jar = ROOT / 'backend/target/communityfix-backend-0.1.0.jar'
    shutil.copyfile(jar, folder / 'app.jar')
    # Base is an already validated, locally present snapshot tag. No RUN or
    # install instructions, and the original unprivileged user is inherited.
    (folder / 'Dockerfile').write_text('ARG BASE_IMAGE\nFROM ${BASE_IMAGE}\nCOPY app.jar /app/app.jar\n', encoding='utf-8')
    tag = run.name + '-backend:acceptance'
    subprocess.run(['docker', 'build', '--network=none', '--build-arg', 'BASE_IMAGE=' + base['tag'],
                    '-t', tag, str(folder)], check=True)
    image = subprocess.check_output(['docker', 'image', 'inspect', '--format', '{{.Id}}', tag], text=True).strip()
    evidence = dict(baseImage=base, imageId=image, jarSha256=hashlib.sha256(jar.read_bytes()).hexdigest(),
        sourceSha256=hashlib.sha256(b''.join(p.relative_to(ROOT).as_posix().encode() + p.read_bytes()
            for p in sorted((ROOT / 'backend/src/main').rglob('*')) if p.is_file())).hexdigest())
    save(folder / 'evidence.json', evidence)
    run.record('backendRebuild', 'PASS', **evidence)


if __name__ == '__main__':
    main()
