# Inspection Camera (Hệ Thống Chụp Ảnh Đăng Kiểm Xe 15-07D)

Hệ thống chụp và đồng bộ ảnh kiểm định xe cơ giới cho TTĐK 15-07D Hải Phòng: ứng dụng Android cho đăng kiểm viên (ĐKV) tại dây chuyền, backend FastAPI trên máy tính Windows nội bộ, và cụm Firebase Realtime Database 4 node để đồng bộ.

**Phiên bản:** Android `v1.6.1` · Backend `1.6.0` (cổng `:8095`)

> **API, cấu hình `photo_config.json`, quy tắc đặt tên file, cơ chế Firebase/offline/dọn dẹp:** xem [`SPEC.md`](SPEC.md) (nguồn sự thật duy nhất). Lịch sử phiên bản: [`CHANGELOG.md`](CHANGELOG.md). README này chỉ hướng dẫn cài đặt và vận hành.

---

## Mục lục

1. [Kiến trúc hệ thống](#kiến-trúc-hệ-thống)
2. [Cài đặt & Vận hành Backend](#cài-đặt--vận-hành-backend)
3. [Hướng dẫn Ứng dụng Di động (Android)](#hướng-dẫn-ứng-dụng-di-động-android)
4. [Xử lý sự cố (Troubleshooting)](#xử-lý-sự-cố-troubleshooting)

---

## Kiến trúc hệ thống

```
┌─────────────────────────────────────────────────────────────┐
│                 Android App (Kotlin v1.6.1)                 │
│  - CameraX (chụp & watermark thời gian)                     │
│  - Firebase Realtime Database (cụm 4 node)                  │
│  - Gửi ảnh qua hòm thư Firebase (inbox_photos)              │
└──────────────┬──────────────────────────────┬───────────────┘
               │ (Realtime sync & upload)     │ (HTTP dự phòng LAN/Tailscale)
               ▼                              ▼
┌──────────────────────────────┐ ┌────────────────────────────┐
│ Cụm 4 node Firebase RTDB     │ │ FastAPI Backend (:8095)    │
│ (3 node luân phiên theo thứ  │ │ - Worker nền 5s            │
│  + 1 node Backup failover)   │ │ - Kéo ảnh inbox từ Firebase│
│                              │ │ - Đẩy xe hôm nay lên RTDB  │
│                              │ │ - Circuit Breaker          │
└──────────────┬───────────────┘ └────────────┬───────────────┘
               └──────────────┬───────────────┘
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                  Hệ thống Trạm 15-07D                       │
│  - SQLite WAL: C:\PTCGDB_Online\ptcgdb.db                   │
│  - Bảng đệm xe chờ: waiting_vehicles (ptcgdb_waiting.db)    │
│  - CSDL Access gốc: Z:\DataPTCGDB\PT90_1507D.mdb (chỉ đọc)  │
│  - Lưu ảnh: ổ mạng Z:\ (thư mục theo loại ảnh, xem SPEC 4)  │
└─────────────────────────────────────────────────────────────┘
```

- **Kết nối & đồng bộ:** điện thoại ĐKV trao đổi dữ liệu xe và gửi ảnh qua Firebase RTDB trên cả Wi-Fi nội bộ và 4G, không cần cấu hình NAT. HTTP LAN/Tailscale là đường dự phòng.
- **Luân phiên node, failover, worker 5s, Circuit Breaker:** SPEC mục 1.3 và 3.7.

---

## Cài đặt & Vận hành Backend

### Yêu cầu
- Windows 10 / 11 / Server, Python 3.11+
- Ổ lưu ảnh theo `photo_config.json` (mặc định `Z:\...`, xem SPEC mục 4)
- `C:\PTCGDB_Online\ptcgdb.db` (nếu dùng danh sách xe)

### Cài môi trường
```bash
cd D:\inspection-camera\backend
python -m venv venv
venv\Scripts\activate.bat          # Git Bash: source venv/Scripts/activate
pip install -r requirements-dev.txt   # máy chạy thật chỉ cần requirements.txt
```

### Chạy thủ công
```bash
cd D:\inspection-camera\backend
venv\Scripts\python main.py
curl http://localhost:8095/api/health
```
Máy chủ lắng nghe tại `http://0.0.0.0:8095`.

### Chạy ngầm & tự khởi động cùng Windows

1. **Chạy ngầm:** `wscript D:\inspection-camera\backend\start_background.vbs` (ẩn cửa sổ, tự chống chạy trùng bằng WMI).
2. **Autostart:** tạo `inspection_camera.vbs` trong
   `C:\Users\<Tên_User>\AppData\Roaming\Microsoft\Windows\Start Menu\Programs\Startup\`:
   ```vbs
   Set sh = CreateObject("WScript.Shell")
   Set fso = CreateObject("Scripting.FileSystemObject")
   target = "D:\inspection-camera\backend\start_background.vbs"
   If fso.FileExists(target) Then
       sh.Run "wscript.exe """ & target & """", 0, False
   End If
   ```
   Cách này chỉ chạy **sau khi người dùng đăng nhập**, nên ổ mạng `Z:` đã ánh xạ. Nếu chuyển sang Task Scheduler "At startup" hoặc service, ổ `Z:` sẽ không có: đổi đường dẫn trong `photo_config.json` sang UNC (`\\server\share\...`).
3. **Dừng máy chủ:**
   ```bash
   netstat -ano | findstr :8095
   taskkill /PID <PID> /F
   ```

---

## Hướng dẫn Ứng dụng Di động (Android)

### Cài đặt APK
1. Vào **Actions** trên GitHub → run mới nhất → tải artifact `inspection-camera-debug.zip` → giải nén lấy `app-debug.apk` (build tự động khi có commit vào `android/` trên nhánh `main`).
2. Chép APK vào điện thoại, mở file, cho phép cài từ nguồn không xác định nếu được hỏi.
3. Cấp quyền **Máy ảnh** ở lần mở đầu.

### Thiết lập kết nối
**Cài đặt** (bánh răng, góc trên phải):
- **Bật kết nối LAN (Wi-Fi trạm)** và **Địa chỉ máy chủ LAN** (VD `192.168.193.11`): tắt nếu chỉ dùng 4G/Tailscale để khỏi mất thời gian dò Wi-Fi.
- **Bật kết nối Tailscale (4G / từ xa)** và **Địa chỉ máy chủ Tailscale** (VD `100.81.114.84`): tắt nếu ở trong trạm.
- **Bật danh sách xe:** hiển thị xe khám trong ngày từ PTCGDB.
- Nhấn **Lưu cấu hình**.

### Quy trình chụp
1. **Chọn xe hoặc nhập biển số:**
   - Chọn từ danh sách: tự điền biển số và màu biển (Trắng/Vàng/Xanh).
   - Nhập tay: `15A-123.45` hoặc `15A12345`, chọn màu biển nếu là biển mới 5 số (biển cũ 4 số không có đuôi màu).
   - Chọn **Lần 1 / Lần 2** (app tự gợi ý Lần 2 cho xe đã chụp hoặc trượt Lần 1 trong ngày).
2. **Chụp 5 loại ảnh:** Ảnh phương tiện, Ảnh biển số, Số khung / Khoang máy, Khoang người lái, Xe mới / Sau miễn KĐ. Ý nghĩa và tên file: SPEC mục 2.4.
3. **Watermark:** mỗi ảnh tự đóng dấu giờ (VD `14:30:25 - 27/09/2026`). Màn chụp có đèn pin (Torch) và bù sáng EV cho xe chụp trong gầm/tối.
4. **Hoàn thành:** ảnh chỉ được gửi khi bấm **HOÀN THÀNH - VỀ TRANG ĐẦU**.
5. **Xem lại / xoá ảnh lỗi:** nút **Xem lại** → biểu tượng thùng rác xoá ảnh cả trên máy chủ và trong bộ nhớ máy.

### Chế độ Offline & dọn ảnh tạm
- Mất mạng: app chuyển sang Offline, nút chụp hiện `✓ Offline` kèm số ảnh chờ gửi; khi có mạng lại, ảnh tự gửi theo thứ tự (SPEC 5.6).
- Ảnh xe đã cấp tem tự xoá khỏi điện thoại sau 15 phút kể từ khi tải lên thành công; xe chưa cấp tem giữ ảnh suốt ca (SPEC 5.8).

---

## Xử lý sự cố (Troubleshooting)

### 1. "Không kết nối được máy chủ"
- `netstat -ano | findstr :8095` để xác nhận backend đang chạy.
- Windows Firewall: mở inbound TCP `8095` (hoặc cho phép Python).
- `ipconfig`: IP LAN có thể đổi do DHCP. Nên đặt DHCP reservation cho máy trạm.
- 4G/ngoài trạm: Tailscale trên cả máy tính và điện thoại phải ở trạng thái `Connected`.

### 2. Danh sách xe không hiển thị
- Kiểm tra `C:\PTCGDB_Online\ptcgdb.db` có tồn tại.
- Ngày giờ điện thoại và máy tính phải khớp. Ngày nghỉ không có lượt kiểm thì trả `[]`; thử `/api/vehicles/today?date=YYYY-MM-DD` với ngày trước.
- Kiểm tra worker 5s còn chạy (xem `server.log`) và Circuit Breaker không đang mở.

### 3. Ảnh không xuất hiện trong thư mục lưu ảnh
- Ổ mạng `Z:\` có bị ngắt hoặc mất quyền ghi? Nếu backend chạy ngoài phiên đăng nhập, dùng đường dẫn UNC.
- Kiểm tra đường dẫn theo loại ảnh trong `backend/photo_config.json` (SPEC mục 4).
- Xem `server.log` để biết mã lỗi chi tiết.

### 4. Không đồng bộ được ảnh offline
- Điện thoại phải nối lại đúng Wi-Fi trạm, Tailscale hoặc có 4G (Firebase).
- Nhấn "Thử đồng bộ lại" ở màn hình chính.
- Kiểm tra dung lượng trống trên điện thoại.

### 5. Sự cố Firebase (ảnh/danh sách xe không về qua RTDB)
- Kiểm tra backend kéo `inbox_photos` có chạy (`server.log`).
- Node theo lịch có thể hết quota hoặc lỗi: app tự chuyển Node Backup (SPEC 1.3); nếu vẫn lỗi, kiểm tra Firebase Security Rules.
- **Kiểm tra trạng thái cụm node:** **Cài đặt** → cuộn xuống **Kiểm tra kết nối máy chủ** → nhấn **Kiểm tra kết nối**. App đo độ trễ và hiển thị `✓ Sẵn sàng (<ms>ms)` hoặc `✗ Lỗi` cho cả 4 node (Node 1, Node 2, Node 3, Node Backup).
- **Làm mới kết nối & danh sách xe:** tại màn hình chính, vuốt kéo xuống (pull-to-refresh) hoặc nhấn "Thử đồng bộ lại".
