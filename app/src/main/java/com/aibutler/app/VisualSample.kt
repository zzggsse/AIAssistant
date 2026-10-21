package com.aibutler.app

data class VisualSample(
    val id: String,
    val label: String,
    val packageName: String,
    val imagePath: String,
    val phash: Long,
    val colorFeature: FloatArray,
    val featureVersion: Int = VisualFeatureEncoder.FEATURE_VERSION,
    val createdAt: Long = System.currentTimeMillis(),
    val matchCount: Int = 0,
    val lastMatchedAt: Long = 0L,
    val enabled: Boolean = true
) {
    override fun equals(other: Any?): Boolean = other is VisualSample && other.id == id

    override fun hashCode(): Int = id.hashCode()
}

data class VisualMatch(
    val sampleId: String,
    val label: String,
    val rectKey: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val score: Float
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}
