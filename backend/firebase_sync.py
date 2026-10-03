import base64
import json
import logging
import os
import sys
import time
import urllib.request
import urllib.error
from datetime import datetime
from typing import Any, Dict

PROJECT_DIR = os.path.dirname(os.path.abspath(__file__))
if PROJECT_DIR not in sys.path:
    sys.path.insert(0, PROJECT_DIR)

from config import load_config
from photo_handler import save_photo
from vehicle_service import get_vehicles_today

logger = logging.getLogger("FirebaseSync")
logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(name)s: %(message)s")

FIREBASE_RTDB_URL = "https://ttdk-1507d-default-rtdb.asia-southeast1.firebasedatabase.app"
CONFIG_PATH = os.environ.get("PHOTO_CONFIG_PATH") or os.path.join(PROJECT_DIR, "photo_config.json")
DB_PATH = os.environ.get("PTCGDB_PATH") or "C:\\PTCGDB_Online\\ptcgdb.db"


def rtdb_request(path: str, method: str = "GET", data: Any = None) -> Any:
    url = f"{FIREBASE_RTDB_URL}/{path.lstrip('/')}.json"
    headers = {"Content-Type": "application/json"}
    req_body = None
    if data is not None:
        req_body = json.dumps(data).encode("utf-8")

    req = urllib.request.Request(url, data=req_body, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            content = resp.read().decode("utf-8")
            if content and content != "null":
                return json.loads(content)
            return None
    except Exception as e:
        logger.error(f"Error calling Firebase RTDB {method} {url}: {e}")
        return None


def sync_vehicles_to_firebase():
    """Đẩy danh sách xe kiểm định hôm nay lên Firebase node /vehicles_today"""
    try:
        config = load_config(CONFIG_PATH)
        today = datetime.now().strftime("%Y-%m-%d")
        vehicles = get_vehicles_today(DB_PATH, today, config, filter_waiting=False)
        if vehicles is None:
            return

        # Firebase Realtime Database: Key bằng plate_clean
        vehicles_dict = {}
        for v in vehicles:
            plate_clean = v.get("plate_clean") or v.get("plate")
            if plate_clean:
                # Chuẩn hóa key (không chứa các ký tự cấm của Firebase: . $ # [ ] /)
                safe_key = plate_clean.replace(".", "").replace("/", "").replace("$", "").replace("#", "").replace("[", "").replace("]", "")
                vehicles_dict[safe_key] = v

        rtdb_request("vehicles_today", method="PUT", data=vehicles_dict)
        logger.info(f"Đã đồng bộ {len(vehicles_dict)} xe lên Firebase Realtime Database")
    except Exception as e:
        logger.error(f"Lỗi khi đồng bộ danh sách xe lên Firebase: {e}", exc_info=True)


def process_photo_inbox():
    """Tải và lưu trữ ảnh do KĐV chụp gửi vào /photo_inbox qua 4G"""
    try:
        config = load_config(CONFIG_PATH)
        inbox = rtdb_request("photo_inbox", method="GET")
        if not inbox or not isinstance(inbox, dict):
            return

        for photo_id, item in inbox.items():
            if not isinstance(item, dict):
                continue

            try:
                base64_str = item.get("image_base64")
                plate = item.get("plate")
                plate_color = item.get("plate_color")
                photo_type = item.get("photo_type")
                seq = item.get("seq")
                lan_kd = item.get("lan_kd") or 1

                if not base64_str or not plate or not photo_type:
                    logger.warning(f"Bỏ qua item inbox thiếu dữ liệu: {photo_id}")
                    rtdb_request(f"photo_inbox/{photo_id}", method="DELETE")
                    continue

                file_bytes = base64.b64decode(base64_str)
                res = save_photo(
                    file_bytes=file_bytes,
                    plate=plate,
                    plate_color=plate_color,
                    photo_type=photo_type,
                    seq=seq,
                    config=config,
                    lan_kd=lan_kd,
                )

                if res.get("ok"):
                    logger.info(f"Đã lưu ảnh từ Firebase inbox thành công: {plate} - {photo_type} -> {res.get('filename')}")
                    # Xóa khỏi inbox sau khi đã lưu trữ an toàn vào ổ Z:
                    rtdb_request(f"photo_inbox/{photo_id}", method="DELETE")
                else:
                    logger.error(f"Lỗi lưu ảnh {photo_id} ({plate}): {res.get('error')}")
            except Exception as e:
                logger.error(f"Lỗi xử lý photo {photo_id}: {e}", exc_info=True)
    except Exception as e:
        logger.error(f"Lỗi khi xử lý photo inbox: {e}", exc_info=True)


def run_sync_loop(interval_sec: int = 10):
    logger.info(f"Bắt đầu dịch vụ Firebase Sync (chu kỳ {interval_sec}s)...")
    while True:
        try:
            sync_vehicles_to_firebase()
            process_photo_inbox()
        except Exception as e:
            logger.error(f"Lỗi trong vòng lặp sync: {e}")
        time.sleep(interval_sec)


if __name__ == "__main__":
    run_sync_loop(interval_sec=5)
