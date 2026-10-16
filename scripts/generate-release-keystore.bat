@echo off
setlocal enabledelayedexpansion

echo =======================================================
echo   1507DCamera - Tao Production Release Keystore
echo =======================================================
echo.
echo Luu y: Keystore va mat khau phai duoc bao mat tuyet doi.
echo Khong bao gio commit file .jks hoac keystore.properties vao Git.
echo.

set KEYSTORE_FILE=release.jks
set KEY_ALIAS=camera1507d_release

if exist "%KEYSTORE_FILE%" (
    echo [CANH BAO] File %KEYSTORE_FILE% da ton tai!
    echo Neu muon tao lai, hay doi ten hoac xoa file cu truoc.
    pause
    exit /b 1
)

echo Dang tao %KEYSTORE_FILE% voi alias '%KEY_ALIAS%' (RSA 4096-bit, 10.000 ngay)...
keytool -genkeypair -v -keystore "%KEYSTORE_FILE%" -alias "%KEY_ALIAS%" -keyalg RSA -keysize 4096 -validity 10000

if not exist "%KEYSTORE_FILE%" (
    echo [LOI] Khong the tao keystore. Kiem tra xem keytool (JDK) da duoc cai dat chua.
    pause
    exit /b 1
)

echo.
echo Tao thanh cong file: %KEYSTORE_FILE%
echo.
echo Dang tao chuoi Base64 de dua vao GitHub Actions Secrets...
certutil -encode "%KEYSTORE_FILE%" temp_base64.txt >nul
findstr /v /c:"CERTIFICATE" temp_base64.txt > release_keystore_base64.txt
del temp_base64.txt

echo.
echo [XONG]
echo 1. File %KEYSTORE_FILE%: Dat tai android/app/release.jks neu muon build release offline.
echo 2. File release_keystore_base64.txt: Copy toan bo noi dung lam gia tri cho Secret RELEASE_KEYSTORE_BASE64 tren GitHub.
echo.
pause
