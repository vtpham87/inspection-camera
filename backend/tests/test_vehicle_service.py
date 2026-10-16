import os
import sqlite3
from datetime import datetime
from unittest.mock import patch, MagicMock
import pytest
from vehicle_service import get_vehicles_today, _check_photos_taken
from config import PhotoConfig


@pytest.fixture
def mock_db(tmp_path):
    """Create a minimal SQLite DB matching ptcgdb.db schema."""
    db_path = str(tmp_path / "test.db")
    conn = sqlite3.connect(db_path)
    cur = conn.cursor()
    cur.execute("""
        CREATE TABLE vehicles (
            biendk_id TEXT PRIMARY KEY,
            biendk TEXT,
            biendk_clean TEXT,
            chupt TEXT,
            nhanhieu TEXT,
            tenloaipt TEXT
        )
    """)
    cur.execute("""
        CREATE TABLE inspections (
            sophieu TEXT,
            biendk_id TEXT,
            ngaykd TEXT,
            giokd TEXT,
            ketluan INTEGER,
            sotem TEXT
        )
    """)
    cur.execute("""
        INSERT INTO vehicles VALUES
        ('15A12345T', '15A-123.45T', '15A12345T', 'Nguyễn Văn A', 'TOYOTA', 'Ô tô con')
    """)
    cur.execute("""
        INSERT INTO vehicles VALUES
        ('11K2639', '11K-2639', '11K2639', 'Trần Văn B', 'HONDA', 'Ô tô con')
    """)
    cur.execute("""
        INSERT INTO inspections VALUES
        ('001/26', '15A12345T', '2026-09-27', '08:30', 0, '')
    """)
    cur.execute("""
        INSERT INTO inspections VALUES
        ('002/26', '11K2639', '2026-09-27', '09:15', 0, '')
    """)
    conn.commit()
    conn.close()
    return db_path


def test_get_vehicles_returns_list(mock_db):
    config = PhotoConfig()
    result = get_vehicles_today(mock_db, "2026-09-27", config)
    assert len(result) == 2


def test_vehicle_has_required_fields(mock_db):
    config = PhotoConfig()
    result = get_vehicles_today(mock_db, "2026-09-27", config)
    v = result[0]
    assert "plate" in v
    assert "plate_clean" in v
    assert "plate_color" in v
    assert "vehicle_type" in v
    assert "brand" in v
    assert "owner" in v
    assert "time" in v
    assert "result" in v
    assert "photos_taken" in v
    assert "lan_kd" in v
    assert "suggest_lan_2" in v


def test_old_plate_has_null_color(mock_db):
    config = PhotoConfig()
    result = get_vehicles_today(mock_db, "2026-09-27", config)
    old = [v for v in result if v["plate_clean"] == "11K2639"][0]
    assert old["plate_color"] is None


def test_new_plate_has_color(mock_db):
    config = PhotoConfig()
    result = get_vehicles_today(mock_db, "2026-09-27", config)
    new = [v for v in result if v["plate_clean"] == "15A12345"][0]
    assert new["plate_color"] == "T"


def test_empty_date_returns_empty(mock_db):
    config = PhotoConfig()
    result = get_vehicles_today(mock_db, "2000-01-01", config)
    assert result == []


def test_vehicle_list_disabled_returns_none(mock_db):
    config = PhotoConfig()
    config.vehicle_list_enabled = False
    result = get_vehicles_today(mock_db, "2026-09-27", config)
    assert result is None


def test_vehicles_ordered_by_sophieu_asc(mock_db):
    config = PhotoConfig()
    result = get_vehicles_today(mock_db, "2026-09-27", config)
    assert len(result) == 2
    assert result[0]["ticket_num"] == "001/26"
    assert result[1]["ticket_num"] == "002/26"


def test_vehicles_ordered_by_sophieu_desc_when_not_waiting(mock_db):
    config = PhotoConfig()
    result = get_vehicles_today(mock_db, "2026-09-27", config, filter_waiting=False)
    assert result is not None
    assert len(result) == 2
    assert result[0]["ticket_num"] == "002/26"
    assert result[1]["ticket_num"] == "001/26"


def test_photos_taken_detects_existing_photos(mock_db, tmp_path):
    config = PhotoConfig()
    photo_dir = tmp_path / "photos"
    for pt in config.paths:
        config.paths[pt] = str(photo_dir / "{date}")

    today = "20260927"
    target_dir = photo_dir / today
    target_dir.mkdir(parents=True, exist_ok=True)

    (target_dir / "15A12345T.jpg").write_bytes(b"dummy")
    (target_dir / "bs15A12345T.jpg").write_bytes(b"dummy")

    result = get_vehicles_today(mock_db, "2026-09-27", config)
    v15 = [v for v in result if v["plate_clean"] == "15A12345"][0]
    assert "rear_45" in v15["photos_taken"]
    assert "front_45" in v15["photos_taken"]
    assert "chassis" not in v15["photos_taken"]

    v11 = [v for v in result if v["plate_clean"] == "11K2639"][0]
    assert v11["photos_taken"] == []


def test_photos_taken_passenger_with_seq(mock_db, tmp_path):
    config = PhotoConfig()
    photo_dir = tmp_path / "photos"
    config.paths["passenger"] = str(photo_dir / "{date}" / "{plate}")

    today = "20260927"
    target_dir = photo_dir / today / "15A12345T"
    target_dir.mkdir(parents=True, exist_ok=True)

    # SPEC v2.0: passenger khong co hau to mau trong ten file
    (target_dir / "15A12345_1.jpg").write_bytes(b"dummy")

    result = get_vehicles_today(mock_db, "2026-09-27", config)
    v15 = [v for v in result if v["plate_clean"] == "15A12345"][0]
    assert "passenger" in v15["photos_taken"]


def test_date_none_defaults_to_today(tmp_path):
    db_path = str(tmp_path / "test_today.db")
    today_str = datetime.now().strftime("%Y-%m-%d")
    conn = sqlite3.connect(db_path)
    cur = conn.cursor()
    cur.execute("CREATE TABLE vehicles (biendk_id TEXT PRIMARY KEY, biendk TEXT, biendk_clean TEXT, chupt TEXT, nhanhieu TEXT, tenloaipt TEXT)")
    cur.execute("CREATE TABLE inspections (sophieu TEXT, biendk_id TEXT, ngaykd TEXT, giokd TEXT, ketluan INTEGER, sotem TEXT)")
    cur.execute("INSERT INTO vehicles VALUES ('29A99999T', '29A-999.99T', '29A99999T', 'Owner Today', 'KIA', 'Ô tô con')")
    cur.execute("INSERT INTO inspections VALUES ('999/26', '29A99999T', ?, '10:00', 0, '')", (today_str,))
    conn.commit()
    conn.close()

    config = PhotoConfig()
    result = get_vehicles_today(db_path, None, config)
    assert len(result) == 1
    assert result[0]["plate_clean"] == "29A99999"


def test_nonexistent_db_returns_empty():
    config = PhotoConfig()
    result = get_vehicles_today("nonexistent_path_xyz.db", "2026-09-27", config)
    assert result == []


def test_check_photos_does_not_create_empty_dirs(mock_db, tmp_path):
    config = PhotoConfig()
    photo_dir = tmp_path / "photos_empty_check"
    for pt in config.paths:
        config.paths[pt] = str(photo_dir / "{date}" / "{plate}")

    assert not photo_dir.exists()

    result = get_vehicles_today(mock_db, "2026-09-27", config)
    assert result is not None
    assert len(result) == 2
    # Verify that querying vehicles did NOT create photo_dir or subdirectories
    assert not photo_dir.exists()


def test_check_photos_taken_helper_does_not_create_empty_dirs(tmp_path):
    config = PhotoConfig()
    photo_dir = tmp_path / "check_taken"
    for pt in config.paths:
        config.paths[pt] = str(photo_dir / "{date}" / "{plate}")

    assert not photo_dir.exists()
    taken = _check_photos_taken("15A12345", "T", config, date="2026-09-27")
    assert taken == []
    assert not photo_dir.exists()


def test_db_connection_closed_finally(mock_db):
    config = PhotoConfig()
    mock_conn = MagicMock()
    mock_cursor = MagicMock()
    mock_conn.cursor.return_value = mock_cursor
    mock_cursor.fetchall.return_value = []

    with patch("vehicle_service.sqlite3.connect", return_value=mock_conn):
        get_vehicles_today(mock_db, "2026-09-27", config)

    mock_conn.close.assert_called_once()


def test_db_connection_closed_on_query_exception(mock_db):
    config = PhotoConfig()
    mock_conn = MagicMock()
    mock_cursor = MagicMock()
    mock_conn.cursor.return_value = mock_cursor
    mock_cursor.execute.side_effect = sqlite3.OperationalError("Query failed")

    with patch("vehicle_service.sqlite3.connect", return_value=mock_conn):
        with pytest.raises(sqlite3.OperationalError):
            get_vehicles_today(mock_db, "2026-09-27", config)

    mock_conn.close.assert_called_once()


def test_vehicle_lan_kd_from_sqlite(tmp_path):
    db_path = str(tmp_path / "test_lankd.db")
    conn = sqlite3.connect(db_path)
    cur = conn.cursor()
    cur.execute("CREATE TABLE vehicles (biendk_id TEXT PRIMARY KEY, biendk TEXT, biendk_clean TEXT, chupt TEXT, nhanhieu TEXT, tenloaipt TEXT)")
    cur.execute("CREATE TABLE inspections (sophieu TEXT, biendk_id TEXT, ngaykd TEXT, giokd TEXT, ketluan INTEGER, sotem TEXT, lankd INTEGER)")
    cur.execute("INSERT INTO vehicles VALUES ('15A12345T', '15A-123.45', '15A12345T', 'Owner', 'TOYOTA', 'Ô tô con')")
    cur.execute("INSERT INTO inspections VALUES ('100/26', '15A12345T', '2026-09-27', '09:00', 0, '', 2)")
    conn.commit()
    conn.close()

    config = PhotoConfig()
    result = get_vehicles_today(db_path, "2026-09-27", config)
    assert len(result) == 1
    assert result[0]["lan_kd"] == 2


def test_vehicle_lan_kd_photos_taken_detects_l2(tmp_path):
    db_path = str(tmp_path / "test_lankd_photos.db")
    conn = sqlite3.connect(db_path)
    cur = conn.cursor()
    cur.execute("CREATE TABLE vehicles (biendk_id TEXT PRIMARY KEY, biendk TEXT, biendk_clean TEXT, chupt TEXT, nhanhieu TEXT, tenloaipt TEXT)")
    cur.execute("CREATE TABLE inspections (sophieu TEXT, biendk_id TEXT, ngaykd TEXT, giokd TEXT, ketluan INTEGER, sotem TEXT, lankd INTEGER)")
    cur.execute("INSERT INTO vehicles VALUES ('15A12345T', '15A-123.45', '15A12345T', 'Owner', 'TOYOTA', 'Ô tô con')")
    cur.execute("INSERT INTO inspections VALUES ('100/26', '15A12345T', '2026-09-27', '09:00', 0, '', 2)")
    conn.commit()
    conn.close()

    config = PhotoConfig()
    photo_dir = tmp_path / "photos"
    for pt in config.paths:
        config.paths[pt] = str(photo_dir)
    photo_dir.mkdir(parents=True, exist_ok=True)
    # Write Lần 2 photos
    (photo_dir / "15A12345TL2.jpg").write_bytes(b"dummy")
    (photo_dir / "bs15A12345TL2.jpg").write_bytes(b"dummy")

    result = get_vehicles_today(db_path, "2026-09-27", config)
    assert len(result) == 1
    assert "rear_45" in result[0]["photos_taken"]
    assert "front_45" in result[0]["photos_taken"]


def test_suggest_lan_2_when_failed_earlier(tmp_path):
    db_path = str(tmp_path / "test_failed_earlier.db")
    conn = sqlite3.connect(db_path)
    cur = conn.cursor()
    cur.execute("CREATE TABLE vehicles (biendk_id TEXT PRIMARY KEY, biendk TEXT, biendk_clean TEXT, chupt TEXT, nhanhieu TEXT, tenloaipt TEXT)")
    cur.execute("CREATE TABLE inspections (sophieu TEXT, biendk_id TEXT, ngaykd TEXT, giokd TEXT, ketluan INTEGER, sotem TEXT, lankd INTEGER)")
    cur.execute("INSERT INTO vehicles VALUES ('15A12345T', '15A-123.45', '15A12345T', 'Owner', 'TOYOTA', 'Ô tô con')")
    # First inspection failed: ketluan = 1, lankd = 1
    cur.execute("INSERT INTO inspections VALUES ('090/26', '15A12345T', '2026-09-27', '08:00', 1, '', 1)")
    # Second inspection is waiting: ketluan != 1 (None or -1), but lankd might not be 2 yet
    cur.execute("INSERT INTO inspections VALUES ('100/26', '15A12345T', '2026-09-27', '09:00', -1, '', 1)")
    conn.commit()
    conn.close()

    config = PhotoConfig()
    result = get_vehicles_today(db_path, "2026-09-27", config)
    v = [x for x in result if x["ticket_num"] == "100/26"][0]
    assert v["suggest_lan_2"] is True


def test_check_plate_status_detects_failure_and_l1(tmp_path):
    from vehicle_service import check_plate_status
    db_path = str(tmp_path / "test_check_plate.db")
    conn = sqlite3.connect(db_path)
    cur = conn.cursor()
    cur.execute("CREATE TABLE inspections (sophieu TEXT, biendk_id TEXT, ngaykd TEXT, giokd TEXT, ketluan INTEGER, sotem TEXT, lankd INTEGER)")
    cur.execute("INSERT INTO inspections VALUES ('090/26', '15A12345T', '2026-09-27', '08:00', 1, '', 1)")
    conn.commit()
    conn.close()

    config = PhotoConfig()
    photo_dir = tmp_path / "photos"
    for pt in config.paths:
        config.paths[pt] = str(photo_dir)
    photo_dir.mkdir(parents=True, exist_ok=True)
    (photo_dir / "15A12345T.jpg").write_bytes(b"dummy")

    status = check_plate_status(db_path, "15A12345", "T", config, date="2026-09-27")
    assert status["has_failed_today"] is True
    assert status["has_l1_photos"] is True
    assert status["suggest_lan_2"] is True


def test_suggest_lan_2_is_false_when_not_failed_even_with_l1_photos(tmp_path):
    from vehicle_service import check_plate_status
    db_path = str(tmp_path / "test_check_plate2.db")
    conn = sqlite3.connect(db_path)
    cur = conn.cursor()
    cur.execute("CREATE TABLE inspections (sophieu TEXT, biendk_id TEXT, ngaykd TEXT, giokd TEXT, ketluan INTEGER, sotem TEXT, lankd INTEGER)")
    # Vehicle passed (ketluan = 0) or no failure recorded
    cur.execute("INSERT INTO inspections VALUES ('091/26', '15A12345T', '2026-09-27', '08:00', 0, '123456', 1)")
    conn.commit()
    conn.close()

    config = PhotoConfig()
    photo_dir = tmp_path / "photos"
    for pt in config.paths:
        config.paths[pt] = str(photo_dir)
    photo_dir.mkdir(parents=True, exist_ok=True)
    (photo_dir / "15A12345T.jpg").write_bytes(b"dummy")

    status = check_plate_status(db_path, "15A12345", "T", config, date="2026-09-27")
    assert status["has_failed_today"] is False
    assert status["has_l1_photos"] is True
    # MUST NOT suggest L2 simply because L1 photos exist!
    assert status["suggest_lan_2"] is False


def test_waiting_vehicles_sqlite_sync_and_circuit_breaker(tmp_path):
    from vehicle_service import (
        init_waiting_vehicles_table,
        sync_waiting_vehicles_to_sqlite,
        get_waiting_vehicles_from_sqlite,
    )

    db_path = str(tmp_path / "ptcgdb.db")
    conn = sqlite3.connect(db_path)
    cur = conn.cursor()
    cur.execute("CREATE TABLE vehicles (biendk_id TEXT, biendk TEXT, nhanhieu TEXT, tenloaipt TEXT, chupt TEXT)")
    cur.execute("INSERT INTO vehicles VALUES ('15A12345T', '15A-123.45T', 'TOYOTA', 'Ô tô con', 'Nguyễn Văn A')")
    conn.commit()
    conn.close()

    init_waiting_vehicles_table(db_path)

    # 1. Test empty table
    rows = get_waiting_vehicles_from_sqlite(db_path)
    assert rows == []

    # 2. Test successful sync
    mock_raw = [
        {
            "SoPhieuKD": "100/26",
            "BienDK_ID": "15A12345T",
            "GioKD": "08:15:00",
            "KetLuan": -1,
            "LanKD": 1,
            "BienDK": "15A-123.45T",
            "TenLoaiPT": "",
            "NhanHieu": "",
            "ChuPT": "",
        },
        {
            "SoPhieuKD": "101/26",
            "BienDK_ID": "15B99999T",
            "GioKD": "08:30:00",
            "KetLuan": -1,
            "LanKD": 1,
            "BienDK": "15B-999.99T",
            "TenLoaiPT": "á tá con",
            "NhanHieu": "HYUNDAI",
            "ChuPT": "TrÇn V¨n B",
        }
    ]

    with patch("vehicle_service.fetch_waiting_from_access_raw", return_value=mock_raw):
        ok = sync_waiting_vehicles_to_sqlite(db_path)
        assert ok is True

    # Check read from SQLite
    cached = get_waiting_vehicles_from_sqlite(db_path)
    assert len(cached) == 2
    assert cached[0]["SoPhieuKD"] == "100/26"
    assert cached[0]["BienDK"] == "15A-123.45T"
    assert cached[0]["NhanHieu"] == "TOYOTA"  # Resolved from vehicles table
    assert cached[0]["TenLoaiPT"] == "Ô tô con"

    assert cached[1]["SoPhieuKD"] == "101/26"
    assert cached[1]["TenLoaiPT"] == "Ô tô con"  # Decoded from D32
    assert cached[1]["NhanHieu"] == "HYUNDAI"

    # 3. Test Circuit Breaker: when Access fails (returns None), existing SQLite data MUST NOT be deleted!
    with patch("vehicle_service.fetch_waiting_from_access_raw", return_value=None):
        ok_fail = sync_waiting_vehicles_to_sqlite(db_path)
        assert ok_fail is False

    cached_after_fail = get_waiting_vehicles_from_sqlite(db_path)
    assert len(cached_after_fail) == 2
    assert cached_after_fail[0]["SoPhieuKD"] == "100/26"

    # 4. Test Circuit Breaker: when Access returns [] during working hours (07h-18h), existing SQLite data MUST NOT be deleted!
    with patch("vehicle_service.fetch_waiting_from_access_raw", return_value=[]), \
         patch("vehicle_service.datetime") as mock_dt:
        mock_dt.now.return_value = datetime(2026, 10, 16, 10, 30, 0)
        ok_empty = sync_waiting_vehicles_to_sqlite(db_path)
        assert ok_empty is True

    cached_after_empty = get_waiting_vehicles_from_sqlite(db_path)
    assert len(cached_after_empty) == 2
    assert cached_after_empty[0]["SoPhieuKD"] == "100/26"







def test_circuit_breaker_releases_after_empty_streak(tmp_path):
    import vehicle_service
    from vehicle_service import sync_waiting_vehicles_to_sqlite, get_waiting_vehicles_from_sqlite
    """Access rỗng liên tiếp quá ngưỡng -> xoá bộ đệm (xe cuối cùng đã xử lý xong không treo tới 18h)."""
    db_path = str(tmp_path / "ptcgdb.db")
    _c = sqlite3.connect(db_path)
    _c.execute("CREATE TABLE vehicles (biendk_id TEXT, biendk TEXT, nhanhieu TEXT, tenloaipt TEXT, chupt TEXT)")
    _c.commit()
    _c.close()
    row = {"SoPhieuKD": "1/26", "BienDK_ID": "15A12345T", "GioKD": "08:00:00", "KetLuan": -1, "LanKD": 1,
           "BienDK": "15A-123.45", "TenLoaiPT": "x", "NhanHieu": "y", "ChuPT": "z"}
    vehicle_service._EMPTY_STREAK = 0
    with patch("vehicle_service.fetch_waiting_from_access_raw", return_value=[row]):
        assert sync_waiting_vehicles_to_sqlite(db_path) is True
    assert len(get_waiting_vehicles_from_sqlite(db_path)) == 1
    with patch("vehicle_service.fetch_waiting_from_access_raw", return_value=[]), \
         patch("vehicle_service.datetime") as mock_dt:
        mock_dt.now.return_value = datetime(2026, 10, 16, 10, 30, 0)
        for _ in range(vehicle_service._EMPTY_STREAK_LIMIT):
            sync_waiting_vehicles_to_sqlite(db_path)
            assert len(get_waiting_vehicles_from_sqlite(db_path)) == 1
        sync_waiting_vehicles_to_sqlite(db_path)
    assert get_waiting_vehicles_from_sqlite(db_path) == []


def _fresh_waiting_env(tmp_path, monkeypatch, with_vehicles=True, items=None):
    import sqlite3
    import vehicle_service as vs

    db_path = str(tmp_path / "t.db")
    boot = sqlite3.connect(db_path)
    if with_vehicles:
        boot.execute("CREATE TABLE vehicles (biendk_id TEXT, biendk TEXT, nhanhieu TEXT, tenloaipt TEXT, chupt TEXT)")
    boot.commit()
    boot.close()
    vs._WAITING_TABLE_READY.discard(db_path)
    vs._WAITING_ROWS_SIG.pop(db_path, None)
    data = items if items is not None else [
        {"SoPhieuKD": "1", "BienDK_ID": "A1", "BienDK": "29A12345", "GioKD": "08:00", "KetLuan": -1, "LanKD": 1}
    ]
    monkeypatch.setattr(vs, "fetch_waiting_from_access_raw", lambda: data)
    return vs, db_path


def test_sync_waiting_skips_write_when_unchanged(tmp_path, monkeypatch):
    """Dữ liệu Access không đổi -> không DELETE/INSERT lại; đổi hoặc bảng bị xoá ngoài -> ghi lại."""
    import sqlite3

    vs, db_path = _fresh_waiting_env(tmp_path, monkeypatch)
    assert vs.sync_waiting_vehicles_to_sqlite(db_path) is True
    conn = sqlite3.connect(vs.waiting_db_path(db_path))
    conn.execute("UPDATE waiting_vehicles SET updated_at = '2000-01-01 00:00:00'")
    conn.commit()
    assert vs.sync_waiting_vehicles_to_sqlite(db_path) is True  # không đổi -> không ghi
    assert conn.execute("SELECT updated_at FROM waiting_vehicles").fetchone()[0] == "2000-01-01 00:00:00"
    conn.execute("DELETE FROM waiting_vehicles")
    conn.commit()
    assert vs.sync_waiting_vehicles_to_sqlite(db_path) is True  # bảng bị xoá ngoài -> ghi lại
    assert conn.execute("SELECT count(*) FROM waiting_vehicles").fetchone()[0] == 1
    conn.close()


def test_waiting_buffer_is_separate_wal_file(tmp_path, monkeypatch):
    import sqlite3

    vs, db_path = _fresh_waiting_env(tmp_path, monkeypatch)
    assert vs.sync_waiting_vehicles_to_sqlite(db_path) is True
    buf = vs.waiting_db_path(db_path)
    assert buf != db_path and os.path.exists(buf)
    c = sqlite3.connect(buf)
    assert c.execute("PRAGMA journal_mode").fetchone()[0].lower() == "wal"
    c.close()
    src = sqlite3.connect(db_path)  # CSDL nguồn không bị tạo bảng đệm / đổi journal mode
    assert src.execute("SELECT count(*) FROM sqlite_master WHERE name='waiting_vehicles'").fetchone()[0] == 0
    assert src.execute("PRAGMA journal_mode").fetchone()[0].lower() != "wal"
    src.close()


def test_sync_waiting_tolerates_missing_vehicles_table(tmp_path, monkeypatch):
    vs, db_path = _fresh_waiting_env(tmp_path, monkeypatch, with_vehicles=False)
    assert vs.sync_waiting_vehicles_to_sqlite(db_path) is True
    rows = vs.get_waiting_vehicles_from_sqlite(db_path)
    assert len(rows) == 1 and rows[0]["BienDK"] == "29A12345"


def test_waiting_sync_interval_adaptive(monkeypatch):
    import vehicle_service as vs

    monkeypatch.setattr(vs, "_IDLE_SYNC_SEC", 10.0)
    monkeypatch.setitem(vs._WAITING_SYNC_STATE, "status", "ok")
    monkeypatch.setitem(vs._WAITING_SYNC_STATE, "count", 0)
    assert vs.waiting_sync_interval() == 10.0
    monkeypatch.setitem(vs._WAITING_SYNC_STATE, "count", 3)
    assert vs.waiting_sync_interval() == 5.0
    monkeypatch.setitem(vs._WAITING_SYNC_STATE, "status", "error")
    monkeypatch.setitem(vs._WAITING_SYNC_STATE, "count", 0)
    assert vs.waiting_sync_interval() == 5.0


def test_run_task_backs_off_when_task_returns_false(monkeypatch):
    import firebase_sync as fs

    sleeps = []

    def fake_sleep(sec):
        sleeps.append(sec)
        if len(sleeps) >= 3:
            raise KeyboardInterrupt

    monkeypatch.setattr(fs.time, "sleep", fake_sleep)
    try:
        fs._run_task(lambda: False, 5)
    except KeyboardInterrupt:
        pass
    assert sleeps == [10, 20, 40]
