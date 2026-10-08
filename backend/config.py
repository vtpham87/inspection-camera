import os
import json
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
        # Ảnh góc 45° lưu trực tiếp vào thư mục chọn, không tạo thư mục con theo ngày
        self.paths["rear_45"] = root
        self.paths["front_45"] = root
        self.paths["chassis"] = root

        # Sync root to passenger & new_vehicle if using defaults
        if root != "D:\\Photos":
            if self.passenger_path == "D:\\Photos\\{date}\\{plate}":
                self.passenger_path = f"{root}\\{{date}}\\{{plate}}"
            if self.new_vehicle_path == "D:\\Photos\\{date}\\{plate}":
                self.new_vehicle_path = f"{root}\\{{date}}\\{{plate}}"

        # If passenger_path is explicitly set or customized, sync to paths
        p_val = (self.passenger_path or "").rstrip("\\/")
        if p_val and "{plate}" not in p_val:
            p_val = f"{p_val}\\{{plate}}"
        if p_val:
            self.passenger_path = p_val
            self.paths["passenger"] = p_val

        # If new_vehicle_path is explicitly set or customized, sync to paths
        nv_val = (self.new_vehicle_path or "").rstrip("\\/")
        if nv_val and "{plate}" not in nv_val:
            nv_val = f"{nv_val}\\{{plate}}"
        if nv_val:
            self.new_vehicle_path = nv_val
            self.paths["new_vehicle"] = nv_val

    def model_post_init(self, __context):
        self.recalculate_paths()

CONFIG_DEFAULTS = PhotoConfig()

def load_config(path: str = "photo_config.json") -> PhotoConfig:
    if os.path.exists(path):
        try:
            with open(path, "r", encoding="utf-8") as f:
                data = json.load(f)
            return PhotoConfig(**data)
        except Exception:
            return CONFIG_DEFAULTS
    config = PhotoConfig()
    save_config(config, path)
    return config

def save_config(config: PhotoConfig, path: str = "photo_config.json") -> None:
    config.recalculate_paths()
    dirname = os.path.dirname(path)
    if dirname:
        os.makedirs(dirname, exist_ok=True)
    tmp_path = f"{path}.tmp_{os.getpid()}"
    with open(tmp_path, "w", encoding="utf-8") as f:
        json.dump(config.model_dump(), f, indent=2, ensure_ascii=False)
    os.replace(tmp_path, path)
