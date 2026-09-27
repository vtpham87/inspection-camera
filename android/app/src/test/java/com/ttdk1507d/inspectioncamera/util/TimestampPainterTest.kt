package com.ttdk1507d.inspectioncamera.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class TimestampPainterTest {

    @Test
    fun testFormatTimestampDefaultPattern() {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.YEAR, 2026)
            set(Calendar.MONTH, Calendar.SEPTEMBER)
            set(Calendar.DAY_OF_MONTH, 27)
            set(Calendar.HOUR_OF_DAY, 14)
            set(Calendar.MINUTE, 30)
            set(Calendar.SECOND, 15)
        }
        val date = calendar.time
        val formatted = TimestampPainter.formatTimestamp(date, "HH:mm:ss - dd/MM/yyyy")
        assertEquals("14:30:15 - 27/09/2026", formatted)
    }

    @Test
    fun testFormatTimestampCustomPattern() {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.YEAR, 2026)
            set(Calendar.MONTH, Calendar.SEPTEMBER)
            set(Calendar.DAY_OF_MONTH, 27)
            set(Calendar.HOUR_OF_DAY, 8)
            set(Calendar.MINUTE, 5)
            set(Calendar.SECOND, 9)
        }
        val date = calendar.time
        val formatted = TimestampPainter.formatTimestamp(date, "yyyy-MM-dd HH:mm:ss")
        assertEquals("2026-09-27 08:05:09", formatted)
    }

    @Test
    fun testCalculatePositionTopLeft() {
        val (left, top) = TimestampPainter.calculatePosition(
            position = "top_left",
            bitmapWidth = 1000f,
            bitmapHeight = 800f,
            boxWidth = 200f,
            boxHeight = 50f,
            margin = 20f
        )
        assertEquals(20f, left, 0.001f)
        assertEquals(20f, top, 0.001f)
    }

    @Test
    fun testCalculatePositionTopCenter() {
        val (left, top) = TimestampPainter.calculatePosition(
            position = "top_center",
            bitmapWidth = 1000f,
            bitmapHeight = 800f,
            boxWidth = 200f,
            boxHeight = 50f,
            margin = 20f
        )
        assertEquals(400f, left, 0.001f)
        assertEquals(20f, top, 0.001f)
    }

    @Test
    fun testCalculatePositionTopRight() {
        val (left, top) = TimestampPainter.calculatePosition(
            position = "top_right",
            bitmapWidth = 1000f,
            bitmapHeight = 800f,
            boxWidth = 200f,
            boxHeight = 50f,
            margin = 20f
        )
        assertEquals(780f, left, 0.001f)
        assertEquals(20f, top, 0.001f)
    }

    @Test
    fun testCalculatePositionBottomLeft() {
        val (left, top) = TimestampPainter.calculatePosition(
            position = "bottom_left",
            bitmapWidth = 1000f,
            bitmapHeight = 800f,
            boxWidth = 200f,
            boxHeight = 50f,
            margin = 20f
        )
        assertEquals(20f, left, 0.001f)
        assertEquals(730f, top, 0.001f)
    }

    @Test
    fun testCalculatePositionBottomCenter() {
        val (left, top) = TimestampPainter.calculatePosition(
            position = "bottom_center",
            bitmapWidth = 1000f,
            bitmapHeight = 800f,
            boxWidth = 200f,
            boxHeight = 50f,
            margin = 20f
        )
        assertEquals(400f, left, 0.001f)
        assertEquals(730f, top, 0.001f)
    }

    @Test
    fun testCalculatePositionBottomRightDefault() {
        val (left, top) = TimestampPainter.calculatePosition(
            position = "bottom_right",
            bitmapWidth = 1000f,
            bitmapHeight = 800f,
            boxWidth = 200f,
            boxHeight = 50f,
            margin = 20f
        )
        assertEquals(780f, left, 0.001f)
        assertEquals(730f, top, 0.001f)
    }

    @Test
    fun testCalculatePositionFallbackDefault() {
        val (left, top) = TimestampPainter.calculatePosition(
            position = "invalid_position",
            bitmapWidth = 1000f,
            bitmapHeight = 800f,
            boxWidth = 200f,
            boxHeight = 50f,
            margin = 20f
        )
        assertEquals(780f, left, 0.001f)
        assertEquals(730f, top, 0.001f)
    }

    @Test
    fun testCalculatePositionClamping() {
        // Box larger than bitmap dimension
        val (left, top) = TimestampPainter.calculatePosition(
            position = "bottom_right",
            bitmapWidth = 100f,
            bitmapHeight = 100f,
            boxWidth = 150f,
            boxHeight = 120f,
            margin = 20f
        )
        assertEquals(0f, left, 0.001f)
        assertEquals(0f, top, 0.001f)
    }
}
