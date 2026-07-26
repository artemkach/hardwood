/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.jfr.iotrace;

import dev.hardwood.internal.iotrace.CaptureSink;
import jdk.jfr.EventType;

/// [CaptureSink] that delivers each capture record as a JFR event.
///
/// The disabled-path guard is a **cached** `EventType.isEnabled()` check per
/// event type: the instance method `Event.shouldCommit()` cannot guard its own
/// construction (the object is already allocated by then), so the family's
/// enablement is queried once at sink construction and re-read cheaply per
/// emit. When the capture family is disabled — the case whenever an unrelated
/// production recording is active but no capture recording is — every emit is
/// a boolean check and an early return, with no event allocation.
///
/// Emission happens *from* the planning and execution sites (no capture object
/// is injected across threads for delivery); the identity that lets an
/// execution-side event name its node is carried on the request object by
/// [dev.hardwood.internal.iotrace.CaptureContext], which is mechanism-neutral.
public final class JfrCaptureSink implements CaptureSink {

    // Whether the capture event family is enabled. Cached at construction; the
    // capture recording enables the whole family atomically before the sink is
    // used, so a single read here reflects the family state for the run.
    private final boolean enabled;

    public JfrCaptureSink() {
        this.enabled = EventType.getEventType(PlanNodeEvent.class).isEnabled();
    }

    @Override
    public void emitPlanNode(long executionId, long planId, long nodeId,
                             long offset, int length, String stage, String role) {
        if (!enabled) {
            return;
        }
        PlanNodeEvent event = new PlanNodeEvent();
        event.executionId = executionId;
        event.planId = planId;
        event.nodeId = nodeId;
        event.offset = offset;
        event.length = length;
        event.stage = stage;
        event.role = role;
        event.commit();
    }

    @Override
    public void emitPlanRequirement(long executionId, long planId, long requirementId,
                                    long requestNodeId, long offset, int length, String columnRole) {
        if (!enabled) {
            return;
        }
        PlanRequirementEvent event = new PlanRequirementEvent();
        event.executionId = executionId;
        event.planId = planId;
        event.requirementId = requirementId;
        event.requestNodeId = requestNodeId;
        event.offset = offset;
        event.length = length;
        event.columnRole = columnRole;
        event.commit();
    }

    @Override
    public void emitPlanEdge(long executionId, long planId, long fromNodeId, long toNodeId, String edgeKind) {
        if (!enabled) {
            return;
        }
        PlanEdgeEvent event = new PlanEdgeEvent();
        event.executionId = executionId;
        event.planId = planId;
        event.fromNodeId = fromNodeId;
        event.toNodeId = toNodeId;
        event.edgeKind = edgeKind;
        event.commit();
    }

    @Override
    public void emitPlanSealed(long executionId, long planId, int nodeCount, int requirementCount,
                               int edgeCount, String planHash, String status, String reason) {
        if (!enabled) {
            return;
        }
        PlanSealedEvent event = new PlanSealedEvent();
        event.executionId = executionId;
        event.planId = planId;
        event.nodeCount = nodeCount;
        event.requirementCount = requirementCount;
        event.edgeCount = edgeCount;
        event.planHash = planHash;
        event.status = status;
        event.reason = reason;
        event.commit();
    }

    @Override
    public void emitRequest(long executionId, long planId, long nodeId, long attemptId,
                            long actualOffset, int actualLength, String stage, String outcome,
                            long beginNanos, long durationNanos) {
        if (!enabled) {
            return;
        }
        RequestEvent event = new RequestEvent();
        event.executionId = executionId;
        event.planId = planId;
        event.nodeId = nodeId;
        event.attemptId = attemptId;
        event.actualOffset = actualOffset;
        event.actualLength = actualLength;
        event.stage = stage;
        event.outcome = outcome;
        event.beginNanos = beginNanos;
        event.durationNanos = durationNanos;
        event.commit();
    }

    @Override
    public void emitExecutionSealed(long executionId, int requestAttemptCount,
                                    String requestAttemptHash, String terminalOutcome) {
        if (!enabled) {
            return;
        }
        ExecutionSealedEvent event = new ExecutionSealedEvent();
        event.executionId = executionId;
        event.requestAttemptCount = requestAttemptCount;
        event.requestAttemptHash = requestAttemptHash;
        event.terminalOutcome = terminalOutcome;
        event.commit();
    }
}
