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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.hardwood.InputFile;
import dev.hardwood.internal.capture.CaptureContext;
import dev.hardwood.internal.capture.CaptureControl;
import dev.hardwood.internal.capture.PlanExtractor;
import dev.hardwood.internal.capture.RecordSet;
import dev.hardwood.internal.capture.ScenarioManifest;
import dev.hardwood.internal.capture.StaticFetchPlan;
import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.reader.RowReader;
import jdk.jfr.Recording;

import static org.assertj.core.api.Assertions.assertThat;

/// The JFR mechanism's leg of the go/no-go: can the default-disabled JFR event
/// family, once explicitly enabled by a capture recording, reconstruct the
/// exact static plan and correlate every executed request across the reader's
/// asynchronous handoffs (prefetch on the common pool, decode on virtual
/// threads)?
///
/// The test enables the six-event family on an explicit [Recording] *before*
/// constructing the sink (the sink caches enablement), reads the fixture,
/// dumps the recording, parses it back with [JfrRecordingReader], and runs the
/// same [PlanExtractor] the observer uses. A green run proves JFR meets exact
/// reconstruction + correlation + seal-based loss detection — the properties
/// that decide JFR-first vs the observer fallback.
class JfrCaptureRoundTripTest {

    private static final Path SEQ_FILE = Path.of("src/test/resources/yellow_tripdata_sample.parquet");

    @Test
    void jfrRoundTripReconstructsAndCorrelates(@TempDir Path dir) throws IOException {
        long executionId = 5150L;
        Path dump = dir.resolve("capture.jfr");

        try (Recording recording = new Recording()) {
            enableCaptureFamily(recording);
            recording.start();

            // Construct the sink AFTER enabling + starting, so its cached
            // EventType.isEnabled() guard reads `true`.
            JfrCaptureSink sink = new JfrCaptureSink();
            CaptureContext context = CaptureContext.start(executionId, sink);
            readUnderCapture(context);

            recording.stop();
            recording.dump(dump);
        }

        RecordSet records = JfrRecordingReader.read(dump);
        assertThat(records.dataLoss()).isFalse();

        StaticFetchPlan plan = PlanExtractor.extractSingle(records, ScenarioManifest.single(executionId));

        // Same shape the observer mechanism reconstructs for this fixture: one
        // fused data node carrying every projected column's requirement.
        assertThat(plan.isSupported()).isTrue();
        assertThat(plan.dataStageNodeCount()).isEqualTo(1);
        assertThat(plan.requirements().size()).isGreaterThanOrEqualTo(2);
        assertThat(plan.executionId()).isEqualTo(executionId);
    }

    @Test
    void disabledFamilyProducesNoCaptureEvents(@TempDir Path dir) throws IOException {
        // A recording that does NOT enable the capture family (the shape of an
        // unrelated production recording) must not activate per-request
        // capture instrumentation: the sink's cached guard is false, so no
        // capture events are emitted.
        Path dump = dir.resolve("unrelated.jfr");
        long executionId = 6000L;

        try (Recording recording = new Recording()) {
            // Enable an unrelated Hardwood event, not the capture family.
            recording.enable("dev.hardwood.FileOpened");
            recording.start();

            JfrCaptureSink sink = new JfrCaptureSink();
            CaptureContext context = CaptureContext.start(executionId, sink);
            readUnderCapture(context);

            recording.stop();
            recording.dump(dump);
        }

        RecordSet records = JfrRecordingReader.read(dump);
        assertThat(records.planNodes()).isEmpty();
        assertThat(records.planSeals()).isEmpty();
        assertThat(records.requests()).isEmpty();
        assertThat(records.executionSeals()).isEmpty();
    }

    private static void enableCaptureFamily(Recording recording) {
        recording.enable(PlanNodeEvent.class);
        recording.enable(PlanRequirementEvent.class);
        recording.enable(PlanEdgeEvent.class);
        recording.enable(PlanSealedEvent.class);
        recording.enable(RequestEvent.class);
        recording.enable(ExecutionSealedEvent.class);
    }

    private static void readUnderCapture(CaptureContext context) throws IOException {
        InputFile file = InputFile.of(SEQ_FILE);
        file.open();
        try (CaptureControl.Scope ignored = CaptureControl.install(context);
             ParquetFileReader reader = ParquetFileReader.open(file);
             RowReader rows = reader.rowReader()) {
            while (rows.hasNext()) {
                rows.next();
            }
        }
    }
}
