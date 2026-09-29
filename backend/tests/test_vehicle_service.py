import os
import sqlite3
from datetime import datetime
from unittest.mock import patch, MagicMock
import pytest
from vehicle_service import get_vehicles_today, _check_photos_taken, PHOTO_TYPES
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

    (target_dir / "15A12345T_1.jpg").write_bytes(b"dummy")

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




