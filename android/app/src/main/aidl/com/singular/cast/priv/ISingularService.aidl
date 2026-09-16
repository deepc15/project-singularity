package com.singular.cast.priv;

import android.view.Surface;

/**
 * Runs inside the Shizuku/ADB shell process, which holds the privileges the app
 * itself cannot get: creating a trusted virtual display, launching an activity
 * onto it, and injecting input events into it.
 *
 * Every method carries an explicit transaction id because Shizuku reserves
 * 16777114 for `destroy`, and AIDL requires either all ids or none.
 */
interface ISingularService {

    /** Human-readable identification of the shell process, for diagnostics. */
    String describe() = 1;

    /**
     * Create a trusted, own-content-only virtual display rendering into
     * {@code surface} (the encoder's input surface).
     *
     * @return the new display id, or -1 if the shell lacks the privilege.
     */
    int createDisplay(String name, int width, int height, int densityDpi, in Surface surface) = 2;

    boolean resizeDisplay(int displayId, int width, int height, int densityDpi) = 3;

    /**
     * Point an existing display at a new encoder surface. This is how a tile
     * resize is handled without destroying the display — and therefore without
     * killing the app running on it.
     */
    boolean setDisplaySurface(int displayId, in Surface surface) = 4;

    void releaseDisplay(int displayId) = 5;

    /** Start the package's launcher activity on {@code displayId}. */
    boolean launchPackage(String packageName, int displayId) = 6;

    /** Task id of the package's current task, or -1 if it has none. */
    int findTaskId(String packageName) = 7;

    /**
     * Every package that currently owns a task. One call instead of a
     * findTaskId per installed app.
     */
    String[] runningPackages() = 8;

    /** Used both to move an app onto a display and to bring it back to display 0. */
    boolean moveTaskToDisplay(int taskId, int displayId) = 9;

    boolean injectTouch(int displayId, int action, float x, float y, long downTimeMs) = 10;

    boolean injectKey(int displayId, int action, int keyCode, int metaState, long downTimeMs) = 11;

    boolean injectScroll(int displayId, float x, float y, float hScroll, float vScroll) = 12;

    /** Commit a string by synthesising key events for it. */
    boolean injectText(int displayId, String text) = 13;

    /** Shizuku's reserved transaction for tearing down a user service. */
    void destroy() = 16777114;
}
