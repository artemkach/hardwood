/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.iotrace;

/// Plain carriers for the six capture record types, shared by the observer
/// sink and the extractor. They mirror the [IoTraceSink] method parameters
/// one-to-one; the JFR sink maps the same fields onto JFR events, and both
/// mechanisms reconstruct through the same [PlanExtractor] logic.
public final class IoTraceRecords {

    private IoTraceRecords() {
    }

    public record PlanNode(long executionId, long planId, long nodeId,
                           long offset, int length, String stage, String role) {}

    public record PlanRequirement(long executionId, long planId, long requirementId,
                                  long requestNodeId, long offset, int length, String columnRole) {}

    public record PlanEdge(long executionId, long planId, long fromNodeId, long toNodeId,
                           String edgeKind) {}

    public record PlanSealed(long executionId, long planId, int nodeCount, int requirementCount,
                             int edgeCount, String planHash, String status, String reason) {}

    public record Request(long executionId, long planId, long nodeId, long attemptId,
                          long actualOffset, int actualLength, String stage, String outcome,
                          long beginNanos, long durationNanos) {}

    public record ExecutionSealed(long executionId, int requestAttemptCount,
                                  String requestAttemptHash, String terminalOutcome) {}
}
