import os
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
    cur.execute("CREATE TABLE inspections (sophieu TEXT, biendk_id TEXT, ngaykd TEXT, giokd TEXT, ketluan INTEGER, sotem TEXT)")
    cur.execute("INSERT INTO vehicles VALUES ('15A12345T','15A-123.45T','15A12345T','Test Owner','TOYOTA','Ô tô con')")
    cur.execute("INSERT INTO inspections VALUES ('001/26','15A12345T','2026-09-27','08:30',0,'')")
    conn.commit()
    conn.close()

    os.environ["PHOTO_CONFIG_PATH"] = config_path
    os.environ["PTCGDB_PATH"] = db_path

    # Write config with test paths
    from config import PhotoConfig, save_config
    cfg = PhotoConfig(photo_save_dir=photo_dir)
    for key in cfg.paths:
        cfg.paths[key] = os.path.join(photo_dir, "{date}")
    save_config(cfg, config_path)

    from main import app
    import main
    main.CONFIG_PATH = config_path
    main.DB_PATH = db_path

    return TestClient(app), tmp_path

FAKE_JPEG = b"\xff\xd8\xff\xe0" + b"\x00" * 200 + b"\xff\xd9"

def test_health(setup_env):
    client, _ = setup_env
    r = client.get("/api/health")
    assert r.status_code == 200
    assert r.json()["ok"] is True
    assert r.json()["server"] == "15-07D Photo Server"
    assert "time" in r.json()

def test_upload_success(setup_env):
    client, _ = setup_env
    r = client.post(
        "/api/upload",
        data={"plate": "15A12345", "plate_color": "T", "photo_type": "rear_45"},
        files={"file": ("test.jpg", FAKE_JPEG, "image/jpeg")},
    )
    assert r.status_code == 200
    assert r.json()["ok"] is True
    assert r.json()["filename"] == "15A12345T.jpg"

def test_upload_non_jpeg_rejected(setup_env):
    client, _ = setup_env
    png = b"\x89PNG\r\n\x1a\n" + b"\x00" * 100
    r = client.post(
        "/api/upload",
        data={"plate": "15A12345", "photo_type": "rear_45"},
        files={"file": ("test.jpg", png, "image/jpeg")},
    )
    assert r.status_code == 400

def test_upload_path_traversal_rejected(setup_env):
    client, _ = setup_env
    r = client.post(
        "/api/upload",
        data={"plate": "../../../etc", "photo_type": "rear_45"},
        files={"file": ("test.jpg", FAKE_JPEG, "image/jpeg")},
    )
    assert r.status_code == 400

def test_upload_with_seq(setup_env):
    client, _ = setup_env
    r = client.post(
        "/api/upload",
        data={"plate": "15A12345", "photo_type": "passenger", "seq": 2},
        files={"file": ("test.jpg", FAKE_JPEG, "image/jpeg")},
    )
    assert r.status_code == 200
    assert r.json()["ok"] is True
    assert r.json()["filename"] == "15A12345T_2.jpg"

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
    cfg["photo_resolution"] = "medium"
    r2 = client.post("/api/config", json=cfg)
    assert r2.status_code == 200
    assert r2.json()["ok"] is True
    # Verify persisted
    r3 = client.get("/api/config")
    assert r3.json()["jpeg_quality"] == 70
    assert r3.json()["photo_resolution"] == "medium"


def test_post_config_photo_resolution_variants(setup_env):
    client, _ = setup_env
    # 1. Test "high" / "4k"
    r = client.post("/api/config", json={"photo_resolution": "4k"})
    assert r.status_code == 200
    r_get = client.get("/api/config")
    assert r_get.json()["photo_resolution"] == "high"

    # 2. Test "1080p" -> "medium"
    r = client.post("/api/config", json={"photo_resolution": "1080p"})
    assert r.status_code == 200
    r_get = client.get("/api/config")
    assert r_get.json()["photo_resolution"] == "medium"

    # 3. Test "720p" -> "low"
    r = client.post("/api/config", json={"photo_resolution": "720p"})
    assert r.status_code == 200
    r_get = client.get("/api/config")
    assert r_get.json()["photo_resolution"] == "low"

    # 4. Test "original"
    r = client.post("/api/config", json={"photo_resolution": "original"})
    assert r.status_code == 200
    r_get = client.get("/api/config")
    assert r_get.json()["photo_resolution"] == "original"


def test_upload_lan_kd_2(setup_env):
    client, _ = setup_env
    r = client.post(
        "/api/upload",
        data={"plate": "15A12345", "plate_color": "T", "photo_type": "rear_45", "lan_kd": 2},
        files={"file": ("test.jpg", FAKE_JPEG, "image/jpeg")},
    )
    assert r.status_code == 200
    assert r.json()["ok"] is True
    assert r.json()["filename"] == "15A12345TL2.jpg"


def test_upload_lan_kd_2_front(setup_env):
    client, _ = setup_env
    r = client.post(
        "/api/upload",
        data={"plate": "15A12345", "plate_color": "T", "photo_type": "front_45", "lan_kd": 2},
        files={"file": ("test.jpg", FAKE_JPEG, "image/jpeg")},
    )
    assert r.status_code == 200
    assert r.json()["ok"] is True
    assert r.json()["filename"] == "bs15A12345TL2.jpg"


def test_check_plate_endpoint(setup_env):
    client, _ = setup_env
    r = client.get("/api/vehicles/check-plate?plate=15A12345&plate_color=T&date=2026-09-27")
    assert r.status_code == 200
    data = r.json()
    assert data["plate"] == "15A12345"
    assert "suggest_lan_2" in data


def test_vehicles_today(setup_env):
    client, _ = setup_env
    r = client.get("/api/vehicles/today?date=2026-09-27")
    assert r.status_code == 200
    data = r.json()
    assert len(data) >= 1
    assert data[0]["plate_clean"] == "15A12345"

def test_vehicles_today_default_date(setup_env):
    client, _ = setup_env
    r = client.get("/api/vehicles/today")
    assert r.status_code == 200
    assert isinstance(r.json(), list)

def test_vehicles_today_disabled(setup_env):
    client, _ = setup_env
    r = client.get("/api/config")
    cfg = r.json()
    cfg["vehicle_list_enabled"] = False
    client.post("/api/config", json=cfg)

    r2 = client.get("/api/vehicles/today")
    assert r2.status_code == 200
    assert r2.json() == []

def test_delete_photo_success(setup_env):
    client, _ = setup_env
    # Upload photo first
    r_up = client.post(
        "/api/upload",
        data={"plate": "15A12345", "plate_color": "T", "photo_type": "rear_45"},
        files={"file": ("test.jpg", FAKE_JPEG, "image/jpeg")},
    )
    assert r_up.status_code == 200

    # Delete photo
    r_del = client.request(
        "DELETE",
        "/api/photos",
        json={"plate": "15A12345T", "photo_type": "rear_45"},
    )
    assert r_del.status_code == 200
    assert r_del.json()["ok"] is True
    assert "deleted" in r_del.json()

def test_delete_photo_not_found(setup_env):
    client, _ = setup_env
    r = client.request(
        "DELETE",
        "/api/photos",
        json={"plate": "15A99999", "photo_type": "rear_45"},
    )
    assert r.status_code == 404
    assert r.json()["detail"] == "File không tồn tại"

def test_delete_photo_invalid_plate(setup_env):
    client, _ = setup_env
    r = client.request(
        "DELETE",
        "/api/photos",
        json={"plate": "invalid!plate@", "photo_type": "rear_45"},
    )
    assert r.status_code == 400


def test_delete_photo_without_color_suffix_fallback(setup_env):
    client, _ = setup_env
    r_up = client.post(
        "/api/upload",
        data={"plate": "15A12345", "plate_color": "T", "photo_type": "rear_45"},
        files={"file": ("test.jpg", FAKE_JPEG, "image/jpeg")},
    )
    assert r_up.status_code == 200

    r_del = client.request(
        "DELETE",
        "/api/photos",
        json={"plate": "15A12345", "photo_type": "rear_45"},
    )
    assert r_del.status_code == 200
    assert r_del.json()["ok"] is True


def test_delete_old_plate(setup_env):
    client, _ = setup_env
    r_up = client.post(
        "/api/upload",
        data={"plate": "11K2639", "photo_type": "rear_45"},
        files={"file": ("test.jpg", FAKE_JPEG, "image/jpeg")},
    )
    assert r_up.status_code == 200

    r_del = client.request(
        "DELETE",
        "/api/photos",
        json={"plate": "11K2639", "photo_type": "rear_45"},
    )
    assert r_del.status_code == 200
    assert r_del.json()["ok"] is True

def test_delete_photo_invalid_photo_type(setup_env):
    client, _ = setup_env
    r = client.request(
        "DELETE",
        "/api/photos",
        json={"plate": "15A12345", "photo_type": "invalid_type"},
    )
    assert r.status_code == 400
    assert r.json()["detail"] == "Loại ảnh không hợp lệ"

def test_upload_invalid_plate_color(setup_env):
    client, _ = setup_env
    r = client.post(
        "/api/upload",
        data={"plate": "15A12345", "plate_color": "INVALID", "photo_type": "rear_45"},
        files={"file": ("test.jpg", FAKE_JPEG, "image/jpeg")},
    )
    assert r.status_code == 400
    assert r.json()["detail"] == "Màu biển không hợp lệ"

def test_upload_auto_seq_passenger(setup_env):
    client, _ = setup_env
    r1 = client.post(
        "/api/upload",
        data={"plate": "15A12345", "photo_type": "passenger"},
        files={"file": ("test1.jpg", FAKE_JPEG, "image/jpeg")},
    )
    assert r1.status_code == 200
    assert r1.json()["filename"] == "15A12345T_1.jpg"

    r2 = client.post(
        "/api/upload",
        data={"plate": "15A12345", "photo_type": "passenger"},
        files={"file": ("test2.jpg", FAKE_JPEG, "image/jpeg")},
    )
    assert r2.status_code == 200
    assert r2.json()["filename"] == "15A12345T_2.jpg"


def test_index_settings_html(setup_env):
    client, _ = setup_env
    r = client.get("/")
    assert r.status_code == 200
    assert "HỆ THỐNG CHỤP ẢNH KIỂM ĐỊNH 15-07D" in r.text

    r_settings = client.get("/settings")
    assert r_settings.status_code == 200
    assert "photo_save_dir" in r_settings.text


def test_check_path_api(setup_env, tmp_path):
    client, _ = setup_env
    test_dir = str(tmp_path / "valid_photos")
    r = client.post("/api/check-path", json={"path": test_dir})
    assert r.status_code == 200
    data = r.json()
    assert data["ok"] is True
    assert data["exists"] is True

    r_empty = client.post("/api/check-path", json={"path": ""})
    assert r_empty.status_code == 200
    assert r_empty.json()["ok"] is False


def test_upload_validations(setup_env):
    client, _ = setup_env
    # Invalid photo type
    r_type = client.post(
        "/api/upload",
        data={"plate": "15A12345", "photo_type": "hacker_photo"},
        files={"file": ("test.jpg", FAKE_JPEG, "image/jpeg")},
    )
    assert r_type.status_code == 400
    assert "Loại ảnh không hợp lệ" in r_type.json()["detail"]

    # Invalid seq
    r_seq = client.post(
        "/api/upload",
        data={"plate": "15A12345", "photo_type": "rear_45", "seq": 999},
        files={"file": ("test.jpg", FAKE_JPEG, "image/jpeg")},
    )
    assert r_seq.status_code == 400
    assert "Thứ tự ảnh (seq) không hợp lệ" in r_seq.json()["detail"]

    # Empty file
    r_empty = client.post(
        "/api/upload",
        data={"plate": "15A12345", "photo_type": "rear_45"},
        files={"file": ("test.jpg", b"", "image/jpeg")},
    )
    assert r_empty.status_code == 400
    assert "File ảnh rỗng" in r_empty.json()["detail"]


