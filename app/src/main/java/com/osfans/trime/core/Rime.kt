/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.core

import com.osfans.trime.BuildConfig
import com.osfans.trime.data.base.DataManager
import com.osfans.trime.data.opencc.OpenCCDictManager
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.ime.core.InlinePreeditMode
import com.osfans.trime.util.appContext
import com.osfans.trime.util.isStorageAvailable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import kotlin.time.Duration.Companion.minutes

/**
 * Rime JNI and instance methods
 *
 * @see [librime](https://github.com/rime/librime)
 */
class Rime :
    RimeApi,
    RimeLifecycleOwner {
    private val lifecycleRegistry = RimeLifecycleRegistry()
    override val lifecycle get() = lifecycleRegistry

    override val messageFlow = messageFlow_.asSharedFlow()

    override val isReady: Boolean
        get() = lifecycle.currentState == RimeLifecycle.State.READY

    // Written by rime's thread and read from the UI thread, so these need a
    // happens-before edge to publish their updates.
    @Volatile
    override var schemaCached = RimeSchema(".default")
        private set

    @Volatile
    override var statusCached = StatusProto()
        private set

    @Volatile
    override var compositionCached = CompositionProto()
        private set

    @Volatile
    override var hasMenu: Boolean = false
        private set

    @Volatile
    override var paging: Boolean = false
        private set

    private val dispatcher =
        RimeDispatcher(
            object : RimeDispatcher.RimeController {
                override fun nativeStartup() {
                    startRime(false)
                    lifecycleRegistry.emitState(RimeLifecycle.State.READY)
                }

                override fun nativeFinalize() {
                    exitRime()
                }
            },
        )

    private val inlinePreeditMode by AppPrefs.defaultInstance().general.inlinePreeditMode
    private val showAsciiSwitchTips by AppPrefs.defaultInstance().general.asciiSwitchTips

    private var asciiSwitchTipsJob: Job? = null
    private var deployStatusJob: Job? = null

    // future.get() 一旦被取走就作废，两次 join 只能排队而不能并发
    private val maintenanceJoinMutex = Mutex()

    // 活动键盘要求的 ascii 模式：重建的引擎不经过 attach，部署结束按它补一次
    @Volatile
    var keyboardAsciiMode: Boolean? = null
    private var isNullInputType = true
    private var lastAsciiTipsText = ""
    private var pagingMode = false

    init {
        if (lifecycle.currentState != RimeLifecycle.State.STOPPED) {
            throw IllegalStateException("Rime has already been created!")
        }
    }

    private suspend inline fun <T> withRimeContext(crossinline block: suspend () -> T): T = withContext(dispatcher) {
        block()
    }

    override suspend fun isEmpty(): Boolean = withRimeContext {
        getCurrentRimeSchema() == ".default" // 無方案
    }

    override suspend fun deploy() = RimeMaintenanceMutex.withLock {
        val deployFinished = CompletableDeferred<Boolean>()
        val deployHandler: (RimeMessage<*>) -> Unit = { message ->
            if (message is RimeMessage.DeployMessage) {
                when (message.data) {
                    RimeMessage.DeployMessage.State.Start -> Unit

                    RimeMessage.DeployMessage.State.Success -> {
                        deployFinished.complete(true)
                    }

                    RimeMessage.DeployMessage.State.Failure -> {
                        deployFinished.complete(false)
                    }
                }
            }
        }
        registerRimeMessageHandler(deployHandler)
        try {
            withRimeContext {
                exitRime()
                startRime(true)
            }
            val success =
                withContext(Dispatchers.IO) {
                    // withTimeoutOrNull rather than withTimeout: a timeout is a
                    // maintenance outcome, and letting it throw would surface as
                    // an uncaught coroutine exception in the callers (the intent
                    // receiver and the settings screen do not catch it).
                    withTimeoutOrNull(DEPLOY_TIMEOUT) {
                        deployFinished.await()
                    }
                }
            // A failed schema build is an expected maintenance result (for example when
            // a third-party schema dependency is missing). The native message handler
            // has already published a failure notification, so do not turn it into an
            // uncaught coroutine exception that crashes the whole IME process.
            when (success) {
                null -> Timber.w("Rime deploy timed out")
                false -> Timber.w("Rime deploy failed")
                true -> Unit
            }
        } finally {
            unregisterRimeMessageHandler(deployHandler)
        }
    }

    // Restarting the engine races with deploy and sync, which also tear the rime
    // session down and back up. Take the same maintenance lock they do.
    override suspend fun updateConfig() = RimeMaintenanceMutex.withLock {
        withRimeContext {
            exitRime()
            startRime(false)
        }
    }

    override suspend fun syncUserData(): Boolean = withRimeContext {
        syncRimeUserData()
    }

    override suspend fun processKey(
        value: Int,
        modifiers: UInt,
        isVirtual: Boolean,
    ): Boolean = withRimeContext {
        processKeyInner(value, modifiers.toInt(), isVirtual)
    }

    override suspend fun processKey(
        value: KeyValue,
        modifiers: KeyModifiers,
        isVirtual: Boolean,
    ): Boolean = withRimeContext {
        processKeyInner(value.value, modifiers.toInt(), isVirtual)
    }

    override suspend fun simulateKeySequence(sequence: String): Boolean = withRimeContext {
        Timber.d("simulateKeySequence: $sequence")
        if (simulateRimeKeySequence(sequence)) {
            val commit = getRimeCommit()
            val input = getRimeRawInput()
            if (!commit.text.isNullOrEmpty() || input.isNotEmpty()) {
                emitResponse(commit)
                true
            } else {
                emitResponse(CommitProto(sequence))
                false
            }
        } else {
            false
        }.also { Timber.d("simulateKeySequence ${if (it) "success" else "failed"}") }
    }

    override suspend fun selectCandidate(idx: Int, global: Boolean): Boolean = withRimeContext {
        selectRimeCandidate(idx, global).also { emitResponse() }
    }

    override suspend fun deleteCandidate(idx: Int, global: Boolean): Boolean = withRimeContext {
        deleteRimeCandidate(idx, global).also { emitResponse() }
    }

    override suspend fun changeCandidatePage(backward: Boolean): Boolean = withRimeContext {
        changeRimeCandidatePage(backward).also { emitResponse() }
    }

    override suspend fun moveCursorPos(position: Int) = withRimeContext {
        setRimeCaretPos(position)
        emitResponse()
    }

    override suspend fun availableSchemata(): Array<SchemaItem> = withRimeContext { getAvailableRimeSchemaList() }

    override suspend fun enabledSchemata(): Array<SchemaItem> = withRimeContext { getSelectedRimeSchemaList() }

    override suspend fun setEnabledSchemata(schemaIds: Array<String>) = withRimeContext { selectRimeSchemas(schemaIds) }

    override suspend fun selectedSchemata(): Array<SchemaItem> = withRimeContext { getRimeSchemaList() }

    override suspend fun selectedSchemaId(): String = withRimeContext { getCurrentRimeSchema() }

    override suspend fun selectSchema(schemaId: String) = withRimeContext { selectRimeSchema(schemaId) }

    override suspend fun currentSchema(): RimeSchema = withRimeContext {
        RimeSchema(getCurrentRimeSchema())
    }

    override suspend fun commitComposition(): Boolean = withRimeContext { commitRimeComposition().also { if (it) emitResponse() } }

    override suspend fun clearComposition() = withRimeContext {
        clearRimeComposition()
        emitResponse()
    }

    override suspend fun getRawInput(): String = withRimeContext {
        getRimeRawInput()
    }

    override suspend fun setRuntimeOption(
        option: String,
        value: Boolean,
    ): Unit = withRimeContext {
        setRimeOption(option, value)
    }

    override suspend fun getRuntimeOption(option: String): Boolean = withRimeContext {
        getRimeOption(option)
    }

    override suspend fun updateRuntimeOption(
        option: String,
        transform: (Boolean) -> Boolean,
    ): Boolean? = withRimeContext {
        // 无 session 时 get_current_schema 回答空串，而 set_option 会被 librime 静默丢弃
        if (getCurrentRimeSchema().isEmpty()) {
            null
        } else {
            val current = getRimeOption(option)
            // 写入即通知，通知是标签重绘与状态同步的唯一来源：这里不做"值没变就省一次"
            setRimeOption(option, transform(current))
            current
        }
    }

    override suspend fun setNullInputType(value: Boolean) = withRimeContext {
        isNullInputType = value
    }

    override suspend fun getCandidates(
        startIndex: Int,
        limit: Int,
    ): Array<CandidateProto> = withRimeContext {
        getRimeCandidates(startIndex, limit)
    }

    override suspend fun peekCandidatesBySequence(
        sequence: String,
        limit: Int,
    ): Array<CandidateProto> = withRimeContext {
        val hadComposition = getRimeRawInput().isNotEmpty()
        val asciiMode = getRimeOption("ascii_mode")
        val asciiPunch = getRimeOption("ascii_punch")
        val needsSwitch = asciiMode || asciiPunch
        clearRimeComposition()
        val candidates = try {
            // The symbol presets live behind '/', which both switches eat as-is.
            // Only flip a switch that is actually in the way: set_option notifies
            // listeners even when the value ends up unchanged.
            if (needsSwitch) {
                silencingOptionMessages = true
                setRimeOption(ASCII_LEASE_MARKER, true)
            }
            if (asciiMode) setRimeOption("ascii_mode", false)
            if (asciiPunch) setRimeOption("ascii_punch", false)
            readSymbolCandidates(sequence, limit)
        } finally {
            // 标记随旧引擎一起消失：引擎若被部署重建过，把英文写回去就是在制造偶发英文
            if (needsSwitch && getRimeOption(ASCII_LEASE_MARKER)) {
                if (asciiMode) setRimeOption("ascii_mode", true)
                if (asciiPunch) setRimeOption("ascii_punch", true)
            }
            silencingOptionMessages = false
            // 解除标记的通知照常外传，用来对齐缓存
            if (needsSwitch) setRimeOption(ASCII_LEASE_MARKER, false)
        }
        clearRimeComposition()
        getRimeCommit()
        // Nobody else emits, so a composition the caller could still see has to
        // be announced once it is gone.
        if (hadComposition) emitResponse()
        candidates
    }

    private fun readSymbolCandidates(
        sequence: String,
        limit: Int,
    ): Array<CandidateProto> {
        if (!simulateRimeKeySequence(sequence)) return emptyArray()
        // A commit means the engine consumed the sequence as ordinary input, i.e.
        // the schema has no symbol preset under this name.
        if (!getRimeCommit().text.isNullOrEmpty()) return emptyArray()
        return getRimeCandidates(0, limit)
    }

    override suspend fun insertSymbolSequence(sequence: String) = withRimeContext<Unit> {
        val asciiMode = getRimeOption("ascii_mode")
        val asciiPunch = getRimeOption("ascii_punch")
        val needsSwitch = asciiMode || asciiPunch
        try {
            if (needsSwitch) setRimeOption(ASCII_LEASE_MARKER, true)
            if (asciiMode) setRimeOption("ascii_mode", false)
            if (asciiPunch) setRimeOption("ascii_punch", false)
            clearComposition()
            simulateKeySequence(sequence)
        } finally {
            // 与预读同一判据：标记没了说明引擎换过，这时把英文写回去就是偶发英文
            if (needsSwitch && getRimeOption(ASCII_LEASE_MARKER)) {
                if (asciiPunch) setRimeOption("ascii_punch", true)
                // 还挂着待选编码就留在中文：英文态会把后续按键当原始输入塞进编码
                if (asciiMode && getRimeRawInput().isEmpty()) setRimeOption("ascii_mode", true)
            }
            if (needsSwitch) setRimeOption(ASCII_LEASE_MARKER, false)
        }
    }

    override suspend fun beginLiquidAsciiLease(): Boolean = withRimeContext {
        // 无 session 时读到的英文态是 JNI 默认值，不是用户选的，不能据此接租约
        if (getCurrentRimeSchema().isEmpty()) {
            false
        } else if (!getRimeOption("ascii_mode")) {
            false
        } else {
            setRimeOption(LIQUID_LEASE_MARKER, true)
            true
        }
    }

    override suspend fun endLiquidAsciiLease(): Boolean = withRimeContext {
        // 标记随旧引擎一起消失：引擎换过之后把英文写回去就是偶发英文，租约作废
        if (!getRimeOption(LIQUID_LEASE_MARKER)) {
            true
        } else if (getRimeRawInput().isNotEmpty()) {
            // 待选编码还挂着：英文态会把后续按键当原始输入塞进编码
            false
        } else {
            // 写入即通知，通知是标签重绘与状态同步的唯一来源：这里不做"值没变就省一次"
            setRimeOption("ascii_mode", true)
            setRimeOption(LIQUID_LEASE_MARKER, false)
            true
        }
    }

    override suspend fun setCandidatePagingMode(enabled: Boolean) = withRimeContext {
        pagingMode = enabled
        emitResponse()
    }

    private fun startRime(fullCheck: Boolean) {
        DataManager.sync()
        val sharedDataDir = DataManager.sharedDataDir.absolutePath
        val userDataDir = DataManager.userDataDir.absolutePath
        Timber.d(
            """
            Starting rime with:
            sharedDataDir: $sharedDataDir
            userDataDir: $userDataDir
            fullCheck: $fullCheck
            """.trimIndent(),
        )
        startupRime(sharedDataDir, userDataDir, BuildConfig.BUILD_VERSION_NAME, fullCheck)
    }

    private fun processKeyInner(value: Int, modifiers: Int, isVirtual: Boolean): Boolean {
        lastAsciiTipsText = asciiTipsText(getRimeStatus())
        val handled = processRimeKey(value, modifiers)
        emitResponse()
        if (!handled) {
            handleRimeMessage(
                10, // RimeMessage.MessageType.Key,
                arrayOf(value, modifiers, isVirtual),
            )
        }
        return handled
    }

    private fun asciiTipsText(status: StatusProto): String = when {
        status.isAsciiMode -> "En"
        status.schemaName.isNotEmpty() && !status.schemaName.startsWith('.') ->
            status.schemaName.take(2)
        else -> ""
    }

    private fun emitResponse(commit: CommitProto? = null) {
        val response = getRimeResponse(pagingMode)
        handleRimeMessage(4, arrayOf(commit ?: response.commit))
        handlePreedit(response.composition)
        if (response.composition.length <= 0 && lastAsciiTipsText != asciiTipsText(response.status)) {
            showAsciiSwitchTips(response.status)
        }
        when (val candidates = response.candidates) {
            is Candidates.Paged -> handleRimeMessage(7, arrayOf(candidates))
            is Candidates.Bulk -> handleRimeMessage(9, arrayOf(candidates))
        }
        handleRimeMessage(8, arrayOf(response.status))
    }

    private fun handlePreedit(composition: CompositionProto) {
        val mode = if (isNullInputType) {
            InlinePreeditMode.DISABLE
        } else {
            inlinePreeditMode
        }
        val inlinePreedit = when (mode) {
            InlinePreeditMode.DISABLE -> ""
            InlinePreeditMode.COMPOSING_TEXT -> composition.preedit ?: ""
            InlinePreeditMode.COMMIT_TEXT_PREVIEW -> composition.commitTextPreview ?: ""
        }
        val composition = if (mode == InlinePreeditMode.COMPOSING_TEXT) {
            CompositionProto()
        } else {
            composition
        }
        handleRimeMessage(5, arrayOf(inlinePreedit))
        handleRimeMessage(6, arrayOf(composition))
    }

    private fun handleRimeMessage(it: RimeMessage<*>) {
        when (it) {
            is RimeMessage.SchemaMessage -> {
                statusCached = getRimeStatus()
                schemaCached = RimeSchema(it.data.id)
            }
            is RimeMessage.OptionMessage -> {
                // Option change won't trigger response update
                val wasAsciiMode = statusCached.isAsciiMode
                val status = getRimeStatus()
                statusCached = status
                updateSchemaCached(status)
                // librime notifies on every set_option, even one that changes
                // nothing; the tip is only worth showing when the mode moved.
                if (it.data.option == "ascii_mode" && wasAsciiMode != status.isAsciiMode) {
                    showAsciiSwitchTips(status)
                }
            }
            is RimeMessage.DeployMessage -> {
                when (it.data) {
                    RimeMessage.DeployMessage.State.Start -> {
                        OpenCCDictManager.buildOpenCCDict()
                        deployStatusJob?.cancel()
                    }
                    // 结束通知是维护线程在返回之前发的，所以收到它还得等线程
                    RimeMessage.DeployMessage.State.Success,
                    RimeMessage.DeployMessage.State.Failure,
                    -> publishStatusAfterDeploy()
                }
            }
            is RimeMessage.CompositionMessage -> {
                val composition = it.data
                compositionCached = composition
            }
            is RimeMessage.PagedCandidatesMessage -> {
                val paged = it.data
                paging = paged.hasPrevPage
                hasMenu = paged.candidates.isNotEmpty()
            }
            is RimeMessage.BulkCandidatesMessage -> {
                hasMenu = it.data.candidates.isNotEmpty()
            }
            is RimeMessage.StatusMessage -> {
                statusCached = it.data
                updateSchemaCached(it.data)
            }
            else -> {}
        }
    }

    private fun updateSchemaCached(status: StatusProto) {
        val (schemaId, schemaName) = status
        // Engine response update won't send SchemaMessage, but usually update RimeStatus
        if (schemaId != schemaCached.schemaId) {
            schemaCached = RimeSchema(schemaId)
            // notify downstream consumers that schema has changed
            messageFlow_.tryEmit(
                RimeMessage.SchemaMessage(
                    SchemaItem(schemaId, schemaName),
                ),
            )
        }
    }

    // 部署后补一次真实状态：方案变了由 updateSchemaCached 通知出去，中英按键盘约定还回来
    private fun publishStatusAfterDeploy() {
        deployStatusJob?.cancel()
        deployStatusJob = lifecycleScope.launch {
            maintenanceJoinMutex.withLock {
                withContext(Dispatchers.IO) { joinRimeMaintenanceThread() }
            }
            val status = withRimeContext { getRimeStatus() }
            // 走通知而非直写缓存：方案同步与键面重绘都以通知为唯一来源
            if (status.schemaId.isEmpty()) return@launch
            handleRimeMessage(8, arrayOf(status))
            // 重建的引擎回方案默认，键盘的约定在这期间没人兑现，补一次真实写入让通知重画
            val target = keyboardAsciiMode ?: return@launch
            if (target != status.isAsciiMode) {
                withRimeContext { setRimeOption("ascii_mode", target) }
            }
        }
    }

    private fun showAsciiSwitchTips(status: StatusProto) {
        if (!showAsciiSwitchTips) return
        val tipsText = asciiTipsText(status)
        if (tipsText.isEmpty()) return

        lastAsciiTipsText = tipsText

        val tips = CompositionProto(tipsText)
        messageFlow_.tryEmit(RimeMessage.CompositionMessage(tips))
        compositionCached = tips
        asciiSwitchTipsJob?.cancel()
        asciiSwitchTipsJob = lifecycleScope.launch {
            delay(1000L)
            // This scope runs on Dispatchers.Default, but librime is not
            // thread-safe. Hop onto rime's single thread before touching the
            // native layer, as every other call site does.
            withRimeContext {
                val ctx = getRimeContext()
                handleRimeMessage(6, arrayOf(ctx.composition))
            }
        }
    }

    fun startup() {
        if (!appContext.isStorageAvailable()) {
            Timber.w("Skip starting rime: storage not available!")
            return
        }
        if (lifecycle.currentState != RimeLifecycle.State.STOPPED) {
            Timber.w("Skip starting rime: not at stopped state!")
            return
        }
        registerRimeMessageHandler(::handleRimeMessage)
        lifecycleRegistry.emitState(RimeLifecycle.State.STARTING)
        dispatcher.start()
    }

    fun finalize() {
        if (lifecycle.currentState != RimeLifecycle.State.READY) {
            Timber.w("Skip stopping rime: not at ready state!")
            return
        }
        lifecycleRegistry.emitState(RimeLifecycle.State.STOPPING)
        Timber.i("Rime finalize()")
        dispatcher.stop().let {
            if (it.isNotEmpty()) {
                Timber.w("${it.size} job(s) didn't get a chance to run!")
            }
        }
        lifecycleRegistry.emitState(RimeLifecycle.State.STOPPED)
        unregisterRimeMessageHandler(::handleRimeMessage)
    }

    companion object {
        private val DEPLOY_TIMEOUT = 15.minutes

        // `_` 前缀是 session 局部选项，换方案或引擎重建即消失：标记临时让路属于哪台引擎
        private const val ASCII_LEASE_MARKER = "_trime_ascii_lease"

        // 同上，但活过一次按键：液态面板期间的英文态归属，编码清空才结算
        private const val LIQUID_LEASE_MARKER = "_trime_liquid_lease"

        // 预读期间吞掉开关通知防中间态外泄
        @Volatile
        private var silencingOptionMessages = false

        private val messageFlow_ =
            MutableSharedFlow<RimeMessage<*>>(
                extraBufferCapacity = 15,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )

        private val rimeMessageHandlers = ArrayList<(RimeMessage<*>) -> Unit>()

        init {
            System.loadLibrary("rime_jni")
        }

        // init
        @JvmStatic
        external fun startupRime(
            sharedDir: String,
            userDir: String,
            versionName: String,
            fullCheck: Boolean,
        )

        @JvmStatic
        external fun exitRime()

        @JvmStatic
        external fun joinRimeMaintenanceThread()

        @JvmStatic
        external fun deployRimeSchemaFile(schemaFile: String): Boolean

        @JvmStatic
        external fun deployRimeConfigFile(
            fileName: String,
            versionKey: String,
        ): Boolean

        @JvmStatic
        external fun syncRimeUserData(): Boolean

        // input
        @JvmStatic
        external fun processRimeKey(
            keycode: Int,
            mask: Int,
        ): Boolean

        @JvmStatic
        external fun commitRimeComposition(): Boolean

        @JvmStatic
        external fun clearRimeComposition()

        // output
        @JvmStatic
        external fun getRimeCommit(): CommitProto

        @JvmStatic
        external fun getRimeContext(): ContextProto

        @JvmStatic
        external fun getRimeStatus(): StatusProto

        // runtime options
        @JvmStatic
        external fun setRimeOption(
            option: String,
            value: Boolean,
        )

        @JvmStatic
        external fun getRimeOption(option: String): Boolean

        @JvmStatic
        external fun getRimeSchemaList(): Array<SchemaItem>

        @JvmStatic
        external fun getCurrentRimeSchema(): String

        @JvmStatic
        external fun selectRimeSchema(schemaId: String): Boolean

        // testing
        @JvmStatic
        external fun simulateRimeKeySequence(keySequence: String): Boolean

        @JvmStatic
        external fun getRimeRawInput(): String

        @JvmStatic
        external fun getRimeCaretPos(): Int

        @JvmStatic
        external fun setRimeCaretPos(caretPos: Int)

        @JvmStatic
        external fun selectRimeCandidate(index: Int, global: Boolean): Boolean

        @JvmStatic
        external fun deleteRimeCandidate(index: Int, global: Boolean): Boolean

        @JvmStatic
        external fun changeRimeCandidatePage(backward: Boolean): Boolean

        @JvmStatic
        external fun getAvailableRimeSchemaList(): Array<SchemaItem>

        @JvmStatic
        external fun getSelectedRimeSchemaList(): Array<SchemaItem>

        @JvmStatic
        external fun selectRimeSchemas(schemaIds: Array<String>): Boolean

        @JvmStatic
        external fun getRimeCandidates(
            startIndex: Int,
            limit: Int,
        ): Array<CandidateProto>

        @JvmStatic
        external fun getRimeResponse(pagingMode: Boolean): RimeResponse

        @JvmStatic
        fun handleRimeMessage(
            type: Int,
            params: Array<Any>,
        ) {
            val message = RimeMessage.nativeCreate(type, params)
            if (message is RimeMessage.OptionMessage && silencingOptionMessages) return
            Timber.d("Handling $message")
            rimeMessageHandlers.forEach { it.invoke(message) }
            messageFlow_.tryEmit(message)
        }

        private fun registerRimeMessageHandler(handler: (RimeMessage<*>) -> Unit) {
            if (rimeMessageHandlers.contains(handler)) return
            rimeMessageHandlers.add(handler)
        }

        private fun unregisterRimeMessageHandler(handler: (RimeMessage<*>) -> Unit) {
            rimeMessageHandlers.remove(handler)
        }
    }
}
