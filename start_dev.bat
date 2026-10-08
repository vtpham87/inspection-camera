@echo off
chcp 65001 >nul
echo ========================================================
echo  KHỞI ĐỘNG DEV BACKEND (PORT 8096, ĐỘC LẬP VỚI PROD)
echo ========================================================
set PORT=8096
set DISABLE_FIREBASE_SYNC=1
set PHOTO_CONFIG_PATH=D:\inspection-camera\backend\photo_config_dev.json

cd /d "D:\inspection-camera\backend"

if exist "venv\Scripts\python.exe" (
    venv\Scripts\python.exe main.py
) else (
    python main.py
)

pause
