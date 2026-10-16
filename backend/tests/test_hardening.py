"""Test cho các bản vá rà soát v1.6.0 (bảo mật, race condition, vòng lặp lỗi, hiệu năng)."""
import os
import threading
from unittest.mock import patch

import pytest
from fastapi.testclient import TestClient

import firebase_sync
import vehicle_service
from config import PhotoConfig, save_config
from photo_handler import save_photo

JPEG = b"\xff\xd8\xff\xe0" + b"0" * 64


@pytest.fixture
def client(tmp_path, monkeypatch):
    cfg_path = str(tmp_path / "config.json")
    photos = str(tmp_path / "photos")
    cfg = PhotoConfig(photo_save_dir=photos, passenger_path=photos + "\\{plate}", new_vehicle_path=photos + "\\{plate}")
    for key in cfg.paths:
        cfg.paths[key] = os.path.join(photos, "{date}")
    save_config(cfg, cfg_path)
    monkeypatch.setenv("PHOTO_CONFIG_PATH", cfg_path)
    monkeypatch.setenv("PTCGDB_PATH", str(tmp_path / "none.db"))
    from main import app

    async def from_lan(scope, receive, send):  # giả lập request đến từ máy khác trong LAN
        if scope["type"] == "http":
            scope = {**scope, "client": ("192.168.1.50", 5555)}
        await app(scope, receive, send)

    return TestClient(from_lan)


# ---------- main.py ----------
@pytest.mark.parametrize("bad", ["..\\..\\x", "2026-13-40", "abc", "2026-9-1"])
def test_date_param_is_validated(client, bad):
    assert client.get("/api/vehicles/today", params={"date": bad}).status_code == 400
    assert client.get("/api/vehicles/check-plate", params={"plate": "15A12345", "date": bad}).status_code == 400


def test_check_path_has_no_side_effects(client, tmp_path):
    target = tmp_path / "chua_ton_tai" / "con"
    r = client.post("/api/check-path", json={"path": str(target)})
    assert r.json()["ok"] is True
    assert not target.exists()


def test_debug_access_only_from_server_host(client):
    assert client.get("/api/debug-access").status_code == 403


def test_lifespan_respects_disable_firebase_sync(monkeypatch):
    from main import app
    monkeypatch.setenv("DISABLE_FIREBASE_SYNC", "1")
    with patch.object(firebase_sync, "run_sync_loop") as loop:
        with TestClient(app):
            pass
    loop.assert_not_called()


def test_upload_disk_error_returns_503(client):
    with patch("main.save_photo", side_effect=OSError("disk full")):
        r = client.post(
            "/api/upload",
            files={"file": ("a.jpg", JPEG, "image/jpeg")},
            data={"plate": "15A12345", "photo_type": "rear_45"},
        )
    assert r.status_code == 503


# ---------- photo_handler ----------
def test_auto_seq_is_unique_under_concurrency(tmp_path):
    photos = str(tmp_path / "p")
    cfg = PhotoConfig(photo_save_dir=photos)
    cfg.paths["passenger"] = photos
    results = []

    def worker():
        results.append(save_photo(JPEG, "15A12345", "T", "passenger", None, cfg))

    threads = [threading.Thread(target=worker) for _ in range(8)]
    [t.start() for t in threads]
    [t.join() for t in threads]
    names = sorted(r["filename"] for r in results)
    assert len(set(names)) == 8, names


# ---------- firebase_sync ----------
def test_remote_config_is_validated_and_blocks_sensitive_keys():
    base = PhotoConfig()
    out = firebase_sync._apply_remote_config(
        base, {"server_port": 1, "paths": {"rear_45": "C:\\Windows"}, "jpeg_quality": 70, "unknown": 1}
    )
    assert out.server_port == base.server_port
    assert out.jpeg_quality == 70
    with pytest.raises((ValueError, TypeError)):
        firebase_sync._apply_remote_config(base, {"jpeg_quality": "khong-phai-so"})


def test_invalid_remote_update_is_dropped_not_looped(tmp_path):
    cfg_file = str(tmp_path / "c.json")
    save_config(PhotoConfig(), cfg_file)
    deletes = []

    def fake(url, path, method="GET", data=None):
        if path == "config_update" and method == "GET":
            return {"jpeg_quality": "xxx"}
        if method == "DELETE":
            deletes.append(url)
        return None

    with patch.object(firebase_sync, "CONFIG_PATH", cfg_file), patch.object(firebase_sync, "rtdb_request", side_effect=fake):
        assert firebase_sync.sync_config_from_firebase() is False
    assert len(deletes) == len(firebase_sync.FIREBASE_NODES)  # yêu cầu hỏng vẫn bị xoá


def test_sync_config_reports_failure_when_all_nodes_fail():
    with patch.object(firebase_sync, "_rtdb_call", side_effect=OSError("offline")):
        assert firebase_sync.sync_config_to_firebase(PhotoConfig()) is False


def test_inbox_poison_item_is_quarantined(tmp_path):
    cfg_file = str(tmp_path / "c.json")
    save_config(PhotoConfig(photo_save_dir=str(tmp_path / "ph")), cfg_file)
    firebase_sync._INBOX_FAILS.clear()
    calls = []
    bad = {"image_base64": "AAAA", "plate": "15A12345", "photo_type": "rear_45"}  # không phải JPEG

    def fake(base_url, path, method="GET", data=None, query=""):
        calls.append((base_url, path, method))
        if path == "photo_inbox" and query:
            return {"bad1": True} if base_url == firebase_sync.FIREBASE_NODES[0]["url"] else None
        if path == "photo_inbox/bad1" and method == "GET":
            return bad
        return None

    with patch.object(firebase_sync, "CONFIG_PATH", cfg_file), patch.object(firebase_sync, "_rtdb_call", side_effect=fake):
        for _ in range(firebase_sync._INBOX_MAX_ATTEMPTS):
            firebase_sync.process_photo_inbox()
    assert any(p == "photo_inbox_failed/bad1" and m == "PUT" for _, p, m in calls)
    assert any(p == "photo_inbox/bad1" and m == "DELETE" for _, p, m in calls)


# ---------- vehicle_service ----------
def test_net_use_has_cooldown(monkeypatch):
    runs = []
    monkeypatch.setattr(vehicle_service.os.path, "exists", lambda p: False)
    monkeypatch.setattr(vehicle_service.subprocess, "run", lambda *a, **k: runs.append(a))
    monkeypatch.setattr(vehicle_service, "_LAST_NET_USE", 0.0)
    for _ in range(3):
        vehicle_service._ACCESS_PATH_CACHE.update(ts=0.0, value=None)
        assert vehicle_service.resolve_access_db_path() is None
    assert len(runs) == 1


def test_dir_cache_is_bounded(tmp_path):
    vehicle_service._DIR_CACHE.clear()
    for i in range(vehicle_service._DIR_CACHE_MAX * 2):
        vehicle_service._get_dir_files(str(tmp_path / f"d{i}"))
    assert len(vehicle_service._DIR_CACHE) <= vehicle_service._DIR_CACHE_MAX


def test_inbox_io_error_is_transient_not_quarantined(tmp_path):
    """Z: rớt (OSError) không được tính vào số lần lỗi -> ảnh không bị chuyển sang photo_inbox_failed."""
    import base64
    cfg_file = str(tmp_path / "c.json")
    save_config(PhotoConfig(photo_save_dir=str(tmp_path / "ph")), cfg_file)
    firebase_sync._INBOX_FAILS.clear()
    calls = []
    good = {"image_base64": base64.b64encode(b"\xff\xd8\xff\xe0" + b"0" * 64).decode(), "plate": "15A12345", "photo_type": "rear_45"}

    def fake(base_url, path, method="GET", data=None, query=""):
        calls.append((base_url, path, method))
        if path == "photo_inbox" and query:
            return {"ph1": True} if base_url == firebase_sync.FIREBASE_NODES[0]["url"] else None
        if path == "photo_inbox/ph1" and method == "GET":
            return good
        return None

    def boom(*a, **k):
        raise OSError("Z: unreachable")

    with patch.object(firebase_sync, "CONFIG_PATH", cfg_file), \
         patch.object(firebase_sync, "_rtdb_call", side_effect=fake), \
         patch.object(firebase_sync, "save_photo", side_effect=boom):
        for _ in range(firebase_sync._INBOX_MAX_ATTEMPTS + 3):
            firebase_sync.process_photo_inbox()
    assert not any(p == "photo_inbox_failed/ph1" for _, p, _m in calls)
    assert not any(p == "photo_inbox/ph1" and m == "DELETE" for _, p, m in calls)
    assert firebase_sync._INBOX_FAILS == {}


def test_firebase_disabled_blocks_push(monkeypatch):
    monkeypatch.setenv("DISABLE_FIREBASE_SYNC", "1")
    with patch.object(firebase_sync, "_rtdb_call", side_effect=AssertionError("không được gọi Firebase")):
        assert firebase_sync.sync_config_to_firebase(PhotoConfig()) is False
        firebase_sync.sync_vehicles_to_firebase(force=False)


def test_vehicle_push_failure_does_not_update_hash(tmp_path, monkeypatch):
    monkeypatch.delenv("DISABLE_FIREBASE_SYNC", raising=False)
    firebase_sync._LAST_VEHICLES_HASH = ""
    fake_vehicles = [{"plate_clean": "15A12345", "plate": "15A-123.45", "ticket_int": 1, "lan_kd": 1, "is_completed": False}]
    with patch.object(firebase_sync, "get_vehicles_today", return_value=fake_vehicles), \
         patch("vehicle_service.get_access_status", return_value={"ready": True, "has_cache": True}), \
         patch.object(firebase_sync, "_rtdb_call", side_effect=OSError("offline")):
        firebase_sync.sync_vehicles_to_firebase(force=False)
    assert firebase_sync._LAST_VEHICLES_HASH == ""


def test_upload_and_firebase_share_size_limit():
    import main, photo_handler
    assert main.MAX_FILE_SIZE == photo_handler.MAX_FILE_SIZE == 15 * 1024 * 1024
