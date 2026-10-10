import re
ma_path = "D:/inspection-camera/android/app/src/main/java/com/ttdk1507d/inspectioncamera/firebase/FirebaseManager.kt"
with open(ma_path, "r", encoding="utf-8") as f:
    content = f.read()

# Replace the > 7MB check
old_pattern = r"""            var bytes = photoFile\.readBytes\(\)
            if \(bytes\.size > 7 \* 1024 \* 1024\) \{
                Log\.w\(TAG, "File ảnh quá lớn \(\$\{bytes\.size\} bytes > 7MB\)\. Đang nén lại trước khi upload\.\.\."\)
                val bmp = BitmapFactory\.decodeByteArray\(bytes, 0, bytes\.size\)
                val baos = ByteArrayOutputStream\(\)
                bmp\.compress\(Bitmap\.CompressFormat\.JPEG, 70, baos\)
                bytes = baos\.toByteArray\(\)
                bmp\.recycle\(\)
                if \(bytes\.size > 7 \* 1024 \* 1024\) \{
                    Log\.e\(TAG, "Ảnh sau khi nén vẫn > 7MB, hủy upload Firebase\."\)
                    if \(continuation\.isActive\) continuation\.resume\(false\)
                    return@suspendCancellableCoroutine
                \}
            \}"""

new_code = """            val fileSize = photoFile.length()
            var bytes: ByteArray
            if (fileSize > 500 * 1024) { // 500KB threshold to prevent Firebase Base64 limits
                Log.w(TAG, "File ảnh > 500KB (${fileSize} bytes). Nén lại (inSampleSize) trước khi upload dự phòng...")
                val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(photoFile.absolutePath, options)
                
                var sampleSize = 1
                val maxDim = 1280 // Max dimension for fallback sync to keep Base64 small
                while ((options.outWidth / sampleSize) > maxDim || (options.outHeight / sampleSize) > maxDim) {
                    sampleSize *= 2
                }
                
                options.inJustDecodeBounds = false
                options.inSampleSize = sampleSize
                val bmp = android.graphics.BitmapFactory.decodeFile(photoFile.absolutePath, options)
                
                if (bmp != null) {
                    val baos = java.io.ByteArrayOutputStream()
                    bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, baos)
                    bytes = baos.toByteArray()
                    bmp.recycle()
                } else {
                    bytes = photoFile.readBytes()
                }
            } else {
                bytes = photoFile.readBytes()
            }"""

content = re.sub(old_pattern, new_code, content)
with open(ma_path, "w", encoding="utf-8") as f:
    f.write(content)
print("Firebase OOM and Base64 size patched")
