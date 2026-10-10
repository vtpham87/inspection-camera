package com.ttdk1507d.inspectioncamera.util

import android.content.Context
import android.util.Log
import com.ttdk1507d.inspectioncamera.firebase.FirebaseManager
import java.io.File
import java.util.Calendar
import java.util.Locale

object CacheManager {
    private const val TAG = "CacheManager"

    fun getCacheSizeBytes(context: Context): Long {
        var total = 0L
        try {
            context.cacheDir?.let { total += getFolderSize(it) }
            context.externalCacheDir?.let { total += getFolderSize(it) }
            val reviewDir = File(context.filesDir, "review")
            if (reviewDir.exists()) {
                total += getFolderSize(reviewDir)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi tính dung lượng cache: ${e.message}")
        }
        return total
    }

    fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        return when {
            mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
            kb >= 1.0 -> String.format(Locale.US, "%.1f KB", kb)
            else -> "$bytes B"
        }
    }

    private fun getFolderSize(folder: File): Long {
        if (!folder.exists()) return 0L
        var size = 0L
        val files = folder.listFiles() ?: return 0L
        for (f in files) {
            size += if (f.isDirectory) getFolderSize(f) else f.length()
        }
        return size
    }

    fun clearAllCache(context: Context): Long {
        val before = getCacheSizeBytes(context)
        try {
            // 1. Clear context.cacheDir
            deleteContents(context.cacheDir)

            // 2. Clear context.externalCacheDir
            context.externalCacheDir?.let { deleteContents(it) }

            // 3. Clear review directory
            val reviewDir = File(context.filesDir, "review")
            if (reviewDir.exists()) {
                reviewDir.deleteRecursively()
            }

            // 4. Force Firebase reconnect & refresh
            FirebaseManager.reconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi xóa cache: ${e.message}")
        }
        return before
    }

    fun autoCleanOldData(context: Context) {
        try {
            // 1. Tự động dọn dẹp các ảnh review cũ hơn ngày hôm nay
            val reviewDir = File(context.filesDir, "review")
            if (reviewDir.exists() && reviewDir.isDirectory) {
                cleanOldFiles(reviewDir)
            }

            // 2. Tự động xóa file tạm trong cacheDir cũ hơn 24 giờ
            val cacheDir = context.cacheDir
            if (cacheDir != null && cacheDir.exists()) {
                cleanOldCacheFiles(cacheDir, 24 * 60 * 60 * 1000L)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi auto clean: ${e.message}")
        }
    }

    private fun isToday(timeMillis: Long): Boolean {
        if (timeMillis <= 0) return false
        val calNow = Calendar.getInstance()
        val calTarget = Calendar.getInstance().apply { this.timeInMillis = timeMillis }
        return calNow.get(Calendar.YEAR) == calTarget.get(Calendar.YEAR) &&
               calNow.get(Calendar.DAY_OF_YEAR) == calTarget.get(Calendar.DAY_OF_YEAR)
    }

    private fun cleanOldFiles(folder: File) {
        val list = folder.listFiles() ?: return
        for (f in list) {
            if (f.isDirectory) {
                cleanOldFiles(f)
                // Xóa thư mục rỗng
                if (f.list()?.isEmpty() == true) {
                    f.delete()
                }
            } else if (f.isFile) {
                if (!isToday(f.lastModified())) {
                    f.delete()
                }
            }
        }
    }

    private fun cleanOldCacheFiles(folder: File, maxAgeMillis: Long) {
        val now = System.currentTimeMillis()
        val list = folder.listFiles() ?: return
        for (f in list) {
            if (f.isDirectory) {
                cleanOldCacheFiles(f, maxAgeMillis)
                if (f.list()?.isEmpty() == true) {
                    f.delete()
                }
            } else if (f.isFile) {
                if (now - f.lastModified() > maxAgeMillis) {
                    f.delete()
                }
            }
        }
    }

    private fun deleteContents(folder: File?) {
        if (folder == null || !folder.exists()) return
        val files = folder.listFiles() ?: return
        for (f in files) {
            if (f.isDirectory) {
                f.deleteRecursively()
            } else {
                f.delete()
            }
        }
    }
}
