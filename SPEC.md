# ĐẶC TẢ KỸ THUẬT: ỨNG DỤNG CHỤP ẢNH KIỂM ĐỊNH XE CƠ GIỚI

**Dự án:** inspection-camera
**Trạm:** Trung tâm Đăng kiểm xe cơ giới 15-07D Hải Phòng
**Ngày tạo:** 2026-09-27
**Phiên bản:** 1.0.0

---

## 1. TỔNG QUAN

### 1.1. Mục tiêu
Xây dựng ứng dụng Android (file `.apk`) cho đăng kiểm viên chụp ảnh xe tại dây chuyền kiểm định. Ảnh tự động đặt tên chuẩn theo biển số xe và truyền thẳng về máy tính trạm qua mạng nội bộ hoặc Tailscale.

### 1.2. Thành phần hệ thống

| Thành phần | Công nghệ | Vị trí |
|---|---|---|
| App Android | Kotlin + CameraX + Retrofit + Material Design 3 | Điện thoại ĐKV |
| Backend API | FastAPI (Python) | Máy tính trạm 15-07D |
| CSDL | SQLite (`ptcgdb.db`) | Máy tính trạm 15-07D |
| Build APK | GitHub Actions | GitHub repo `vtpham87/inspection-camera` |

### 1.3. Kết nối mạng
- **Mạng LAN Wi-Fi nội bộ trạm:** `http://192.168.193.11:8095`
- **Mạng Tailscale / 4G từ xa:** `http://100.81.114.84:8095`
- App tự phát hiện và ưu tiên LAN khi có sẵn, chuyển sang Tailscale khi ở ngoài bãi.

---

## 2. BACKEND API (FastAPI — Port 8095)

### 2.1. Cấu trúc thư mục Backend

```
D:\inspection-camera\
├── backend\
│   ├── main.py              # FastAPI app chính
│   ├── config.py            # Đọc/ghi photo_config.json
│   ├── photo_handler.py     # Logic đặt tên & lưu file ảnh
│   ├── vehicle_service.py   # Truy vấn danh sách xe từ ptcgdb.db
│   ├── photo_config.json    # File cấu hình (auto-generated)
│   └── requirements.txt     # Dependencies
```

### 2.2. API Endpoints

#### `POST /api/upload` — Nhận ảnh từ điện thoại

**Request:** `multipart/form-data`

| Field | Type | Required | Mô tả |
|---|---|---|---|
| `file` | File (JPEG) | ✅ | File ảnh chụp từ camera (đã in timestamp nếu bật) |
| `plate` | string | ✅ | Biển số xe, VD: `15A12345` |
| `plate_color` | string | ❌ | Màu biển: `T` (mặc định) / `V` / `X` |
| `photo_type` | string | ✅ | Loại ảnh: `rear_45` / `front_45` / `chassis` / `passenger` / `new_vehicle` |
| `seq` | int | ❌ | Số thứ tự cho loại chụp nhiều ảnh (mặc định: auto-increment) |

> **Lưu ý:** Timestamp được in trực tiếp lên ảnh (burn-in) tại phía App Android trước khi upload, đảm bảo ảnh lưu trên máy tính trạm luôn có dấu thời gian.

**Quy tắc đặt tên file:**

| photo_type | Tên file | Thư mục |
|---|---|---|
| `rear_45` | `{plate}.jpg` hoặc `{plate}{color}.jpg` | Cấu hình `paths.rear_45` |
| `front_45` | `bs{plate}.jpg` hoặc `bs{plate}{color}.jpg` | Cấu hình `paths.front_45` |
| `chassis` | `sk_{plate}.jpg` | Cấu hình `paths.chassis` |
| `passenger` | `{plate}_1.jpg`, `{plate}_2.jpg`, ... | Cấu hình `paths.passenger` (thư mục con `{plate}\`) |
| `new_vehicle` | `{plate}_1.jpg`, `{plate}_2.jpg`, ... | Cấu hình `paths.new_vehicle` (thư mục con `{plate}\`) |

> **Quy tắc hậu tố màu biển:**
> - Khi `plate_color_suffix = true` VÀ `plate_color` có giá trị (T/V/X): thêm hậu tố vào tên file (VD: `15A12345T.jpg`, `bs15A12345T.jpg`)
> - Khi `plate_color` là `null` (biển cũ không có hậu tố): **KHÔNG** thêm hậu tố, dùng biển thuần (VD: `11K2639.jpg`)

**Biến đường dẫn:**
- `{date}` → Ngày hiện tại dạng `YYYYMMDD` (VD: `20260927`)
- `{plate}` → Biển số xe đã chuẩn hóa (VD: `15A12345`)

**Response thành công:** `200 OK`
```json
{
  "ok": true,
  "path": "D:\\Photos\\20260927\\15A12345.jpg",
  "filename": "15A12345.jpg"
}
```

**Response lỗi:** `400 / 500`
```json
{
  "ok": false,
  "error": "Biển số không hợp lệ"
}
```

#### `DELETE /api/photos` — Xoá ảnh trên máy tính

**Request:** `application/json`
```json
{
  "plate": "15A12345",
  "photo_type": "rear_45",
  "seq": null
}
```

**Response:** `200 OK`
```json
{
  "ok": true,
  "deleted": "D:\\Photos\\20260927\\15A12345.jpg"
}
```

#### `GET /api/vehicles/today` — Danh sách xe kiểm định hôm nay

**Query parameters:**

| Param | Type | Mô tả |
|---|---|---|
| `date` | string (YYYY-MM-DD) | Ngày cần truy vấn, mặc định hôm nay |

**Logic truy vấn:**
```sql
SELECT v.biendk, v.biendk_clean, v.chupt, v.nhanhieu, v.tenloaipt,
       i.ngaykd, i.giokd, i.ketluan
FROM inspections i
JOIN vehicles v ON i.biendk_id = v.biendk_id
WHERE i.ngaykd = :today
ORDER BY i.giokd DESC
```

**Trích xuất `plate_color` từ `biendk_clean`:**

Bảng `vehicles` không có cột `plate_color` riêng. Màu biển được nhúng vào **ký tự cuối** của `biendk_clean`:
- Ký tự cuối là `T` → Trắng, `V` → Vàng, `X` → Xanh
- Ký tự cuối là **chữ số** (biển cũ, VD: `11K2639`) → `plate_color = null`, phần biển số thuần = toàn bộ `biendk_clean`

```python
# Backend logic trích xuất plate_color
def extract_plate_color(biendk_clean: str) -> tuple[str, str | None]:
    """Trả về (plate_number, plate_color).
    VD: '15A12345T' -> ('15A12345', 'T')
        '11K2639'   -> ('11K2639', None)
    """
    if biendk_clean and biendk_clean[-1] in ('T', 'V', 'X'):
        return biendk_clean[:-1], biendk_clean[-1]
    return biendk_clean, None
```

**Response:** `200 OK`
```json
[
  {
    "plate": "15A-123.45",
    "plate_clean": "15A12345",
    "plate_color": "T",
    "vehicle_type": "Ô tô con",
    "brand": "TOYOTA",
    "owner": "Nguyễn Văn A",
    "time": "08:30",
    "result": 1,
    "photos_taken": ["rear_45", "front_45"]
  }
]
```

> **Lưu ý:** `plate_color` có thể là `null` đối với biển số cũ không có hậu tố màu. Khi `plate_color` là `null`, tên file ảnh KHÔNG thêm hậu tố (VD: `11K2639.jpg` thay vì `11K2639T.jpg`).

Trường `photos_taken` kiểm tra file đã tồn tại trên đĩa để hiển thị trạng thái chụp trên app.

#### `GET /api/config` — Đọc cấu hình

**Response:** `200 OK`
```json
{
  "vehicle_list_enabled": true,
  "server_port": 8095,
  "paths": {
    "rear_45": "D:\\Photos\\{date}",
    "front_45": "D:\\Photos\\{date}",
    "chassis": "D:\\Photos\\{date}",
    "passenger": "D:\\Photos\\{date}\\{plate}",
    "new_vehicle": "D:\\Photos\\{date}\\{plate}"
  }
}
```

#### `POST /api/config` — Cập nhật cấu hình

**Request:** `application/json` (cùng cấu trúc như GET response)

**Response:** `200 OK`
```json
{
  "ok": true,
  "message": "Đã lưu cấu hình"
}
```

#### `GET /api/health` — Kiểm tra kết nối

**Response:** `200 OK`
```json
{
  "ok": true,
  "server": "15-07D Photo Server",
  "version": "1.0.0",
  "time": "2026-09-27T14:30:00"
}
```

### 2.3. File cấu hình `photo_config.json`

```json
{
  "vehicle_list_enabled": true,
  "server_port": 8095,
  "paths": {
    "rear_45": "D:\\Photos\\{date}",
    "front_45": "D:\\Photos\\{date}",
    "chassis": "D:\\Photos\\{date}",
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

| Tham số | Mô tả | Mặc định |
|---|---|---|
| `vehicle_list_enabled` | Bật/tắt API danh sách xe | `true` |
| `server_port` | Cổng dịch vụ | `8095` |
| `paths.*` | Đường dẫn lưu ảnh cho từng loại nút | Xem bảng trên |
| `jpeg_quality` | Chất lượng nén JPEG (1-100) | `85` |
| `plate_color_suffix` | Thêm hậu tố màu biển (T/V/X) vào tên file | `true` |
| `timestamp.enabled` | Bật/tắt in thời gian chụp lên ảnh | `true` |
| `timestamp.format` | Định dạng thời gian hiển thị trên ảnh | `HH:mm:ss - dd/MM/yyyy` |
| `timestamp.font_size` | Cỡ chữ timestamp (sp) | `28` |
| `timestamp.font_color` | Màu chữ timestamp (HEX) | `#FFFFFF` (trắng) |
| `timestamp.font_bold` | Chữ đậm | `true` |
| `timestamp.font_stroke_enabled` | Bật/tắt viền chữ (stroke) | `true` |
| `timestamp.font_stroke_color` | Màu viền chữ (HEX) | `#000000` (đen) |
| `timestamp.font_stroke_width` | Độ dày viền chữ (dp) | `2.0` |
| `timestamp.background_color` | Màu nền phía sau chữ timestamp (HEX + alpha) | `#80000000` (đen bán trong suốt) |
| `timestamp.position` | Vị trí in trên ảnh | `bottom_right` |
| `photo_resolution` | Độ phân giải ảnh chụp | `original` |

**Giá trị hợp lệ cho `photo_resolution`:**

| Giá trị | Kích thước | Ghi chú |
|---|---|---|
| `original` | Giữ nguyên độ phân giải gốc camera | Mặc định — chất lượng cao nhất, dung lượng lớn |
| `high` | 3840 × 2160 (4K) | Cân bằng chất lượng và dung lượng |
| `medium` | 1920 × 1080 (Full HD) | Phù hợp cho hầu hết nhu cầu kiểm định |
| `low` | 1280 × 720 (HD) | Tiết kiệm dung lượng và băng thông mạng |

> Khi chọn chế độ khác `original`, ảnh sẽ được resize (giữ tỉ lệ gốc) trước khi in timestamp và upload.

**Giá trị hợp lệ cho `timestamp.position`:**

| Giá trị | Vị trí trên ảnh |
|---|---|
| `top_left` | Góc trên bên trái |
| `top_right` | Góc trên bên phải |
| `top_center` | Chính giữa phía trên |
| `bottom_left` | Góc dưới bên trái |
| `bottom_right` | Góc dưới bên phải (mặc định) |
| `bottom_center` | Chính giữa phía dưới |

---

## 3. APP ANDROID

### 3.1. Cấu trúc thư mục Android

```
android/
├── app/
│   ├── src/main/
│   │   ├── java/com/ttdk1507d/inspectioncamera/
│   │   │   ├── MainActivity.kt              # Màn hình 1: Chọn xe
│   │   │   ├── CameraActivity.kt            # Màn hình 2: Chụp ảnh
│   │   │   ├── ReviewActivity.kt            # Màn hình 3: Xem lại ảnh
│   │   │   ├── SettingsActivity.kt           # Màn hình 4: Cài đặt
│   │   │   ├── api/
│   │   │   │   ├── ApiService.kt            # Retrofit interface
│   │   │   │   └── ApiClient.kt             # HTTP client config
│   │   │   ├── model/
│   │   │   │   ├── Vehicle.kt               # Data class xe
│   │   │   │   ├── PhotoType.kt             # Enum loại ảnh
│   │   │   │   └── AppConfig.kt             # Data class cấu hình
│   │   │   ├── adapter/
│   │   │   │   ├── VehicleAdapter.kt        # RecyclerView adapter
│   │   │   │   └── PhotoReviewAdapter.kt    # Grid ảnh xem lại
│   │   │   └── util/
│   │   │       ├── NetworkUtil.kt           # Phát hiện LAN / Tailscale
│   │   │       ├── PrefsManager.kt          # SharedPreferences
│   │   │       └── TimestampPainter.kt      # Vẽ timestamp lên Bitmap
│   │   ├── res/
│   │   │   ├── layout/                      # XML layouts 4 màn hình
│   │   │   ├── values/
│   │   │   │   ├── colors.xml               # Bảng màu Light Mode
│   │   │   │   ├── strings.xml              # Chuỗi tiếng Việt
│   │   │   │   └── themes.xml               # Material 3 Light Theme
│   │   │   └── drawable/                    # Icons, backgrounds
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── build.gradle.kts
├── settings.gradle.kts
└── gradle/
```

### 3.2. Yêu cầu Android tối thiểu

| Tham số | Giá trị |
|---|---|
| `minSdk` | 24 (Android 7.0) |
| `targetSdk` | 34 (Android 14) |
| `compileSdk` | 34 |
| Package name | `com.ttdk1507d.inspectioncamera` |
| App name | `Chụp ảnh KĐ 15-07D` |

### 3.3. Dependencies chính

```kotlin
// Camera
implementation("androidx.camera:camera-camera2:1.3.4")
implementation("androidx.camera:camera-lifecycle:1.3.4")
implementation("androidx.camera:camera-view:1.3.4")

// Network
implementation("com.squareup.retrofit2:retrofit:2.11.0")
implementation("com.squareup.retrofit2:converter-gson:2.11.0")
implementation("com.squareup.okhttp3:okhttp:4.12.0")

// UI
implementation("com.google.android.material:material:1.12.0")
implementation("androidx.recyclerview:recyclerview:1.3.2")
implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

// Image loading (xem lại thumbnail)
implementation("io.coil-kt:coil:2.7.0")
```

### 3.4. Permissions (AndroidManifest.xml)

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.VIBRATE" />
```

### 3.5. Luồng xử lý chính (Flow)

```
Mở app
  │
  ├─ Đọc cấu hình cục bộ (SharedPreferences)
  │    ├─ IP / Port máy chủ
  │    ├─ Toggle danh sách xe (bật/tắt)
  │    └─ Đường dẫn lưu ảnh
  │
  ├─ [Toggle BẬT] Gọi GET /api/vehicles/today
  │    └─ Hiển thị danh sách xe → Chạm chọn → CameraActivity
  │
  ├─ [Toggle TẮT hoặc lỗi mạng] Chỉ hiển thị ô nhập biển số
  │    └─ Nhập biển số + Chọn màu biển → Bấm [CHỌN] → CameraActivity
  │
  └─ CameraActivity (Màn hình chụp)
       │
       ├─ Khởi tạo CameraX (camera sau, Live Preview)
       │
       ├─ Bấm nút [Góc sau 45°]
       │    ├─ Chụp ảnh JPEG (CameraX takePicture)
       │    ├─ Resize theo photo_resolution (nếu không phải original)
       │    ├─ [Timestamp BẬT] Vẽ dòng thời gian lên ảnh (TimestampPainter)
       │    │    └─ Format, font, stroke, vị trí theo cấu hình người dùng
       │    ├─ Nén JPEG theo jpeg_quality
       │    ├─ POST /api/upload (plate, photo_type=rear_45, file)
       │    ├─ Thành công → ✅ + rung nhẹ
       │    └─ Thất bại → ❌ + lưu tạm cục bộ để retry
       │
       ├─ Bấm nút [Góc trước 45°] → Tương tự, photo_type=front_45
       ├─ Bấm nút [Số khung/Khoang máy] → photo_type=chassis
       ├─ Bấm nút [Khoang hành khách] → photo_type=passenger, seq++
       ├─ Bấm nút [Ảnh xe mới] → photo_type=new_vehicle, seq++
       │
       └─ Bấm [XEM LẠI ẢNH] → ReviewActivity
            ├─ Hiển thị thumbnail từng nhóm
            ├─ [Xoá] → DELETE /api/photos
            ├─ [Chụp lại] → Quay lại CameraActivity, ghi đè
            └─ [Thêm] → Chụp thêm seq++
```

### 3.6. Xử lý lỗi mạng & Retry

| Tình huống | Xử lý |
|---|---|
| Mất kết nối khi chụp | Lưu ảnh tạm vào bộ nhớ điện thoại (thư mục `pending/`). Hiện badge đỏ "⏳ 3 ảnh chờ gửi" |
| Kết nối phục hồi | Tự động retry gửi ảnh đang chờ (background worker) |
| Server không phản hồi | Hiện thông báo "❌ Không kết nối được máy chủ. Kiểm tra mạng Wi-Fi hoặc Tailscale" |
| Toggle danh sách TẮT | Bỏ qua hoàn toàn API `/vehicles/today`, chỉ nhập tay |

### 3.7. Giao diện — Nguyên tắc thiết kế

| Tiêu chí | Giá trị |
|---|---|
| Theme | Material 3 Light — nền `#FFFFFF`, text `#1A1A1A` |
| Font biển số | 24sp Bold Monospace |
| Font tiêu đề | 20sp Bold |
| Font nút bấm | 18sp Medium |
| Chiều cao nút | Tối thiểu 56dp |
| Màu nút chính | `#1565C0` (Blue 800) + text trắng |
| Trạng thái ✅ | Nền `#E8F5E9` (Green 50) + viền `#4CAF50` |
| Trạng thái ⬜ | Nền `#F5F5F5` (Grey 100) + viền `#BDBDBD` |
| Haptic feedback | Rung nhẹ 50ms khi chụp thành công |

---

## 4. BUILD & DEPLOY

### 4.1. GitHub Actions Workflow

**Repo:** `vtpham87/inspection-camera`

**File:** `.github/workflows/build-apk.yml`

```yaml
name: Build APK
on:
  push:
    branches: [main]
    paths: ['android/**']
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v4
      - name: Build Debug APK
        working-directory: android
        run: ./gradlew assembleDebug
      - name: Upload APK
        uses: actions/upload-artifact@v4
        with:
          name: inspection-camera-debug
          path: android/app/build/outputs/apk/debug/app-debug.apk
```

### 4.2. Cài đặt Backend trên máy tính trạm

```bash
cd D:\inspection-camera\backend
pip install -r requirements.txt
python main.py
# Chạy trên http://0.0.0.0:8095
```

Có thể đăng ký Windows Service hoặc thêm vào Task Scheduler khởi động cùng máy tính.

### 4.3. Cài đặt APK trên điện thoại

1. Tải file `app-debug.apk` từ GitHub Actions artifacts
2. Copy vào điện thoại Android qua USB / Zalo / Email
3. Mở file → Cho phép cài đặt từ nguồn không xác định → Cài đặt
4. Mở app → Vào Cài đặt → Nhập IP máy tính trạm → Kiểm tra kết nối → Bắt đầu sử dụng

---

## 5. BẢO MẬT

| Mối nguy | Biện pháp |
|---|---|
| Truy cập trái phép API | API chỉ lắng nghe trên mạng nội bộ LAN + Tailscale (không mở public) |
| Upload file độc hại | Chỉ chấp nhận file JPEG, kiểm tra magic bytes, giới hạn 10MB/file |
| Tấn công path traversal | Chuẩn hóa biển số (chỉ giữ A-Z 0-9), không cho phép `../` trong tên file |
| Dữ liệu cấu hình | `photo_config.json` chỉ đọc/ghi cục bộ, không expose qua internet |

---

## 6. PHIÊN BẢN VÀ MỞ RỘNG TƯƠNG LAI

### v1.0.0 (Bản phát hành đầu tiên)
- ✅ 5 nút chụp chuẩn với đặt tên file tự động
- ✅ Danh sách xe hôm nay (toggle bật/tắt)
- ✅ Cấu hình đường dẫn lưu riêng từng nút
- ✅ In thời gian chụp lên ảnh (tuỳ chỉnh định dạng, font, vị trí; bật/tắt)
- ✅ Xem lại / xoá / chụp lại ảnh
- ✅ Hỗ trợ LAN + Tailscale
- ✅ Retry tự động khi mất mạng

### v1.1.0 (Dự kiến mở rộng)
- 🔲 Watermark bổ sung (biển số, GPS lên góc ảnh)
- 🔲 Quét biển số bằng OCR camera (tự nhận dạng, không cần gõ)
- 🔲 Thông báo đẩy khi có xe mới vào dây chuyền
- 🔲 Đồng bộ ảnh với hệ thống gcn-uploader (tự đẩy lên Cục ĐKVN)
