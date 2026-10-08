import os
import sys
from contextlib import asynccontextmanager
from datetime import datetime
from fastapi import FastAPI, File, Form, UploadFile, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import HTMLResponse
from pydantic import BaseModel

PROJECT_DIR = os.path.dirname(os.path.abspath(__file__))
if PROJECT_DIR not in sys.path:
    sys.path.insert(0, PROJECT_DIR)

from config import load_config, save_config, PhotoConfig
from photo_handler import (
    save_photo,
    normalize_plate,
    extract_plate_color,
    should_omit_color_suffix,
    build_filename,
    resolve_save_path,
    VALID_PHOTO_TYPES,
)
from vehicle_service import get_vehicles_today, check_plate_status

CONFIG_PATH = os.environ.get("PHOTO_CONFIG_PATH") or os.path.join(PROJECT_DIR, "photo_config.json")
DB_PATH = os.environ.get("PTCGDB_PATH") or "C:\\PTCGDB_Online\\ptcgdb.db"


def get_config_path() -> str:
    return os.environ.get("PHOTO_CONFIG_PATH") or CONFIG_PATH


def get_db_path() -> str:
    return os.environ.get("PTCGDB_PATH") or DB_PATH


@asynccontextmanager
async def lifespan(app: FastAPI):
    import threading
    from firebase_sync import run_sync_loop
    t = threading.Thread(target=run_sync_loop, args=(10,), daemon=True)
    t.start()
    yield

app = FastAPI(title="15-07D Photo Server", version="1.0.0", lifespan=lifespan)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.get("/api/health")
def health():
    return {
        "ok": True,
        "server": "15-07D Photo Server",
        "version": "1.0.0",
        "time": datetime.now().isoformat(),
    }


@app.post("/api/upload")
async def upload_photo(
    file: UploadFile = File(...),
    plate: str = Form(...),
    plate_color: str | None = Form(None),
    photo_type: str = Form(...),
    seq: int | None = Form(None),
    lan_kd: int | None = Form(1),
):
    if photo_type not in VALID_PHOTO_TYPES:
        raise HTTPException(status_code=400, detail="Loại ảnh không hợp lệ")
    if seq is not None and (seq < 1 or seq > 50):
        raise HTTPException(status_code=400, detail="Thứ tự ảnh (seq) không hợp lệ (1-50)")
    if lan_kd is not None and (lan_kd < 1 or lan_kd > 10):
        raise HTTPException(status_code=400, detail="Lần kiểm định (lan_kd) không hợp lệ (1-10)")

    config = load_config(get_config_path())
    file_bytes = await file.read()
    if not file_bytes:
        raise HTTPException(status_code=400, detail="File ảnh rỗng")
    if len(file_bytes) > 15 * 1024 * 1024:
        raise HTTPException(status_code=413, detail="File ảnh vượt quá 15MB")

    result = save_photo(file_bytes, plate, plate_color, photo_type, seq, config, lan_kd=lan_kd)
    if not result["ok"]:
        raise HTTPException(status_code=400, detail=result["error"])
    return result


class DeleteRequest(BaseModel):
    plate: str
    photo_type: str
    seq: int | None = None
    plate_color: str | None = None
    lan_kd: int | None = 1


@app.delete("/api/photos")
def delete_photo(req: DeleteRequest):
    if req.photo_type not in VALID_PHOTO_TYPES:
        raise HTTPException(status_code=400, detail="Loại ảnh không hợp lệ")
    config = load_config(get_config_path())
    try:
        plate_clean = normalize_plate(req.plate)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))

    plate_num, color = extract_plate_color(plate_clean)
    if req.plate_color:
        color = req.plate_color
    if should_omit_color_suffix(plate_num):
        color = None
        candidate_colors = [None]
    else:
        candidate_colors = [color] if color else [None, "T", "V", "X"]
    deleted_path = None
    matched_save_dir = None
    for c in candidate_colors:
        try:
            cur_save_dir = resolve_save_path(req.photo_type, plate_num, config, create_dir=False, plate_color=c)
            filename = build_filename(plate_num, c, req.photo_type, req.seq, config.plate_color_suffix, lan_kd=req.lan_kd)
            full_path = os.path.join(cur_save_dir, filename)
            if os.path.exists(full_path):
                os.remove(full_path)
                deleted_path = full_path
                matched_save_dir = cur_save_dir
                break
        except ValueError:
            pass

    if deleted_path:
        try:
            root_save_dir = os.path.realpath(config.photo_save_dir)
            if (
                matched_save_dir
                and os.path.realpath(matched_save_dir) != root_save_dir
                and os.path.exists(matched_save_dir)
                and os.path.isdir(matched_save_dir)
                and not os.listdir(matched_save_dir)
            ):
                os.rmdir(matched_save_dir)
        except Exception:
            pass
        return {"ok": True, "deleted": deleted_path}
    raise HTTPException(status_code=404, detail="File không tồn tại")


@app.get("/api/vehicles/today")
def vehicles_today(date: str | None = None, waiting_only: bool = True):
    config = load_config(get_config_path())
    if not config.vehicle_list_enabled:
        return []
    query_date = date or datetime.now().strftime("%Y-%m-%d")
    result = get_vehicles_today(get_db_path(), query_date, config, filter_waiting=waiting_only)
    if result is None:
        return []
    return result


@app.get("/api/vehicles/check-plate")
def check_plate(plate: str, plate_color: str | None = None, date: str | None = None):
    config = load_config(get_config_path())
    query_date = date or datetime.now().strftime("%Y-%m-%d")
    return check_plate_status(get_db_path(), plate, plate_color, config, date=query_date)



class CheckPathRequest(BaseModel):
    path: str


@app.post("/api/check-path")
def check_path(req: CheckPathRequest):
    raw_path = (req.path or "").strip()
    if not raw_path:
        return {"ok": False, "message": "Đường dẫn không được để trống"}
    test_path = raw_path.replace("{plate}", "15A12345T").replace("{date}", datetime.now().strftime("%Y-%m-%d"))
    exists = os.path.exists(test_path)
    creatable = False
    if not exists:
        try:
            os.makedirs(test_path, exist_ok=True)
            creatable = True
        except Exception as e:
            return {"ok": False, "exists": False, "message": f"Không thể tạo hoặc truy cập: {e}"}
    return {
        "ok": True,
        "exists": exists or creatable,
        "path": test_path,
        "message": "Đường dẫn hợp lệ và có thể ghi ảnh!" if (exists or creatable) else "Đường dẫn không tồn tại",
    }


@app.post("/api/sync-config-now")
def sync_config_now():
    try:
        from firebase_sync import sync_config_to_firebase
        cfg = load_config(get_config_path())
        ok = sync_config_to_firebase(cfg)
        return {
            "ok": ok,
            "message": "Đã đồng bộ cấu hình sang Firebase thành công!" if ok else "Lỗi đồng bộ Firebase",
        }
    except Exception as e:
        return {"ok": False, "message": f"Lỗi: {e}"}


@app.post("/api/sync-vehicles-now")
def sync_vehicles_now():
    try:
        from firebase_sync import sync_vehicles_to_firebase
        sync_vehicles_to_firebase()
        return {"ok": True, "message": "Đã đồng bộ danh sách xe lên điện thoại thành công!"}
    except Exception as e:
        return {"ok": False, "message": f"Lỗi đồng bộ xe: {e}"}


@app.post("/api/restart-service")
def restart_service():
    import subprocess
    try:
        subprocess.Popen(["cscript", "//nologo", "D:\\inspection-camera\\backend\\restart_background.vbs"])
        return {"ok": True, "message": "Đang khởi động lại dịch vụ..."}
    except Exception as e:
        return {"ok": False, "message": f"Lỗi khởi động lại: {e}"}


@app.get("/api/config")
def get_config():
    return load_config(get_config_path()).model_dump()


@app.post("/api/config")
def post_config(config: PhotoConfig):
    config.recalculate_paths()
    save_config(config, get_config_path())
    sync_status = "Đã lưu vào máy tính trạm"
    try:
        from firebase_sync import sync_config_to_firebase
        ok = sync_config_to_firebase(config)
        if ok:
            sync_status += " và đồng bộ thành công sang điện thoại qua Firebase!"
        else:
            sync_status += ", nhưng đồng bộ Firebase gặp lỗi."
    except Exception as e:
        sync_status += f", cảnh báo Firebase: {e}"
    return {"ok": True, "message": sync_status}


HTML_CONTENT = """<!DOCTYPE html>
<html lang="vi">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Cấu hình Lưu ảnh & Đồng bộ - TTĐK 15-07D</title>
  <style>
    :root {
      --primary: #2563eb;
      --primary-hover: #1d4ed8;
      --success: #16a34a;
      --success-hover: #15803d;
      --danger: #dc2626;
      --bg: #f8fafc;
      --card-bg: #ffffff;
      --text-main: #0f172a;
      --text-muted: #475569;
      --border: #cbd5e1;
    }
    * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif; }
    body { background-color: var(--bg); color: var(--text-main); line-height: 1.5; padding: 20px; font-size: 15px; }
    .container { max-width: 860px; margin: 0 auto; }
    header { background: #ffffff; border-radius: 12px; padding: 24px; margin-bottom: 20px; border: 1px solid var(--border); box-shadow: 0 2px 4px rgba(0,0,0,0.04); }
    h1 { font-size: 22px; font-weight: 700; color: #1e3a8a; margin-bottom: 6px; }
    .subtitle { color: var(--text-muted); font-size: 14px; }
    .status-badge { display: inline-flex; align-items: center; gap: 6px; padding: 4px 12px; background: #ecfdf5; border: 1px solid #a7f3d0; border-radius: 9999px; color: #065f46; font-size: 13px; font-weight: 600; margin-top: 10px; }
    .status-dot { width: 8px; height: 8px; border-radius: 50%; background: #10b981; }
    .card { background: var(--card-bg); border-radius: 12px; padding: 24px; margin-bottom: 20px; border: 1px solid var(--border); box-shadow: 0 2px 4px rgba(0,0,0,0.04); }
    .card-title { font-size: 17px; font-weight: 700; color: #0f172a; margin-bottom: 16px; display: flex; align-items: center; gap: 8px; border-bottom: 2px solid #e2e8f0; padding-bottom: 10px; }
    .form-group { margin-bottom: 18px; }
    .form-group:last-child { margin-bottom: 0; }
    label { display: block; font-weight: 600; margin-bottom: 6px; font-size: 14px; color: #1e293b; }
    .input-row { display: flex; gap: 8px; }
    input[type="text"], input[type="number"], select { width: 100%; padding: 10px 14px; font-size: 15px; border: 1px solid var(--border); border-radius: 8px; background: #ffffff; color: #0f172a; outline: none; transition: border-color 0.2s; }
    input:focus, select:focus { border-color: var(--primary); box-shadow: 0 0 0 3px rgba(37,99,235,0.15); }
    .helper-text { font-size: 13px; color: var(--text-muted); margin-top: 4px; }
    .check-btn { padding: 10px 16px; background: #f1f5f9; border: 1px solid var(--border); border-radius: 8px; cursor: pointer; font-weight: 600; font-size: 14px; color: #334155; white-space: nowrap; transition: all 0.2s; }
    .check-btn:hover { background: #e2e8f0; color: #0f172a; }
    .check-result { margin-top: 6px; font-size: 13px; font-weight: 600; display: none; }
    .check-result.ok { color: var(--success); display: block; }
    .check-result.err { color: var(--danger); display: block; }
    .checkbox-label { display: flex; align-items: center; gap: 10px; font-weight: 500; cursor: pointer; font-size: 14px; color: #1e293b; }
    .checkbox-label input[type="checkbox"] { width: 18px; height: 18px; accent-color: var(--primary); cursor: pointer; }
    .grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
    @media (max-width: 640px) { .grid-2 { grid-template-columns: 1fr; } }
    .actions { display: flex; gap: 12px; margin-top: 24px; position: sticky; bottom: 20px; background: rgba(248,250,252,0.92); backdrop-filter: blur(8px); padding: 14px; border-radius: 12px; border: 1px solid var(--border); box-shadow: 0 4px 12px rgba(0,0,0,0.08); }
    .btn-save { flex: 2; padding: 14px 20px; background: var(--success); color: #ffffff; border: none; border-radius: 8px; font-size: 16px; font-weight: 700; cursor: pointer; transition: background 0.2s; display: flex; align-items: center; justify-content: center; gap: 8px; }
    .btn-save:hover { background: var(--success-hover); }
    .btn-sync { flex: 1; padding: 14px 16px; background: var(--primary); color: #ffffff; border: none; border-radius: 8px; font-size: 15px; font-weight: 600; cursor: pointer; transition: background 0.2s; }
    .btn-sync:hover { background: var(--primary-hover); }
    #toast { position: fixed; top: 20px; right: 20px; z-index: 9999; padding: 14px 20px; border-radius: 8px; font-weight: 600; font-size: 14px; color: #fff; box-shadow: 0 4px 12px rgba(0,0,0,0.15); display: none; max-width: 400px; line-height: 1.4; animation: slideIn 0.3s forwards; }
    @keyframes slideIn { from { transform: translateY(-20px); opacity: 0; } to { transform: translateY(0); opacity: 1; } }
  </style>
</head>
<body>
  <div id="toast"></div>
  <div class="container">
    <header>
      <h1>📸 HỆ THỐNG CHỤP ẢNH KIỂM ĐỊNH 15-07D</h1>
      <div class="subtitle">Cấu hình thư mục lưu ảnh trên máy tính & đồng bộ thời gian thực sang điện thoại</div>
      <div style="display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 10px; margin-top: 12px;">
        <div class="status-badge">
          <div class="status-dot"></div>
          <span id="sync-status-text">Đang tải cấu hình máy chủ...</span>
        </div>
        <div style="display: flex; gap: 8px;">
          <button type="button" class="check-btn" style="background: #2563eb; color: #fff; border-color: #1d4ed8;" onclick="forceSyncVehicles()">🔄 Đồng bộ danh sách xe</button>
          <button type="button" class="check-btn" style="background: #e2e8f0; color: #0f172a;" onclick="restartService()">⚡ Khởi động lại dịch vụ</button>
        </div>
      </div>
    </header>

    <form id="configForm" onsubmit="saveConfiguration(event)">
      <!-- Card 1: Thư mục lưu ảnh PC -->
      <div class="card">
        <div class="card-title">📂 Thư mục lưu ảnh trên máy tính (PC)</div>
        
        <div class="form-group">
          <label for="photo_save_dir">1. Đường dẫn ảnh góc chụp 45° (Trước / Sau / Gầm):</label>
          <div class="input-row">
            <input type="text" id="photo_save_dir" placeholder="Ví dụ: Z:\\Anh Phuong Tien hoặc D:\\Photos" required>
            <button type="button" class="check-btn" onclick="checkDirectory('photo_save_dir')">Kiểm tra</button>
          </div>
          <div id="photo_save_dir_res" class="check-result"></div>
          <div class="helper-text">💡 Thư mục chuẩn để phần mềm PTCGDB v9.2 đọc và in giấy chứng nhận kiểm định.</div>
        </div>

        <div class="form-group">
          <label for="passenger_path">2. Đường dẫn ảnh khoang hành khách / CCCD:</label>
          <div class="input-row">
            <input type="text" id="passenger_path" placeholder="Ví dụ: Z:\\Anh Khoang HK CCCD\\{plate}">
            <button type="button" class="check-btn" onclick="checkDirectory('passenger_path')">Kiểm tra</button>
          </div>
          <div id="passenger_path_res" class="check-result"></div>
          <div class="helper-text">💡 Biến {plate} sẽ tự động được thay bằng biển số xe khi lưu ảnh.</div>
        </div>

        <div class="form-group">
          <label for="new_vehicle_path">3. Đường dẫn ảnh xe mới (cấp miễn):</label>
          <div class="input-row">
            <input type="text" id="new_vehicle_path" placeholder="Ví dụ: Z:\\Anh sau cap mien\\{plate}">
            <button type="button" class="check-btn" onclick="checkDirectory('new_vehicle_path')">Kiểm tra</button>
          </div>
          <div id="new_vehicle_path_res" class="check-result"></div>
          <div class="helper-text">💡 Lưu ảnh hồ sơ phương tiện miễn đăng kiểm lần đầu.</div>
        </div>

        <div class="form-group">
          <label class="checkbox-label">
            <input type="checkbox" id="sync_new_vehicle_45">
            Tự động sao chép thêm 1 bản ảnh xe mới sang thư mục ảnh 45°
          </label>
        </div>
      </div>

      <!-- Card 2: Đóng dấu ảnh (Timestamp) -->
      <div class="card">
        <div class="card-title">🕒 Đóng dấu ngày giờ & biển số lên ảnh (Timestamp)</div>
        
        <div class="form-group">
          <label class="checkbox-label">
            <input type="checkbox" id="ts_enabled">
            Bật tính năng đóng dấu ngày giờ kiểm định lên ảnh
          </label>
        </div>

        <div class="grid-2">
          <div class="form-group">
            <label for="ts_format">Định dạng thời gian:</label>
            <input type="text" id="ts_format" value="HH:mm:ss - dd/MM/yyyy">
            <div class="helper-text">Ví dụ: 14:30:15 - 05/10/2026</div>
          </div>
          <div class="form-group">
            <label for="ts_position">Vị trí đóng dấu:</label>
            <select id="ts_position">
              <option value="bottom_right">Góc dưới bên phải (Chuẩn)</option>
              <option value="bottom_left">Góc dưới bên trái</option>
              <option value="top_right">Góc trên bên phải</option>
              <option value="top_left">Góc trên bên trái</option>
            </select>
          </div>
        </div>

        <div class="grid-2">
          <div class="form-group">
            <label for="ts_font_size">Cỡ chữ đóng dấu (px):</label>
            <input type="number" id="ts_font_size" min="16" max="64" value="28">
          </div>
          <div class="form-group">
            <label for="photo_resolution">Độ phân giải chụp:</label>
            <select id="photo_resolution">
              <option value="low">HD (1280×720 - Tiêu chuẩn, khuyên dùng)</option>
              <option value="medium">Full HD (1920×1080 - Trung bình)</option>
              <option value="high">4K (3840×2160 - Nét cao)</option>
              <option value="original">Gốc camera (Dung lượng lớn)</option>
            </select>
          </div>
        </div>
      </div>

      <!-- Actions -->
      <div class="actions">
        <button type="submit" class="btn-save">
          💾 LƯU CẤU HÌNH & ĐỒNG BỘ SANG ĐIỆN THOẠI
        </button>
        <button type="button" class="btn-sync" onclick="forceSyncFirebase()">
          🔄 Đồng bộ lại Firebase
        </button>
      </div>
    </form>
  </div>

  <script>
    let currentRawConfig = {};

    function showToast(msg, isError = false) {
      const t = document.getElementById("toast");
      t.style.display = "block";
      t.style.background = isError ? "var(--danger)" : "var(--success)";
      t.textContent = msg;
      setTimeout(() => { t.style.display = "none"; }, 4000);
    }

    async function loadConfig() {
      try {
        const res = await fetch("/api/config");
        if (!res.ok) throw new Error("Không thể kết nối máy chủ API");
        const cfg = await res.json();
        currentRawConfig = cfg;

        document.getElementById("photo_save_dir").value = cfg.photo_save_dir || "";
        document.getElementById("passenger_path").value = cfg.passenger_path || "";
        document.getElementById("new_vehicle_path").value = cfg.new_vehicle_path || "";
        document.getElementById("sync_new_vehicle_45").checked = !!cfg.sync_new_vehicle_45;

        const ts = cfg.timestamp || {};
        document.getElementById("ts_enabled").checked = ts.enabled !== false;
        document.getElementById("ts_format").value = ts.format || "HH:mm:ss - dd/MM/yyyy";
        document.getElementById("ts_position").value = ts.position || "bottom_right";
        document.getElementById("ts_font_size").value = ts.font_size || 28;
        const resVal = (cfg.photo_resolution || "original").toLowerCase();
        const resMap = { "720p": "low", "hd": "low", "1080p": "medium", "fhd": "medium", "4k": "high" };
        document.getElementById("photo_resolution").value = resMap[resVal] || resVal || "original";

        document.getElementById("sync-status-text").textContent = "Máy chủ 8095 sẵn sàng • Đã nạp cấu hình";
      } catch (err) {
        document.getElementById("sync-status-text").textContent = "Lỗi nạp cấu hình: " + err.message;
        showToast("Lỗi nạp cấu hình: " + err.message, true);
      }
    }

    async function checkDirectory(fieldId) {
      const val = document.getElementById(fieldId).value.trim();
      const resEl = document.getElementById(fieldId + "_res");
      if (!val) {
        resEl.className = "check-result err";
        resEl.textContent = "Vui lòng nhập đường dẫn để kiểm tra";
        return;
      }
      resEl.className = "check-result";
      resEl.textContent = "Đang kiểm tra đường dẫn...";
      resEl.style.display = "block";

      try {
        const resp = await fetch("/api/check-path", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ path: val })
        });
        const data = await resp.json();
        if (data.ok) {
          resEl.className = "check-result ok";
          resEl.textContent = "✅ " + data.message + " (" + data.path + ")";
        } else {
          resEl.className = "check-result err";
          resEl.textContent = "❌ " + data.message;
        }
      } catch (e) {
        resEl.className = "check-result err";
        resEl.textContent = "❌ Lỗi mạng khi kiểm tra";
      }
    }

    async function saveConfiguration(e) {
      e.preventDefault();
      const payload = {
        ...currentRawConfig,
        photo_save_dir: document.getElementById("photo_save_dir").value.trim(),
        passenger_path: document.getElementById("passenger_path").value.trim(),
        new_vehicle_path: document.getElementById("new_vehicle_path").value.trim(),
        sync_new_vehicle_45: document.getElementById("sync_new_vehicle_45").checked,
        photo_resolution: document.getElementById("photo_resolution").value,
        timestamp: {
          ...(currentRawConfig.timestamp || {}),
          enabled: document.getElementById("ts_enabled").checked,
          format: document.getElementById("ts_format").value.trim(),
          position: document.getElementById("ts_position").value,
          font_size: parseInt(document.getElementById("ts_font_size").value) || 28
        }
      };
      delete payload.paths;

      try {
        const resp = await fetch("/api/config", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(payload)
        });
        const result = await resp.json();
        if (result.ok) {
          showToast("✅ " + result.message);
          document.getElementById("sync-status-text").textContent = "Cấu hình đã lưu & đồng bộ Firebase lúc " + new Date().toLocaleTimeString();
        } else {
          showToast("❌ Lỗi: " + (result.message || "Không lưu được"), true);
        }
      } catch (err) {
        showToast("❌ Lỗi kết nối khi lưu: " + err.message, true);
      }
    }

    async function forceSyncFirebase() {
      try {
        const resp = await fetch("/api/sync-config-now", { method: "POST" });
        const res = await resp.json();
        showToast(res.ok ? "✅ " + res.message : "❌ " + res.message, !res.ok);
      } catch (err) {
        showToast("❌ Lỗi khi đồng bộ: " + err.message, true);
      }
    }

    async function forceSyncVehicles() {
      try {
        const resp = await fetch("/api/sync-vehicles-now", { method: "POST" });
        const res = await resp.json();
        showToast(res.ok ? "✅ " + res.message : "❌ " + res.message, !res.ok);
      } catch (err) {
        showToast("❌ Lỗi khi đồng bộ xe: " + err.message, true);
      }
    }

    async function restartService() {
      if (!confirm("Khởi động lại dịch vụ Photo Server và tiến trình đồng bộ?")) return;
      try {
        await fetch("/api/restart-service", { method: "POST" });
        showToast("⚡ Đang khởi động lại dịch vụ... Trang sẽ tự làm mới sau 3 giây");
        setTimeout(() => location.reload(), 3000);
      } catch (err) {
        showToast("❌ Lỗi khi gửi lệnh: " + err.message, true);
      }
    }

    window.onload = loadConfig;
  </script>
</body>
</html>
"""


@app.get("/", response_class=HTMLResponse)
@app.get("/settings", response_class=HTMLResponse)
def index_settings():
    return HTMLResponse(content=HTML_CONTENT)



if __name__ == "__main__":
    import uvicorn
    config = load_config(get_config_path())
    uvicorn.run(app, host="0.0.0.0", port=config.server_port, log_level="info")
