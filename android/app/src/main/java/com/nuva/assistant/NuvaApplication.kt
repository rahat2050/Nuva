package com.nuva.assistant

import android.app.Application
import com.nuva.assistant.core.NuvaContainer
import com.nuva.assistant.automation.ScheduledComposeScheduler
import com.nuva.assistant.core.permissions.NuvaPermissions
import com.nuva.assistant.service.WakeWordService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Application entry — initializes the dependency container. */
class NuvaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        NuvaContainer.init(this)

        // If the user already opted in, restore the visible wake-word service
        // whenever the app process is recreated. Permissions are still checked;
        // NUVA never starts background microphone use silently.
        if (NuvaContainer.preferences.wakeWordEnabledBlocking() && NuvaPermissions.hasWakeWordPermissions(this)) {
            WakeWordService.start(this)
        }

        // Idempotent PendingIntent replacement makes this safe on every process start;
        // BOOT_COMPLETED also restores alarms when the process was not already running.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            ScheduledComposeScheduler.restorePending(applicationContext)
        }
    }
}
