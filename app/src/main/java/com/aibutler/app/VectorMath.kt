package com.aibutler.app

import kotlin.math.sqrt

object VectorMath {
    fun normalize(values: FloatArray): FloatArray {
        var sum = 0.0
        for (value in values) sum += value.toDouble() * value
        val scale = sqrt(sum)
        if (scale <= 1.0e-9) return FloatArray(values.size)
        val out = FloatArray(values.size)
        for (index in values.indices) out[index] = (values[index] / scale).toFloat()
        return out
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.isEmpty() || a.size != b.size) return 0f
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (index in a.indices) {
            val left = a[index].toDouble()
            val right = b[index].toDouble()
            dot += left * right
            normA += left * left
            normB += right * right
        }
        if (normA <= 1.0e-12 || normB <= 1.0e-12) return 0f
        return (dot / sqrt(normA * normB)).toFloat().coerceIn(-1f, 1f)
    }
}
