import os
import re
import shutil
import time
import threading
import uuid
from contextlib import nullcontext
from datetime import datetime
from functools import lru_cache
from pathlib import Path
from config import PhotoConfig

_SEQ_LOCK = threading.Lock()  # chỉ bảo vệ dict _SEQ_LOCKS; khoá thực tế theo từng thư mục+tiền tố ở _seq_lock()
_SEQ_LOCKS: dict[str, threading.Lock] = {}


def _seq_lock(key: str) -> threading.Lock:
    """Khoá riêng cho từng (thư mục, tiền tố biển số): ảnh của xe khác nhau không phải chờ nhau khi ổ mạng chậm."""
    with _SEQ_LOCK:
        return _SEQ_LOCKS.setdefault(key, threading.Lock())

MAX_FILE_SIZE = 15 * 1024 * 1024  # 15MB: dùng chung cho /api/upload và Firebase inbox (base64 <= 20MB)
JPEG_MAGIC = b"\xff\xd8\xff"
VALID_PLATE_RE = re.compile(r"^[A-Z0-9]+$")
VALID_PHOTO_TYPES = {"rear_45", "front_45", "chassis", "passenger", "new_vehicle"}
_PLATE_STRIP_RE = re.compile(r"[.\-\s]")


def normalize_plate(raw: str) -> str:
    cleaned = _PLATE_STRIP_RE.sub("", raw).upper()
    if not cleaned or not VALID_PLATE_RE.match(cleaned):
        raise ValueError("Biển số không hợp lệ")
    return cleaned


def should_omit_color_suffix(plate: str) -> bool:
    """Kiểm tra biển số có thuộc diện không thêm hậu tố màu (t/v/x) khi lưu hay không.

    1. Các xe có sê-ri 2 chữ cái đặc biệt: KT (quân đội làm kinh tế), LD (liên doanh), HC (hành chính/công vụ)
       ví dụ 15KT-123.45, 15LD-123.45, 15HC-123.45
    2. Biển cũ (không kết thúc bằng 5 số, ví dụ 11K2639)
    """
    clean = _PLATE_STRIP_RE.sub("", plate).upper()
    # 1. Xe sê-ri KT, LD, HC (ví dụ 15KT, 15LD, 15HC, 29LD...)
    if re.search(r"^\d{2}(?:KT|LD|HC)", clean):
        return True
    # Bóc tách hậu tố L[1-9] và màu T/V/X nếu có để kiểm tra 5 số đuôi
    base = re.sub(r"(?:[TVX])?(?:L[1-9])?$", "", clean)
    if re.search(r"[TVX]$", base) and len(base) > 1 and base[-2].isdigit():
        base = base[:-1]
    # 2. Biển cũ (không kết thúc bằng 5 số)
    return not bool(re.search(r"\d{5}$", base))


@lru_cache(maxsize=2048)
def clean_plate_and_color(
    plate: str,
    plate_color: str | None = None,
    lan_kd: int | None = 1,
) -> tuple[str, str | None, int]:
    cleaned = _PLATE_STRIP_RE.sub("", plate).upper()
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
    elif not color and re.search(r"\d{5}$", s):
        color = "T"
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

    is_lan2 = lan_kd is not None and lan_kd > 1
    lan_suffix = f"L{lan_kd}" if is_lan2 else ""

    # Theo SPEC v2.0: Chỉ rear_45 và front_45 có hậu tố màu (khi color_suffix_enabled=True và có plate_color)
    # chassis, passenger, new_vehicle không bao giờ có hậu tố màu
    color_part = plate_color if (color_suffix_enabled and plate_color) else ""

    if photo_type == "rear_45":
        return f"{plate}{color_part}{lan_suffix}.jpg"
    elif photo_type == "front_45":
        return f"bs{plate}{color_part}{lan_suffix}.jpg"
    elif photo_type == "chassis":
        return f"sk_{plate}{lan_suffix}.jpg"
    elif photo_type in ("passenger", "new_vehicle"):
        s = seq if seq else 1
        return f"{plate}{lan_suffix}_{s}.jpg"
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
    final_color = det_c

    folder_plate = f"{clean_p}{final_color}" if (final_color and config.plate_color_suffix) else clean_p
    path = template.replace("{date}", current_date).replace("{plate}", folder_plate)
    if create_dir:
        os.makedirs(path, exist_ok=True)
    return path


def validate_jpeg(file_bytes: bytes) -> bool:
    return file_bytes.startswith(JPEG_MAGIC)


def sync_new_vehicle_photos(
    plate: str,
    plate_color: str | None,
    config: PhotoConfig,
    lan_kd: int | None = 1,
) -> None:
    plate, plate_color, lan_kd = clean_plate_and_color(plate, plate_color, lan_kd)

    if not config.sync_new_vehicle_45:
        return

    new_veh_dir = resolve_save_path("new_vehicle", plate, config, create_dir=False, plate_color=plate_color)
    if not os.path.isdir(new_veh_dir):
        return

    # Only sync if new_veh_dir actually contains new_vehicle photos
    prefix = f"{plate}L{lan_kd}" if (lan_kd and lan_kd > 1) else plate
    if not any(f.startswith(f"{prefix}_") and f.lower().endswith(".jpg") for f in os.listdir(new_veh_dir)):
        return

    for ptype in ("rear_45", "front_45"):
        src_dir = resolve_save_path(ptype, plate, config, create_dir=False, plate_color=plate_color)
        if not os.path.exists(src_dir):
            continue
        try:
            fname = build_filename(plate, plate_color, ptype, None, config.plate_color_suffix, lan_kd=lan_kd)
        except ValueError:
            continue
        src = os.path.join(src_dir, fname)
        dst = os.path.join(new_veh_dir, fname)
        if os.path.exists(src) and os.path.getsize(src) > 10:
            if not os.path.exists(dst) or os.path.getsize(dst) < 10:
                _atomic_copy(src, dst)


def _atomic_copy(src: str, dst: str) -> None:
    """Chép qua file tạm rồi os.replace: không để lại bản chép dở trên ổ mạng khi rớt giữa chừng."""
    tmp = f"{dst}.tmp_{uuid.uuid4().hex}"
    try:
        shutil.copy2(src, tmp)
        os.replace(tmp, dst)
    except OSError:
        try:
            os.remove(tmp)
        except OSError:
            pass
        raise


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

    # Khi tự tăng seq, giữ khoá suốt bước chọn seq + ghi file để 2 request song song không trùng seq
    auto_seq = photo_type in ("passenger", "new_vehicle") and seq is None
    seq_prefix = f"{plate}L{lan_kd}" if (lan_kd and lan_kd > 1) else plate
    seq_guard = _seq_lock(f"{os.path.normcase(save_dir)}|{seq_prefix}") if auto_seq else nullcontext()
    with seq_guard:
        if auto_seq:
            prefix = seq_prefix
            pattern = re.compile(rf"^{re.escape(prefix)}_(\d+)\.jpg$", re.IGNORECASE)
            existing = []
            if os.path.isdir(save_dir):
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
        tmp_path = f"{full_path}.tmp_{uuid.uuid4().hex}"  # duy nhất cho mỗi lần ghi (2 request cùng đích không giẫm nhau)
        last_err = None
        for attempt in range(3):
            try:
                with open(tmp_path, "wb") as f:
                    f.write(file_bytes)
                os.replace(tmp_path, full_path)
                last_err = None
                break
            except OSError as e:
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
