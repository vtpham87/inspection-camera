import os
import shutil
import time
import urllib.request
import json
import sys

DEV_DIR = r"D:\inspection-camera\backend"
PROD_DIR = r"D:\inspection-camera-prod\backend"

PYTHON_FILES = [
    "config.py",
    "firebase_sync.py",
    "main.py",
    "photo_handler.py",
    "vehicle_service.py",
    "requirements.txt",
]


def deploy():
    print(f"Deploying backend from {DEV_DIR} to {PROD_DIR}...")
    for filename in PYTHON_FILES:
        src = os.path.join(DEV_DIR, filename)
        dst = os.path.join(PROD_DIR, filename)
        if os.path.exists(src):
            shutil.copy2(src, dst)
            print(f"  [OK] Copied {filename}")
        else:
            print(f"  [WARN] Not found: {filename}")

    # Do not overwrite photo_config.json if it already exists in PROD
    prod_config = os.path.join(PROD_DIR, "photo_config.json")
    if not os.path.exists(prod_config):
        shutil.copy2(os.path.join(DEV_DIR, "photo_config.json"), prod_config)
        print("  [OK] Initialized photo_config.json in PROD")
    else:
        print("  [SKIP] Kept existing photo_config.json in PROD")

    print("\nRestarting PROD backend (port 8095)...")
    restart_vbs = os.path.join(PROD_DIR, "restart_background.vbs")
    os.system(f'cscript //nologo "{restart_vbs}"')

    # Wait and check health
    print("Waiting for server to be healthy...")
    time.sleep(2)
    healthy = False
    for _ in range(10):
        try:
            req = urllib.request.Request("http://127.0.0.1:8095/api/health")
            with urllib.request.urlopen(req, timeout=1.5) as resp:
                if resp.status == 200:
                    data = json.loads(resp.read().decode("utf-8"))
                    print(f"  [SUCCESS] Server healthy: {data}")
                    healthy = True
                    break
        except Exception:
            time.sleep(1)

    if not healthy:
        print("  [ERROR] Server failed health check on port 8095!")
        sys.exit(1)
    print("\nDeployment completed successfully!")


if __name__ == "__main__":
    deploy()
