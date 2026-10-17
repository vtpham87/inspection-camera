import os
import re
import json
import logging
import threading
from typing import Any
from pydantic import BaseModel, field_validator

RESOLUTION_MAP = {
    "original": "original",
    "low": "low",
    "hd": "low",
    "720p": "low",
    "medium": "medium",
    "fhd": "medium",
    "1080p": "medium",
    "high": "high",
    "4k": "high",
}

CONFIG_PATH = os.environ.get("PHOTO_CONFIG_PATH") or os.path.join(os.path.dirname(os.path.abspath(__file__)), "photo_config.json")
DB_PATH = os.environ.get("PTCGDB_PATH") or "C:\\PTCGDB_Online\\ptcgdb.db"
WATCHER_DB_PATH = os.environ.get("WATCHER_DB_PATH") or "C:\\PTCGDB_Online\\waiting_vehicles.db"
ACCESS_DB_UNC_PATH = r"\\T1507\Data\DataPTCGDB\PT90_1507D.mdb"
ACCESS_DB_Z_PATH = r"Z:\DataPTCGDB\PT90_1507D.mdb"
ACCESS_DB_PATH = os.environ.get("PTCGDB_ACCESS_PATH") or ACCESS_DB_Z_PATH
SYSDB_PATH = os.environ.get("PTCGDB_MDW_PATH") or r"C:\PTCGDB\SysDb.mdw"

logger = logging.getLogger("config")

# Tài khoản đọc Access (ODBC): BẮT BUỘC đặt qua biến môi trường trên máy trạm (không còn mặc định trong mã).
ACCESS_DB_USER = os.environ.get("PTCGDB_ACCESS_USER") or ""
ACCESS_DB_PASSWORD = os.environ.get("PTCGDB_ACCESS_PASSWORD") or ""

def firebase_disabled() -> bool:
    """Môi trường dev đặt DISABLE_FIREBASE_SYNC=1: mọi thao tác đọc/ghi Firebase phải bị vô hiệu (tránh ghi đè dữ liệu prod)."""
    return (os.environ.get("DISABLE_FIREBASE_SYNC") or "").strip().lower() in ("1", "true", "yes")


# Khoá API cho các endpoint HTTP (header X-API-Key). Truy cập từ chính máy chủ (loopback) không cần khoá.
# Để trống = không kiểm tra (tương thích ngược, sẽ có cảnh báo khi khởi động).
API_KEY = os.environ.get("PTCGDB_API_KEY") or ""

# Token xác thực Firebase RTDB (query ?auth=). Cần khi đã bật rules yêu cầu đăng nhập.
FIREBASE_AUTH = os.environ.get("PTCGDB_FIREBASE_AUTH") or ""

# Gốc thư mục được phép lưu ảnh khi cấu hình đến từ điện thoại/HTTP. Phân tách bằng dấu ";".
ALLOWED_SAVE_ROOTS = [
    r.strip().rstrip("\\/").upper()
    for r in (os.environ.get("PTCGDB_ALLOWED_SAVE_ROOTS") or "Z:;D:").split(";")
    if r.strip()
]

_DRIVE_PATH_RE = re.compile(r"^[A-Za-z]:[\\/]")


def validate_save_path(value: str, field: str = "path") -> str:
    """Kiểm tra đường dẫn lưu ảnh nhận từ bên ngoài: đường dẫn ổ đĩa tuyệt đối, không '..', nằm trong gốc cho phép."""
    v = (value or "").strip()
    if not v:
        return v
    if not _DRIVE_PATH_RE.match(v):
        raise ValueError(f"{field}: phải là đường dẫn ổ đĩa tuyệt đối (vd Z:\\...)")
    if any(part == ".." for part in re.split(r"[\\/]+", v)):
        raise ValueError(f"{field}: không được chứa '..'")
    if ":" in v[2:]:
        raise ValueError(f"{field}: chứa ký tự ':' không hợp lệ (tránh luồng dữ liệu NTFS)")
    if re.search(r'[<>"|?*\x00-\x1f]', v.replace("{plate}", "").replace("{date}", "")):
        raise ValueError(f"{field}: chứa ký tự không hợp lệ")
    if v[:2].upper() not in ALLOWED_SAVE_ROOTS:
        raise ValueError(f"{field}: ổ đĩa {v[:2]} không nằm trong PTCGDB_ALLOWED_SAVE_ROOTS")
    return v


class TimestampConfig(BaseModel):
    enabled: bool = True
    format: str = "HH:mm:ss - dd/MM/yyyy"
    font_size: int = 28
    font_color: str = "#FFFFFF"
    font_bold: bool = True
    font_stroke_enabled: bool = True
    font_stroke_color: str = "#000000"
    font_stroke_width: float = 2.0
    background_color: str = "#00000000"
    position: str = "bottom_right"

class PhotoConfig(BaseModel):
    vehicle_list_enabled: bool = True
    server_port: int = 8095
    photo_save_dir: str = "D:\\Photos"
    passenger_path: str = "D:\\Photos\\{date}\\{plate}"
    new_vehicle_path: str = "D:\\Photos\\{date}\\{plate}"
    sync_new_vehicle_45: bool = True
    paths: dict[str, str] = {
        "rear_45": "D:\\Photos",
        "front_45": "D:\\Photos",
        "chassis": "D:\\Photos",
        "passenger": "D:\\Photos\\{date}\\{plate}",
        "new_vehicle": "D:\\Photos\\{date}\\{plate}",
    }
    jpeg_quality: int = 85
    plate_color_suffix: bool = True
    auto_start_with_windows: bool = True
    timestamp: TimestampConfig = TimestampConfig()
    photo_resolution: str = "original"

    @field_validator("photo_resolution", mode="before")
    @classmethod
    def normalize_photo_resolution(cls, v: Any) -> str:
        if isinstance(v, str):
            key = v.lower().strip()
            return RESOLUTION_MAP.get(key, "original")
        return "original"

    def recalculate_paths(self) -> None:
        self.plate_color_suffix = True
        root = self.photo_save_dir.rstrip("\\/")
        self.paths["rear_45"] = root
        self.paths["front_45"] = root
        self.paths["chassis"] = root

        def _sync_path(val: str, default_template: str) -> str:
            if root != "D:\\Photos" and val == default_template:
                val = f"{root}\\{{date}}\\{{plate}}"
            val = (val or "").rstrip("\\/")
            if val and "{plate}" not in val:
                val = f"{val}\\{{plate}}"
            return val

        self.passenger_path = _sync_path(self.passenger_path, "D:\\Photos\\{date}\\{plate}")
        if self.passenger_path:
            self.paths["passenger"] = self.passenger_path

        self.new_vehicle_path = _sync_path(self.new_vehicle_path, "D:\\Photos\\{date}\\{plate}")
        if self.new_vehicle_path:
            self.paths["new_vehicle"] = self.new_vehicle_path

    def model_post_init(self, __context):
        self.recalculate_paths()

CONFIG_DEFAULTS = PhotoConfig()

# Khoá tái nhập: bảo vệ chuỗi đọc-sửa-ghi cấu hình giữa API (/api/config) và luồng đồng bộ Firebase nền.
CONFIG_LOCK = threading.RLock()
# Bộ nhớ đệm theo đường dẫn: (chữ ký file, cấu hình hợp lệ gần nhất). Tránh mở file + parse JSON + validate ở mỗi request.
_CONFIG_CACHE: dict[str, tuple[tuple[int, int, int], "PhotoConfig"]] = {}


def _file_signature(path: str) -> tuple[int, int, int] | None:
    try:
        st = os.stat(path)
    except OSError:
        return None
    return (st.st_mtime_ns, st.st_size, st.st_ino)  # save_config ghi nguyên tử bằng os.replace nên st_ino đổi theo mỗi lần lưu


def load_config(path: str = CONFIG_PATH) -> PhotoConfig:
    """Đọc cấu hình (có đệm theo chữ ký file). Luôn trả bản sao: người gọi được phép sửa tại chỗ.

    File hỏng/đang ghi dở -> giữ cấu hình hợp lệ gần nhất thay vì rơi về mặc định (D:\\Photos) làm ảnh lưu sai chỗ.
    """
    sig = _file_signature(path)
    if sig is None:
        config = PhotoConfig()
        save_config(config, path)
        return config
    with CONFIG_LOCK:
        cached = _CONFIG_CACHE.get(path)
        if cached and cached[0] == sig:
            return cached[1].model_copy(deep=True)
        try:
            with open(path, "r", encoding="utf-8") as f:
                data = json.load(f)
            config = PhotoConfig(**data)
        except Exception as e:
            fallback = cached[1] if cached else PhotoConfig()
            logger.warning(
                "Không đọc được cấu hình %s (%s), dùng %s", path, e,
                "bản hợp lệ gần nhất" if cached else "mặc định",
            )
            config = fallback
        _CONFIG_CACHE[path] = (sig, config)  # đệm cả khi lỗi để không parse/log lại ở mỗi request tới khi file đổi
        return config.model_copy(deep=True)


def save_config(config: PhotoConfig, path: str = "photo_config.json") -> None:
    config.recalculate_paths()
    dirname = os.path.dirname(path)
    if dirname:
        os.makedirs(dirname, exist_ok=True)
    tmp_path = f"{path}.tmp_{os.getpid()}_{threading.get_ident()}"
    with CONFIG_LOCK:
        try:
            with open(tmp_path, "w", encoding="utf-8") as f:
                json.dump(config.model_dump(), f, indent=2, ensure_ascii=False)
            os.replace(tmp_path, path)
        except Exception:
            if os.path.exists(tmp_path):
                try:
                    os.remove(tmp_path)
                except OSError:
                    pass
            raise
