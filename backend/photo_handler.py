import os
import re
import shutil
import time
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


def should_omit_color_suffix(plate: str) -> bool:
    """Kiểm tra biển số có thuộc diện không thêm hậu tố màu (t/v/x) khi lưu hay không.

    1. Các xe có sê-ri 2 chữ cái đặc biệt: KT (quân đội làm kinh tế), LD (liên doanh), HC (hành chính/công vụ)
       ví dụ 15KT-123.45, 15LD-123.45, 15HC-123.45
    2. Biển cũ (không kết thúc bằng 5 số, ví dụ 11K2639)
    """
    clean = re.sub(r"[.\-\s]", "", plate).upper()
    # 1. Xe sê-ri KT, LD, HC (ví dụ 15KT, 15LD, 15HC, 29LD...)
    if re.search(r"^\d{2}(?:KT|LD|HC)", clean):
        return True
    # Bóc tách hậu tố L[1-9] và màu T/V/X nếu có để kiểm tra 5 số đuôi
    base = re.sub(r"(?:[TVX])?(?:L[1-9])?$", "", clean)
    if re.search(r"[TVX]$", base) and len(base) > 1 and base[-2].isdigit():
        base = base[:-1]
    # 2. Biển cũ (không kết thúc bằng 5 số)
    return not bool(re.search(r"\d{5}$", base))


def clean_plate_and_color(
    plate: str,
    plate_color: str | None = None,
    lan_kd: int | None = 1,
) -> tuple[str, str | None, int]:
    cleaned = re.sub(r"[.\-\s]", "", plate).upper()
    s = cleaned
    detected_color = None
    detected_lan = 1
    while True:
        m = re.search(r"([TVX])?L([1-9])$", s)
        if m:
            prefix = s[:m.start()]
            if prefix and prefix[-1].isdigit():
                if m.group(1):
                    detected_color = m.group(1)
                lan_num = int(m.group(2))
                if lan_num > 1:
                    detected_lan = lan_num
                s = prefix
                continue
        m2 = re.search(r"([TVX])+$", s)
        if m2:
            prefix = s[:m2.start()]
            if prefix and prefix[-1].isdigit():
                detected_color = m2.group(0)[-1]
                s = prefix
                continue
        break
    color = plate_color or detected_color
    if should_omit_color_suffix(s):
        color = None
    lan = lan_kd if (lan_kd and lan_kd > 1) else detected_lan
    return s, color, lan


def extract_plate_color(biendk_clean: str) -> tuple[str, str | None]:
    plate, color, _ = clean_plate_and_color(biendk_clean)
    return plate, color


def build_filename(
    plate: str,
    plate_color: str | None,
    photo_type: str,
    seq: int | None,
    color_suffix_enabled: bool,
    lan_kd: int | None = 1,
) -> str:
    plate, plate_color, lan_kd = clean_plate_and_color(plate, plate_color, lan_kd)
    if should_omit_color_suffix(plate):
        plate_color = None
    elif not plate_color and re.search(r"\d{5}$", plate):
        plate_color = "T"
    if lan_kd and lan_kd > 1:
        suffix = f"{plate_color or ''}L{lan_kd}"
        if photo_type == "rear_45":
            return f"{plate}{suffix}.jpg"
        elif photo_type == "front_45":
            return f"bs{plate}{suffix}.jpg"
        elif photo_type == "chassis":
            return f"sk_{plate}{suffix}.jpg"
        elif photo_type in ("passenger", "new_vehicle"):
            s = seq if seq else 1
            return f"{plate}{suffix}_{s}.jpg"
        else:
            raise ValueError(f"Loại ảnh không hợp lệ: {photo_type}")

    suffix = plate_color if (color_suffix_enabled and plate_color) else ""

    if photo_type == "rear_45":
        return f"{plate}{suffix}.jpg"
    elif photo_type == "front_45":
        return f"bs{plate}{suffix}.jpg"
    elif photo_type == "chassis":
        return f"sk_{plate}.jpg"
    elif photo_type in ("passenger", "new_vehicle"):
        s = seq if seq else 1
        return f"{plate}{suffix}_{s}.jpg"
    else:
        raise ValueError(f"Loại ảnh không hợp lệ: {photo_type}")


def resolve_save_path(
    photo_type: str,
    plate: str,
    config: PhotoConfig,
    create_dir: bool = False,
    date_str: str | None = None,
    plate_color: str | None = None,
) -> str:
    template = config.paths.get(photo_type, config.photo_save_dir)
    current_date = date_str.replace("-", "") if date_str is not None else datetime.now().strftime("%Y%m%d")
    clean_p, det_c, _ = clean_plate_and_color(plate, plate_color)
    if not clean_p or not VALID_PLATE_RE.match(clean_p):
        raise ValueError(f"Biển số không hợp lệ: {plate}")
    final_color = plate_color or det_c
    if should_omit_color_suffix(clean_p):
        final_color = None
    elif not final_color and re.search(r"\d{5}$", clean_p):
        final_color = "T"
    folder_plate = f"{clean_p}{final_color}" if (final_color and getattr(config, "plate_color_suffix", True)) else clean_p
    path = template.replace("{date}", current_date).replace("{plate}", folder_plate)
    if create_dir:
        os.makedirs(path, exist_ok=True)
    return path


def validate_jpeg(file_bytes: bytes) -> bool:
    if len(file_bytes) < 3:
        return False
    return file_bytes[:3] == JPEG_MAGIC


def sync_new_vehicle_photos(
    plate: str,
    plate_color: str | None,
    config: PhotoConfig,
    lan_kd: int | None = 1,
) -> None:
    plate, plate_color, lan_kd = clean_plate_and_color(plate, plate_color, lan_kd)
    if should_omit_color_suffix(plate):
        plate_color = None
    elif not plate_color and re.search(r"\d{5}$", plate):
        plate_color = "T"
    if not getattr(config, "sync_new_vehicle_45", True):
        return

    new_veh_dir = resolve_save_path("new_vehicle", plate, config, create_dir=False, plate_color=plate_color)
    if not os.path.exists(new_veh_dir) or not os.path.isdir(new_veh_dir):
        return

    # Only sync if new_veh_dir actually contains new_vehicle photos
    color_part = plate_color if (plate_color and config.plate_color_suffix) else ""
    prefix = f"{plate}{plate_color or ''}L{lan_kd}" if (lan_kd and lan_kd > 1) else f"{plate}{color_part}"
    existing_photos = [f for f in os.listdir(new_veh_dir) if f.lower().endswith(".jpg")]
    has_nv = any(f.startswith(f"{prefix}_") for f in existing_photos)
    if not has_nv:
        return

    rear_dir = resolve_save_path("rear_45", plate, config, create_dir=False, plate_color=plate_color)
    front_dir = resolve_save_path("front_45", plate, config, create_dir=False, plate_color=plate_color)

    # Sync matching rear_45 photo
    if os.path.exists(rear_dir):
        try:
            fname = build_filename(plate, plate_color, "rear_45", None, config.plate_color_suffix, lan_kd=lan_kd)
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
            fname = build_filename(plate, plate_color, "front_45", None, config.plate_color_suffix, lan_kd=lan_kd)
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
    lan_kd: int | None = 1,
) -> dict:
    plate, plate_color, lan_kd = clean_plate_and_color(plate, plate_color, lan_kd)
    # Validate plate
    try:
        plate = normalize_plate(plate)
    except ValueError as e:
        return {"ok": False, "error": str(e)}

    # Validate plate color
    if plate_color not in (None, "", "T", "V", "X"):
        return {"ok": False, "error": "Màu biển không hợp lệ"}

    if should_omit_color_suffix(plate):
        plate_color = None
    elif not plate_color and re.search(r"\d{5}$", plate):
        plate_color = "T"

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
    save_dir = resolve_save_path(photo_type, plate, config, create_dir=True, plate_color=plate_color)

    # Auto-increment seq when seq is None for multi-photo types
    if photo_type in ("passenger", "new_vehicle") and seq is None:
        color_part = plate_color if (plate_color and config.plate_color_suffix) else ""
        prefix = f"{plate}{plate_color or ''}L{lan_kd}" if (lan_kd and lan_kd > 1) else f"{plate}{color_part}"
        pattern = re.compile(rf"^{re.escape(prefix)}_(\d+)\.jpg$", re.IGNORECASE)
        existing = []
        if os.path.exists(save_dir) and os.path.isdir(save_dir):
            for fname in os.listdir(save_dir):
                m = pattern.match(fname)
                if m:
                    existing.append(int(m.group(1)))
        seq = max(existing) + 1 if existing else 1

    # Build filename and path
    filename = build_filename(plate, plate_color, photo_type, seq, config.plate_color_suffix, lan_kd=lan_kd)
    full_path = os.path.join(save_dir, filename)

    # Final path traversal check
    real_dir = os.path.realpath(save_dir)
    real_path = os.path.realpath(full_path)
    if not Path(real_path).is_relative_to(Path(real_dir)):
        return {"ok": False, "error": "Đường dẫn không hợp lệ"}

    # Atomic write with retry for network drive stability (e.g. Z:\ SMB timeouts)
    tmp_path = f"{full_path}.tmp_{os.getpid()}_{int(time.time() * 1000)}"
    last_err = None
    for attempt in range(3):
        try:
            with open(tmp_path, "wb") as f:
                f.write(file_bytes)
            os.replace(tmp_path, full_path)
            last_err = None
            break
        except (OSError, IOError) as e:
            last_err = e
            if os.path.exists(tmp_path):
                try:
                    os.remove(tmp_path)
                except OSError:
                    pass
            if attempt < 2:
                time.sleep(0.5)
    if last_err is not None:
        raise last_err

    if photo_type in ("new_vehicle", "rear_45", "front_45"):
        sync_new_vehicle_photos(plate, plate_color, config, lan_kd=lan_kd)

    return {"ok": True, "path": full_path, "filename": filename}
