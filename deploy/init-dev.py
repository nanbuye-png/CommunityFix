"""Generate local secrets; --upgrade fills missing middleware keys, preserving nonempty values. Never prints secrets."""
from pathlib import Path
import secrets
import argparse

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--upgrade',action='store_true',help='Fill missing Redis/RabbitMQ credentials without changing existing values')
args=parser.parse_args()

target = Path(__file__).resolve().parent / ".env"
if target.exists():
    if args.upgrade:
        lines=target.read_text(encoding='utf-8').splitlines()
        added=[]
        for key in ('REDIS_PASSWORD','RABBITMQ_PASSWORD'):
            indices=[i for i,line in enumerate(lines) if line.startswith(key+'=')]
            if indices and lines[indices[-1]].split('=',1)[1].strip().strip('\"\''): continue
            value=secrets.token_urlsafe(32)
            if indices: lines[indices[-1]]=key+'='+value
            else: lines.append(key+'='+value)
            added.append(key)
        if added: target.write_text('\n'.join(lines)+'\n',encoding='utf-8')
        print('Updated missing keys: '+(', '.join(added) or 'none')+'; existing nonempty values preserved')
    else: print("deploy/.env already exists; use --upgrade to add missing middleware credentials")
else:
    values = {
        "DB_PASSWORD": secrets.token_urlsafe(24),
        "REDIS_PASSWORD": secrets.token_urlsafe(32),
        "RABBITMQ_PASSWORD": secrets.token_urlsafe(32),
        "DB_ROOT_PASSWORD": secrets.token_urlsafe(24),
        "JWT_SECRET": secrets.token_urlsafe(48),
        "BOOTSTRAP_USERNAME": "admin",
        "BOOTSTRAP_PASSWORD": secrets.token_urlsafe(18),
        "MINIO_ACCESS_KEY": "communityfix-storage",
        "MINIO_SECRET_KEY": secrets.token_urlsafe(24),
        "DEMO_ENABLED": "true",
        "DEMO_SYNC_PASSWORDS": "true",
        "CORS_ORIGINS": "http://localhost:5173,http://localhost:5174,http://localhost:8088",
        "WEB_PORT": "8088",
    }
    target.write_text("\n".join(f"{key}={value}" for key, value in values.items()) + "\n", encoding="utf-8")
    print("Created deploy/.env. Read BOOTSTRAP_PASSWORD there for development accounts.")
