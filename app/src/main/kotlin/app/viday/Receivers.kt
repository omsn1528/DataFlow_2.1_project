package app.viday

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Fires only at window boundaries and, when alerts are on, during a counting window. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Eng.tick(context)
    }
}

/** Restores alarms and takes a fresh snapshot after reboot, app update or clock/time-zone change. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Eng.tick(context)
    }
}
