/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.capture;

/// Mechanism-neutral delivery seam for the causal plan-capture schema.
///
/// The causal schema (the six record types below) is custom and shared; a
/// sink is only responsible for *delivering* an already-formed record.
/// [dev.hardwood.internal.capture.JfrCaptureSink] commits a JFR event per
/// record; [dev.hardwood.internal.capture.ObserverCaptureSink] appends to an
/// in-memory list. The identity, canonical ordering, and seal-hash work all
/// live above this seam in [CaptureContext], so switching mechanisms moves
/// only the delivery problem, never the identity problem.
///
/// All fields are flat primitives or strings — JFR event payloads do not
/// portably carry records, enums, arrays, or collections, so the neutral
/// contract is constrained to what the weaker mechanism can express.
///
/// A sink is invoked only when capture is enabled; the disabled path never
/// reaches a sink (see [CaptureContext#enabled]).
public interface CaptureSink {

    /// A final request node in the published plan (emitted after
    /// `coalesceAcrossColumns()`). A fused region is one node serving several
    /// columns' subranges.
    void emitPlanNode(long executionId, long planId, long nodeId,
                      long offset, int length, String stage, String role);

    /// One first-read requirement, materialized into a final node. A fused
    /// node carries N requirement records (this is `MATERIALIZES_INTO`, not a
    /// scheduling edge).
    void emitPlanRequirement(long executionId, long planId, long requirementId,
                             long requestNodeId, long offset, int length, String columnRole);

    /// A scheduling dependency between two final nodes.
    void emitPlanEdge(long executionId, long planId, long fromNodeId, long toNodeId, String edgeKind);

    /// The plan seal: the plan-publication quiescence point for one plan.
    /// `status` distinguishes a supported plan from an incomplete one so that
    /// an unsupported plan is not confused with recording loss.
    void emitPlanSealed(long executionId, long planId, int nodeCount, int requirementCount,
                        int edgeCount, String planHash, String status, String reason);

    /// One executed request attempt = one invocation of a final request
    /// object's `readRange()`. Committed on both success and failure so lost
    /// or defective attempts are represented.
    void emitRequest(long executionId, long planId, long nodeId, long attemptId,
                     long actualOffset, int actualLength, String stage, String outcome,
                     long beginNanos, long durationNanos);

    /// The execution seal: closes the request stream after the supported
    /// execution scope is quiescent. Without it a lost duplicate/retry/
    /// unplanned request could make defective execution look conformant.
    void emitExecutionSealed(long executionId, int requestAttemptCount,
                             String requestAttemptHash, String terminalOutcome);
}
