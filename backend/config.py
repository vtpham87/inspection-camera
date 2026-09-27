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
    photo_save_dir: str = "D:\\Photos"
    passenger_path: str = "D:\\Photos\\{date}\\{plate}"
    new_vehicle_path: str = "D:\\Photos\\{date}\\{plate}"
    sync_new_vehicle_45: bool = True
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

    def model_post_init(self, __context):
        root = self.photo_save_dir.rstrip("\\/")
        if root != "D:\\Photos" and all(v.startswith("D:\\Photos") for v in self.paths.values()):
            self.paths["rear_45"] = f"{root}\\{{date}}"
            self.paths["front_45"] = f"{root}\\{{date}}"
            self.paths["chassis"] = f"{root}\\{{date}}"
            if self.passenger_path == "D:\\Photos\\{date}\\{plate}":
                self.passenger_path = f"{root}\\{{date}}\\{{plate}}"
                self.paths["passenger"] = self.passenger_path
            if self.new_vehicle_path == "D:\\Photos\\{date}\\{plate}":
                self.new_vehicle_path = f"{root}\\{{date}}\\{{plate}}"
                self.paths["new_vehicle"] = self.new_vehicle_path

        # If passenger_path was explicitly set
        if self.passenger_path != "D:\\Photos\\{date}\\{plate}":
            p_val = self.passenger_path.rstrip("\\/")
            if "{plate}" not in p_val:
                p_val = f"{p_val}\\{{date}}\\{{plate}}" if "{date}" not in p_val else f"{p_val}\\{{plate}}"
            self.paths["passenger"] = p_val

        # If new_vehicle_path was explicitly set
        if self.new_vehicle_path != "D:\\Photos\\{date}\\{plate}":
            nv_val = self.new_vehicle_path.rstrip("\\/")
            if "{plate}" not in nv_val:
                nv_val = f"{nv_val}\\{{date}}\\{{plate}}" if "{date}" not in nv_val else f"{nv_val}\\{{plate}}"
            self.paths["new_vehicle"] = nv_val

CONFIG_DEFAULTS = PhotoConfig()

def load_config(path: str = "photo_config.json") -> PhotoConfig:
    if os.path.exists(path):
        try:
            with open(path, "r", encoding="utf-8") as f:
                data = json.load(f)
            return PhotoConfig(**data)
        except (json.JSONDecodeError, Exception):
            return CONFIG_DEFAULTS
    config = PhotoConfig()
    save_config(config, path)
    return config

def save_config(config: PhotoConfig, path: str = "photo_config.json") -> None:
    dirname = os.path.dirname(path)
    if dirname:
        os.makedirs(dirname, exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(config.model_dump(), f, indent=2, ensure_ascii=False)
