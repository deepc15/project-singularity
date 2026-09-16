package com.singular.cast

import android.app.Application
import com.singular.cast.cast.CastEngine

class SingularApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // The engine outlives every activity, so it is wired up here rather
        // than in MainActivity.
        CastEngine.init(this)
    }
}
