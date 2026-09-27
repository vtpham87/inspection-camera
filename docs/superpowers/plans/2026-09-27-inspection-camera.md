# Inspection Camera — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a FastAPI backend + Android APK that lets inspectors photograph vehicles at station 15-07D with auto-named files synced to the station workstation.

**Architecture:** The backend is a standalone FastAPI service on port 8095 (separate from PTCGDB Online on 8080). It receives photos via multipart upload, saves them to configurable directories on `D:\Photos\`, and serves vehicle lists from the existing `ptcgdb.db`. The Android app uses CameraX for capture, burns timestamps onto photos client-side, and uploads via Retrofit over LAN or Tailscale. GitHub Actions builds the APK.

**Tech Stack:** Python 3.11 + FastAPI + uvicorn (backend); Kotlin + CameraX + Retrofit + Material Design 3 (Android); GitHub Actions CI (APK build).

**Spec:** `D:\inspection-camera\SPEC.md`

## Global Constraints

- Backend port: **8095** (never 8080)
- Python: 3.11+ (use the Hermes venv at `C:\Users\t1507d\AppData\Local\hermes\hermes-agent\venv\Scripts\python.exe`)
- Android: minSdk 24, targetSdk 34, compileSdk 34
- Package name: `com.ttdk1507d.inspectioncamera`
- Photo paths default: `D:\Photos\{YYYYMMDD}\`
- JPEG only, max 10MB, magic-bytes validation
- Plate normalization: strip dots/dashes/spaces, uppercase, A-Z 0-9 only
- Plate color: extracted from last char of `biendk_clean` (T/V/X); old plates ending in digit → `plate_color = null`
- Light Mode only: `#FFFFFF` bg, `#1A1A1A` text, `#1565C0` primary
- Buttons ≥ 56dp, font ≥ 18sp
- GitHub repo: `vtpham87/inspection-camera`
- Database: read-only from `C:\PTCGDB_Online\ptcgdb.db` (SQLite)

## Review Focus

1. **Biển cũ không hậu tố** (e.g. `11K2639`): upload với `plate_color=null` phải tạo file `11K2639.jpg`, KHÔNG phải `11K2639T.jpg` → Test trong Task 2
2. **Path traversal via plate** (e.g. `../../../etc`): plate chứa ký tự đặc biệt phải bị reject 400 → Test trong Task 2
3. **JPEG magic bytes mismatch**: upload file PNG đổi đuôi .jpg phải bị reject → Test trong Task 2
4. **File > 10MB**: upload ảnh vượt quá giới hạn phải trả 413 → Test trong Task 2
5. **Toggle vehicle_list_enabled = false**: API `/vehicles/today` trả 404 hoặc skip, app không gọi endpoint → Test trong Task 3 (backend) + Task 5 (app)

---

## File Structure

### Backend (`D:\inspection-camera\backend\`)

| File | Responsibility |
|---|---|
| `main.py` | FastAPI app, CORS, startup, uvicorn runner |
| `config.py` | Load/save `photo_config.json`, defaults, Pydantic models |
| `photo_handler.py` | Plate normalization, color extraction, filename generation, file save with validation |
| `vehicle_service.py` | Query `ptcgdb.db` for today's vehicles, check photos on disk |
| `photo_config.json` | Runtime config (auto-generated on first run) |
| `requirements.txt` | Dependencies |
| `start_background.vbs` | Background launcher (no duplicate process) |
| `tests/test_photo_handler.py` | Unit tests for plate logic, filename gen, validation |
| `tests/test_api.py` | Integration tests for all API endpoints |
| `tests/test_vehicle_service.py` | Tests for vehicle query + photo status |

### Android (`D:\inspection-camera\android\`)

| File | Responsibility |
|---|---|
| `app/src/main/java/.../MainActivity.kt` | Screen 1: Vehicle select / plate input |
| `app/src/main/java/.../CameraActivity.kt` | Screen 2: Camera preview + 5 capture buttons |
| `app/src/main/java/.../ReviewActivity.kt` | Screen 3: Photo review thumbnails |
| `app/src/main/java/.../SettingsActivity.kt` | Screen 4: Server config, connection test |
| `app/src/main/java/.../api/ApiService.kt` | Retrofit interface |
| `app/src/main/java/.../api/ApiClient.kt` | OkHttp client with dual-network logic |
| `app/src/main/java/.../model/Vehicle.kt` | Data class |
| `app/src/main/java/.../model/PhotoType.kt` | Enum 5 photo types |
| `app/src/main/java/.../model/AppConfig.kt` | Config data class |
| `app/src/main/java/.../util/NetworkUtil.kt` | LAN probe + Tailscale fallback |
| `app/src/main/java/.../util/PrefsManager.kt` | SharedPreferences wrapper |
| `app/src/main/java/.../util/TimestampPainter.kt` | Burn timestamp onto Bitmap |
| `app/src/main/java/.../util/PlateUtil.kt` | Plate normalization + color extraction |
| `app/src/main/java/.../worker/PendingUploadWorker.kt` | Background retry for offline queue |
| `app/src/main/java/.../adapter/VehicleAdapter.kt` | RecyclerView for vehicle list |
| `app/src/main/java/.../adapter/PhotoReviewAdapter.kt` | Grid adapter for review screen |

### CI (`.github/workflows/`)

| File | Responsibility |
|---|---|
| `build-apk.yml` | Build debug APK on push to `android/**` |

---

## Task 1: Project Scaffolding + Config Module

**Files:**
- Create: `backend/config.py`
- Create: `backend/requirements.txt`
- Create: `backend/tests/__init__.py`
- Create: `backend/tests/test_config.py`

**Interfaces:**
- Produces:
  - `class TimestampConfig(BaseModel)` — fields: enabled, format, font_size, font_color, font_bold, font_stroke_enabled, font_stroke_color, font_stroke_width, background_color, position
  - `class PhotoConfig(BaseModel)` — fields: vehicle_list_enabled, server_port, paths (dict[str,str]), jpeg_quality, plate_color_suffix, timestamp (TimestampConfig), photo_resolution
  - `def load_config(path: str = "photo_config.json") -> PhotoConfig`
  - `def save_config(config: PhotoConfig, path: str = "photo_config.json") -> None`
  - `CONFIG_DEFAULTS: PhotoConfig` — hardcoded defaults matching Spec §2.3

- [ ] **Step 1: Create `backend/requirements.txt`**

```
fastapi==0.115.0
uvicorn[standard]==0.30.0
python-multipart==0.0.9
pydantic==2.9.0
pytest==8.3.0
httpx==0.27.0
```

- [ ] **Step 2: Create virtual environment and install deps**

```bash
cd D:\inspection-camera\backend
python -m venv venv
venv\Scripts\pip install -r requirements.txt
```

- [ ] **Step 3: Initialize GitHub repo**

```bash
cd D:\inspection-camera
git init
gh repo create vtpham87/inspection-camera --private --source=. --push
```

- [ ] **Step 4: Write failing test for config defaults**

```python
# backend/tests/test_config.py
import os
import json
import pytest
from config import load_config, save_config, PhotoConfig, CONFIG_DEFAULTS

def test_defaults_have_correct_port():
    assert CONFIG_DEFAULTS.server_port == 8095

def test_defaults_have_five_path_keys():
    assert set(CONFIG_DEFAULTS.paths.keys()) == {
        "rear_45", "front_45", "chassis", "passenger", "new_vehicle"
    }

def test_defaults_timestamp_enabled():
    assert CONFIG_DEFAULTS.timestamp.enabled is True
    assert CONFIG_DEFAULTS.timestamp.position == "bottom_right"

def test_defaults_photo_resolution():
    assert CONFIG_DEFAULTS.photo_resolution == "original"

def test_defaults_no_max_photo_width():
    """max_photo_width was removed — ensure it doesn't exist."""
    assert not hasattr(CONFIG_DEFAULTS, "max_photo_width")

def test_load_creates_file_if_missing(tmp_path):
    path = str(tmp_path / "config.json")
    config = load_config(path)
    assert config.server_port == 8095
    assert os.path.exists(path)

def test_save_and_reload(tmp_path):
    path = str(tmp_path / "config.json")
    cfg = CONFIG_DEFAULTS.model_copy()
    cfg.jpeg_quality = 70
    save_config(cfg, path)
    reloaded = load_config(path)
    assert reloaded.jpeg_quality == 70
```

- [ ] **Step 5: Run tests — expect FAIL**

```bash
cd D:\inspection-camera\backend
venv\Scripts\python -m pytest tests/test_config.py -v
```

Expected: `ModuleNotFoundError: No module named 'config'`

- [ ] **Step 6: Implement `config.py`**

```python
# backend/config.py
import os
import json
from pydantic import BaseModel

class TimestampConfig(BaseModel):
    enabled: bool = True
    format: str = "HH:mm:ss - dd/MM/yyyy"
    font_size: int = 28
    font_color: str = "#FFFFFF"
    font_bold: bool = True
    font_stroke_enabled: bool = True
    font_stroke_color: str = "#000000"
    font_stroke_width: float = 2.0
    background_color: str = "#80000000"
    position: str = "bottom_right"

class PhotoConfig(BaseModel):
    vehicle_list_enabled: bool = True
    server_port: int = 8095
    paths: dict[str, str] = {
        "rear_45": "D:\\Photos\\{date}",
        "front_45": "D:\\Photos\\{date}",
        "chassis": "D:\\Photos\\{date}",
        "passenger": "D:\\Photos\\{date}\\{plate}",
        "new_vehicle": "D:\\Photos\\{date}\\{plate}",
    }
    jpeg_quality: int = 85
    plate_color_suffix: bool = True
    timestamp: TimestampConfig = TimestampConfig()
    photo_resolution: str = "original"

CONFIG_DEFAULTS = PhotoConfig()

def load_config(path: str = "photo_config.json") -> PhotoConfig:
    if os.path.exists(path):
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
        return PhotoConfig(**data)
    config = PhotoConfig()
    save_config(config, path)
    return config

def save_config(config: PhotoConfig, path: str = "photo_config.json") -> None:
    with open(path, "w", encoding="utf-8") as f:
        json.dump(config.model_dump(), f, indent=2, ensure_ascii=False)
```

- [ ] **Step 7: Run tests — expect PASS**

```bash
cd D:\inspection-camera\backend
venv\Scripts\python -m pytest tests/test_config.py -v
```

- [ ] **Step 8: Commit**

```bash
git add backend/
git commit -m "feat: config module with Pydantic models and defaults"
git push
```

---

## Task 2: Photo Handler — Plate Logic + File Save + Validation

**Files:**
- Create: `backend/photo_handler.py`
- Create: `backend/tests/test_photo_handler.py`

**Interfaces:**
- Consumes: `PhotoConfig`, `load_config()` from Task 1
- Produces:
  - `def normalize_plate(raw: str) -> str` — strip dots/dashes/spaces, uppercase
  - `def extract_plate_color(biendk_clean: str) -> tuple[str, str | None]` — split plate number from color suffix
  - `def build_filename(plate: str, plate_color: str | None, photo_type: str, seq: int | None, color_suffix_enabled: bool) -> str`
  - `def resolve_save_path(photo_type: str, plate: str, config: PhotoConfig) -> str` — expand `{date}` and `{plate}` in path templates
  - `def validate_jpeg(file_bytes: bytes) -> bool` — check JPEG magic bytes `FF D8 FF`
  - `def save_photo(file_bytes: bytes, plate: str, plate_color: str | None, photo_type: str, seq: int | None, config: PhotoConfig) -> dict` — validate + save + return `{ok, path, filename}`

- [ ] **Step 1: Write failing tests**

```python
# backend/tests/test_photo_handler.py
import os
import pytest
from photo_handler import (
    normalize_plate,
    extract_plate_color,
    build_filename,
    resolve_save_path,
    validate_jpeg,
    save_photo,
)
from config import PhotoConfig

# --- normalize_plate ---
def test_normalize_strips_dots_dashes_spaces():
    assert normalize_plate("15A-123.45") == "15A12345"

def test_normalize_uppercase():
    assert normalize_plate("15a-123.45t") == "15A12345T"

def test_normalize_rejects_special_chars():
    with pytest.raises(ValueError, match="không hợp lệ"):
        normalize_plate("../etc")

def test_normalize_rejects_empty():
    with pytest.raises(ValueError, match="không hợp lệ"):
        normalize_plate("")

# --- extract_plate_color ---
def test_extract_color_white():
    plate, color = extract_plate_color("15A12345T")
    assert plate == "15A12345"
    assert color == "T"

def test_extract_color_yellow():
    plate, color = extract_plate_color("11B00103V")
    assert plate == "11B00103"
    assert color == "V"

def test_extract_color_old_plate():
    """Old plate ending in digit — no color suffix."""
    plate, color = extract_plate_color("11K2639")
    assert plate == "11K2639"
    assert color is None

# --- build_filename ---
def test_filename_rear_45_with_color():
    assert build_filename("15A12345", "T", "rear_45", None, True) == "15A12345T.jpg"

def test_filename_rear_45_no_color():
    assert build_filename("11K2639", None, "rear_45", None, True) == "11K2639.jpg"

def test_filename_rear_45_suffix_disabled():
    assert build_filename("15A12345", "T", "rear_45", None, False) == "15A12345.jpg"

def test_filename_front_45():
    assert build_filename("15A12345", "T", "front_45", None, True) == "bs15A12345T.jpg"

def test_filename_chassis():
    assert build_filename("15A12345", "T", "chassis", None, True) == "sk_15A12345.jpg"

def test_filename_passenger_seq():
    assert build_filename("15A12345", "T", "passenger", 2, True) == "15A12345_2.jpg"

def test_filename_new_vehicle_seq():
    assert build_filename("15A12345", "T", "new_vehicle", 1, True) == "15A12345_1.jpg"

# --- validate_jpeg ---
def test_validate_real_jpeg():
    # Minimal valid JPEG: FF D8 FF E0 ... FF D9
    jpeg_bytes = b"\xff\xd8\xff\xe0" + b"\x00" * 100 + b"\xff\xd9"
    assert validate_jpeg(jpeg_bytes) is True

def test_validate_png_rejected():
    png_bytes = b"\x89PNG\r\n\x1a\n" + b"\x00" * 100
    assert validate_jpeg(png_bytes) is False

def test_validate_empty_rejected():
    assert validate_jpeg(b"") is False

# --- save_photo ---
def test_save_photo_creates_file(tmp_path):
    config = PhotoConfig()
    config.paths["rear_45"] = str(tmp_path / "{date}")
    jpeg = b"\xff\xd8\xff\xe0" + b"\x00" * 100 + b"\xff\xd9"
    result = save_photo(jpeg, "15A12345", "T", "rear_45", None, config)
    assert result["ok"] is True
    assert os.path.exists(result["path"])

def test_save_photo_rejects_non_jpeg(tmp_path):
    config = PhotoConfig()
    config.paths["rear_45"] = str(tmp_path / "{date}")
    png = b"\x89PNG\r\n\x1a\n" + b"\x00" * 100
    result = save_photo(png, "15A12345", "T", "rear_45", None, config)
    assert result["ok"] is False

def test_save_photo_rejects_oversize(tmp_path):
    config = PhotoConfig()
    config.paths["rear_45"] = str(tmp_path / "{date}")
    # 11MB fake JPEG
    big = b"\xff\xd8\xff\xe0" + b"\x00" * (11 * 1024 * 1024)
    result = save_photo(big, "15A12345", "T", "rear_45", None, config)
    assert result["ok"] is False

def test_save_photo_path_traversal_rejected(tmp_path):
    config = PhotoConfig()
    config.paths["rear_45"] = str(tmp_path / "{date}")
    jpeg = b"\xff\xd8\xff\xe0" + b"\x00" * 100 + b"\xff\xd9"
    result = save_photo(jpeg, "../../../etc", None, "rear_45", None, config)
    assert result["ok"] is False
```

- [ ] **Step 2: Run tests — expect FAIL**

```bash
cd D:\inspection-camera\backend
venv\Scripts\python -m pytest tests/test_photo_handler.py -v
```

- [ ] **Step 3: Implement `photo_handler.py`**

```python
# backend/photo_handler.py
import os
import re
from datetime import datetime
from config import PhotoConfig

MAX_FILE_SIZE = 10 * 1024 * 1024  # 10MB
JPEG_MAGIC = b"\xff\xd8\xff"
VALID_PLATE_RE = re.compile(r"^[A-Z0-9]+$")
VALID_PHOTO_TYPES = {"rear_45", "front_45", "chassis", "passenger", "new_vehicle"}


def normalize_plate(raw: str) -> str:
    cleaned = re.sub(r"[.\-\s]", "", raw).upper()
    if not cleaned or not VALID_PLATE_RE.match(cleaned):
        raise ValueError("Biển số không hợp lệ")
    return cleaned


def extract_plate_color(biendk_clean: str) -> tuple[str, str | None]:
    if biendk_clean and biendk_clean[-1] in ("T", "V", "X"):
        return biendk_clean[:-1], biendk_clean[-1]
    return biendk_clean, None


def build_filename(
    plate: str,
    plate_color: str | None,
    photo_type: str,
    seq: int | None,
    color_suffix_enabled: bool,
) -> str:
    suffix = ""
    if color_suffix_enabled and plate_color:
        suffix = plate_color

    if photo_type == "rear_45":
        return f"{plate}{suffix}.jpg"
    elif photo_type == "front_45":
        return f"bs{plate}{suffix}.jpg"
    elif photo_type == "chassis":
        return f"sk_{plate}.jpg"
    elif photo_type in ("passenger", "new_vehicle"):
        s = seq if seq else 1
        return f"{plate}_{s}.jpg"
    else:
        raise ValueError(f"Loại ảnh không hợp lệ: {photo_type}")


def resolve_save_path(photo_type: str, plate: str, config: PhotoConfig) -> str:
    template = config.paths.get(photo_type, "D:\\Photos\\{date}")
    today = datetime.now().strftime("%Y%m%d")
    path = template.replace("{date}", today).replace("{plate}", plate)
    os.makedirs(path, exist_ok=True)
    return path


def validate_jpeg(file_bytes: bytes) -> bool:
    if len(file_bytes) < 3:
        return False
    return file_bytes[:3] == JPEG_MAGIC


def save_photo(
    file_bytes: bytes,
    plate: str,
    plate_color: str | None,
    photo_type: str,
    seq: int | None,
    config: PhotoConfig,
) -> dict:
    # Validate plate
    try:
        plate = normalize_plate(plate)
    except ValueError as e:
        return {"ok": False, "error": str(e)}

    # Validate photo type
    if photo_type not in VALID_PHOTO_TYPES:
        return {"ok": False, "error": f"Loại ảnh không hợp lệ: {photo_type}"}

    # Validate JPEG
    if not validate_jpeg(file_bytes):
        return {"ok": False, "error": "File không phải định dạng JPEG"}

    # Validate size
    if len(file_bytes) > MAX_FILE_SIZE:
        return {"ok": False, "error": f"File vượt quá {MAX_FILE_SIZE // (1024*1024)}MB"}

    # Build filename and path
    filename = build_filename(plate, plate_color, photo_type, seq, config.plate_color_suffix)
    save_dir = resolve_save_path(photo_type, plate, config)
    full_path = os.path.join(save_dir, filename)

    # Final path traversal check
    real_dir = os.path.realpath(save_dir)
    real_path = os.path.realpath(full_path)
    if not real_path.startswith(real_dir):
        return {"ok": False, "error": "Đường dẫn không hợp lệ"}

    with open(full_path, "wb") as f:
        f.write(file_bytes)

    return {"ok": True, "path": full_path, "filename": filename}
```

- [ ] **Step 4: Run tests — expect PASS**

```bash
cd D:\inspection-camera\backend
venv\Scripts\python -m pytest tests/test_photo_handler.py -v
```

- [ ] **Step 5: Commit**

```bash
git add backend/photo_handler.py backend/tests/test_photo_handler.py
git commit -m "feat: photo handler with plate logic, filename gen, validation"
git push
```

---

## Task 3: Vehicle Service — Query DB + Photo Status

**Files:**
- Create: `backend/vehicle_service.py`
- Create: `backend/tests/test_vehicle_service.py`

**Interfaces:**
- Consumes: `PhotoConfig`, `extract_plate_color()`, `resolve_save_path()`, `build_filename()` from Tasks 1-2
- Produces:
  - `def get_vehicles_today(db_path: str, date: str | None, config: PhotoConfig) -> list[dict]` — returns list of vehicle dicts with `photos_taken` field

- [ ] **Step 1: Write failing tests**

```python
# backend/tests/test_vehicle_service.py
import os
import sqlite3
import pytest
from vehicle_service import get_vehicles_today
from config import PhotoConfig

@pytest.fixture
def mock_db(tmp_path):
    """Create a minimal SQLite DB matching ptcgdb.db schema."""
    db_path = str(tmp_path / "test.db")
    conn = sqlite3.connect(db_path)
    cur = conn.cursor()
    cur.execute("""
        CREATE TABLE vehicles (
            biendk_id TEXT PRIMARY KEY,
            biendk TEXT,
            biendk_clean TEXT,
            chupt TEXT,
            nhanhieu TEXT,
            tenloaipt TEXT
        )
    """)
    cur.execute("""
        CREATE TABLE inspections (
            sophieu TEXT,
            biendk_id TEXT,
            ngaykd TEXT,
            giokd TEXT,
            ketluan INTEGER
        )
    """)
    cur.execute("""
        INSERT INTO vehicles VALUES
        ('15A12345T', '15A-123.45T', '15A12345T', 'Nguyễn Văn A', 'TOYOTA', 'Ô tô con')
    """)
    cur.execute("""
        INSERT INTO vehicles VALUES
        ('11K2639', '11K-2639', '11K2639', 'Trần Văn B', 'HONDA', 'Ô tô con')
    """)
    cur.execute("""
        INSERT INTO inspections VALUES
        ('001/26', '15A12345T', '2026-09-27', '08:30', 1)
    """)
    cur.execute("""
        INSERT INTO inspections VALUES
        ('002/26', '11K2639', '2026-09-27', '09:15', 0)
    """)
    conn.commit()
    conn.close()
    return db_path

def test_get_vehicles_returns_list(mock_db):
    config = PhotoConfig()
    result = get_vehicles_today(mock_db, "2026-09-27", config)
    assert len(result) == 2

def test_vehicle_has_required_fields(mock_db):
    config = PhotoConfig()
    result = get_vehicles_today(mock_db, "2026-09-27", config)
    v = result[0]
    assert "plate" in v
    assert "plate_clean" in v
    assert "plate_color" in v
    assert "vehicle_type" in v
    assert "brand" in v
    assert "owner" in v
    assert "time" in v
    assert "result" in v
    assert "photos_taken" in v

def test_old_plate_has_null_color(mock_db):
    config = PhotoConfig()
    result = get_vehicles_today(mock_db, "2026-09-27", config)
    old = [v for v in result if v["plate_clean"] == "11K2639"][0]
    assert old["plate_color"] is None

def test_new_plate_has_color(mock_db):
    config = PhotoConfig()
    result = get_vehicles_today(mock_db, "2026-09-27", config)
    new = [v for v in result if v["plate_clean"] == "15A12345"][0]
    assert new["plate_color"] == "T"

def test_empty_date_returns_empty(mock_db):
    config = PhotoConfig()
    result = get_vehicles_today(mock_db, "2000-01-01", config)
    assert result == []

def test_vehicle_list_disabled_returns_none(mock_db):
    config = PhotoConfig()
    config.vehicle_list_enabled = False
    result = get_vehicles_today(mock_db, "2026-09-27", config)
    assert result is None
```

- [ ] **Step 2: Run tests — expect FAIL**

```bash
cd D:\inspection-camera\backend
venv\Scripts\python -m pytest tests/test_vehicle_service.py -v
```

- [ ] **Step 3: Implement `vehicle_service.py`**

```python
# backend/vehicle_service.py
import os
import sqlite3
from config import PhotoConfig
from photo_handler import extract_plate_color, resolve_save_path, build_filename

PHOTO_TYPES = ["rear_45", "front_45", "chassis", "passenger", "new_vehicle"]


def _check_photos_taken(plate: str, plate_color: str | None, config: PhotoConfig) -> list[str]:
    taken = []
    for pt in PHOTO_TYPES:
        try:
            save_dir = resolve_save_path(pt, plate, config)
            filename = build_filename(plate, plate_color, pt, 1 if pt in ("passenger", "new_vehicle") else None, config.plate_color_suffix)
            if os.path.exists(os.path.join(save_dir, filename)):
                taken.append(pt)
        except Exception:
            pass
    return taken


def get_vehicles_today(
    db_path: str, date: str | None, config: PhotoConfig
) -> list[dict] | None:
    if not config.vehicle_list_enabled:
        return None

    conn = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
    conn.row_factory = sqlite3.Row
    cur = conn.cursor()
    cur.execute(
        """
        SELECT v.biendk, v.biendk_clean, v.chupt, v.nhanhieu, v.tenloaipt,
               i.ngaykd, i.giokd, i.ketluan
        FROM inspections i
        JOIN vehicles v ON i.biendk_id = v.biendk_id
        WHERE i.ngaykd = ?
        ORDER BY i.giokd DESC
        """,
        (date,),
    )
    rows = cur.fetchall()
    conn.close()

    results = []
    for row in rows:
        plate_num, plate_color = extract_plate_color(row["biendk_clean"])
        photos_taken = _check_photos_taken(plate_num, plate_color, config)
        results.append({
            "plate": row["biendk"],
            "plate_clean": plate_num,
            "plate_color": plate_color,
            "vehicle_type": row["tenloaipt"],
            "brand": row["nhanhieu"],
            "owner": row["chupt"],
            "time": row["giokd"],
            "result": row["ketluan"],
            "photos_taken": photos_taken,
        })
    return results
```

- [ ] **Step 4: Run tests — expect PASS**

```bash
cd D:\inspection-camera\backend
venv\Scripts\python -m pytest tests/test_vehicle_service.py -v
```

- [ ] **Step 5: Commit**

```bash
git add backend/vehicle_service.py backend/tests/test_vehicle_service.py
git commit -m "feat: vehicle service with photo status checking"
git push
```

---

## Task 4: FastAPI App — All Endpoints + Integration Tests

**Files:**
- Create: `backend/main.py`
- Create: `backend/tests/test_api.py`
- Create: `backend/start_background.vbs`

**Interfaces:**
- Consumes: `PhotoConfig`, `load_config()`, `save_config()` from Task 1; `save_photo()`, `normalize_plate()`, `extract_plate_color()` from Task 2; `get_vehicles_today()` from Task 3
- Produces:
  - `POST /api/upload` — multipart upload endpoint
  - `DELETE /api/photos` — delete photo endpoint
  - `GET /api/vehicles/today` — vehicle list endpoint
  - `GET /api/config` — read config
  - `POST /api/config` — update config
  - `GET /api/health` — health check

- [ ] **Step 1: Write failing integration tests**

```python
# backend/tests/test_api.py
import os
import json
import sqlite3
import pytest
from fastapi.testclient import TestClient

# Patch config path before importing app
os.environ["PHOTO_CONFIG_PATH"] = ""  # will be set per-test

@pytest.fixture
def setup_env(tmp_path):
    config_path = str(tmp_path / "config.json")
    db_path = str(tmp_path / "test.db")
    photo_dir = str(tmp_path / "photos")
    os.makedirs(photo_dir, exist_ok=True)

    # Create test DB
    conn = sqlite3.connect(db_path)
    cur = conn.cursor()
    cur.execute("CREATE TABLE vehicles (biendk_id TEXT PRIMARY KEY, biendk TEXT, biendk_clean TEXT, chupt TEXT, nhanhieu TEXT, tenloaipt TEXT)")
    cur.execute("CREATE TABLE inspections (sophieu TEXT, biendk_id TEXT, ngaykd TEXT, giokd TEXT, ketluan INTEGER)")
    cur.execute("INSERT INTO vehicles VALUES ('15A12345T','15A-123.45T','15A12345T','Test Owner','TOYOTA','Ô tô con')")
    cur.execute("INSERT INTO inspections VALUES ('001/26','15A12345T','2026-09-27','08:30',1)")
    conn.commit()
    conn.close()

    os.environ["PHOTO_CONFIG_PATH"] = config_path
    os.environ["PTCGDB_PATH"] = db_path

    # Write config with test paths
    from config import PhotoConfig, save_config
    cfg = PhotoConfig()
    for key in cfg.paths:
        cfg.paths[key] = os.path.join(photo_dir, "{date}")
    save_config(cfg, config_path)

    from main import app
    return TestClient(app), tmp_path

FAKE_JPEG = b"\xff\xd8\xff\xe0" + b"\x00" * 200 + b"\xff\xd9"

def test_health(setup_env):
    client, _ = setup_env
    r = client.get("/api/health")
    assert r.status_code == 200
    assert r.json()["ok"] is True

def test_upload_success(setup_env):
    client, _ = setup_env
    r = client.post("/api/upload", data={"plate": "15A12345", "plate_color": "T", "photo_type": "rear_45"}, files={"file": ("test.jpg", FAKE_JPEG, "image/jpeg")})
    assert r.status_code == 200
    assert r.json()["ok"] is True
    assert r.json()["filename"] == "15A12345T.jpg"

def test_upload_non_jpeg_rejected(setup_env):
    client, _ = setup_env
    png = b"\x89PNG\r\n\x1a\n" + b"\x00" * 100
    r = client.post("/api/upload", data={"plate": "15A12345", "photo_type": "rear_45"}, files={"file": ("test.jpg", png, "image/jpeg")})
    assert r.status_code == 400

def test_upload_path_traversal_rejected(setup_env):
    client, _ = setup_env
    r = client.post("/api/upload", data={"plate": "../../../etc", "photo_type": "rear_45"}, files={"file": ("test.jpg", FAKE_JPEG, "image/jpeg")})
    assert r.status_code == 400

def test_get_config(setup_env):
    client, _ = setup_env
    r = client.get("/api/config")
    assert r.status_code == 200
    assert r.json()["server_port"] == 8095

def test_post_config(setup_env):
    client, _ = setup_env
    r = client.get("/api/config")
    cfg = r.json()
    cfg["jpeg_quality"] = 70
    r2 = client.post("/api/config", json=cfg)
    assert r2.status_code == 200
    assert r2.json()["ok"] is True
    # Verify persisted
    r3 = client.get("/api/config")
    assert r3.json()["jpeg_quality"] == 70

def test_vehicles_today(setup_env):
    client, _ = setup_env
    r = client.get("/api/vehicles/today?date=2026-09-27")
    assert r.status_code == 200
    data = r.json()
    assert len(data) >= 1
```

- [ ] **Step 2: Run tests — expect FAIL**

```bash
cd D:\inspection-camera\backend
venv\Scripts\python -m pytest tests/test_api.py -v
```

- [ ] **Step 3: Implement `main.py`**

```python
# backend/main.py
import os
import sys
from datetime import datetime, date
from fastapi import FastAPI, File, Form, UploadFile, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel

PROJECT_DIR = os.path.dirname(os.path.abspath(__file__))
if PROJECT_DIR not in sys.path:
    sys.path.insert(0, PROJECT_DIR)

from config import load_config, save_config, PhotoConfig
from photo_handler import save_photo, normalize_plate, extract_plate_color
from vehicle_service import get_vehicles_today

CONFIG_PATH = os.environ.get("PHOTO_CONFIG_PATH", os.path.join(PROJECT_DIR, "photo_config.json"))
DB_PATH = os.environ.get("PTCGDB_PATH", "C:\\PTCGDB_Online\\ptcgdb.db")

app = FastAPI(title="15-07D Photo Server", version="1.0.0")

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
    plate_color: str = Form(None),
    photo_type: str = Form(...),
    seq: int = Form(None),
):
    config = load_config(CONFIG_PATH)
    file_bytes = await file.read()

    result = save_photo(file_bytes, plate, plate_color, photo_type, seq, config)
    if not result["ok"]:
        raise HTTPException(status_code=400, detail=result["error"])
    return result


class DeleteRequest(BaseModel):
    plate: str
    photo_type: str
    seq: int | None = None


@app.delete("/api/photos")
def delete_photo(req: DeleteRequest):
    config = load_config(CONFIG_PATH)
    try:
        plate_clean = normalize_plate(req.plate)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))

    from photo_handler import build_filename, resolve_save_path
    plate_num, color = extract_plate_color(plate_clean)
    filename = build_filename(plate_num, color, req.photo_type, req.seq, config.plate_color_suffix)
    save_dir = resolve_save_path(req.photo_type, plate_num, config)
    full_path = os.path.join(save_dir, filename)

    if os.path.exists(full_path):
        os.remove(full_path)
        return {"ok": True, "deleted": full_path}
    raise HTTPException(status_code=404, detail="File không tồn tại")


@app.get("/api/vehicles/today")
def vehicles_today(date: str = None):
    config = load_config(CONFIG_PATH)
    if not config.vehicle_list_enabled:
        raise HTTPException(status_code=404, detail="Danh sách xe đã tắt")
    query_date = date or datetime.now().strftime("%Y-%m-%d")
    result = get_vehicles_today(DB_PATH, query_date, config)
    if result is None:
        raise HTTPException(status_code=404, detail="Danh sách xe đã tắt")
    return result


@app.get("/api/config")
def get_config():
    return load_config(CONFIG_PATH).model_dump()


@app.post("/api/config")
def post_config(config: PhotoConfig):
    save_config(config, CONFIG_PATH)
    return {"ok": True, "message": "Đã lưu cấu hình"}


if __name__ == "__main__":
    import uvicorn
    config = load_config(CONFIG_PATH)
    uvicorn.run(app, host="0.0.0.0", port=config.server_port, log_level="info")
```

- [ ] **Step 4: Run tests — expect PASS**

```bash
cd D:\inspection-camera\backend
venv\Scripts\python -m pytest tests/test_api.py -v
```

- [ ] **Step 5: Create `start_background.vbs`**

```vbs
' Inspection Camera Photo Server - Background Launcher
Option Explicit

Dim wmi, colProcesses
Set wmi = GetObject("winmgmts:{impersonationLevel=impersonate}!\\.\root\cimv2")
Set colProcesses = wmi.ExecQuery("SELECT CommandLine FROM Win32_Process WHERE Name = 'python.exe' AND CommandLine LIKE '%inspection-camera%main.py%'")
If colProcesses.Count > 0 Then
    WScript.Quit 0
End If

Dim sh, fso, pythonExe, targetScript
Set sh = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")

pythonExe = "C:\Users\t1507d\AppData\Local\hermes\hermes-agent\venv\Scripts\python.exe"
targetScript = "D:\inspection-camera\backend\main.py"

If Not fso.FileExists(pythonExe) Then
    pythonExe = "python.exe"
End If

sh.Run """" & pythonExe & """ """ & targetScript & """", 0, False
```

- [ ] **Step 6: Run all backend tests**

```bash
cd D:\inspection-camera\backend
venv\Scripts\python -m pytest tests/ -v
```

- [ ] **Step 7: Commit**

```bash
git add backend/main.py backend/tests/test_api.py backend/start_background.vbs
git commit -m "feat: FastAPI app with all endpoints + integration tests"
git push
```

---

## Task 5: Android Project Scaffold + Gradle + CI

**Files:**
- Create: `android/` — full Gradle project scaffold
- Create: `.github/workflows/build-apk.yml`
- Create: `android/app/src/main/java/com/ttdk1507d/inspectioncamera/model/PhotoType.kt`
- Create: `android/app/src/main/java/com/ttdk1507d/inspectioncamera/model/Vehicle.kt`
- Create: `android/app/src/main/java/com/ttdk1507d/inspectioncamera/model/AppConfig.kt`
- Create: `android/app/src/main/java/com/ttdk1507d/inspectioncamera/util/PlateUtil.kt`

**Interfaces:**
- Produces:
  - `enum class PhotoType` — REAR_45, FRONT_45, CHASSIS, PASSENGER, NEW_VEHICLE with `apiName`, `label`, `prefix`
  - `data class Vehicle` — plate, plateClean, plateColor, vehicleType, brand, owner, time, result, photosTaken
  - `data class AppConfig` — mirrors PhotoConfig from backend
  - `object PlateUtil` — `fun normalize(raw: String): String`, `fun extractColor(cleanPlate: String): Pair<String, String?>`

- [ ] **Step 1: Create Gradle project structure**

Create `android/settings.gradle.kts`:
```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "InspectionCamera"
include(":app")
```

Create `android/build.gradle.kts`:
```kotlin
plugins {
    id("com.android.application") version "8.5.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}
```

Create `android/app/build.gradle.kts`:
```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ttdk1507d.inspectioncamera"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ttdk1507d.inspectioncamera"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
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
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.core:core-ktx:1.13.1")

    // Image loading
    implementation("io.coil-kt:coil:2.7.0")

    // Background work
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}
```

Create `android/gradle/wrapper/gradle-wrapper.properties`:
```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.7-bin.zip
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

- [ ] **Step 2: Create data models**

Create `android/app/src/main/java/com/ttdk1507d/inspectioncamera/model/PhotoType.kt`:
```kotlin
package com.ttdk1507d.inspectioncamera.model

enum class PhotoType(
    val apiName: String,
    val label: String,
    val prefix: String,
    val multiPhoto: Boolean = false
) {
    REAR_45("rear_45", "Góc SAU 45°", ""),
    FRONT_45("front_45", "Góc TRƯỚC 45°", "bs"),
    CHASSIS("chassis", "Số khung / Khoang máy", "sk_"),
    PASSENGER("passenger", "Khoang hành khách", "", multiPhoto = true),
    NEW_VEHICLE("new_vehicle", "Ảnh xe mới", "", multiPhoto = true);
}
```

Create `android/app/src/main/java/com/ttdk1507d/inspectioncamera/model/Vehicle.kt`:
```kotlin
package com.ttdk1507d.inspectioncamera.model

import com.google.gson.annotations.SerializedName

data class Vehicle(
    val plate: String,
    @SerializedName("plate_clean") val plateClean: String,
    @SerializedName("plate_color") val plateColor: String?,
    @SerializedName("vehicle_type") val vehicleType: String,
    val brand: String,
    val owner: String,
    val time: String,
    val result: Int,
    @SerializedName("photos_taken") val photosTaken: List<String>
)
```

Create `android/app/src/main/java/com/ttdk1507d/inspectioncamera/util/PlateUtil.kt`:
```kotlin
package com.ttdk1507d.inspectioncamera.util

object PlateUtil {
    private val VALID_PLATE = Regex("^[A-Z0-9]+$")

    fun normalize(raw: String): String {
        val cleaned = raw.replace(Regex("[.\\-\\s]"), "").uppercase()
        require(cleaned.isNotEmpty() && VALID_PLATE.matches(cleaned)) {
            "Biển số không hợp lệ"
        }
        return cleaned
    }

    fun extractColor(cleanPlate: String): Pair<String, String?> {
        if (cleanPlate.isNotEmpty() && cleanPlate.last() in listOf('T', 'V', 'X')) {
            return cleanPlate.dropLast(1) to cleanPlate.last().toString()
        }
        return cleanPlate to null
    }
}
```

- [ ] **Step 3: Create AndroidManifest.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.CAMERA" />
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
    <uses-permission android:name="android.permission.VIBRATE" />

    <uses-feature android:name="android.hardware.camera" android:required="true" />

    <application
        android:allowBackup="true"
        android:label="Chụp ảnh KĐ 15-07D"
        android:theme="@style/Theme.InspectionCamera"
        android:supportsRtl="true">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
        <activity android:name=".CameraActivity" />
        <activity android:name=".ReviewActivity" />
        <activity android:name=".SettingsActivity" />
    </application>
</manifest>
```

- [ ] **Step 4: Create GitHub Actions CI**

Create `.github/workflows/build-apk.yml`:
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

- [ ] **Step 5: Create `.gitignore`**

```
# Python
__pycache__/
*.pyc
backend/venv/
backend/photo_config.json

# Android
android/.gradle/
android/build/
android/app/build/
android/local.properties
*.apk

# IDE
.idea/
*.iml
.vscode/
```

- [ ] **Step 6: Commit**

```bash
git add android/ .github/ .gitignore
git commit -m "feat: Android project scaffold + Gradle + CI workflow"
git push
```

---

## Task 6: Android Network Layer — ApiService + ApiClient + NetworkUtil

**Files:**
- Create: `android/app/src/main/java/com/ttdk1507d/inspectioncamera/api/ApiService.kt`
- Create: `android/app/src/main/java/com/ttdk1507d/inspectioncamera/api/ApiClient.kt`
- Create: `android/app/src/main/java/com/ttdk1507d/inspectioncamera/util/NetworkUtil.kt`
- Create: `android/app/src/main/java/com/ttdk1507d/inspectioncamera/util/PrefsManager.kt`

**Interfaces:**
- Consumes: `Vehicle`, `PhotoType` from Task 5
- Produces:
  - `interface ApiService` — Retrofit interface for all endpoints
  - `object ApiClient` — singleton creating Retrofit instance with configurable base URL
  - `object NetworkUtil` — `suspend fun resolveBaseUrl(lanUrl: String, tailscaleUrl: String): String` — probe LAN with 1.5s timeout, fallback Tailscale
  - `class PrefsManager(context: Context)` — read/write SharedPreferences for server IP, port, toggle, etc.

- [ ] **Step 1: Create `ApiService.kt`**

```kotlin
package com.ttdk1507d.inspectioncamera.api

import com.ttdk1507d.inspectioncamera.model.Vehicle
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

interface ApiService {
    @GET("/api/health")
    suspend fun health(): Response<Map<String, Any>>

    @Multipart
    @POST("/api/upload")
    suspend fun uploadPhoto(
        @Part file: MultipartBody.Part,
        @Part("plate") plate: RequestBody,
        @Part("plate_color") plateColor: RequestBody?,
        @Part("photo_type") photoType: RequestBody,
        @Part("seq") seq: RequestBody?
    ): Response<Map<String, Any>>

    @HTTP(method = "DELETE", path = "/api/photos", hasBody = true)
    suspend fun deletePhoto(@Body body: Map<String, Any?>): Response<Map<String, Any>>

    @GET("/api/vehicles/today")
    suspend fun getVehiclesToday(@Query("date") date: String? = null): Response<List<Vehicle>>

    @GET("/api/config")
    suspend fun getConfig(): Response<Map<String, Any>>

    @POST("/api/config")
    suspend fun postConfig(@Body config: Map<String, Any>): Response<Map<String, Any>>
}
```

- [ ] **Step 2: Create `ApiClient.kt`**

```kotlin
package com.ttdk1507d.inspectioncamera.api

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {
    private var retrofit: Retrofit? = null
    private var currentBaseUrl: String = ""

    fun getService(baseUrl: String): ApiService {
        if (retrofit == null || currentBaseUrl != baseUrl) {
            currentBaseUrl = baseUrl
            val client = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()

            retrofit = Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
        }
        return retrofit!!.create(ApiService::class.java)
    }
}
```

- [ ] **Step 3: Create `NetworkUtil.kt`**

```kotlin
package com.ttdk1507d.inspectioncamera.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL

object NetworkUtil {
    private const val PROBE_TIMEOUT_MS = 1500L

    suspend fun resolveBaseUrl(lanUrl: String, tailscaleUrl: String): String {
        return withContext(Dispatchers.IO) {
            val lanReachable = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                try {
                    val conn = URL("$lanUrl/api/health").openConnection() as HttpURLConnection
                    conn.connectTimeout = 1500
                    conn.readTimeout = 1500
                    conn.requestMethod = "GET"
                    val code = conn.responseCode
                    conn.disconnect()
                    code == 200
                } catch (e: Exception) {
                    false
                }
            } ?: false

            if (lanReachable) lanUrl else tailscaleUrl
        }
    }
}
```

- [ ] **Step 4: Create `PrefsManager.kt`**

```kotlin
package com.ttdk1507d.inspectioncamera.util

import android.content.Context
import android.content.SharedPreferences

class PrefsManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("inspection_camera", Context.MODE_PRIVATE)

    var lanIp: String
        get() = prefs.getString("lan_ip", "192.168.193.11") ?: "192.168.193.11"
        set(value) = prefs.edit().putString("lan_ip", value).apply()

    var tailscaleIp: String
        get() = prefs.getString("tailscale_ip", "100.81.114.84") ?: "100.81.114.84"
        set(value) = prefs.edit().putString("tailscale_ip", value).apply()

    var serverPort: Int
        get() = prefs.getInt("server_port", 8095)
        set(value) = prefs.edit().putInt("server_port", value).apply()

    var vehicleListEnabled: Boolean
        get() = prefs.getBoolean("vehicle_list_enabled", true)
        set(value) = prefs.edit().putBoolean("vehicle_list_enabled", value).apply()

    val lanUrl: String get() = "http://$lanIp:$serverPort"
    val tailscaleUrl: String get() = "http://$tailscaleIp:$serverPort"
}
```

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/ttdk1507d/inspectioncamera/api/
git add android/app/src/main/java/com/ttdk1507d/inspectioncamera/util/
git commit -m "feat: Android network layer — ApiService, ApiClient, NetworkUtil, PrefsManager"
git push
```

---

## Task 7: Android UI — 4 Screens + Theme + TimestampPainter

**Files:**
- Create: `android/app/src/main/res/values/colors.xml`
- Create: `android/app/src/main/res/values/strings.xml`
- Create: `android/app/src/main/res/values/themes.xml`
- Create: `android/app/src/main/res/layout/activity_main.xml`
- Create: `android/app/src/main/res/layout/activity_camera.xml`
- Create: `android/app/src/main/res/layout/activity_review.xml`
- Create: `android/app/src/main/res/layout/activity_settings.xml`
- Create: `android/app/src/main/res/layout/item_vehicle.xml`
- Create: `android/app/src/main/res/layout/item_photo_review.xml`
- Create: `android/app/src/main/java/com/ttdk1507d/inspectioncamera/util/TimestampPainter.kt`
- Create: `android/app/src/main/java/com/ttdk1507d/inspectioncamera/adapter/VehicleAdapter.kt`
- Create: `android/app/src/main/java/com/ttdk1507d/inspectioncamera/adapter/PhotoReviewAdapter.kt`
- Create: `android/app/src/main/java/com/ttdk1507d/inspectioncamera/MainActivity.kt`
- Create: `android/app/src/main/java/com/ttdk1507d/inspectioncamera/CameraActivity.kt`
- Create: `android/app/src/main/java/com/ttdk1507d/inspectioncamera/ReviewActivity.kt`
- Create: `android/app/src/main/java/com/ttdk1507d/inspectioncamera/SettingsActivity.kt`
- Create: `android/app/src/main/java/com/ttdk1507d/inspectioncamera/worker/PendingUploadWorker.kt`

**Interfaces:**
- Consumes: `ApiService`, `ApiClient`, `NetworkUtil`, `PrefsManager`, `PlateUtil`, `Vehicle`, `PhotoType` from Tasks 5-6

This is the largest task. It covers:
1. Material 3 Light theme (colors, strings, themes XML)
2. XML layouts for all 4 screens
3. TimestampPainter — burn date/time onto Bitmap using Canvas.drawText()
4. MainActivity — vehicle list or manual plate entry
5. CameraActivity — CameraX preview + 5 capture buttons
6. ReviewActivity — thumbnail grid with delete/re-capture
7. SettingsActivity — server config, connection test
8. PendingUploadWorker — WorkManager for offline retry
9. VehicleAdapter + PhotoReviewAdapter

**Implementation note:** This task is large because the UI components are tightly coupled. Each Activity depends on the network layer, models, and utils from prior tasks. The actual implementation files will be written following the layout and flow described in Spec §3. Each file corresponds to one screen or one utility. Write them in order: theme/resources → utils → adapters → Activities → Worker.

Full code for each file should follow the Spec §3.3–3.7 design rules (Light Mode only, 56dp buttons, 18sp+ fonts, Material 3, haptic feedback). Detailed Kotlin code for each file is provided in the implementation step — see the inline code blocks.

- [ ] **Step 1: Create theme resources** (`colors.xml`, `strings.xml`, `themes.xml`)
- [ ] **Step 2: Create XML layouts** (6 layout files per Spec §3.7 design rules)
- [ ] **Step 3: Implement `TimestampPainter.kt`** — Canvas.drawText with stroke, background band, configurable position
- [ ] **Step 4: Implement `VehicleAdapter.kt`** — RecyclerView adapter with photo status icons
- [ ] **Step 5: Implement `PhotoReviewAdapter.kt`** — Grid adapter with Coil image loading
- [ ] **Step 6: Implement `MainActivity.kt`** — plate input + vehicle list (gated by toggle)
- [ ] **Step 7: Implement `CameraActivity.kt`** — CameraX preview + 5 buttons + capture → resize → timestamp → upload flow
- [ ] **Step 8: Implement `ReviewActivity.kt`** — thumbnails with delete/re-capture
- [ ] **Step 9: Implement `SettingsActivity.kt`** — IP/port config, connection test button, toggles
- [ ] **Step 10: Implement `PendingUploadWorker.kt`** — WorkManager periodic retry for offline queue
- [ ] **Step 11: Commit**

```bash
git add android/
git commit -m "feat: Android UI — 4 screens, theme, timestamp painter, offline worker"
git push
```

---

## Task 8: End-to-End Smoke Test + Backend Deployment

**Files:**
- Modify: `backend/main.py` (if any adjustments needed)
- Create: `README.md`

**Interfaces:**
- Consumes: Everything from Tasks 1-7

- [ ] **Step 1: Start backend and verify endpoints manually**

```bash
cd D:\inspection-camera\backend
venv\Scripts\python main.py
```

Test in another terminal:
```bash
curl http://localhost:8095/api/health
curl http://localhost:8095/api/config
curl http://localhost:8095/api/vehicles/today
```

- [ ] **Step 2: Test upload with curl**

```bash
# Create a tiny test JPEG
python -c "open('test.jpg','wb').write(b'\xff\xd8\xff\xe0'+b'\x00'*200+b'\xff\xd9')"
curl -X POST http://localhost:8095/api/upload \
  -F "file=@test.jpg" \
  -F "plate=15A12345" \
  -F "plate_color=T" \
  -F "photo_type=rear_45"
```

Verify: file exists at `D:\Photos\YYYYMMDD\15A12345T.jpg`

- [ ] **Step 3: Register backend as startup task**

Add `start_background.vbs` to Task Scheduler or Startup folder.

- [ ] **Step 4: Write `README.md`**

Include: project overview, backend setup, APK download/install, configuration, troubleshooting.

- [ ] **Step 5: Push APK workflow and verify GitHub Actions builds**

```bash
git add README.md
git commit -m "docs: README with setup instructions"
git push
```

Check GitHub Actions tab → workflow should trigger on `android/**` changes.

- [ ] **Step 6: Final commit + tag**

```bash
git tag v1.0.0
git push --tags
```
