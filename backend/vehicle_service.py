import os
import sqlite3
from datetime import datetime
from config import PhotoConfig
from photo_handler import extract_plate_color, resolve_save_path, build_filename

PHOTO_TYPES = ["rear_45", "front_45", "chassis", "passenger", "new_vehicle"]


def _check_photos_taken(plate: str, plate_color: str | None, config: PhotoConfig) -> list[str]:
    taken = []
    for pt in PHOTO_TYPES:
        try:
            save_dir = resolve_save_path(pt, plate, config)
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
    db_path: str, date: str | None, config: PhotoConfig
) -> list[dict] | None:
    if not config.vehicle_list_enabled:
        return None

    if not os.path.exists(db_path):
        return []

    if date is None:
        date = datetime.now().strftime("%Y-%m-%d")

    conn = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
    conn.row_factory = sqlite3.Row
    cur = conn.cursor()
    cur.execute(
        """
        SELECT v.biendk, v.biendk_clean, v.chupt, v.nhanhieu, v.tenloaipt,
               i.ngaykd, i.giokd, i.ketluan
        FROM inspections i
        JOIN vehicles v ON i.biendk_id = v.biendk_id
        WHERE i.ngaykd = ?
        ORDER BY i.giokd DESC
        """,
        (date,),
    )
    rows = cur.fetchall()
    conn.close()

    results = []
    for row in rows:
        plate_num, plate_color = extract_plate_color(row["biendk_clean"])
        photos_taken = _check_photos_taken(plate_num, plate_color, config)
        results.append({
            "plate": row["biendk"],
            "plate_clean": plate_num,
            "plate_color": plate_color,
            "vehicle_type": row["tenloaipt"],
            "brand": row["nhanhieu"],
            "owner": row["chupt"],
            "time": row["giokd"],
            "result": row["ketluan"],
            "photos_taken": photos_taken,
        })
    return results
