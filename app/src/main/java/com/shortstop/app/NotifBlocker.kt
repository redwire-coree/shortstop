package com.shortstop.app

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Hides notifications from blocked apps while focus is on. Needs "Notification access" turned on once. */
class NotifBlocker : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        try {
            if (!Store.blockNotifs(this) || !Store.hard(this)) return
            val pkg = n.packageName
            if (pkg == packageName || n.isOngoing) return
            if (pkg in Store.focusApps(this) || pkg in Store.blockedApps(this)) cancelNotification(n.key)
        } catch (e: Exception) { }
    }
}
