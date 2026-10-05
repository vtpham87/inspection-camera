import base64
import json
import logging
import os
import sys
import time
import urllib.request
import urllib.error
from datetime import datetime
from typing import Any, Dict, List, Optional

PROJECT_DIR = os.path.dirname(os.path.abspath(__file__))
if PROJECT_DIR not in sys.path:
    sys.path.insert(0, PROJECT_DIR)

from config import load_config
from photo_handler import save_photo
from vehicle_service import get_vehicles_today

logger = logging.getLogger("FirebaseSync")
logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(name)s: %(message)s")

# 4 Firebase Nodes (3 chính xoay vòng theo thứ + 1 dự phòng failover)
FIREBASE_NODES = [
    {
        "id": "node1",
        "name": "Node 1 (T2, T5)",
        "url": "https://ttdk-1507d-default-rtdb.asia-southeast1.firebasedatabase.app",
        "days": [0, 3],  # Thứ 2, Thứ 5
    },
    {
        "id": "node2",
        "name": "Node 2 (T3, T6)",
        "url": "https://ttdk-1507d-p2-default-rtdb.asia-southeast1.firebasedatabase.app",
        "days": [1, 4],  # Thứ 3, Thứ 6
    },
    {
        "id": "node3",
        "name": "Node 3 (T4, T7)",
        "url": "https://ttdk-1507d-p3-default-rtdb.asia-southeast1.firebasedatabase.app",
        "days": [2, 5],  # Thứ 4, Thứ 7
    },
    {
        "id": "backup",
        "name": "Node Dự phòng (Failover)",
        "url": "https://ttdk-1507d-bk-default-rtdb.asia-southeast1.firebasedatabase.app",
        "days": [],  # Backup
    },
]

CONFIG_PATH = os.environ.get("PHOTO_CONFIG_PATH") or os.path.join(PROJECT_DIR, "photo_config.json")
DB_PATH = os.environ.get("PTCGDB_PATH") or "C:\\PTCGDB_Online\\ptcgdb.db"


def get_active_nodes_for_today() -> List[Dict[str, Any]]:
    """Trả về node chính của ngày hôm nay + node dự phòng"""
    weekday = datetime.now().weekday()  # 0=T2, 1=T3, ..., 6=CN
    primary = None
    backup = None

    for node in FIREBASE_NODES:
        if node["id"] == "backup":
            backup = node
        elif weekday in node.get("days", []):
            primary = node

    # Nếu Chủ Nhật (weekday=6) hoặc không match, mặc định dùng Node 1 làm chính
    if not primary:
        primary = FIREBASE_NODES[0]

    res = [primary]
    if backup and backup not in res:
        res.append(backup)
    return res


def rtdb_request(base_url: str, path: str, method: str = "GET", data: Any = None) -> Any:
    url = f"{base_url.rstrip('/')}/{path.lstrip('/')}.json"
    headers = {"Content-Type": "application/json"}
    req_body = None
    if data is not None:
        req_body = json.dumps(data).encode("utf-8")

    req = urllib.request.Request(url, data=req_body, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            content = resp.read().decode("utf-8")
            if content and content != "null":
                return json.loads(content)
            return None
    except Exception as e:
        logger.error(f"Error calling Firebase RTDB {method} {url}: {e}")
        return None


def sync_vehicles_to_firebase():
    """Đẩy danh sách xe hôm nay lên Firebase (Node chính của ngày + Node dự phòng)"""
    try:
        config = load_config(CONFIG_PATH)
        today = datetime.now().strftime("%Y-%m-%d")
        vehicles = get_vehicles_today(DB_PATH, today, config, filter_waiting=False)
        if vehicles is None:
            return

        vehicles_dict = {}
        for v in vehicles:
            plate_clean = v.get("plate_clean") or v.get("plate")
            if plate_clean:
                safe_key = plate_clean.replace(".", "").replace("/", "").replace("$", "").replace("#", "").replace("[", "").replace("]", "")
                vehicles_dict[safe_key] = v

        targets = get_active_nodes_for_today()
        for node in targets:
            rtdb_request(node["url"], "vehicles_today", method="PUT", data=vehicles_dict)
            logger.info(f"Đã đồng bộ {len(vehicles_dict)} xe lên {node['name']} ({node['url']})")
    except Exception as e:
        logger.error(f"Lỗi khi đồng bộ danh sách xe lên Firebase: {e}", exc_info=True)


def process_photo_inbox():
    """Tải và lưu trữ ảnh do KĐV chụp gửi vào /photo_inbox trên TẤT CẢ các cụm Firebase"""
    try:
        config = load_config(CONFIG_PATH)
        for node in FIREBASE_NODES:
            base_url = node["url"]
            node_name = node["name"]
            inbox = rtdb_request(base_url, "photo_inbox", method="GET")
            if not inbox or not isinstance(inbox, dict):
                continue

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
                        logger.warning(f"[{node_name}] Bỏ qua item inbox thiếu dữ liệu: {photo_id}")
                        rtdb_request(base_url, f"photo_inbox/{photo_id}", method="DELETE")
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
                        logger.info(f"[{node_name}] Đã lưu ảnh thành công: {plate} - {photo_type} -> {res.get('filename')}")
                        rtdb_request(base_url, f"photo_inbox/{photo_id}", method="DELETE")
                    else:
                        logger.error(f"[{node_name}] Lỗi lưu ảnh {photo_id} ({plate}): {res.get('error')}")
                except Exception as e:
                    logger.error(f"[{node_name}] Lỗi xử lý photo {photo_id}: {e}", exc_info=True)
    except Exception as e:
        logger.error(f"Lỗi khi xử lý photo inbox: {e}", exc_info=True)


def run_sync_loop(interval_sec: int = 10):
    logger.info(f"Bắt đầu dịch vụ Firebase Multi-Cluster Sync (chu kỳ {interval_sec}s)...")
    while True:
        try:
            sync_vehicles_to_firebase()
            process_photo_inbox()
        except Exception as e:
            logger.error(f"Lỗi trong vòng lặp sync: {e}")
        time.sleep(interval_sec)


if __name__ == "__main__":
    run_sync_loop(interval_sec=5)
