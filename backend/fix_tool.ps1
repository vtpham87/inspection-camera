# ==============================================================================
# HỆ THỐNG ĐĂNG KIỂM 15-07D - BỘ CÔNG CỤ TỰ ĐỘNG KHẮC PHỤC SỰ CỐ PHẦN MỀM
# File: fix_tool.ps1
# ==============================================================================

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

function Show-Header {
    Clear-Host
    Write-Host "================================================================================" -ForegroundColor Cyan
    Write-Host "         TRUNG TÂM ĐĂNG KIỂM XE CƠ GIỚI 15-07D - HẢI PHÒNG" -ForegroundColor White
    Write-Host "             BỘ CÔNG CỤ TỰ ĐỘNG KHẮC PHỤC SỰ CỐ PHẦN MỀM" -ForegroundColor Yellow
    Write-Host "          (Khắc phục lỗi mất kết nối ổ Z, treo Server, mất danh sách xe)" -ForegroundColor Gray
    Write-Host "================================================================================" -ForegroundColor Cyan
    Write-Host ""
}

function Get-LocalIP {
    try {
        $ip = (Get-NetIPAddress -AddressFamily IPv4 | Where-Object { 
            $_.IPAddress -like "192.168.*" -or $_.IPAddress -like "100.*"
        } | Select-Object -First 1).IPAddress
        if ($ip) { return $ip }
    } catch {}
    return "192.168.193.11"
}

function Fix-AllIssues {
    Write-Host "--- [ BẮT ĐẦU QUÉT VÀ TỰ ĐỘNG SỬA LỖI ] ---" -ForegroundColor Yellow
    Write-Host ""

    $statusZ = "CHƯA RÕ"
    $statusServer = "CHƯA RÕ"
    $statusSync = "CHƯA RÕ"
    $status8080 = "CHƯA RÕ"
    $vehicleTotal = 0
    $vehicleWaiting = 0

    # 1. KIỂM TRA MẠNG LAN VÀ MÁY CHỦ T1507
    Write-Host "[1/5] Kiểm tra kết nối mạng LAN tới máy chủ T1507 (192.168.193.2)..." -NoNewline
    $pingServer = Test-Connection -ComputerName "192.168.193.2" -Count 1 -Quiet -ErrorAction SilentlyContinue
    if ($pingServer) {
        Write-Host " [ OK ]" -ForegroundColor Green
    } else {
        Write-Host " [ CẢNH BÁO: Mất phản hồi ping 192.168.193.2 ]" -ForegroundColor Yellow
    }

    # 2. KIỂM TRA & KẾT NỐI LẠI Ổ ĐĨA MẠNG Z:
    Write-Host "[2/5] Kiểm tra ổ đĩa mạng Z: và file CSDL Access PT90..." -NoNewline
    $accessPath = "Z:\DataPTCGDB\PT90_1507D.mdb"
    if (-not (Test-Path $accessPath)) {
        Write-Host " [ ĐANG SỬA ]" -ForegroundColor Yellow
        Write-Host "      -> Đang kích hoạt kết nối lại ổ đĩa mạng Z:..." -ForegroundColor Gray
        Start-Process -FilePath "cmd.exe" -ArgumentList "/c net use Z: \\T1507\Data /persistent:yes" -Wait -WindowStyle Hidden
        Start-Sleep -Seconds 2
    }

    if (Test-Path $accessPath) {
        Write-Host " [ OK: Đã kết nối ổ Z: ]" -ForegroundColor Green
        $statusZ = "KẾT NỐI TỐT (Z:\DataPTCGDB\PT90_1507D.mdb)"
    } else {
        Write-Host " [ LỖI: Không kết nối được ổ Z: ]" -ForegroundColor Red
        Write-Host "      Vui lòng kiểm tra dây mạng LAN hoặc máy chủ T1507." -ForegroundColor Red
        $statusZ = "LỖI KẾT NỐI (Kiểm tra dây mạng)"
    }

    # Kiểm tra thư mục ảnh
    $photoFolder = "Z:\Anh Phuong Tien"
    if (Test-Path $photoFolder) {
        Write-Host "      -> Thư mục lưu ảnh kiểm định sẵn sàng: $photoFolder" -ForegroundColor Gray
    }

    # 3. KIỂM TRA DỊCH VỤ PHOTO SERVER (PORT 8095)
    Write-Host "[3/5] Kiểm tra dịch vụ Photo Server 1507D (Cổng 8095)..." -NoNewline
    $serverHealthy = $false
    try {
        $resp = Invoke-RestMethod -Uri "http://127.0.0.1:8095/api/health" -TimeoutSec 3 -ErrorAction Stop
        if ($resp.ok -eq $true) { $serverHealthy = $true }
    } catch {}

    if (-not $serverHealthy) {
        Write-Host " [ ĐANG KHỞI ĐỘNG LẠI ]" -ForegroundColor Yellow
        Write-Host "      -> Đang khởi động lại dịch vụ Photo Server..." -ForegroundColor Gray
        $restartVbs = "D:\inspection-camera-prod\backend\restart_background.vbs"
        if (Test-Path $restartVbs) {
            Start-Process -FilePath "cscript.exe" -ArgumentList "//nologo `"$restartVbs`"" -Wait -WindowStyle Hidden
        } else {
            $startVbs = "D:\inspection-camera-prod\backend\start_background.vbs"
            Start-Process -FilePath "cscript.exe" -ArgumentList "//nologo `"$startVbs`"" -Wait -WindowStyle Hidden
        }
        Start-Sleep -Seconds 3

        # Kiểm tra lại
        try {
            $resp2 = Invoke-RestMethod -Uri "http://127.0.0.1:8095/api/health" -TimeoutSec 3 -ErrorAction Stop
            if ($resp2.ok -eq $true) { $serverHealthy = $true }
        } catch {}
    }

    if ($serverHealthy) {
        Write-Host " [ OK: Dịch vụ đang hoạt động ]" -ForegroundColor Green
        $statusServer = "HOẠT ĐỘNG BÌNH THƯỜNG (Cổng 8095)"
    } else {
        Write-Host " [ LỖI: Không khởi động được Server ]" -ForegroundColor Red
        $statusServer = "KHÔNG PHẢN HỒI (Vui lòng kiểm tra lại Python)"
    }

    # 4. ÉP ĐỒNG BỘ DANH SÁCH XE LÊN 4 CỤM FIREBASE
    Write-Host "[4/5] Ép đồng bộ danh sách xe lên điện thoại (4 cụm Firebase)..." -NoNewline
    $syncSuccess = $false
    try {
        $syncResp = Invoke-RestMethod -Uri "http://127.0.0.1:8095/api/sync-vehicles-now" -Method Post -TimeoutSec 5 -ErrorAction Stop
        if ($syncResp.ok -eq $true) { $syncSuccess = $true }
    } catch {}

    try {
        $vList = Invoke-RestMethod -Uri "http://127.0.0.1:8095/api/vehicles/today?waiting_only=false" -TimeoutSec 5 -ErrorAction Stop
        $vehicleTotal = $vList.Count
        $vehicleWaiting = ($vList | Where-Object { -not $_.is_completed -and -not $_.sotem -and $_.result -ne 0 }).Count
    } catch {}

    if ($syncSuccess) {
        Write-Host " [ OK: Đã đồng bộ thành công ]" -ForegroundColor Green
        Write-Host "      -> Tổng số: $vehicleTotal xe | Đang chờ chụp/kiểm định: $vehicleWaiting xe" -ForegroundColor Gray
        $statusSync = "ĐÃ ĐỒNG BỘ ($vehicleTotal xe hôm nay, $vehicleWaiting xe đang chờ)"
    } else {
        Write-Host " [ CẢNH BÁO: Chưa đồng bộ được ]" -ForegroundColor Yellow
        $statusSync = "CHƯA HOÀN TẤT"
    }

    # 5. KIỂM TRA CỔNG TRA CỨU PTCGDB ONLINE (PORT 8080)
    Write-Host "[5/5] Kiểm tra cổng tra cứu PTCGDB Online (Cổng 8080)..." -NoNewline
    $ptcgdbHealthy = $false
    try {
        $resp8080 = Invoke-WebRequest -Uri "http://127.0.0.1:8080/" -TimeoutSec 2 -UseBasicParsing -ErrorAction Stop
        if ($resp8080.StatusCode -eq 200) { $ptcgdbHealthy = $true }
    } catch {}

    if (-not $ptcgdbHealthy) {
        Write-Host " [ ĐANG BẬT ]" -ForegroundColor Yellow
        $ptcgdbVbs = "C:\PTCGDB_Online\start_background.vbs"
        if (Test-Path $ptcgdbVbs) {
            Start-Process -FilePath "cscript.exe" -ArgumentList "//nologo `"$ptcgdbVbs`"" -Wait -WindowStyle Hidden
            Start-Sleep -Seconds 2
        }
    }

    try {
        $resp8080b = Invoke-WebRequest -Uri "http://127.0.0.1:8080/" -TimeoutSec 2 -UseBasicParsing -ErrorAction Stop
        if ($resp8080b.StatusCode -eq 200) { $ptcgdbHealthy = $true }
    } catch {}

    if ($ptcgdbHealthy) {
        Write-Host " [ OK: Hoạt động bình thường ]" -ForegroundColor Green
        $status8080 = "HOẠT ĐỘNG BÌNH THƯỜNG (Cổng 8080)"
    } else {
        Write-Host " [ CẢNH BÁO ]" -ForegroundColor Yellow
        $status8080 = "CHƯA CHẠY HOẶC ĐANG TẮT"
    }

    # HIỂN THỊ BẢNG TRẠNG THÁI TỔNG HỢP
    Write-Host ""
    Write-Host "================================================================================" -ForegroundColor White
    Write-Host "                     KẾT QUẢ KIỂM TRA VÀ TRẠNG THÁI" -ForegroundColor Yellow
    Write-Host "================================================================================" -ForegroundColor White
    $localIp = Get-LocalIP
    Write-Host ("  * IP máy tính trạm LAN:    " + $localIp) -ForegroundColor Cyan
    Write-Host ("  * Ổ đĩa mạng Z:            " + $statusZ) -ForegroundColor $(if ($statusZ -like "*KẾT NỐI TỐT*") { "Green" } else { "Red" })
    Write-Host ("  * Dịch vụ Camera (8095):   " + $statusServer) -ForegroundColor $(if ($statusServer -like "*HOẠT ĐỘNG*") { "Green" } else { "Red" })
    Write-Host ("  * Đồng bộ lên điện thoại:  " + $statusSync) -ForegroundColor $(if ($statusSync -like "*ĐÃ ĐỒNG BỘ*") { "Green" } else { "Yellow" })
    Write-Host ("  * Cổng tra cứu PTCGDB:     " + $status8080) -ForegroundColor $(if ($status8080 -like "*HOẠT ĐỘNG*") { "Green" } else { "Yellow" })
    Write-Host "================================================================================" -ForegroundColor White
    Write-Host ""
}

function Restart-CameraServiceOnly {
    Write-Host ""
    Write-Host "Đang khởi động lại toàn bộ dịch vụ Camera & Đồng bộ..." -ForegroundColor Yellow
    $restartVbs = "D:\inspection-camera-prod\backend\restart_background.vbs"
    Start-Process -FilePath "cscript.exe" -ArgumentList "//nologo `"$restartVbs`"" -Wait -WindowStyle Hidden
    Start-Sleep -Seconds 3

    try {
        $syncResp = Invoke-RestMethod -Uri "http://127.0.0.1:8095/api/sync-vehicles-now" -Method Post -TimeoutSec 5 -ErrorAction Stop
        Write-Host "✅ Dịch vụ đã khởi động lại và đồng bộ thành công!" -ForegroundColor Green
    } catch {
        Write-Host "❌ Khởi động lại xong nhưng chưa đồng bộ được: $($_.Exception.Message)" -ForegroundColor Red
    }
    Write-Host ""
    Write-Host "Nhấn phím bất kỳ để quay lại menu..." -ForegroundColor Gray
    try { $null = $Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown") } catch {}
}

function Show-RecentLogs {
    Write-Host ""
    Write-Host "--- [ 25 DÒNG NHẬT KÝ MỚI NHẤT (server.log) ] ---" -ForegroundColor Yellow
    $logPath = "D:\inspection-camera-prod\backend\server.log"
    if (Test-Path $logPath) {
        Get-Content -Path $logPath -Tail 25 -Encoding UTF8
    } else {
        Write-Host "Chưa có file nhật ký tại $logPath" -ForegroundColor Gray
    }
    Write-Host ""
    Write-Host "Nhấn phím bất kỳ để quay lại menu..." -ForegroundColor Gray
    try { $null = $Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown") } catch {}
}

# --- VÒNG LẶP CHÍNH ---
Show-Header
Fix-AllIssues

while ($true) {
    Write-Host "LỰA CHỌN THAO TÁC:" -ForegroundColor White
    Write-Host "  [1] Tự động quét & Sửa lại toàn bộ lỗi (Khuyên dùng)" -ForegroundColor Green
    Write-Host "  [2] Khởi động lại dịch vụ Camera & Ép đồng bộ Firebase ngay" -ForegroundColor Cyan
    Write-Host "  [3] Mở trang Cấu hình Camera trên trình duyệt web" -ForegroundColor Yellow
    Write-Host "  [4] Xem 25 dòng nhật ký hoạt động mới nhất (server.log)" -ForegroundColor Gray
    Write-Host "  [5] Mở thư mục ảnh kiểm định trên ổ Z (Z:\Anh Phuong Tien)" -ForegroundColor Gray
    Write-Host "  [0] Thoát (hoặc nhấn Enter)" -ForegroundColor White
    Write-Host ""
    
    $choice = ""
    try {
        $choice = Read-Host "Nhập lựa chọn của anh (0-5, mặc định Enter để thoát)"
    } catch {
        break
    }

    if ([string]::IsNullOrWhiteSpace($choice) -or $choice -eq "0") {
        Write-Host ""
        Write-Host "Hoàn tất! Chúc anh làm việc hiệu quả." -ForegroundColor Green
        Start-Sleep -Seconds 1
        break
    } elseif ($choice -eq "1") {
        Show-Header
        Fix-AllIssues
    } elseif ($choice -eq "2") {
        Restart-CameraServiceOnly
        Show-Header
        Fix-AllIssues
    } elseif ($choice -eq "3") {
        Start-Process "http://localhost:8095/"
    } elseif ($choice -eq "4") {
        Show-RecentLogs
        Show-Header
        Fix-AllIssues
    } elseif ($choice -eq "5") {
        Start-Process "explorer.exe" "Z:\Anh Phuong Tien"
    } else {
        Write-Host "Lựa chọn không hợp lệ!" -ForegroundColor Red
        Start-Sleep -Seconds 1
    }
}
