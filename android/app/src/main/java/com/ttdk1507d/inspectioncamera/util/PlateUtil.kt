package com.ttdk1507d.inspectioncamera.util

object PlateUtil {
    private val VALID_PLATE = Regex("^[A-Z0-9]+$")

    fun normalize(raw: String): String {
        val cleaned = raw.replace(Regex("[.\\-\\s]"), "").uppercase()
        require(cleaned.isNotEmpty() && VALID_PLATE.matches(cleaned)) {
            "Biển số không hợp lệ"
        }
        return cleaned
    }

    fun extractColor(cleanPlate: String): Pair<String, String?> {
        if (cleanPlate.isNotEmpty() && cleanPlate.last() in listOf('T', 'V', 'X')) {
            return cleanPlate.dropLast(1) to cleanPlate.last().toString()
        }
        return cleanPlate to null
    }

    /**
     * Gộp biển + màu biển ngắn gọn: ví dụ 15A12345T, 15C12345V, 11K2639
     */
    fun formatCompactPlate(rawPlate: String, rawColor: String?): String {
        val clean = rawPlate.replace(Regex("[.\\-\\s]"), "").uppercase()
        val (base, detectedColor) = extractColor(clean)
        val color = (rawColor?.uppercase() ?: detectedColor)?.trim()
        return if (!color.isNullOrEmpty() && color in listOf("T", "V", "X")) {
            "$base$color"
        } else {
            base
        }
    }
}
