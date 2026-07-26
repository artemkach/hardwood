/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.iotrace;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import dev.hardwood.InputFile;
import dev.hardwood.jfr.iotrace.JfrCaptureSink;
import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.reader.RowReader;
import jdk.jfr.Recording;

import static org.assertj.core.api.Assertions.assertThat;

/// Indicative disabled-path overhead measurement over a bundled fixture.
///
/// This is **not** the authoritative equivalence gate — that is the JMH
/// [dev.hardwood.benchmarks.CaptureOverheadBenchmark] on a fixed Linux host
/// with a large fixture and a predeclared equivalence bound. This test runs in
/// the ordinary build to keep the disabled-path *shape* honest cheaply: it
/// times a full local (mmap) read across the three disabled-path
/// configurations (no recording; unrelated recording, capture family
/// disabled; and the enabled path for contrast) and asserts the disabled
/// configurations stay within a generous factor of the no-recording baseline,
/// so a gross regression (an allocation or lock on the hot path when disabled)
/// fails the build. Tight equivalence is left to the JMH gate.
///
/// The median-of-N design keeps it robust to a jittery CI host without
/// claiming statistical equivalence — the assertion is a coarse guard, and its
/// factor is deliberately loose.
class DisabledPathOverheadTest {

    private static final Path FILE = Path.of("src/test/resources/yellow_tripdata_sample.parquet");
    private static final int WARMUP = 50;
    private static final int MEASURE = 200;

    @Test
    void disabledPathStaysCloseToBaseline() throws IOException {
        long baseline = median(this::readNoRecording);
        long unrelated = median(this::readUnrelatedRecording);
        long enabled = median(this::readCaptureEnabled);

        System.out.printf("disabled-path overhead (median ns/read over %d):%n", MEASURE);
        System.out.printf("  baseline (no recording)                = %d%n", baseline);
        System.out.printf("  unrelated recording, capture disabled  = %d  (%.2fx)%n",
                unrelated, (double) unrelated / baseline);
        System.out.printf("  capture enabled (characterization)     = %d  (%.2fx)%n",
                enabled, (double) enabled / baseline);

        // Coarse guard only: the disabled path must not balloon the read. A
        // real regression (per-request allocation/lock when disabled) shows up
        // as a multiple, not a few percent. The JMH gate does the precise work.
        assertThat(unrelated)
                .as("unrelated-recording (capture disabled) read time vs baseline")
                .isLessThan(baseline * 3);
    }

    private long median(ReadRun run) throws IOException {
        for (int i = 0; i < WARMUP; i++) {
            run.read();
        }
        long[] samples = new long[MEASURE];
        for (int i = 0; i < MEASURE; i++) {
            long start = System.nanoTime();
            run.read();
            samples[i] = System.nanoTime() - start;
        }
        Arrays.sort(samples);
        return samples[samples.length / 2];
    }

    private void readNoRecording() throws IOException {
        readAll();
    }

    private void readUnrelatedRecording() throws IOException {
        try (Recording recording = new Recording()) {
            recording.enable("dev.hardwood.FileOpened");
            recording.start();
            readAll();
            recording.stop();
        }
    }

    private void readCaptureEnabled() throws IOException {
        try (Recording recording = new Recording()) {
            recording.enable("dev.hardwood.iotrace.PlanNode");
            recording.enable("dev.hardwood.iotrace.PlanRequirement");
            recording.enable("dev.hardwood.iotrace.PlanEdge");
            recording.enable("dev.hardwood.iotrace.PlanSealed");
            recording.enable("dev.hardwood.iotrace.Request");
            recording.enable("dev.hardwood.iotrace.ExecutionSealed");
            recording.start();
            CaptureContext context = CaptureContext.start(1L, new JfrCaptureSink());
            try (CaptureControl.Scope ignored = CaptureControl.install(context)) {
                readAll();
            }
            recording.stop();
        }
    }

    private void readAll() throws IOException {
        try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(FILE));
             RowReader rows = reader.rowReader()) {
            while (rows.hasNext()) {
                rows.next();
            }
        }
    }

    @FunctionalInterface
    private interface ReadRun {
        void read() throws IOException;
    }
}
