# Inspection Camera (Hệ Thống Chụp Ảnh Đăng Kiểm Xe)

Hệ thống chụp và đồng bộ ảnh kiểm định xe cơ giới dành cho trung tâm đăng kiểm (TTDK 15-07D). Bao gồm ứng dụng Android dành cho đăng kiểm viên tại dây chuyền và máy chủ backend FastAPI chạy trên máy tính Windows nội bộ để lưu trữ và phân loại ảnh tự động.

---

## Mục lục

1. [Kiến trúc hệ thống](#kiến-trúc-hệ-thống)
2. [Cài đặt & Vận hành Backend](#cài-đặt--vận-hành-backend)
   - [Yêu cầu hệ thống](#yêu-cầu-hệ-thống)
   - [Cài đặt môi trường](#cài-đặt-môi-trường)
   - [Khởi chạy thủ công](#khởi-chạy-thủ-công)
   - [Chạy ngầm & Tự khởi động cùng Windows](#chạy-ngầm--tự-khởi-động-cùng-windows)
3. [Hướng dẫn Ứng dụng Di động (Android)](#hướng-dẫn-ứng-dụng-di-động-android)
   - [Tải và cài đặt APK](#tải-và-cài-đặt-apk)
   - [Thiết lập kết nối máy chủ](#thiết-lập-kết-nối-máy-chủ)
   - [Quy trình chụp ảnh trên dây chuyền](#quy-trình-chụp-ảnh-trên-dây-chuyền)
   - [Chế độ Offline & Đồng bộ tự động](#chế-độ-offline--đồng-bộ-tự-động)
4. [Tài liệu API (Endpoints)](#tài-liệu-api-endpoints)
5. [Cấu hình Hệ thống (`photo_config.json`)](#cấu-hình-hệ-thống-photo_configjson)
6. [Quy tắc Đặt tên & Lưu trữ Ảnh](#quy-tắc-đặt-tên--lưu-trữ-ảnh)
7. [Xử lý sự cố (Troubleshooting)](#xử-lý-sự-cố-troubleshooting)

---

## Kiến trúc hệ thống

```
┌─────────────────────────────────┐
│     Android App (Kotlin)        │
│  - CameraX (Chụp & Watermark)   │
│  - Retrofit + OkHttp            │
│  - WorkManager (Offline Sync)   │
│  - Material Design 3            │
└───────────────┬─────────────────┘
                │ HTTP / REST (Port 8095)
                │ [Ưu tiên LAN -> Dự phòng Tailscale]
                ▼
┌─────────────────────────────────┐
│     FastAPI Backend (Python)    │
│  - Uvicorn Server (:8095)       │
│  - Đọc PTCGDB SQLite (Read-only)│
│  - Chuẩn hóa biển số & màu biển │
│  - Phân loại đường dẫn lưu trữ  │
└───────────────┬─────────────────┘
                ▼
┌─────────────────────────────────┐
│  Hệ thống File Server (Windows) │
│  - D:\Photos\                   │
│  - D:\Photos\YYYYMMDD\{plate}\  │
└─────────────────────────────────┘
```

- **Mạng kết nối**: Ứng dụng tự động kiểm tra mạng nội bộ (LAN WiFi TTDK) trước với timeout ngắn (1.5 giây). Nếu không kết nối được LAN, tự động chuyển sang VPN mạng riêng Tailscale để gửi ảnh từ xa.
- **Dữ liệu đăng kiểm**: Backend đọc trực tiếp từ cơ sở dữ liệu `C:\PTCGDB_Online\ptcgdb.db` ở chế độ read-only (`mode=ro`), không làm ảnh hưởng hay khóa ứng dụng đánh giá kiểm định hiện hành.

---

## Cài đặt & Vận hành Backend

### Yêu cầu hệ thống
- Hệ điều hành: Windows 10 / Windows 11 / Windows Server
- Python 3.11 trở lên
- Ổ đĩa lưu ảnh: `D:\` (mặc định lưu tại `D:\Photos`)
- Cơ sở dữ liệu: `C:\PTCGDB_Online\ptcgdb.db` (nếu dùng tính năng tải danh sách xe)

### Cài đặt môi trường
Mở Git Bash hoặc Command Prompt tại thư mục dự án:

```bash
cd D:\inspection-camera\backend

# Khởi tạo môi trường ảo Python
python -m venv venv

# Kích hoạt môi trường ảo
# Windows Command Prompt:
venv\Scripts\activate.bat
# Git Bash:
source venv/Scripts/activate

# Cài đặt các thư viện phụ thuộc
pip install -r requirements.txt
```

### Khởi chạy thủ công
```bash
cd D:\inspection-camera\backend
venv\Scripts\python main.py
```
Máy chủ sẽ lắng nghe tại `http://0.0.0.0:8095`.

Kiểm tra trạng thái máy chủ:
```bash
curl http://localhost:8095/api/health
```

### Chạy ngầm & Tự khởi động cùng Windows

1. **Khởi chạy ngầm không hiện cửa sổ**:
   Thư mục `backend/` đã có sẵn file script `start_background.vbs`. File này sử dụng Windows Script Host (`wscript.exe`) chạy ẩn (window style `0`), đồng thời có cơ chế chống khởi chạy trùng lặp (WMI process check).
   ```bash
   wscript D:\inspection-camera\backend\start_background.vbs
   ```

2. **Cấu hình tự khởi động cùng Windows (Autostart)**:
   Tạo shortcut hoặc file `.vbs` trong thư mục Startup của Windows:
   - Đường dẫn thư mục: `C:\Users\<Tên_User>\AppData\Roaming\Microsoft\Windows\Start Menu\Programs\Startup\`
   - Tạo file `inspection_camera.vbs` với nội dung:
     ```vbs
     Set sh = CreateObject("WScript.Shell")
     Set fso = CreateObject("Scripting.FileSystemObject")
     target = "D:\inspection-camera\backend\start_background.vbs"
     If fso.FileExists(target) Then
         sh.Run "wscript.exe """ & target & """", 0, False
     End If
     ```
   Khi máy tính bật hoặc người dùng đăng nhập, máy chủ ảnh sẽ tự động chạy ngầm.

3. **Dừng máy chủ đang chạy ngầm**:
   ```bash
   # Tìm PID cổng 8095
   netstat -ano | findstr :8095
   # Dừng tiến trình theo PID
   taskkill /PID <PID> /F
   ```

---

## Hướng dẫn Ứng dụng Di động (Android)

### Tải và cài đặt APK
1. **Tải từ GitHub Releases / CI**:
   - Khi có commit đẩy lên nhánh `main` trong thư mục `android/`, GitHub Actions tự động build ra file `app-debug.apk`.
   - Vào mục **Actions** trên GitHub repository -> chọn workflow run mới nhất -> tải artifact `inspection-camera-debug.zip` -> giải nén lấy file `app-debug.apk`.
2. **Cài đặt lên điện thoại**:
   - Chép file `app-debug.apk` vào điện thoại Android.
   - Mở file và chọn **Cài đặt** (cho phép cài đặt ứng dụng từ nguồn không xác định nếu được yêu cầu).
   - Cấp quyền **Máy ảnh (Camera)** cho ứng dụng trong lần mở đầu tiên.

### Thiết lập kết nối máy chủ
1. Mở ứng dụng, nhấn vào biểu tượng **Cài đặt (Bánh răng)** ở góc trên bên phải màn hình chính.
2. Nhập các thông số mạng:
   - **Địa chỉ máy chủ LAN**: Ví dụ `http://192.168.1.100:8095` (IP máy chủ trong mạng nội bộ trạm).
   - **Địa chỉ máy chủ Tailscale**: Ví dụ `http://100.x.y.z:8095` (IP Tailscale của máy tính).
   - **Bật danh sách xe**: Bật công tắc để hiển thị danh sách xe khám trong ngày từ phần mềm PTCGDB.
3. Nhấn **Lưu cấu hình**.

### Quy trình chụp ảnh trên dây chuyền
1. **Chọn xe hoặc Nhập biển số**:
   - **Xe có trong danh sách**: Nhấn chọn biển số từ danh sách xe đăng kiểm trong ngày. Ứng dụng tự động điền biển số và loại màu biển (Trắng / Vàng / Xanh).
   - **Nhập thủ công**: Nhập biển số (ví dụ `15A-123.45` hoặc `15A12345`), chọn màu biển nếu là xe biển mới 5 số (xe biển cũ 4 số không có đuôi màu).
2. **Chụp 5 góc ảnh quy định**:
   - **Góc chụp sau 45° (`rear_45`)**: Ảnh tổng thể sau xe nhìn chéo 45 độ, chụp rõ biển số sau.
   - **Góc chụp trước 45° (`front_45`)**: Ảnh tổng thể đầu xe nhìn chéo 45 độ, chụp rõ biển số trước.
   - **Số khung (`chassis`)**: Chụp cận cảnh số khung dập trên xe hoặc tem khung.
   - **Khoang khách (`passenger`)**: Chụp bên trong xe (ghế, khoang hành khách). Có thể chụp nhiều ảnh liên tiếp (`_1`, `_2`, ...).
   - **Xe nghiệm thu (`new_vehicle`)**: Chụp ảnh nghiệm thu cải tạo/xe mới. Có thể chụp nhiều ảnh liên tiếp (`_1`, `_2`, ...).
3. **Đóng dấu thời gian (Watermark)**:
   - Mỗi ảnh chụp đều được tự động đóng dấu ngày giờ (ví dụ `14:30:25 - 27/09/2026`) theo chuẩn định dạng của Cục Đăng kiểm.
4. **Xem lại và Xóa ảnh lỗi**:
   - Nhấn nút **Xem lại** trên màn hình chụp để xem toàn bộ ảnh đã chụp cho xe hiện tại.
   - Nhấn vào biểu tượng thùng rác để xóa ảnh lỗi; hệ thống sẽ xóa ảnh cả trên máy chủ và trong bộ nhớ máy.

### Chế độ Offline & Đồng bộ tự động
- Khi mất kết nối WiFi/LAN và không có 4G/Tailscale, ứng dụng chuyển sang **chế độ Offline**.
- Ảnh chụp được lưu trữ an toàn trong bộ nhớ nội bộ (`filesDir/pending`).
- Nút chụp hiển thị trạng thái `✓ Offline` và đếm số lượng ảnh đang chờ gửi.
- Khi thiết bị kết nối lại mạng LAN hoặc Tailscale, tiến trình chạy nền `SyncWorker` (WorkManager) sẽ tự động đẩy toàn bộ ảnh tồn đọng lên máy chủ theo thứ tự và dọn dẹp file tạm.

---

## Tài liệu API (Endpoints)

Base URL: `http://<server-ip>:8095`

### 1. Kiểm tra trạng thái máy chủ
- **Endpoint**: `GET /api/health`
- **Mô tả**: Dùng cho ứng dụng ping kiểm tra kết nối LAN hoặc Tailscale.
- **Response** (`200 OK`):
  ```json
  {
    "ok": true,
    "server": "15-07D Photo Server",
    "version": "1.0.0",
    "time": "2026-09-27T19:50:00.000000"
  }
  ```

### 2. Tải ảnh lên
- **Endpoint**: `POST /api/upload`
- **Content-Type**: `multipart/form-data`
- **Parameters**:
  - `file`: File ảnh định dạng JPEG (bắt buộc, dung lượng < 15MB).
  - `plate`: Biển số xe (ví dụ: `15A-123.45`, `15A12345`).
  - `plate_color`: Mã màu biển (`T` = Trắng, `V` = Vàng, `X` = Xanh). Để trống hoặc `null` với biển cũ 4 số.
  - `photo_type`: Một trong 5 loại: `rear_45`, `front_45`, `chassis`, `passenger`, `new_vehicle`.
  - `seq`: Số thứ tự ảnh (tùy chọn, áp dụng cho `passenger` và `new_vehicle`, mặc định `1`).
- **Response** (`200 OK`):
  ```json
  {
    "ok": true,
    "path": "D:\\Photos\\15A12345T.jpg",
    "filename": "15A12345T.jpg"
  }
  ```

### 3. Xóa ảnh
- **Endpoint**: `DELETE /api/photos`
- **Content-Type**: `application/json`
- **Request Body**:
  ```json
  {
    "plate": "15A12345",
    "plate_color": "T",
    "photo_type": "rear_45",
    "seq": null
  }
  ```
- **Response** (`200 OK`):
  ```json
  {
    "ok": true,
    "deleted": "D:\\Photos\\15A12345T.jpg"
  }
  ```

### 4. Lấy danh sách xe kiểm định trong ngày
- **Endpoint**: `GET /api/vehicles/today`
- **Query Parameters**:
  - `date`: Định dạng `YYYY-MM-DD` (tùy chọn, mặc định là ngày hôm nay).
- **Response** (`200 OK`):
  ```json
  [
    {
      "plate": "88D-001.41T",
      "plate_clean": "88D00141",
      "plate_color": "T",
      "vehicle_type": "Ô tô tải VAN",
      "brand": "SUZUKI",
      "owner": "Vũ Văn Chính",
      "time": "15:30",
      "result": 0,
      "photos_taken": ["rear_45", "front_45"]
    }
  ]
  ```

### 5. Đọc & Cập nhật cấu hình
- **Endpoint**: `GET /api/config`
- **Response**: Trả về toàn bộ file cấu hình JSON hiện tại.
- **Endpoint**: `POST /api/config`
- **Request Body**: JSON định dạng `PhotoConfig`. Cập nhật và lưu lại file cấu hình trên đĩa.

---

## Cấu hình Hệ thống (`photo_config.json`)

File cấu hình đặt tại `backend/photo_config.json`:

```json
{
  "vehicle_list_enabled": true,
  "server_port": 8095,
  "paths": {
    "rear_45": "D:\\Photos",
    "front_45": "D:\\Photos",
    "chassis": "D:\\Photos",
    "passenger": "D:\\Photos\\{date}\\{plate}",
    "new_vehicle": "D:\\Photos\\{date}\\{plate}"
  },
  "jpeg_quality": 85,
  "plate_color_suffix": true,
  "timestamp": {
    "enabled": true,
    "format": "HH:mm:ss - dd/MM/yyyy",
    "font_size": 28,
    "font_color": "#FFFFFF",
    "font_bold": true,
    "font_stroke_enabled": true,
    "font_stroke_color": "#000000",
    "font_stroke_width": 2.0,
    "background_color": "#80000000",
    "position": "bottom_right"
  },
  "photo_resolution": "original"
}
```

### Chi tiết các tham số:
- `vehicle_list_enabled` (*boolean*): Bật/tắt đọc danh sách xe từ CSDL đăng kiểm PTCGDB.
- `server_port` (*int*): Cổng mạng dịch vụ FastAPI (mặc định `8095`).
- `paths` (*object*): Quy tắc thư mục lưu trữ cho từng loại ảnh. Hỗ trợ biến thay thế:
  - `{date}`: Ngày hiện tại định dạng `YYYYMMDD` (ví dụ `20260927`).
  - `{plate}`: Biển số xe đã chuẩn hóa (ví dụ `15A12345`).
- `jpeg_quality` (*int*): Mức nén chất lượng ảnh JPEG từ 1 đến 100 (mặc định `85`).
- `plate_color_suffix` (*boolean*): Khi `true`, nối ký tự mã màu biển vào tên file ảnh góc 45° (`T`, `V`, `X`).
- `timestamp` (*object*):
  - `enabled`: Cho phép vẽ watermark thời gian lên ảnh.
  - `format`: Mẫu hiển thị thời gian (`HH:mm:ss - dd/MM/yyyy`).
  - `font_size`: Cỡ chữ (pixel tương đối trên ảnh).
  - `font_color`: Màu chữ (mã HEX, mặc định trắng `#FFFFFF`).
  - `font_stroke_enabled`: Bật viền chữ để dễ đọc trên nền sáng.
  - `font_stroke_color`: Màu viền chữ (mặc định đen `#000000`).
  - `font_stroke_width`: Độ dày viền chữ.
  - `background_color`: Màu nền chữ mờ (`#80000000` = đen trong suốt 50%).
  - `position`: Vị trí đóng dấu (`bottom_right`, `bottom_left`, `top_right`, `top_left`).
- `photo_resolution` (*string*): Độ phân giải ảnh (`original`, `1080p`, `720p`).

---

## Quy tắc Đặt tên & Lưu trữ Ảnh

### 1. Chuẩn hóa biển số xe Việt Nam
- Ký tự gạch ngang `-`, dấu chấm `.`, khoảng trắng ` ` được loại bỏ hoàn toàn.
- Chuyển toàn bộ chữ cái sang IN HOA.
- **Biển mới (5 số)**: Có mã màu đuôi:
  - `T`: Biển trắng (xe tư nhân, doanh nghiệp). Ví dụ `15A-123.45` biển trắng -> `15A12345T`.
  - `V`: Biển vàng (xe kinh doanh vận tải). Ví dụ `15B-012.34` biển vàng -> `15B01234V`.
  - `X`: Biển xanh (xe cơ quan nhà nước). Ví dụ `15A-001.23` biển xanh -> `15A00123X`.
- **Biển cũ (4 số)**: Không có mã màu đuôi. Ví dụ `11K-2639` -> `11K2639`.

### 2. Quy tắc tên file ảnh
| Loại ảnh | Mã API | Tên file ví dụ (Biển mới) | Tên file ví dụ (Biển cũ) |
|---|---|---|---|
| Góc sau 45° | `rear_45` | `15A12345T.jpg` | `11K2639.jpg` |
| Góc trước 45° | `front_45` | `bs15A12345T.jpg` | `bs11K2639.jpg` |
| Số khung | `chassis` | `sk_15A12345.jpg` | `sk_11K2639.jpg` |
| Khoang khách | `passenger` | `15A12345_1.jpg`, `15A12345_2.jpg` | `11K2639_1.jpg` |
| Xe mới / nghiệm thu | `new_vehicle` | `15A12345_1.jpg` | `11K2639_1.jpg` |

---

## Xử lý sự cố (Troubleshooting)

### 1. Ứng dụng báo "Không kết nối được máy chủ"
- Kiểm tra xem máy tính chủ có đang bật và mở cổng 8095 không:
  ```bash
  netstat -ano | findstr :8095
  ```
- Kiểm tra tường lửa Windows (Windows Firewall): Đảm bảo cổng inbound TCP `8095` đã được mở hoặc ứng dụng Python được cấp phép qua firewall.
- Kiểm tra địa chỉ IP mạng nội bộ của máy tính chủ (`ipconfig`) xem có bị thay đổi do DHCP cấp phát lại không. Nếu thay đổi, cập nhật lại địa chỉ LAN trong cài đặt ứng dụng Android.
- Nếu đang dùng mạng 4G/ngoài trạm: Đảm bảo phần mềm Tailscale trên máy tính chủ và điện thoại đều đang ở trạng thái `Connected`.

### 2. Danh sách xe trong ngày không hiển thị
- Kiểm tra xem file cơ sở dữ liệu `C:\PTCGDB_Online\ptcgdb.db` có tồn tại trên máy tính chủ không.
- Kiểm tra ngày tháng trên điện thoại và máy tính chủ có khớp nhau không. Vào ngày nghỉ (thứ Bảy/Chủ Nhật), nếu không có lượt xe đăng kiểm nào, danh sách xe sẽ trả về rỗng `[]`. Bạn có thể thử gọi `/api/vehicles/today?date=YYYY-MM-DD` với ngày trước đó để kiểm tra.

### 3. Ảnh chụp không xuất hiện trong thư mục `D:\Photos`
- Kiểm tra ổ đĩa `D:\` có bị đầy hoặc bị khóa ghi không.
- Kiểm tra đường dẫn cấu hình trong `backend/photo_config.json`.
- Kiểm tra log máy chủ backend để xem chi tiết mã lỗi trả về.

### 4. Không đồng bộ được ảnh offline
- Đảm bảo điện thoại đã kết nối lại đúng mạng WiFi của trạm hoặc Tailscale đã kết nối.
- Mở màn hình chính của ứng dụng và nhấn vào nút "Thử đồng bộ lại" hoặc chụp thêm một tấm ảnh khi có mạng để kích hoạt `SyncWorker` làm việc ngay.
- Kiểm tra dung lượng bộ nhớ trống trên điện thoại.
