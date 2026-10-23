// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.keyboard

import android.graphics.Point
import android.os.Build
import android.text.InputType
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import com.osfans.trime.R
import com.osfans.trime.core.CompositionProto
import com.osfans.trime.core.RimeMessage
import com.osfans.trime.core.SchemaItem
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.KeyActionManager
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.model.TextKeyboard
import com.osfans.trime.ime.bar.InputBarDelegate
import com.osfans.trime.ime.broadcast.EnterKeyDisplayDelegate
import com.osfans.trime.ime.broadcast.InputBroadcastReceiver
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.KeyboardPrefs.isFloatingEffective
import com.osfans.trime.ime.keyboard.KeyboardPrefs.isLandscapeMode
import com.osfans.trime.ime.popup.PopupDelegate
import com.osfans.trime.ime.window.BoardWindow
import com.osfans.trime.ime.window.ResidentWindow
import com.osfans.trime.util.isLandscape
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import org.kodein.di.instance
import splitties.dimensions.dp
import splitties.systemservices.windowManager
import splitties.views.dsl.core.add
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.wrapContent
import timber.log.Timber
import java.lang.ref.WeakReference

class KeyboardWindow :
    BoardWindow.NoBarBoardWindow(),
    ResidentWindow,
    InputBroadcastReceiver {
    private val service: TrimeInputMethodService by di.instance()
    private val theme: Theme by di.instance()
    private val rime: RimeSession by di.instance()
    private val commonKeyboardActionListener: CommonKeyboardActionListener by di.instance()
    private val popup: PopupDelegate by di.instance()
    private val enterKeyDisplay: EnterKeyDisplayDelegate by di.instance()
    private val inputBarDelegate: InputBarDelegate by di.instance()

    private val cursorCapsMode: Int
        get() =
            service.currentInputEditorInfo.run {
                if (inputType != InputType.TYPE_NULL) {
                    service.currentInputConnection?.getCursorCapsMode(inputType) ?: 0
                } else {
                    0
                }
            }

    private val _currentKeyboardHeight =
        MutableSharedFlow<Int>(
            replay = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    val currentKeyboardHeight = _currentKeyboardHeight.asSharedFlow()

    private lateinit var keyboardView: FrameLayout

    companion object : ResidentWindow.Key {
        private var currentKeyboardRef: WeakReference<Keyboard>? = null

        var currentKeyboard: Keyboard
            get() = currentKeyboardRef?.get() ?: error("No active keyboard")
            private set(value) {
                currentKeyboardRef = WeakReference(value)
            }
    }

    override val key: ResidentWindow.Key
        get() = KeyboardWindow

    private val presetKeyboardIds = theme.presetKeyboards.keys.toList()
    private val internalPrefs = AppPrefs.defaultInstance().internal
    private var initializeKeyboardId = internalPrefs.initializeKeyboardId.getValue()
    private val keyboardSourceMap = mutableMapOf<String, String>()
    private var currentKeyboardId = ""

    // 记录最近一次已知的方案 ID，用于过滤重复通知。只作为去重用，
    // 首次方案不能当作“用户切换方案”处理（见 onRimeSchemaUpdated）。
    private var lastSchemaId: String? = null

    // onCreateView 是否用了记忆键盘：有记忆时部署/重启补发的方案消息不能改动当前键盘。
    private var restoredFromMemory = false
    private var lastKeyboardId = ""
    private var lastLockKeyboardId = ""
    private var tempAsciiMode: Boolean? = null
    private var lastNavBarKeyboardId: String? = null
    private val cachedKeyboards = mutableMapOf<String, Pair<Keyboard, KeyboardView>>()
    private val currentKeyboard: Keyboard? get() = cachedKeyboards[currentKeyboardId]?.first
    private val currentKeyboardView: KeyboardView? get() = cachedKeyboards[currentKeyboardId]?.second

    /**
     * 当前键盘构造时烘焙的悬浮宽/高档位：InputView 缩放归一的基准
     * （见 [Keyboard.bakedFloatingWidthPercent] 与 InputView.applyFloatingScale）。
     * 尚无挂载的键盘时为 null，由调用方回退。
     */
    val currentBakedFloatingWidthPercent: Int? get() = currentKeyboard?.bakedFloatingWidthPercent
    val currentBakedFloatingHeightPercent: Int? get() = currentKeyboard?.bakedFloatingHeightPercent

    private val keyboardActionListener = commonKeyboardActionListener.listener

    private var lastIsPortrait: Boolean? = null
    private var containerWidth: Int = 0
    private var allowedWidth: Int = 0

    private val onKeyboardViewLayoutChangeListener =
        View.OnLayoutChangeListener { v, left, _, right, _, _, _, _, _ ->
            val width = right - left
            if (width > 0 && allowedWidth != width) {
                val isPortrait = !context.resources.configuration.isLandscape()
                lastIsPortrait = isPortrait
                containerWidth = width
                allowedWidth = width
                // 宽/高变化后所有缓存键盘的已烘焙几何都失效，必须整表重建
                v.post { refreshKeyboards(true) }
            }
        }

    override fun onCreateView(): View {
        keyboardView = context.frameLayout(R.id.keyboard_view)
        keyboardView.addOnLayoutChangeListener(onKeyboardViewLayoutChangeListener)

        restoreKeyboardSourceMap()
        // 只恢复主题里标了 lock 的键盘：lock 是主题对“这个键盘需要被记住”的显式声明。
        // 没写 lock 的布局（符号、数字、ASCII 等）退出后回到 .default 匹配即可。
        // 注意 resolveKeyboardConfig 对未知 ID 会回落到主题的 default 键盘，
        // 必须先确认 ID 本身存在，否则空记忆会被当成 default 键盘。
        val rememberedKeyboardId = initializeKeyboardId.takeIf {
            presetKeyboardIds.contains(it) && selectKeyboardConfig(it)?.lock == true
        }
        restoredFromMemory = rememberedKeyboardId != null

        // 用当前已缓存的方案作为去重基线；部署重启期间缓存可能是 .default/空，
        // 此时保持 null，等引擎真正应用方案（native 通知）再处理。
        lastSchemaId = rime.status.schemaId.takeIf { it.isNotEmpty() && it != ".default" }

        attachKeyboard(evalKeyboard(rememberedKeyboardId ?: ".default"))
        return keyboardView
    }

    private fun detachCurrentView() {
        currentKeyboardView?.also {
            it.onDetach()
            keyboardView.removeView(it)
        }
        currentKeyboard?.lastAsciiMode = rime.status.isAsciiMode
    }

    /** 计算键盘可用宽度：优先使用已测量的容器宽度，否则回退到系统窗口测量。 */
    private fun computeAllowedWidth(): Int {
        val isPortrait = !context.resources.configuration.isLandscape()

        if (containerWidth > 0 && lastIsPortrait == isPortrait) {
            return containerWidth
        }

        // 悬浮键盘忽略主题键盘左右 padding：keyboard_padding* 是给贴底的普通键盘
        // 用的，悬浮卡片下只会无谓挤占可用宽度（与 InputView.updateKeyboardSize
        // 忽略左右 padding 空位视图对应）。
        val padding =
            if (context.isFloatingEffective()) {
                0
            } else {
                theme.generalStyle.run {
                    if (context.isLandscapeMode()) keyboardPaddingLand else keyboardPadding
                }
            }

        fun resolvePadding(configValue: Int) = configValue.takeIf { it > 0 } ?: padding

        // 横屏不参与单手模式
        val oneHandState = if (isPortrait) {
            AppPrefs.defaultInstance().keyboard.oneHandMode.getValue()
        } else {
            AppPrefs.Keyboard.OneHandMode.DISABLE
        }

        val (paddingLeft, paddingRight) = when (oneHandState) {
            AppPrefs.Keyboard.OneHandMode.LEFT ->
                padding to resolvePadding(theme.generalStyle.keyboardPaddingRight)
            AppPrefs.Keyboard.OneHandMode.RIGHT ->
                resolvePadding(theme.generalStyle.keyboardPaddingLeft) to padding
            AppPrefs.Keyboard.OneHandMode.DISABLE -> padding to padding
        }

        val totalPadding = paddingLeft + paddingRight

        val safeWidth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowMetrics = context.windowManager.maximumWindowMetrics
            val insets = windowMetrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            val displayWidth = context.resources.displayMetrics.widthPixels
            val windowWidth = windowMetrics.bounds.width() - insets.left - insets.right
            if (windowWidth < displayWidth - context.dp(1)) displayWidth else windowWidth
        } else {
            @Suppress("DEPRECATION")
            val size = Point()
            @Suppress("DEPRECATION")
            context.windowManager.defaultDisplay.getSize(size)
            size.x
        }

        val width = safeWidth - context.dp(totalPadding)
        allowedWidth = width
        return width
    }

    private fun selectKeyboardConfig(name: String): TextKeyboard? = resolveKeyboardConfig(theme.presetKeyboards, name)

    private fun attachKeyboard(target: String) {
        currentKeyboardId = target
        lastKeyboardId = target

        val config = selectKeyboardConfig(target)
        val keyboard = currentKeyboard ?: Keyboard(context, theme, computeAllowedWidth(), config)
        val view = currentKeyboardView ?: KeyboardView(context, theme, keyboard, popup, service, keyboardActionListener, enterKeyDisplay)

        if (currentKeyboard == null) {
            cachedKeyboards[target] = keyboard to view
            keyboard.lastAsciiMode = keyboard.asciiMode
        }

        keyboard.also {
            runBlocking { _currentKeyboardHeight.emit(it.keyboardHeight) }
            if (it.isLock) lastLockKeyboardId = target
            dispatchCapsState(it::setShifted)

            val currentMode = rime.status.isAsciiMode
            val targetMode = if (it.resetAsciiMode) it.asciiMode else it.lastAsciiMode

            if (currentMode != targetMode) {
                service.postRimeJob {
                    commitComposition()
                    setRuntimeOption("ascii_mode", targetMode)
                }
            }

            // TODO：为避免过量重构，这里暂时将 currentKeyboard 同步到 KeyboardSwitcher
            KeyboardSwitcher.currentKeyboard = it
            // Keys draw their toggle state from a cache, so seed it once here
            // instead of querying rime on every frame.
            it.refreshToggleStates()
        }

        view.let {
            keyboardView.apply {
                (it.parent as? android.view.ViewGroup)?.removeView(it)
                attachNavBar()
                add(it, lParams(matchParent, matchParent))
            }
        }
    }

    /**
     * 根据当前键盘配置挂载或卸载顶部导航栏。
     */
    private fun attachNavBar() {
        if (currentKeyboardId == lastNavBarKeyboardId) return
        lastNavBarKeyboardId = currentKeyboardId
        val config = selectKeyboardConfig(currentKeyboardId)
        if (config?.navbar == true) {
            inputBarDelegate.navBar.attach(
                title = config.name,
                onCloseClick = { service.requestHideSelf(0) },
                onBackClick = { switchKeyboard(".previous") },
            )
        } else {
            inputBarDelegate.navBar.detach()
        }
    }

    private fun smartMatchKeyboard(): String {
        // 主题的布局中包含方案id，直接采用
        val currentSchema = rime.status.schemaId
        if (presetKeyboardIds.contains(currentSchema)) {
            return currentSchema
        }
        val alphabet = rime.schema.alphabet
        val layout =
            when {
                alphabet.all { it.isLetter() } -> "qwerty" // 包含 26 个字母
                alphabet.all { it.isLetter() || ",./;".any(it::equals) } -> "qwerty_" // 包含 26 个字母和,./;
                alphabet.all { it.isLetterOrDigit() } -> "qwerty0" // 包含 26 个字母和数字键
                else -> "default"
            }
        return if (presetKeyboardIds.contains(layout)) layout else "default"
    }

    private fun persistKeyboardSourceMap() {
        val serialized = keyboardSourceMap.entries
            .joinToString(",") { "${it.key}:${it.value}" }
        internalPrefs.previousKeyboardIds.setValue(serialized)
    }

    private fun restoreKeyboardSourceMap() {
        val serialized = internalPrefs.previousKeyboardIds.getValue()
        serialized.takeIf { it.isNotEmpty() }?.split(",")
            ?.mapNotNull { entry ->
                entry.split(":").takeIf { it.size == 2 }?.let { (target, source) ->
                    target to source
                }?.takeIf { (target, source) ->
                    target in presetKeyboardIds && source in presetKeyboardIds
                }
            }?.toMap(keyboardSourceMap)
    }

    /**
     * 记忆用户最后显式选择的 lock 键盘（.last_lock 语义）。
     *
     * 只能在横屏布局替换前调用：landscape_keyboard 通常只是同一主键盘的横向变体，
     * 写进记忆后下次启动（竖屏）会错误地挂载横向布局。
     */
    private fun rememberKeyboardId(id: String) {
        if (id == initializeKeyboardId) return
        initializeKeyboardId = id
        internalPrefs.initializeKeyboardId.setValue(id)
    }

    /**
     * 解析目标键盘 ID 并挂载。
     *
     * [remember] 只在用户显式切换键盘时为 true（按键 select / _keyboard_ 选项等），
     * 且只有主题标了 lock 的键盘才会写入记忆；由输入框类型、方案变化、窗口重建
     * 触发的自动切换不能写记忆，否则会把默认键盘当成用户选择持久化。
     */
    private fun evalKeyboard(
        id: String,
        remember: Boolean = false,
    ): String {
        val currentIdx = presetKeyboardIds.indexOfFirst { currentKeyboardId == it }
        val dot =
            when (id) {
                ".default" -> smartMatchKeyboard()
                ".prior" -> presetKeyboardIds.getOrNull(currentIdx - 1) ?: currentKeyboardId
                ".next" -> presetKeyboardIds.getOrNull(currentIdx + 1) ?: currentKeyboardId
                ".last" -> lastKeyboardId
                ".previous" -> keyboardSourceMap[currentKeyboardId] ?: currentKeyboardId
                ".last_lock" -> lastLockKeyboardId
                ".ascii" -> {
                    var ascii = currentKeyboard?.asciiKeyboard
                    if (ascii.isNullOrEmpty()) {
                        ascii = lastLockKeyboardId
                    }
                    if (presetKeyboardIds.contains(ascii)) ascii else currentKeyboardId
                }
                else -> {
                    id.ifEmpty {
                        when {
                            currentKeyboard?.isLock == true -> currentKeyboardId
                            lastLockKeyboardId.isNotEmpty() -> lastLockKeyboardId
                            // 新建窗口时 lastLockKeyboardId 还是空的，此时回落到记忆的
                            // lock 键盘；没有记忆就交给 smartMatchKeyboard。
                            else -> initializeKeyboardId.takeIf {
                                presetKeyboardIds.contains(it) && selectKeyboardConfig(it)?.lock == true
                            } ?: ""
                        }
                    }
                }
            }
        val resolved = dot.ifEmpty { smartMatchKeyboard() }

        // 只记录用户显式选择、且主题标了 lock 的键盘。
        if (remember && presetKeyboardIds.contains(resolved) && selectKeyboardConfig(resolved)?.lock == true) {
            rememberKeyboardId(resolved)
        }

        var final = resolved

        // 切换到横屏布局。悬浮键盘例外：landscape_keyboard 指定的布局通常更宽，
        // 悬浮缩放后难以点按，且悬浮卡片的尺寸解算只按竖屏布局校准——实测指定了
        // landscape_keyboard 的主题在悬浮模式下横屏会整块不可见，故悬浮时始终用
        // 竖屏布局。「有效悬浮」包含横屏自动悬浮；只有真正回落普通键盘时，
        // landscape_keyboard 才恢复生效（非悬浮模式维持原始行为）。
        if (service.isLandscapeMode() && !context.isFloatingEffective()) {
            val landscape =
                theme.presetKeyboards[final]?.landscapeKeyboard ?: ""
            if (landscape.isNotEmpty() && presetKeyboardIds.contains(landscape)) final = landscape
        }
        return final
    }

    fun switchKeyboard(
        to: String,
        remember: Boolean = true,
    ) {
        val target = evalKeyboard(to, remember)
        ContextCompat.getMainExecutor(service).execute {
            if (cachedKeyboards.containsKey(target)) {
                if (target == currentKeyboardId) return@execute
            }
            // 保存上一个键盘ID，用于来源键盘回退（如果不是返回类操作且不是重新弹出则记录）
            if (to.isNotEmpty() && to !in setOf(".previous", ".last_lock")) {
                keyboardSourceMap[target] = currentKeyboardId
                persistKeyboardSourceMap()
            }
            detachCurrentView()
            attachKeyboard(target)
        }
        Timber.d("Switched to keyboard: $target")
    }

    fun refreshKeyboards(isAll: Boolean = false) {
        val id = currentKeyboardId.ifEmpty { return }
        detachCurrentView()
        if (isAll) {
            cachedKeyboards.clear()
        } else {
            cachedKeyboards.remove(id)
        }
        attachKeyboard(id)
    }

    override fun onStartInput(info: EditorInfo) {
        val targetKeyboard =
            when (info.imeOptions and EditorInfo.IME_FLAG_FORCE_ASCII) {
                EditorInfo.IME_FLAG_FORCE_ASCII -> ".ascii"
                else -> {
                    when (info.inputType and InputType.TYPE_MASK_CLASS) {
                        InputType.TYPE_CLASS_NUMBER,
                        InputType.TYPE_CLASS_PHONE,
                        InputType.TYPE_CLASS_DATETIME,
                        -> "number"
                        InputType.TYPE_CLASS_TEXT -> {
                            when (info.inputType and InputType.TYPE_MASK_VARIATION) {
                                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
                                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                                -> ".ascii"
                                else -> ""
                            }
                        }
                        else -> ""
                    }
                }
            }
        // 由输入框类型决定的临时键盘（数字/ASCII）以及空目标的回落不能写记忆，
        // 它们不代表用户选择。
        switchKeyboard(targetKeyboard, remember = false)
        val isAsciiMode = rime.status.isAsciiMode
        if (targetKeyboard == ".ascii" || targetKeyboard == "number") {
            if (tempAsciiMode == null) {
                tempAsciiMode = isAsciiMode
            }
            if (!isAsciiMode) {
                service.postRimeJob { setRuntimeOption("ascii_mode", true) }
            }
        } else {
            tempAsciiMode?.let { saved ->
                if (isAsciiMode != saved) {
                    service.postRimeJob { setRuntimeOption("ascii_mode", saved) }
                }
                tempAsciiMode = null
            } ?: currentKeyboard?.let {
                if (theme.generalStyle.resetAsciiModeOnFocusChange) {
                    val targetMode = if (it.resetAsciiMode) it.asciiMode else it.lastAsciiMode
                    if (isAsciiMode != targetMode) {
                        service.postRimeJob { setRuntimeOption("ascii_mode", targetMode) }
                    }
                }
            }
        }
    }

    private fun dispatchCapsState(setShift: (Boolean, Boolean) -> Unit) {
        val status = rime.status
        // TODO: 启用自动首句大写后，点击方向键时，保持Shift锁定状态功能将无法生效
        if (theme.generalStyle.autoCaps && status.isAsciiMode && currentKeyboardView?.isCapsOn == false) {
            setShift(false, cursorCapsMode != 0)
        }
    }

    override fun onKeyAppearanceUpdate(composing: Boolean, menu: Boolean, paging: Boolean) {
        if (!rime.status.isAsciiMode) {
            currentKeyboard?.appearanceStateKeys?.forEach { key ->
                currentKeyboardView?.invalidateKeyByIndex(key.index)
            }
        }
    }

    override fun onSelectionUpdate(
        start: Int,
        end: Int,
    ) {
        dispatchCapsState { on, shifted ->
            currentKeyboard?.setShifted(on, shifted)?.let { if (it) currentKeyboardView?.invalidateAllKeys() }
        }
    }

    override fun onRimeSchemaUpdated(
        schema: SchemaItem,
        isEngineNotification: Boolean,
    ) {
        // 只有 librime 的 "schema" 通知才算用户真正换了方案；部署/重启时由状态差异
        // 补发的消息只是引擎恢复方案过程中的瞬时状态，不能用来重置当前键盘和记忆。
        // .default / 空 ID 是“无方案”的占位状态，不是真实方案。
        if (schema.id.isEmpty() || schema.id == ".default") return

        val previous = lastSchemaId
        lastSchemaId = schema.id

        if (isEngineNotification) {
            if (previous == schema.id) return
            // 方案真正变更时才自动匹配合适键盘。这是用户换方案带来的键盘变化，
            // 若匹配结果是 lock 键盘则照常写入记忆（lock 的原意）。
            switchKeyboard(".default", remember = true)
            return
        }

        // 合成消息：引擎启动时会静默恢复上次方案（不触发 native 通知），首个真实方案
        // 只能从这里得知。仅在“本窗口没有可恢复的记忆键盘且尚无方案基线”时用它做
        // 首次匹配（全新安装/首次使用）；有记忆时保持用户选择的键盘不变。
        if (previous == null && !restoredFromMemory) {
            switchKeyboard(".default", remember = false)
        }
    }

    override fun onRimeOptionUpdated(value: RimeMessage.OptionMessage.Data) {
        val option = value.option
        // Keep the cached toggle state of the keys in sync for free -- the
        // message already carries the new value.
        currentKeyboard?.updateToggleState(option, value.value)
        when {
            option.startsWith("_keyboard_") -> {
                val target = option.removePrefix("_keyboard_")
                if (target.isNotEmpty()) {
                    switchKeyboard(target)
                }
            }
            option.startsWith("_key_") -> {
                val what = option.removePrefix("_key_")
                if (what.isNotEmpty() && value.value) {
                    commonKeyboardActionListener
                        .listener
                        .onAction(KeyActionManager.getAction(what))
                }
            }
        }
        currentKeyboardView?.invalidateAllKeys()
    }

    override fun onAttached() {
        attachNavBar()
    }

    override fun onDetached() {
        lastNavBarKeyboardId = null
        inputBarDelegate.navBar.detach()
        currentKeyboardView?.onDetach()
    }
}
