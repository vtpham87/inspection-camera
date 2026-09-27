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
}
