import os
import pytest
from photo_handler import (
    normalize_plate,
    clean_plate_and_color,
    extract_plate_color,
    should_omit_color_suffix,
    build_filename,
    resolve_save_path,
    validate_jpeg,
    save_photo,
)
from config import PhotoConfig

# --- normalize_plate ---
def test_normalize_strips_dots_dashes_spaces():
    assert normalize_plate("15A-123.45") == "15A12345"

def test_normalize_uppercase():
    assert normalize_plate("15a-123.45t") == "15A12345T"

def test_normalize_rejects_special_chars():
    with pytest.raises(ValueError, match="không hợp lệ"):
        normalize_plate("../etc")

def test_normalize_rejects_empty():
    with pytest.raises(ValueError, match="không hợp lệ"):
        normalize_plate("")

# --- extract_plate_color ---
def test_extract_color_white():
    plate, color = extract_plate_color("15A12345T")
    assert plate == "15A12345"
    assert color == "T"

def test_extract_color_yellow():
    plate, color = extract_plate_color("11B00103V")
    assert plate == "11B00103"
    assert color == "V"

def test_extract_color_blue():
    plate, color = extract_plate_color("15A12345X")
    assert plate == "15A12345"
    assert color == "X"

def test_extract_color_old_plate():
    """Old plate ending in digit — no color suffix."""
    plate, color = extract_plate_color("11K2639")
    assert plate == "11K2639"
    assert color is None
    # Biển cũ kể cả khi có gắn hậu tố màu cũng tự động bóc bỏ (mặc định không có màu)
    plate_t, color_t = extract_plate_color("11K2639T")
    assert plate_t == "11K2639"
    assert color_t is None

def test_extract_color_special_series():
    """Biển KT, LD, HC không gắn hậu tố màu dù có 5 số."""
    for p in ["15KT12345", "15LD12345", "15HC12345", "15KT12345T", "15LD12345V", "15HC12345X"]:
        plate, color = extract_plate_color(p)
        assert color is None
        assert plate.startswith("15") and plate.endswith("12345")

def test_should_omit_color_suffix():
    assert should_omit_color_suffix("15KT12345") is True
    assert should_omit_color_suffix("15LD12345") is True
    assert should_omit_color_suffix("15HC12345") is True
    assert should_omit_color_suffix("15KT-123.45") is True
    assert should_omit_color_suffix("29KT99999") is True
    assert should_omit_color_suffix("11K2639") is True
    assert should_omit_color_suffix("15A12345") is False
    assert should_omit_color_suffix("15B99999") is False
    assert should_omit_color_suffix("15C12345") is False

# --- build_filename ---
def test_filename_rear_45_with_color():
    assert build_filename("15A12345", "T", "rear_45", None, True) == "15A12345T.jpg"

def test_filename_rear_45_no_color():
    assert build_filename("11K2639", None, "rear_45", None, True) == "11K2639.jpg"

def test_filename_special_series_ignores_color():
    """Biển KT, LD, HC không thêm t/v/x khi lưu file."""
    assert build_filename("15KT12345", "T", "rear_45", None, True) == "15KT12345.jpg"
    assert build_filename("15KT12345", "V", "rear_45", None, True) == "15KT12345.jpg"
    assert build_filename("15LD12345", "T", "front_45", None, True) == "bs15LD12345.jpg"
    assert build_filename("15HC12345", "X", "chassis", None, True) == "sk_15HC12345.jpg"
    assert build_filename("15KT12345", "T", "rear_45", None, True, lan_kd=2) == "15KT12345L2.jpg"

def test_filename_old_plate_ignores_color():
    """Biển cũ mặc định không thêm t/v/x dù có truyền mã màu."""
    assert build_filename("11K2639", "T", "rear_45", None, True) == "11K2639.jpg"
    assert build_filename("11K2639", "V", "rear_45", None, True) == "11K2639.jpg"
    assert build_filename("11K2639", "X", "front_45", None, True) == "bs11K2639.jpg"
    assert build_filename("11K2639", "T", "rear_45", None, True, lan_kd=2) == "11K2639L2.jpg"
    assert build_filename("16L3565", "T", "rear_45", None, True) == "16L3565.jpg"

def test_filename_rear_45_suffix_disabled():
    assert build_filename("15A12345", "T", "rear_45", None, False) == "15A12345.jpg"

def test_filename_front_45():
    assert build_filename("15A12345", "T", "front_45", None, True) == "bs15A12345T.jpg"

def test_filename_chassis():
    assert build_filename("15A12345", "T", "chassis", None, True) == "sk_15A12345.jpg"

def test_filename_passenger_seq():
    assert build_filename("15A12345", "T", "passenger", 2, True) == "15A12345T_2.jpg"

def test_filename_new_vehicle_seq():
    assert build_filename("15A12345", "T", "new_vehicle", 1, True) == "15A12345T_1.jpg"

def test_filename_invalid_photo_type():
    with pytest.raises(ValueError, match="Loại ảnh không hợp lệ"):
        build_filename("15A12345", "T", "unknown_type", None, True)

# --- resolve_save_path ---
def test_resolve_save_path(tmp_path):
    config = PhotoConfig()
    config.paths["passenger"] = str(tmp_path / "{date}" / "{plate}")
    resolved = resolve_save_path("passenger", "15A12345", config, create_dir=True)
    assert os.path.exists(resolved)
    assert resolved.endswith("15A12345T")

def test_resolve_save_path_color_formats(tmp_path):
    config = PhotoConfig()
    config.paths["passenger"] = str(tmp_path / "{date}" / "{plate}")
    # 5-digit with explicit T
    r1 = resolve_save_path("passenger", "15A12345", config, plate_color="T")
    assert r1.endswith("15A12345T")
    # 5-digit already with T suffix in plate string
    r2 = resolve_save_path("passenger", "15A12345T", config)
    assert r2.endswith("15A12345T")
    # 5-digit without color defaults to T
    r3 = resolve_save_path("passenger", "15A12345", config)
    assert r3.endswith("15A12345T")
    # Yellow plate V
    r4 = resolve_save_path("passenger", "15C12345", config, plate_color="V")
    assert r4.endswith("15C12345V")
    # Blue plate X
    r5 = resolve_save_path("passenger", "15A00123", config, plate_color="X")
    assert r5.endswith("15A00123X")
    # Old 4-digit plate (no color)
    r6 = resolve_save_path("passenger", "11K2639", config)
    assert r6.endswith("11K2639")
    # Special series KT, LD, HC (no color suffix even with 5 digits)
    r7 = resolve_save_path("passenger", "15KT12345", config)
    assert r7.endswith("15KT12345")
    r8 = resolve_save_path("passenger", "15LD12345", config, plate_color="V")
    assert r8.endswith("15LD12345")
    r9 = resolve_save_path("passenger", "15HC12345", config, plate_color="X")
    assert r9.endswith("15HC12345")

def test_resolve_save_path_no_create_dir(tmp_path):
    config = PhotoConfig()
    config.paths["rear_45"] = str(tmp_path / "subdir" / "{date}")
    resolved = resolve_save_path("rear_45", "15A12345", config, create_dir=False)
    assert not os.path.exists(resolved)
    assert "subdir" in resolved

def test_resolve_save_path_custom_date_str(tmp_path):
    config = PhotoConfig()
    config.paths["rear_45"] = str(tmp_path / "{date}")
    resolved = resolve_save_path("rear_45", "15A12345", config, create_dir=False, date_str="20261001")
    assert "20261001" in resolved
    assert not os.path.exists(resolved)

    resolved_dashed = resolve_save_path("rear_45", "15A12345", config, create_dir=False, date_str="2026-10-01")
    assert "20261001" in resolved_dashed
    assert not os.path.exists(resolved_dashed)

# --- validate_jpeg ---
def test_validate_real_jpeg():
    # Minimal valid JPEG: FF D8 FF E0 ... FF D9
    jpeg_bytes = b"\xff\xd8\xff\xe0" + b"\x00" * 100 + b"\xff\xd9"
    assert validate_jpeg(jpeg_bytes) is True

def test_validate_png_rejected():
    png_bytes = b"\x89PNG\r\n\x1a\n" + b"\x00" * 100
    assert validate_jpeg(png_bytes) is False

def test_validate_empty_rejected():
    assert validate_jpeg(b"") is False

# --- save_photo ---
def test_save_photo_creates_file(tmp_path):
    config = PhotoConfig(photo_save_dir=str(tmp_path))
    config.paths["rear_45"] = str(tmp_path / "{date}")
    jpeg = b"\xff\xd8\xff\xe0" + b"\x00" * 100 + b"\xff\xd9"
    result = save_photo(jpeg, "15A12345", "T", "rear_45", None, config)
    assert result["ok"] is True
    assert os.path.exists(result["path"])

def test_save_photo_rejects_non_jpeg(tmp_path):
    config = PhotoConfig(photo_save_dir=str(tmp_path))
    config.paths["rear_45"] = str(tmp_path / "{date}")
    png = b"\x89PNG\r\n\x1a\n" + b"\x00" * 100
    result = save_photo(png, "15A12345", "T", "rear_45", None, config)
    assert result["ok"] is False

def test_save_photo_rejects_oversize(tmp_path):
    config = PhotoConfig(photo_save_dir=str(tmp_path))
    config.paths["rear_45"] = str(tmp_path / "{date}")
    # 11MB fake JPEG
    big = b"\xff\xd8\xff\xe0" + b"\x00" * (11 * 1024 * 1024)
    result = save_photo(big, "15A12345", "T", "rear_45", None, config)
    assert result["ok"] is False

def test_save_photo_path_traversal_rejected(tmp_path):
    config = PhotoConfig(photo_save_dir=str(tmp_path))
    config.paths["rear_45"] = str(tmp_path / "{date}")
    jpeg = b"\xff\xd8\xff\xe0" + b"\x00" * 100 + b"\xff\xd9"
    result = save_photo(jpeg, "../../../etc", None, "rear_45", None, config)
    assert result["ok"] is False

def test_save_photo_rejects_invalid_photo_type(tmp_path):
    config = PhotoConfig(photo_save_dir=str(tmp_path))
    jpeg = b"\xff\xd8\xff\xe0" + b"\x00" * 100 + b"\xff\xd9"
    result = save_photo(jpeg, "15A12345", "T", "invalid_type", None, config)
    assert result["ok"] is False
    assert "Loại ảnh không hợp lệ" in result["error"]

def test_save_photo_rejects_invalid_plate_color(tmp_path):
    config = PhotoConfig(photo_save_dir=str(tmp_path))
    config.paths["rear_45"] = str(tmp_path / "{date}")
    jpeg = b"\xff\xd8\xff\xe0" + b"\x00" * 100 + b"\xff\xd9"
    result = save_photo(jpeg, "15A12345", "INVALID", "rear_45", None, config)
    assert result["ok"] is False
    assert result["error"] == "Màu biển không hợp lệ"

def test_save_photo_valid_plate_colors(tmp_path):
    config = PhotoConfig(photo_save_dir=str(tmp_path))
    config.paths["rear_45"] = str(tmp_path / "{date}")
    jpeg = b"\xff\xd8\xff\xe0" + b"\x00" * 100 + b"\xff\xd9"
    for color in (None, "", "T", "V", "X"):
        res = save_photo(jpeg, "15A12345", color, "rear_45", None, config)
        assert res["ok"] is True

def test_save_photo_auto_increments_seq(tmp_path):
    config = PhotoConfig(photo_save_dir=str(tmp_path))
    config.paths["passenger"] = str(tmp_path / "{date}" / "{plate}")
    jpeg = b"\xff\xd8\xff\xe0" + b"\x00" * 100 + b"\xff\xd9"
    # First save with seq=None
    res1 = save_photo(jpeg, "15A12345", None, "passenger", None, config)
    assert res1["ok"] is True
    assert res1["filename"] == "15A12345T_1.jpg"
    assert os.path.exists(res1["path"])
    assert "15A12345T" in res1["path"]

    # Second save with seq=None
    res2 = save_photo(jpeg, "15A12345", None, "passenger", None, config)
    assert res2["ok"] is True
    assert res2["filename"] == "15A12345T_2.jpg"
    assert os.path.exists(res2["path"])

    # Third save with seq=None
    res3 = save_photo(jpeg, "15A12345", None, "passenger", None, config)
    assert res3["ok"] is True
    assert res3["filename"] == "15A12345T_3.jpg"
    assert os.path.exists(res3["path"])

    # Explicit seq is preserved
    res_explicit = save_photo(jpeg, "15A12345", None, "passenger", 10, config)
    assert res_explicit["ok"] is True
    assert res_explicit["filename"] == "15A12345T_10.jpg"

    # Next auto seq picks up max + 1
    res4 = save_photo(jpeg, "15A12345", None, "passenger", None, config)
    assert res4["ok"] is True
    assert res4["filename"] == "15A12345T_11.jpg"


def test_sync_new_vehicle_copies_front_and_rear(tmp_path):
    root = str(tmp_path / "photos")
    config = PhotoConfig(
        photo_save_dir=root,
        paths={
            "rear_45": root,
            "front_45": root,
            "chassis": root,
            "passenger": os.path.join(root, "{date}", "{plate}"),
            "new_vehicle": os.path.join(root, "{date}", "{plate}"),
        },
        sync_new_vehicle_45=True,
    )
    jpeg = b"\xff\xd8\xff\xe0" + b"\x00" * 20 + b"\xff\xd9"

    # Save rear and front first
    res_rear = save_photo(jpeg, "15A12345", "T", "rear_45", None, config)
    assert res_rear["ok"] is True
    res_front = save_photo(jpeg, "15A12345", "T", "front_45", None, config)
    assert res_front["ok"] is True

    # Now save new_vehicle photo
    res_nv = save_photo(jpeg, "15A12345", "T", "new_vehicle", None, config)
    assert res_nv["ok"] is True

    # Check that new_vehicle subfolder now contains rear_45 and front_45 copies!
    nv_dir = os.path.dirname(res_nv["path"])
    assert os.path.exists(os.path.join(nv_dir, "15A12345T.jpg"))
    assert os.path.exists(os.path.join(nv_dir, "bs15A12345T.jpg"))
    assert os.path.exists(os.path.join(nv_dir, "15A12345T_1.jpg"))

def test_save_photo_45_directly_in_chosen_dir_without_date(tmp_path):
    chosen_dir = tmp_path / "AnhPhuongTien"
    config = PhotoConfig(photo_save_dir=str(chosen_dir))
    jpeg = b"\xff\xd8\xff\xe0" + b"\x00" * 100 + b"\xff\xd9"
    result = save_photo(jpeg, "15A12345", "T", "rear_45", None, config)
    assert result["ok"] is True
    expected_path = os.path.join(str(chosen_dir), "15A12345T.jpg")
    assert result["path"] == expected_path
    assert os.path.exists(expected_path)
    # Ensure no subdirectories were created inside chosen_dir
    assert os.listdir(str(chosen_dir)) == ["15A12345T.jpg"]


# --- lan_kd (Lần 2) tests ---
def test_filename_lan2_rear_45_with_color():
    assert build_filename("15A12345", "T", "rear_45", None, False, lan_kd=2) == "15A12345TL2.jpg"

def test_filename_lan2_front_45_with_color():
    assert build_filename("15A12345", "T", "front_45", None, False, lan_kd=2) == "bs15A12345TL2.jpg"

def test_filename_lan2_no_color():
    assert build_filename("11K2639", None, "rear_45", None, False, lan_kd=2) == "11K2639L2.jpg"
    assert build_filename("11K2639", None, "front_45", None, False, lan_kd=2) == "bs11K2639L2.jpg"

def test_filename_lan2_passenger():
    assert build_filename("15A12345", "T", "passenger", 1, False, lan_kd=2) == "15A12345TL2_1.jpg"

def test_filename_lan2_chassis():
    assert build_filename("15A12345", "T", "chassis", None, False, lan_kd=2) == "sk_15A12345TL2.jpg"

def test_save_photo_lan2(tmp_path):
    chosen_dir = tmp_path / "Photos"
    config = PhotoConfig(photo_save_dir=str(chosen_dir))
    jpeg = b"\xff\xd8\xff\xe0" + b"\x00" * 100 + b"\xff\xd9"
    result = save_photo(jpeg, "15A12345", "T", "rear_45", None, config, lan_kd=2)
    assert result["ok"] is True
    assert result["filename"] == "15A12345TL2.jpg"
    expected_path = os.path.join(str(chosen_dir), "15A12345TL2.jpg")
    assert result["path"] == expected_path
    assert os.path.exists(expected_path)


def test_filename_lan2_no_duplicate_tl2():
    # Verify that passing already-suffixed plates does NOT result in duplicate suffixes (e.g. TL2TL2)
    assert build_filename("15A12345TL2", "T", "rear_45", None, False, lan_kd=2) == "15A12345TL2.jpg"
    assert build_filename("15A12345TL2", "T", "front_45", None, False, lan_kd=2) == "bs15A12345TL2.jpg"
    assert build_filename("15A12345TL2TL2", "T", "rear_45", None, False, lan_kd=2) == "15A12345TL2.jpg"
    assert build_filename("15A12345T", "T", "rear_45", None, False, lan_kd=2) == "15A12345TL2.jpg"
    assert build_filename("15A12345TT", "T", "rear_45", None, False, lan_kd=2) == "15A12345TL2.jpg"
    assert build_filename("11K2639L2", None, "rear_45", None, False, lan_kd=2) == "11K2639L2.jpg"
    assert build_filename("15A12345TL2", "T", "passenger", 1, False, lan_kd=2) == "15A12345TL2_1.jpg"


def test_save_photo_lan2_no_duplicate_tl2(tmp_path):
    chosen_dir = tmp_path / "Photos"
    config = PhotoConfig(photo_save_dir=str(chosen_dir))
    jpeg = b"\xff\xd8\xff\xe0" + b"\x00" * 100 + b"\xff\xd9"
    # Even if client sends plate="15A12345TL2", the saved file must be 15A12345TL2.jpg, NOT 15A12345TL2TL2.jpg
    result = save_photo(jpeg, "15A12345TL2", "T", "rear_45", None, config, lan_kd=2)
    assert result["ok"] is True
    assert result["filename"] == "15A12345TL2.jpg"
    assert os.path.exists(os.path.join(str(chosen_dir), "15A12345TL2.jpg"))


def test_plate_with_l_series():
    # Plates with 'L' in the series (e.g. 16L-3565, 29L-1234) must NOT be truncated to province code
    p, c, lan = clean_plate_and_color("16L-3565", None, 1)
    assert p == "16L3565"
    assert c is None
    assert lan == 1

    p2, c2, lan2 = clean_plate_and_color("16L3565", None, 1)
    assert p2 == "16L3565"
    assert c2 is None
    assert lan2 == 1

    # Lần 1 filenames
    assert build_filename("16L-3565", None, "rear_45", None, False, lan_kd=1) == "16L3565.jpg"
    assert build_filename("16L-3565", None, "front_45", None, False, lan_kd=1) == "bs16L3565.jpg"
    assert build_filename("16L3565", None, "rear_45", None, False, lan_kd=1) == "16L3565.jpg"
    assert build_filename("16L3565", None, "front_45", None, False, lan_kd=1) == "bs16L3565.jpg"

    # Lần 2 filenames
    assert build_filename("16L3565", None, "rear_45", None, False, lan_kd=2) == "16L3565L2.jpg"
    assert build_filename("16L3565", None, "front_45", None, False, lan_kd=2) == "bs16L3565L2.jpg"
    assert build_filename("16L-3565L2", None, "rear_45", None, False, lan_kd=2) == "16L3565L2.jpg"


def test_save_photo_plate_with_l_series(tmp_path):
    chosen_dir = tmp_path / "Photos"
    config = PhotoConfig(photo_save_dir=str(chosen_dir))
    jpeg = b"\xff\xd8\xff\xe0" + b"\x00" * 100 + b"\xff\xd9"
    result = save_photo(jpeg, "16L-3565", None, "rear_45", None, config, lan_kd=1)
    assert result["ok"] is True
    assert result["filename"] == "16L3565.jpg"
    assert os.path.exists(os.path.join(str(chosen_dir), "16L3565.jpg"))



