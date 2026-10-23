// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.daemon

import com.osfans.trime.core.CompositionProto
import com.osfans.trime.core.RimeApi
import com.osfans.trime.core.RimeSchema
import com.osfans.trime.core.StatusProto
import kotlinx.coroutines.CoroutineScope

/**
 * A interface to run different operations on RimeApi
 */
interface RimeSession {
    /**
     * Cached rime status.
     *
     * These cached accessors never block and never touch the native layer, so they
     * are safe to call from the UI thread while drawing. Use them instead of
     * [run] `{ statusCached }` in hot paths: [run] wraps the block in an event
     * loop and, for anything that has to reach rime's thread, blocks the caller
     * until it answers.
     */
    val status: StatusProto

    /** Cached "the candidate list has a previous page" flag. Never blocks. */
    val paging: Boolean

    /** Cached "the candidate list is not empty" flag. Never blocks. */
    val hasMenu: Boolean

    /** Cached composition snapshot. Never blocks. */
    val composition: CompositionProto

    /** Cached schema of the current session. Never blocks. */
    val schema: RimeSchema

    /** The ascii mode the active keyboard asks for; replayed once after a deploy rebuild. */
    var keyboardAsciiMode: Boolean?

    /**
     * Run an operation immediately
     * The suspended [block] will be executed in caller's thread.
     * Use this function only for non-blocking operations like
     * accessing [RimeApi.messageFlow].
     */
    fun <T> run(block: suspend RimeApi.() -> T): T

    /**
     * Run an operation immediately if rime is at ready state.
     * Otherwise, caller will be suspended until rime is ready and operation is done.
     * The [block] will be executed in caller's thread.
     * Client should use this function in most cases.
     */
    suspend fun <T> runOnReady(block: suspend RimeApi.() -> T): T

    /**
     * Run an operation if rime is at ready state.
     * Otherwise, do nothing.
     * The [block] will be executed in executed in thread pool.
     * This function does not block or suspend the caller.
     */
    fun runIfReady(block: suspend RimeApi.() -> Unit)

    val lifecycleScope: CoroutineScope
}
