import subprocess
import os

res = subprocess.run("netstat -ano", capture_output=True, text=True, shell=True)
found = False
for line in res.stdout.splitlines():
    if ":8096" in line and "LISTENING" in line:
        parts = line.strip().split()
        pid = parts[-1]
        print(f"Stopping Dev backend process (PID {pid})...")
        os.system(f"taskkill /F /PID {pid}")
        found = True

if not found:
    print("Dev backend (port 8096) is not running.")
