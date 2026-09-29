import os
import re
import shutil
from datetime import datetime
from pathlib import Path
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


def resolve_save_path(
    photo_type: str,
    plate: str,
    config: PhotoConfig,
    create_dir: bool = False,
    date_str: str | None = None,
) -> str:
    template = config.paths.get(photo_type, config.photo_save_dir)
    current_date = date_str.replace("-", "") if date_str is not None else datetime.now().strftime("%Y%m%d")
    path = template.replace("{date}", current_date).replace("{plate}", plate)
    if create_dir:
        os.makedirs(path, exist_ok=True)
    return path


def validate_jpeg(file_bytes: bytes) -> bool:
    if len(file_bytes) < 3:
        return False
    return file_bytes[:3] == JPEG_MAGIC


def sync_new_vehicle_photos(plate: str, plate_color: str | None, config: PhotoConfig) -> None:
    if not getattr(config, "sync_new_vehicle_45", True):
        return

    new_veh_dir = resolve_save_path("new_vehicle", plate, config, create_dir=False)
    if not os.path.exists(new_veh_dir) or not os.path.isdir(new_veh_dir):
        return

    # Only sync if new_veh_dir actually contains new_vehicle photos
    existing_photos = [f for f in os.listdir(new_veh_dir) if f.lower().endswith(".jpg")]
    has_nv = any(f.startswith(f"{plate}_") for f in existing_photos)
    if not has_nv:
        return

    rear_dir = resolve_save_path("rear_45", plate, config, create_dir=False)
    front_dir = resolve_save_path("front_45", plate, config, create_dir=False)

    # Sync matching rear_45 photo
    if os.path.exists(rear_dir):
        try:
            fname = build_filename(plate, plate_color, "rear_45", None, config.plate_color_suffix)
            src = os.path.join(rear_dir, fname)
            dst = os.path.join(new_veh_dir, fname)
            if os.path.exists(src) and os.path.getsize(src) > 10:
                if not os.path.exists(dst) or os.path.getsize(dst) < 10:
                    shutil.copy2(src, dst)
        except ValueError:
            pass

    # Sync matching front_45 photo
    if os.path.exists(front_dir):
        try:
            fname = build_filename(plate, plate_color, "front_45", None, config.plate_color_suffix)
            src = os.path.join(front_dir, fname)
            dst = os.path.join(new_veh_dir, fname)
            if os.path.exists(src) and os.path.getsize(src) > 10:
                if not os.path.exists(dst) or os.path.getsize(dst) < 10:
                    shutil.copy2(src, dst)
        except ValueError:
            pass


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

    # Validate plate color
    if plate_color not in (None, "", "T", "V", "X"):
        return {"ok": False, "error": "Màu biển không hợp lệ"}

    # Validate photo type
    if photo_type not in VALID_PHOTO_TYPES:
        return {"ok": False, "error": f"Loại ảnh không hợp lệ: {photo_type}"}

    # Validate JPEG
    if not validate_jpeg(file_bytes):
        return {"ok": False, "error": "File không phải định dạng JPEG"}

    # Validate size
    if len(file_bytes) > MAX_FILE_SIZE:
        return {"ok": False, "error": f"File vượt quá {MAX_FILE_SIZE // (1024*1024)}MB"}

    # Only create directory when actually saving photo
    save_dir = resolve_save_path(photo_type, plate, config, create_dir=True)

    # Auto-increment seq when seq is None for multi-photo types
    if photo_type in ("passenger", "new_vehicle") and seq is None:
        pattern = re.compile(rf"^{re.escape(plate)}_(\d+)\.jpg$", re.IGNORECASE)
        existing = []
        if os.path.exists(save_dir) and os.path.isdir(save_dir):
            for fname in os.listdir(save_dir):
                m = pattern.match(fname)
                if m:
                    existing.append(int(m.group(1)))
        seq = max(existing) + 1 if existing else 1

    # Build filename and path
    filename = build_filename(plate, plate_color, photo_type, seq, config.plate_color_suffix)
    full_path = os.path.join(save_dir, filename)

    # Final path traversal check
    real_dir = os.path.realpath(save_dir)
    real_path = os.path.realpath(full_path)
    if not Path(real_path).is_relative_to(Path(real_dir)):
        return {"ok": False, "error": "Đường dẫn không hợp lệ"}

    with open(full_path, "wb") as f:
        f.write(file_bytes)

    if photo_type in ("new_vehicle", "rear_45", "front_45"):
        sync_new_vehicle_photos(plate, plate_color, config)

    return {"ok": True, "path": full_path, "filename": filename}
