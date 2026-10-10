import os
import re

ma_path = "D:/inspection-camera/backend/firebase_sync.py"
with open(ma_path, "r", encoding="utf-8") as f:
    content = f.read()

old_loop = """def run_sync_loop(interval_sec: int = 10):
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
        time.sleep(sleep_time)"""

new_loop = """import threading

def _run_task(task_func, interval_sec):
    consecutive_errors = 0
    while True:
        try:
            task_func()
            consecutive_errors = 0
            sleep_time = interval_sec
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

    threading.Thread(target=_run_task, args=(sync_vehicles_to_firebase, interval_sec), daemon=True).start()
    threading.Thread(target=_run_task, args=(sync_config_from_firebase, interval_sec), daemon=True).start()
    
    # Chạy inbox trên main thread để giữ process không bị exit
    _run_task(process_photo_inbox, interval_sec)"""

if "def run_sync_loop" in content:
    content = content.replace(old_loop, new_loop)
    with open(ma_path, "w", encoding="utf-8") as f:
        f.write(content)
    print("Backend loop patched")
else:
    print("Run sync loop not found")
