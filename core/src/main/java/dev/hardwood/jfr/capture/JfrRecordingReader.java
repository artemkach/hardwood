/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.jfr.capture;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import dev.hardwood.internal.capture.CaptureRecords;
import dev.hardwood.internal.capture.CaptureSchema;
import dev.hardwood.internal.capture.RecordSet;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

/// Parses a dumped `.jfr` recording into the mechanism-neutral
/// [RecordSet] that [dev.hardwood.internal.capture.PlanExtractor] validates.
///
/// This is the *only* JFR-specific part of extraction: it maps the six event
/// types back to plain records and detects `jdk.DataLoss` (which the JFR
/// runtime emits when it drops events). Everything downstream — reconstruction,
/// seals, hashes, semantic conformance — is shared with the observer mechanism,
/// so both are held to the same go/no-go bar by one code path.
///
/// Comparing extracted canonical records (never raw `.jfr` bytes) is deliberate:
/// recordings embed timestamps, thread IDs, and chunk ordering that vary per
/// run; the [RecordSet] carries only the structural fields.
public final class JfrRecordingReader {

    private JfrRecordingReader() {
    }

    private static final String DATA_LOSS = "jdk.DataLoss";

    /// Reads the recording at `path` into a [RecordSet]. The schema version is
    /// stamped from [CaptureSchema#VERSION] — the events themselves carry no
    /// version field, so a version mismatch is a build-time concern; the
    /// value is recorded so the extractor's version check is meaningful when
    /// recordings outlive a schema bump (the reader would then be rebuilt).
    public static RecordSet read(Path path) throws IOException {
        List<CaptureRecords.PlanNode> planNodes = new ArrayList<>();
        List<CaptureRecords.PlanRequirement> planRequirements = new ArrayList<>();
        List<CaptureRecords.PlanEdge> planEdges = new ArrayList<>();
        List<CaptureRecords.PlanSealed> planSeals = new ArrayList<>();
        List<CaptureRecords.Request> requests = new ArrayList<>();
        List<CaptureRecords.ExecutionSealed> executionSeals = new ArrayList<>();
        boolean dataLoss = false;

        try (RecordingFile file = new RecordingFile(path)) {
            while (file.hasMoreEvents()) {
                RecordedEvent event = file.readEvent();
                String name = event.getEventType().getName();
                switch (name) {
                    case "dev.hardwood.capture.PlanNode" -> planNodes.add(new CaptureRecords.PlanNode(
                            event.getLong("executionId"), event.getLong("planId"), event.getLong("nodeId"),
                            event.getLong("offset"), event.getInt("length"),
                            event.getString("stage"), event.getString("role")));
                    case "dev.hardwood.capture.PlanRequirement" -> planRequirements.add(
                            new CaptureRecords.PlanRequirement(
                                    event.getLong("executionId"), event.getLong("planId"),
                                    event.getLong("requirementId"), event.getLong("requestNodeId"),
                                    event.getLong("offset"), event.getInt("length"),
                                    event.getString("columnRole")));
                    case "dev.hardwood.capture.PlanEdge" -> planEdges.add(new CaptureRecords.PlanEdge(
                            event.getLong("executionId"), event.getLong("planId"),
                            event.getLong("fromNodeId"), event.getLong("toNodeId"),
                            event.getString("edgeKind")));
                    case "dev.hardwood.capture.PlanSealed" -> planSeals.add(new CaptureRecords.PlanSealed(
                            event.getLong("executionId"), event.getLong("planId"),
                            event.getInt("nodeCount"), event.getInt("requirementCount"),
                            event.getInt("edgeCount"), event.getString("planHash"),
                            event.getString("status"), event.getString("reason")));
                    case "dev.hardwood.capture.Request" -> requests.add(new CaptureRecords.Request(
                            event.getLong("executionId"), event.getLong("planId"), event.getLong("nodeId"),
                            event.getLong("attemptId"), event.getLong("actualOffset"),
                            event.getInt("actualLength"), event.getString("stage"),
                            event.getString("outcome"), event.getLong("beginNanos"),
                            event.getLong("durationNanos")));
                    case "dev.hardwood.capture.ExecutionSealed" -> executionSeals.add(
                            new CaptureRecords.ExecutionSealed(
                                    event.getLong("executionId"), event.getInt("requestAttemptCount"),
                                    event.getString("requestAttemptHash"),
                                    event.getString("terminalOutcome")));
                    case DATA_LOSS -> dataLoss = true;
                    default -> { /* unrelated event — ignore */ }
                }
            }
        }

        return new RecordSet(CaptureSchema.VERSION, dataLoss,
                planNodes, planRequirements, planEdges, planSeals, requests, executionSeals);
    }
}
