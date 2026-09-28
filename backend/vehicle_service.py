import os
import re
import sqlite3
from datetime import datetime
from config import PhotoConfig
from photo_handler import extract_plate_color, resolve_save_path, build_filename

PHOTO_TYPES = ["rear_45", "front_45", "chassis", "passenger", "new_vehicle"]


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

    if date is None:
        date = datetime.now().strftime("%Y-%m-%d")

    conn = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
    try:
        conn.row_factory = sqlite3.Row
        cur = conn.cursor()

        cur.execute("PRAGMA table_info(inspections)")
        col_names = {r["name"] for r in cur.fetchall()}
        has_sotem = "sotem" in col_names

        where_clause = "WHERE i.ngaykd = ?"
        if filter_waiting:
            # Bỏ qua xe không đạt (ketluan=1) và xe đã xong (sotem đã cấp)
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
    finally:
        conn.close()

    results = []
    for row in rows:
        plate_num, plate_color = extract_plate_color(row["biendk_clean"])
        photos_taken = _check_photos_taken(plate_num, plate_color, config, date=date)

        sp = row["sophieu"] or ""
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

    # Sắp xếp thứ tự theo số phiếu tăng dần
    results.sort(key=lambda x: (x["ticket_int"], x.get("time") or ""))
    return results
