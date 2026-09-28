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
from vehicle_service import get_vehicles_today

CONFIG_PATH = os.environ.get("PHOTO_CONFIG_PATH") or os.path.join(PROJECT_DIR, "photo_config.json")
DB_PATH = os.environ.get("PTCGDB_PATH") or "C:\\PTCGDB_Online\\ptcgdb.db"


def get_config_path() -> str:
    return os.environ.get("PHOTO_CONFIG_PATH") or CONFIG_PATH


def get_db_path() -> str:
    return os.environ.get("PTCGDB_PATH") or DB_PATH


app = FastAPI(title="15-07D Photo Server", version="1.0.0")

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
):
    config = load_config(get_config_path())
    file_bytes = await file.read()

    result = save_photo(file_bytes, plate, plate_color, photo_type, seq, config)
    if not result["ok"]:
        raise HTTPException(status_code=400, detail=result["error"])
    return result


class DeleteRequest(BaseModel):
    plate: str
    photo_type: str
    seq: int | None = None
    plate_color: str | None = None


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
    save_dir = resolve_save_path(req.photo_type, plate_num, config, create_dir=False)

    deleted_path = None
    for c in candidate_colors:
        try:
            filename = build_filename(plate_num, c, req.photo_type, req.seq, config.plate_color_suffix)
            full_path = os.path.join(save_dir, filename)
            if os.path.exists(full_path):
                os.remove(full_path)
                deleted_path = full_path
                break
        except ValueError:
            pass

    if deleted_path:
        try:
            if os.path.exists(save_dir) and os.path.isdir(save_dir) and not os.listdir(save_dir):
                os.rmdir(save_dir)
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
