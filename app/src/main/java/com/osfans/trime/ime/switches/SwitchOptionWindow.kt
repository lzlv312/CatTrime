/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.switches

import android.app.Dialog
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.content.res.use
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.osfans.trime.R
import com.osfans.trime.core.RimeApi
import com.osfans.trime.core.RimeConfig
import com.osfans.trime.core.RimeMessage
import com.osfans.trime.core.SchemaItem
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.daemon.launchOnReady
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.FontManager
import com.osfans.trime.data.theme.KeyActionManager
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.model.MenuKeyboard
import com.osfans.trime.ime.bar.ui.ToolButton
import com.osfans.trime.ime.broadcast.InputBroadcastReceiver
import com.osfans.trime.ime.core.InputTabLayout
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.dialog.EnabledSchemaPickerDialog
import com.osfans.trime.ime.keyboard.CommonKeyboardActionListener
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.window.BoardWindow
import com.osfans.trime.ime.window.BoardWindowManager
import com.osfans.trime.ui.common.withMaterialTheme
import com.osfans.trime.ui.main.settings.ThemePickerDialog
import com.osfans.trime.util.AppUtils
import com.osfans.trime.util.ImePopupMenu
import kotlinx.coroutines.launch
import org.kodein.di.instance
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.before
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.core.add
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.dsl.recyclerview.recyclerView
import splitties.views.recyclerview.gridLayoutManager
import timber.log.Timber
import splitties.views.dsl.core.lParams as coreLParams

class SwitchOptionWindow :
    BoardWindow.BarBoardWindow(),
    InputBroadcastReceiver {
    private val service: TrimeInputMethodService by di.instance()
    private val rime: RimeSession by di.instance()
    private val windowManager: BoardWindowManager by di.instance()
    private val theme: Theme by di.instance()
    private val prefs: AppPrefs = AppPrefs.defaultInstance()
    private val commonKeyboardActionListener: CommonKeyboardActionListener by di.instance()

    /** 主题声明了 `menu_keyboard` 时进入双 Tab 模式：方案开关 + 自定义功能面板。 */
    private val menuKeyboard: MenuKeyboard? get() = theme.menuKeyboard

    private val staticEntryData: Array<SwitchOptionEntry> by lazy {
        arrayOf(
            SwitchOptionEntry.Static(
                context.getString(R.string.theme),
                R.drawable.ic_baseline_color_lens_24,
                SwitchOptionEntry.Static.Type.ThemeList,
            ),
            SwitchOptionEntry.Static(
                context.getString(R.string.schemata),
                R.drawable.ic_round_view_list_24,
                SwitchOptionEntry.Static.Type.SchemaList,
            ),
            SwitchOptionEntry.Static(
                context.getString(R.string.update_config),
                R.drawable.ic_baseline_sync_24,
                SwitchOptionEntry.Static.Type.UpdateConfig,
            ),
            SwitchOptionEntry.Static(
                context.getString(R.string.virtual_keyboard),
                R.drawable.ic_baseline_keyboard_24,
                SwitchOptionEntry.Static.Type.Keyboard,
            ),
        )
    }

    private val staticEntries: Array<SwitchOptionEntry>
        get() = if (!prefs.general.hideStaticSwitcher.getValue()) {
            staticEntryData
        } else {
            emptyArray()
        }

    var popupMenu: ImePopupMenu? = null

    private val saveOptions by lazy {
        RimeConfig.openConfig("default").use {
            it.getList("switcher/save_options", RimeConfig::getString).toSet()
        }
    }

    private suspend fun RimeApi.applyOption(option: String, value: Boolean) {
        setRuntimeOption(option, value)
        if (option in saveOptions) {
            RimeConfig.openUserConfig("user").use {
                it.setBool("var/option/$option", value)
            }
        }
    }

    private fun showDialog(builder: suspend (RimeApi) -> Dialog) {
        rime.launchOnReady { api ->
            service.lifecycleScope.launch {
                service.showDialog(builder(api))
            }
        }
    }

    private val schemaAdapter: SwitchOptionAdapter by lazy {
        object : SwitchOptionAdapter() {
            override val theme: Theme = this@SwitchOptionWindow.theme

            override fun onItemClick(
                view: View,
                entry: SwitchOptionEntry,
            ) {
                when (entry) {
                    is SwitchOptionEntry.Static -> when (entry.type) {
                        SwitchOptionEntry.Static.Type.SchemaList -> showDialog { r ->
                            EnabledSchemaPickerDialog.build(r, service.lifecycleScope, context.withMaterialTheme()) {
                                setNegativeButton(R.string.enable_schemata) { _, _ ->
                                    AppUtils.launchMainToSchemaList(context)
                                }
                            }
                        }
                        SwitchOptionEntry.Static.Type.UpdateConfig -> rime.launchOnReady { r ->
                            r.updateConfig()
                            service.lifecycleScope.launch {
                                Toast.makeText(service, R.string.done, Toast.LENGTH_SHORT).show()
                            }
                        }
                        SwitchOptionEntry.Static.Type.Keyboard -> AppUtils.launchMainToKeyboard(context)
                        SwitchOptionEntry.Static.Type.ThemeList -> showDialog { r ->
                            ThemePickerDialog.build(service.lifecycleScope, context.withMaterialTheme()) {
                                r.commitComposition()
                            }
                        }
                    }
                    is SwitchOptionEntry.Custom -> {
                        val options = entry.switch.options
                        if (options.isEmpty()) {
                            rime.launchOnReady {
                                val oldValue = it.getRuntimeOption(entry.switch.name)
                                it.applyOption(entry.switch.name, !oldValue)
                            }
                        } else {
                            // A switch declaring `options` in the schema yaml has one state per
                            // option, so the states have to be offered as a list. This was a
                            // `PopupMenu`, which drops the input session and tears this window down
                            // the moment it opens; see `ImePopupMenu`.
                            val menu = ImePopupMenu(view)
                            // 选中标记的强调色：从应用 MD3 主题解析（动态色）。colorPrimary 的
                            // attr 声明在 appcompat（material 1.12+ 已移除 M2 attrs），
                            // 主题解析链的终点仍是 MD3 的 colorPrimary。
                            val checkTint =
                                view.context
                                    .obtainStyledAttributes(intArrayOf(androidx.appcompat.R.attr.colorPrimary))
                                    .use { it.getColor(0, 0) }
                            entry.switch.states.forEachIndexed { stateIndex, state ->
                                val checked = stateIndex == entry.selectedIndex
                                menu.item(
                                    title = state,
                                    icon = if (checked) R.drawable.ic_baseline_check_circle_24 else 0,
                                    // 选中标记用动态强调色，且放行尾（选择列表惯例）。
                                    iconTint = if (checked) checkTint else 0,
                                    iconAtEnd = true,
                                ) {
                                    rime.launchOnReady {
                                        options.forEachIndexed { optionIndex, option ->
                                            it.applyOption(option, stateIndex == optionIndex)
                                        }
                                    }
                                }
                            }
                            popupMenu?.dismiss()
                            popupMenu = menu
                            menu.onDismiss = { if (menu === popupMenu) popupMenu = null }
                            menu.show()
                        }
                    }
                    is SwitchOptionEntry.Menu -> Unit
                }
            }
        }
    }

    private val menuAdapter: SwitchOptionAdapter by lazy {
        object : SwitchOptionAdapter() {
            override val theme: Theme = this@SwitchOptionWindow.theme

            override fun onItemClick(
                view: View,
                entry: SwitchOptionEntry,
            ) {
                entry as? SwitchOptionEntry.Menu ?: return
                if (entry.item.action.isNotEmpty()) {
                    commonKeyboardActionListener.listener.onAction(KeyActionManager.getAction(entry.item.action))
                }
                // 默认点完就关：动作自己换了窗口（例如又打开其他面板）时就不再拉回键盘。
                if (entry.item.autoClose && windowManager.isAttached(this@SwitchOptionWindow)) {
                    windowManager.attachWindow(KeyboardWindow)
                }
            }
        }
    }

    /**
     * 功能面板条目构建规则。
     *
     * 预设键带 `toggle` 的条目是双态开关：点击翻转，开启时 label 高亮。条目没写 label 时
     * 跟随预设键 —— 双态取当前 state 文本，否则取预设键 label。icons 只作图标位的背景图片，
     * 按实际生效配色的深浅取：一项通用，两项为 [浅色, 深色]；非图片视同未配置。没有图片
     * 时图标位文字取自预设键 —— 双态取当前 state，否则取预设键 label，与条目 label 无关。
     */
    private fun buildMenuEntries(menu: MenuKeyboard): List<SwitchOptionEntry.Menu> {
        // 日夜跟随最终生效的方案：带 light_scheme 的是深色侧，带 dark_scheme 的是浅色侧
        // （见 evaluateActiveColorScheme）；手动选了暗色方案而系统是亮的也不会取错。
        val isNight = ColorManager.isDarkSchemeActive
        return menu.items.mapNotNull { item ->
            if (item.action.isEmpty()) {
                Timber.w("menu_keyboard 条目「%s」缺少 action，已跳过", item.label)
                return@mapNotNull null
            }
            val preset = theme.presetKeys[item.action]
            val isToggle = preset?.toggle?.isNotEmpty() == true
            val states = preset?.states.orEmpty()
            val isOn =
                if (isToggle) {
                    runCatching { rime.run { getRuntimeOption(preset!!.toggle) } }.getOrDefault(false)
                } else {
                    false
                }
            val label =
                item.label.ifEmpty {
                    when {
                        isToggle && states.size == 2 -> states[if (isOn) 1 else 0]
                        else -> preset?.label.orEmpty()
                    }
                }
            // icons 只作背景图片：一项通用，两项 [浅色, 深色]；非图片视同未配置。
            val iconStyle =
                when {
                    item.icons.size == 1 -> item.icons.first()
                    item.icons.size > 1 -> item.icons[if (isNight) 1 else 0]
                    else -> ""
                }.takeIf { it.matches(IMAGE_PATTERN) } ?: ""
            // 图标位文字取自预设键，和条目自身的 label 是两回事：双态取当前 state，否则取
            // 预设键 label。条目没写 label 时 label 同步 state，两处同文是预期。
            val iconText =
                if (iconStyle.isEmpty()) {
                    if (isToggle) states.getOrNull(if (isOn) 1 else 0).orEmpty() else preset?.label.orEmpty()
                } else {
                    ""
                }
            if (label.isEmpty() && iconStyle.isEmpty()) {
                Timber.w("menu_keyboard 条目「%s」缺 label/icons，预设键也无 label，已跳过", item.action)
                return@mapNotNull null
            }
            SwitchOptionEntry.Menu(item, label, iconStyle, iconText, isToggle && isOn)
        }
    }

    private val schemaPageView: View by lazy {
        context.recyclerView {
            layoutManager = gridLayoutManager(4)
            adapter = schemaAdapter
        }
    }

    private val functionPageView: View by lazy {
        context.recyclerView {
            layoutManager = gridLayoutManager(4)
            adapter = menuAdapter
        }
    }

    private val tabLayout: InputTabLayout by lazy { InputTabLayout(context) }

    private fun initialTab(): Int = (menuKeyboard?.tab ?: 0).coerceIn(0, 1)

    private val pagerView: ViewPager2 by lazy {
        context.view(::ViewPager2) {}.apply {
            adapter = PagesAdapter(listOf(schemaPageView, functionPageView))
        }
    }

    private class PagesAdapter(
        private val pages: List<View>,
    ) : RecyclerView.Adapter<PagesAdapter.PageViewHolder>() {
        class PageViewHolder(val container: FrameLayout) : RecyclerView.ViewHolder(container)

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): PageViewHolder {
            val container =
                FrameLayout(parent.context).apply {
                    layoutParams = coreLParams(matchParent, matchParent)
                    isSaveEnabled = false
                }
            return PageViewHolder(container)
        }

        override fun onBindViewHolder(
            holder: PageViewHolder,
            position: Int,
        ) {
            holder.container.removeAllViews()
            holder.container.addView(pages[position])
        }

        override fun getItemViewType(position: Int) = position

        override fun getItemCount() = pages.size
    }

    override fun onCreateView(): View {
        if (menuKeyboard == null) return schemaPageView
        tabLayout.onConfigureTab(pagerView) { tabUi, position ->
            tabUi.label.apply {
                setText(if (position == 0) R.string.switch_items else R.string.tools_panel)
                textSize = theme.generalStyle.candidateTextSize
                setTypeface(FontManager.getTypeface("candidate_font"), Typeface.BOLD)
                setTextColor(ColorManager.getColor("key_text_color"))
            }
        }
        return pagerView
    }

    private fun updateSchemaOptionEntries() {
        val switches = rime.schema.switches
        schemaAdapter.submitList(
            if (menuKeyboard == null) {
                listOf(
                    *staticEntries,
                    *switches.mapNotNull { SwitchOptionEntry.fromSwitch(rime, it) }.toTypedArray(),
                )
            } else {
                switches.mapNotNull { SwitchOptionEntry.fromSwitch(rime, it) }
            },
        )
        menuKeyboard?.let { menu -> menuAdapter.submitList(buildMenuEntries(menu)) }
    }

    override fun onRimeSchemaUpdated(schema: SchemaItem) {
        updateSchemaOptionEntries()
    }

    override fun onRimeOptionUpdated(value: RimeMessage.OptionMessage.Data) {
        updateSchemaOptionEntries()
    }

    private val settingsButton by lazy {
        ToolButton(context, R.drawable.ic_baseline_settings_24).apply {
            setOnClickListener { AppUtils.launchMainActivity(context) }
        }
    }

    private val toggleVisibilityButton by lazy {
        ToolButton(context, R.drawable.ic_baseline_visibility_24).apply {
            setOnClickListener {
                val newValue = !prefs.general.hideStaticSwitcher.getValue()
                prefs.general.hideStaticSwitcher.setValue(newValue)
                updateSchemaOptionEntries()
                updateToggleIcon()
            }
        }
    }

    private fun updateToggleIcon() {
        val hide = prefs.general.hideStaticSwitcher.getValue()
        toggleVisibilityButton.setIcon(
            if (hide) {
                R.drawable.ic_baseline_visibility_off_24
            } else {
                R.drawable.ic_baseline_visibility_24
            },
        )
    }

    private val barExternalView by lazy {
        context.constraintLayout {
            val size = dp(theme.generalStyle.run { candidateViewHeight + commentHeight })
            add(
                toggleVisibilityButton,
                lParams(size, size) {
                    before(settingsButton, dp(6))
                },
            )
            add(
                settingsButton,
                lParams(size, size) {
                    endOfParent()
                },
            )
        }
    }

    // 双 Tab 模式的标题栏：左 Tab、右设置；隐藏了眼睛按钮与静态入口（功能面板替代之）。
    private val tabBarView by lazy {
        context.constraintLayout {
            val size = dp(theme.generalStyle.run { candidateViewHeight + commentHeight })
            add(
                tabLayout,
                lParams(wrapContent, size) {
                    startOfParent()
                    centerVertically()
                },
            )
            add(
                settingsButton,
                lParams(size, size) {
                    endOfParent()
                    centerVertically()
                },
            )
        }
    }

    override fun onCreateBarView(): View = if (menuKeyboard == null) barExternalView else tabBarView

    override fun onAttached() {
        rime.launchOnReady { _ ->
            service.lifecycleScope.launch {
                updateSchemaOptionEntries()
                if (menuKeyboard == null) {
                    updateToggleIcon()
                } else {
                    pagerView.setCurrentItem(initialTab(), false)
                }
            }
        }
    }

    override fun onDetached() {
        popupMenu?.dismiss()
        popupMenu = null
    }
}
