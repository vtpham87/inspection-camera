# Firebase RTDB: khoá quyền truy cập

`database.rules.json` là bản nháp rules. KHÔNG deploy khi chưa làm đủ 3 bước, nếu không điện thoại và PC sẽ mất kết nối:

1. **PC (backend)**: đặt biến môi trường `PTCGDB_FIREBASE_AUTH` (Database secret cũ hoặc token có quyền admin). Backend gắn `?auth=` vào mọi lời gọi REST. Database secret bỏ qua rules nên PC vẫn ghi `vehicles_today`/`config`.
2. **Điện thoại (app)**: rules trên yêu cầu `auth != null`. App hiện CHƯA đăng nhập Firebase (patch này không đổi phần đó). Cần bản 1.6.1:
   - thêm `firebase-auth-ktx`, gọi `signInAnonymously()` trước khi dùng RTDB;
   - các lời gọi REST thô trong `FirebaseManager` (`testNode`, `fetchConfigOnce`, `sendConfigUpdate`) phải gắn `?auth=<idToken>`.
   - Anonymous Auth một mình chỉ chặn truy cập vô danh qua URL; nên bật thêm **App Check** (Play Integrity) để chặn client giả.
3. Áp rules cho CẢ 4 cụm (`ttdk-1507d`, `-p2`, `-p3`, `-bk`) trong Firebase Console → Realtime Database → Rules, thử bằng Rules Playground trước.

Trong lúc chờ bước 2: ít nhất hãy kiểm tra Rules hiện tại trong Console. Nếu đang là `".read": true, ".write": true` thì dữ liệu (biển số, tên chủ xe, ảnh) đang công khai.
