import os
import json
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

def test_timestamp_defaults_match_spec():
    ts = CONFIG_DEFAULTS.timestamp
    assert ts.format == "HH:mm:ss - dd/MM/yyyy"
    assert ts.font_size == 28
    assert ts.font_color == "#FFFFFF"
    assert ts.font_bold is True
    assert ts.font_stroke_enabled is True
    assert ts.font_stroke_color == "#000000"
    assert ts.font_stroke_width == 4.0
    assert ts.background_color == "#00000000"

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

def test_save_config_creates_parent_dir(tmp_path):
    path = str(tmp_path / "sub" / "nested" / "config.json")
    cfg = CONFIG_DEFAULTS.model_copy()
    save_config(cfg, path)
    assert os.path.exists(path)
    assert load_config(path).server_port == 8095

def test_load_config_corrupt_returns_defaults(tmp_path):
    path = str(tmp_path / "corrupt.json")
    with open(path, "w", encoding="utf-8") as f:
        f.write("{ invalid json")
    loaded = load_config(path)
    assert loaded == CONFIG_DEFAULTS

def test_photo_45_saves_directly_to_photo_save_dir():
    cfg = PhotoConfig(photo_save_dir="Z:\\Anh Phuong Tien")
    assert cfg.paths["rear_45"] == "Z:\\Anh Phuong Tien"
    assert cfg.paths["front_45"] == "Z:\\Anh Phuong Tien"
    assert cfg.paths["chassis"] == "Z:\\Anh Phuong Tien"
    assert "{date}" not in cfg.paths["rear_45"]

def test_default_photo_45_paths_have_no_date():
    assert CONFIG_DEFAULTS.paths["rear_45"] == "D:\\Photos"
    assert CONFIG_DEFAULTS.paths["front_45"] == "D:\\Photos"
    assert "{date}" not in CONFIG_DEFAULTS.paths["rear_45"]

def test_load_config_overwrites_stale_date_paths(tmp_path):
    path = str(tmp_path / "config.json")
    stale_data = {
        "photo_save_dir": "Z:\\Anh Phuong Tien",
        "paths": {
            "rear_45": "Z:\\Anh Phuong Tien\\{date}",
            "front_45": "Z:\\Anh Phuong Tien\\{date}",
            "passenger": "Z:\\Anh Khoang HK\\{plate}",
            "new_vehicle": "Z:\\Anh xe moi\\{plate}"
        }
    }
    with open(path, "w", encoding="utf-8") as f:
        json.dump(stale_data, f)
    loaded = load_config(path)
    assert loaded.paths["rear_45"] == "Z:\\Anh Phuong Tien"
    assert loaded.paths["front_45"] == "Z:\\Anh Phuong Tien"
    assert "{date}" not in loaded.paths["rear_45"]

def test_photo_resolution_normalization():
    assert PhotoConfig(photo_resolution="720p").photo_resolution == "low"
    assert PhotoConfig(photo_resolution="hd").photo_resolution == "low"
    assert PhotoConfig(photo_resolution="low").photo_resolution == "low"
    assert PhotoConfig(photo_resolution="1080p").photo_resolution == "medium"
    assert PhotoConfig(photo_resolution="fhd").photo_resolution == "medium"
    assert PhotoConfig(photo_resolution="medium").photo_resolution == "medium"
    assert PhotoConfig(photo_resolution="4k").photo_resolution == "high"
    assert PhotoConfig(photo_resolution="high").photo_resolution == "high"
    assert PhotoConfig(photo_resolution="original").photo_resolution == "original"
    assert PhotoConfig(photo_resolution="ORIGINAL").photo_resolution == "original"
    assert PhotoConfig(photo_resolution="UNKNOWN").photo_resolution == "original"

