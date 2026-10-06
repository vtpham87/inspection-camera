package com.ttdk1507d.inspectioncamera.util

object PlateUtil {
    private val VALID_PLATE = Regex("^[A-Z0-9]+$")

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
     * Tách biển số thành 3 phần: Biển thuần (basePlate), Màu biển (T/V/X hoặc null), Lần KD (1, 2, ...).
     * Loại bỏ triệt để việc lặp đuôi (ví dụ 15A12345TL2, 15A12345TL2TL2, 15A12345T, 15A12345TT...)
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
        val isOld = !Regex("\\d{5}$").containsMatchIn(s)
        val finalColor = if (isOld) null else color
        return PlateInfo(s, finalColor, lan)
    }

    fun isOldPlate(plate: String): Boolean {
        val parsed = parsePlate(plate)
        return !Regex("\\d{5}$").containsMatchIn(parsed.basePlate)
    }

    /**
     * Chuẩn hóa toàn diện biển số, màu biển và lần KĐ từ input thô và intent color.
     */
    fun resolveFullPlate(rawPlate: String, intentColor: String? = null): PlateInfo {
        val parsed = parsePlate(rawPlate)
        val isOld = isOldPlate(parsed.basePlate)
        var color: String? = if (isOld) {
            null
        } else {
            intentColor ?: parsed.color
        }
        if (!isOld && color == null && Regex("\\d{5}$").containsMatchIn(parsed.basePlate)) {
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
     * Biển cũ (4 số hoặc không có 5 số ở đuôi) mặc định không thêm t/v/x.
     */
    fun formatCompactPlate(rawPlate: String, rawColor: String?, lanKd: Int = 1): String {
        val parsed = parsePlate(rawPlate)
        val base = parsed.basePlate
        val isOld = isOldPlate(base)
        var color = if (isOld) {
            null // Biển cũ mặc định không thêm t/v/x
        } else {
            (rawColor?.uppercase() ?: parsed.color)?.trim()
        }
        if (!isOld && color.isNullOrEmpty() && Regex("\\d{5}$").containsMatchIn(base)) {
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
