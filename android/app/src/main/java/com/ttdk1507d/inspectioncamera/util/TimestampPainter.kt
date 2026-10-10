package com.ttdk1507d.inspectioncamera.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.ttdk1507d.inspectioncamera.model.TimestampConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

object TimestampPainter {
    private var cachedFormatter: java.text.SimpleDateFormat? = null
    private var cachedFormatString: String? = null


    fun formatTimestamp(date: Date = Date(), pattern: String = "HH:mm:ss - dd/MM/yyyy"): String {
        return try {
            SimpleDateFormat(pattern, Locale.getDefault()).format(date)
        } catch (e: Exception) {
            SimpleDateFormat("HH:mm:ss - dd/MM/yyyy", Locale.getDefault()).format(date)
        }
    }

    fun resizeBitmap(bitmap: Bitmap, resolution: String): Bitmap {
        val (targetLong, targetShort) = when (resolution.lowercase().trim()) {
            "high", "4k" -> 3840 to 2160
            "medium", "fhd", "1080p" -> 1920 to 1080
            "low", "hd", "720p" -> 1280 to 720
            else -> return bitmap // "original" or unknown
        }

        val width = bitmap.width
        val height = bitmap.height

        val isLandscape = width >= height
        val targetWidth = if (isLandscape) targetLong else targetShort
        val targetHeight = if (isLandscape) targetShort else targetLong

        // First crop to exact target aspect ratio
        val targetAspect = targetWidth.toFloat() / targetHeight.toFloat()
        val currentAspect = width.toFloat() / height.toFloat()

        val croppedBitmap = if (Math.abs(currentAspect - targetAspect) > 0.01f) {
            if (currentAspect > targetAspect) {
                val cropWidth = (height * targetAspect).roundToInt().coerceAtMost(width)
                val cropX = (width - cropWidth) / 2
                Bitmap.createBitmap(bitmap, cropX, 0, cropWidth, height)
            } else {
                val cropHeight = (width / targetAspect).roundToInt().coerceAtMost(height)
                val cropY = (height - cropHeight) / 2
                Bitmap.createBitmap(bitmap, 0, cropY, width, cropHeight)
            }
        } else {
            bitmap
        }

        val scaled = Bitmap.createScaledBitmap(croppedBitmap, targetWidth, targetHeight, true)
        if (croppedBitmap != bitmap && croppedBitmap != scaled) {
            croppedBitmap.recycle()
        }
        return scaled
    }

    fun paintTimestamp(
        bitmap: Bitmap,
        config: TimestampConfig = TimestampConfig(),
        date: Date = Date()
    ): Bitmap {
        if (!config.enabled) return bitmap

        val text = formatTimestamp(date, config.format)
        val resultBitmap = if (bitmap.isMutable) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(resultBitmap)

        // Scale relative to 720p base resolution
        val baseDim = minOf(resultBitmap.width, resultBitmap.height)
        val scale = (baseDim.toFloat() / 720f).coerceAtLeast(1.0f)

        val scaledFontSize = config.fontSize * scale
        val scaledStrokeWidth = config.fontStrokeWidth * scale
        val padH = 16f * scale
        val padV = 8f * scale
        val margin = 24f * scale
        val cornerRadius = 6f * scale

        // Paint for background band
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = try {
                Color.parseColor(config.backgroundColor)
            } catch (e: Exception) {
                Color.parseColor("#80000000")
            }
        }

        // Paint for text
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = scaledFontSize
            isFakeBoldText = config.fontBold
            style = Paint.Style.FILL
            color = try {
                Color.parseColor(config.fontColor)
            } catch (e: Exception) {
                Color.WHITE
            }
        }

        // Paint for stroke
        val strokePaint = if (config.fontStrokeEnabled) {
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = scaledFontSize
                isFakeBoldText = config.fontBold
                style = Paint.Style.STROKE
                strokeWidth = scaledStrokeWidth
                color = try {
                    Color.parseColor(config.fontStrokeColor)
                } catch (e: Exception) {
                    Color.BLACK
                }
            }
        } else null

        val textWidth = textPaint.measureText(text)
        val fontMetrics = textPaint.fontMetrics
        val textHeight = fontMetrics.descent - fontMetrics.ascent
        val boxWidth = textWidth + padH * 2
        val boxHeight = textHeight + padV * 2

        val (boxLeft, boxTop) = calculatePosition(
            position = config.position,
            bitmapWidth = resultBitmap.width.toFloat(),
            bitmapHeight = resultBitmap.height.toFloat(),
            boxWidth = boxWidth,
            boxHeight = boxHeight,
            margin = margin
        )

        val boxRect = RectF(boxLeft, boxTop, boxLeft + boxWidth, boxTop + boxHeight)
        canvas.drawRoundRect(boxRect, cornerRadius, cornerRadius, bgPaint)

        val textX = boxLeft + padH
        val textY = boxTop + padV - fontMetrics.ascent

        strokePaint?.let { canvas.drawText(text, textX, textY, it) }
        canvas.drawText(text, textX, textY, textPaint)

        return resultBitmap
    }

    fun calculatePosition(
        position: String,
        bitmapWidth: Float,
        bitmapHeight: Float,
        boxWidth: Float,
        boxHeight: Float,
        margin: Float
    ): Pair<Float, Float> {
        val left = when (position.lowercase()) {
            "top_left", "bottom_left" -> margin
            "top_center", "bottom_center" -> (bitmapWidth - boxWidth) / 2f
            "top_right", "bottom_right" -> bitmapWidth - boxWidth - margin
            else -> bitmapWidth - boxWidth - margin // Default bottom_right
        }

        val top = when (position.lowercase()) {
            "top_left", "top_center", "top_right" -> margin
            "bottom_left", "bottom_center", "bottom_right" -> bitmapHeight - boxHeight - margin
            else -> bitmapHeight - boxHeight - margin // Default bottom_right
        }

        val clampedLeft = left.coerceIn(0f, (bitmapWidth - boxWidth).coerceAtLeast(0f))
        val clampedTop = top.coerceIn(0f, (bitmapHeight - boxHeight).coerceAtLeast(0f))

        return clampedLeft to clampedTop
    }
}
