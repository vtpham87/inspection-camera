import re
ma_path = "D:/inspection-camera/android/app/src/main/java/com/ttdk1507d/inspectioncamera/worker/PendingUploadWorker.kt"
with open(ma_path, "r", encoding="utf-8") as f:
    content = f.read()

old_code = """                if (!imgFile.exists() || imgFile.length() == 0L) {
                    // Invalid/corrupted entry, clean up
                    metaFile.delete()
                    imgFile.delete()
                    return@async
                }"""
new_code = """                if (!imgFile.exists()) {
                    metaFile.delete()
                    return@async
                }
                if (imgFile.length() == 0L) {
                    // File có thể đang được ghi xuống đĩa, bỏ qua để Worker thử lại ở lần sau
                    anyFailed = true
                    return@async
                }"""
content = content.replace(old_code, new_code)
with open(ma_path, "w", encoding="utf-8") as f:
    f.write(content)
