/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.benchmarks;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import dev.hardwood.InputFile;
import dev.hardwood.internal.iotrace.CaptureContext;
import dev.hardwood.internal.iotrace.CaptureControl;
import dev.hardwood.jfr.iotrace.JfrCaptureSink;
import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.reader.RowReader;
import jdk.jfr.Recording;

/// Disabled-path overhead gate for plan capture (spike §4.2 / non-interference).
///
/// The spike requires the capture instrumentation to add no measurable cost
/// when disabled. This benchmark measures a full local (mmap) read — where
/// `readRange` returns instantly, so any capture cost is exposed rather than
/// hidden behind I/O latency — across the four mandated configurations:
///
/// - `baseline` — no recording of any kind;
/// - `instrumentedNoRecording` — capture code paths present in the build (they
///   always are), no JFR recording active;
/// - `unrelatedRecordingCaptureDisabled` — a JFR recording is active but the
///   capture event family is NOT enabled (the shape of a production recording);
/// - `captureEnabled` — capture recording active and family enabled
///   (characterization only — not part of the disabled-path equivalence gate).
///
/// The gate: the first three configurations must be statistically equivalent
/// within a predeclared bound; a confidence interval for the difference must
/// lie inside the equivalence region. `captureEnabled` is reported for
/// characterization, not held to the disabled-path bound.
///
/// Fixture: any local Parquet file; `dataDir` + `fileName` are JMH params.
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Fork(value = 2, jvmArgs = { "-Xms512m", "-Xmx512m", "--add-modules", "jdk.incubator.vector" })
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 8, time = 1)
public class CaptureOverheadBenchmark {

    @Param({})
    private String dataDir;

    @Param("yellow_tripdata_2016-03.parquet")
    private String fileName;

    private Path path;

    @Setup(Level.Trial)
    public void setup() {
        path = Path.of(dataDir).resolve(fileName).toAbsolutePath().normalize();
        if (!path.toFile().exists()) {
            throw new IllegalStateException("Parquet file not found: " + path
                    + ". Run './mvnw verify -Pperformance-test' first to download test data.");
        }
    }

    /// A full read with no JFR recording — the reference cost.
    @Benchmark
    public void baseline(Blackhole bh) throws IOException {
        readAll(bh);
    }

    /// A full read with the capture code present but no recording — identical
    /// to baseline unless the mere presence of the instrumentation costs
    /// something. (The core has no build-time toggle; this is `baseline` under
    /// a different name to make the comparison explicit in the results.)
    @Benchmark
    public void instrumentedNoRecording(Blackhole bh) throws IOException {
        readAll(bh);
    }

    /// A full read with an unrelated JFR recording active (capture family
    /// disabled) — the production-recording shape. The disabled-path guard
    /// must keep this equivalent to baseline.
    @Benchmark
    public void unrelatedRecordingCaptureDisabled(Blackhole bh) throws IOException {
        try (Recording recording = new Recording()) {
            recording.enable("dev.hardwood.FileOpened");
            recording.start();
            readAll(bh);
            recording.stop();
        }
    }

    /// A full read with the capture family enabled — characterization only.
    @Benchmark
    public void captureEnabled(Blackhole bh) throws IOException {
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
                readAll(bh);
            }
            recording.stop();
        }
    }

    private void readAll(Blackhole bh) throws IOException {
        try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(path));
             RowReader rows = reader.rowReader()) {
            long count = 0;
            while (rows.hasNext()) {
                rows.next();
                count++;
            }
            bh.consume(count);
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        // No persistent resources; explicit hook kept for symmetry with the
        // other benchmarks and to make the lifecycle obvious.
    }
}
