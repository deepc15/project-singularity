package com.singular.cast.apps

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Process
import android.util.Base64
import android.util.Log
import com.singular.cast.cast.ShizukuBridge
import com.singular.cast.model.AppInfo
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Enumerates launchable apps and works out which of them are active.
 *
 * "Running" is genuinely knowable only with shell privileges — since
 * Android 5.1 an ordinary app cannot list other processes. So there are two
 * answers: an authoritative task list via Shizuku, and a usage-stats
 * approximation ("recent") when Shizuku is absent.
 */
class AppRepository(
    private val context: Context,
    private val shizuku: ShizukuBridge,
) {
    private val iconCache = HashMap<String, String>()

    suspend fun load(): List<AppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        @Suppress("DEPRECATION")
        val resolved: List<ResolveInfo> = pm.queryIntentActivities(launcher, 0)

        val recent = recentPackages()
        val running = runningPackages()
        val self = context.packageName

        resolved
            .asSequence()
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                // Casting ourselves onto the PC would be an infinite mirror.
                if (pkg == self) return@mapNotNull null
                AppInfo(
                    pkg = pkg,
                    label = info.loadLabel(pm)?.toString()?.ifBlank { pkg } ?: pkg,
                    running = pkg in running,
                    recent = pkg in recent,
                    iconPng = iconFor(pkg, info, pm),
                )
            }
            .distinctBy { it.pkg }
            .sortedWith(
                compareByDescending<AppInfo> { it.running }
                    .thenByDescending { it.recent }
                    .thenBy { it.label.lowercase() },
            )
            .toList()
    }

    /** Authoritative, but only when the privileged bridge is up. */
    private fun runningPackages(): Set<String> {
        if (!shizuku.isReady) return emptySet()
        return shizuku.withService { it.runningPackages().toSet() } ?: emptySet()
    }

    /**
     * Packages brought to the foreground in the recent past. Requires the
     * user to grant Usage access; without it this returns empty and the list
     * simply has no "recent" markers.
     */
    private fun recentPackages(): Set<String> {
        if (!hasUsageAccess()) return emptySet()
        val usage = context.getSystemService(UsageStatsManager::class.java) ?: return emptySet()
        val now = System.currentTimeMillis()

        return try {
            val events = usage.queryEvents(now - RECENT_WINDOW_MS, now)
            val out = HashSet<String>()
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                    event.packageName?.let(out::add)
                }
            }
            out
        } catch (e: Exception) {
            Log.w(TAG, "usage query failed: ${e.message}")
            emptySet()
        }
    }

    fun hasUsageAccess(): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    // -------------------------------------------------------------------- icons

    private fun iconFor(pkg: String, info: ResolveInfo, pm: PackageManager): String? {
        iconCache[pkg]?.let { return it }
        val drawable = runCatching { info.loadIcon(pm) }.getOrNull() ?: return null
        val encoded = encodeIcon(drawable) ?: return null
        iconCache[pkg] = encoded
        return encoded
    }

    private fun encodeIcon(drawable: Drawable): String? {
        val bitmap = if (drawable is BitmapDrawable && drawable.bitmap != null) {
            Bitmap.createScaledBitmap(drawable.bitmap, ICON_PX, ICON_PX, true)
        } else {
            Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888).also { bmp ->
                val canvas = Canvas(bmp)
                drawable.setBounds(0, 0, ICON_PX, ICON_PX)
                drawable.draw(canvas)
            }
        }
        return try {
            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            }
        } catch (e: Exception) {
            null
        }
    }

    private companion object {
        const val TAG = "SingularApps"
        const val RECENT_WINDOW_MS = 30 * 60 * 1000L
        const val ICON_PX = 48
    }
}
