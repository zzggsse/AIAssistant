package com.aibutler.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualFeatureLogicTest {
    @Test
    fun cosine_same_direction_is_one_and_opposite_is_negative_one() {
        val left = floatArrayOf(2f, 0f, -1f)
        val right = floatArrayOf(8f, 0f, -4f)
        assertEquals(1f, VectorMath.cosine(left, right), 0.0001f)
        assertEquals(-1f, VectorMath.cosine(left, floatArrayOf(-2f, 0f, 1f)), 0.0001f)
        assertEquals(0f, VectorMath.cosine(left, floatArrayOf(0f, 5f, 0f)), 0.0001f)
    }

    @Test
    fun normalize_produces_unit_vector() {
        val feature = VectorMath.normalize(floatArrayOf(3f, 4f, 0f))
        assertEquals(0.6f, feature[0], 0.0001f)
        assertEquals(0.8f, feature[1], 0.0001f)
        assertEquals(1f, VectorMath.cosine(feature, feature), 0.0001f)
    }

    @Test
    fun empty_or_mismatched_vectors_do_not_match() {
        assertEquals(0f, VectorMath.cosine(FloatArray(0), FloatArray(0)), 0f)
        assertEquals(0f, VectorMath.cosine(floatArrayOf(1f), floatArrayOf(1f, 2f)), 0f)
    }

    @Test
    fun feature_layout_has_fixed_size() {
        assertEquals(196 + 144 + 16 + 8 + 8, VisualFeatureEncoder.FEATURE_LENGTH)
        assertTrue(VisualFeatureEncoder.FEATURE_VERSION >= 1)
    }
}
