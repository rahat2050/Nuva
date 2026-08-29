package com.nuva.assistant

import android.app.Application
import com.nuva.assistant.core.NuvaContainer

/** Application entry — initializes dependencies only; it starts no background work. */
class NuvaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        NuvaContainer.init(this)

        // Do not start microphone or draft-restore work here. The process may
        // have been created by BOOT_COMPLETED or another background component.
        // BootReceiver owns reboot/update restore; MainActivity handles a
        // normal visible relaunch (including recovery after force-stop).
    }
}
