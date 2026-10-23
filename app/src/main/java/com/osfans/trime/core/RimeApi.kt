/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.core

import kotlinx.coroutines.flow.SharedFlow

interface RimeApi {
    val messageFlow: SharedFlow<RimeMessage<*>>

    val isReady: Boolean

    val schemaCached: RimeSchema

    val statusCached: StatusProto

    val compositionCached: CompositionProto

    val hasMenu: Boolean

    val paging: Boolean

    suspend fun isEmpty(): Boolean

    suspend fun deploy()

    suspend fun updateConfig()

    suspend fun syncUserData(): Boolean

    suspend fun processKey(
        value: Int,
        modifiers: UInt = 0u,
        isVirtual: Boolean = true,
    ): Boolean

    suspend fun processKey(
        value: KeyValue,
        modifiers: KeyModifiers,
        isVirtual: Boolean = true,
    ): Boolean

    suspend fun simulateKeySequence(
        sequence: String,
    ): Boolean

    suspend fun selectCandidate(idx: Int, global: Boolean): Boolean

    suspend fun deleteCandidate(idx: Int, global: Boolean): Boolean

    suspend fun changeCandidatePage(backward: Boolean): Boolean

    suspend fun moveCursorPos(position: Int)

    suspend fun availableSchemata(): Array<SchemaItem>

    suspend fun enabledSchemata(): Array<SchemaItem>

    suspend fun setEnabledSchemata(schemaIds: Array<String>): Boolean

    suspend fun selectedSchemata(): Array<SchemaItem>

    suspend fun selectedSchemaId(): String

    suspend fun selectSchema(schemaId: String): Boolean

    suspend fun currentSchema(): RimeSchema

    suspend fun commitComposition(): Boolean

    suspend fun clearComposition()

    suspend fun getRawInput(): String

    suspend fun setRuntimeOption(
        option: String,
        value: Boolean,
    )

    suspend fun getRuntimeOption(option: String): Boolean

    /**
     * Sets [option] to what [transform] derives from its current value, as a single
     * engine operation. Returns the value the option had beforehand, or null when
     * rime had no session at all: while it has none every read answers "off" and
     * every write is silently dropped, so a read and a write issued separately can
     * turn that fake "off" into a real "on".
     *
     * The write is never skipped: rime's option notification is the only thing that
     * repaints the keys and re-syncs [statusCached], so a redundant-looking write is
     * what corrects a cache left behind by a dead session.
     */
    suspend fun updateRuntimeOption(
        option: String,
        transform: (Boolean) -> Boolean,
    ): Boolean?

    suspend fun setNullInputType(value: Boolean)

    suspend fun getCandidates(
        startIndex: Int,
        limit: Int,
    ): Array<CandidateProto>

    /**
     * Reads the candidates [sequence] would produce, without publishing the engine
     * response: the transient composition never reaches the preedit nor the
     * candidate window. Any commit the sequence yields is discarded, otherwise it
     * would sit in the session buffer and land in the editor on the next key.
     *
     * An empty result means the current schema does not know this sequence.
     */
    suspend fun peekCandidatesBySequence(
        sequence: String,
        limit: Int,
    ): Array<CandidateProto>

    /**
     * Types [sequence] for the caller, getting the two ascii switches out of the way
     * only for as long as the feed takes: in ascii mode the first key is rejected
     * before it reaches the input buffer, so a symbol preset would silently not be
     * inserted at all.
     *
     * The switches come back to the engine that was flipped — a schema switch or a
     * deployment rebuild discards them with the old engine. `ascii_mode` stays off
     * when the sequence left a composition pending, because ascii mode would append
     * the following keys to that code as raw input; that debt is then the liquid
     * keyboard's to settle, see [beginLiquidAsciiLease].
     */
    suspend fun insertSymbolSequence(sequence: String)

    /**
     * Borrows the ascii mode for the liquid keyboard's visit: true when the engine is in
     * ascii mode and the panel owes it back, false when there is nothing to give
     * (already 中文, or no session -- reads without one answer "off", which is not a mode
     * anyone chose).
     */
    suspend fun beginLiquidAsciiLease(): Boolean

    /**
     * Ends the borrow once nothing is composing: while a code is pending ascii mode would
     * append the following keys to it as raw input. True when the lease is over, including
     * the void case -- the marker died with a rebuilt engine, and replaying the old mode
     * onto the new one is exactly the intermittent english the lease is meant to avoid.
     */
    suspend fun endLiquidAsciiLease(): Boolean

    suspend fun setCandidatePagingMode(enabled: Boolean)
}
