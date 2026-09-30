package io.github.chayanforyou.quickball.core.visibility

/**
 * Tracks which *application* is in the foreground using only the
 * `packageName` / `className` carried by TYPE_WINDOW_STATE_CHANGED events.
 *
 * It deliberately does NOT use `rootInActiveWindow`, window content or
 * `canRetrieveWindowContent` — Quick Ball keeps its minimal accessibility
 * capabilities. The only extra signal is a PackageManager lookup that tells
 * whether `className` is a real Activity of `packageName`.
 *
 * Why the activity check matters: keyboards (IME), toasts, dialogs, the
 * notification shade and our own overlay windows all emit window-state events.
 * Treating those as "the foreground app changed" makes the ball flicker back
 * on top of an excluded app (e.g. when the keyboard opens inside a banking
 * app). Only a genuine Activity transition changes the foreground app.
 *
 * Pure Kotlin — no Android dependency — so it is unit-testable on the JVM.
 */
class ForegroundAppTracker(
    private val isActivity: (packageName: String, className: String) -> Boolean,
    private val ignoredPackages: () -> Set<String>,
) {
    /** Package of the last Activity seen in the foreground, or null if unknown. */
    var currentPackage: String? = null
        private set

    /**
     * Feed one TYPE_WINDOW_STATE_CHANGED event.
     * @return true when the foreground application actually changed.
     */
    fun onWindowStateChanged(packageName: String?, className: String?): Boolean {
        if (packageName.isNullOrBlank() || className.isNullOrBlank()) return false
        if (packageName in ignoredPackages()) return false
        if (!isActivity(packageName, className)) return false
        if (packageName == currentPackage) return false
        currentPackage = packageName
        return true
    }

    /** Forget the foreground app (e.g. after the screen was turned off). */
    fun reset() {
        currentPackage = null
    }
}
