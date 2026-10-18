"""Kiểm thử cho các sửa lỗi/tối ưu ở đợt rà soát v1.6.1-r3."""
import sqlite3
from unittest.mock import patch

import pytest

import firebase_sync
import vehicle_service as vs
from config import CONFIG_LOCK, PhotoConfig, save_config


def test_resolve_access_prefers_configured_path(tmp_path, monkeypatch):
    """PTCGDB_ACCESS_PATH phải thắng ổ Z: mặc định (trước đây Z: luôn được thử trước)."""
    configured = tmp_path / "dev.mdb"
    z_default = tmp_path / "z.mdb"
    configured.write_bytes(b"x")
    z_default.write_bytes(b"x")
    monkeypatch.setattr(vs, "ACCESS_DB_PATH", str(configured))
    monkeypatch.setattr(vs, "ACCESS_DB_Z_PATH", str(z_default))
    vs._ACCESS_PATH_CACHE.update(ts=0.0, value=None)
    assert vs.resolve_access_db_path() == str(configured)
    vs._ACCESS_PATH_CACHE.update(ts=0.0, value=None)


def test_resolve_access_ignores_unc(tmp_path, monkeypatch):
    monkeypatch.setattr(vs, "ACCESS_DB_PATH", r"\\srv\share\a.mdb")
    monkeypatch.setattr(vs, "ACCESS_DB_Z_PATH", str(tmp_path / "missing.mdb"))
    monkeypatch.setattr(vs, "_LAST_NET_USE", 1e18)  # đang trong cooldown: không chạy `net use`
    vs._ACCESS_PATH_CACHE.update(ts=0.0, value=None)
    assert vs.resolve_access_db_path() is None
    vs._ACCESS_PATH_CACHE.update(ts=0.0, value=None)


@pytest.mark.parametrize("value,expected", [(None, -1), ({}, -1), ("", -1), ("abc", -1), ("3", 3), (2, 2), (0, 0)])
def test_to_int_is_safe(value, expected):
    assert vs._to_int(value, -1) == expected


def test_vehicles_today_survives_missing_tables(tmp_path):
    """DB mới chưa có inspections/vehicles: không lỗi 500, vẫn hiển thị xe chờ lấy từ Access."""
    db = tmp_path / "ptcgdb.db"
    sqlite3.connect(db).close()
    today = vs.datetime.now().strftime("%Y-%m-%d")
    waiting = [{"SoPhieuKD": "7", "BienDK_ID": "15A12345T", "BienDK": "15A-123.45", "KetLuan": -1, "LanKD": 1,
                "GioKD": "08:00:00", "NhanHieu": "", "TenLoaiPT": "", "ChuPT": ""}]
    with patch.object(vs, "get_waiting_vehicles_from_access", return_value=waiting):
        res = vs.get_vehicles_today(str(db), today, PhotoConfig(), filter_waiting=True)
    assert [v["sophieu"] for v in res] == ["7"] and res[0]["plate"] == "15A-123.45"
    with patch.object(vs, "get_waiting_vehicles_from_access", return_value=[]):
        assert vs.get_vehicles_today(str(db), today, PhotoConfig(), filter_waiting=True) == []


def test_waiting_zero_after_success_does_not_spawn_powershell(tmp_path, monkeypatch):
    monkeypatch.setitem(vs._WAITING_SYNC_STATE, "has_success", True)
    with patch.object(vs, "fetch_waiting_from_access_raw", side_effect=AssertionError("không được gọi Access")):
        assert vs.get_waiting_vehicles_from_access(str(tmp_path / "ptcgdb.db")) == []


def test_lan2_photos_checked_only_when_suggested(tmp_path):
    """Xe chưa trượt/Lần 1: chỉ quét ảnh Lần 1 (trước đây luôn quét thêm Lần 2)."""
    db = tmp_path / "ptcgdb.db"
    con = sqlite3.connect(db)
    con.execute("CREATE TABLE vehicles (biendk_id TEXT, biendk TEXT, biendk_clean TEXT, chupt TEXT, nhanhieu TEXT, tenloaipt TEXT)")
    con.execute("CREATE TABLE inspections (sophieu TEXT, biendk_id TEXT, ngaykd TEXT, giokd TEXT, ketluan INTEGER, sotem TEXT, lankd INTEGER)")
    today = vs.datetime.now().strftime("%Y-%m-%d")
    con.execute("INSERT INTO vehicles VALUES ('15A12345T','15A-123.45','15A12345','A','B','C')")
    con.execute("INSERT INTO inspections VALUES ('1','15A12345T',?, '08:00:00', NULL, '', 1)", (today,))
    con.commit()
    con.close()
    seen = []
    real = vs._check_photos_taken
    with patch.object(vs, "get_waiting_vehicles_from_access", return_value=[]), \
         patch.object(vs, "_check_photos_taken", side_effect=lambda *a, **k: seen.append(k["lan_kd"]) or real(*a, **k)):
        res = vs.get_vehicles_today(str(db), today, PhotoConfig(), filter_waiting=False)
    assert len(res) == 1 and seen == [1]


def test_config_update_cleared_outside_config_lock(tmp_path):
    """Xoá config_update (gọi mạng) không được giữ CONFIG_LOCK, nếu không POST /api/config bị treo theo mạng."""
    cfg = str(tmp_path / "c.json")
    save_config(PhotoConfig(), cfg)
    held = []
    with patch.object(firebase_sync, "CONFIG_PATH", cfg), \
         patch.object(firebase_sync, "rtdb_request",
                      side_effect=lambda url, path, method="GET", data=None: {"jpeg_quality": 70} if (path, method) == ("config_update", "GET") else None), \
         patch.object(firebase_sync, "_clear_config_update", side_effect=lambda: held.append(CONFIG_LOCK._is_owned())), \
         patch.object(firebase_sync, "sync_config_to_firebase"):
        assert firebase_sync.sync_config_from_firebase() is True
    assert held == [False]


def test_invalid_config_update_is_still_cleared(tmp_path):
    cfg = str(tmp_path / "c.json")
    save_config(PhotoConfig(), cfg)
    cleared = []
    with patch.object(firebase_sync, "CONFIG_PATH", cfg), \
         patch.object(firebase_sync, "rtdb_request",
                      side_effect=lambda url, path, method="GET", data=None: {"jpeg_quality": "không-phải-số"} if (path, method) == ("config_update", "GET") else None), \
         patch.object(firebase_sync, "_clear_config_update", side_effect=lambda: cleared.append(1)):
        assert firebase_sync.sync_config_from_firebase() is False
    assert cleared == [1]


def test_vehicle_push_reports_result(monkeypatch):
    monkeypatch.delenv("DISABLE_FIREBASE_SYNC", raising=False)
    firebase_sync._LAST_VEHICLES_HASH = ""
    veh = [{"plate_clean": "15A12345", "plate": "15A-123.45", "ticket_int": 1, "lan_kd": 1, "is_completed": False}]
    status = {"ready": True, "has_cache": True}
    with patch.object(firebase_sync, "get_vehicles_today", return_value=veh), \
         patch("vehicle_service.get_access_status", return_value=status):
        with patch.object(firebase_sync, "_rtdb_call", side_effect=OSError("offline")):
            assert firebase_sync.sync_vehicles_to_firebase(force=True) is False
        with patch.object(firebase_sync, "_rtdb_call", return_value=None):
            assert firebase_sync.sync_vehicles_to_firebase(force=True) is True
            assert firebase_sync.sync_vehicles_to_firebase(force=False) is True  # không đổi -> không đẩy lại
    firebase_sync._LAST_VEHICLES_HASH = ""


def test_delete_request_bounds(tmp_path, monkeypatch):
    from fastapi.testclient import TestClient
    import main
    monkeypatch.setenv("PHOTO_CONFIG_PATH", str(tmp_path / "c.json"))
    client = TestClient(main.app)
    body = {"plate": "15A12345", "photo_type": "rear_45"}
    assert client.request("DELETE", "/api/photos", json={**body, "lan_kd": 99}).status_code == 422
    assert client.request("DELETE", "/api/photos", json={**body, "seq": 0}).status_code == 422
