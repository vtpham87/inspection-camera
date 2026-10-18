import os
import re
import json
import time
import sqlite3
import subprocess
import logging
import threading
from datetime import datetime

from config import PhotoConfig, ACCESS_DB_PATH, ACCESS_DB_Z_PATH, SYSDB_PATH, DB_PATH, ACCESS_DB_USER, ACCESS_DB_PASSWORD
from photo_handler import extract_plate_color, resolve_save_path, build_filename, should_omit_color_suffix

logger = logging.getLogger("vehicle_service")

PHOTO_TYPES = ["rear_45", "front_45", "chassis", "passenger", "new_vehicle"]

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


# Circuit breaker: chỉ giữ bộ đệm khi Access trả rỗng trong vài chu kỳ liên tiếp (chu kỳ 5s), sau đó coi là hết xe chờ thật
_EMPTY_STREAK = 0
_EMPTY_STREAK_LIMIT = 6

# Khoá bảo vệ trạng thái dùng chung giữa worker nền và các request (cập nhật gộp nhiều khoá phải nguyên tử)
_STATE_LOCK = threading.RLock()
# Chỉ 1 lượt đồng bộ chạy tại một thời điểm (worker 5s và /api/sync-vehicles-now không giẫm nhau, không spawn PowerShell song song)
_SYNC_LOCK = threading.Lock()

_ACCESS_PATH_CACHE: dict = {"ts": 0.0, "value": None}
_ACCESS_PATH_TTL = 5.0       # giây: tránh gọi os.path.exists trên ổ mạng mỗi request
_NET_USE_COOLDOWN = 60.0     # giây: tránh chạy `net use` (tối đa 5s) liên tục khi ổ Z: mất
_LAST_NET_USE = 0.0


def resolve_access_db_path() -> str | None:
    """Trả về đường dẫn CSDL Access hợp lệ (có cache ngắn hạn)."""
    now = time.time()
    if now - _ACCESS_PATH_CACHE["ts"] < _ACCESS_PATH_TTL:
        return _ACCESS_PATH_CACHE["value"]
    value = _resolve_access_db_path_uncached()
    _ACCESS_PATH_CACHE["ts"] = time.time()
    _ACCESS_PATH_CACHE["value"] = value
    return value


def _resolve_access_db_path_uncached() -> str | None:
    r"""LƯU Ý: Jet ODBC 32-bit (Driver={Microsoft Access Driver (*.mdb)}) yêu cầu ký tự ổ đĩa (Z:\...),
    không hỗ trợ kết nối trực tiếp qua đường dẫn UNC.
    """
    global _LAST_NET_USE
    # 1. Đường dẫn cấu hình (PTCGDB_ACCESS_PATH), rồi ổ Z: mặc định; bỏ qua UNC (Jet ODBC không hỗ trợ)
    for candidate in dict.fromkeys((ACCESS_DB_PATH, ACCESS_DB_Z_PATH)):
        if candidate and not candidate.startswith("\\\\") and os.path.exists(candidate):
            return candidate
    # 2. Nếu ổ Z: chưa mount nhưng mạng thông (UNC tồn tại), tự động mount lại ổ Z:
    if time.time() - _LAST_NET_USE < _NET_USE_COOLDOWN:
        return None
    _LAST_NET_USE = time.time()
    try:
        subprocess.run(
            ["cmd.exe", "/c", "net", "use", "Z:", r"\\T1507\Data", "/persistent:yes"],
            check=False,
            capture_output=True,
            timeout=5,
        )
        if os.path.exists(ACCESS_DB_Z_PATH):
            return ACCESS_DB_Z_PATH
    except Exception as e:
        logger.debug("Bỏ qua lỗi không nghiêm trọng: %s", e, exc_info=True)
    return None


_WAITING_SYNC_STATE: dict = {
    "timestamp": 0.0,
    "last_success": 0.0,
    "has_success": False,
    "status": "idle",
    "last_error": None,
    "count": 0,
}


def _set_sync_state(**kw) -> None:
    with _STATE_LOCK:
        _WAITING_SYNC_STATE.update(kw)


def waiting_db_path(db_path: str = DB_PATH) -> str:
    """Đường dẫn file SQLite riêng (WAL) chứa bảng đệm waiting_vehicles, đặt cạnh CSDL nguồn.

    Tách khỏi ptcgdb.db để không đổi journal mode / không khoá file CSDL dùng chung với phần mềm khác.
    """
    root, ext = os.path.splitext(db_path)
    return f"{root}_waiting{ext or '.db'}"


_WAITING_TABLE_READY: set[str] = set()
_WAITING_ROWS_SIG: dict[str, tuple] = {}


def _open_waiting_rw(db_path: str) -> sqlite3.Connection:
    conn = sqlite3.connect(waiting_db_path(db_path), timeout=10.0)
    conn.execute("PRAGMA busy_timeout=10000")
    return conn


def init_waiting_vehicles_table(db_path: str = DB_PATH) -> None:
    """Tạo file bộ đệm (WAL) + bảng waiting_vehicles; chỉ chạy DDL 1 lần/tiến trình cho mỗi CSDL nguồn."""
    if db_path in _WAITING_TABLE_READY or not os.path.exists(db_path):
        return
    try:
        conn = _open_waiting_rw(db_path)
        try:
            conn.execute("PRAGMA journal_mode=WAL")
            conn.execute("PRAGMA synchronous=NORMAL")
            with conn:
                conn.execute("""
                    CREATE TABLE IF NOT EXISTS waiting_vehicles (
                        sophieu TEXT PRIMARY KEY,
                        biendk_id TEXT,
                        giokd TEXT,
                        ketluan INTEGER DEFAULT -1,
                        lankd INTEGER DEFAULT 1,
                        biendk TEXT,
                        tenloaipt TEXT,
                        nhanhieu TEXT,
                        chupt TEXT,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    );
                """)
                conn.execute("CREATE INDEX IF NOT EXISTS idx_wv_biendk_id ON waiting_vehicles(biendk_id);")
            _WAITING_TABLE_READY.add(db_path)
        finally:
            conn.close()
    except Exception as e:
        logger.warning(f"Lỗi khởi tạo bảng waiting_vehicles trong SQLite: {e}")


def _ps_dq(value: str) -> str:
    """Escape chuỗi để nhúng an toàn vào chuỗi PowerShell nháy kép."""
    return str(value).replace("`", "``").replace('"', '`"').replace("$", "`$")


def fetch_waiting_from_access_raw() -> list[dict] | None:
    """Truy vấn tmp_DangKyKD từ CSDL Access qua PowerShell 32-bit ODBC.
    Trả về list[dict] nếu thành công, None nếu thất bại.
    """
    if not ACCESS_DB_USER or not ACCESS_DB_PASSWORD:
        logger.error("Chưa đặt PTCGDB_ACCESS_USER / PTCGDB_ACCESS_PASSWORD: bỏ qua truy vấn Access")
        return None
    active_path = resolve_access_db_path()
    if not active_path:
        logger.warning("Không thể truy cập CSDL Access (máy chủ hoặc ổ đĩa mạng chưa sẵn sàng)")
        return None

    ps_script = f"""
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$connStr = "Driver={{Microsoft Access Driver (*.mdb)}};Dbq={_ps_dq(active_path)};SystemDB={_ps_dq(SYSDB_PATH)};Uid=$($env:PTCGDB_PS_USER);Pwd=$($env:PTCGDB_PS_PWD);"
$conn = New-Object System.Data.Odbc.OdbcConnection($connStr)
try {{
    $conn.Open()
    $cmd = $conn.CreateCommand()
    $ps_cmd = @"
SELECT t.SoPhieuKD, t.BienDK_ID, t.GioKD, t.KetLuan, t.LanKD,
       p.BienDK, p.TenLoaiPT, p.NhanHieu, p.ChuPT
FROM tmp_DangKyKD t LEFT JOIN PT_PhuongTien p ON t.BienDK_ID = p.BienDK_ID
ORDER BY t.SoPhieuKD ASC
"@
    $cmd.CommandText = $ps_cmd
    $reader = $cmd.ExecuteReader()
    $list = [System.Collections.Generic.List[object]]::new()
    while ($reader.Read()) {{
        $list.Add([PSCustomObject]@{{
            SoPhieuKD = $reader['SoPhieuKD']
            BienDK_ID = $reader['BienDK_ID']
            GioKD     = if ($reader['GioKD'] -ne [DBNull]::Value) {{ $reader['GioKD'].ToString('HH:mm:ss') }} else {{ '' }}
            KetLuan   = if ($reader['KetLuan'] -ne [DBNull]::Value) {{ [int]$reader['KetLuan'] }} else {{ -1 }}
            LanKD     = if ($reader['LanKD'] -ne [DBNull]::Value) {{ [int]$reader['LanKD'] }} else {{ 1 }}
            BienDK    = if ($reader['BienDK'] -ne [DBNull]::Value) {{ $reader['BienDK'].ToString() }} else {{ '' }}
            TenLoaiPT = if ($reader['TenLoaiPT'] -ne [DBNull]::Value) {{ $reader['TenLoaiPT'].ToString() }} else {{ '' }}
            NhanHieu  = if ($reader['NhanHieu'] -ne [DBNull]::Value) {{ $reader['NhanHieu'].ToString() }} else {{ '' }}
            ChuPT     = if ($reader['ChuPT'] -ne [DBNull]::Value) {{ $reader['ChuPT'].ToString() }} else {{ '' }}
        }})
    }}
    if ($list.Count -eq 0) {{
        Write-Output "[]"
    }} else {{
        $json = $list | ConvertTo-Json -Compress
        Write-Output $json
    }}
}} catch {{
    [Console]::Error.WriteLine($_.Exception.Message)
    Write-Output "ERROR"
}} finally {{
    if ($conn.State -eq [System.Data.ConnectionState]::Open) {{
        $conn.Close()
    }}
    $conn.Dispose()
}}
"""
    try:
        res = subprocess.run(
            [POWERSHELL_32, "-NoProfile", "-Command", ps_script],
            check=False,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=12,
            # Truyền thông tin đăng nhập qua biến môi trường của tiến trình con (không nằm trong dòng lệnh)
            env={**os.environ, "PTCGDB_PS_USER": ACCESS_DB_USER, "PTCGDB_PS_PWD": ACCESS_DB_PASSWORD},
        )
        out = (res.stdout or "").strip()
        err = (res.stderr or "").strip()
        if out == "ERROR" or res.returncode != 0:
            if err:
                logger.warning(f"Lỗi truy vấn Access DB qua PowerShell (code={res.returncode}): {err}")
            return None

        if not out:
            logger.warning("Truy vấn Access DB qua PowerShell trả về stdout rỗng, coi là lỗi kết nối")
            return None

        if out == "[]":
            return []

        data = json.loads(out)
        return [data] if isinstance(data, dict) else data
    except Exception as e:
        logger.warning(f"Lỗi gọi PowerShell ODBC truy vấn Access DB: {e}")
        return None


def _to_int(value, default: int) -> int:
    """int() an toàn: None/chuỗi rỗng/kiểu lạ (vd DBNull serialize thành {}) -> default."""
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def _fetch_vehicle_rows(cur: sqlite3.Cursor, bids: list[str]) -> dict:
    """Đọc bảng vehicles theo lô 100 khoá (cur phải dùng row_factory=sqlite3.Row). Trả {biendk_id: row}."""
    rows: dict = {}
    for i in range(0, len(bids), 100):
        chunk = bids[i:i + 100]
        cur.execute(
            f"SELECT biendk_id, biendk, nhanhieu, tenloaipt, chupt FROM vehicles WHERE biendk_id IN ({','.join('?' * len(chunk))})",
            chunk,
        )
        for r in cur.fetchall():
            rows[r["biendk_id"]] = r
    return rows


def _load_vehicle_map(db_path: str, bids: list[str]) -> dict:
    """Đọc thông tin xe từ bảng vehicles của CSDL nguồn (chỉ đọc). Thiếu bảng/DB lỗi -> {} (dùng dữ liệu Access)."""
    if not bids:
        return {}
    try:
        src = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True, timeout=5.0)
        try:
            src.row_factory = sqlite3.Row
            return _fetch_vehicle_rows(src.cursor(), bids)
        finally:
            src.close()
    except sqlite3.Error as e:
        logger.warning("Không đọc được bảng vehicles (%s); dùng dữ liệu từ Access cho lượt đồng bộ này", e)
        return {}


def sync_waiting_vehicles_to_sqlite(db_path: str = DB_PATH) -> bool:
    """Đồng bộ xe chờ từ CSDL Access vào bảng đệm waiting_vehicles (SQLite WAL riêng).
    Bảo vệ Circuit Breaker: nếu kết nối Access lỗi, giữ nguyên dữ liệu trong SQLite.
    Chỉ một lượt chạy tại một thời điểm.
    """
    with _SYNC_LOCK:
        return _sync_waiting_locked(db_path)


def _sync_waiting_locked(db_path: str) -> bool:
    global _EMPTY_STREAK
    now = time.time()
    _set_sync_state(timestamp=now)

    raw_items = fetch_waiting_from_access_raw()
    if raw_items is None:
        _set_sync_state(status="error")
        logger.warning("Đồng bộ xe chờ vào SQLite thất bại do không kết nối được Access DB. Giữ nguyên cache SQLite.")
        return False

    if not os.path.exists(db_path):
        _set_sync_state(status="db_not_found")
        return False

    try:
        init_waiting_vehicles_table(db_path)
        conn = _open_waiting_rw(db_path)
        try:
            cur = conn.cursor()

            # Circuit Breaker: nếu Access trả về danh sách rỗng ([]) trong giờ làm việc (07h-18h)
            # trong khi SQLite đang có xe chờ, giữ nguyên bộ đệm tránh làm mất danh sách trên app.
            if raw_items:
                _EMPTY_STREAK = 0
            else:
                _EMPTY_STREAK += 1
                cur.execute("SELECT count(*) FROM waiting_vehicles")
                curr_count = cur.fetchone()[0]
                now_hour = datetime.now().hour
                if curr_count > 0 and 7 <= now_hour < 18 and _EMPTY_STREAK <= _EMPTY_STREAK_LIMIT:
                    logger.warning(
                        f"Circuit Breaker kích hoạt: Access trả về 0 xe trong giờ làm việc ({now_hour}h) "
                        f"khi SQLite đang có {curr_count} xe chờ (lần rỗng {_EMPTY_STREAK}/{_EMPTY_STREAK_LIMIT}). Giữ nguyên bộ đệm."
                    )
                    _set_sync_state(status="circuit_breaker_active", last_success=now, has_success=True)
                    return True

            bids = list({r.get("BienDK_ID") for r in raw_items if r.get("BienDK_ID")})
            vehicle_map = _load_vehicle_map(db_path, bids)

            rows_to_insert = []
            for r in raw_items:
                sp = str(r.get("SoPhieuKD") or "")
                bid = str(r.get("BienDK_ID") or "")
                v_row = vehicle_map.get(bid)
                if v_row:
                    plate_display = v_row["biendk"] or r.get("BienDK") or bid
                    brand = v_row["nhanhieu"] or ""
                    vtype = v_row["tenloaipt"] or ""
                    owner = v_row["chupt"] or ""
                else:
                    plate_display = r.get("BienDK") or bid
                    brand = decode_d32(r.get("NhanHieu") or "")
                    vtype = decode_d32(r.get("TenLoaiPT") or "")
                    owner = decode_d32(r.get("ChuPT") or "")

                rows_to_insert.append((
                    sp,
                    bid,
                    str(r.get("GioKD") or ""),
                    _to_int(r.get("KetLuan"), -1),
                    _to_int(r.get("LanKD"), 1) or 1,
                    plate_display,
                    vtype,
                    brand,
                    owner,
                ))

            # Bỏ qua ghi khi danh sách không đổi (tránh DELETE+INSERT mỗi 5 giây): chỉ ghi lại khi chữ ký
            # khác lần trước hoặc số dòng thực tế trong bảng lệch (bảng bị xoá/sửa từ bên ngoài).
            rows_sig = tuple(rows_to_insert)
            skip_write = False
            if _WAITING_ROWS_SIG.get(db_path) == rows_sig:
                cur.execute("SELECT count(*) FROM waiting_vehicles")
                skip_write = cur.fetchone()[0] == len(rows_to_insert)
            if not skip_write:
                with conn:  # Atomic transaction
                    cur.execute("DELETE FROM waiting_vehicles")
                    cur.executemany("""
                        INSERT OR REPLACE INTO waiting_vehicles (
                            sophieu, biendk_id, giokd, ketluan, lankd, biendk, tenloaipt, nhanhieu, chupt, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                    """, rows_to_insert)
                _WAITING_ROWS_SIG[db_path] = rows_sig

            with _STATE_LOCK:
                _WAITING_SYNC_STATE.update(
                    last_success=now, has_success=True, status="ok", count=len(raw_items), last_error=None
                )
            return True
        finally:
            conn.close()
    except Exception as e:
        logger.error(f"Lỗi cập nhật bảng waiting_vehicles trong SQLite: {e}", exc_info=True)
        _set_sync_state(status="write_error", last_error=str(e))
        return False


_IDLE_SYNC_SEC = float(os.environ.get("PTCGDB_IDLE_SYNC_SEC") or 10)


def waiting_sync_interval(active_sec: float = 5.0) -> float:
    """Chu kỳ đồng bộ kế tiếp: nhanh khi có xe chờ, giãn ra khi Access trả rỗng để bớt spawn PowerShell 32-bit."""
    with _STATE_LOCK:
        idle = _WAITING_SYNC_STATE.get("status") == "ok" and _WAITING_SYNC_STATE.get("count", 0) == 0
    return max(active_sec, _IDLE_SYNC_SEC) if idle else active_sec


def get_waiting_vehicles_from_sqlite(db_path: str = DB_PATH) -> list[dict]:
    """Đọc danh sách xe chờ trực tiếp từ bảng đệm waiting_vehicles trong SQLite WAL riêng (< 5ms)."""
    buf = waiting_db_path(db_path)
    if not os.path.exists(buf):
        return []
    try:
        conn = sqlite3.connect(f"file:{buf}?mode=ro", uri=True, timeout=5.0)
        try:
            cur = conn.execute(
                "SELECT sophieu, biendk_id, giokd, ketluan, lankd, biendk, tenloaipt, nhanhieu, chupt "
                "FROM waiting_vehicles ORDER BY sophieu ASC"
            )
            return [
                {
                    "SoPhieuKD": r[0],
                    "BienDK_ID": r[1],
                    "GioKD": r[2] or "",
                    "KetLuan": r[3],
                    "LanKD": r[4] or 1,
                    "BienDK": r[5] or "",
                    "TenLoaiPT": r[6] or "",
                    "NhanHieu": r[7] or "",
                    "ChuPT": r[8] or "",
                }
                for r in cur.fetchall()
            ]
        finally:
            conn.close()
    except sqlite3.OperationalError as e:  # chưa có bảng (chưa đồng bộ lần nào) hoặc file đang bị khoá
        logger.debug("Chưa đọc được waiting_vehicles: %s", e)
        return []
    except Exception as e:
        logger.warning("Lỗi đọc waiting_vehicles từ SQLite: %s", e)
        return []


def get_waiting_vehicles_from_access(db_path: str = DB_PATH) -> list[dict] | None:
    """Danh sách xe chờ: ưu tiên bộ đệm SQLite (< 5ms, không phụ thuộc Z:).

    Bộ đệm rỗng nhưng đã từng đồng bộ thành công = thật sự 0 xe chờ. Chưa từng đồng bộ -> hỏi thẳng Access
    (None nếu lỗi kết nối).
    """
    cached = get_waiting_vehicles_from_sqlite(db_path)
    if cached:
        return cached
    with _STATE_LOCK:
        if _WAITING_SYNC_STATE.get("has_success", False):
            return []
    return fetch_waiting_from_access_raw()


def get_access_status() -> dict:
    """Trả về trạng thái kết nối CSDL Access và tính khả dụng của bộ đệm SQLite."""
    active_path = resolve_access_db_path()
    sqlite_count = 0
    buf = waiting_db_path(DB_PATH)
    if os.path.exists(buf):
        try:
            conn = sqlite3.connect(f"file:{buf}?mode=ro", uri=True, timeout=1.0)
            try:
                sqlite_count = conn.execute("SELECT count(*) FROM waiting_vehicles").fetchone()[0]
            finally:
                conn.close()
        except sqlite3.Error as e:  # chưa có bảng / file đang khoá: coi như bộ đệm rỗng
            logger.debug("Bỏ qua lỗi không nghiêm trọng: %s", e)

    with _STATE_LOCK:
        state = dict(_WAITING_SYNC_STATE)
    return {
        "ready": active_path is not None,
        "path": active_path,
        "has_cache": state["has_success"] or sqlite_count > 0,
        "cache_count": sqlite_count if sqlite_count > 0 else state["count"],
        "last_success": state["last_success"],
        "sync_status": state["status"],
    }


_DIR_CACHE: dict = {}
_DIR_CACHE_TTL = 2.0
_DIR_CACHE_MAX = 256


def _get_dir_files(path: str) -> set:
    now = time.time()
    entry = _DIR_CACHE.get(path)
    if entry and now - entry["time"] < _DIR_CACHE_TTL:
        return entry["files"]
    try:
        files = set(os.listdir(path))
    except Exception:
        files = set()
    if len(_DIR_CACHE) >= _DIR_CACHE_MAX:
        for k in [k for k, v in list(_DIR_CACHE.items()) if now - v["time"] >= _DIR_CACHE_TTL]:
            _DIR_CACHE.pop(k, None)
        if len(_DIR_CACHE) >= _DIR_CACHE_MAX:
            _DIR_CACHE.clear()
    _DIR_CACHE[path] = {"time": now, "files": files}
    return files

def _check_photos_taken(
    plate: str,
    plate_color: str | None,
    config: PhotoConfig,
    date: str | None = None,
    lan_kd: int = 1,
) -> list[str]:
    taken = []
    for pt in PHOTO_TYPES:
        try:
            save_dir = resolve_save_path(
                pt, plate, config, create_dir=False, date_str=date, plate_color=plate_color
            )
            filename = build_filename(
                plate,
                plate_color,
                pt,
                1 if pt in ("passenger", "new_vehicle") else None,
                config.plate_color_suffix,
                lan_kd=lan_kd,
            )
            files = _get_dir_files(save_dir)
            if filename in files:
                taken.append(pt)
            elif date and "{date}" not in config.paths.get(pt, ""):
                date_clean = date.replace("-", "")
                sub_dir = os.path.join(save_dir, date_clean)
                sub_files = _get_dir_files(sub_dir)
                if filename in sub_files:
                    taken.append(pt)
        except Exception as e:
            logger.debug("Bỏ qua lỗi không nghiêm trọng: %s", e, exc_info=True)
    return taken


def check_plate_status(
    db_path: str,
    plate: str,
    plate_color: str | None,
    config: PhotoConfig,
    date: str | None = None,
) -> dict:
    today_str = datetime.now().strftime("%Y-%m-%d")
    if date is None:
        date = today_str

    clean_plate, detected_color = extract_plate_color(plate)
    final_color = plate_color or detected_color
    if should_omit_color_suffix(clean_plate):
        final_color = None
    elif not final_color and re.search(r"\d{5}$", clean_plate):
        final_color = "T"
    biendk_id = f"{clean_plate}{final_color}" if final_color else clean_plate

    has_failed_today = False
    lan_kd = 1
    if os.path.exists(db_path):
        conn = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True, timeout=5.0)
        try:
            conn.row_factory = sqlite3.Row
            cur = conn.cursor()
            cur.execute("PRAGMA table_info(inspections)")
            cols = {r["name"] for r in cur.fetchall()}
            lankd_col = ", lankd" if "lankd" in cols else ""
            rows = []
            if cols:  # bảng inspections có thể chưa tồn tại (DB mới) -> không truy vấn để tránh lỗi 500
                cur.execute(
                    f"SELECT ketluan{lankd_col} FROM inspections WHERE (biendk_id = ? OR biendk_id LIKE ?) AND ngaykd = ?",
                    (biendk_id, f"{clean_plate}%", date),
                )
                rows = cur.fetchall()
            for r in rows:
                if r["ketluan"] == 1:
                    has_failed_today = True
                if "lankd" in r.keys() and r["lankd"] and r["lankd"] > lan_kd:
                    lan_kd = int(r["lankd"])
        finally:
            conn.close()

    l1_photos = _check_photos_taken(clean_plate, final_color, config, date=date, lan_kd=1)
    has_l1 = len(l1_photos) > 0
    suggest_lan_2 = (lan_kd >= 2) or has_failed_today

    return {
        "plate": clean_plate,
        "plate_color": final_color,
        "lan_kd": lan_kd,
        "suggest_lan_2": suggest_lan_2,
        "has_failed_today": has_failed_today,
        "has_l1_photos": has_l1,
        "photos_taken_l1": l1_photos,
    }


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

    conn = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True, timeout=5.0)
    results = []
    try:
        conn.row_factory = sqlite3.Row
        cur = conn.cursor()

        cur.execute("PRAGMA table_info(inspections)")
        col_names = {r["name"] for r in cur.fetchall()}
        has_sotem = "sotem" in col_names
        has_lankd = "lankd" in col_names

        failed_biendk_ids = set()
        if col_names:  # bảng inspections có thể chưa tồn tại (DB mới) -> bỏ qua thay vì lỗi 500
            cur.execute("SELECT DISTINCT biendk_id FROM inspections WHERE ngaykd = ? AND ketluan = 1", (date,))
            for fr in cur.fetchall():
                bid = fr[0]
                if bid:
                    failed_biendk_ids.add(bid)
                    failed_biendk_ids.add(extract_plate_color(bid)[0])

        is_today = (date == today_str)
        is_production_db = os.path.normpath(db_path).endswith("ptcgdb.db")
        waiting_raw = (get_waiting_vehicles_from_access(db_path) or []) if (is_today and is_production_db) else []

        seen_tickets = set()

        def _resolve_vehicle(sp, biendk_id, ketluan, giokd, raw_lankd, plate_display, brand, vtype, owner, sotem_val=""):
            if sp in seen_tickets:
                return None
            if filter_waiting and ketluan == 1:
                return None

            try:
                lan_kd_val = int(raw_lankd) if raw_lankd is not None else 1
            except (ValueError, TypeError):
                lan_kd_val = 1
            if lan_kd_val <= 0:
                lan_kd_val = 1

            plate_num, plate_color = extract_plate_color(biendk_id)
            is_failed_earlier = (biendk_id in failed_biendk_ids) or (plate_num in failed_biendk_ids)
            suggest_lan_2 = (lan_kd_val >= 2) or is_failed_earlier

            if lan_kd_val >= 2:
                photos_taken = _check_photos_taken(plate_num, plate_color, config, date=date, lan_kd=lan_kd_val)
            else:
                photos_taken = (
                    suggest_lan_2 and _check_photos_taken(plate_num, plate_color, config, date=date, lan_kd=2)
                ) or _check_photos_taken(plate_num, plate_color, config, date=date, lan_kd=1)

            has_both_45 = ("rear_45" in photos_taken) and ("front_45" in photos_taken)
            is_completed = bool(
                sotem_val
                or has_both_45
                or (ketluan == 0)
                or (ketluan == 1 and not suggest_lan_2 and lan_kd_val < 2)
            )

            seen_tickets.add(sp)
            m = re.match(r"^(\d+)", sp.strip())
            ticket_int = int(m.group(1)) if m else 999999

            return {
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
                "sotem": sotem_val,
                "photos_taken": photos_taken,
                "lan_kd": lan_kd_val,
                "suggest_lan_2": suggest_lan_2,
                "is_completed": is_completed,
            }

        # Đọc trước thông tin xe cho cả danh sách chờ (tránh N+1); thiếu bảng vehicles -> dùng dữ liệu Access
        vehicle_map: dict = {}
        waiting_ids = list({r.get("BienDK_ID") for r in waiting_raw if r.get("BienDK_ID")})
        if waiting_ids:
            try:
                vehicle_map = _fetch_vehicle_rows(cur, waiting_ids)
            except sqlite3.OperationalError as e:
                logger.warning("Không đọc được bảng vehicles (%s); dùng dữ liệu từ Access", e)

        if waiting_raw:
            for r in waiting_raw:
                sp = r.get("SoPhieuKD") or ""
                biendk_id = r.get("BienDK_ID") or ""
                v_row = vehicle_map.get(biendk_id)
                if v_row:
                    plate_display = v_row["biendk"] or r.get("BienDK") or biendk_id
                    brand = v_row["nhanhieu"] or ""
                    vtype = v_row["tenloaipt"] or ""
                    owner = v_row["chupt"] or ""
                else:
                    plate_display = r.get("BienDK") or biendk_id
                    brand = decode_d32(r.get("NhanHieu") or "")
                    vtype = decode_d32(r.get("TenLoaiPT") or "")
                    owner = decode_d32(r.get("ChuPT") or "")

                veh_dict = _resolve_vehicle(
                    sp, biendk_id, r.get("KetLuan"), r.get("GioKD") or "", r.get("LanKD") or 1,
                    plate_display, brand, vtype, owner
                )
                if veh_dict:
                    results.append(veh_dict)

        if col_names and (not waiting_raw or not filter_waiting):
            where_clause = "WHERE i.ngaykd = ?"
            if filter_waiting:
                where_clause += " AND (i.ketluan != 1 OR i.ketluan IS NULL)"
                if has_sotem:
                    where_clause += " AND (i.sotem IS NULL OR trim(i.sotem) = '')"

            sotem_select = "i.sotem" if has_sotem else "'' AS sotem"
            lankd_select = "i.lankd" if has_lankd else "1 AS lankd"
            cur.execute(
                f"""
                SELECT i.sophieu, v.biendk, v.biendk_clean, v.chupt, v.nhanhieu, v.tenloaipt,
                       i.ngaykd, i.giokd, i.ketluan, {sotem_select}, {lankd_select}, i.biendk_id
                FROM inspections i
                JOIN vehicles v ON i.biendk_id = v.biendk_id
                {where_clause}
                """,
                (date,),
            )
            for row in cur.fetchall():
                veh_dict = _resolve_vehicle(
                    row["sophieu"] or "", row["biendk_id"] or row["biendk_clean"], row["ketluan"], row["giokd"],
                    row["lankd"] if has_lankd else 1, row["biendk"], row["nhanhieu"], row["tenloaipt"], row["chupt"],
                    (row["sotem"] or "").strip() if has_sotem else ""
                )
                if veh_dict:
                    results.append(veh_dict)
    finally:
        conn.close()

    results.sort(
        key=lambda x: (x["ticket_int"], x.get("time") or ""),
        reverse=not filter_waiting,
    )
    return results
