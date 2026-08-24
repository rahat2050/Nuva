package com.nuva.assistant

import android.app.Application
import com.nuva.assistant.core.NuvaContainer

/** Application entry — initializes the dependency container. */
class NuvaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        NuvaContainer.init(this)
    }
}
