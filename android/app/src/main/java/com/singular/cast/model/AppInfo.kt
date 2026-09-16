package com.singular.cast.model

/** One launchable app on the device, as shown in the cast list. */
data class AppInfo(
    val pkg: String,
    val label: String,
    /** A task for this package currently exists (needs Shizuku to know for sure). */
    val running: Boolean,
    /** Used in the foreground recently — the best guess without privileges. */
    val recent: Boolean,
    /** base64 PNG of the launcher icon, computed lazily and cached. */
    val iconPng: String? = null,
)

/** A live cast, as tracked on the phone. */
data class CastSession(
    val id: Int,
    val app: AppInfo,
    val mode: Mode,
    val width: Int,
    val height: Int,
    val dpi: Int,
    /** -1 in mirror mode, where there is no virtual display. */
    val displayId: Int,
    /** Task to move back to display 0 when the app is recalled. */
    val taskId: Int,
) {
    enum class Mode { Display, Mirror }

    val modeWire: String get() = if (mode == Mode.Display) "display" else "mirror"
}
