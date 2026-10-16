@echo off
chcp 65001 >nul
echo ========================================================
echo  ĐỒNG BỘ CODE BACKEND SANG PRODUCTION (D:\inspection-camera-prod)
echo ========================================================
python "D:\inspection-camera\deploy_to_prod.py"
pause
