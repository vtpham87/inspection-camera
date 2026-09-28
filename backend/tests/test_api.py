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
    cur.execute("CREATE TABLE inspections (sophieu TEXT, biendk_id TEXT, ngaykd TEXT, giokd TEXT, ketluan INTEGER, sotem TEXT)")
    cur.execute("INSERT INTO vehicles VALUES ('15A12345T','15A-123.45T','15A12345T','Test Owner','TOYOTA','Ô tô con')")
    cur.execute("INSERT INTO inspections VALUES ('001/26','15A12345T','2026-09-27','08:30',0,'')")
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
    assert r.json()["filename"] == "15A12345_2.jpg"

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
    assert r1.json()["filename"] == "15A12345_1.jpg"

    r2 = client.post(
        "/api/upload",
        data={"plate": "15A12345", "photo_type": "passenger"},
        files={"file": ("test2.jpg", FAKE_JPEG, "image/jpeg")},
    )
    assert r2.status_code == 200
    assert r2.json()["filename"] == "15A12345_2.jpg"

