/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.symbol

import android.view.View
import androidx.core.content.ContextCompat
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayoutManager
import com.google.android.flexbox.JustifyContent
import com.osfans.trime.core.CompositionProto
import com.osfans.trime.core.RimeMessage
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.daemon.launchOnReady
import com.osfans.trime.data.SymbolHistory
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.model.LiquidKeyboard
import com.osfans.trime.ime.bar.ui.LiquidKeyboardNavBar
import com.osfans.trime.ime.broadcast.InputBroadcastReceiver
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.CommonKeyboardActionListener
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.window.BoardWindow
import com.osfans.trime.ime.window.BoardWindowManager
import com.osfans.trime.ime.window.ResidentWindow
import org.kodein.di.instance
import splitties.dimensions.dp

class LiquidWindow :
    BoardWindow.BarBoardWindow(),
    ResidentWindow,
    InputBroadcastReceiver {
    override val showTitle: Boolean
        get() = liquidLayout.isNavbarMode

    private val service: TrimeInputMethodService by di.instance()
    private val rime: RimeSession by di.instance()
    private val theme: Theme by di.instance()
    private val windowManager: BoardWindowManager by di.instance()
    private val commonKeyboardActionListener: CommonKeyboardActionListener by di.instance()
    private val navBar by lazy { LiquidKeyboardNavBar(context, theme) }

    private lateinit var liquidLayout: LiquidLayout
    private val symbolHistory = SymbolHistory(180)
    var currentDataType: LiquidData.Type = LiquidData.Type.SINGLE
        private set
    private var currentTabIndex: Int = -1

    /** 分类层的当前位置；小于 0 表示还在主 tab 层。 */
    private var currentGroupIndex: Int = -1

    /** 面板借来的英文态：只在英文模式进场时持有，还掉或引擎重建即结束。 */
    private var asciiLeaseHeld = false

    private val adapter by lazy {
        LiquidAdapter(theme) {
            when (currentDataType) {
                LiquidData.Type.SYMBOL -> triggerSymbolInput(this.text)
                LiquidData.Type.GROUPS -> openGroup(it)
                LiquidData.Type.VAR_LENGTH -> {
                    // VAR_LENGTH类型：如果altText和text不同，说明是键值对，像SYMBOL一样处理
                    // 如果altText和text相同，说明是纯文本，像SINGLE一样直接上屏
                    if (this.altText != this.text) {
                        triggerSymbolInput(this.text)
                    } else {
                        service.commitText(this.text)
                        symbolHistory.insert(this.text)
                        symbolHistory.save()
                    }
                }
                LiquidData.Type.TABS -> {
                    val realPosition = LiquidData.getTagList()
                        .indexOfFirst { it.label == this.text }
                    setDataByIndex(realPosition)
                }
                else -> {
                    service.commitText(this.text)
                    if (currentDataType != LiquidData.Type.HISTORY) {
                        symbolHistory.insert(this.text)
                        symbolHistory.save()
                    }
                }
            }
        }
    }

    private fun justifyContentOf(dataType: LiquidData.Type) = if (LiquidData.isVarLengthType(dataType)) JustifyContent.SPACE_BETWEEN else JustifyContent.FLEX_START

    private fun newLayoutManager(align: Int) = FlexboxLayoutManager(context).apply {
        flexDirection = FlexDirection.ROW
        flexWrap = FlexWrap.WRAP
        justifyContent = align
    }

    companion object : ResidentWindow.Key {
        private const val GROUP_SYMBOL_LIMIT = 512
    }

    override val key: ResidentWindow.Key
        get() = LiquidWindow

    override fun onCreateView(): View = LiquidLayout(context, theme, commonKeyboardActionListener).apply {
        liquidLayout = this
        tabsUi.apply {
            setTags(LiquidData.getTagList())
            setOnTabClickListener { i ->
                if (currentGroupIndex >= 0) openGroup(i) else setDataByIndex(i)
            }
        }
        recyclerView.apply {
            layoutManager = newLayoutManager(justifyContentOf(currentDataType))
            this.adapter = this@LiquidWindow.adapter
        }
    }

    override fun onCreateBarView(): View? = if (liquidLayout.isNavbarMode) {
        navBar.createLiquidNavBar(
            fixedKeys = theme.liquidKeyboard.fixedKeyBar.keys,
            actionListener = commonKeyboardActionListener,
        )
    } else {
        liquidLayout.tabsUi.root
    }

    override fun onAttached() {
        if (asciiLeaseHeld) return
        service.postRimeJob {
            val held = beginLiquidAsciiLease()
            ContextCompat.getMainExecutor(service).execute {
                asciiLeaseHeld = held
                // 借到租约时面板已经收走了：当场还掉，不留到下一次编码清空
                if (held && !windowManager.isAttached(this@LiquidWindow)) releaseAsciiLease()
            }
        }
    }

    override fun onDetached() {
        releaseAsciiLease()
    }

    override fun onCompositionUpdate(data: CompositionProto) {
        // 编码落地或清掉的一刻才轮到还租约：那时英文态不会再把后续按键塞进编码
        if (data.length == 0) releaseAsciiLease()
    }

    override fun onRimeOptionUpdated(value: RimeMessage.OptionMessage.Data) {
        // 只把"写回英文"当外来改动：符号写入留下的中文是我们自己的，通知排到收走之后也不该作废租约
        if (asciiLeaseHeld &&
            value.option == "ascii_mode" &&
            value.value &&
            !windowManager.isAttached(this)
        ) {
            asciiLeaseHeld = false
        }
    }

    private fun releaseAsciiLease() {
        if (!asciiLeaseHeld) return
        service.postRimeJob {
            if (endLiquidAsciiLease()) {
                ContextCompat.getMainExecutor(service).execute { asciiLeaseHeld = false }
            }
        }
    }

    fun setDataByIndex(
        i: Int,
        openFirstGroup: Boolean = true,
    ) {
        // flex 布局防抖：不重复提交同一个 tab；分类层放行才能退回主 tab 层。
        if (currentTabIndex == i && currentGroupIndex < 0) {
            return
        }
        currentTabIndex = i
        currentGroupIndex = -1

        val tag = LiquidData.getTagList()[i]
        currentDataType = tag.type
        liquidLayout.tabsUi.setTags(LiquidData.getTagList())
        liquidLayout.tabsUi.setGroupLabel(null, null)
        liquidLayout.tabsUi.activateTab(i)

        val data = when (tag.type) {
            LiquidData.Type.HISTORY -> {
                symbolHistory.load()
                symbolHistory.toOrderedList().map { LiquidKeyboard.KeyItem(it) }
            }
            else -> {
                LiquidData.getDataByIndex(i)
            }
        }
        // 分类 tab 直接落到第一个分类：取到了才渲染，面板不会先出分类块再跳一次。
        // 组名退回主 tab 层时必须关掉它，否则退回等于再进一次第一个分类。
        if (openFirstGroup && tag.type == LiquidData.Type.GROUPS && data.isNotEmpty()) {
            openGroup(0, data)
            return
        }
        submitData(data)
    }

    /** 分类的符号由 rime 给出，取到了才进分类层；方案不认识这个序列时留在当前屏。 */
    private fun openGroup(
        i: Int,
        fallback: List<LiquidKeyboard.KeyItem>? = null,
    ) {
        if (i == currentGroupIndex) {
            return
        }
        val tabIndex = currentTabIndex
        val schemaId = rime.schema.schemaId
        val cached = LiquidData.getGroupSymbols(tabIndex, i, schemaId)
        if (cached != null) {
            showGroup(tabIndex, i, cached)
            return
        }
        val sequence = LiquidData.getDataByIndex(tabIndex).getOrNull(i)?.text
        if (sequence == null) {
            fallback?.let { submitData(it) }
            return
        }
        rime.launchOnReady { api ->
            val symbols = api.peekCandidatesBySequence(sequence, GROUP_SYMBOL_LIMIT)
                .map { LiquidKeyboard.KeyItem(it.text) }
            ContextCompat.getMainExecutor(service).execute {
                if (currentTabIndex != tabIndex ||
                    tabIndex >= LiquidData.getTagList().size ||
                    rime.schema.schemaId != schemaId
                ) {
                    return@execute
                }
                if (symbols.isEmpty()) {
                    // 只有等着进分类层的那一次需要退回分类块
                    fallback?.let { submitData(it) }
                    return@execute
                }
                LiquidData.putGroupSymbols(tabIndex, i, schemaId, symbols)
                showGroup(tabIndex, i, symbols)
            }
        }
    }

    private fun showGroup(
        tabIndex: Int,
        i: Int,
        symbols: List<LiquidKeyboard.KeyItem>,
    ) {
        val entersGroupLevel = currentGroupIndex < 0
        currentGroupIndex = i
        currentDataType = LiquidData.Type.VAR_LENGTH
        if (entersGroupLevel) {
            val tags = LiquidData.getDataByIndex(tabIndex)
                .map { LiquidData.Tag(it.altText.ifEmpty { it.text }, LiquidData.Type.GROUPS) }
            liquidLayout.tabsUi.setTags(tags)
            liquidLayout.tabsUi.setGroupLabel(LiquidData.getTagList()[tabIndex].label) {
                setDataByIndex(tabIndex, openFirstGroup = false)
            }
        }
        liquidLayout.tabsUi.activateTab(i)
        submitData(symbols)
    }

    private fun submitData(data: List<LiquidKeyboard.KeyItem>) {
        // 模式和列表在同一次提交里落地，绑定到的条目不会拿到上一个模式的宽度
        applyLayoutManager(currentDataType)
        adapter.currentDataType = currentDataType
        val renderable = if (LiquidData.isVarLengthType(currentDataType)) {
            // 不可见的占位撑满末行，SPACE_BETWEEN 就不会把最后一行拉开
            data + List(calculatePlaceholderCount()) { LiquidKeyboard.KeyItem("", "") }
        } else {
            data
        }
        adapter.submitList(renderable)
    }

    /** 换模式只能换掉 LayoutManager：Flexbox 的 flex 行和按位置的测量缓存不随整表刷新失效。 */
    private fun applyLayoutManager(dataType: LiquidData.Type) {
        val recyclerView = liquidLayout.recyclerView
        val align = justifyContentOf(dataType)
        if ((recyclerView.layoutManager as? FlexboxLayoutManager)?.justifyContent == align) {
            return
        }
        recyclerView.layoutManager = newLayoutManager(align)
    }

    private fun calculatePlaceholderCount(): Int {
        val recyclerView = liquidLayout.recyclerView
        val containerWidth = recyclerView.width
        if (containerWidth <= 0) return 0

        val singleWidth = context.dp(theme.liquidKeyboard.singleWidth)
        val marginX = context.dp(theme.liquidKeyboard.marginX).toInt()
        val itemTotalWidth = singleWidth + 2 * marginX
        if (itemTotalWidth <= 0) return 0

        return containerWidth / itemTotalWidth
    }

    private fun triggerSymbolInput(symbol: String) {
        rime.launchOnReady {
            it.insertSymbolSequence(symbol)
            ContextCompat.getMainExecutor(service).execute {
                windowManager.attachWindow(KeyboardWindow)
            }
        }
    }
}
