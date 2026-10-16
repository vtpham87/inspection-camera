import base64
import concurrent.futures
import hashlib
import json
import logging
import os
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime
from typing import Any, Dict, List, Optional

PROJECT_DIR = os.path.dirname(os.path.abspath(__file__))
if PROJECT_DIR not in sys.path:
    sys.path.insert(0, PROJECT_DIR)

from config import load_config, save_config, PhotoConfig, CONFIG_PATH, DB_PATH, FIREBASE_AUTH, validate_save_path, firebase_disabled
from photo_handler import save_photo
from vehicle_service import get_vehicles_today

logger = logging.getLogger("FirebaseSync")

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


# Cho phép ghi đè danh sách cụm bằng env PTCGDB_FIREBASE_NODES (JSON: [{"id","name","url","days"}]) -> dev dùng cụm riêng
_nodes_override = os.environ.get("PTCGDB_FIREBASE_NODES")
if _nodes_override:
    try:
        _parsed = json.loads(_nodes_override)
        if isinstance(_parsed, list) and _parsed and all(isinstance(n, dict) and n.get("url") for n in _parsed):
            FIREBASE_NODES = [
                {"id": n.get("id", f"n{i}"), "name": n.get("name", f"Node {i}"), "url": n["url"], "days": n.get("days", [])}
                for i, n in enumerate(_parsed)
            ]
        else:
            logging.getLogger("FirebaseSync").error("PTCGDB_FIREBASE_NODES không hợp lệ, dùng danh sách mặc định")
    except ValueError as _e:
        logging.getLogger("FirebaseSync").error("PTCGDB_FIREBASE_NODES không phải JSON: %s", _e)


def get_active_nodes_for_today() -> List[Dict[str, Any]]:
    """Trả về toàn bộ các node để đảm bảo tính đồng nhất 100% trên toàn bộ các cụm Firebase"""
    return FIREBASE_NODES


def sync_config_to_firebase(config: Optional[Any] = None) -> bool:
    """Đồng bộ cấu hình từ PC lên Firebase RTDB để các thiết bị điện thoại nhận được"""
    if firebase_disabled():
        logger.warning("DISABLE_FIREBASE_SYNC bật: bỏ qua đẩy cấu hình lên Firebase")
        return False
    try:
        if config is None:
            config = load_config(CONFIG_PATH)
        config.recalculate_paths()

        payload = config.model_dump()
        payload["updated_at"] = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        payload["source"] = "pc"

        def _push_node(node) -> bool:
            try:
                _rtdb_call(node["url"], "config", method="PUT", data=payload)
                return True
            except Exception as e:
                logger.error(f"Không đẩy được cấu hình lên {node['name']}: {e}")
                return False

        with concurrent.futures.ThreadPoolExecutor(max_workers=len(FIREBASE_NODES)) as executor:
            ok_count = sum(1 for ok in executor.map(_push_node, FIREBASE_NODES) if ok)

        if ok_count == 0:
            logger.error("Không đồng bộ được cấu hình lên bất kỳ cụm Firebase nào")
            return False
        logger.info(f"Đã đồng bộ cấu hình PC lên {ok_count}/{len(FIREBASE_NODES)} cụm Firebase")
        return True
    except Exception as e:
        logger.error(f"Lỗi khi đồng bộ cấu hình lên Firebase: {e}", exc_info=True)
        return False


# Các khoá không cho phép thay đổi từ xa (đổi cổng/đường dẫn nội bộ có thể làm hỏng dịch vụ)
_REMOTE_CONFIG_BLOCKED_KEYS = {"paths", "server_port"}
# Các khoá đường dẫn: cho phép đổi nhưng phải qua validate_save_path (ổ đĩa tuyệt đối, không "..", trong gốc cho phép)
_REMOTE_PATH_KEYS = {"photo_save_dir", "passenger_path", "new_vehicle_path"}


def _apply_remote_config(config: PhotoConfig, update_data: Dict[str, Any]) -> PhotoConfig:
    """Gộp cấu hình điện thoại gửi lên và kiểm tra hợp lệ bằng Pydantic (không dùng model_copy vì nó bỏ qua validate)."""
    merged = config.model_dump()
    for k, v in update_data.items():
        if k in _REMOTE_CONFIG_BLOCKED_KEYS or k not in PhotoConfig.model_fields:
            continue
        if k == "timestamp" and isinstance(v, dict):
            ts_valid = {tk: tv for tk, tv in v.items() if tk in config.timestamp.model_fields}
            merged["timestamp"] = {**merged["timestamp"], **ts_valid}
        elif k != "timestamp":
            if k in _REMOTE_PATH_KEYS:
                v = validate_save_path(v, k) if isinstance(v, str) else v
            merged[k] = v
    return PhotoConfig.model_validate(merged)


def sync_config_from_firebase() -> bool:
    """Kiểm tra nếu điện thoại gửi yêu cầu cập nhật cấu hình (/config_update) lên Firebase"""
    try:
        def _get_update(node):
            return node, rtdb_request(node["url"], "config_update", method="GET")

        with concurrent.futures.ThreadPoolExecutor(max_workers=len(FIREBASE_NODES)) as executor:
            results = list(executor.map(_get_update, FIREBASE_NODES))

        for node, update_data in results:
            if not (update_data and isinstance(update_data, dict)):
                continue
            logger.info(f"Nhận được cập nhật cấu hình từ điện thoại trên {node['name']}: {update_data}")
            try:
                config = _apply_remote_config(load_config(CONFIG_PATH), update_data)
            except Exception as e:
                logger.error(f"Cấu hình từ điện thoại không hợp lệ, bỏ qua: {e}")
                config = None

            # Xoá config_update trên tất cả các node dù hợp lệ hay không (tránh xử lý lặp vô hạn)
            def _delete_update(n):
                rtdb_request(n["url"], "config_update", method="DELETE")
            with concurrent.futures.ThreadPoolExecutor(max_workers=len(FIREBASE_NODES)) as executor:
                list(executor.map(_delete_update, FIREBASE_NODES))

            if config is None:
                return False
            save_config(config, CONFIG_PATH)
            logger.info(f"Đã áp dụng cấu hình từ điện thoại vào {CONFIG_PATH}")
            sync_config_to_firebase(config)
            return True
        return False
    except Exception as e:
        logger.error(f"Lỗi khi đồng bộ cấu hình từ Firebase: {e}", exc_info=True)
        return False


def _rtdb_call(base_url: str, path: str, method: str = "GET", data: Any = None, query: str = "") -> Any:
    """Gọi RTDB REST; ném exception khi lỗi mạng/HTTP."""
    if FIREBASE_AUTH:
        query += ("&" if "?" in query else "?") + "auth=" + urllib.parse.quote(FIREBASE_AUTH, safe="")
    url = f"{base_url.rstrip('/')}/{path.lstrip('/')}.json{query}"
    headers = {"Content-Type": "application/json"}
    req_body = None
    if data is not None:
        req_body = json.dumps(data).encode("utf-8")

    req = urllib.request.Request(url, data=req_body, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=10) as resp:
        content = resp.read().decode("utf-8")
        if content and content != "null":
            return json.loads(content)
        return None


def rtdb_request(base_url: str, path: str, method: str = "GET", data: Any = None) -> Any:
    try:
        return _rtdb_call(base_url, path, method, data)
    except Exception as e:
        logger.error(f"Error calling Firebase RTDB {method} {base_url}/{path}: {e}")
        return None


_LAST_VEHICLES_HASH: str = ""
_LAST_SYNC_TIME: float = 0.0


def sync_vehicles_to_firebase(force: bool = False):
    """Đẩy danh sách xe hôm nay lên Firebase (Node chính của ngày + Node dự phòng)"""
    global _LAST_VEHICLES_HASH, _LAST_SYNC_TIME
    if firebase_disabled():
        return
    try:
        if force:
            try:
                from vehicle_service import sync_waiting_vehicles_to_sqlite
                sync_waiting_vehicles_to_sqlite()
            except Exception as e:
                logger.warning(f"Lỗi ép buộc đồng bộ xe chờ vào SQLite: {e}")

        config = load_config(CONFIG_PATH)
        today = datetime.now().strftime("%Y-%m-%d")
        vehicles = get_vehicles_today(DB_PATH, today, config, filter_waiting=False)
        if vehicles is None:
            return

        from vehicle_service import get_access_status
        access_status = get_access_status()
        # Chốt chặn bảo vệ (Fail-safe / Circuit Breaker):
        # Nếu CSDL Access mất kết nối và chưa từng có bộ đệm xe chờ hợp lệ,
        # tuyệt đối không đẩy đè danh sách rỗng lên Firebase để tránh làm trắng màn hình xe chờ trên điện thoại.
        if not access_status["ready"] and not access_status["has_cache"]:
            logger.warning(
                "CSDL Access chưa sẵn sàng và chưa có bộ đệm xe chờ hợp lệ. "
                "Bỏ qua đồng bộ Firebase lần này để bảo toàn danh sách xe trên điện thoại."
            )
            return

        vehicles_dict = {}
        for v in vehicles:
            plate_clean = v.get("plate_clean") or v.get("plate")
            if plate_clean:
                safe_key = plate_clean.replace(".", "").replace("/", "").replace("$", "").replace("#", "").replace("[", "").replace("]", "")
                if safe_key not in vehicles_dict:
                    vehicles_dict[safe_key] = v
                else:
                    curr = vehicles_dict[safe_key]
                    v_key = (v.get("ticket_int") or 0, v.get("lan_kd") or 1)
                    curr_key = (curr.get("ticket_int") or 0, curr.get("lan_kd") or 1)
                    if v_key > curr_key:
                        vehicles_dict[safe_key] = v

        current_hash = hashlib.md5(json.dumps(vehicles_dict, sort_keys=True).encode("utf-8")).hexdigest()
        now = time.time()
        if not force and current_hash == _LAST_VEHICLES_HASH and (now - _LAST_SYNC_TIME) < 60.0:
            return

        waiting_count = sum(1 for v in vehicles_dict.values() if not v.get("is_completed"))
        targets = get_active_nodes_for_today()
        def _push_vehicles(node) -> bool:
            try:
                _rtdb_call(node["url"], "vehicles_today", method="PUT", data=vehicles_dict)
            except Exception as e:
                logger.error(f"Không đẩy được danh sách xe lên {node['name']}: {e}")
                return False
            logger.info(f"Đã đồng bộ {len(vehicles_dict)} xe ({waiting_count} xe đang chờ) lên {node['name']} ({node['url']})")
            return True

        with concurrent.futures.ThreadPoolExecutor(max_workers=len(targets)) as executor:
            ok_count = sum(1 for ok in executor.map(_push_vehicles, targets) if ok)

        if ok_count == 0:
            # Không cập nhật hash: chu kỳ sau sẽ thử lại thay vì tưởng đã đồng bộ
            logger.error("Không đồng bộ được danh sách xe lên bất kỳ cụm Firebase nào")
            return

        _LAST_VEHICLES_HASH = current_hash
        _LAST_SYNC_TIME = now
    except Exception as e:
        logger.error(f"Lỗi khi đồng bộ danh sách xe lên Firebase: {e}", exc_info=True)


_INBOX_MAX_ATTEMPTS = 5
_INBOX_FAILS: Dict[tuple, int] = {}


def _inbox_register_failure(base_url: str, node_name: str, photo_id: str, item: Any, reason: str) -> None:
    """Đếm lần lỗi của một ảnh trong inbox; quá ngưỡng thì chuyển sang photo_inbox_failed để không lặp vô hạn."""
    key = (base_url, photo_id)
    attempts = _INBOX_FAILS.get(key, 0) + 1
    if attempts < _INBOX_MAX_ATTEMPTS:
        _INBOX_FAILS[key] = attempts
        logger.error(f"[{node_name}] Lỗi xử lý ảnh {photo_id} (lần {attempts}/{_INBOX_MAX_ATTEMPTS}): {reason}")
        return
    _INBOX_FAILS.pop(key, None)
    logger.error(f"[{node_name}] Ảnh {photo_id} lỗi {_INBOX_MAX_ATTEMPTS} lần ({reason}), chuyển sang photo_inbox_failed")
    safe_id = urllib.parse.quote(photo_id, safe="")
    try:
        if isinstance(item, dict):
            _rtdb_call(base_url, f"photo_inbox_failed/{safe_id}", method="PUT", data=item)
        _rtdb_call(base_url, f"photo_inbox/{safe_id}", method="DELETE")
    except Exception as e:
        logger.error(f"[{node_name}] Không chuyển được ảnh lỗi {photo_id}: {e}")


def process_photo_inbox():
    """Tải và lưu trữ ảnh do KĐV chụp gửi vào /photo_inbox trên TẤT CẢ các cụm Firebase"""
    try:
        config = load_config(CONFIG_PATH)
        for node in FIREBASE_NODES:
            base_url = node["url"]
            node_name = node["name"]
            # Chỉ lấy danh sách khoá (shallow) rồi tải từng ảnh: tránh kéo toàn bộ base64 của mọi ảnh trong một request
            try:
                keys = _rtdb_call(base_url, "photo_inbox", query="?shallow=true")
            except Exception as e:
                logger.error(f"[{node_name}] Không đọc được photo_inbox: {e}")
                continue
            if not keys or not isinstance(keys, dict):
                continue

            for photo_id in list(keys.keys()):
                safe_id = urllib.parse.quote(photo_id, safe="")
                item = None
                try:
                    item = _rtdb_call(base_url, f"photo_inbox/{safe_id}")
                    if item is None:
                        continue  # đã bị xoá bởi tiến trình khác
                    if not isinstance(item, dict):
                        _rtdb_call(base_url, f"photo_inbox/{safe_id}", method="DELETE")
                        continue

                    base64_str = item.get("image_base64")
                    plate = item.get("plate")
                    plate_color = item.get("plate_color")
                    photo_type = item.get("photo_type")
                    seq = item.get("seq")
                    lan_kd = item.get("lan_kd") or 1
                    if item.get("degraded"):
                        logger.warning(
                            f"[{node_name}] Ảnh {photo_id} là bản đã nén khi gửi qua Firebase "
                            f"({item.get('original_size')} -> {item.get('file_size')} byte), không phải ảnh gốc"
                        )

                    if not base64_str or not plate or not photo_type:
                        logger.warning(f"[{node_name}] Bỏ qua item inbox thiếu dữ liệu: {photo_id}")
                        _rtdb_call(base_url, f"photo_inbox/{safe_id}", method="DELETE")
                        continue

                    # B-04: Giới hạn kích thước Base64 tránh tràn bộ nhớ server (max 20MB Base64 ~ 15MB JPEG)
                    if len(base64_str) > 20 * 1024 * 1024:
                        logger.error(f"[{node_name}] Ảnh {photo_id} ({plate}) vượt giới hạn 20MB Base64, từ chối tải")
                        _rtdb_call(base_url, f"photo_inbox/{safe_id}", method="DELETE")
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
                        _INBOX_FAILS.pop((base_url, photo_id), None)
                        logger.info(f"[{node_name}] Đã lưu ảnh thành công: {plate} - {photo_type} -> {res.get('filename')}")
                        _rtdb_call(base_url, f"photo_inbox/{safe_id}", method="DELETE")
                    else:
                        _inbox_register_failure(base_url, node_name, photo_id, item, str(res.get("error")))
                except OSError as e:
                    # Lỗi ghi đĩa/ổ mạng (vd Z: rớt) là tạm thời: KHÔNG tính vào số lần lỗi, giữ ảnh trong inbox
                    # và thử lại ở chu kỳ sau. Dừng cụm này để không dồn dập khi ổ đang hỏng.
                    logger.warning(f"[{node_name}] Lỗi ghi ảnh {photo_id} (tạm thời, sẽ thử lại): {e}")
                    break
                except Exception as e:
                    _inbox_register_failure(base_url, node_name, photo_id, item, str(e))
    except Exception as e:
        logger.error(f"Lỗi khi xử lý photo inbox: {e}", exc_info=True)


def _run_task(task_func, interval_sec, interval_fn=None):
    consecutive_errors = 0
    while True:
        try:
            result = task_func()
            if result is False:
                # Tác vụ báo lỗi bằng giá trị trả về (vd sync_waiting_vehicles_to_sqlite): cũng phải giãn chu kỳ,
                # nếu không sẽ gọi PowerShell/ODBC mỗi 5 giây khi Access hoặc ổ Z: đang hỏng.
                consecutive_errors += 1
                sleep_time = min(interval_sec * (2 ** min(consecutive_errors, 5)), 60)
            else:
                consecutive_errors = 0
                sleep_time = interval_fn() if interval_fn else interval_sec
        except Exception as e:
            consecutive_errors += 1
            sleep_time = min(interval_sec * (2 ** min(consecutive_errors, 5)), 60)
            logger.error(f"Lỗi vòng lặp {task_func.__name__} ({consecutive_errors} lần): {e}")
        time.sleep(sleep_time)

def run_sync_loop(interval_sec: int = 10):
    logger.info(f"Bắt đầu dịch vụ Firebase Multi-Cluster Sync độc lập (chu kỳ {interval_sec}s)...")
    try:
        sync_config_to_firebase()
    except Exception as e:
        logger.error(f"Lỗi khởi tạo sync config: {e}")

    from vehicle_service import sync_waiting_vehicles_to_sqlite, waiting_sync_interval
    try:
        sync_waiting_vehicles_to_sqlite()
    except Exception as e:
        logger.warning(f"Lỗi khởi tạo sync_waiting_vehicles_to_sqlite: {e}")

    threading.Thread(
        target=_run_task, args=(sync_waiting_vehicles_to_sqlite, 5, waiting_sync_interval), daemon=True
    ).start()
    threading.Thread(target=_run_task, args=(sync_vehicles_to_firebase, interval_sec), daemon=True).start()
    threading.Thread(target=_run_task, args=(sync_config_from_firebase, interval_sec), daemon=True).start()
    
    # Chạy inbox trên main thread để giữ process không bị exit
    _run_task(process_photo_inbox, interval_sec)


if __name__ == "__main__":
    run_sync_loop(interval_sec=5)
