# BÁO CÁO PHÂN TÍCH MÃ NGUỒN - DỰ ÁN 1507DCamera

**Ngày phân tích:** 07/10/2026  
**Phiên bản:** 1.4.5 (versionCode 30)  
**Phạm vi:** Backend (Python/FastAPI) + Android (Kotlin)

---

## MỤC LỤC

1. [BACKEND BUGS](#1-backend-bugs)
2. [ANDROID BUGS](#2-android-bugs)
3. [PERFORMANCE ISSUES](#3-performance-issues)
4. [SECURITY ISSUES](#4-security-issues)
5. [CODE QUALITY](#5-code-quality)
6. [OPTIMIZATION RECOMMENDATIONS](#6-optimization-recommendations)

---

## 1. BACKEND BUGS

### 🔴 CRITICAL

#### B-01: Race condition khi ghi file ảnh — `photo_handler.py`
- **File:** `backend/photo_handler.py`
- **Mô tả:** Hàm lưu ảnh không sử dụng file locking. Khi nhiều request cùng upload ảnh cho cùng biển số và loại ảnh, file có thể bị ghi đè hoặc corrupt do ghi đồng thời.
- **Tác động:** Mất ảnh kiểm định, ảnh bị hỏng trên ổ mạng `Z:\`.
- **Khắc phục:** Sử dụng `fcntl.flock()` (Linux) hoặc `msvcrt.locking()` (Windows) hoặc atomic write pattern (ghi file tạm rồi `os.rename()`).

#### B-02: Kết nối Access Database không đóng đúng cách — `vehicle_service.py`
- **File:** `backend/vehicle_service.py`
- **Mô tả:** Sử dụng `pyodbc` để kết nối MS Access nhưng không dùng context manager (`with`) cho connection và cursor. Nếu exception xảy ra giữa chừng, connection sẽ bị leak, dần dần dẫn đến hết connection pool hoặc lock file `.ldb` không được giải phóng.
- **Tác động:** Database bị lock, backend không truy vấn được danh sách phương tiện.
- **Khắc phục:** Bọc connection trong `try/finally` hoặc context manager, đảm bảo `conn.close()` luôn được gọi.

#### B-03: Firebase sync polling không có backoff — `firebase_sync.py`
- **File:** `backend/firebase_sync.py`
- **Mô tả:** Vòng lặp polling Firebase RTDB chạy liên tục. Khi Firebase trả lỗi (network, auth expired), vòng lặp sẽ spam request liên tục mà không có exponential backoff, có thể dẫn đến rate-limit hoặc tiêu tốn bandwidth.
- **Tác động:** Tiêu tốn quota Firebase, có thể bị block bởi Firebase rate limiter.
- **Khắc phục:** Thêm exponential backoff (bắt đầu 1s, tối đa 60s) khi gặp lỗi liên tiếp.

### 🟠 HIGH

#### B-04: Không validate kích thước base64 photo từ Firebase — `firebase_sync.py`
- **File:** `backend/firebase_sync.py`
- **Mô tả:** Backend nhận base64 string từ Firebase RTDB và decode mà không kiểm tra kích thước. Một ảnh base64 lớn bất thường có thể gây OOM (Out Of Memory) trên server.
- **Tác động:** Server crash do hết RAM khi nhận ảnh quá lớn.
- **Khắc phục:** Thêm size check: `if len(base64_data) > MAX_PHOTO_SIZE_B64: reject`.

#### B-05: Đường dẫn file không được sanitize — `photo_handler.py`
- **File:** `backend/photo_handler.py`
- **Mô tả:** Tên biển số (`plate`) được sử dụng trực tiếp để tạo đường dẫn thư mục lưu ảnh. Nếu biển số chứa ký tự đặc biệt (dấu `/`, `..`, null byte), có thể dẫn đến path traversal.
- **Tác động:** Ghi file ngoài thư mục mong muốn, tiềm ẩn RCE.
- **Khắc phục:** Sử dụng `os.path.basename()` và whitelist ký tự hợp lệ cho biển số (chữ, số, dấu chấm, gạch ngang).

#### B-06: Timestamp overlay dùng PIL có thể fail silently — `photo_handler.py`
- **File:** `backend/photo_handler.py`
- **Mô tả:** Nếu font file không tồn tại hoặc PIL không decode được ảnh JPEG bị hỏng, exception có thể bị nuốt hoặc xử lý không đúng, dẫn đến ảnh được lưu mà không có timestamp nhưng không có thông báo lỗi.
- **Tác động:** Ảnh kiểm định thiếu thông tin timestamp bắt buộc mà không ai biết.
- **Khắc phục:** Log rõ ràng khi font missing, validate ảnh trước khi xử lý.

### 🟡 MEDIUM

#### B-07: Endpoint `/config` trả về cấu hình nhạy cảm — `main.py`
- **File:** `backend/main.py`
- **Mô tả:** Endpoint GET `/config` trả về toàn bộ cấu hình ứng dụng mà không phân biệt field nào là nhạy cảm. Có thể vô tình expose đường dẫn nội bộ, Firebase credentials.
- **Khắc phục:** Tạo response schema riêng cho client, chỉ trả các field cần thiết (timestamp config, jpeg quality, photo resolution).

#### B-08: Không có retry logic cho ghi file lên ổ mạng — `photo_handler.py`
- **File:** `backend/photo_handler.py`
- **Mô tả:** Ghi file lên `Z:\` (ổ mạng) có thể fail do network hiccup. Hiện tại chỉ raise exception mà không retry.
- **Khắc phục:** Thêm retry 2-3 lần với delay 1s giữa các lần thử.

---

## 2. ANDROID BUGS

### 🔴 CRITICAL

#### A-01: Bitmap memory leak trong `CameraActivity.processAndSave()` — `CameraActivity.kt`
- **File:** `CameraActivity.kt`, dòng 562-601
- **Mô tả:** Hàm `processAndSave()` tạo 3 bitmap liên tiếp (`rawBitmap`, `resizedBitmap`, `stampedBitmap`) nhưng KHÔNG gọi `recycle()` cho bitmap trung gian. Trên thiết bị RAM thấp hoặc khi chụp liên tục, sẽ gây OOM crash.
- **Code vấn đề:**
  ```kotlin
  val rawBitmap = imageProxyToBitmap(imageProxy)  // Bitmap 1
  val resizedBitmap = TimestampPainter.resizeBitmap(rawBitmap, ...)  // Bitmap 2, rawBitmap KHÔNG recycle
  val stampedBitmap = TimestampPainter.paintTimestamp(resizedBitmap, ...)  // Bitmap 3, resizedBitmap KHÔNG recycle
  ```
- **Tác động:** App crash với `OutOfMemoryError` khi chụp nhiều ảnh liên tiếp.
- **Khắc phục:**
  ```kotlin
  val rawBitmap = imageProxyToBitmap(imageProxy)
  val resizedBitmap = TimestampPainter.resizeBitmap(rawBitmap, appConfig.photoResolution)
  if (resizedBitmap !== rawBitmap) rawBitmap.recycle()
  val stampedBitmap = if (appConfig.timestamp.enabled) {
      val stamped = TimestampPainter.paintTimestamp(resizedBitmap, appConfig.timestamp)
      if (stamped !== resizedBitmap) resizedBitmap.recycle()
      stamped
  } else resizedBitmap
  // ... sau khi compress xong:
  stampedBitmap.recycle()
  ```

#### A-02: `imageProxyToBitmap()` tạo bitmap thừa — `CameraActivity.kt`
- **File:** `CameraActivity.kt`, dòng 680-692
- **Mô tả:** Khi `rotationDegrees != 0`, hàm tạo bitmap mới qua `Bitmap.createBitmap()` nhưng bitmap gốc không được `recycle()`. Đây là memory leak, đặc biệt nghiêm trọng khi ảnh camera có resolution cao (12MP+).
- **Khắc phục:** Recycle bitmap gốc sau khi tạo bitmap đã xoay.

#### A-03: `triggerBackgroundUpload()` shadow variable và quét file trùng lặp — `CameraActivity.kt`
- **File:** `CameraActivity.kt`, dòng 630-677
- **Mô tả:** 
  1. Biến `pendingDir`, `metaFiles`, `gson` được khai báo lại (shadow) ở dòng 659-661, trùng với dòng 635-637
  2. Tham số `bytes` (JPEG data) được truyền vào nhưng KHÔNG sử dụng — thay vào đó hàm đọc lại file từ pending directory
  3. Nếu `saveToPendingQueue()` chưa flush xong (race condition giữa 2 coroutine), file pending chưa tồn tại khi `triggerBackgroundUpload()` tìm
- **Tác động:** Upload thất bại ngẫu nhiên, dữ liệu bytes bị giữ trong memory vô ích.
- **Khắc phục:** Upload trực tiếp từ `bytes` thay vì đọc lại file; bỏ shadow variable.

### 🟠 HIGH

#### A-04: 5 empty catch blocks nuốt exception — `ReviewActivity.kt`
- **File:** `ReviewActivity.kt`, dòng 199, 279, 319, 389, 409
- **Mô tả:** Tất cả 5 catch block đều không log lỗi, không thông báo người dùng:
  - **Dòng 199:** Meta file bị corrupt → bị bỏ qua im lặng
  - **Dòng 279:** Lỗi đọc pending meta → bỏ qua
  - **Dòng 319:** Upload thất bại → trả `false` không log
  - **Dòng 389:** Server delete thất bại → comment "If offline, proceed with local cleanup"
  - **Dòng 409:** Lỗi xóa meta → bỏ qua
- **Tác động:** Bug ẩn không thể debug. Đặc biệt dòng 389: xóa trên server thất bại nhưng file local bị xóa (dòng 394), gây mất đồng bộ.
- **Khắc phục:** Log tất cả exception qua `Log.w()`, hiển thị thông báo cho lỗi quan trọng (upload/delete thất bại).

#### A-05: Delete photo mất tính nhất quán — `ReviewActivity.kt`
- **File:** `ReviewActivity.kt`, dòng 369-417
- **Mô tả:** Khi delete photo:
  1. Gọi API server delete (dòng 385-387)
  2. Nếu server lỗi → catch rỗng (dòng 389-391)
  3. **Vẫn xóa file local** (dòng 394) bất kể server thành công hay thất bại
- **Tác động:** Ảnh bị mất trên client nhưng vẫn còn trên server, hoặc ngược lại.
- **Khắc phục:** Chỉ xóa local khi server xác nhận thành công, hoặc thêm flag "pending_delete" để retry.

#### A-06: `lanKd` chỉ hỗ trợ toggle 1↔2 — `CameraActivity.kt`
- **File:** `CameraActivity.kt`, dòng 241
- **Mô tả:** `lanKd = if (lanKd == 1) 2 else 1` — Logic này chỉ cho phép lần KĐ 1 hoặc 2. Nếu business logic yêu cầu lần 3+ (xe kiểm định lại nhiều lần), không thể chọn.
- **Khắc phục:** Đổi thành cycle: `lanKd = (lanKd % MAX_LAN_KD) + 1` hoặc dialog input số.

#### A-07: `Regex("\\\\d{5}$")` có thể sai — `CameraActivity.kt`, `ReviewActivity.kt`
- **File:** `CameraActivity.kt` dòng 133, `ReviewActivity.kt` dòng 84
- **Mô tả:** Regex `"\\\\d{5}$"` trong Kotlin string thực sự match literal `\\d{5}` (backslash + d) chứ không phải 5 chữ số. Regex đúng phải là `Regex("\\d{5}$")`. Tuy nhiên, vì dòng này nằm trong file text đã escape, cần xác nhận lại trong source gốc.
- **Tác động:** Nếu regex sai, điều kiện `plateColor = "T"` sẽ không bao giờ match, biển mới 5 chữ số cuối sẽ thiếu hậu tố màu.
- **Khắc phục:** Kiểm tra regex trong source gốc, đảm bảo pattern là `\d{5}$`.

### 🟡 MEDIUM

#### A-08: `refreshLocalPhotoStatus()` quét toàn bộ pending dir mỗi lần — `CameraActivity.kt`
- **File:** `CameraActivity.kt`, dòng 445-498
- **Mô tả:** Mỗi lần gọi (sau mỗi lần chụp, mỗi lần resume, mỗi lần đổi lanKd), hàm đọc và deserialize TẤT CẢ file `.meta` trong thư mục pending. Với nhiều ảnh chờ upload, gây lag UI.
- **Khắc phục:** Cache danh sách meta trong memory, chỉ cập nhật khi có thay đổi.

#### A-09: `ByteArrayOutputStream` không được đóng — `CameraActivity.kt`
- **File:** `CameraActivity.kt`, dòng 578-580
- **Mô tả:** `ByteArrayOutputStream` tạo ra không được `.close()` hoặc `.use {}`. Dù ByteArrayOutputStream.close() là no-op, đây là bad practice và IDE sẽ warning.
- **Khắc phục:** Bọc trong `baos.use { ... }`.

#### A-10: `vibrateSuccess()` unsafe cast — `CameraActivity.kt`
- **File:** `CameraActivity.kt`, dòng 697
- **Mô tả:** Dòng `val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager` dùng unsafe cast (`as`). Trên một số ROM custom, service có thể null → `ClassCastException`. So sánh với `vibrateTick()` (dòng 409) dùng safe cast (`as?`) đúng cách.
- **Khắc phục:** Đổi thành `as? VibratorManager` cho nhất quán.

---

## 3. PERFORMANCE ISSUES

### 🔴 CRITICAL

#### P-01: Base64 encoding ảnh trong Firebase RTDB — Kiến trúc tổng thể
- **Files:** `FirebaseManager.kt`, `firebase_sync.py`
- **Mô tả:** Ảnh JPEG (thường 500KB-2MB) được encode thành base64 (tăng 33% kích thước) rồi lưu vào Firebase Realtime Database. Đây là anti-pattern vì:
  1. RTDB giới hạn node size 10MB
  2. Base64 tăng payload 33%
  3. Đọc/ghi node chứa ảnh base64 chậm
  4. Tốn bandwidth gấp đôi (upload lên RTDB + download về backend)
- **Tác động:** Upload chậm, tốn quota Firebase, có thể hit limit 10MB/node.
- **Khắc phục:** Chuyển sang Firebase Storage (upload ảnh binary) → gửi download URL qua RTDB. Hoặc chuyển sang upload trực tiếp qua API HTTP nếu có mạng LAN.

### 🟠 HIGH

#### P-02: Bitmap full-resolution tồn tại trong memory — `CameraActivity.kt`
- **File:** `CameraActivity.kt`, dòng 562-601
- **Mô tả:** `imageProxyToBitmap()` decode ảnh camera ở full resolution (12MP = ~48MB ARGB_8888 bitmap), giữ trong memory trong suốt quá trình resize + stamp + compress. Với 3 bitmap cùng tồn tại (raw + resized + stamped), peak memory có thể đạt 100MB+.
- **Khắc phục:** Sử dụng `BitmapFactory.Options.inSampleSize` để decode trực tiếp ở resolution mong muốn.

#### P-03: Quét file `pending/` lặp lại nhiều lần — `CameraActivity.kt`, `ReviewActivity.kt`
- **Files:** `CameraActivity.kt` dòng 445-498 (`refreshLocalPhotoStatus`) và dòng 635-672 (`triggerBackgroundUpload`); `ReviewActivity.kt` dòng 178-261 (`loadPhotos`) và dòng 263-338 (`uploadPendingPhotos`)
- **Mô tả:** Mỗi module đều tự quét `pending/` dir, deserialize TẤT CẢ `.meta` file bằng Gson. Trong `triggerBackgroundUpload()`, quét 2 lần (dòng 636 và 660). Gson deserialization trên UI thread (trong `refreshLocalPhotoStatus`) gây jank.
- **Khắc phục:** Tạo `PendingUploadRepository` singleton quản lý state, cache metadata in-memory, chỉ cập nhật khi có thay đổi.

#### P-04: `vehicle_service.py` mở/đóng connection mỗi request — `vehicle_service.py`
- **File:** `backend/vehicle_service.py`
- **Mô tả:** Mỗi API call tới danh sách xe đều mở connection mới tới MS Access database, truy vấn, rồi đóng. MS Access file lock (`*.ldb`) tạo/xóa liên tục gây I/O overhead.
- **Khắc phục:** Sử dụng connection pool hoặc cache kết quả query (danh sách xe không thay đổi thường xuyên, cache 30s-60s).

### 🟡 MEDIUM

#### P-05: `PhotoType.values()` gọi lặp lại không cần thiết — Nhiều file
- **Files:** `CameraActivity.kt`, `ReviewActivity.kt`
- **Mô tả:** `PhotoType.values()` (tạo array mới mỗi lần gọi theo JVM spec) được gọi trong vòng lặp lồng nhau: `for (file in files) { for (pt in PhotoType.values()) { ... } }`. Dù overhead nhỏ, nên cache.
- **Khắc phục:** `val types = PhotoType.values()` hoặc `PhotoType.entries` (Kotlin 1.9+).

#### P-06: `Gson()` khởi tạo mới mỗi lần dùng — Nhiều file
- **Files:** `CameraActivity.kt` dòng 467, 623, 637, 661; `ReviewActivity.kt` dòng 187, 270, 400
- **Mô tả:** `Gson()` được tạo mới mỗi lần cần deserialize/serialize. Gson constructor có overhead (reflection setup).
- **Khắc phục:** Tạo companion object `private val gson = Gson()` trong mỗi class.

---

## 4. SECURITY ISSUES

### 🔴 CRITICAL

#### S-01: Firebase credentials có thể bị expose — `config.py`, `PrefsManager.kt`
- **Files:** `backend/config.py`, `PrefsManager.kt`
- **Mô tả:** Firebase service account key path được lưu trong config. Nếu config file bị commit lên Git hoặc endpoint `/config` trả về cho client, credentials sẽ bị lộ.
- **Khắc phục:** 
  1. Sử dụng environment variables cho credentials
  2. Thêm `config.py` vào `.gitignore`
  3. Endpoint `/config` không trả về credential-related fields

#### S-02: API endpoints không có authentication — `main.py`
- **File:** `backend/main.py`
- **Mô tả:** Tất cả endpoints (upload, delete, config, vehicle list) đều public, không yêu cầu authentication. Bất kỳ ai biết URL backend đều có thể:
  1. Upload ảnh giả
  2. Xóa ảnh thật
  3. Đọc danh sách phương tiện
  4. Thay đổi cấu hình
- **Tác động:** Dữ liệu kiểm định có thể bị xáo trộn hoặc xóa.
- **Khắc phục:** Thêm API key header hoặc JWT authentication. Ít nhất thêm shared secret key cho internal API.

#### S-03: Cloudflare tunnel URL hardcoded — `PrefsManager.kt`
- **File:** `PrefsManager.kt`
- **Mô tả:** `DEFAULT_CLOUD_URL` chứa URL Cloudflare tunnel. URL này expose backend ra internet. Kết hợp với S-02 (không có auth), bất kỳ ai biết URL đều truy cập được.
- **Khắc phục:** Cloudflare Access policy hoặc API authentication.

### 🟠 HIGH

#### S-04: Không validate input biển số — `main.py`, `photo_handler.py`
- **File:** `backend/main.py`, `backend/photo_handler.py`
- **Mô tả:** Biển số từ request được dùng trực tiếp trong đường dẫn file mà không validate. Ký tự đặc biệt (`../`, `\`, null byte) có thể bị inject.
- **Khắc phục:** Regex whitelist: `^[0-9A-Z.\-]{4,12}$`.

#### S-05: Delete endpoint không xác thực ownership — `main.py`
- **File:** `backend/main.py`
- **Mô tả:** Endpoint delete photo chỉ cần `plate` + `photo_type` + `seq` để xóa. Không có kiểm tra ai có quyền xóa, không có audit log.
- **Khắc phục:** Thêm auth + audit log cho mọi thao tác xóa.

### 🟡 MEDIUM

#### S-06: HTTP không mã hóa cho kết nối LAN — `NetworkUtil.kt`
- **File:** `NetworkUtil.kt`
- **Mô tả:** Kết nối LAN sử dụng HTTP (không phải HTTPS). Ảnh kiểm định truyền không mã hóa trên mạng nội bộ.
- **Tác động:** Có thể bị sniff nếu có người xâm nhập mạng LAN.
- **Khắc phục:** Self-signed cert cho LAN, hoặc chấp nhận risk cho mạng nội bộ khép kín.

---

## 5. CODE QUALITY

### CQ-01: Code duplication — Plate parsing logic
- **Files:** `CameraActivity.kt` dòng 124-135, `ReviewActivity.kt` dòng 75-86
- **Mô tả:** Logic parse plate (gọi `PlateUtil.parsePlate`, check `isOldPlate`, gán `plateColor` với fallback "T") được copy-paste giống hệt nhau ở 2 Activity. Thay đổi logic ở 1 nơi sẽ quên nơi kia.
- **Khắc phục:** Extract thành helper function `PlateUtil.resolveFullPlate(rawPlate, intentColor)` trả về data class.

### CQ-02: Code duplication — Pending directory scan
- **Files:** `CameraActivity.kt` dòng 464-480, `ReviewActivity.kt` dòng 183-201, 268-281
- **Mô tả:** Logic quét `pending/` dir, đọc `.meta` files, filter theo `plate`/`lanKd` được lặp lại 4 lần gần giống nhau.
- **Khắc phục:** Tạo `PendingUploadRepository` class dùng chung.

### CQ-03: FQN import thay vì import statement — `ReviewActivity.kt`
- **File:** `ReviewActivity.kt`, dòng 76, 78, 113, 168
- **Mô tả:** Sử dụng fully qualified name `com.ttdk1507d.inspectioncamera.util.PlateUtil.parsePlate(rawPlate)` thay vì import. Không thống nhất với `CameraActivity.kt` đã import `PlateUtil`.
- **Khắc phục:** Thêm `import com.ttdk1507d.inspectioncamera.util.PlateUtil` ở đầu file.

### CQ-04: Shadow variable `pendingDir`, `metaFiles`, `gson` — `CameraActivity.kt`
- **File:** `CameraActivity.kt`, dòng 635-637 vs 659-661
- **Mô tả:** Trong `triggerBackgroundUpload()`, biến `pendingDir`, `metaFiles`, `gson` được khai báo 2 lần trong cùng hàm (outer scope vs after `if (uploadOk)`). Gây confusing và IDE warning.
- **Khắc phục:** Tái sử dụng biến đã khai báo, không khai báo lại.

### CQ-05: Magic strings cho photo type — Nhiều file
- **Mô tả:** API name của photo type (e.g. "rear_45", "front_45") được truyền dưới dạng string giữa các component. Nếu đổi tên, phải tìm/thay ở nhiều nơi.
- **Khắc phục:** Đã có enum `PhotoType` nhưng nhiều chỗ vẫn dùng `type.apiName` string. Cần dùng enum consistently cả 2 phía.

### CQ-06: Backend thiếu type hints — `main.py`, `firebase_sync.py`
- **Files:** `backend/main.py`, `backend/firebase_sync.py`, `backend/photo_handler.py`
- **Mô tả:** Nhiều hàm Python thiếu type hints cho parameters và return type, làm giảm readability và IDE support.
- **Khắc phục:** Thêm type annotations cho tất cả hàm public.

### CQ-07: `saveToPendingQueue()` truyền tham số `bytes` không cần thiết — `CameraActivity.kt`
- **File:** `CameraActivity.kt`, dòng 630
- **Mô tả:** `triggerBackgroundUpload(type, seq, jpegBytes)` nhận `bytes` nhưng không sử dụng (đọc lại từ file). Tham số thừa gây confusing.
- **Khắc phục:** Bỏ tham số `bytes` hoặc sử dụng trực tiếp thay vì đọc lại file.

### CQ-08: Deprecated API usage — `CameraActivity.kt`
- **File:** `CameraActivity.kt`
- **Mô tả:** 
  - `PhotoType.values()` → nên dùng `PhotoType.entries` (Kotlin 1.9+)
  - `windowManager.defaultDisplay.rotation` (dòng 282) → deprecated từ API 30
  - `vibrator.vibrate(50)` (dòng 414, 707) → deprecated, đã có `VibrationEffect`
- **Khắc phục:** Cập nhật theo Android best practices hiện tại.

---

## 6. OPTIMIZATION RECOMMENDATIONS

### 🎯 ƯU TIÊN CAO (Nên làm ngay)

#### R-01: Chuyển từ Firebase RTDB base64 sang Firebase Storage
- **Hiện tại:** Ảnh → base64 → RTDB node (text) → backend poll → decode → save
- **Đề xuất:** Ảnh → Firebase Storage (binary upload) → RTDB chỉ chứa metadata + download URL → backend tải file trực tiếp
- **Lợi ích:** Giảm 33% bandwidth, không bị giới hạn 10MB/node, upload nhanh hơn nhiều
- **Effort:** Medium (2-3 ngày)

#### R-02: Implement Bitmap pool / recycle chain
- **Hiện tại:** 3 bitmap tồn tại đồng thời, không recycle
- **Đề xuất:** Recycle bitmap ngay sau khi không cần, sử dụng `inBitmap` option để tái sử dụng bộ nhớ
- **Lợi ích:** Giảm peak memory 60-70%, tránh OOM crash
- **Effort:** Low (1 ngày)

#### R-03: Thêm API authentication
- **Đề xuất:** Thêm middleware FastAPI kiểm tra API key trong header `X-API-Key`
- **Effort:** Low (0.5 ngày)
- **Code mẫu:**
  ```python
  from fastapi import Security, HTTPException
  from fastapi.security import APIKeyHeader
  
  api_key_header = APIKeyHeader(name="X-API-Key")
  
  async def verify_api_key(api_key: str = Security(api_key_header)):
      if api_key != settings.API_KEY:
          raise HTTPException(status_code=403, detail="Invalid API key")
  ```

#### R-04: Tạo `PendingUploadRepository` singleton
- **Đề xuất:** Centralize quản lý pending uploads:
  ```kotlin
  object PendingUploadRepository {
      private val cache = mutableMapOf<String, List<PendingUploadMetadata>>()
      
      fun getForPlate(plate: String, lanKd: Int): List<PendingUploadMetadata>
      fun add(meta: PendingUploadMetadata)
      fun remove(meta: PendingUploadMetadata)
      fun invalidateCache()
  }
  ```
- **Lợi ích:** Loại bỏ code duplication, giảm I/O đọc file, tránh Gson deserialize lặp
- **Effort:** Medium (1-2 ngày)

### 🎯 ƯU TIÊN TRUNG BÌNH

#### R-05: Connection pooling cho MS Access
- **Đề xuất:** Cache connection hoặc kết quả query danh sách xe (TTL 30-60s)
- **Lợi ích:** Giảm I/O, tránh file lock conflict
- **Effort:** Low (0.5 ngày)

#### R-06: Atomic file write pattern cho backend
- **Đề xuất:**
  ```python
  import tempfile, os
  
  def safe_write(target_path: str, data: bytes):
      dir_name = os.path.dirname(target_path)
      fd, tmp_path = tempfile.mkstemp(dir=dir_name)
      try:
          os.write(fd, data)
          os.close(fd)
          os.replace(tmp_path, target_path)  # Atomic trên cùng filesystem
      except:
          os.close(fd)
          os.unlink(tmp_path)
          raise
  ```
- **Lợi ích:** Tránh file corrupt khi crash/power loss
- **Effort:** Low (0.5 ngày)

#### R-07: Upload trực tiếp qua HTTP khi có LAN
- **Hiện tại:** Ngay cả khi có LAN, ảnh vẫn đi qua Firebase (base64)
- **Đề xuất:** Khi `NetworkUtil` detect LAN available, upload trực tiếp qua HTTP multipart. Firebase chỉ dùng làm fallback khi không có LAN.
- **Lợi ích:** Upload nhanh hơn 5-10x, không tốn Firebase quota
- **Effort:** Medium (2 ngày)

#### R-08: Thêm structured logging cho backend
- **Đề xuất:** Sử dụng Python `logging` module với format chuẩn, bao gồm request ID, plate, photo type cho mỗi log entry.
- **Lợi ích:** Debug production issues nhanh hơn nhiều
- **Effort:** Low (1 ngày)

### 🎯 ƯU TIÊN THẤP (Nice to have)

#### R-09: Migrate từ `Gson` sang `kotlinx.serialization`
- **Lý do:** `kotlinx.serialization` không dùng reflection, nhanh hơn, type-safe hơn Gson.
- **Effort:** Medium (1-2 ngày)

#### R-10: Thêm health check endpoint chi tiết
- **Đề xuất:** Endpoint `/health` trả về status của: Firebase connection, Access DB connection, disk space `Z:\`, pending photo count.
- **Effort:** Low (0.5 ngày)

#### R-11: Database connection retry with exponential backoff
- **Đề xuất:** Wrap Access DB connection trong retry decorator:
  ```python
  @retry(stop=stop_after_attempt(3), wait=wait_exponential(min=1, max=10))
  def get_vehicle_list():
      ...
  ```
- **Effort:** Low (0.5 ngày)

#### R-12: Implement ảnh thumbnail cho ReviewActivity
- **Hiện tại:** Coil load ảnh full-size vào RecyclerView
- **Đề xuất:** Tạo thumbnail khi chụp (200x150), hiển thị trong list, full-size chỉ khi xem chi tiết
- **Lợi ích:** RecyclerView scroll mượt hơn, ít memory hơn
- **Effort:** Low (1 ngày)

---

## TỔNG KẾT

| Mức độ | Backend | Android | Tổng |
|--------|---------|---------|------|
| 🔴 Critical | 3 | 3 | **6** |
| 🟠 High | 3 | 4 | **7** |
| 🟡 Medium | 2 | 3 | **5** |

**Top 5 vấn đề cần xử lý ngay:**

1. **A-01**: Bitmap memory leak trong CameraActivity (OOM crash risk)
2. **S-02**: API endpoints không có authentication (bảo mật)
3. **P-01**: Base64 qua Firebase RTDB (performance bottleneck)
4. **B-01**: Race condition ghi file (data integrity)
5. **A-04 + A-05**: Empty catch blocks + delete inconsistency (data loss risk)

**Ước lượng tổng effort sửa lỗi critical + high:** ~5-7 ngày developer

---

*Báo cáo được tạo bởi phân tích tĩnh mã nguồn. Một số vấn đề cần xác nhận bằng runtime testing.*
