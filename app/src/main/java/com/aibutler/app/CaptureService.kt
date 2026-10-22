package com.aibutler.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.view.WindowManager

/**
 * 屏幕采集服务：让 AI 拥有"看截图"的视觉能力。
 * 用户点 👁 授权后，AI 可以随时抓取当前屏幕画面发给视觉模型分析。
 */
class CaptureService : Service() {

    companion object {
        private var projection: MediaProjection? = null
        private var vdisplay: VirtualDisplay? = null
        private var reader: ImageReader? = null
        private var width = 0
        private var height = 0
        private var dpi = 0
        private var lastFrame: Bitmap? = null
        private val lastFrameLock = Any()
        private var frameThread: HandlerThread? = null
        private var frameHandler: Handler? = null

        @Volatile
        var active = false
            private set

        fun isActive(): Boolean = active && reader != null

        fun start(context: Context, resultCode: Int, data: Intent) {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val bounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                windowManager.maximumWindowMetrics.bounds
            } else {
                @Suppress("DEPRECATION")
                val point = Point().also { windowManager.defaultDisplay.getRealSize(it) }
                Rect(0, 0, point.x, point.y)
            }
            width = bounds.width()
            height = bounds.height()
            val metrics = context.resources.displayMetrics
            dpi = metrics.densityDpi
            val intent = Intent(context, CaptureService::class.java)
                .putExtra("code", resultCode)
                .putExtra("data", data)
            context.startForegroundService(intent)
        }

        /** 抓取当前屏幕帧 */
        fun capture(): Bitmap? {
            val r = reader ?: return null
            var img: Image? = try { r.acquireLatestImage() } catch (_: Exception) { null }
            var waitCount = 0
            while (img == null && waitCount < 5) {
                Thread.sleep(20)
                img = try { r.acquireLatestImage() } catch (_: Exception) { null }
                waitCount++
            }
            val image = img ?: return snapshot()
            val frame = try { imageToBitmap(image) } catch (_: Exception) { null }
            try { image.close() } catch (_: Exception) { }
            if (frame == null) return snapshot()
            cacheFrame(frame)
            return snapshot()
        }

        private fun imageToBitmap(image: Image): Bitmap {
            val plane = image.planes[0]
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val width = image.width
            val height = image.height
            val rowWidth = rowStride / pixelStride
            val full = Bitmap.createBitmap(rowWidth, height, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(plane.buffer)
            return if (rowWidth == width) full else Bitmap.createBitmap(full, 0, 0, width, height).also {
                if (it !== full) full.recycle()
            }
        }

        private fun cacheFrame(frame: Bitmap?) {
            if (frame == null) return
            val cached = frame.copy(Bitmap.Config.ARGB_8888, false)
            if (cached != null) {
                synchronized(lastFrameLock) {
                    lastFrame?.recycle()
                    lastFrame = cached
                }
            }
            frame.recycle()
        }

        private fun snapshot(): Bitmap? {
            return synchronized(lastFrameLock) {
                lastFrame?.copy(Bitmap.Config.ARGB_8888, false)
            }
        }

        // start 与 stopAll 可能来自不同线程（投屏停止回调 / 主线程 / Agent 工作线程），
        // 不加锁会让上一次的 stopAll 在新一轮 start 之后才执行完，出现"假激活"或半初始化状态
        private val stateLock = Any()

        fun stopAll() = synchronized(stateLock) {
            try { vdisplay?.release() } catch (e: Exception) { }
            try { projection?.stop() } catch (e: Exception) { }
            try { reader?.close() } catch (e: Exception) { }
            vdisplay = null
            projection = null
            try { frameHandler?.removeCallbacksAndMessages(null) } catch (_: Exception) { }
            try { frameThread?.quitSafely() } catch (_: Exception) { }
            frameHandler = null
            frameThread = null
            reader = null
            // 回收缓存帧必须持锁：capture()/snapshot() 可能正在另一线程复制它，
            // 否则会出现"使用已回收的 Bitmap"崩溃
            synchronized(lastFrameLock) {
                lastFrame?.recycle()
                lastFrame = null
            }
            active = false
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.i("AIButler", "Capture onStartCommand intentNull=${intent == null}")
        // 必须最先调用 startForeground，否则系统判定超时直接崩
        val chId = "aivision"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(chId, "屏幕视觉", NotificationManager.IMPORTANCE_LOW)
        )
        val notif = Notification.Builder(this, chId)
            .setContentTitle("AI小助手·屏幕视觉已开启")
            .setContentText("AI 可以看懂屏幕上的图标了")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(2001, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(2001, notif)
        }
        android.util.Log.i("AIButler", "Capture startForeground 完成")

        val code = intent?.getIntExtra("code", -1) ?: -1
        val data: Intent? = if (android.os.Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra("data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("data")
        }
        android.util.Log.i("AIButler", "Capture code=$code dataNull=${data == null}")
        if (data == null) {
            android.util.Log.i("AIButler", "Capture 参数缺失，停止服务")
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            // 与 stopAll 互斥：防止上一轮投屏的停止回调在本轮初始化中途把资源拆掉
            synchronized(stateLock) {
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projection = mpm.getMediaProjection(code, data)
                projection?.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        stopAll()
                    }
                }, null)
                reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
                frameThread = HandlerThread("AIButlerFrameReader").apply { start() }
                frameHandler = Handler(frameThread!!.looper)
                reader?.setOnImageAvailableListener({ imageReader ->
                    val image = try { imageReader.acquireLatestImage() } catch (_: Exception) { null }
                    if (image == null) return@setOnImageAvailableListener
                    val frame = try { imageToBitmap(image) } catch (_: Exception) { null }
                    try { image.close() } catch (_: Exception) { }
                    cacheFrame(frame)
                }, frameHandler)
                vdisplay = projection?.createVirtualDisplay(
                    "aivision", width, height, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader!!.surface, null, null
                )
                active = true
                android.util.Log.i("AIButler", "Capture 虚拟屏幕已创建 active=true")
            }
        } catch (e: Exception) {
            android.util.Log.i("AIButler", "Capture 初始化失败：${e.message}")
            stopAll()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopAll()
        super.onDestroy()
    }
}
