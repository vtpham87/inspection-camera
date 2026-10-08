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


def sync_config_to_firebase(config: Optional[Any] = None) -> bool:
    """Đồng bộ cấu hình từ PC lên Firebase RTDB để các thiết bị điện thoại nhận được"""
    try:
        if config is None:
            config = load_config(CONFIG_PATH)
        config.recalculate_paths()

        payload = {
            "photo_save_dir": config.photo_save_dir,
            "passenger_path": config.passenger_path,
            "new_vehicle_path": config.new_vehicle_path,
            "sync_new_vehicle_45": config.sync_new_vehicle_45,
            "server_port": config.server_port,
            "vehicle_list_enabled": config.vehicle_list_enabled,
            "auto_start_with_windows": getattr(config, "auto_start_with_windows", True),
            "jpeg_quality": config.jpeg_quality,
            "photo_resolution": config.photo_resolution,
            "plate_color_suffix": config.plate_color_suffix,
            "timestamp": {
                "enabled": config.timestamp.enabled,
                "format": config.timestamp.format,
                "font_size": config.timestamp.font_size,
                "font_color": config.timestamp.font_color,
                "font_bold": config.timestamp.font_bold,
                "font_stroke_enabled": config.timestamp.font_stroke_enabled,
                "font_stroke_color": config.timestamp.font_stroke_color,
                "font_stroke_width": config.timestamp.font_stroke_width,
                "background_color": config.timestamp.background_color,
                "position": config.timestamp.position,
            },
            "updated_at": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
            "source": "pc",
        }

        # Đẩy lên tất cả các node để điện thoại dù kết nối tới node nào cũng có cấu hình
        for node in FIREBASE_NODES:
            rtdb_request(node["url"], "config", method="PUT", data=payload)
        logger.info("Đã đồng bộ cấu hình PC lên tất cả các cụm Firebase")
        return True
    except Exception as e:
        logger.error(f"Lỗi khi đồng bộ cấu hình lên Firebase: {e}", exc_info=True)
        return False


def sync_config_from_firebase() -> bool:
    """Kiểm tra nếu điện thoại gửi yêu cầu cập nhật cấu hình (/config_update) lên Firebase"""
    try:
        updated = False
        config = None
        for node in FIREBASE_NODES:
            update_data = rtdb_request(node["url"], "config_update", method="GET")
            if update_data and isinstance(update_data, dict):
                logger.info(f"Nhận được cập nhật cấu hình từ điện thoại trên {node['name']}: {update_data}")
                if config is None:
                    config = load_config(CONFIG_PATH)

                # Cập nhật và kiểm tra tính hợp lệ của các trường cấu hình
                def is_safe_path(p: str) -> bool:
                    return isinstance(p, str) and bool(p.strip()) and ".." not in p

                if "photo_save_dir" in update_data and is_safe_path(update_data["photo_save_dir"]):
                    config.photo_save_dir = update_data["photo_save_dir"]
                if "passenger_path" in update_data and is_safe_path(update_data["passenger_path"]):
                    config.passenger_path = update_data["passenger_path"]
                if "new_vehicle_path" in update_data and is_safe_path(update_data["new_vehicle_path"]):
                    config.new_vehicle_path = update_data["new_vehicle_path"]
                if "sync_new_vehicle_45" in update_data:
                    config.sync_new_vehicle_45 = bool(update_data["sync_new_vehicle_45"])
                if "auto_start_with_windows" in update_data:
                    config.auto_start_with_windows = bool(update_data["auto_start_with_windows"])
                if "jpeg_quality" in update_data:
                    try:
                        q = int(update_data["jpeg_quality"])
                        if 10 <= q <= 100:
                            config.jpeg_quality = q
                    except (ValueError, TypeError):
                        pass
                if "photo_resolution" in update_data:
                    res_val = str(update_data["photo_resolution"]).lower()
                    if res_val in ("original", "4k", "1080p", "720p"):
                        config.photo_resolution = res_val

                # Cập nhật timestamp nếu có
                if "timestamp" in update_data and isinstance(update_data["timestamp"], dict):
                    ts_data = update_data["timestamp"]
                    if "enabled" in ts_data:
                        config.timestamp.enabled = bool(ts_data["enabled"])
                    if "format" in ts_data and isinstance(ts_data["format"], str) and len(ts_data["format"]) < 50:
                        config.timestamp.format = str(ts_data["format"])
                    if "position" in ts_data and ts_data["position"] in ("bottom_right", "bottom_left", "top_right", "top_left"):
                        config.timestamp.position = str(ts_data["position"])
                    if "font_size" in ts_data:
                        try:
                            fs = int(ts_data["font_size"])
                            if 10 <= fs <= 200:
                                config.timestamp.font_size = fs
                        except (ValueError, TypeError):
                            pass

                from config import save_config
                save_config(config, CONFIG_PATH)
                logger.info(f"Đã áp dụng cấu hình từ điện thoại vào {CONFIG_PATH}")
                updated = True

                # Xóa config_update trên tất cả các node
                for n in FIREBASE_NODES:
                    rtdb_request(n["url"], "config_update", method="DELETE")
                break

        if updated and config:
            sync_config_to_firebase(config)
            return True
        return False
    except Exception as e:
        logger.error(f"Lỗi khi đồng bộ cấu hình từ Firebase: {e}", exc_info=True)
        return False


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

                    # B-04: Giới hạn kích thước Base64 tránh tràn bộ nhớ server (max 20MB Base64 ~ 15MB JPEG)
                    if len(base64_str) > 20 * 1024 * 1024:
                        logger.error(f"[{node_name}] Ảnh {photo_id} ({plate}) vượt giới hạn 20MB Base64, từ chối tải")
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
    try:
        sync_config_to_firebase()
    except Exception as e:
        logger.error(f"Lỗi khởi tạo sync config: {e}")

    consecutive_errors = 0
    while True:
        try:
            sync_vehicles_to_firebase()
            sync_config_from_firebase()
            process_photo_inbox()
            consecutive_errors = 0
            sleep_time = interval_sec
        except Exception as e:
            consecutive_errors += 1
            # Exponential backoff từ interval_sec đến tối đa 60s
            sleep_time = min(interval_sec * (2 ** min(consecutive_errors, 5)), 60)
            logger.error(f"Lỗi trong vòng lặp sync ({consecutive_errors} lần liên tiếp): {e}. Thử lại sau {sleep_time}s.")
        time.sleep(sleep_time)


if __name__ == "__main__":
    run_sync_loop(interval_sec=5)
