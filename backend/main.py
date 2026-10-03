import os
import sys
from datetime import datetime
from fastapi import FastAPI, File, Form, UploadFile, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel

PROJECT_DIR = os.path.dirname(os.path.abspath(__file__))
if PROJECT_DIR not in sys.path:
    sys.path.insert(0, PROJECT_DIR)

from config import load_config, save_config, PhotoConfig
from photo_handler import (
    save_photo,
    normalize_plate,
    extract_plate_color,
    build_filename,
    resolve_save_path,
    VALID_PHOTO_TYPES,
)
from vehicle_service import get_vehicles_today, check_plate_status

CONFIG_PATH = os.environ.get("PHOTO_CONFIG_PATH") or os.path.join(PROJECT_DIR, "photo_config.json")
DB_PATH = os.environ.get("PTCGDB_PATH") or "C:\\PTCGDB_Online\\ptcgdb.db"


def get_config_path() -> str:
    return os.environ.get("PHOTO_CONFIG_PATH") or CONFIG_PATH


def get_db_path() -> str:
    return os.environ.get("PTCGDB_PATH") or DB_PATH


app = FastAPI(title="15-07D Photo Server", version="1.0.0")

@app.on_event("startup")
def start_firebase_sync():
    import threading
    from firebase_sync import run_sync_loop
    t = threading.Thread(target=run_sync_loop, args=(10,), daemon=True)
    t.start()

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.get("/api/health")
def health():
    return {
        "ok": True,
        "server": "15-07D Photo Server",
        "version": "1.0.0",
        "time": datetime.now().isoformat(),
    }


@app.post("/api/upload")
async def upload_photo(
    file: UploadFile = File(...),
    plate: str = Form(...),
    plate_color: str | None = Form(None),
    photo_type: str = Form(...),
    seq: int | None = Form(None),
    lan_kd: int | None = Form(1),
):
    config = load_config(get_config_path())
    file_bytes = await file.read()

    result = save_photo(file_bytes, plate, plate_color, photo_type, seq, config, lan_kd=lan_kd)
    if not result["ok"]:
        raise HTTPException(status_code=400, detail=result["error"])
    return result


class DeleteRequest(BaseModel):
    plate: str
    photo_type: str
    seq: int | None = None
    plate_color: str | None = None
    lan_kd: int | None = 1


@app.delete("/api/photos")
def delete_photo(req: DeleteRequest):
    if req.photo_type not in VALID_PHOTO_TYPES:
        raise HTTPException(status_code=400, detail="Loại ảnh không hợp lệ")
    config = load_config(get_config_path())
    try:
        plate_clean = normalize_plate(req.plate)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))

    plate_num, color = extract_plate_color(plate_clean)
    if req.plate_color:
        color = req.plate_color

    candidate_colors = [color] if color else [None, "T", "V", "X"]
    deleted_path = None
    matched_save_dir = None
    for c in candidate_colors:
        try:
            cur_save_dir = resolve_save_path(req.photo_type, plate_num, config, create_dir=False, plate_color=c)
            filename = build_filename(plate_num, c, req.photo_type, req.seq, config.plate_color_suffix, lan_kd=req.lan_kd)
            full_path = os.path.join(cur_save_dir, filename)
            if os.path.exists(full_path):
                os.remove(full_path)
                deleted_path = full_path
                matched_save_dir = cur_save_dir
                break
        except ValueError:
            pass

    if deleted_path:
        try:
            root_save_dir = os.path.realpath(config.photo_save_dir)
            if (
                matched_save_dir
                and os.path.realpath(matched_save_dir) != root_save_dir
                and os.path.exists(matched_save_dir)
                and os.path.isdir(matched_save_dir)
                and not os.listdir(matched_save_dir)
            ):
                os.rmdir(matched_save_dir)
        except Exception:
            pass
        return {"ok": True, "deleted": deleted_path}
    raise HTTPException(status_code=404, detail="File không tồn tại")


@app.get("/api/vehicles/today")
def vehicles_today(date: str | None = None, waiting_only: bool = True):
    config = load_config(get_config_path())
    if not config.vehicle_list_enabled:
        return []
    query_date = date or datetime.now().strftime("%Y-%m-%d")
    result = get_vehicles_today(get_db_path(), query_date, config, filter_waiting=waiting_only)
    if result is None:
        return []
    return result


@app.get("/api/vehicles/check-plate")
def check_plate(plate: str, plate_color: str | None = None, date: str | None = None):
    config = load_config(get_config_path())
    query_date = date or datetime.now().strftime("%Y-%m-%d")
    return check_plate_status(get_db_path(), plate, plate_color, config, date=query_date)



@app.get("/api/config")
def get_config():
    return load_config(get_config_path()).model_dump()


@app.post("/api/config")
def post_config(config: PhotoConfig):
    save_config(config, get_config_path())
    return {"ok": True, "message": "Đã lưu cấu hình"}


if __name__ == "__main__":
    import uvicorn
    config = load_config(get_config_path())
    uvicorn.run(app, host="0.0.0.0", port=config.server_port, log_level="info")
