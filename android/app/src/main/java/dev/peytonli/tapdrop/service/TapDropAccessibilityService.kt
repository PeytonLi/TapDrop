package dev.peytonli.tapdrop.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import java.lang.ref.WeakReference
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class TapDropAccessibilityService : AccessibilityService() {
    @Volatile
    var foregroundPackage: String? = null
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceReference = WeakReference(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        foregroundPackage = event?.packageName?.toString()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (serviceReference?.get() === this) serviceReference = null
        super.onDestroy()
    }

    fun currentBrowserUrl(): String? {
        val browser = BROWSERS[foregroundPackage] ?: return null
        val root = rootInActiveWindow ?: return null
        val viewId = "$foregroundPackage:id/${browser.urlViewId}"
        val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
        return nodes.firstNotNullOfOrNull { node ->
            node.text?.toString()?.trim()?.takeIf(String::isNotEmpty)
                ?: node.contentDescription?.toString()?.trim()?.takeIf(String::isNotEmpty)
        }
    }

    suspend fun screenshot(): Bitmap? {
        if (Build.VERSION.SDK_INT < 30) return null
        return captureScreenshot()
    }

    @RequiresApi(30)
    private suspend fun captureScreenshot(): Bitmap? = suspendCancellableCoroutine { continuation ->
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            ContextCompat.getMainExecutor(this),
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val buffer = screenshot.hardwareBuffer
                    val bitmap = try {
                        Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)?.let { wrapped ->
                            wrapped.copy(Bitmap.Config.ARGB_8888, false).also { wrapped.recycle() }
                        }
                    } catch (_: Exception) {
                        null
                    } finally {
                        buffer.close()
                    }
                    if (continuation.isActive) continuation.resume(bitmap)
                }

                override fun onFailure(errorCode: Int) {
                    if (continuation.isActive) continuation.resume(null)
                }
            },
        )
    }

    companion object {
        @Volatile
        private var serviceReference: WeakReference<TapDropAccessibilityService>? = null

        private data class Browser(val urlViewId: String)

        private val BROWSERS = mapOf(
            "com.android.chrome" to Browser("url_bar"),
            "com.sec.android.app.sbrowser" to Browser("location_bar_edit_text"),
            "org.mozilla.firefox" to Browser("mozac_browser_toolbar_url_view"),
            "com.microsoft.emmx" to Browser("url_bar"),
            "com.brave.browser" to Browser("url_bar"),
        )

        fun current(): TapDropAccessibilityService? = serviceReference?.get()

        fun isEnabled(context: Context): Boolean {
            val component = ComponentName(context, TapDropAccessibilityService::class.java)
            val enabled = Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                0,
            ) == 1
            if (!enabled) return false
            return Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ).orEmpty().split(':').any { ComponentName.unflattenFromString(it) == component }
        }
    }
}
