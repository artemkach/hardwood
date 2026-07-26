/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.jfr.iotrace;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import dev.hardwood.internal.iotrace.IoTraceRecordSet;
import dev.hardwood.internal.iotrace.IoTraceRecords;
import dev.hardwood.internal.iotrace.IoTraceSchema;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

/// Parses a dumped `.jfr` recording into the mechanism-neutral
/// [IoTraceRecordSet] that [dev.hardwood.internal.iotrace.PlanExtractor] validates.
///
/// This is the *only* JFR-specific part of extraction: it maps the six event
/// types back to plain records and detects `jdk.DataLoss` (which the JFR
/// runtime emits when it drops events). Everything downstream — reconstruction,
/// seals, hashes, semantic conformance — is shared with the observer mechanism,
/// so both are held to the same go/no-go bar by one code path.
///
/// Comparing extracted canonical records (never raw `.jfr` bytes) is deliberate:
/// recordings embed timestamps, thread IDs, and chunk ordering that vary per
/// run; the [IoTraceRecordSet] carries only the structural fields.
public final class JfrRecordingReader {

    private JfrRecordingReader() {
    }

    private static final String DATA_LOSS = "jdk.DataLoss";

    /// Reads the recording at `path` into a [IoTraceRecordSet]. The schema version is
    /// stamped from [IoTraceSchema#VERSION] — the events themselves carry no
    /// version field, so a version mismatch is a build-time concern; the
    /// value is recorded so the extractor's version check is meaningful when
    /// recordings outlive a schema bump (the reader would then be rebuilt).
    public static IoTraceRecordSet read(Path path) throws IOException {
        List<IoTraceRecords.PlanNode> planNodes = new ArrayList<>();
        List<IoTraceRecords.PlanRequirement> planRequirements = new ArrayList<>();
        List<IoTraceRecords.PlanEdge> planEdges = new ArrayList<>();
        List<IoTraceRecords.PlanSealed> planSeals = new ArrayList<>();
        List<IoTraceRecords.Request> requests = new ArrayList<>();
        List<IoTraceRecords.ExecutionSealed> executionSeals = new ArrayList<>();
        boolean dataLoss = false;

        try (RecordingFile file = new RecordingFile(path)) {
            while (file.hasMoreEvents()) {
                RecordedEvent event = file.readEvent();
                String name = event.getEventType().getName();
                switch (name) {
                    case "dev.hardwood.iotrace.PlanNode" -> planNodes.add(new IoTraceRecords.PlanNode(
                            event.getLong("executionId"), event.getLong("planId"), event.getLong("nodeId"),
                            event.getLong("offset"), event.getInt("length"),
                            event.getString("stage"), event.getString("role")));
                    case "dev.hardwood.iotrace.PlanRequirement" -> planRequirements.add(
                            new IoTraceRecords.PlanRequirement(
                                    event.getLong("executionId"), event.getLong("planId"),
                                    event.getLong("requirementId"), event.getLong("requestNodeId"),
                                    event.getLong("offset"), event.getInt("length"),
                                    event.getString("columnRole")));
                    case "dev.hardwood.iotrace.PlanEdge" -> planEdges.add(new IoTraceRecords.PlanEdge(
                            event.getLong("executionId"), event.getLong("planId"),
                            event.getLong("fromNodeId"), event.getLong("toNodeId"),
                            event.getString("edgeKind")));
                    case "dev.hardwood.iotrace.PlanSealed" -> planSeals.add(new IoTraceRecords.PlanSealed(
                            event.getLong("executionId"), event.getLong("planId"),
                            event.getInt("nodeCount"), event.getInt("requirementCount"),
                            event.getInt("edgeCount"), event.getString("planHash"),
                            event.getString("status"), event.getString("reason")));
                    case "dev.hardwood.iotrace.Request" -> requests.add(new IoTraceRecords.Request(
                            event.getLong("executionId"), event.getLong("planId"), event.getLong("nodeId"),
                            event.getLong("attemptId"), event.getLong("actualOffset"),
                            event.getInt("actualLength"), event.getString("stage"),
                            event.getString("outcome"), event.getLong("beginNanos"),
                            event.getLong("durationNanos")));
                    case "dev.hardwood.iotrace.ExecutionSealed" -> executionSeals.add(
                            new IoTraceRecords.ExecutionSealed(
                                    event.getLong("executionId"), event.getInt("requestAttemptCount"),
                                    event.getString("requestAttemptHash"),
                                    event.getString("terminalOutcome")));
                    case DATA_LOSS -> dataLoss = true;
                    default -> { /* unrelated event — ignore */ }
                }
            }
        }

        return new IoTraceRecordSet(IoTraceSchema.VERSION, dataLoss,
                planNodes, planRequirements, planEdges, planSeals, requests, executionSeals);
    }
}
