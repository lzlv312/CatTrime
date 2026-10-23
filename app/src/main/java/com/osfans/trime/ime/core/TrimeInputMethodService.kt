/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.core

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.RectF
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.FrameLayout
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.core.KeyModifiers
import com.osfans.trime.core.KeyValue
import com.osfans.trime.core.RimeApi
import com.osfans.trime.core.RimeKeyMapping
import com.osfans.trime.core.RimeMessage
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.prefs.PreferenceDelegate
import com.osfans.trime.data.prefs.PreferenceDelegateProvider
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.ime.composition.CandidatesView
import com.osfans.trime.ime.keyboard.InputFeedbackManager
import com.osfans.trime.receiver.RimeIntentReceiver
import com.osfans.trime.util.any
import com.osfans.trime.util.findSectionFrom
import com.osfans.trime.util.forceShowSelf
import com.osfans.trime.util.monitorCursorAnchor
import com.osfans.trime.util.styledFloat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import splitties.bitflags.hasFlag
import splitties.systemservices.clipboardManager
import splitties.systemservices.inputMethodManager
import timber.log.Timber

/** [輸入法][InputMethodService]主程序  */

open class TrimeInputMethodService : LifecycleInputMethodService() {
    private lateinit var rime: RimeSession
    private val jobs = Channel<Job>(capacity = Channel.UNLIMITED)

    private val prefs = AppPrefs.defaultInstance()
    private lateinit var decorView: View
    private lateinit var contentView: FrameLayout
    private lateinit var lastKnownConfig: Configuration
    private var inputView: InputView? = null
    private var candidatesView: CandidatesView? = null
    private val navBarManager = NavigationBarManager()
    private val inputDeviceManager = InputDeviceManager { useVirtualKeyboard, useCandidatesView ->
        postRimeJob {
            setCandidatePagingMode(useCandidatesView)
        }
        currentInputConnection?.monitorCursorAnchor(useCandidatesView)
        window.window?.let {
            navBarManager.evaluate(it, useVirtualKeyboard)
        }
    }
    private val rimeIntentReceiver = RimeIntentReceiver()

    private var lastCommittedText: String = ""

    private var composingText: String = ""

    private var cursorUpdateIndex = 0

    private val recreateInputViewPrefs: Array<PreferenceDelegate<*>> = arrayOf(
        prefs.keyboard.floatingKeyboard,
        // 横屏自动悬浮开关在 InputView 构造期参与「有效悬浮」判定，切换后必须
        // 重建输入视图才能生效（走监听器的 else 分支整体重建）。
        prefs.keyboard.floatingKeyboardAutoLandscape,
        prefs.keyboard.floatingKeyboardWidth,
        prefs.keyboard.floatingKeyboardHeight,
        prefs.keyboard.floatingKeyboardKeepRatio,
        // 拖动条位置（顶部/底部）在 InputView 构建时读取并决定标题栏摆放，
        // 切换后必须整体重建输入视图才能实时生效。
        prefs.keyboard.floatingKeyboardBarPosition,
        prefs.keyboard.floatingKeyboardCornerRadius,
        prefs.keyboard.floatingPreeditOffsetX,
        prefs.keyboard.floatingPreeditOffsetY,
        prefs.keyboard.expandKeypressArea,
        prefs.keyboard.hideKeySymbol,
        prefs.keyboard.hideKeyHint,
        prefs.keyboard.hideInputBar,
        prefs.advanced.ignoreSystemGestureInsets,
    )

    @Keep
    private val recreateInputViewListener =
        PreferenceDelegate.OnChangeListener<Any> { key, _ ->
            if (updatingFloatingKeyboardSize) return@OnChangeListener
            when (key) {
                // 悬浮键盘开关直接整体重建输入视图：状态一律从偏好重新派生，
                // 避免原位迁移留下的半应用状态（曾导致开关有时无法关闭）。
                AppPrefs.Keyboard.FLOATING_KEYBOARD -> replaceInputView(ThemeManager.activeTheme)

                AppPrefs.Keyboard.HIDE_INPUT_BAR -> {
                    if (inputView != null) {
                        inputView?.refreshHideInputBar()
                    } else {
                        replaceInputView(ThemeManager.activeTheme)
                    }
                }

                AppPrefs.Keyboard.FLOATING_KEYBOARD_WIDTH,
                AppPrefs.Keyboard.FLOATING_KEYBOARD_HEIGHT,
                -> {
                    syncKeepRatioDimension(key)
                    replaceInputView(ThemeManager.activeTheme)
                }

                // 等比开关切换：开启时先把高度对齐到宽度档位（等比以宽度档位为准），
                // 再重建输入视图让 applyFloatingScale 立即按新开关重算缩放，实时生效。
                AppPrefs.Keyboard.FLOATING_KEYBOARD_KEEP_RATIO -> {
                    syncKeepRatioDimension(AppPrefs.Keyboard.FLOATING_KEYBOARD_WIDTH)
                    replaceInputView(ThemeManager.activeTheme)
                }

                else -> replaceInputView(ThemeManager.activeTheme)
            }
        }

    /** Guard against the linked-dimension write-back re-entering [syncKeepRatioDimension]. */
    private var updatingKeepRatioDimension = false

    /**
     * 等比缩放开启时宽高是同一个值：任意拖动其中一个滑条，另一个立即同步为相同数字
     * （宽高滑条范围已统一为 40-100，联动值天然落在双方都可表达的区间内）；
     * 关闭等比时宽高各自独立。
     */
    private fun syncKeepRatioDimension(changedKey: String) {
        if (updatingKeepRatioDimension) return
        if (!prefs.keyboard.floatingKeyboardKeepRatio.getValue()) return
        val widthPref = prefs.keyboard.floatingKeyboardWidth
        val heightPref = prefs.keyboard.floatingKeyboardHeight
        updatingKeepRatioDimension = true
        try {
            when (changedKey) {
                AppPrefs.Keyboard.FLOATING_KEYBOARD_WIDTH -> {
                    val height = widthPref.getValue()
                    if (heightPref.getValue() != height) heightPref.setValue(height)
                }
                AppPrefs.Keyboard.FLOATING_KEYBOARD_HEIGHT -> {
                    val width = heightPref.getValue()
                    if (widthPref.getValue() != width) widthPref.setValue(width)
                }
            }
        } finally {
            updatingKeepRatioDimension = false
        }
    }

    private var updatingFloatingKeyboardSize = false

    /** Save both dimensions without rebuilding the currently scaled keyboard view. */
    fun updateFloatingKeyboardSize(widthPercent: Int, heightPercent: Int) {
        val widthPref = prefs.keyboard.floatingKeyboardWidth
        val heightPref = prefs.keyboard.floatingKeyboardHeight
        if (widthPref.getValue() == widthPercent && heightPref.getValue() == heightPercent) return
        updatingFloatingKeyboardSize = true
        try {
            widthPref.sharedPreferences.edit {
                putInt(widthPref.key, widthPercent)
                putInt(heightPref.key, heightPercent)
            }
        } finally {
            updatingFloatingKeyboardSize = false
        }
    }

    @Keep
    private val recreateCandidatesViewListener =
        PreferenceDelegateProvider.OnChangeListener { key ->
            replaceCandidateView(ThemeManager.activeTheme)
            if (key == AppPrefs.Candidates.MODE) {
                // 候选栏与候选窗口切换时清理当前输入，避免残留的候选/预编辑引发意外行为
                postRimeJob { clearComposition() }
            }
        }

    @Keep
    private val onThemeChangeListener =
        ThemeManager.OnThemeChangeListener {
            replaceInputViews(it)
        }

    @Keep
    private val onColorChangeListener =
        ColorManager.OnColorChangeListener {
            ContextCompat.getMainExecutor(this).execute {
                replaceInputViews(it)
            }
        }

    private fun postJob(
        scope: CoroutineScope,
        block: suspend () -> Unit,
    ): Job {
        val job = scope.launch(start = CoroutineStart.LAZY) { block() }
        jobs.trySend(job)
        return job
    }

    /**
     * Post a rime operation to [jobs] to be executed
     *
     * Unlike `rime.runOnReady` or `rime.launchOnReady` where
     * subsequent operations can start if the prior operation is not finished (suspended),
     * [postRimeJob] ensures that operations are executed sequentially.
     */
    fun postRimeJob(block: suspend RimeApi.() -> Unit) = postJob(rime.lifecycleScope) { rime.runOnReady(block) }

    private suspend fun updateRimeOption(api: RimeApi) {
        try {
            api.setRuntimeOption("soft_cursor", prefs.keyboard.useSoftCursor.getValue()) // 軟光標
        } catch (e: Exception) {
            Timber.e(e)
        }
    }

    private fun registerReceiver() {
        val intentFilter =
            IntentFilter().apply {
                addAction(RimeIntentReceiver.ACTION_DEPLOY)
                addAction(RimeIntentReceiver.ACTION_SYNC_USER_DATA)
            }
        ContextCompat.registerReceiver(
            this,
            rimeIntentReceiver,
            intentFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onCreate() {
        rime = RimeDaemon.createSession(javaClass.name)
        lifecycleScope.launch {
            jobs.consumeEach { it.join() }
        }
        lifecycleScope.launch {
            rime.run { messageFlow }.collect {
                handleRimeMessage(it)
            }
        }
        recreateInputViewPrefs.forEach {
            it.registerOnChangeListener(recreateInputViewListener)
        }
        prefs.candidates.registerOnChangeListener(recreateCandidatesViewListener)
        // ensure theme and color managers are initialized after rime is ready
        lifecycleScope.launch {
            rime.runOnReady {
                ThemeManager.init(resources.configuration)
                // The listeners live in static managers, and onDestroy has already
                // removed this service's listeners by now -- registering after that
                // would leak them for the rest of the process lifetime.
                if (destroyed) return@runOnReady
                ThemeManager.addOnChangedListener(onThemeChangeListener)
                ColorManager.addOnChangedListener(onColorChangeListener)
            }
        }
        InputFeedbackManager.init(this)
        registerReceiver()
        super.onCreate()
        Timber.d("onCreate")
        decorView = window.window!!.decorView
        contentView = decorView.findViewById(android.R.id.content)
        lastKnownConfig = Configuration(resources.configuration)
    }

    private fun handleRimeMessage(it: RimeMessage<*>) {
        when (it) {
            is RimeMessage.CommitTextMessage -> {
                if (!it.data.text.isNullOrEmpty()) {
                    commitText(it.data.text)
                }
            }
            is RimeMessage.InlinePreeditMessage -> {
                updateComposingText(it.data)
            }
            is RimeMessage.KeyMessage ->
                it.data.let msg@{
                    if (it.isVirtual) {
                        when (it.value.value) {
                            RimeKeyMapping.RimeKey_Return -> handleReturnKey()
                            else -> {
                                val keyCode = it.value.keyCode
                                if (keyCode != KeyEvent.KEYCODE_UNKNOWN) {
                                    // recognized keyCode
                                    sendDownUpKeyEvent(
                                        keyCode,
                                        it.modifiers.metaState or meta(
                                            alt = it.modifiers.alt,
                                            shift = it.modifiers.shift,
                                            ctrl = it.modifiers.ctrl,
                                            meta = it.modifiers.meta,
                                        ),
                                    )
                                    if (it.modifiers.ctrl && keyCode == KeyEvent.KEYCODE_C) clearTextSelection()
                                } else {
                                    if (it.value.value > 0) {
                                        runCatching {
                                            commitText(Character.toString(it.value.value))
                                        }.getOrElse { t -> Timber.w(t, "Unhandled Virtual KeyEvent: $it") }
                                    } else {
                                        Timber.w("Unhandled Virtual KeyEvent: $it")
                                    }
                                }
                            }
                        }
                    } else {
                        val keyCode = it.value.keyCode
                        if (keyCode != KeyEvent.KEYCODE_UNKNOWN) {
                            // recognized keyCode
                            val eventTime = SystemClock.uptimeMillis()
                            if (it.modifiers.release) {
                                sendUpKeyEvent(eventTime, keyCode, it.modifiers.metaState)
                            } else {
                                sendDownKeyEvent(eventTime, keyCode, it.modifiers.metaState)
                            }
                        } else {
                            if (!it.modifiers.release && it.value.value > 0) {
                                runCatching {
                                    commitText(Character.toString(it.value.value))
                                }.getOrElse { t -> Timber.w(t, "Unhandled Rime KeyEvent: $it") }
                            } else {
                                Timber.w("Unhandled Rime KeyEvent: $it")
                            }
                        }
                    }
                }
            is RimeMessage.DeployMessage -> {
                if (it.data == RimeMessage.DeployMessage.State.Success) {
                    ThemeManager.selectTheme(ThemeManager.prefs.selectedTheme.getValue())
                }
            }
            else -> {}
        }
    }

    private fun replaceInputView(theme: Theme): InputView {
        val newInputView = InputView(this, rime, theme)
        setInputView(newInputView)
        inputDeviceManager.setInputView(newInputView)
        inputView = newInputView
        return newInputView
    }

    private fun replaceCandidateView(theme: Theme): CandidatesView {
        val newCandidatesView = CandidatesView(this, rime, theme)
        contentView.removeView(candidatesView)
        contentView.addView(newCandidatesView)
        inputDeviceManager.setCandidatesView(newCandidatesView)
        candidatesView = newCandidatesView
        if (decorLocationUpdated) {
            candidatesView?.updateCursorAnchor(anchorPosition, contentSize)
        } else {
            candidatesView?.updateCursorAnchor(contentSize)
        }
        // A rebuilt view starts ignorant of the keyboard next to it, so hand the current
        // bounds over rather than waiting for the next input-view layout.
        syncCandidatesObstruction()
        return newCandidatesView
    }

    private fun replaceInputViews(theme: Theme) {
        navBarManager.evaluate(window.window!!, inputDeviceManager.useVirtualKeyboard)
        replaceInputView(theme)
        replaceCandidateView(theme)
        inputView?.updateEnterKeyLabel(currentInputEditorInfo)
    }

    /**
     * Re-applies the candidate window mode to the views that are already on screen.
     *
     * The mode only decides what the *next* evaluation picks, so a toggle pressed while the
     * keyboard is up has to ask for one explicitly. This is the evaluation a tap on the input
     * view triggers, and tapping the toggle is exactly that kind of interaction.
     */
    fun refreshCandidatesViewMode() {
        inputDeviceManager.evaluateOnViewClicked(this)
        // Whether the window exists at all just changed, so does what it has to avoid.
        syncCandidatesObstruction()
    }

    private val contentLocationInWindow = intArrayOf(0, 0)
    private val floatingObstruction = RectF()

    /**
     * Hands [CandidatesView] the region it has to keep clear of.
     *
     * While the keyboard is docked, [updateDecorLocation] already confines the candidate
     * window to the area above the keyboard by reporting the keyboard's top as the parent
     * height. Nothing plays that role once the keyboard floats: it overlays the editor, so the
     * cursor anchor can land inside the keyboard and the window would be drawn over the keys.
     * [InputView.getFloatingKeyboardBoundsInWindow] is the same rectangle the window manager
     * gets as the touchable region, and it already includes the floating preedit.
     */
    private fun syncCandidatesObstruction() {
        val cv = candidatesView ?: return
        val iv = inputView
        val bounds = Rect()
        val floating =
            iv != null &&
                inputDeviceManager.useCandidatesView &&
                iv.isFloatingKeyboardEnabled &&
                iv.getFloatingKeyboardBoundsInWindow(bounds)
        if (!floating) {
            cv.setObstruction(null)
            return
        }
        contentView.getLocationInWindow(contentLocationInWindow)
        floatingObstruction.set(
            (bounds.left - contentLocationInWindow[0]).toFloat(),
            (bounds.top - contentLocationInWindow[1]).toFloat(),
            (bounds.right - contentLocationInWindow[0]).toFloat(),
            (bounds.bottom - contentLocationInWindow[1]).toFloat(),
        )
        cv.setObstruction(floatingObstruction)
    }

    /** Set once [onDestroy] has run; guards work that resumes after a suspension. */
    private var destroyed = false

    override fun onDestroy() {
        destroyed = true
        InputFeedbackManager.destroy()
        // The dialog borrows this service's window token, so leaving it up while
        // the service goes away leaks the window.
        dismissShowingDialog()
        inputView = null
        recreateInputViewPrefs.forEach {
            it.unregisterOnChangeListener(recreateInputViewListener)
        }
        prefs.candidates.unregisterOnChangeListener(recreateCandidatesViewListener)
        ThemeManager.removeOnChangedListener(onThemeChangeListener)
        ColorManager.removeOnChangedListener(onColorChangeListener)
        super.onDestroy()
        unregisterReceiver(rimeIntentReceiver)
        RimeDaemon.destroySession(javaClass.name)
    }

    private fun handleReturnKey() {
        currentInputEditorInfo.run {
            if (inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_NULL ||
                imeOptions.hasFlag(EditorInfo.IME_FLAG_NO_ENTER_ACTION)
            ) {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
                return
            }
            if (!actionLabel.isNullOrEmpty() && actionId != EditorInfo.IME_ACTION_UNSPECIFIED) {
                currentInputConnection.performEditorAction(actionId)
                return
            }
            when (val action = imeOptions and EditorInfo.IME_MASK_ACTION) {
                EditorInfo.IME_ACTION_UNSPECIFIED,
                EditorInfo.IME_ACTION_NONE,
                -> sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)

                else -> currentInputConnection.performEditorAction(action)
            }
        }
    }

    /**
     * https://github.com/fcitx5-android/fcitx5-android/blob/fe3a618c8fd18842305d2f8ec2880fcc67ec1679/app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt#L523-#L547
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        postRimeJob { clearComposition() }
        val keyboardUiModeMask = ActivityInfo.CONFIG_KEYBOARD or
            ActivityInfo.CONFIG_KEYBOARD_HIDDEN or
            ActivityInfo.CONFIG_UI_MODE
        val diff = lastKnownConfig.diff(newConfig)
        Timber.d("onConfigurationChanged diff=$diff")
        if (diff and keyboardUiModeMask != diff) {
            super.onConfigurationChanged(newConfig)
        }
        lastKnownConfig.setTo(newConfig)
    }

    private val contentSize = floatArrayOf(0f, 0f)
    private val decorLocation = floatArrayOf(0f, 0f)
    private val decorLocationInt = intArrayOf(0, 0)
    private var decorLocationUpdated = false

    /**
     * Re-derives the area the candidate window is placed against from the live hierarchy.
     *
     * Docked, that area ends at the top of the keyboard; floating, it is the whole content view
     * because the floating layer overlays the editor and the window is instead kept above the
     * keys by its obstruction. Both change after the window was last shown -- a taller keyboard,
     * a floating keyboard that moved -- so neither may be captured only once.
     */
    private fun updateCandidatesParentSize() {
        val iv = inputView
        contentSize[0] = contentView.width.toFloat()
        contentSize[1] =
            if (iv == null || iv.isFloatingKeyboardEnabled || !inputDeviceManager.useVirtualKeyboard) {
                contentView.height.toFloat()
            } else {
                iv.keyboardView.getLocationInWindow(inputViewLocation)
                inputViewLocation[1].toFloat()
            }
        if (contentSize[0] > 0 && contentSize[1] > 0) {
            candidatesView?.updateParentSize(contentSize)
        }
    }

    /**
     * Hands the candidate window the current keyboard geometry: where it may be placed, and
     * which part of the parent it must avoid.
     */
    fun refreshCandidatesGeometry() {
        updateCandidatesParentSize()
        syncCandidatesObstruction()
    }

    private fun updateDecorLocation() {
        updateCandidatesParentSize()
        decorView.getLocationOnScreen(decorLocationInt)
        decorLocation[0] = decorLocationInt[0].toFloat()
        decorLocation[1] = decorLocationInt[1].toFloat()
        // contentSize and decorLocation can be completely wrong,
        // when measuring right after the very first onStartInputView() of an IMS' lifecycle
        if (contentSize[0] > 0 && contentSize[1] > 0) {
            decorLocationUpdated = true
        }
    }

    private val anchorPosition = RectF()

    override fun onUpdateCursorAnchorInfo(info: CursorAnchorInfo) {
        val bounds = info.getCharacterBounds(0)
        // update anchorPosition
        if (bounds == null) {
            // composing is disabled in target app or trime settings
            // use the position of the insertion marker instead
            anchorPosition.top = info.insertionMarkerTop
            anchorPosition.left = info.insertionMarkerHorizontal
            anchorPosition.bottom = info.insertionMarkerBottom
            anchorPosition.right = info.insertionMarkerHorizontal
        } else {
            // for different writing system (e.g. right to left languages),
            // we have to calculate the correct RectF
            val horizontal = if (candidatesView?.layoutDirection == View.LAYOUT_DIRECTION_RTL) bounds.right else bounds.left
            anchorPosition.top = bounds.top
            anchorPosition.left = horizontal
            anchorPosition.bottom = bounds.bottom
            anchorPosition.right = horizontal
        }
        if (!decorLocationUpdated) {
            updateDecorLocation()
        }
        if (anchorPosition.any(Float::isNaN)) {
            candidatesView?.updateCursorAnchor(contentSize)
            return
        }
        info.matrix.mapRect(anchorPosition)
        val (dX, dY) = decorLocation
        anchorPosition.offset(-dX, -dY)
        candidatesView?.updateCursorAnchor(anchorPosition, contentSize)
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart,
            oldSelEnd,
            newSelStart,
            newSelEnd,
            candidatesStart,
            candidatesEnd,
        )
        cursorUpdateIndex += 1
        handleCursorUpdate(newSelStart, newSelEnd, candidatesStart, candidatesEnd, cursorUpdateIndex)
        inputView?.updateSelection(newSelStart, newSelEnd)
    }

    private fun handleCursorUpdate(
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
        updateIndex: Int,
    ) {
        if (newSelStart != newSelEnd) return
        if (candidatesStart == candidatesEnd) return
        if (newSelStart in candidatesStart..candidatesEnd) {
            val position = newSelStart - candidatesStart
            if (position != composingText.length) {
                postRimeJob {
                    if (updateIndex != cursorUpdateIndex) return@postRimeJob
                    Timber.d("handleCursorUpdate: move rime cursor to $position")
                    moveCursorPos(position)
                }
            }
        } else {
            Timber.d("handleCursorUpdate: clear composition")
            postRimeJob {
                clearComposition()
            }
        }
    }

    private val inputViewLocation = intArrayOf(0, 0)

    fun requestInputInsetsUpdate() {
        if (::decorView.isInitialized) {
            decorView.requestLayout()
        }
    }

    override fun onComputeInsets(outInsets: Insets) {
        if (inputDeviceManager.useVirtualKeyboard) {
            val currentInputView = inputView
            val floatingBounds = Rect()
            if (
                currentInputView?.isFloatingKeyboardEnabled == true &&
                currentInputView.getFloatingKeyboardBoundsInWindow(floatingBounds)
            ) {
                outInsets.apply {
                    // A floating keyboard overlays the editor instead of resizing it.
                    contentTopInsets = contentView.height
                    visibleTopInsets = contentView.height
                    touchableInsets = Insets.TOUCHABLE_INSETS_REGION
                    touchableRegion.set(floatingBounds)
                }
            } else {
                currentInputView?.keyboardView?.getLocationInWindow(inputViewLocation)
                outInsets.apply {
                    contentTopInsets = inputViewLocation[1]
                    visibleTopInsets = inputViewLocation[1]
                    touchableInsets = Insets.TOUCHABLE_INSETS_VISIBLE
                }
            }
        } else {
            val n = decorView.findViewById<View>(android.R.id.navigationBarBackground)?.height ?: 0
            val h = decorView.height - n
            outInsets.apply {
                contentTopInsets = h
                visibleTopInsets = h
                touchableInsets = Insets.TOUCHABLE_INSETS_VISIBLE
            }
        }
        if (showingDialog != null) {
            // 弹框窗口不可获取焦点（见 showDialog），收不到自身范围以外的触摸。输入法
            // 窗口必须整框可触摸，由弹框下方的遮罩接管空白处的点击；否则这些点击会
            // 落到键盘上（输入字符）或穿透到后面的应用。
            outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_FRAME
        }
        // This callback is the framework's, and InputView only asks for another round when the
        // floating keyboard's bounds actually changed, so it doubles as the signal that the
        // candidate window has to be re-placed relative to the keyboard -- and to learn the
        // keyboard's new top when its height changed.
        refreshCandidatesGeometry()
    }

    // always show InputView since we delegate CandidatesView's visibility to it
    @SuppressLint("MissingSuperCall")
    override fun onEvaluateInputViewShown() = true

    fun superEvaluateInputViewShown() = super.onEvaluateInputViewShown()

    override fun onCreateInputView(): View? {
        Timber.d("onCreateInputView")
        replaceInputViews(ThemeManager.activeTheme)
        // We will call `setInputView` by ourselves. This is fine.
        return null
    }

    override fun setInputView(view: View) {
        super.setInputView(view)
        val inputArea = contentView.findViewById<FrameLayout>(android.R.id.inputArea)
        inputArea.updateLayoutParams<ViewGroup.LayoutParams> {
            height = ViewGroup.LayoutParams.MATCH_PARENT
        }
        view.updateLayoutParams<ViewGroup.LayoutParams> {
            height = ViewGroup.LayoutParams.MATCH_PARENT
        }
    }

    override fun onConfigureWindow(
        win: Window,
        isFullscreen: Boolean,
        isCandidatesOnly: Boolean,
    ) {
        win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onStartInput(
        attribute: EditorInfo,
        restarting: Boolean,
    ) {
        composingText = ""
        Timber.d("onStartInput: restarting=$restarting")
        val isNullType = attribute.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_NULL
        postRimeJob {
            if (restarting) {
                // when input restarts in the same editor, clear previous composition
                clearComposition()
            }
            setNullInputType(isNullType)
        }
    }

    private val inlineSuggestions by prefs.general.inlineSuggestions

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onCreateInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest? {
        if (!inlineSuggestions || !inputDeviceManager.useVirtualKeyboard) return null
        return InlineSuggestions.createRequest(this)
    }

    @SuppressLint("NewApi")
    override fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean {
        if (!inputDeviceManager.useVirtualKeyboard) return false
        return inputView?.handleInlineSuggestions(response) == true
    }

    override fun onStartInputView(
        attribute: EditorInfo,
        restarting: Boolean,
    ) {
        Timber.d("onStartInputView: restarting=$restarting")
        InputFeedbackManager.startInput()
        postRimeJob {
            updateRimeOption(this)
        }
        val (useVirtualKeyboard, useCandidatesView) =
            inputDeviceManager.evaluateOnStartInputView(attribute, this)
        if (useVirtualKeyboard) {
            inputView?.startInput(attribute, restarting)
        }
        if (useCandidatesView) {
            if (currentInputConnection?.monitorCursorAnchor() != true) {
                if (!decorLocationUpdated) {
                    updateDecorLocation()
                }
                // anchor CandidatesView to bottom-left corner in case InputConnection does not
                // support monitoring CursorAnchorInfo
                candidatesView?.updateCursorAnchor(contentSize)
            }
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        Timber.d("onFinishInputView: finishingInput=$finishingInput")
        // A picker is only drawn while the keyboard is up, so it has to go with it.
        dismissShowingDialog()
        decorLocationUpdated = false
        inputView?.dismissCandidateActionMenu()
        candidatesView?.dismissCandidateActionMenu()
        inputDeviceManager.onFinishInputView()
        currentInputConnection?.apply {
            finishComposingText()
            monitorCursorAnchor(false)
        }
        composingText = ""
        postRimeJob {
            clearComposition()
        }
        InputFeedbackManager.finishInput()
    }

    fun commitText(text: String) {
        val ic = currentInputConnection ?: return

        // when composing text equals commit content, finish composing text as-is
        if (composingText.isNotEmpty() && composingText == text) {
            ic.finishComposingText()
        } else {
            ic.commitText(text, 1)
        }
        lastCommittedText = text
        composingText = ""
        InputFeedbackManager.textCommitSpeak(text)
    }

    /**
     * Constructs a meta state integer flag which can be used for setting the `metaState` field when sending a KeyEvent
     * to the input connection. If this method is called without a meta modifier set to true, the default value `0` is
     * returned.
     *
     * @param ctrl Set to true to enable the CTRL meta modifier. Defaults to false.
     * @param alt Set to true to enable the ALT meta modifier. Defaults to false.
     * @param shift Set to true to enable the SHIFT meta modifier. Defaults to false.
     *
     * @return An integer containing all meta flags passed and formatted for use in a [KeyEvent].
     */
    fun meta(
        alt: Boolean = false,
        ctrl: Boolean = false,
        shift: Boolean = false,
        meta: Boolean = false,
        sym: Boolean = false,
    ): Int {
        var metaState = 0
        if (alt) metaState = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        if (ctrl) metaState = metaState or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (shift) metaState = metaState or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        if (meta) metaState = metaState or KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
        if (sym) metaState = metaState or KeyEvent.META_SYM_ON
        return metaState
    }

    private fun sendDownKeyEvent(
        eventTime: Long,
        keyEventCode: Int,
        metaState: Int = 0,
    ): Boolean {
        val ic = currentInputConnection ?: return false
        return ic.sendKeyEvent(
            KeyEvent(
                eventTime,
                eventTime,
                KeyEvent.ACTION_DOWN,
                keyEventCode,
                0,
                metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD,
                0,
                KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE,
                InputDevice.SOURCE_KEYBOARD,
            ),
        )
    }

    private fun sendUpKeyEvent(
        eventTime: Long,
        keyEventCode: Int,
        metaState: Int = 0,
    ): Boolean {
        val ic = currentInputConnection ?: return false
        return ic.sendKeyEvent(
            KeyEvent(
                eventTime,
                SystemClock.uptimeMillis(),
                KeyEvent.ACTION_UP,
                keyEventCode,
                0,
                metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD,
                0,
                KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE,
                InputDevice.SOURCE_KEYBOARD,
            ),
        )
    }

    /**
     * Same as [InputMethodService.sendDownUpKeyEvents] but also allows to set meta state.
     *
     * @param keyEventCode The key code to send, use a key code defined in Android's [KeyEvent].
     * @param metaState Flags indicating which meta keys are currently pressed.
     *
     * @return True on success, false if an error occurred or the input connection is invalid.
     */
    fun sendDownUpKeyEvent(
        keyEventCode: Int,
        metaState: Int = meta(),
    ): Boolean {
        val eventTime = SystemClock.uptimeMillis()
        if (metaState and KeyEvent.META_ALT_ON != 0) {
            sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_ALT_LEFT)
        }
        if (metaState and KeyEvent.META_CTRL_ON != 0) {
            sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_CTRL_LEFT)
        }
        if (metaState and KeyEvent.META_SHIFT_ON != 0) {
            sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_SHIFT_LEFT)
        }
        if (metaState and KeyEvent.META_META_ON != 0) {
            sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_META_LEFT)
        }
        if (metaState and KeyEvent.META_SYM_ON != 0) {
            sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_SYM)
        }
        sendDownKeyEvent(eventTime, keyEventCode, metaState)
        sendUpKeyEvent(eventTime, keyEventCode, metaState)
        if (metaState and KeyEvent.META_SYM_ON != 0) {
            sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_SYM)
        }
        if (metaState and KeyEvent.META_META_ON != 0) {
            sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_META_LEFT)
        }
        if (metaState and KeyEvent.META_SHIFT_ON != 0) {
            sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_SHIFT_LEFT)
        }
        if (metaState and KeyEvent.META_CTRL_ON != 0) {
            sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_CTRL_LEFT)
        }
        if (metaState and KeyEvent.META_ALT_ON != 0) {
            sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_ALT_LEFT)
        }
        return true
    }

    private fun forwardKeyEvent(event: KeyEvent): Boolean {
        val keyVal = KeyValue.fromKeyEvent(event)
        if (keyVal.value != RimeKeyMapping.RimeKey_VoidSymbol) {
            val modifiers = KeyModifiers.fromKeyEvent(event)
            postRimeJob {
                processKey(keyVal, modifiers, isVirtual = false)
            }
            return true
        }
        Timber.d("Skipped KeyEvent: $event")
        return false
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        if (inputDeviceManager.evaluateOnKeyDown(event, this)) {
            decorLocationUpdated = false
            forceShowSelf()
        }
        return forwardKeyEvent(event) || super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean = forwardKeyEvent(event) || super.onKeyUp(keyCode, event)

    // Added in API level 14, deprecated in 29
    // it's needed because editors still use it even on API 36
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onViewClicked(focusChanged: Boolean) {
        super.onViewClicked(focusChanged)
        inputDeviceManager.evaluateOnViewClicked(this)
    }

    @RequiresApi(34)
    override fun onUpdateEditorToolType(toolType: Int) {
        super.onUpdateEditorToolType(toolType)
        inputDeviceManager.evaluateOnUpdateEditorToolType(toolType, this)
    }

    fun switchToPrevIme() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                switchToPreviousInputMethod()
            } else {
                @Suppress("DEPRECATION")
                inputMethodManager.switchToLastInputMethod(window.window!!.attributes.token)
            }
        } catch (e: Exception) {
            Timber.e(e, "Unable to switch to the previous IME.")
            inputMethodManager.showInputMethodPicker()
        }
    }

    fun switchToNextIme() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                switchToNextInputMethod(false)
            } else {
                @Suppress("DEPRECATION")
                inputMethodManager.switchToNextInputMethod(window.window!!.attributes.token, false)
            }
        } catch (e: Exception) {
            Timber.e(e, "Unable to switch to the next IME.")
            inputMethodManager.showInputMethodPicker()
        }
    }

    fun shareText(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val ic = currentInputConnection ?: return false
            val cs = ic.getSelectedText(0)
            if (cs == null) ic.performContextMenuAction(android.R.id.selectAll)
            return ic.performContextMenuAction(android.R.id.shareText)
        }
        return false
    }

    /** 編輯操作 */
    fun hookKeyboard(
        code: Int,
        mask: Int,
    ): Boolean {
        val ic = currentInputConnection ?: return false
        // 没按下 Ctrl 键
        if (mask != KeyEvent.META_CTRL_ON) {
            return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (prefs.keyboard.hookCtrlZY.getValue()) {
                when (code) {
                    KeyEvent.KEYCODE_Y -> return ic.performContextMenuAction(android.R.id.redo)
                    KeyEvent.KEYCODE_Z -> return ic.performContextMenuAction(android.R.id.undo)
                }
            }
        }

        when (code) {
            KeyEvent.KEYCODE_A -> {
                // 全选
                return if (prefs.keyboard.hookCtrlA.getValue()) {
                    ic.performContextMenuAction(android.R.id.selectAll)
                } else {
                    false
                }
            }

            KeyEvent.KEYCODE_X -> {
                // 剪切
                if (prefs.keyboard.hookCtrlCV.getValue()) {
                    val etr = ExtractedTextRequest()
                    etr.token = 0
                    val et = ic.getExtractedText(etr, 0)
                    if (et != null) {
                        if (et.selectionStart != et.selectionEnd) return ic.performContextMenuAction(android.R.id.cut)
                    }
                }
                Timber.w("hookKeyboard cut fail")
                return false
            }

            KeyEvent.KEYCODE_C -> {
                // 复制
                if (prefs.keyboard.hookCtrlCV.getValue()) {
                    val etr = ExtractedTextRequest()
                    etr.token = 0
                    val et = ic.getExtractedText(etr, 0)
                    if (et != null) {
                        if (et.selectionStart != et.selectionEnd) {
                            ic.performContextMenuAction(android.R.id.copy).also { result ->
                                if (result) {
                                    clearTextSelection()
                                }
                                return result
                            }
                        }
                    }
                }
                Timber.w("hookKeyboard copy fail")
                return false
            }

            KeyEvent.KEYCODE_V -> {
                // 粘贴
                if (prefs.keyboard.hookCtrlCV.getValue()) {
                    val etr = ExtractedTextRequest()
                    etr.token = 0
                    val et = ic.getExtractedText(etr, 0)
                    if (et == null) {
                        Timber.d("hookKeyboard paste, et == null, try commitText")
                        val clipboardText = clipboardManager.primaryClip?.getItemAt(0)?.coerceToText(this)
                        if (ic.commitText(clipboardText, 1)) {
                            return true
                        }
                    } else if (ic.performContextMenuAction(android.R.id.paste)) {
                        return true
                    }
                    Timber.w("hookKeyboard paste fail")
                }
                return false
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (prefs.keyboard.hookCtrlLR.getValue()) {
                    val etr = ExtractedTextRequest()
                    etr.token = 0
                    val et = ic.getExtractedText(etr, 0)
                    if (et != null) {
                        val moveTo = et.text.findSectionFrom(et.startOffset + et.selectionEnd)
                        ic.setSelection(moveTo, moveTo)
                        return true
                    }
                }
            }

            KeyEvent.KEYCODE_DPAD_LEFT ->
                if (prefs.keyboard.hookCtrlLR.getValue()) {
                    val etr = ExtractedTextRequest()
                    etr.token = 0
                    val et = ic.getExtractedText(etr, 0)
                    if (et != null) {
                        val moveTo = et.text.findSectionFrom(et.startOffset + et.selectionStart, true)
                        ic.setSelection(moveTo, moveTo)
                        return true
                    }
                }
        }
        return false
    }

    fun clearTextSelection() {
        val ic = currentInputConnection ?: return
        val etr = ExtractedTextRequest().apply { token = 0 }
        val et = currentInputConnection.getExtractedText(etr, 0)
        et?.let {
            if (it.selectionStart != it.selectionEnd) {
                ic.setSelection(it.selectionEnd, it.selectionEnd)
            }
        }
    }

    internal fun updateComposingText(text: String) {
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        if (composingText.isNotEmpty() || text.isNotEmpty()) {
            if (!ic.getSelectedText(0).isNullOrEmpty()) {
                ic.deleteSurroundingText(1, 0)
            }
            ic.setComposingText(text, 1)
            if (text.isEmpty()) {
                ic.finishComposingText()
            }
        }
        composingText = text
        ic.endBatchEdit()
    }

    fun getActiveText(type: Int): String {
        val rimeComposition = rime.composition
        val selected = currentInputConnection?.getSelectedText(0)?.toString()
        val commitPreview = rimeComposition.commitTextPreview
        val preedit = rimeComposition.preedit ?: ""
        val beforeCursor = getTextAroundCursor(1024, before = true) ?: ""
        val afterCursor = getTextAroundCursor(before = false) ?: ""
        val lastCommitted = lastCommittedText

        return sequenceOf(
            when (type) {
                2 -> preedit
                3 -> selected
                4 -> beforeCursor
                1 -> lastCommitted
                else -> null
            },
            commitPreview,
            selected,
            lastCommitted,
            beforeCursor,
            afterCursor,
        )
            .firstOrNull { it?.isNotEmpty() == true } ?: ""
    }

    private fun getTextAroundCursor(
        initialStep: Int = 1024,
        before: Boolean,
    ): String? {
        val ic = currentInputConnection ?: return null
        var step = initialStep
        var last: CharSequence? = null
        // Cap the window growth. Without a bound, a very large document turns
        // this into an unbounded main-thread IPC loop, and `step *= 2` would
        // eventually overflow into a negative size.
        val maxStep = 64 * 1024
        while (step <= maxStep) {
            val text = (if (before) ic.getTextBeforeCursor(step, 0) else ic.getTextAfterCursor(step, 0)) ?: return ""
            if (text.length < step) return text.toString()
            last = text
            step *= 2
        }
        return last?.toString() ?: ""
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    private var showingDialog: Dialog? = null

    val isDialogShowing: Boolean get() = showingDialog != null

    /**
     * A transparent surface between the dialog window and the keyboard.
     *
     * A window that is not focusable does not receive touches outside its own bounds (the flag
     * implies `FLAG_NOT_TOUCH_MODAL`), so a tap outside the dialog card would reach the keyboard
     * -- typing behind the dialog -- or the app above it. This view is a child of the IME
     * window's decor, above everything the input method draws; while it is up [onComputeInsets]
     * makes the whole IME frame touchable, so those taps land here and cancel the dialog instead
     * of leaking through.
     */
    private var dialogScrim: View? = null

    /**
     * Shows one of the theme / colour / sound / schema pickers.
     *
     * The dialog has to borrow this service's window token, otherwise a window owned by a service
     * cannot be added at all. That makes it a *child* of the input method window: it is drawn just
     * above the keyboard and it goes down together with it. That coupling is why the dialog must
     * not be focusable.
     *
     * A focusable dialog pulls the window focus away from the editor, so the framework stops the
     * input method — and a child of a stopped input method window never gets a surface, it stays
     * in `DRAW_PENDING`. The user taps a picker key, the keyboard disappears and nothing is shown;
     * the picker only appears once the editor is focused again and the keyboard comes back.
     *
     * `FLAG_NOT_FOCUSABLE` keeps the editor focused instead, so the keyboard stays up, the picker
     * is drawn above it, and `FLAG_DIM_BEHIND` dims everything behind. Keeping the keyboard alive
     * is what the pickers want anyway: they exist to try out a theme while typing. They carry a
     * cancel button and no text field, so nothing here needs key input.
     *
     * (`FLAG_ALT_FOCUSABLE_IM` was the old flag. It is the documented way to put a dialog over the
     * keyboard for an *activity*, but for an input method it does the opposite of what is needed:
     * by blocking every view below it from connecting to the input method, it makes the framework
     * stop the very keyboard the dialog is a child of.)
     *
     * Not being focusable also means the dialog window never sees touches outside the card; those
     * are caught by [dialogScrim] instead.
     */
    fun showDialog(dialog: Dialog) {
        showingDialog?.dismiss()
        dismissDialogScrim()
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.also {
            it.attributes.apply {
                token = decorView.windowToken
                type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG
            }
            it.addFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            )
            it.setDimAmount(styledFloat(android.R.attr.backgroundDimAmount))
        }
        val scrim = newDialogScrim(dialog)
        dialog.setOnDismissListener {
            // Dismissal is delivered asynchronously: another dialog and its scrim may already
            // have replaced this one by the time this listener runs.
            if (showingDialog === dialog && !dialog.isShowing) showingDialog = null
            if (dialogScrim === scrim) dismissDialogScrim()
        }
        dialog.show()
        // 弹框里的第一个可聚焦视图会自动拿到焦点，系统随之画出默认焦点高亮
        // （一层半透明黑遮罩，看起来像弹框背景发灰）。与输入视图同样的处理：
        // 遮罩没有存在的必要，触摸交互也不依赖 view focus。
        disableDialogFocusHighlight(dialog)
        showingDialog = dialog
        candidatesView?.onDialogVisibilityChanged()
        // Above the whole input view, candidate window included: anything the input method
        // draws could otherwise consume a tap that is meant to dismiss the dialog.
        (decorView as ViewGroup).addView(
            scrim,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        scrim.bringToFront()
        dialogScrim = scrim
        requestInputInsetsUpdate()
    }

    private fun newDialogScrim(dialog: Dialog): View = View(this).apply {
        setOnClickListener {
            if (showingDialog === dialog) dialog.cancel()
        }
    }

    /**
     * Keeps the dialog's content from taking view focus.
     *
     * The dialog window is already `FLAG_NOT_FOCUSABLE` (see [showDialog]), but views inside it can
     * still request focus on their own. A focused view gets the platform's default focus highlight,
     * a dim layer that reads as a greyed-out dialog card. Nothing in these pickers (lists and
     * buttons) needs focus -- they are operated by touch -- so the whole subtree is excluded and
     * the highlight is switched off as well, for views that already held focus.
     */
    private fun disableDialogFocusHighlight(dialog: Dialog) {
        val decor = dialog.window?.decorView as? ViewGroup ?: return
        decor.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        decor.isFocusable = false
        decor.isFocusableInTouchMode = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            disableDefaultFocusHighlight(decor)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun disableDefaultFocusHighlight(view: View) {
        view.defaultFocusHighlightEnabled = false
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                disableDefaultFocusHighlight(view.getChildAt(i))
            }
        }
    }

    private fun dismissDialogScrim() {
        val scrim = dialogScrim
        if (scrim != null) {
            dialogScrim = null
            (scrim.parent as? ViewGroup)?.removeView(scrim)
            requestInputInsetsUpdate()
        }
        // The dialog may be gone without a scrim, but the touch receiver still has to return.
        candidatesView?.onDialogVisibilityChanged()
    }

    /**
     * Dismisses a picker that is still up.
     *
     * The picker is a child of the input method window, so it is only drawn while the keyboard is
     * up. Left alive it would stay invisible and then pop back on screen the next time the keyboard
     * is shown, so it has to go as soon as the keyboard goes.
     */
    private fun dismissShowingDialog() {
        showingDialog?.dismiss()
        showingDialog = null
        dismissDialogScrim()
    }
}
