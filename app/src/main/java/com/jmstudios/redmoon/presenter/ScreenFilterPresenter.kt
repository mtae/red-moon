/*
 * Copyright (c) 2016  Marien Raat <marienraat@riseup.net>
 * Copyright (c) 2017  Stephen Michel <s@smichel.me>
 *
 *  This file is free software: you may copy, redistribute and/or modify it
 *  under the terms of the GNU General Public License as published by the Free
 *  Software Foundation, either version 3 of the License, or (at your option)
 *  any later version.
 *
 *  This file is distributed in the hope that it will be useful, but WITHOUT ANY
 *  WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 *  FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more
 *  details.
 *
 *  You should have received a copy of the GNU General Public License along with
 *  this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * This file incorporates work covered by the following copyright and
 * permission notice:
 *
 *     Copyright (c) 2015 Chris Nguyen
 *     Copyright (c) 2016 Zoraver <https://github.com/Zoraver>
 *
 *     Permission to use, copy, modify, and/or distribute this software
 *     for any purpose with or without fee is hereby granted, provided
 *     that the above copyright notice and this permission notice appear
 *     in all copies.
 *
 *     THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL
 *     WARRANTIES WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED
 *     WARRANTIES OF MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE
 *     AUTHOR BE LIABLE FOR ANY SPECIAL, DIRECT, INDIRECT, OR
 *     CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS
 *     OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT,
 *     NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR IN
 *     CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
 */

package com.jmstudios.redmoon.presenter

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.support.v4.app.NotificationCompat
import android.support.v4.content.ContextCompat

import com.jmstudios.redmoon.R

import com.jmstudios.redmoon.activity.MainActivity
import com.jmstudios.redmoon.event.*
import com.jmstudios.redmoon.helper.EventBus
import com.jmstudios.redmoon.helper.Logger
import com.jmstudios.redmoon.helper.Permission
import com.jmstudios.redmoon.manager.BrightnessManager
import com.jmstudios.redmoon.manager.CurrentAppMonitor
import com.jmstudios.redmoon.manager.WindowViewManager
import com.jmstudios.redmoon.model.Config
import com.jmstudios.redmoon.model.ProfilesModel
import com.jmstudios.redmoon.receiver.NextProfileCommandReceiver
import com.jmstudios.redmoon.receiver.OrientationChangeReceiver
import com.jmstudios.redmoon.receiver.SwitchAppWidgetProvider
import com.jmstudios.redmoon.service.ScreenFilterService
import com.jmstudios.redmoon.service.ScreenFilterService.Command
import com.jmstudios.redmoon.service.ServiceLifeCycleController
import com.jmstudios.redmoon.util.*

import org.greenrobot.eventbus.Subscribe

class ScreenFilterPresenter(private val mContext: Context,
                            private val mServiceController: ServiceLifeCycleController,
                            private val mWindowViewManager: WindowViewManager,
                            private val mCurrentAppMonitor: CurrentAppMonitor,
                            private val mBrightnessManager: BrightnessManager) :
                        OrientationChangeReceiver.OnOrientationChangeListener {

    private val mOnState      = OnState()
    private val mOffState     = OffState()
    private val mPauseState   = PauseState()
    private val mPreviewState = PreviewState()
    private val mSuspendState = SuspendState()
    private val mFadeInState  = FadeInState()
    private val mFadeOutState = FadeOutState()

    private var mCurrentState: State = mOffState

    init {
        Log.i("Initializing")
        Config.filterIsOn = mCurrentState.filterIsOn
        EventBus.register(mCurrentState)
    }

    fun handleCommand(command: Command) = mCurrentState.handleCommand(command)
    fun updateWidgets() = mCurrentState.updateWidgets()

    override fun onPortraitOrientation()  = mWindowViewManager.reLayout()
    override fun onLandscapeOrientation() = mWindowViewManager.reLayout()

    // TODO: Clean up everything
    private abstract inner class State {

        abstract val filterIsOn: Boolean

        open protected val notificationContentText
            get() = ProfilesModel.getProfileName(Config.profile)

        protected val notification: NotificationCompat.Builder
            get() = NotificationCompat.Builder(mContext).apply {
                // Set notification appearance
                setSmallIcon(R.drawable.notification_icon_half_moon)
                color    = ContextCompat.getColor(mContext, R.color.color_primary)
                priority = Notification.PRIORITY_MIN

                if (belowAPI(24)) { setContentTitle(getString(R.string.app_name)) }
                setContentText(notificationContentText)

                // Open Red Moon when tapping notification body
                val mainActivityIntent = Intent(mContext, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                setContentIntent(PendingIntent.getActivity(mContext, REQUEST_CODE_ACTION_SETTINGS,
                                            mainActivityIntent, PendingIntent.FLAG_UPDATE_CURRENT))

                // Add toggle action
                val (toggleIcon, toggleTextResId, toggleIntent) = if (filterIsOn) {
                    Triple(R.drawable.ic_stop_circle_outline_white_36dp,
                           R.string.notification_action_turn_off,
                           ScreenFilterService.intent(Command.PAUSE))
                } else {
                    Triple(R.drawable.ic_play,
                           R.string.notification_action_turn_on,
                           ScreenFilterService.intent(Command.ON))
                }

                val togglePI = PendingIntent.getService(mContext, REQUEST_CODE_ACTION_TOGGLE,
                                                        toggleIntent,
                                                        PendingIntent.FLAG_UPDATE_CURRENT)
                addAction(toggleIcon, getString(toggleTextResId), togglePI)

                val deletePI = PendingIntent.getService(mContext, REQUEST_CODE_ACTION_STOP,
                                                        ScreenFilterService.intent(Command.OFF),
                                                        PendingIntent.FLAG_UPDATE_CURRENT)
                setDeleteIntent(deletePI)

                // Add profile switch action
                val nextProfileText = getString(R.string.notification_action_next_filter)
                val nextProfileIntent = Intent(mContext, NextProfileCommandReceiver::class.java)
                val nextProfilePI = PendingIntent.getBroadcast(mContext, REQUEST_CODE_NEXT_PROFILE,
                                                               nextProfileIntent, 0)
                addAction(R.drawable.ic_skip_next_white_36dp, nextProfileText, nextProfilePI)
            }

        open protected fun onActivation(prevState: State) {
            Log.i("super($this).onActivation($prevState)")
            EventBus.register(this)
            Config.filterIsOn = filterIsOn
            refreshNotification()
        }

        open internal fun nextState(command: Command): State = when (command) {
            Command.OFF           -> mOffState
            Command.ON            -> mOnState
            Command.PAUSE         -> mPauseState
            Command.SHOW_PREVIEW  -> mPreviewState
            Command.START_SUSPEND -> mSuspendState
            Command.TOGGLE        -> if (filterIsOn) mOffState else mOnState
            Command.FADE_ON       -> mFadeInState
            Command.FADE_OFF      -> mFadeOutState
            else                  -> this
        }

        open internal fun handleCommand(command: Command) {
            moveToState(nextState(command))
        }

        protected fun moveToState(newState: State) {
            if (!Permission.Overlay.isGranted) {
                Log.i("No overlay permission.")
                EventBus.post(overlayPermissionDenied())
            } else if (newState !== this) {
                Log.i("Transitioning from $this to $newState")
                EventBus.unregister(this)
                mCurrentState = newState
                mCurrentState.onActivation(this)
            }
        }

        @Subscribe open fun onProfileChanged(event: profileChanged) = refreshNotification()

        @Subscribe fun onPowerStateChanged(event: filterIsOnChanged) {
            updateWidgets()
            // If an app like Tasker wants to do something each time
            // Red Moon is toggled, it can listen for this event
            val intent = Intent()
            intent.action = BROADCAST_ACTION
            intent.putExtra(BROADCAST_FIELD, filterIsOn)
            mContext.sendBroadcast(intent)
        }

        open protected fun refreshNotification() {
            Log.d("Creating notification while in $this")
            val nm = mContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, notification.build())
        }

        internal fun updateWidgets() {
            //Broadcast to keep appwidgets in sync
            Log.i("Sending update broadcast")
            val updateAppWidgetIntent = Intent(mContext, SwitchAppWidgetProvider::class.java)
            updateAppWidgetIntent.action = SwitchAppWidgetProvider.ACTION_UPDATE
            updateAppWidgetIntent.putExtra(SwitchAppWidgetProvider.EXTRA_POWER, filterIsOn)
            mContext.sendBroadcast(updateAppWidgetIntent)
        }

        override fun toString(): String = javaClass.simpleName
    }

    private open inner class OnState : State() {
        override val filterIsOn = true

        override fun onActivation(prevState: State) {
            super.onActivation(prevState)

            Log.i("Active profile is $activeProfile")
            mWindowViewManager.open(FADE_DURATION_LONG)

            if (Config.lowerBrightness) { mBrightnessManager.lower() }
            if (Config.secureSuspend)   { mCurrentAppMonitor.start() }
        }

        override fun refreshNotification() {
            Log.d("Creating a persistent notification")
            mServiceController.startForeground(NOTIFICATION_ID, notification.build())
        }

        @Subscribe override fun onProfileChanged(event: profileChanged) {
            mWindowViewManager.open(FADE_DURATION_SHORT)
            refreshNotification()
        }

        @Subscribe fun onLowerBrightnessChanged(event: lowerBrightnessChanged) {
            Log.i("LowerBrightnessChanged")
            if (!Permission.WriteSettings.isGranted) {
                EventBus.post(changeBrightnessDenied())
                Log.i("BrightnessPermissionDenied")
            } else {
                val lower = Config.lowerBrightness
                Log.i("Lower brightness flag changed to: $lower")
                mBrightnessManager.run { if (lower) lower() else restore() }
            }
        }

        @Subscribe fun onSecureSuspendChanged(event: secureSuspendChanged) {
            mCurrentAppMonitor.run { if (Config.secureSuspend) start() else stop() }
        }

        @Subscribe fun onButtonBacklightChanged(event: buttonBacklightChanged) {
            mWindowViewManager.open()
        }
    }

    private inner class FadeInState : OnState() {
        override fun onActivation(prevState: State) {
            super.onActivation(prevState)
            mWindowViewManager.open(FADE_TRANSITION)
            if (Config.lowerBrightness) { mBrightnessManager.lower() }
            if (Config.secureSuspend)   { mCurrentAppMonitor.start() }
        }
    }

    private inner class FadeOutState : OnState() {
        override fun onActivation(prevState: State) {
            super.onActivation(prevState)
            mWindowViewManager.close(FADE_TRANSITION) {
                moveToState(mOffState)
            }
        }
    }

    private inner class OffState : State() {
        override val filterIsOn = false

        override fun onActivation(prevState: State) {
            super.onActivation(prevState)

            val len = if (prevState === mPreviewState) FADE_DURATION_INSTANT else FADE_DURATION_LONG
            mWindowViewManager.close(len)
            
            if (Config.lowerBrightness) { mBrightnessManager.restore() }
            if (Config.secureSuspend  ) { mCurrentAppMonitor.stop()    }

            val uiOpen = EventBus.getSticky(MainActivity.UI::class)?.isOpen ?: false
            if (!uiOpen) { mServiceController.stopSelf() }
        }

        override fun refreshNotification() {
            mServiceController.stopForeground(false)
            super.refreshNotification()
        }

        @Subscribe(sticky = true)
        fun stopServiceWhenUICloses(ui: MainActivity.UI) {
            if (!ui.isOpen) {
                EventBus.removeSticky(ui)
                mServiceController.stopSelf()
            }
        }
    }

    private inner class PauseState : State() {
        override val filterIsOn = false

        override val notificationContentText = getString(R.string.notification_status_paused)

        override fun onActivation(prevState: State) {
            super.onActivation(prevState)

            mWindowViewManager.close(FADE_DURATION_LONG)
            
            if (Config.lowerBrightness) { mBrightnessManager.restore() }
            if (Config.secureSuspend  ) { mCurrentAppMonitor.stop()    }
        }

        override fun refreshNotification() {
            mServiceController.stopForeground(false)
            super.refreshNotification()
        }
    }

    private abstract inner class TempState : State() {
        lateinit var mPrevState: State

        override val filterIsOn: Boolean
            get() = mPrevState.filterIsOn

        override fun onActivation(prevState: State) {
            mPrevState = prevState
            super.onActivation(mPrevState)
        }

        override fun nextState(command: Command): State = mPrevState.nextState(command)
        override fun handleCommand(command: Command) { mPrevState = nextState(command) }
    }

    /* This State is used to present the filter to the user when (s)he
     * is holding one of the seekbars to adjust the filter. It turns
     * on the filter and saves what state it should be when it will be
     * turned off.
     */
    private inner class PreviewState : TempState() {
        var pressesActive: Int = 0

        override fun onActivation(prevState: State) {
            pressesActive = 1
            super.onActivation(prevState)
            mWindowViewManager.open(FADE_DURATION_INSTANT)
        }

        override fun handleCommand(command: Command) {
            Log.d("Preview, got command: " + command.name)
            when (command) {
                Command.SHOW_PREVIEW -> {
                    pressesActive++
                    Log.d(String.format("%d presses active", pressesActive))
                }
                Command.HIDE_PREVIEW -> {
                    pressesActive--
                    Log.d(String.format("%d presses active", pressesActive))
                    if (pressesActive <= 0) {
                        Log.d("Moving back to state: $mPrevState")
                        moveToState(mPrevState)
                    }
                }
                else -> super.handleCommand(command)
            }
        }

        @Subscribe fun onColorChanged    (event: colorChanged    ) = mWindowViewManager.open()
        @Subscribe fun onIntensityChanged(event: intensityChanged) = mWindowViewManager.open()
        @Subscribe fun onDimChanged      (event: dimLevelChanged ) = mWindowViewManager.open()
    }

    /* This state is used when the filter is suspended temporarily,
     * because the user is in an excluded app (for example the package
     * installer). It stops the filter like in the OffState, but
     * doesn't change the UI, switch or brightness state just like the
     * PreviewState. Like the PreviewState, it logs changes to the
     * state and applies them when the suspend state is deactivated.
     */
    private inner class SuspendState : TempState() {

        override val notificationContentText = getString(R.string.notification_status_paused)

        override fun onActivation(prevState: State) {
            mWindowViewManager.close(FADE_DURATION_SHORT)
            super.onActivation(prevState)
        }

        override fun handleCommand(command: Command) {
            Log.d("In Suspend, got command: " + command.name)
            when (command) {
                Command.STOP_SUSPEND  -> moveToState(mPrevState)
                Command.START_SUSPEND -> {}
                else -> super.handleCommand(command)
            }
        }
    }

    companion object : Logger() {
        const val NOTIFICATION_ID = 1
        private const val REQUEST_CODE_ACTION_SETTINGS = 1000
        private const val REQUEST_CODE_ACTION_TOGGLE   = 3000
        private const val REQUEST_CODE_NEXT_PROFILE    = 4000
        private const val REQUEST_CODE_ACTION_STOP     = 5000

        const val FADE_TRANSITION = 3600000 // One hour
        const val FADE_DURATION_LONG = 1000 // One second
        const val FADE_DURATION_SHORT = 250
        const val FADE_DURATION_INSTANT = 0

        const val BROADCAST_ACTION = "com.jmstudios.redmoon.RED_MOON_TOGGLED"
        const val BROADCAST_FIELD  = "jmstudios.bundle.key.FILTER_IS_ON"

        // Statically used by BootReceiver
    }
}
