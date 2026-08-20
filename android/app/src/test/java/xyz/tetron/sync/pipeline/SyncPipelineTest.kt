// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.pipeline

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.tetron_mobile_sync.SyncException
import uniffi.tetron_mobile_sync.SyncProgressEvent
import uniffi.tetron_mobile_sync.SyncTransferOutcome
import xyz.tetron.sync.bridge.BridgePeer
import xyz.tetron.sync.bridge.BridgeResponse
import xyz.tetron.sync.bridge.BridgeSnapshot
import xyz.tetron.sync.bridge.BridgeTunnelState
import xyz.tetron.sync.bridge.ConnKind
import xyz.tetron.sync.bridge.MeshBridge
import xyz.tetron.sync.bridge.StatusCaller
import xyz.tetron.sync.delete.DeleteAfterBackupConfig
import xyz.tetron.sync.gates.GateConfig
import xyz.tetron.sync.gates.GateNotificationCoalescer
import xyz.tetron.sync.gates.GateReason

/**
 * SYNC-005 ACCEPTANCE (Android-side): gate short-circuit (never invoking the
 * transfer runner when blocked), history recording (added/skipped/failed +
 * interrupted-is-not-a-failure), reentrancy (a mid-run trigger is a no-op),
 * and coalesced notification wiring. The engine's own byte-identical/resume
 * behavior is covered at the Rust level (`tests/engine_rsyncd.rs`,
 * SYNC-002) -- this suite fakes [TransferRunner] so it never touches the
 * native `.so`.
 */
class SyncPipelineTest {

    private val target = SyncTarget(displayName = "aorus", meshIp = "10.10.0.2", module = "photos")
    private val openPeer = BridgePeer(hostname = "aorus", ip = "10.10.0.2", connKind = ConnKind.Direct)
    private val openDeviceState = object : DeviceStateProvider {
        override fun isWifiConnected() = true
        override fun isCharging() = true
        override fun batteryPercent() = 100
    }

    private fun bridgeWith(snapshot: BridgeSnapshot?): MeshBridge {
        val caller = StatusCaller {
            snapshot?.let { BridgeResponse.Snapshot(it) } ?: BridgeResponse.Unavailable
        }
        return MeshBridge(caller, cacheTtlMillis = 0)
    }

    private fun openSnapshot(peers: List<BridgePeer> = listOf(openPeer)) =
        BridgeSnapshot(
            state = BridgeTunnelState.Active,
            network = "mesh0",
            ownMeshIp = "10.10.0.1",
            subnet = "10.10.0.0/24",
            peers = peers,
            updatedAtMillis = 1L,
        )

    private fun pipeline(
        snapshot: BridgeSnapshot? = openSnapshot(),
        targetProvider: TargetProvider = TargetProvider { target },
        sourcePathProvider: SourcePathProvider = SourcePathProvider { "/dcim/camera" },
        deviceState: DeviceStateProvider = openDeviceState,
        transferRunner: TransferRunner,
        historyStore: RunHistoryStore = InMemoryRunHistoryStore(),
        gateConfig: GateConfig = GateConfig(),
        coalescer: GateNotificationCoalescer = GateNotificationCoalescer(),
        onNotify: (GateReason) -> Unit = {},
        deleteConfig: DeleteAfterBackupConfig = DeleteAfterBackupConfig(),
        deletionRequester: xyz.tetron.sync.delete.DeletionRequester = xyz.tetron.sync.delete.DeletionRequester {},
    ) = SyncPipeline(
        bridge = bridgeWith(snapshot),
        targetProvider = targetProvider,
        sourcePathProvider = sourcePathProvider,
        deviceState = deviceState,
        transferRunner = transferRunner,
        historyStore = historyStore,
        coalescer = coalescer,
        gateConfig = gateConfig,
        onNotify = onNotify,
        deleteConfig = deleteConfig,
        deletionRequester = deletionRequester,
    )

    private fun transferEvent(path: String, isTransfer: Boolean = true, isFinal: Boolean = true) =
        SyncProgressEvent(
            path = path,
            totalBytes = null,
            overallTransferred = 0UL,
            overallTotalBytes = null,
            filesDone = 1UL,
            filesTotal = 1UL,
            flistEof = true,
            transferComplete = false,
            isTransfer = isTransfer,
            isFinal = isFinal,
        )

    private fun outcome(filesCopied: Int, filesTotal: Int, ioErrorExitCode: Int? = null) =
        SyncTransferOutcome(
            filesCopied = filesCopied.toULong(),
            filesTotal = filesTotal.toULong(),
            bytesCopied = 0UL,
            bytesSent = 0UL,
            matchedBytes = 0UL,
            ioErrorExitCode = ioErrorExitCode,
        )

    @Test
    fun blockedGate_neverInvokesRunner_andRecordsNoHistory() {
        var invoked = false
        val history = InMemoryRunHistoryStore()
        val pipeline = pipeline(
            snapshot = openSnapshot().copy(state = BridgeTunnelState.Standby),
            transferRunner = { _, _, _, _ -> invoked = true; outcome(1, 1) },
            historyStore = history,
        )

        val result = pipeline.run()

        assertEquals(PipelineResult.Gated(GateReason.TunnelNotActive), result)
        assertFalse("a blocked gate must never invoke the transfer runner", invoked)
        assertNull(history.lastRun())
    }

    @Test
    fun allowedRun_recordsSuccess() {
        val history = InMemoryRunHistoryStore()
        val pipeline = pipeline(
            transferRunner = { _, _, _, _ -> outcome(filesCopied = 3, filesTotal = 5) },
            historyStore = history,
        )

        val result = pipeline.run()

        val record = (result as PipelineResult.Ran).record
        assertEquals(3, record.added)
        assertEquals(2, record.skipped)
        assertEquals(0, record.failed)
        assertFalse(record.interrupted)
        assertEquals(record, history.lastRun())
    }

    @Test
    fun interruptedTransfer_isNotRecordedAsFailure() {
        val pipeline = pipeline(
            transferRunner = { _, _, _, _ -> outcome(filesCopied = 2, filesTotal = 4, ioErrorExitCode = 23) },
        )

        val record = (pipeline.run() as PipelineResult.Ran).record

        assertTrue("interrupted must be flagged", record.interrupted)
        assertEquals("an interrupted run is NOT a failure (spec/sync.py SYNC-005)", 0, record.failed)
    }

    @Test
    fun hardEngineFailure_recordsFailedWithReason_doesNotThrow() {
        val history = InMemoryRunHistoryStore()
        val pipeline = pipeline(
            transferRunner = { _, _, _, _ -> throw SyncException.Engine(exitCode = 1, detail = "connection refused") },
            historyStore = history,
        )

        val record = (pipeline.run() as PipelineResult.Ran).record

        assertEquals(1, record.failed)
        assertEquals(0, record.added)
        assertFalse(record.interrupted)
        assertTrue(record.failureReason!!.contains("connection refused"))
        assertEquals(record, history.lastRun())
    }

    @Test
    fun reentrantCall_whileRunInProgress_isNoOp() {
        val startedLatch = CountDownLatch(1)
        val releaseLatch = CountDownLatch(1)
        val invocationCount = AtomicInteger(0)
        val history = InMemoryRunHistoryStore()
        val pipeline = pipeline(
            transferRunner = { _, _, _, _ ->
                invocationCount.incrementAndGet()
                startedLatch.countDown()
                releaseLatch.await(5, TimeUnit.SECONDS)
                outcome(1, 1)
            },
            historyStore = history,
        )

        var firstResult: PipelineResult? = null
        val worker = Thread { firstResult = pipeline.run() }
        worker.start()
        assertTrue("first run must have started", startedLatch.await(5, TimeUnit.SECONDS))

        val secondResult = pipeline.run()

        releaseLatch.countDown()
        worker.join(5_000)

        assertEquals(PipelineResult.AlreadyRunning, secondResult)
        assertEquals(1, invocationCount.get())
        assertTrue(firstResult is PipelineResult.Ran)
        assertEquals(history.lastRun(), (firstResult as PipelineResult.Ran).record)
    }

    @Test
    fun noTargetConfigured_gatesAsTargetUnreachable_neverInvokesRunner() {
        var invoked = false
        val pipeline = pipeline(
            targetProvider = TargetProvider { null },
            gateConfig = GateConfig(directOnly = false),
            transferRunner = { _, _, _, _ -> invoked = true; outcome(1, 1) },
        )

        val result = pipeline.run()

        assertEquals(PipelineResult.Gated(GateReason.TargetUnreachable), result)
        assertFalse(invoked)
    }

    @Test
    fun sourceUnavailable_gatesAsTargetUnreachable_neverInvokesRunner() {
        var invoked = false
        val pipeline = pipeline(
            sourcePathProvider = SourcePathProvider { null },
            transferRunner = { _, _, _, _ -> invoked = true; outcome(1, 1) },
        )

        val result = pipeline.run()

        assertEquals(PipelineResult.Gated(GateReason.TargetUnreachable), result)
        assertFalse(invoked)
    }

    @Test
    fun gatedRun_notifiesOnce_thenCoalescesWithinWindow() {
        var notifyCount = 0
        val pipeline = pipeline(
            snapshot = openSnapshot().copy(state = BridgeTunnelState.Standby),
            transferRunner = { _, _, _, _ -> outcome(1, 1) },
            onNotify = { notifyCount++ },
        )

        pipeline.run()
        pipeline.run()

        assertEquals("second gated cycle for the same reason must be coalesced", 1, notifyCount)
    }

    @Test
    fun deleteEnabled_requestsDelete_forTransferredFilesOnly() {
        var requested: List<String>? = null
        val pipeline = pipeline(
            transferRunner = { _, _, _, progress ->
                progress?.onProgress(transferEvent("a.jpg"))
                // A mid-transfer tick for a file still in flight: not final.
                progress?.onProgress(transferEvent("b.jpg", isFinal = false))
                // An already-present skip: never a byte-verified transfer.
                progress?.onProgress(transferEvent("c.jpg", isTransfer = false))
                outcome(filesCopied = 1, filesTotal = 3)
            },
            deleteConfig = DeleteAfterBackupConfig(enabled = true),
            deletionRequester = xyz.tetron.sync.delete.DeletionRequester { requested = it },
        )

        pipeline.run()

        assertEquals(listOf("a.jpg"), requested)
    }

    @Test
    fun deleteDisabled_neverRequestsDelete_evenWithTransferredFiles() {
        var invoked = false
        val pipeline = pipeline(
            transferRunner = { _, _, _, progress ->
                progress?.onProgress(transferEvent("a.jpg"))
                outcome(filesCopied = 1, filesTotal = 1)
            },
            deleteConfig = DeleteAfterBackupConfig(enabled = false),
            deletionRequester = xyz.tetron.sync.delete.DeletionRequester { invoked = true },
        )

        pipeline.run()

        assertFalse("delete-after-backup opt-in is off; no delete path may run", invoked)
    }

    @Test
    fun deleteEnabled_hardEngineFailure_neverRequestsDelete() {
        var invoked = false
        val pipeline = pipeline(
            transferRunner = { _, _, _, progress ->
                progress?.onProgress(transferEvent("a.jpg"))
                throw SyncException.Engine(exitCode = 1, detail = "connection refused")
            },
            deleteConfig = DeleteAfterBackupConfig(enabled = true),
            deletionRequester = xyz.tetron.sync.delete.DeletionRequester { invoked = true },
        )

        pipeline.run()

        assertFalse("a hard engine failure must not trigger deletion", invoked)
    }

    @Test
    fun deleteEnabled_interruptedRun_stillRequestsDelete() {
        var requested: List<String>? = null
        val pipeline = pipeline(
            transferRunner = { _, _, _, progress ->
                progress?.onProgress(transferEvent("a.jpg"))
                outcome(filesCopied = 1, filesTotal = 2, ioErrorExitCode = 23)
            },
            deleteConfig = DeleteAfterBackupConfig(enabled = true),
            deletionRequester = xyz.tetron.sync.delete.DeletionRequester { requested = it },
        )

        val record = (pipeline.run() as PipelineResult.Ran).record

        assertTrue(record.interrupted)
        assertEquals("interrupted is not a failure; what did byte-verify stays eligible", listOf("a.jpg"), requested)
    }

    @Test
    fun override_matchingReason_relaxesGateAndRuns() {
        var invoked = false
        val pipeline = pipeline(
            deviceState = object : DeviceStateProvider {
                override fun isWifiConnected() = false
                override fun isCharging() = true
                override fun batteryPercent() = 100
            },
            transferRunner = { _, _, _, _ -> invoked = true; outcome(1, 1) },
        )

        val result = pipeline.run(overrideReason = GateReason.NotOnWifi)

        assertTrue("override for the actual blocking reason must proceed with the run", invoked)
        assertTrue(result is PipelineResult.Ran)
    }

    @Test
    fun override_mismatchedReason_staysGated_neverInvokesRunner() {
        var invoked = false
        val pipeline = pipeline(
            deviceState = object : DeviceStateProvider {
                override fun isWifiConnected() = false
                override fun isCharging() = false
                override fun batteryPercent() = 100
            },
            gateConfig = GateConfig(chargingRequired = true),
            transferRunner = { _, _, _, _ -> invoked = true; outcome(1, 1) },
        )

        // Blocked on NotOnWifi (evaluated first); overriding a different
        // reason must not relax anything.
        val result = pipeline.run(overrideReason = GateReason.ChargingRequired)

        assertEquals(PipelineResult.Gated(GateReason.NotOnWifi), result)
        assertFalse(invoked)
    }

    @Test
    fun override_forNonRelaxableReason_staysGated() {
        var invoked = false
        val pipeline = pipeline(
            snapshot = openSnapshot().copy(state = BridgeTunnelState.Standby),
            transferRunner = { _, _, _, _ -> invoked = true; outcome(1, 1) },
        )

        val result = pipeline.run(overrideReason = GateReason.TunnelNotActive)

        assertEquals(PipelineResult.Gated(GateReason.TunnelNotActive), result)
        assertFalse("TunnelNotActive has no relaxable knob", invoked)
    }

    @Test
    fun callerSuppliedProgressListener_stillReceivesEveryEvent() {
        val seen = mutableListOf<String?>()
        val pipeline = pipeline(
            transferRunner = { _, _, _, progress ->
                progress?.onProgress(transferEvent("a.jpg"))
                outcome(filesCopied = 1, filesTotal = 1)
            },
        )

        pipeline.run(object : uniffi.tetron_mobile_sync.SyncProgressListener {
            override fun onProgress(event: SyncProgressEvent) {
                seen.add(event.path)
            }
        })

        assertEquals(listOf("a.jpg"), seen)
    }
}
