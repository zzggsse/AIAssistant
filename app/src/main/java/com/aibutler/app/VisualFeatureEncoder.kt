package com.aibutler.app

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.cos
import kotlin.math.sqrt

data class VisualFeature(
    val phash: Long,
    val colorFeature: FloatArray
)

object VisualFeatureEncoder {
    const val FEATURE_VERSION = 2
    const val FEATURE_LENGTH = 196 + 144 + 16 + 8 + 8
    private const val DCT_SIZE = 32
    private const val HASH_BITS = 64
    private const val COLOR_BINS = 32

    fun extract(source: Bitmap): VisualFeature {
        val width = source.width
        val height = source.height
        if (width <= 0 || height <= 0) return VisualFeature(0L, FloatArray(COLOR_BINS))

        val gray = DoubleArray(DCT_SIZE * DCT_SIZE)
        val colorHist = FloatArray(COLOR_BINS)
        val scaled = Bitmap.createScaledBitmap(source, DCT_SIZE, DCT_SIZE, true)
        val pixels = IntArray(DCT_SIZE * DCT_SIZE)
        scaled.getPixels(pixels, 0, DCT_SIZE, 0, 0, DCT_SIZE, DCT_SIZE)

        for (index in pixels.indices) {
            val pixel = pixels[index]
            gray[index] = (0.299 * Color.red(pixel) + 0.587 * Color.green(pixel) + 0.114 * Color.blue(pixel)) / 255.0
            val hsv = FloatArray(3)
            Color.RGBToHSV(Color.red(pixel), Color.green(pixel), Color.blue(pixel), hsv)
            val hueBin = ((hsv[0] / 360.0) * 16).toInt().coerceIn(0, 15)
            val satBin = if (hsv[1] < 0.3) 0 else 1
            colorHist[hueBin + satBin * 16] += hsv[1] * hsv[2]
        }
        if (scaled !== source) scaled.recycle()

        val total = colorHist.sum()
        if (total > 0) for (index in colorHist.indices) colorHist[index] /= total

        val dct = dct2d(gray)
        val lowFreq = DoubleArray(HASH_BITS)
        var bit = 0
        outer@ for (y in 0 until 8) {
            for (x in 0 until 8) {
                if (bit >= HASH_BITS) break@outer
                lowFreq[bit++] = dct[y * DCT_SIZE + x]
            }
        }
        val sorted = lowFreq.copyOf().sorted()
        val median = (sorted[31] + sorted[32]) / 2.0
        var phash = 0L
        for (index in 0 until HASH_BITS) {
            if (lowFreq[index] > median) phash = phash or (1L shl index)
        }
        return VisualFeature(phash, VectorMath.normalize(colorHist))
    }

    fun hammingDistance(a: Long, b: Long): Int {
        var diff = a xor b
        var count = 0
        while (diff != 0L) {
            count += (diff and 1L).toInt()
            diff = diff ushr 1
        }
        return count
    }

    private fun dct2d(input: DoubleArray): DoubleArray {
        val size = DCT_SIZE
        val cosTable = Array(size) { x -> DoubleArray(size) { u -> cos((2.0 * x + 1.0) * u * Math.PI / (2.0 * size)) } }
        val temp = DoubleArray(size * size)
        val output = DoubleArray(size * size)
        for (y in 0 until size) {
            for (u in 0 until size) {
                var sum = 0.0
                for (x in 0 until size) sum += input[y * size + x] * cosTable[x][u]
                temp[y * size + u] = sum * if (u == 0) ROOT_1_OVER_2N else ROOT_2_OVER_N
            }
        }
        for (u in 0 until size) {
            for (v in 0 until size) {
                var sum = 0.0
                for (y in 0 until size) sum += temp[y * size + u] * cosTable[y][v]
                output[v * size + u] = sum * if (v == 0) ROOT_1_OVER_2N else ROOT_2_OVER_N
            }
        }
        return output
    }

    private val ROOT_1_OVER_2N = 1.0 / sqrt(DCT_SIZE * 2.0)
    private val ROOT_2_OVER_N = 2.0 / sqrt(DCT_SIZE * 2.0)
}
