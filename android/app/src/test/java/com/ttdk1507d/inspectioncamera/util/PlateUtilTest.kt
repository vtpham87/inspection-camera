package com.ttdk1507d.inspectioncamera.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PlateUtilTest {

    @Test
    fun testNormalizeStandardPlates() {
        assertEquals("15A12345", PlateUtil.normalize("15A-123.45"))
        assertEquals("15A12345", PlateUtil.normalize("15a 12345"))
        assertEquals("15A12345", PlateUtil.normalize("15A.123.45"))
        assertEquals("15C12345T", PlateUtil.normalize("15C-123.45T"))
        assertEquals("15C12345V", PlateUtil.normalize("15c-12345v"))
        assertEquals("15C12345X", PlateUtil.normalize("15c-123.45x"))
    }

    @Test
    fun testNormalizeInvalidPlates() {
        assertThrows(IllegalArgumentException::class.java) {
            PlateUtil.normalize("")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlateUtil.normalize("   ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlateUtil.normalize("15A@123")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlateUtil.normalize("!#$")
        }
    }

    @Test
    fun testExtractColorSuffix() {
        val (plateT, colorT) = PlateUtil.extractColor("15A12345T")
        assertEquals("15A12345", plateT)
        assertEquals("T", colorT)

        val (plateV, colorV) = PlateUtil.extractColor("15A12345V")
        assertEquals("15A12345", plateV)
        assertEquals("V", colorV)

        val (plateX, colorX) = PlateUtil.extractColor("15A12345X")
        assertEquals("15A12345", plateX)
        assertEquals("X", colorX)
    }

    @Test
    fun testExtractColorNoSuffix() {
        val (plate, color) = PlateUtil.extractColor("15A12345")
        assertEquals("15A12345", plate)
        assertNull(color)
    }

    @Test
    fun testExtractColorOtherEndings() {
        val (plate, color) = PlateUtil.extractColor("15A12345A")
        assertEquals("15A12345A", plate)
        assertNull(color)
    }

    @Test
    fun testFormatCompactPlate() {
        assertEquals("15A12345T", PlateUtil.formatCompactPlate("15A12345", "T"))
        assertEquals("15A12345T", PlateUtil.formatCompactPlate("15A12345T", null))
        assertEquals("15A12345T", PlateUtil.formatCompactPlate("15A12345", null))
        assertEquals("15C12345V", PlateUtil.formatCompactPlate("15C12345", "V"))
        assertEquals("15A00123X", PlateUtil.formatCompactPlate("15A00123", "X"))
        assertEquals("11K2639", PlateUtil.formatCompactPlate("11K2639", null))
        assertEquals("15A12345TL2", PlateUtil.formatCompactPlate("15A12345", "T", lanKd = 2))
    }

    @Test
    fun testPlateWithLSeries() {
        val parsed1 = PlateUtil.parsePlate("16L-3565")
        assertEquals("16L3565", parsed1.basePlate)
        assertNull(parsed1.color)
        assertEquals(1, parsed1.lanKd)

        val parsed2 = PlateUtil.parsePlate("16L3565")
        assertEquals("16L3565", parsed2.basePlate)
        assertNull(parsed2.color)
        assertEquals(1, parsed2.lanKd)

        val parsedL2 = PlateUtil.parsePlate("16L-3565L2")
        assertEquals("16L3565", parsedL2.basePlate)
        assertNull(parsedL2.color)
        assertEquals(2, parsedL2.lanKd)

        assertEquals("16L3565", PlateUtil.formatCompactPlate("16L-3565", null))
        assertEquals("16L3565", PlateUtil.formatCompactPlate("16L3565", null))
        assertEquals("16L3565L2", PlateUtil.formatCompactPlate("16L-3565", null, lanKd = 2))
        assertEquals("16L3565L2", PlateUtil.formatCompactPlate("16L3565L2", null, lanKd = 2))
    }
}
