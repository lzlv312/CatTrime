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
import com.osfans.trime.data.theme.ThemeManager
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
    private var rememberedKeyboardId = internalPrefs.rememberedKeyboardId.getValue()
    private val keyboardSourceMap = mutableMapOf<String, String>()
    private var currentKeyboardId = ""

    // 最近一次已知的方案。null 表示引擎还没给出有效方案（部署/重启期间），
    // 此时收到的方案通知只是引擎在恢复自己的状态，不能当作“用户换方案”。
    private var lastSchemaId: String? = null

    // 本窗口创建时是否沿用了记忆键盘：有记忆时首个方案通知不能推翻它。
    private var restoredFromMemory = false

    // 换主题后若方案尚不可用，等首个有效方案到达再做一次匹配。
    private var pendingMatch = false

    // 用户在本窗口里手动选过键盘：之后引擎恢复方案的通知不能再推翻它。
    private var userChoseKeyboard = false

    // 当前输入框类型决定的临时键盘（数字/ASCII）。方案晚到时的补匹配不能顶掉它。
    private var inputTypeKeyboard: String? = null

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

        // 方案基线：部署/重启期间可能还是 .default，保持 null 等引擎通知。
        val schemaId = currentSchemaId()
        lastSchemaId = schemaId

        // 只有真正换了主题才重新匹配。同一主题下的重建（设置、配色、进程重启、
        // 部署）一律恢复用户最后选择的键盘，保证部署不会改动当前键盘。
        val themeId = ThemeManager.prefs.selectedTheme.getValue()
        val memoryThemeId = internalPrefs.keyboardMemoryTheme.getValue()
        val themeChanged = memoryThemeId.isNotEmpty() && memoryThemeId != themeId
        if (memoryThemeId != themeId) internalPrefs.keyboardMemoryTheme.setValue(themeId)

        // 记忆属于旧主题的键盘集合：换主题后旧选择作废，由新主题重新匹配；
        // 匹配结果是 lock 键盘时会重新写入记忆。
        if (themeChanged) {
            rememberedKeyboardId = ""
            internalPrefs.rememberedKeyboardId.setValue("")
        }

        // 记忆写下之后方案又变过（进程被杀期间的切换）：按新方案重新匹配。
        // 部署不会改变方案，因此不会走到这里。
        val rememberedSchema = internalPrefs.keyboardMemorySchema.getValue()
        val schemaChanged =
            schemaId != null && rememberedSchema.isNotEmpty() && rememberedSchema != schemaId
        if (schemaId != null && rememberedSchema != schemaId) {
            internalPrefs.keyboardMemorySchema.setValue(schemaId)
        }
        // 旧方案下的选择对新方案无效；若匹配结果是 lock 键盘会重新写入记忆，
        // 否则保持“无记忆”，下次启动仍按方案匹配。
        if (schemaChanged) {
            rememberedKeyboardId = ""
            internalPrefs.rememberedKeyboardId.setValue("")
        }

        val remembered = rememberedKeyboardId.takeIf(::isRememberable)
        restoredFromMemory = remembered != null
        pendingMatch = remembered == null && schemaId == null

        // 换主题或没有可用记忆时按方案匹配；方案未知则先挂默认键盘，
        // 等首个有效方案到达后由 pendingMatch 补一次匹配。
        val matchNow = remembered == null && schemaId != null
        attachKeyboard(evalKeyboard(remembered ?: ".default", remember = matchNow))
        return keyboardView
    }

    private fun currentSchemaId(): String? = rime.status.schemaId.takeIf { it.isNotEmpty() && it != ".default" }

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

        // 横屏不参与单手模式
        val oneHandState = if (isPortrait) {
            AppPrefs.defaultInstance().keyboard.oneHandMode.getValue()
        } else {
            AppPrefs.Keyboard.OneHandMode.DISABLE
        }

        // 单手边距是在 keyboard_padding 之上叠加的额外留白，而非替换它：
        // keyboard_padding 为曲面屏等场景预留，任何模式下都必须保留。
        val extraLeft = theme.generalStyle.keyboardPaddingLeft.takeIf {
            oneHandState == AppPrefs.Keyboard.OneHandMode.RIGHT && it > 0
        } ?: 0
        val extraRight = theme.generalStyle.keyboardPaddingRight.takeIf {
            oneHandState == AppPrefs.Keyboard.OneHandMode.LEFT && it > 0
        } ?: 0

        val totalPadding = padding * 2 + extraLeft + extraRight

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

    /** 只有主题标了 lock 的键盘才值得跨重启记忆（符号、数字等临时布局不记）。 */
    private fun isRememberable(id: String): Boolean = presetKeyboardIds.contains(id) && selectKeyboardConfig(id)?.lock == true

    /**
     * 记忆用户最后显式选择的 lock 键盘（.last_lock 语义）。
     *
     * 只能在横屏布局替换前调用：landscape_keyboard 通常只是同一主键盘的横向变体，
     * 写进记忆后下次启动（竖屏）会错误地挂载横向布局。
     */
    private fun rememberKeyboardId(id: String) {
        if (id == rememberedKeyboardId) return
        rememberedKeyboardId = id
        internalPrefs.setKeyboardMemory(id, currentSchemaId())
    }

    /**
     * 解析目标键盘 ID 并挂载。
     *
     * [remember] 只在用户显式切换键盘（按键 select / _keyboard_ 选项等）或主题、
     * 方案变化触发匹配时为 true；输入框类型、窗口重建、部署重启不能写记忆，
     * 否则会把临时键盘当成用户选择持久化。
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
                            else -> rememberedKeyboardId.takeIf(::isRememberable) ?: ""
                        }
                    }
                }
            }
        val resolved = dot.ifEmpty { smartMatchKeyboard() }

        // 只记录用户显式选择、且主题标了 lock 的键盘。
        if (remember && isRememberable(resolved)) {
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
        userInitiated: Boolean = true,
    ) {
        val target = evalKeyboard(to, remember)
        if (userInitiated) {
            // 用户已经做出选择，任何尚未发生的自动匹配都不应再覆盖它。
            pendingMatch = false
            userChoseKeyboard = true
            inputTypeKeyboard = null
        }
        ContextCompat.getMainExecutor(service).execute {
            if (cachedKeyboards.containsKey(target)) {
                if (target == currentKeyboardId) return@execute
            }
            // 保存上一个键盘ID，用于来源键盘回退（如果不是返回类操作且不是重新弹出则记录）。
            // 允许互指：通用工具键盘被 A、C 先后打开时，返回的是最后打开者，所以这里直接覆盖。
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
        inputTypeKeyboard = targetKeyboard.takeIf { it == ".ascii" || it == "number" }
        switchKeyboard(targetKeyboard, remember = false, userInitiated = false)
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

    override fun onRimeSchemaUpdated(schema: SchemaItem) {
        // .default / 空 ID 是“无方案”的占位状态，不是一次方案切换。
        if (schema.id.isEmpty() || schema.id == ".default") return

        val previous = lastSchemaId
        lastSchemaId = schema.id

        // 换主题时方案还不可用：现在已经知道方案了，补做匹配。
        // 当前若被输入框类型钉在数字/ASCII 键盘上，则保持不动。
        if (pendingMatch) {
            pendingMatch = false
            if (inputTypeKeyboard == null) {
                switchKeyboard(".default", remember = true, userInitiated = false)
            }
            internalPrefs.keyboardMemorySchema.setValue(schema.id)
            return
        }

        // 本窗口还没有方案基线：这是引擎恢复方案的通知（部署/重启），不是用户
        // 换方案。已恢复或已手动选择的键盘保持不动；没有记忆时才补一次匹配。
        if (previous == null) {
            if (!restoredFromMemory && !userChoseKeyboard) {
                switchKeyboard(".default", remember = true, userInitiated = false)
            }
            return
        }

        if (previous == schema.id) return

        // 用户确实换了方案：旧方案下的键盘选择作废，按新方案匹配。
        // 匹配结果是 lock 键盘时会重新写入记忆，否则保持“无记忆”。
        rememberedKeyboardId = ""
        internalPrefs.rememberedKeyboardId.setValue("")
        switchKeyboard(".default", remember = true, userInitiated = false)
        internalPrefs.keyboardMemorySchema.setValue(schema.id)
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
