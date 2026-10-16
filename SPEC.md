# ĐẶC TẢ KỸ THUẬT: ỨNG DỤNG CHỤP ẢNH KIỂM ĐỊNH XE CƠ GIỚI

**Dự án:** inspection-camera
**Trạm:** Trung tâm Đăng kiểm xe cơ giới 15-07D Hải Phòng
**Phiên bản tài liệu:** 2.1 (đồng bộ app Android `v1.6.0`, backend `1.6.0`)
**Cập nhật:** 2026-10-14

> Tài liệu này là **nguồn sự thật duy nhất** cho API, cấu hình, quy tắc đặt tên, cơ chế đồng bộ.
> `README.md` chỉ hướng dẫn cài đặt/vận hành và trỏ về đây. Lịch sử phiên bản: `CHANGELOG.md`.
> Hạng mục chưa xác nhận với mã nguồn hoặc còn là đề xuất: xem mục 8.

## Mục lục

1. [Tổng quan](#1-tổng-quan)
2. [Chuẩn hóa & đặt tên](#2-chuẩn-hóa--đặt-tên)
3. [Backend API](#3-backend-api-fastapi-cổng-8095)
4. [Cấu hình `photo_config.json`](#4-cấu-hình-photo_configjson)
5. [App Android](#5-app-android)
6. [Build & Deploy](#6-build--deploy)
7. [Bảo mật](#7-bảo-mật)
8. [Tồn đọng & Đề xuất](#8-tồn-đọng--đề-xuất)

---

## 1. TỔNG QUAN

### 1.1. Mục tiêu
Ứng dụng Android (`.apk`) cho đăng kiểm viên (ĐKV) chụp ảnh xe tại dây chuyền. Ảnh được đặt tên chuẩn theo biển số, đóng dấu thời gian và đồng bộ về máy tính trạm.

### 1.2. Thành phần

| Thành phần | Công nghệ | Vị trí |
|---|---|---|
| App Android | Kotlin, CameraX, Retrofit, WorkManager, Firebase RTDB, Material 3 | Điện thoại ĐKV |
| Backend API | FastAPI (Python ≥ 3.11), cổng `8095` | Máy tính trạm 15-07D |
| CSDL trung gian | SQLite WAL `C:\PTCGDB_Online\ptcgdb.db` (bảng `waiting_vehicles`) | Máy tính trạm |
| CSDL gốc | Access `Z:\DataPTCGDB\PT90_1507D.mdb` (chỉ đọc, bảng tạm `tmp_DangKyKD`) | Máy chủ trạm |
| Đồng bộ thời gian thực | Firebase Realtime Database, cụm 4 node | Cloud |
| Build APK | GitHub Actions, repo `vtpham87/inspection-camera` | GitHub |

Cấu trúc thư mục backend:

```
D:\inspection-camera\backend\
├── main.py              # FastAPI app
├── config.py            # Đọc/ghi photo_config.json
├── photo_handler.py     # Đặt tên & lưu ảnh
├── vehicle_service.py   # Danh sách xe (waiting_vehicles)
├── firebase_sync.py     # Đồng bộ cụm Firebase RTDB
├── start_background.vbs # Chạy ngầm, chống chạy trùng
├── photo_config.json    # Cấu hình (tự sinh)
└── requirements.txt
```

### 1.3. Kênh kết nối

Có 3 kênh. **Firebase RTDB là kênh chính** (trao đổi danh sách xe, nhận ảnh qua `inbox_photos`; chạy trên Wi-Fi và 4G, không cần NAT). **HTTP là kênh dự phòng**, gồm 2 đường:

1. **HTTP LAN:** `http://192.168.193.11:8095`
2. **HTTP Tailscale:** `http://100.81.114.84:8095`

Cài đặt app có công tắc bật/tắt riêng cho LAN và Tailscale. Giữa hai đường HTTP, app ưu tiên LAN khi có, chuyển Tailscale khi ở ngoài bãi.

**Cụm 4 node Firebase RTDB:**

| Node | Instance | Lịch (app) |
|---|---|---|
| Node 1 | `ttdk-1507d-default-rtdb` | Thứ 2, Thứ 5 |
| Node 2 | `ttdk-1507d-p2-default-rtdb` | Thứ 3, Thứ 6 |
| Node 3 | `ttdk-1507d-p3-default-rtdb` | Thứ 4, Thứ 7 |
| Node Backup | `ttdk-1507d-bk-default-rtdb` | Failover khi một node chính lỗi/hết quota |

- **App (`FirebaseManager.kt`):** chọn node theo thứ trong tuần: `ALL_NODES.firstOrNull { it.activeDays.contains(dayOfWeek) } ?: NODE_1`. Chủ nhật không thuộc lịch node nào nên **fallback về Node 1**.
- **Failover (app):** khi tải ảnh qua `uploadPhotoToInbox`, nếu node theo lịch lỗi hoặc quá thời gian chờ **12 giây**, app tự chuyển sang Node Backup, không cần ĐKV can thiệp.
- **Backend (`firebase_sync.py`):** `get_active_nodes_for_today()` broadcast dữ liệu lên **cả 4 node**, và worker lắng nghe `inbox_photos` trên cả 4 node, nên dữ liệu thông suốt dù app dùng Node 1 hay chuyển sang Node Backup.

Backend có worker nền 5 giây (xem 3.7).

---

## 2. CHUẨN HÓA & ĐẶT TÊN

### 2.1. Làm sạch biển số
- Bỏ `-`, `.`, khoảng trắng; chuyển IN HOA. VD `15A-123.45` → `15A12345`.
- Chỉ giữ `A-Z 0-9` (chống path traversal).

### 2.2. Nhận diện loại biển & mã màu
Hàm `should_omit_color_suffix` (`photo_handler.py` và `PlateUtil.kt`) quyết định biển có hậu tố màu hay không.

**Mã màu** nằm ở **ký tự cuối** của `biendk_clean`: `T` Trắng, `V` Vàng, `X` Xanh (biển mới, 5 số).

**Trường hợp không có màu** (`plate_color = null`, tuyệt đối không mang hậu tố màu):
- **Biển cũ 4 số** (không kết thúc bằng 5 chữ số, VD `11K2639`) → `11K2639.jpg`, `bs11K2639.jpg`.
- **Sê-ri đặc biệt** `KT` (quân đội làm kinh tế), `LD` (liên doanh), `HC` (hành chính/công vụ), VD `15KT12345`, `15LD12345`, `15HC12345`: khớp regex `^\d{2}(?:KT|LD|HC)` → `15KT12345.jpg`, `bs15KT12345.jpg`.

**Biển 5 số dân sự thông thường** (kết thúc `\d{5}$`): nếu không chỉ định màu thì mặc định Trắng (`T`).

**Giao diện Android:** từ `v1.3.2` đã bỏ tùy chọn "không màu"; app và backend tự nhận diện các trường hợp loại trừ ở trên. ĐKV chỉ chọn Trắng/Vàng/Xanh cho biển 5 số thông thường.

```python
def extract_plate_color(biendk_clean: str) -> tuple[str, str | None]:
    """'15A12345T' -> ('15A12345', 'T');  '11K2639' -> ('11K2639', None)"""
    s = (biendk_clean or "").strip().upper()
    if should_omit_color_suffix(s):
        return s, None
    if s and s[-1] in ("T", "V", "X"):
        return s[:-1], s[-1]
    return s, None
```

### 2.3. Biến dùng trong tên file và đường dẫn
- `{date}` → `YYYYMMDD` (VD `20260927`), dùng trong `paths.*`
- `{plate}` → biển số chuẩn hóa, không màu (VD `15A12345`), dùng trong `paths.*` và tên file
- `{color}` → mã màu `T`/`V`/`X`, chỉ dùng trong tên file; rỗng nếu `plate_color = null` hoặc `plate_color_suffix = false`

### 2.4. Tên file

| `photo_type` | Tên nút trên app | Lần 1 (`lan_kd=1`) | Lần 2 (`lan_kd=2`) | Thư mục |
|---|---|---|---|---|
| `rear_45` | Ảnh phương tiện (góc 45° phía sau xe) | `{plate}{color}.jpg` | `{plate}{color}L2.jpg` | `paths.rear_45` |
| `front_45` | Ảnh biển số (góc 45° phía trước, thấy biển số) | `bs{plate}{color}.jpg` | `bs{plate}{color}L2.jpg` | `paths.front_45` |
| `chassis` | Số khung / Khoang máy | `sk_{plate}.jpg` | `sk_{plate}L2.jpg` | `paths.chassis` |
| `passenger` | Khoang người lái (nhiều tấm, tự tăng `seq`) | `{plate}_{seq}.jpg` | `{plate}L2_{seq}.jpg` | `paths.passenger` |
| `new_vehicle` | Xe mới / Sau miễn KĐ (nhiều tấm, tự tăng `seq`) | `{plate}_{seq}.jpg` | `{plate}L2_{seq}.jpg` | `paths.new_vehicle` |

(Tên nút: `PhotoType.kt`, `strings.xml`.)

Ví dụ biển mới trắng `15A12345T`: `15A12345T.jpg`, `bs15A12345T.jpg`, `sk_15A12345.jpg`, `15A12345_1.jpg`; Lần 2: `15A12345TL2.jpg`, `bs15A12345TL2.jpg`, `sk_15A12345L2.jpg`, `15A12345L2_1.jpg`.
Ví dụ biển cũ `11K2639`: `11K2639.jpg`, `bs11K2639.jpg`, `sk_11K2639.jpg`, `11K2639L2.jpg`, `bs11K2639L2.jpg`.

Quy tắc (đã chốt):
- **Chỉ `rear_45` và `front_45` có hậu tố màu**, ở cả Lần 1 và Lần 2. `chassis`, `passenger`, `new_vehicle` **không bao giờ** có hậu tố màu.
- Hậu tố `{color}` chỉ thêm khi `plate_color_suffix = true` **và** `plate_color` khác `null`.
- Lần 2 **không ghi đè** ảnh Lần 1 trong cùng thư mục.
- `passenger` / `new_vehicle` có thể lưu trong thư mục con `{plate}` (xem `paths`).

### 2.5. `seq` và Lần 2
`seq` chỉ áp dụng cho `passenger`, `new_vehicle` (`rear_45`, `front_45`, `chassis` không dùng):
- `seq` bỏ trống (`null`): server (có khoá luồng `_SEQ_LOCK`) quét thư mục đích theo mẫu `{plate}_(\d+).jpg` (Lần 2: `{plate}L{lan_kd}_(\d+).jpg`) và lấy `max + 1`; thư mục chưa có ảnh thì `seq = 1`.
- `seq` là `0` hoặc rỗng khi dựng tên file được coi là `1`.
- `seq` truyền tường minh (VD khi "Chụp lại") thì dùng đúng số đó.

App tự gợi ý `Lần 2` cho xe đã có ảnh Lần 1 hoặc trượt Lần 1 trong ngày; ĐKV đổi `Lần 1 ⇄ Lần 2` ở màn hình chính hoặc màn hình Camera.

---

## 3. BACKEND API (FastAPI, cổng 8095)

### 3.1. Quy ước phản hồi lỗi
Mọi lỗi trả JSON `{"ok": false, "error": "<mô tả>"}`.

| Mã | Khi nào |
|---|---|
| 400 | Thiếu/sai tham số, biển số không hợp lệ |
| 409 | File đích đã tồn tại và không được ghi đè |
| 413 | Vượt giới hạn dung lượng |
| 415 | Không phải JPEG |
| 500 | Lỗi ghi đĩa/CSDL |

### 3.2. `POST /api/upload`
`multipart/form-data`

| Field | Kiểu | Bắt buộc | Mô tả |
|---|---|---|---|
| `file` | JPEG | ✅ | Ảnh đã in timestamp (nếu bật). Tối đa 10MB (vượt → `413`) |
| `plate` | string | ✅ | Biển số, VD `15A12345` hoặc `15A-123.45` |
| `plate_color` | string | ❌ | `T` / `V` / `X`; trống hoặc `null` với biển cũ |
| `photo_type` | string | ✅ | `rear_45` / `front_45` / `chassis` / `passenger` / `new_vehicle` |
| `seq` | int | ❌ | Chỉ `passenger`/`new_vehicle`; bỏ trống → server tự cấp `max + 1` (xem 2.5) |
| `lan_kd` | int | ❌ | `1` (mặc định) hoặc `2` |

Server kiểm tra magic bytes JPEG, ghi file tạm rồi đổi tên (`os.replace`) để tránh file dở khi mất kết nối ổ mạng.

Phản hồi `200`:
```json
{ "ok": true, "path": "Z:\\Anh Phuong Tien\\15A12345T.jpg", "filename": "15A12345T.jpg" }
```

### 3.3. `DELETE /api/photos`
`application/json`
```json
{ "plate": "15A12345", "plate_color": "T", "photo_type": "rear_45", "seq": null, "lan_kd": 1 }
```
`plate_color`, `seq`, `lan_kd` tuỳ chọn (mặc định `null`, `null`, `1`); cần để xác định đúng tên file theo 2.4.

Phản hồi `200`:
```json
{ "ok": true, "deleted": "Z:\\Anh Phuong Tien\\15A12345T.jpg" }
```

### 3.4. `GET /api/vehicles/today`
Query: `date` (`YYYY-MM-DD`, mặc định hôm nay).

Nguồn: bảng `waiting_vehicles` (SQLite WAL, phản hồi 9–14ms), được worker đồng bộ từ `tmp_DangKyKD` (5 giây khi có xe chờ; giãn ra `PTCGDB_IDLE_SYNC_SEC`, mặc định 10 giây, khi Access trả rỗng; lỗi thì tăng dần tới 60 giây). Sắp xếp theo số phiếu tăng dần. Trường `photos_taken` được tính bằng cách kiểm tra file thực tế trên đĩa.

```json
[
  {
    "ticket_num": "01",
    "sophieu": "01",
    "ticket_int": 1,
    "plate": "88D-001.41T",
    "plate_clean": "88D00141",
    "plate_color": "T",
    "vehicle_type": "Ô tô tải VAN",
    "brand": "SUZUKI",
    "owner": "Vũ Văn Chính",
    "time": "15:30",
    "result": 1,
    "sotem": "KD-1234567",
    "lan_kd": 1,
    "suggest_lan_2": false,
    "is_completed": true,
    "photos_taken": ["rear_45", "front_45"]
  }
]
```
- `plate_color` có thể `null` (biển cũ).
- `result` (từ cột `KetLuan` của Access, đồng bộ vào `waiting_vehicles`):

  | Giá trị | Ý nghĩa |
  |---|---|
  | `1` | Đạt |
  | `0` | Không đạt (trượt) |
  | `2` | Đang kiểm định (trạng thái trung gian trên dây chuyền) |
  | `-1` hoặc `null` | Chờ kiểm định / chưa có kết luận |

- `sotem`: số seri tem kiểm định (cột `SoSeri` của Access, qua `waiting_vehicles`), cấp sau khi in GCN (VD `KD-xxxxxxx`); đồng bộ lên Firebase RTDB. Chưa cấp tem → chuỗi rỗng `""`.
- Cách app dùng `result` và `sotem` để ẩn/dọn xe: xem 5.8.
- Ngày nghỉ không có lượt kiểm → `[]`.

### 3.5. `GET /api/config` / `POST /api/config`
GET trả toàn bộ `photo_config.json`. POST nhận cùng cấu trúc, ghi đĩa, trả:
```json
{ "ok": true, "message": "Đã lưu cấu hình" }
```

### 3.6. `GET /api/health`
```json
{ "ok": true, "server": "15-07D Photo Server", "version": "<backend_version>", "time": "2026-09-27T19:50:00+07:00" }
```
`version` là phiên bản backend, độc lập với phiên bản app (giá trị hiện tại ghi ở đầu tài liệu).

### 3.7. Xử lý nền & hành vi phụ trợ

**Worker nền 5 giây:**
- Kéo ảnh từ `inbox_photos` (cả 4 node Firebase).
- Đẩy danh sách xe hôm nay lên RTDB.
- Đồng bộ `tmp_DangKyKD` → `waiting_vehicles`. **Circuit Breaker** tự ngắt kết nối Access khi file bị khoá hoặc mất ổ `Z:`, bảo vệ CSDL phần mềm kiểm định.

**Bản sao ảnh xe mới (`sync_new_vehicle_photos`):** khi lưu ảnh `new_vehicle` và `sync_new_vehicle_45: true` (mặc định bật), backend sao chép ảnh `rear_45` (`{plate}{color}.jpg`) và `front_45` (`bs{plate}{color}.jpg`) từ `paths.rear_45`/`paths.front_45` vào thư mục `paths.new_vehicle` của xe đó (VD `Z:\Anh sau cap mien\{plate}\`), để trọn bộ hồ sơ xe mới / sau miễn KĐ nằm một nơi.

---

## 4. CẤU HÌNH `photo_config.json`

```json
{
  "vehicle_list_enabled": true,
  "server_port": 8095,
  "paths": {
    "rear_45": "Z:\\Anh Phuong Tien",
    "front_45": "Z:\\Anh Phuong Tien",
    "chassis": "Z:\\Anh Phuong Tien",
    "passenger": "Z:\\Anh Khoang HK CCCD\\{plate}",
    "new_vehicle": "Z:\\Anh sau cap mien\\{plate}"
  },
  "jpeg_quality": 85,
  "plate_color_suffix": true,
  "sync_new_vehicle_45": true,
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
| `vehicle_list_enabled` | Bật/tắt danh sách xe | `true` |
| `server_port` | Cổng dịch vụ | `8095` |
| `paths.*` | Thư mục lưu theo loại ảnh; hỗ trợ `{date}`, `{plate}` | xem trên |
| `jpeg_quality` | 1-100 | `85` |
| `plate_color_suffix` | Thêm `{color}` vào tên file `rear_45`/`front_45` (theo 2.4) | `true` |
| `sync_new_vehicle_45` | Sao chép ảnh `rear_45`/`front_45` vào thư mục xe mới (xem 3.7) | `true` |
| `timestamp.enabled` | In thời gian lên ảnh | `true` |
| `timestamp.format` | Mẫu thời gian | `HH:mm:ss - dd/MM/yyyy` |
| `timestamp.font_size` | Cỡ chữ, tính bằng pixel trên ảnh | `28` |
| `timestamp.font_color` | HEX | `#FFFFFF` |
| `timestamp.font_bold` | Chữ đậm | `true` |
| `timestamp.font_stroke_enabled` | Viền chữ | `true` |
| `timestamp.font_stroke_color` | HEX | `#000000` |
| `timestamp.font_stroke_width` | Độ dày viền (px) | `2.0` |
| `timestamp.background_color` | HEX + alpha | `#80000000` |
| `timestamp.position` | Xem bảng dưới | `bottom_right` |
| `photo_resolution` | Xem bảng dưới | `original` |

**`photo_resolution`:** backend chuẩn hóa giá trị qua `RESOLUTION_MAP`; app có `normalizeResolution()` khớp 1:1.

| Giá trị chuẩn | Kích thước | Giá trị khác được chấp nhận |
|---|---|---|
| `original` | Giữ nguyên độ phân giải camera | |
| `high` | 3840 × 2160 | `4k` |
| `medium` | 1920 × 1080 | `fhd`, `1080p` |
| `low` | 1280 × 720 | `hd`, `720p` |

Giá trị mặc định cục bộ của app khi chưa đồng bộ cấu hình là `low`; sau khi kết nối (Firebase/API) app áp dụng đúng giá trị server (hiện `original`).

Khi khác `original`, ảnh được resize (giữ tỉ lệ) **trước** khi in timestamp và upload.

**`timestamp.position`:** `top_left`, `top_right`, `top_center`, `bottom_left`, `bottom_right`, `bottom_center`.

**Lưu ý đường dẫn:** ổ `Z:\` là ổ mạng ánh xạ, chỉ tồn tại trong phiên đăng nhập user. Nếu chạy backend bằng service/Task Scheduler khi chưa đăng nhập, dùng đường dẫn UNC (`\\server\share\...`).

---

## 5. APP ANDROID

### 5.1. Cấu trúc thư mục

```
android/
├── app/src/main/
│   ├── java/com/ttdk1507d/inspectioncamera/
│   │   ├── MainActivity.kt        # Màn 1: chọn xe, danh sách xe (Đang chờ / Đã chụp)
│   │   ├── CameraActivity.kt      # Màn 2: chụp ảnh, preview CameraX, bù sáng EV, Torch
│   │   ├── ReviewActivity.kt      # Màn 3: xem lại ảnh, xóa ảnh, chụp lại
│   │   ├── SettingsActivity.kt    # Màn 4: cài đặt, kiểm tra cụm Firebase, dọn cache
│   │   ├── InspectionCameraApp.kt # Application class
│   │   ├── api/                   # ApiService.kt, ApiClient.kt
│   │   ├── firebase/              # FirebaseManager.kt (cụm 4 node RTDB & failover)
│   │   ├── model/                 # Vehicle.kt, PhotoType.kt, AppConfig.kt, BackupConfig.kt
│   │   ├── adapter/               # VehicleAdapter.kt, PhotoReviewAdapter.kt
│   │   ├── util/                  # CacheManager.kt, NetworkUtil.kt, PlateUtil.kt, PrefsManager.kt, TimestampPainter.kt
│   │   └── worker/                # PendingUploadWorker.kt (WorkManager đồng bộ offline & dọn cache)
│   ├── res/                       # layout, values (colors/strings/themes), drawable
│   └── AndroidManifest.xml
├── app/build.gradle.kts, build.gradle.kts, settings.gradle.kts
└── gradle/
```
Worker nền đồng bộ ảnh offline duy nhất của app là **`PendingUploadWorker.kt`** (package `com.ttdk1507d.inspectioncamera.worker`), kế thừa `CoroutineWorker`. Không sử dụng tên `SyncWorker`.

### 5.2. Yêu cầu

| Tham số | Giá trị |
|---|---|
| `minSdk` / `targetSdk` / `compileSdk` | 24 / 34 / 34 |
| Package | `com.ttdk1507d.inspectioncamera` |
| Tên app | `Chụp ảnh KĐ 15-07D` |

### 5.3. Dependencies chính

```kotlin
implementation("androidx.camera:camera-camera2:1.3.4")
implementation("androidx.camera:camera-lifecycle:1.3.4")
implementation("androidx.camera:camera-view:1.3.4")
implementation("com.squareup.retrofit2:retrofit:2.11.0")
implementation("com.squareup.retrofit2:converter-gson:2.11.0")
implementation("com.squareup.okhttp3:okhttp:4.12.0")
implementation("com.google.android.material:material:1.12.0")
implementation("androidx.recyclerview:recyclerview:1.3.2")
implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
implementation("io.coil-kt:coil:2.7.0")
// Còn dùng thêm (chưa ghi phiên bản, xem mục 8): Firebase Realtime Database, WorkManager, kotlinx-coroutines
```

### 5.4. Permissions & cấu hình mạng

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.VIBRATE" />
<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" /> <!-- cài APK cập nhật in-app -->
```
Backend dùng `http://` (không TLS): targetSdk 34 chặn cleartext mặc định, cần `android:usesCleartextTraffic="true"` hoặc `networkSecurityConfig` cho IP LAN/Tailscale (Tailscale dùng dải `100.64.0.0/10`, không khai báo theo CIDR được).

### 5.5. Luồng chính

```
Mở app → đọc cấu hình cục bộ (IP/port, toggle LAN/Tailscale, toggle danh sách xe, đường dẫn)
 ├─ Màn chính có 2 tab: "Đang chờ" (xe chưa có kết luận) và "Đã chụp" (chỉ xe đã có ảnh, mới nhất lên đầu)
 ├─ [Danh sách BẬT] tải danh sách xe → chạm chọn → CameraActivity
 │     (tự làm mới ngầm mỗi 2 phút; kéo để làm mới (pull-to-refresh) kết nối lại; sắp xếp số phiếu tăng dần)
 ├─ [Danh sách TẮT hoặc lỗi mạng] nhập biển số + màu biển + Lần 1/2 → [Chụp] → CameraActivity
 └─ CameraActivity
      ├─ CameraX (camera sau, live preview); nút đèn pin (Torch) và bù sáng EV cho xe chụp trong gầm/tối
      ├─ 5 nút chụp (tên nút: bảng 2.4)
      │    chụp JPEG → resize theo photo_resolution → vẽ timestamp (TimestampPainter) → nén JPEG (jpeg_quality)
      │    → lưu cục bộ trên điện thoại (chưa gửi)
      ├─ [Xem lại ảnh] → ReviewActivity: thumbnail theo nhóm · [Xoá] · [Chụp lại] (ghi đè) · [Thêm] (seq++)
      └─ [HOÀN THÀNH - VỀ TRANG ĐẦU] → mới gửi toàn bộ ảnh của xe (Firebase inbox_photos / POST /api/upload)
            ✅ + rung 50ms khi thành công; thất bại → giữ trong `pending/` để retry (5.6)
```

**Upload chỉ xảy ra khi bấm "HOÀN THÀNH - VỀ TRANG ĐẦU"** (từ v1.4.5), không gửi ngay sau mỗi lần chụp.

#### 5.5.1. Xoá ảnh trong màn hình Xem lại (Review)
- Xoá file ảnh cục bộ trong thư mục cache `review/` trên điện thoại.
- Quét và xoá file `.jpg` kèm file metadata `.meta` tương ứng trong `filesDir/pending/` nếu ảnh đang nằm trong hàng đợi chờ upload.
- Gửi `DELETE /api/photos` (3.3) để xoá file trên ổ đĩa mạng máy chủ trạm (nếu ảnh đã được tải lên trước đó).

### 5.6. Offline & Retry

| Tình huống | Xử lý |
|---|---|
| Mất kết nối khi chụp | Lưu ảnh vào `filesDir/pending/`; badge đỏ "⏳ N ảnh chờ gửi"; nút chụp hiện `✓ Offline` |
| Kết nối phục hồi | Worker nền (WorkManager) gửi lại theo thứ tự và dọn file tạm |
| Server không phản hồi | "❌ Không kết nối được máy chủ. Kiểm tra mạng Wi-Fi hoặc Tailscale" |
| Danh sách xe TẮT | Bỏ qua `/api/vehicles/today`, chỉ nhập tay |

### 5.7. Giao diện

| Tiêu chí | Giá trị |
|---|---|
| Theme | Material 3 Light, nền `#FFFFFF`, chữ `#1A1A1A` |
| Font biển số / tiêu đề / nút | 24sp Bold Monospace / 20sp Bold / 18sp Medium |
| Chiều cao nút | ≥ 56dp |
| Nút chính | `#1565C0` + chữ trắng |
| Trạng thái ✅ | Nền `#E8F5E9`, viền `#4CAF50` |
| Trạng thái ⬜ | Nền `#F5F5F5`, viền `#BDBDBD` |
| Danh sách xe | 1 dòng/xe: biển lớn + badge màu + badge Lần 2 |
| Màn chọn biển | Ô nhập + nút Chụp cùng hàng; màu biển một hàng; Lần 1/Lần 2 hàng riêng bên dưới |

### 5.8. Hiển thị tab & dọn dẹp ảnh cục bộ

**Ẩn khỏi tab "Đang chờ":** xe có `result` là `1` hoặc `0` (xong lượt kiểm định hiện tại).

**Dọn dẹp** dựa trên `hasCertIssued() = !sotem.isNullOrBlank()` (xem 3.4):
1. **Xe đã cấp tem** (`sotem` không rỗng): đã hoàn tất quy trình. Ảnh trong `review/` tự xoá sau **15 phút** kể từ lúc tải lên thành công. Khi `localCount = 0`, xe ẩn khỏi tab "Đã chụp".
2. **Xe chưa cấp tem** (chờ kết luận, trượt `result = 0`, hoặc đã chụp đủ nhưng chưa in tem): **không** bị ẩn; giữ ảnh suốt ca, vẫn hiển thị ở "Đã chụp" kèm số lượng ảnh để chụp bổ sung/đối soát.
3. Worker nền và sự kiện mở danh sách kích hoạt quét định kỳ; xoá file đệm quá hạn và thư mục rỗng.

---

## 6. BUILD & DEPLOY

### 6.1. GitHub Actions (`.github/workflows/build-apk.yml`)

```yaml
name: Build APK
on:
  push:
    branches: [main]
    paths: ['android/**', '.github/workflows/**']
  workflow_dispatch:

concurrency:
  group: build-apk-${{ github.ref }}
  cancel-in-progress: true

permissions:
  contents: read

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { java-version: '17', distribution: 'temurin' }
      - uses: gradle/actions/setup-gradle@v4
      - name: Build APK
        working-directory: android
        run: |
          chmod +x gradlew
          ./gradlew assembleDebug
      - uses: actions/upload-artifact@v4
        with:
          name: inspection-camera-debug
          path: android/app/build/outputs/apk/debug/app-debug.apk
```

### 6.2. Backend trên máy trạm

```bash
cd D:\inspection-camera\backend
python -m venv venv
venv\Scripts\activate.bat
pip install -r requirements.txt
venv\Scripts\python main.py        # http://0.0.0.0:8095
```
Chạy ngầm và tự khởi động cùng Windows: xem `README.md`.

### 6.3. Cài APK
Tải artifact/Release → chép vào điện thoại → cho phép nguồn không xác định → cài → Cài đặt: nhập địa chỉ LAN/Tailscale → Kiểm tra kết nối. Chi tiết: `README.md`.

---

## 7. BẢO MẬT

| Mối nguy | Biện pháp hiện có |
|---|---|
| Truy cập trái phép API | Không mở public (backend bind `0.0.0.0`) |
| Upload độc hại | Chỉ JPEG, kiểm tra magic bytes, giới hạn dung lượng |
| Path traversal | Chuẩn hóa biển số (chỉ A-Z 0-9) |
| Cấu hình | `photo_config.json` đọc/ghi cục bộ |
| Firebase | (chưa ghi nhận) |

Đề xuất tăng cường: xem mục 8.

---

## 8. TỒN ĐỌNG & ĐỀ XUẤT

**Cần đối chiếu với mã nguồn:**
- Biển ngoại giao/quân sự (`NG`/`QT`...): changelog v1.3.x có nhắc "chuẩn hóa biển ngoại giao/quân sự" nhưng 2.2 chỉ liệt kê `KT`/`LD`/`HC`. Xác nhận danh sách sê-ri trong `should_omit_color_suffix`.
- Kích hoạt worker offline: README cũ ghi "chụp thêm một ảnh để kích hoạt worker", mâu thuẫn với quy tắc upload chỉ khi bấm HOÀN THÀNH (5.5). Xác nhận hành vi thực tế; hiện README chỉ còn "Thử đồng bộ lại".
- Mã lỗi `404` cho `DELETE /api/photos` khi không có file: chưa được định nghĩa ở 3.1.
- Dependencies 5.3: bổ sung phiên bản Firebase RTDB, WorkManager, kotlinx-coroutines.
- Mục 7, Firebase: ghi rõ Security Rules.

**Đề xuất:**
- Phản hồi lỗi: FastAPI mặc định trả `{"detail": ...}`, cần exception handler để đúng quy ước 3.1.
- Bảo mật API: giới hạn firewall theo subnet LAN và interface Tailscale; thêm header API key.
- Firebase: không commit khoá/`google-services.json` nhạy cảm nếu repo public.
- Ký APK: debug keystore trên runner GitHub sinh mới mỗi lần build nên chữ ký khác nhau, cập nhật đè bản cũ (kể cả in-app) có thể báo xung đột chữ ký. Dùng keystore cố định lưu trong GitHub Secrets, đặt `versionCode` theo `github.run_number`, tạo GitHub Release khi gắn tag để app kiểm tra cập nhật.
