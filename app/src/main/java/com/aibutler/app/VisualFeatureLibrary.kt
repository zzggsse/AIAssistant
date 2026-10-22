package com.aibutler.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.UUID

class VisualFeatureLibrary(context: Context) {
    private val gson = Gson()
    private val type = object : TypeToken<MutableList<VisualSample>>() {}.type
    private val root = File(context.filesDir, "visual_samples").apply { mkdirs() }
    private val imageDir = File(root, "images").apply { mkdirs() }
    private val indexFile = File(root, "index.json")

    fun all(): List<VisualSample> = synchronized(this) {
        load().filter { it.enabled }
    }

    fun count(): Int = all().size

    fun add(source: Bitmap, label: String, packageName: String): VisualSample? {
        val cleanLabel = label.trim()
        if (cleanLabel.isBlank()) return null
        val vf = VisualFeatureEncoder.extract(source)
        if (vf.phash == 0L) return null
        val id = UUID.randomUUID().toString()
        val imageFile = File(imageDir, "$id.jpg")
        if (!saveImage(source, imageFile)) return null
        val sample = VisualSample(
            id = id,
            label = cleanLabel.take(40),
            packageName = packageName,
            imagePath = imageFile.absolutePath,
            phash = vf.phash,
            colorFeature = vf.colorFeature
        )
        synchronized(this) {
            val items = load().toMutableList()
            items.add(sample)
            while (items.size > MAX_SAMPLES) {
                val oldest = items.minByOrNull { it.createdAt }
                if (oldest == null) break
                File(oldest.imagePath).delete()
                items.remove(oldest)
            }
            AtomicFiles.write(indexFile, gson.toJson(items))
        }
        return sample
    }

    fun delete(sampleId: String) {
        synchronized(this) {
            val items = load().toMutableList()
            val target = items.firstOrNull { it.id == sampleId } ?: return
            File(target.imagePath).delete()
            items.removeAll { it.id == sampleId }
            AtomicFiles.write(indexFile, gson.toJson(items))
        }
    }

    fun rename(sampleId: String, newLabel: String): Boolean {
        val cleanLabel = newLabel.trim()
        if (cleanLabel.isBlank()) return false
        synchronized(this) {
            val items = load().toMutableList()
            val index = items.indexOfFirst { it.id == sampleId }
            if (index < 0) return false
            items[index] = items[index].copy(label = cleanLabel.take(40))
            AtomicFiles.write(indexFile, gson.toJson(items))
        }
        return true
    }

    fun clear() {
        synchronized(this) {
            load().forEach { File(it.imagePath).delete() }
            indexFile.delete()
        }
    }

    fun matchScreen(
        screen: Bitmap,
        packageName: String,
        candidateRects: List<Rect>,
        threshold: Float = MATCH_THRESHOLD
    ): List<VisualMatch> {
        val samples = all().filter {
            it.featureVersion == VisualFeatureEncoder.FEATURE_VERSION &&
                (it.packageName.isBlank() || it.packageName == packageName)
        }
        if (samples.isEmpty() || candidateRects.isEmpty()) return emptyList()
        val hits = ArrayList<VisualMatch>()
        val matchedIds = ArrayList<String>()
        for (rect in candidateRects) {
            if (rect.width() < MIN_WIDTH || rect.height() < MIN_HEIGHT) continue
            if (rect.right > screen.width || rect.bottom > screen.height) continue
            val crop = Bitmap.createBitmap(screen, rect.left, rect.top, rect.width(), rect.height())
            val candidateFeature = VisualFeatureEncoder.extract(crop)
            if (crop !== screen) crop.recycle()
            val best = samples.maxByOrNull { combinedScore(candidateFeature, it) } ?: continue
            val score = combinedScore(candidateFeature, best)
            if (score >= threshold) {
                hits.add(
                    VisualMatch(
                        sampleId = best.id,
                        label = best.label,
                        rectKey = rectKey(rect),
                        left = rect.left,
                        top = rect.top,
                        right = rect.right,
                        bottom = rect.bottom,
                        score = score
                    )
                )
                matchedIds.add(best.id)
            }
        }
        recordMatches(matchedIds)
        return hits
    }

    fun previewBest(screen: Bitmap, packageName: String, rect: Rect): Pair<String, Float> {
        val samples = all().filter {
            it.featureVersion == VisualFeatureEncoder.FEATURE_VERSION &&
                (it.packageName.isBlank() || it.packageName == packageName)
        }
        if (samples.isEmpty() || rect.left < 0 || rect.top < 0 ||
            rect.right > screen.width || rect.bottom > screen.height
        ) {
            return Pair("", 0f)
        }
        val crop = Bitmap.createBitmap(screen, rect.left, rect.top, rect.width(), rect.height())
        val feature = VisualFeatureEncoder.extract(crop)
        if (crop !== screen) crop.recycle()
        val best = samples.maxByOrNull { combinedScore(feature, it) } ?: return Pair("", 0f)
        return Pair(best.label, combinedScore(feature, best))
    }

    private fun combinedScore(query: VisualFeature, sample: VisualSample): Float {
        val hashSim = 1.0f - VisualFeatureEncoder.hammingDistance(query.phash, sample.phash) / 64.0f
        val colorSim = VectorMath.cosine(query.colorFeature, sample.colorFeature).coerceIn(0f, 1f)
        return hashSim * 0.7f + colorSim * 0.3f
    }

    /** 本帧所有命中合并成一次 load→改→save：逐 rect 各写一遍 index.json 是 agent 循环内的 I/O 放大 */
    private fun recordMatches(sampleIds: List<String>) {
        if (sampleIds.isEmpty()) return
        synchronized(this) {
            val counts = sampleIds.groupingBy { it }.eachCount()
            val items = load().toMutableList()
            val now = System.currentTimeMillis()
            var changed = false
            for (i in items.indices) {
                val add = counts[items[i].id] ?: continue
                items[i] = items[i].copy(
                    matchCount = items[i].matchCount + add,
                    lastMatchedAt = now
                )
                changed = true
            }
            if (changed) AtomicFiles.write(indexFile, gson.toJson(items))
        }
    }

    private fun load(): MutableList<VisualSample> {
        if (!indexFile.exists()) return mutableListOf()
        return try {
            gson.fromJson<MutableList<VisualSample>>(indexFile.readText(), type) ?: mutableListOf()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun saveImage(source: Bitmap, target: File): Boolean {
        return try {
            target.outputStream().use { source.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            true
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        const val MATCH_THRESHOLD = 0.72f
        private const val MAX_SAMPLES = 500
        private const val MIN_WIDTH = 20
        private const val MIN_HEIGHT = 20

        fun rectKey(rect: Rect): String = "${rect.left},${rect.top},${rect.right},${rect.bottom}"
    }
}
