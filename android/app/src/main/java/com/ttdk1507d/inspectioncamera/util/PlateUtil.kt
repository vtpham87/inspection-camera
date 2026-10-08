package com.ttdk1507d.inspectioncamera.util

object PlateUtil {
    private val VALID_PLATE = Regex("^[A-Z0-9]+$")
    private val NO_COLOR_SERIES = Regex("^\\d{2}(KT|LD|HC)")

    data class PlateInfo(
        val basePlate: String,
        val color: String?,
        val lanKd: Int
    )

    fun normalize(raw: String): String {
        val cleaned = raw.replace(Regex("[.\\-\\s]"), "").uppercase()
        require(cleaned.isNotEmpty() && VALID_PLATE.matches(cleaned)) {
            "Biển số không hợp lệ"
        }
        return cleaned
    }

    /**
     * Kiểm tra biển không dùng hậu tố màu (T/V/X):
     * 1. Sê-ri đặc biệt: KT, LD, HC (ví dụ 15KT, 15LD, 15HC)
     * 2. Biển cũ: không kết thúc bằng 5 chữ số
     */
    fun shouldOmitColorSuffix(plate: String): Boolean {
        val clean = plate.replace(Regex("[.\\-\\s]"), "").uppercase()
        if (NO_COLOR_SERIES.containsMatchIn(clean)) return true
        val parsed = parsePlate(clean)
        return !Regex("\\d{5}$").containsMatchIn(parsed.basePlate)
    }

    fun isOldPlate(plate: String): Boolean {
        return shouldOmitColorSuffix(plate)
    }

    /**
     * Tách biển số thành 3 phần: Biển thuần (basePlate), Màu biển (T/V/X hoặc null), Lần KD (1, 2, ...).
     * Loại bỏ triệt để việc lặp đuôi (ví dụ 15A12345TL2, 15A12345TL2TL2, 15A12345T, 15A12345TT...)
     * Các biển đặc biệt KT/LD/HC và biển cũ không gán màu.
     */
    fun parsePlate(raw: String): PlateInfo {
        val clean = raw.replace(Regex("[.\\-\\s]"), "").uppercase()
        var s = clean
        var color: String? = null
        var lan = 1

        while (true) {
            val lanMatch = Regex("([TVX])?L([1-9])$").find(s)
            if (lanMatch != null) {
                val prefix = s.substring(0, lanMatch.range.first)
                if (prefix.isNotEmpty() && prefix.last().isDigit()) {
                    if (lanMatch.groupValues[1].isNotEmpty()) {
                        color = lanMatch.groupValues[1]
                    }
                    val l = lanMatch.groupValues[2].toIntOrNull() ?: 1
                    if (l > 1) lan = l
                    s = prefix
                    continue
                }
            }
            val colorMatch = Regex("([TVX])+$").find(s)
            if (colorMatch != null) {
                val prefix = s.substring(0, colorMatch.range.first)
                if (prefix.isNotEmpty() && prefix.last().isDigit()) {
                    color = colorMatch.value.last().toString()
                    s = prefix
                    continue
                }
            }
            break
        }
        val omitColor = NO_COLOR_SERIES.containsMatchIn(s) || !Regex("\\d{5}$").containsMatchIn(s)
        val finalColor = if (omitColor) null else color
        return PlateInfo(s, finalColor, lan)
    }

    /**
     * Chuẩn hóa toàn diện biển số, màu biển và lần KĐ từ input thô và intent color.
     */
    fun resolveFullPlate(rawPlate: String, intentColor: String? = null): PlateInfo {
        val parsed = parsePlate(rawPlate)
        val omitColor = shouldOmitColorSuffix(parsed.basePlate)
        var color: String? = if (omitColor) {
            null
        } else {
            intentColor ?: parsed.color
        }
        if (!omitColor && color == null && Regex("\\d{5}$").containsMatchIn(parsed.basePlate)) {
            color = "T"
        }
        return PlateInfo(parsed.basePlate, color, parsed.lanKd)
    }

    fun extractColor(cleanPlate: String): Pair<String, String?> {
        val parsed = parsePlate(cleanPlate)
        return parsed.basePlate to parsed.color
    }

    /**
     * Gộp biển + màu biển ngắn gọn: ví dụ 15A12345T, 15C12345V, 11K2639, hoặc 15A12345TL2 khi lanKd = 2.
     * Biển KT, LD, HC hoặc biển cũ mặc định không thêm t/v/x.
     */
    fun formatCompactPlate(rawPlate: String, rawColor: String?, lanKd: Int = 1): String {
        val parsed = parsePlate(rawPlate)
        val base = parsed.basePlate
        val omitColor = shouldOmitColorSuffix(base)
        var color = if (omitColor) {
            null // Biển KT/LD/HC hoặc biển cũ mặc định không thêm t/v/x
        } else {
            (rawColor?.uppercase() ?: parsed.color)?.trim()
        }
        if (!omitColor && color.isNullOrEmpty() && Regex("\\d{5}$").containsMatchIn(base)) {
            color = "T"
        }
        val effectiveLan = if (lanKd > 1) lanKd else 1
        val baseWithColor = if (!color.isNullOrEmpty() && color in listOf("T", "V", "X")) {
            "$base$color"
        } else {
            base
        }
        return if (effectiveLan > 1) {
            "${baseWithColor}L$effectiveLan"
        } else {
            baseWithColor
        }
    }
}
