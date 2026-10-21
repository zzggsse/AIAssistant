package com.assistant.one

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.Path
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class AssistantOneAccessibilityService : AccessibilityService() {
    private var screenshotWaiter: ((Bitmap?) -> Unit)? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        AgentRuntime.onServiceConnected(this)
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        AgentRuntime.onServiceDisconnected(this)
        return super.onUnbind(intent)
    }

    fun currentApp(): String {
        val packageName = rootInActiveWindow?.packageName?.toString()
            ?: windows.firstNotNullOfOrNull { it.root?.packageName?.toString() }
            ?: "unknown"
        return runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0))
        }.getOrNull()?.toString() ?: packageName
    }

    suspend fun screenshot(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return suspendCancellableCoroutine { continuation ->
            screenshotWaiter = { bitmap ->
                screenshotWaiter = null
                if (continuation.isActive) continuation.resume(bitmap)
            }
            takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val hardwareBitmap = Bitmap.wrapHardwareBuffer(
                            screenshot.hardwareBuffer,
                            screenshot.colorSpace
                        )
                        val bitmap = hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                        screenshot.hardwareBuffer.close()
                        screenshotWaiter?.invoke(bitmap)
                    }

                    override fun onFailure(errorCode: Int) {
                        screenshotWaiter?.invoke(null)
                    }
                }
            )
        }
    }

    fun tap(relativeX: Int, relativeY: Int): Boolean {
        val bounds = realDisplaySize() ?: return false
        val x = (relativeX.coerceIn(0, 999) / 1000f * bounds.first).toInt()
        val y = (relativeY.coerceIn(0, 999) / 1000f * bounds.second).toInt()
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 80L)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun swipe(startX: Int, startY: Int, endX: Int, endY: Int): Boolean {
        val bounds = realDisplaySize() ?: return false
        val fromX = (startX.coerceIn(0, 999) / 1000f * bounds.first).toInt()
        val fromY = (startY.coerceIn(0, 999) / 1000f * bounds.second).toInt()
        val toX = (endX.coerceIn(0, 999) / 1000f * bounds.first).toInt()
        val toY = (endY.coerceIn(0, 999) / 1000f * bounds.second).toInt()
        val path = Path().apply {
            moveTo(fromX.toFloat(), fromY.toFloat())
            lineTo(toX.toFloat(), toY.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 350L)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun longPress(relativeX: Int, relativeY: Int): Boolean {
        val bounds = realDisplaySize() ?: return false
        val x = (relativeX.coerceIn(0, 999) / 1000f * bounds.first).toInt()
        val y = (relativeY.coerceIn(0, 999) / 1000f * bounds.second).toInt()
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 1000L)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun doubleTap(relativeX: Int, relativeY: Int): Boolean {
        val bounds = realDisplaySize() ?: return false
        val x = (relativeX.coerceIn(0, 999) / 1000f * bounds.first).toInt()
        val y = (relativeY.coerceIn(0, 999) / 1000f * bounds.second).toInt()
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val first = GestureDescription.StrokeDescription(path, 0L, 70L)
        val second = GestureDescription.StrokeDescription(path, 150L, 70L)
        return dispatchGesture(GestureDescription.Builder().addStroke(first).addStroke(second).build(), null, null)
    }

    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.isEditable && setText(focused, text)) return true

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isEditable && setText(node, text)) return true
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::add)
            }
        }
        return false
    }

    private fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    fun launchApp(appName: String): Boolean {
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = packageManager.queryIntentActivities(launchIntent, 0)
        val match = activities.firstOrNull { resolved ->
            val label = resolved.loadLabel(packageManager).toString()
            label.equals(appName, ignoreCase = true) || label.contains(appName, ignoreCase = true)
        } ?: return false
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(match.activityInfo.packageName, match.activityInfo.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { startActivity(intent); true }.getOrDefault(false)
    }

    private fun realDisplaySize(): Pair<Int, Int>? {
        val metrics = resources.displayMetrics
        return if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
            metrics.widthPixels to metrics.heightPixels
        } else null
    }
}
