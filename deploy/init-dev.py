"""Generate local secrets once. Never overwrites an existing .env or prints secrets."""
from pathlib import Path
import secrets

target = Path(__file__).resolve().parent / ".env"
if target.exists():
    print("deploy/.env already exists; left unchanged")
else:
    values = {
        "DB_PASSWORD": secrets.token_urlsafe(24),
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
