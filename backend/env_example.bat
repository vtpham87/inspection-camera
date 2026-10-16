@echo off
REM Mau dat bien moi truong cho may tram PROD (chay 1 lan bang quyen Administrator, roi KHOI DONG LAI backend).
REM Sao chep thanh env_local.bat, dien gia tri that, KHONG commit.
setx /M PTCGDB_ACCESS_USER "ten_tai_khoan_access"
setx /M PTCGDB_ACCESS_PASSWORD "mat_khau_moi"
setx /M PTCGDB_API_KEY "chuoi_ngau_nhien_dai_32_ky_tu_tro_len"
REM Tuy chon:
REM setx /M PTCGDB_FIREBASE_AUTH "database_secret_hoac_token"
REM setx /M PTCGDB_ALLOWED_SAVE_ROOTS "Z:;D:"
REM setx /M PTCGDB_CORS_ORIGINS ""
