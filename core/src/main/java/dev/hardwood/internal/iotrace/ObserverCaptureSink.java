/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.iotrace;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/// In-memory [CaptureSink] — the spike's named fallback mechanism.
///
/// Delivery is a direct list append with none of JFR's loss questions:
/// completeness is structural (no threshold, no buffer, no shedding), so the
/// dual-seal protocol still runs but the "could a record be silently lost?"
/// axis is answered by construction. Its differentiator over JFR is exactly
/// that; causality richness is not a differentiator, since the JFR sink
/// matches it by schema convention (both use the same [CaptureSink] contract).
///
/// Thread-safe: request attempts are delivered from decode / prefetch threads
/// concurrently. A [CopyOnWriteArrayList] keeps the disabled path irrelevant
/// (a disabled capture never constructs a sink) and the enabled path correct
/// without a coarse lock across the whole read.
public final class ObserverCaptureSink implements CaptureSink {

    private final List<CaptureRecords.PlanNode> planNodes = new CopyOnWriteArrayList<>();
    private final List<CaptureRecords.PlanRequirement> planRequirements = new CopyOnWriteArrayList<>();
    private final List<CaptureRecords.PlanEdge> planEdges = new CopyOnWriteArrayList<>();
    private final List<CaptureRecords.PlanSealed> planSeals = new CopyOnWriteArrayList<>();
    private final List<CaptureRecords.Request> requests = new CopyOnWriteArrayList<>();
    private final List<CaptureRecords.ExecutionSealed> executionSeals = new CopyOnWriteArrayList<>();

    @Override
    public void emitPlanNode(long executionId, long planId, long nodeId,
                             long offset, int length, String stage, String role) {
        planNodes.add(new CaptureRecords.PlanNode(executionId, planId, nodeId, offset, length, stage, role));
    }

    @Override
    public void emitPlanRequirement(long executionId, long planId, long requirementId,
                                    long requestNodeId, long offset, int length, String columnRole) {
        planRequirements.add(new CaptureRecords.PlanRequirement(
                executionId, planId, requirementId, requestNodeId, offset, length, columnRole));
    }

    @Override
    public void emitPlanEdge(long executionId, long planId, long fromNodeId, long toNodeId, String edgeKind) {
        planEdges.add(new CaptureRecords.PlanEdge(executionId, planId, fromNodeId, toNodeId, edgeKind));
    }

    @Override
    public void emitPlanSealed(long executionId, long planId, int nodeCount, int requirementCount,
                               int edgeCount, String planHash, String status, String reason) {
        planSeals.add(new CaptureRecords.PlanSealed(
                executionId, planId, nodeCount, requirementCount, edgeCount, planHash, status, reason));
    }

    @Override
    public void emitRequest(long executionId, long planId, long nodeId, long attemptId,
                            long actualOffset, int actualLength, String stage, String outcome,
                            long beginNanos, long durationNanos) {
        requests.add(new CaptureRecords.Request(executionId, planId, nodeId, attemptId,
                actualOffset, actualLength, stage, outcome, beginNanos, durationNanos));
    }

    @Override
    public void emitExecutionSealed(long executionId, int requestAttemptCount,
                                    String requestAttemptHash, String terminalOutcome) {
        executionSeals.add(new CaptureRecords.ExecutionSealed(
                executionId, requestAttemptCount, requestAttemptHash, terminalOutcome));
    }

    /// Materializes the accumulated records into a mechanism-neutral
    /// [RecordSet] for extraction. The observer never loses records, so
    /// `dataLoss` is always `false`.
    public RecordSet toRecordSet() {
        return new RecordSet(
                CaptureSchema.VERSION,
                false,
                List.copyOf(planNodes),
                List.copyOf(planRequirements),
                List.copyOf(planEdges),
                List.copyOf(planSeals),
                List.copyOf(requests),
                List.copyOf(executionSeals));
    }
}
