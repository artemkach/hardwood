/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.iotrace;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/// Per-execution capture state, owned by one reader.
///
/// This is the explicitly-propagated per-execution state the spike requires:
/// there is **no** process-global capture state and **no** caller-thread
/// affinity. One reader holds one context and threads it through the
/// construction path onto the request objects, so concurrent readers are
/// isolated by construction (each has its own `executionId`) and plan
/// construction on virtual threads / the common pool reaches the same context
/// by reference rather than through a `ThreadLocal` that would not follow the
/// hand-off.
///
/// Identity assignment (node / requirement / attempt IDs) is execution-global
/// and monotonic. `nodeId` is execution-global; `RequestEvent` additionally
/// carries `planId`, so a request resolves unambiguously to its plan and node
/// independent of event ordering or carrier migration.
///
/// The mechanism (JFR vs observer) is entirely behind [CaptureSink]; this
/// class is identical under both. It is constructed only when capture is
/// enabled — the disabled path holds a `null` context reference and does no
/// work (see [dev.hardwood.internal.reader.ChunkHandle]).
public final class CaptureContext {

    private final long executionId;
    private final CaptureSink sink;

    private final AtomicLong nodeIdSeq = new AtomicLong();
    private final AtomicLong requirementIdSeq = new AtomicLong();
    private final AtomicLong attemptIdSeq = new AtomicLong();

    /// Execution-wide, order-independent accumulator for the execution seal.
    /// Guarded by its own monitor because attempts commit from many threads.
    private final List<String> attemptCanonical = new ArrayList<>();
    private final Object attemptLock = new Object();
    private volatile boolean anyFailure;
    private volatile boolean executionSealed;

    private CaptureContext(long executionId, CaptureSink sink) {
        this.executionId = executionId;
        this.sink = sink;
    }

    /// Starts a capture context for one execution against the given sink.
    ///
    /// @param executionId the expected execution ID from the scenario manifest
    /// @param sink the delivery mechanism (JFR or observer)
    public static CaptureContext start(long executionId, CaptureSink sink) {
        if (sink == null) {
            throw new IllegalArgumentException("sink must not be null");
        }
        return new CaptureContext(executionId, sink);
    }

    public long executionId() {
        return executionId;
    }

    /// Opens a plan scope for one row group's published plan. All node,
    /// requirement, and seal work for that plan flows through the returned
    /// scope on the single thread that builds the plan.
    ///
    /// @param planId a per-execution-unique plan identifier (the work-item index)
    public PlanScope newPlan(long planId) {
        return new PlanScope(planId);
    }

    /// Records one executed request attempt (an invocation of a final request
    /// object's `readRange()`). Committed on both success and failure so lost
    /// or defective attempts are represented in the execution seal.
    public void recordRequest(NodeIdentity id, long actualOffset, int actualLength,
                              boolean success, long beginNanos, long durationNanos) {
        String outcome = success ? CaptureSchema.OUTCOME_SUCCESS : CaptureSchema.OUTCOME_FAILURE;
        long attemptId = attemptIdSeq.incrementAndGet();
        sink.emitRequest(executionId, id.planId(), id.nodeId(), attemptId,
                actualOffset, actualLength, id.stage(), outcome, beginNanos, durationNanos);
        String canonical = CanonicalHash.requestAttempt(
                id.nodeId(), attemptId, actualOffset, actualLength, id.stage(), outcome);
        synchronized (attemptLock) {
            attemptCanonical.add(canonical);
        }
        if (!success) {
            anyFailure = true;
        }
    }

    /// Closes the request stream after the supported execution scope is
    /// quiescent, emitting the execution seal with the order-independent
    /// attempt hash. A plan-only seal cannot detect a lost duplicate, retry,
    /// or unplanned request — this seal can.
    public void sealExecution() {
        int count;
        String hash;
        synchronized (attemptLock) {
            if (executionSealed) {
                return;
            }
            executionSealed = true;
            count = attemptCanonical.size();
            CanonicalHash acc = new CanonicalHash();
            for (String c : attemptCanonical) {
                acc.add(c);
            }
            hash = acc.digest();
        }
        String terminal = anyFailure ? CaptureSchema.TERMINAL_ABORTED : CaptureSchema.TERMINAL_COMPLETE;
        sink.emitExecutionSealed(executionId, count, hash, terminal);
    }

    /// Per-plan accumulation, confined to the thread building one plan.
    public final class PlanScope {

        private final long planId;
        private final CanonicalHash nodeHash = new CanonicalHash();
        private int nodeCount;
        private int requirementCount;
        private int edgeCount;

        private PlanScope(long planId) {
            this.planId = planId;
        }

        public long planId() {
            return planId;
        }

        /// Publishes one final request node and returns its stable identity to
        /// be stamped onto the request object.
        public NodeIdentity node(long offset, int length, String stage, String role) {
            long nodeId = nodeIdSeq.incrementAndGet();
            sink.emitPlanNode(executionId, planId, nodeId, offset, length, stage, role);
            nodeHash.add(CanonicalHash.planNode(nodeId, offset, length, stage, role));
            nodeCount++;
            return new NodeIdentity(executionId, planId, nodeId, stage, role);
        }

        /// Publishes one first-read requirement materialized into `nodeId`.
        public void requirement(long nodeId, long offset, int length, String columnRole) {
            long requirementId = requirementIdSeq.incrementAndGet();
            sink.emitPlanRequirement(executionId, planId, requirementId, nodeId,
                    offset, length, columnRole);
            requirementCount++;
        }

        /// Publishes a scheduling edge. v0 graphs are edge-free; provided for
        /// completeness and forward compatibility.
        public void edge(long fromNodeId, long toNodeId, String edgeKind) {
            sink.emitPlanEdge(executionId, planId, fromNodeId, toNodeId, edgeKind);
            edgeCount++;
        }

        /// Seals the plan: this method boundary is the plan-publication
        /// quiescence point. `status` records whether the plan is a complete
        /// v0 static plan or was exported incomplete.
        public void seal(String status, String reason) {
            sink.emitPlanSealed(executionId, planId, nodeCount, requirementCount, edgeCount,
                    nodeHash.digest(), status, reason);
        }
    }
}
