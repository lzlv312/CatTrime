// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.daemon

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.osfans.trime.R
import com.osfans.trime.TrimeApplication
import com.osfans.trime.core.CompositionProto
import com.osfans.trime.core.Rime
import com.osfans.trime.core.RimeApi
import com.osfans.trime.core.RimeLifecycle
import com.osfans.trime.core.RimeLifecycleObserver
import com.osfans.trime.core.RimeMessage
import com.osfans.trime.core.RimeSchema
import com.osfans.trime.core.StatusProto
import com.osfans.trime.core.lifecycleScope
import com.osfans.trime.core.whenReady
import com.osfans.trime.ui.main.LogActivity
import com.osfans.trime.util.appContext
import com.osfans.trime.util.createNotificationChannel
import com.osfans.trime.util.readText
import com.osfans.trime.util.subprocess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import splitties.systemservices.notificationManager
import timber.log.Timber
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Manage the singleton instance of [Rime]
 *
 * To use rime, client should call [createSession] to obtain a [RimeSession],
 * and call [destroySession] on client destroyed. Client should not leak the instance of [RimeApi],
 * and must use [RimeSession] to access rime functionalities.
 *
 * The instance of [Rime] always exists,but whether the dispatcher runs and callback works depend on clients, i.e.
 * if no clients are connected, [Rime.finalize] will be called.
 *
 * Functions are thread-safe in this class.
 *
 * Adapted from [fcitx5-android/FcitxDaemon.kt](https://github.com/fcitx5-android/fcitx5-android/blob/364afb44dcf0d9e3db3d43a21a32601b2190cbdf/app/src/main/java/org/fcitx/fcitx5/android/daemon/FcitxDaemon.kt)
 */
object RimeDaemon {
    private val DEPLOY_WAKE_LOCK_TIMEOUT = 20.minutes

    private val RESTORE_OPTIONS_TIMEOUT = 10.seconds

    private val realRime by lazy { Rime() }

    private val rimeImpl by lazy {
        object : RimeApi by realRime {
            // These calls tear down the native session, so the next engine is
            // created with the schema resets and needs the saved options again.
            override suspend fun deploy() {
                realRime.deploy()
                requestOptionRestore()
            }

            override suspend fun updateConfig() {
                realRime.updateConfig()
                requestOptionRestore()
            }

            override suspend fun syncUserData(): Boolean = realRime.syncUserData().also { requestOptionRestore() }
        }
    }

    private val sessions = mutableMapOf<String, RimeSession>()

    private val lock = ReentrantLock()

    /**
     * Lock-free snapshots of [sessions], republished whenever the map changes.
     *
     * Callers that only need "some session" or "is this session still
     * established" read these instead of touching the mutable map: the keyboard
     * asks on every draw, and iterating a HashMap while another thread runs put
     * or remove is not safe.
     */
    @Volatile
    private var cachedSession: RimeSession? = null

    @Volatile
    private var establishedNames: Set<String> = emptySet()

    /** Republishes the snapshots above. Call while holding [lock]. */
    private fun publishSessionsLocked() {
        cachedSession = sessions.firstNotNullOfOrNull { it.value }
        establishedNames = sessions.keys.toSet()
    }

    private val deployWakeLock: PowerManager.WakeLock by lazy {
        (appContext.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Trime:RimeDeploy")
            .apply { setReferenceCounted(false) }
    }

    private fun acquireDeployWakeLock() {
        if (deployWakeLock.isHeld) deployWakeLock.release()
        deployWakeLock.acquire(DEPLOY_WAKE_LOCK_TIMEOUT.inWholeMilliseconds)
    }

    private fun releaseDeployWakeLock() {
        if (deployWakeLock.isHeld) deployWakeLock.release()
    }

    /**
     * Pending requests to re-apply saved options. Conflated so a burst of
     * triggers collapses into a single restore instead of one job per event.
     */
    private val restoreRequests = Channel<Unit>(Channel.CONFLATED)

    private fun establish(name: String) = object : RimeSession {
        private inline fun <T> ensureEstablished(block: () -> T) = if (name in establishedNames) {
            block()
        } else {
            throw IllegalStateException("Session $name is not established")
        }

        // Cached state: plain field reads, so they neither block nor reach the
        // native layer. Deliberately not gated by ensureEstablished() -- reading
        // a stale snapshot is harmless and keeps UI hot paths lock-free.
        override val status: StatusProto
            get() = rimeImpl.statusCached
        override val paging: Boolean
            get() = rimeImpl.paging
        override val hasMenu: Boolean
            get() = rimeImpl.hasMenu
        override val composition: CompositionProto
            get() = rimeImpl.compositionCached
        override val schema: RimeSchema
            get() = rimeImpl.schemaCached

        override fun <T> run(block: suspend RimeApi.() -> T): T = ensureEstablished {
            runBlocking { block(rimeImpl) }
        }

        override suspend fun <T> runOnReady(block: suspend RimeApi.() -> T): T = ensureEstablished {
            realRime.lifecycle.whenReady { block(rimeImpl) }
        }

        override fun runIfReady(block: suspend RimeApi.() -> Unit) {
            ensureEstablished {
                if (realRime.isReady) {
                    realRime.lifecycleScope.launch {
                        block(rimeImpl)
                    }
                }
            }
        }

        override val lifecycleScope: CoroutineScope
            get() = realRime.lifecycle.lifecycleScope
    }

    fun createSession(name: String): RimeSession = lock.withLock {
        if (name in sessions) {
            return@withLock sessions.getValue(name)
        }
        if (realRime.lifecycle.currentState == RimeLifecycle.State.STOPPED) {
            realRime.startup()
        }
        val session = establish(name)
        sessions[name] = session
        publishSessionsLocked()
        return@withLock session
    }

    fun destroySession(name: String): Unit = lock.withLock {
        if (name !in sessions) {
            return
        }
        sessions -= name
        publishSessionsLocked()
        if (sessions.isEmpty()) {
            realRime.finalize()
            releaseDeployWakeLock()
        }
    }

    /**
     * Reuse a session for remote service.
     *
     * Reads a lock-free snapshot rather than the session map: this is called
     * from the keyboard draw path.
     */
    fun getFirstSessionOrNull(): RimeSession? = cachedSession

    private const val CHANNEL_ID = "rime-daemon"
    private const val MESSAGE_ID = 2331
    private var restartId = 0

    private fun requestOptionRestore() {
        restoreRequests.trySend(Unit)
    }

    private suspend fun restoreSavedOptions() {
        if (!realRime.isReady) return
        try {
            // A job dropped by a concurrent RimeDispatcher.stop() would never
            // resume, so bound the wait to keep the worker alive.
            val restored =
                withTimeoutOrNull(RESTORE_OPTIONS_TIMEOUT) {
                    realRime.restoreSavedOptions()
                }
            if (restored == null) {
                Timber.w("Restoring saved options timed out")
            }
        } catch (e: IllegalStateException) {
            // Rime stopped between the readiness check and the dispatch; the
            // next READY event will request another restore.
            Timber.w(e, "Skip restoring saved options")
        }
    }

    init {
        createNotificationChannel(
            CHANNEL_ID,
            appContext.getString(R.string.rime_daemon),
        )
        TrimeApplication.getInstance().coroutineScope.launch {
            restoreRequests.consumeEach { restoreSavedOptions() }
        }
        TrimeApplication.getInstance().coroutineScope.launch {
            realRime.messageFlow.collect {
                handleRimeMessage(it)
                // Applying a schema resets its options again.
                if (it is RimeMessage.SchemaMessage) {
                    requestOptionRestore()
                }
            }
        }

        realRime.lifecycle.addObserver(
            RimeLifecycleObserver { state ->
                if (state == RimeLifecycle.State.READY) {
                    requestOptionRestore()
                }
            },
        )
    }

    private inline fun sendNotification(
        id: Int,
        buildAction: NotificationCompat.Builder.() -> Unit,
    ) {
        val builder =
            NotificationCompat
                .Builder(appContext, CHANNEL_ID)
                .setContentTitle(appContext.getString(R.string.rime_daemon))
        builder.buildAction()
        builder.build().let { notificationManager.notify(id, it) }
    }

    /**
     * Restart Rime instance to deploy while keep the session
     */
    fun restartRime(fullCheck: Boolean = false) = lock.withLock {
        val id = restartId++
        if (!fullCheck) {
            sendNotification(id) {
                setSmallIcon(R.drawable.ic_baseline_sync_24)
                setContentTitle(appContext.getString(R.string.rime_daemon))
                setContentText(appContext.getString(R.string.restarting_rime))
                setOngoing(true)
                setProgress(100, 0, true)
                setPriority(NotificationCompat.PRIORITY_HIGH)
            }
        }
        realRime.finalize()
        realRime.startup()
        // Block until Rime is ready to prevent crashes when accessing API
        runBlocking {
            realRime.lifecycle.whenReady {}
            delay(1000) // Add delay to allow notification to be visible
            notificationManager.cancel(id)
        }
    }

    private suspend fun handleRimeMessage(it: RimeMessage<*>) {
        if (it is RimeMessage.DeployMessage) {
            val buildNotification: NotificationCompat.Builder.() -> Unit
            when (it.data) {
                RimeMessage.DeployMessage.State.Start -> {
                    acquireDeployWakeLock()
                    buildNotification = {
                        setSmallIcon(R.drawable.ic_baseline_refresh_reversed_24)
                        setContentText(appContext.getString(R.string.deploy_progress))
                        setProgress(0, 0, true)
                        setOngoing(true)
                        setAutoCancel(false)
                        setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    }
                    withContext(Dispatchers.IO) { subprocess("logcat", "--clear") }
                }
                RimeMessage.DeployMessage.State.Success -> {
                    releaseDeployWakeLock()
                    buildNotification = {
                        setSmallIcon(R.drawable.ic_baseline_refresh_reversed_24)
                        setColor(Color.GREEN)
                        setContentText(appContext.getString(R.string.deploy_finish))
                        setOngoing(false)
                        setTimeoutAfter(3000L)
                        setAutoCancel(true)
                        setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    }
                }
                RimeMessage.DeployMessage.State.Failure -> {
                    releaseDeployWakeLock()
                    val intent =
                        Intent(appContext, LogActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            val log =
                                subprocess("logcat", "-v", "brief", "-s", "rime.trime:W", "-d")
                                    .readText()
                            putExtra(LogActivity.FROM_DEPLOY, true)
                            putExtra(LogActivity.DEPLOY_FAILURE_TRACE, log)
                        }
                    buildNotification = {
                        setSmallIcon(R.drawable.ic_baseline_warning_24)
                        setColor(Color.YELLOW)
                        setContentText(appContext.getString(R.string.view_deploy_failure_log))
                        setContentIntent(
                            PendingIntent.getActivity(
                                appContext,
                                0,
                                intent,
                                PendingIntent.FLAG_UPDATE_CURRENT or
                                    PendingIntent.FLAG_IMMUTABLE,
                            ),
                        )
                        setOngoing(false)
                        setAutoCancel(true)
                        setPriority(NotificationCompat.PRIORITY_HIGH)
                    }
                }
            }
            sendNotification(MESSAGE_ID, buildNotification)
        }
    }
}
