/*
 * Copyright (C) 2020 Nain57
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; If not, see <http://www.gnu.org/licenses/>.
 */
package com.buzbuz.smartautoclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Path
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat

import com.buzbuz.smartautoclicker.database.ClickInfo
import com.buzbuz.smartautoclicker.database.ClickScenario
import com.buzbuz.smartautoclicker.activity.ScenarioActivity
import com.buzbuz.smartautoclicker.model.DetectorModel
import com.buzbuz.smartautoclicker.overlays.MainMenu
import com.buzbuz.smartautoclicker.baseui.overlays.OverlayController

/**
 * AccessibilityService implementation for the SmartAutoClicker.
 *
 * Started automatically by Android once the user has defined this service has an accessibility service, it provides
 * an API to start and stop the [DetectorModel] correctly in order to display the overlay UI and record the screen for
 * clicks detection.
 * This API is offered through the [LocalService] class, which is instantiated in the [LOCAL_SERVICE_INSTANCE] object.
 * This system is used instead of the usual binder interface because an [AccessibilityService] already has its own
 * binder and it can't be changed. To access this local service, use [getLocalService].
 *
 * We need this service to be an accessibility service in order to inject the detected clicks on the currently
 * displayed activity. This injection is made by the [performClick] method, which is called everytime a [ClickInfo] has
 * been detected.
 */
class SmartAutoClickerService : AccessibilityService() {

    companion object {
        /** The identifier for the foreground notification of this service. */
        private const val NOTIFICATION_ID = 42
        /** The channel identifier for the foreground notification of this service. */
        private const val NOTIFICATION_CHANNEL_ID = "SmartAutoClickerService"
        /** The instance of the [LocalService], providing access for this service to the Activity. */
        private var LOCAL_SERVICE_INSTANCE: LocalService? = null
            set(value) {
                field = value
                LOCAL_SERVICE_CALLBACK?.invoke(field)
            }
        /** Callback upon the availability of the [LOCAL_SERVICE_INSTANCE]. */
        private var LOCAL_SERVICE_CALLBACK: ((LocalService?) -> Unit)? = null
            set(value) {
                field = value
                value?.invoke(LOCAL_SERVICE_INSTANCE)
            }

        /**
         * Static method allowing an activity to register a callback in order to monitor the availability of the
         * [LocalService]. If the service is already available upon registration, the callback will be immediately
         * called.
         *
         * @param stateCallback the object to be notified upon service availability.
         */
        fun getLocalService(stateCallback: ((LocalService?) -> Unit)?) {
            LOCAL_SERVICE_CALLBACK = stateCallback
        }
    }

    /** The root controller for the overlay ui. */
    private var rootOverlayController: OverlayController? = null
    /** True if the overlay is started, false if not. */
    private var isStarted: Boolean = false

    /** Local interface providing an API for the [SmartAutoClickerService]. */
    inner class LocalService {

        /**
         * Start the overlay UI and instantiates the detection objects.
         *
         * This requires the media projection permission code and its data intent, they both can be retrieved using the
         * results of the activity intent provided by [MediaProjectionManager.createScreenCaptureIntent] (this Intent
         * shows the dialog warning about screen recording privacy). Any attempt to call this method without the
         * correct screen capture intent result will leads to a crash.
         *
         * @param resultCode the result code provided by the screen capture intent activity result callback
         * [android.app.Activity.onActivityResult]
         * @param data the data intent provided by the screen capture intent activity result callback
         * [android.app.Activity.onActivityResult]
         * @param scenario the identifier of the scenario of clicks to be used for detection.
         */
        fun start(resultCode: Int, data: Intent, scenario: ClickScenario) {
            if (isStarted) {
                return
            }

            isStarted = true
            startForeground(NOTIFICATION_ID, createNotification(scenario.name))
            DetectorModel.attach(this@SmartAutoClickerService)
            DetectorModel.get().init(this@SmartAutoClickerService, resultCode, data, scenario)
            rootOverlayController = MainMenu(this@SmartAutoClickerService, ::performClick).apply {
                create(::stop)
            }
        }

        /** Stop the overlay UI and release all associated resources. */
        fun stop() {
            if (!isStarted) {
                return
            }

            isStarted = false
            rootOverlayController?.dismiss()
            DetectorModel.get().stop()
            DetectorModel.detach()
            stopForeground(true)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        LOCAL_SERVICE_INSTANCE = LocalService()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        LOCAL_SERVICE_INSTANCE?.stop()
        LOCAL_SERVICE_INSTANCE = null
        return super.onUnbind(intent)
    }

    /**
     * Create the notification for this service allowing it to be set as foreground service.
     *
     * @param scenarioName the name to de displayed in the notification title
     *
     * @return the newly created notification.
     */
    private fun createNotification(scenarioName: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager!!.createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW
                )
            )
        }

        val intent = Intent(this, ScenarioActivity::class.java)
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title, scenarioName))
            .setContentText(getString(R.string.notification_message))
            .setContentIntent(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
            .setSmallIcon(R.drawable.ic_notification)
            .build()
    }

    /**
     * Perform the provided click on the current activity.
     *
     * @param click the click to be performed.
     */
    private fun performClick(click: ClickInfo) {
        val clickPath = Path()
        val clickBuilder = GestureDescription.Builder()

        clickPath.moveTo(click.from!!.x.toFloat(), click.from!!.y.toFloat())
        when (click.type) {
            ClickInfo.SINGLE -> {
                clickBuilder.addStroke(StrokeDescription(clickPath, 0, 1))
            }
            ClickInfo.SWIPE -> {
                clickPath.lineTo(click.to!!.x.toFloat(), click.to!!.y.toFloat())
                clickBuilder.addStroke(StrokeDescription(clickPath, 0, 175))
            }
        }

        dispatchGesture(clickBuilder.build(), null, null)
    }

    override fun onInterrupt() { /* Unused */ }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* Unused */ }
}
