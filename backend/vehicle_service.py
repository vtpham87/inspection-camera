import os
import re
import json
import time
import sqlite3
import subprocess
from datetime import datetime
from config import PhotoConfig
from photo_handler import extract_plate_color, resolve_save_path, build_filename

PHOTO_TYPES = ["rear_45", "front_45", "chassis", "passenger", "new_vehicle"]

ACCESS_DB_PATH = os.environ.get("PTCGDB_ACCESS_PATH") or r"Z:\DataPTCGDB\PT90_1507D.mdb"
SYSDB_PATH = os.environ.get("PTCGDB_MDW_PATH") or r"C:\PTCGDB\SysDb.mdw"
POWERSHELL_32 = r"C:\Windows\SysWOW64\WindowsPowerShell\v1.0\powershell.exe"

D32_MAP = {
    'õ': 'ư', 'ç': 'ơ', 'Ù': 'ị', 'ä': 'ổ', 'Ï': 'í', '¨': 'ả', 'ì': 'ợ', '­': 'ạ',
    'Æ': 'ế', 'Ë': 'ề', 'ï': 'ù', '¶': 'ấ', 'ø': 'ử', '¸': 'ẩ', 'á': 'ô', 'ã': 'ồ',
    'ÿ': 'ý', '·': 'ầ', 'ö': 'ứ', '¬': 'ã', '±': 'ằ', 'ñ': 'ũ', 'Å': 'ê', 'Ü': 'ó',
    'à': 'ọ', 'â': 'ố', 'é': 'ờ', 'Ø': 'ĩ', 'Î': 'ệ', 'º': 'ậ', '°': 'ắ', '´': 'ặ',
    'Ì': 'ể', 'ù': 'ữ', 'Ý': 'ò', 'µ': 'â', '¡': 'à', '¯': 'ă',
    '½': 'đ', '‡': 'Đ', chr(135): 'Đ', chr(159): 'á', 'Í': 'ễ', '«': 'ẫ', '©': 'ấ', '§': 'â', 'ª': 'ẩ',
    '‚': 'Á', 'ƒ': 'Ă', '„': 'Â', '‰': 'Ê', '\x8d': 'Ô', 'œ': 'Ư',
    '™': 'Ủ', 'ž': 'Ý', '\x9d': 'Ứ', '˜': 'Í', '\x8f': 'ạ'
}


def decode_d32(text: str) -> str:
    if not text:
        return ""
    if text.startswith("á tá"):
        text = "\x8d tá" + text[4:]
    return "".join(D32_MAP.get(c, c) for c in text)


_ACCESS_CACHE: dict = {"timestamp": 0.0, "data": []}


def get_waiting_vehicles_from_access() -> list[dict]:
    """Query tmp_DangKyKD from PTCGDB Access DB via 32-bit PowerShell with 3s cache."""
    global _ACCESS_CACHE
    now = time.time()
    if now - _ACCESS_CACHE["timestamp"] < 3.0 and _ACCESS_CACHE["data"]:
        return _ACCESS_CACHE["data"]

    if not os.path.exists(ACCESS_DB_PATH):
        return []

    ps_script = f"""
$connStr = "Driver={{Microsoft Access Driver (*.mdb)}};Dbq={ACCESS_DB_PATH};SystemDB={SYSDB_PATH};Uid=NhanVienNV;Pwd=NhapHSPT&InGCN;"
$conn = New-Object System.Data.Odbc.OdbcConnection($connStr)
try {{
    $conn.Open()
    $cmd = $conn.CreateCommand()
    $cmd.CommandText = @"
SELECT t.SoPhieuKD, t.BienDK_ID, t.GioKD, t.KetLuan, t.CD1, t.CD2, t.CD3, t.CD4, t.CD5,
       p.BienDK, p.TenLoaiPT, p.NhanHieu, p.ChuPT
FROM tmp_DangKyKD t LEFT JOIN PT_PhuongTien p ON t.BienDK_ID = p.BienDK_ID
ORDER BY t.SoPhieuKD ASC
"@
    $reader = $cmd.ExecuteReader()
    $list = @()
    while ($reader.Read()) {{
        $item = [PSCustomObject]@{{
            SoPhieuKD = $reader['SoPhieuKD']
            BienDK_ID = $reader['BienDK_ID']
            GioKD     = if ($reader['GioKD'] -ne [DBNull]::Value) {{ $reader['GioKD'].ToString('HH:mm:ss') }} else {{ '' }}
            KetLuan   = $reader['KetLuan']
            BienDK    = if ($reader['BienDK'] -ne [DBNull]::Value) {{ $reader['BienDK'].ToString() }} else {{ '' }}
            TenLoaiPT = if ($reader['TenLoaiPT'] -ne [DBNull]::Value) {{ $reader['TenLoaiPT'].ToString() }} else {{ '' }}
            NhanHieu  = if ($reader['NhanHieu'] -ne [DBNull]::Value) {{ $reader['NhanHieu'].ToString() }} else {{ '' }}
            ChuPT     = if ($reader['ChuPT'] -ne [DBNull]::Value) {{ $reader['ChuPT'].ToString() }} else {{ '' }}
        }}
        $list += $item
    }}
    $conn.Close()
    $json = $list | ConvertTo-Json -Compress
    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
    Write-Output $json
}} catch {{
    Write-Output "[]"
}}
"""
    try:
        res = subprocess.run(
            [POWERSHELL_32, "-NoProfile", "-Command", ps_script],
            capture_output=True,
            text=True,
            timeout=5
        )
        out = res.stdout.strip()
        if not out or out == "[]":
            return _ACCESS_CACHE["data"]
        data = json.loads(out)
        rows = [data] if isinstance(data, dict) else data
        _ACCESS_CACHE["timestamp"] = now
        _ACCESS_CACHE["data"] = rows
        return rows
    except Exception:
        return _ACCESS_CACHE["data"]


def _check_photos_taken(
    plate: str,
    plate_color: str | None,
    config: PhotoConfig,
    date: str | None = None,
) -> list[str]:
    taken = []
    for pt in PHOTO_TYPES:
        try:
            save_dir = resolve_save_path(
                pt, plate, config, create_dir=False, date_str=date
            )
            filename = build_filename(
                plate,
                plate_color,
                pt,
                1 if pt in ("passenger", "new_vehicle") else None,
                config.plate_color_suffix,
            )
            if os.path.exists(os.path.join(save_dir, filename)):
                taken.append(pt)
            elif date and "{date}" not in config.paths.get(pt, ""):
                date_clean = date.replace("-", "")
                if os.path.exists(os.path.join(save_dir, date_clean, filename)):
                    taken.append(pt)
        except Exception:
            pass
    return taken


def get_vehicles_today(
    db_path: str,
    date: str | None,
    config: PhotoConfig,
    filter_waiting: bool = True,
) -> list[dict] | None:
    if not config.vehicle_list_enabled:
        return None

    if not os.path.exists(db_path):
        return []

    today_str = datetime.now().strftime("%Y-%m-%d")
    if date is None:
        date = today_str

    conn = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
    try:
        conn.row_factory = sqlite3.Row
        cur = conn.cursor()

        cur.execute("PRAGMA table_info(inspections)")
        col_names = {r["name"] for r in cur.fetchall()}
        has_sotem = "sotem" in col_names

        is_today = (date == today_str)
        is_production_db = os.path.normpath(db_path).endswith("ptcgdb.db")
        waiting_raw = []
        if is_today and is_production_db and os.path.exists(ACCESS_DB_PATH):
            waiting_raw = get_waiting_vehicles_from_access()

        results = []
        seen_tickets = set()

        # 1. Nếu có dữ liệu hàng đợi từ tmp_DangKyKD (DanhGiaKT)
        if waiting_raw:
            for r in waiting_raw:
                sp = r.get("SoPhieuKD") or ""
                biendk_id = r.get("BienDK_ID") or ""
                ketluan = r.get("KetLuan")
                giokd = r.get("GioKD") or ""

                if filter_waiting and ketluan == 1:
                    continue

                seen_tickets.add(sp)
                plate_num, plate_color = extract_plate_color(biendk_id)
                photos_taken = _check_photos_taken(plate_num, plate_color, config, date=date)

                cur.execute(
                    "SELECT biendk, nhanhieu, tenloaipt, chupt FROM vehicles WHERE biendk_clean = ? OR biendk_id = ?",
                    (biendk_id, biendk_id),
                )
                v_row = cur.fetchone()
                if v_row:
                    plate_display = v_row["biendk"]
                    brand = v_row["nhanhieu"] or ""
                    vtype = v_row["tenloaipt"] or ""
                    owner = v_row["chupt"] or ""
                else:
                    plate_display = r.get("BienDK") or biendk_id
                    brand = decode_d32(r.get("NhanHieu") or "")
                    vtype = decode_d32(r.get("TenLoaiPT") or "")
                    owner = decode_d32(r.get("ChuPT") or "")

                m = re.match(r"^(\d+)", sp.strip())
                ticket_int = int(m.group(1)) if m else 999999

                results.append({
                    "ticket_num": sp,
                    "sophieu": sp,
                    "ticket_int": ticket_int,
                    "plate": plate_display,
                    "plate_clean": plate_num,
                    "plate_color": plate_color,
                    "vehicle_type": vtype,
                    "brand": brand,
                    "owner": owner,
                    "time": giokd,
                    "result": ketluan,
                    "sotem": "",
                    "photos_taken": photos_taken,
                })

        # 2. Truy vấn bổ sung từ SQLite inspections (hoặc làm nguồn chính khi không có Access/lịch sử)
        if not waiting_raw or not filter_waiting:
            where_clause = "WHERE i.ngaykd = ?"
            if filter_waiting:
                where_clause += " AND (i.ketluan != 1 OR i.ketluan IS NULL)"
                if has_sotem:
                    where_clause += " AND (i.sotem IS NULL OR trim(i.sotem) = '')"

            sotem_select = "i.sotem" if has_sotem else "'' AS sotem"
            cur.execute(
                f"""
                SELECT i.sophieu, v.biendk, v.biendk_clean, v.chupt, v.nhanhieu, v.tenloaipt,
                       i.ngaykd, i.giokd, i.ketluan, {sotem_select}
                FROM inspections i
                JOIN vehicles v ON i.biendk_id = v.biendk_id
                {where_clause}
                """,
                (date,),
            )
            rows = cur.fetchall()

            for row in rows:
                sp = row["sophieu"] or ""
                if sp in seen_tickets:
                    continue
                seen_tickets.add(sp)

                plate_num, plate_color = extract_plate_color(row["biendk_clean"])
                photos_taken = _check_photos_taken(plate_num, plate_color, config, date=date)

                m = re.match(r"^(\d+)", sp.strip())
                ticket_int = int(m.group(1)) if m else 999999

                results.append({
                    "ticket_num": sp,
                    "sophieu": sp,
                    "ticket_int": ticket_int,
                    "plate": row["biendk"],
                    "plate_clean": plate_num,
                    "plate_color": plate_color,
                    "vehicle_type": row["tenloaipt"],
                    "brand": row["nhanhieu"],
                    "owner": row["chupt"],
                    "time": row["giokd"],
                    "result": row["ketluan"],
                    "sotem": row["sotem"],
                    "photos_taken": photos_taken,
                })
    finally:
        conn.close()

    # Sắp xếp thứ tự theo số phiếu tăng dần
    results.sort(key=lambda x: (x["ticket_int"], x.get("time") or ""))
    return results
