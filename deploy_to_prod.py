import os
import shutil
import time
import urllib.request
import json
import sys
import subprocess
from datetime import datetime

DEV_DIR = r"D:\inspection-camera\backend"
PROD_DIR = r"D:\inspection-camera-prod\backend"
PROD_ROOT = r"D:\inspection-camera-prod"

PYTHON_FILES = [
    "config.py",
    "firebase_sync.py",
    "main.py",
    "photo_handler.py",
    "vehicle_service.py",
    "requirements.txt",
]


def run_tests():
    print("=" * 60)
    print("BƯỚC 1: KIỂM THỬ TỰ ĐỘNG TRÊN BẢN DEV (PYTEST)")
    print("=" * 60)
    dev_pytest = os.path.join(DEV_DIR, "venv", "Scripts", "pytest.exe")
    if not os.path.exists(dev_pytest):
        dev_pytest = "pytest"

    cmd = [dev_pytest, os.path.join(DEV_DIR, "tests"), "-q"]
    print(f"Đang chạy kiểm thử: {' '.join(cmd)}...")
    res = subprocess.run(cmd, cwd=DEV_DIR)
    if res.returncode != 0:
        print("\n[LỖI] Các bài kiểm thử trên Dev KHÔNG ĐẠT! Hủy bỏ đồng bộ để bảo vệ Production.")
        sys.exit(1)
    print("[OK] Tất cả bài test trên Dev đã vượt qua thành công!\n")


def create_backup():
    print("=" * 60)
    print("BƯỚC 2: SAO LƯU TOÀN BỘ PRODUCTION TRƯỚC KHI CẬP NHẬT")
    print("=" * 60)
    ts = datetime.now().strftime("%Y%m%d_%H%M%S")
    backup_dir = f"D:\\inspection-camera-prod-backup-{ts}"
    print(f"Đang sao lưu từ {PROD_ROOT} sang {backup_dir}...")

    def ignore_patterns(folder, names):
        ignored = []
        if os.path.abspath(folder) == os.path.abspath(PROD_DIR):
            if "venv" in names:
                ignored.append("venv")
        return ignored

    shutil.copytree(PROD_ROOT, backup_dir, ignore=ignore_patterns)
    print(f"[OK] Đã tạo bản sao lưu an toàn tại: {backup_dir}\n")
    return backup_dir


def rollback(backup_dir):
    print("\n" + "!" * 60)
    print("CẢNH BÁO: PHÁT HIỆN SỰ CỐ! ĐANG TIẾN HÀNH ROLLBACK TỰ ĐỘNG...")
    print("!" * 60)
    backup_backend = os.path.join(backup_dir, "backend")
    if os.path.exists(backup_backend):
        for filename in PYTHON_FILES:
            src = os.path.join(backup_backend, filename)
            dst = os.path.join(PROD_DIR, filename)
            if os.path.exists(src):
                shutil.copy2(src, dst)
                print(f"  [ROLLBACK] Khôi phục {filename}")

        restart_vbs = os.path.join(PROD_DIR, "restart_background.vbs")
        os.system(f'cscript //nologo "{restart_vbs}"')
        print("[ROLLBACK] Đã kích hoạt lại bản Production cũ từ bản sao lưu!")
    print("!" * 60)


def deploy():
    # Kiểm tra xác nhận an toàn
    is_confirmed = "--confirm" in sys.argv
    if not is_confirmed:
        print("=" * 60)
        print("XÁC NHẬN BẢO VỆ PRODUCTION")
        print("=" * 60)
        print("Thao tác này sẽ đồng bộ mã nguồn backend từ DEV sang PRODUCTION.")
        print("Chỉ thực hiện khi có sự đồng ý của quản trị viên.")
        ans = input("Nhập 'dong y' để tiếp tục đồng bộ (hoặc bấm Enter để hủy): ").strip().lower()
        if ans not in ["dong y", "y", "yes"]:
            print("Đã hủy bỏ thao tác đồng bộ.")
            sys.exit(0)

    # Bước 1: Test dev
    run_tests()

    # Bước 2: Backup prod
    backup_dir = create_backup()

    # Bước 3: Copy code
    print("=" * 60)
    print("BƯỚC 3: ĐỒNG BỘ MÃ NGUỒN SANG PRODUCTION")
    print("=" * 60)
    for filename in PYTHON_FILES:
        src = os.path.join(DEV_DIR, filename)
        dst = os.path.join(PROD_DIR, filename)
        if os.path.exists(src):
            shutil.copy2(src, dst)
            print(f"  [OK] Đã chép {filename}")
        else:
            print(f"  [CẢNH BÁO] Không tìm thấy file nguồn: {filename}")

    # Giữ nguyên cấu hình photo_config.json của trạm (không bao giờ ghi đè nếu đã có)
    prod_config = os.path.join(PROD_DIR, "photo_config.json")
    if not os.path.exists(prod_config):
        shutil.copy2(os.path.join(DEV_DIR, "photo_config.json"), prod_config)
        print("  [OK] Khởi tạo photo_config.json mới cho Production")
    else:
        print("  [GIỮ NGUYÊN] Bảo toàn photo_config.json của trạm hiện tại")

    # Bước 4: Khởi động lại dịch vụ
    print("\n" + "=" * 60)
    print("BƯỚC 4: KHỞI ĐỘNG LẠI DỊCH VỤ PRODUCTION & KIỂM TRA SỨC KHỎE")
    print("=" * 60)
    restart_vbs = os.path.join(PROD_DIR, "restart_background.vbs")
    os.system(f'cscript //nologo "{restart_vbs}"')

    # Chờ và kiểm tra /api/health
    print("Đang kiểm tra phản hồi từ máy chủ Production (port 8095)...")
    time.sleep(2)
    healthy = False
    for attempt in range(1, 16):
        try:
            req = urllib.request.Request("http://127.0.0.1:8095/api/health")
            with urllib.request.urlopen(req, timeout=1.5) as resp:
                if resp.status == 200:
                    data = json.loads(resp.read().decode("utf-8"))
                    print(f"  [THÀNH CÔNG] Dịch vụ hoạt động bình thường: {data}")
                    healthy = True
                    break
        except Exception:
            time.sleep(1)

    if not healthy:
        print("  [LỖI] Máy chủ không phản hồi sau 15 giây!")
        rollback(backup_dir)
        sys.exit(1)

    print("\n" + "=" * 60)
    print("HOÀN TẤT: Cập nhật Production thành công và an toàn tuyệt đối!")
    print(f"Bản sao lưu trước cập nhật: {backup_dir}")
    print("=" * 60)


if __name__ == "__main__":
    deploy()
