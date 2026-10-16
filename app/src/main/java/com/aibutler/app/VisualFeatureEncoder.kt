package com.aibutler.app

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.roundToInt

object VisualFeatureEncoder {
    const val FEATURE_VERSION = 1
    private const val LUMA_SIZE = 14
    private const val EDGE_SIZE = 12
    private const val HUE_BINS = 16
    private const val SAT_BINS = 8
    private const val VALUE_BINS = 8
    const val FEATURE_LENGTH =
        LUMA_SIZE * LUMA_SIZE + EDGE_SIZE * EDGE_SIZE + HUE_BINS + SAT_BINS + VALUE_BINS

    fun extract(source: Bitmap): FloatArray {
        val width = source.width
        val height = source.height
        if (width <= 0 || height <= 0) return FloatArray(FEATURE_LENGTH)
        val size = max(width, height).coerceAtMost(96)
        val scaledHeight = ((height.toDouble() * size / width).roundToInt()).coerceAtLeast(1)
        val bitmap = if (width == size && height == scaledHeight) source else {
            Bitmap.createScaledBitmap(source, size, scaledHeight, true)
        }

        val luma = FloatArray(LUMA_SIZE * LUMA_SIZE)
        val edge = FloatArray(EDGE_SIZE * EDGE_SIZE)
        val hue = FloatArray(HUE_BINS)
        val saturation = FloatArray(SAT_BINS)
        val value = FloatArray(VALUE_BINS)
        val bw = bitmap.width
        val bh = bitmap.height
        val pixels = IntArray(bw * bh)
        bitmap.getPixels(pixels, 0, bw, 0, 0, bw, bh)

        for (y in 0 until bh) {
            for (x in 0 until bw) {
                val pixel = pixels[y * bw + x]
                val red = Color.red(pixel) / 255f
                val green = Color.green(pixel) / 255f
                val blue = Color.blue(pixel) / 255f
                val gray = 0.299f * red + 0.587f * green + 0.114f * blue
                luma[binIndex(x, y, bw, bh, LUMA_SIZE)] += gray

                val hsv = FloatArray(3)
                Color.RGBToHSV(Color.red(pixel), Color.green(pixel), Color.blue(pixel), hsv)
                hue[((hsv[0] / 360f) * HUE_BINS).toInt().coerceIn(0, HUE_BINS - 1)] +=
                    hsv[1] * hsv[2]
                saturation[(hsv[1] * SAT_BINS).toInt().coerceIn(0, SAT_BINS - 1)] += hsv[2]
                value[(hsv[2] * VALUE_BINS).toInt().coerceIn(0, VALUE_BINS - 1)]++

                if (x + 1 < bw) {
                    val difference = abs(gray - lumaOf(pixels[y * bw + x + 1]))
                    edge[binIndex(x, y, bw, bh, EDGE_SIZE)] += difference
                }
                if (y + 1 < bh) {
                    val difference = abs(gray - lumaOf(pixels[(y + 1) * bw + x]))
                    edge[binIndex(x, y, bw, bh, EDGE_SIZE)] += difference
                }
            }
        }

        val sampleScale = (bw * bh).toFloat() / (LUMA_SIZE * LUMA_SIZE)
        for (index in luma.indices) luma[index] /= sampleScale
        for (index in edge.indices) edge[index] /= sampleScale
        for (index in value.indices) value[index] /= (bw * bh).toFloat()

        val feature = VectorMath.normalize(luma + edge + hue + saturation + value)
        if (bitmap !== source) bitmap.recycle()
        return feature
    }

    private fun lumaOf(pixel: Int): Float {
        return (0.299f * Color.red(pixel) + 0.587f * Color.green(pixel) + 0.114f * Color.blue(pixel)) / 255f
    }

    private fun binIndex(x: Int, y: Int, width: Int, height: Int, bins: Int): Int {
        val bx = ((x.toDouble() / width) * bins).toInt().coerceIn(0, bins - 1)
        val by = ((y.toDouble() / height) * bins).toInt().coerceIn(0, bins - 1)
        return by * bins + bx
    }

    private fun abs(value: Float): Float = if (value < 0f) -value else value
}
