package com.mtracker.app

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Disabled in every shipped build. Arthak never reads notification text, amounts or merchant names.
 * Payment prompts come only from "which payment app was opened" usage events, entered manually in the app.
 */
class PaymentNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) = Unit
}
