/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.cli.command;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.hardwood.internal.iotrace.FetchPlanConformance;
import dev.hardwood.internal.iotrace.IoTraceSchema;
import dev.hardwood.internal.iotrace.StaticFetchPlan;
import dev.hardwood.internal.iotrace.TracingInputFile;

import static org.assertj.core.api.Assertions.assertThat;

/// Renderer unit tests against hand-built plans: fused (with a dead gap),
/// split, incomplete, and empty shapes, plus conformance verdict rendering.
class IoTraceRendererTest {

    private static StaticFetchPlan fusedWithGap() {
        // One node [0, 300) serving requirements [0,100) and [200,100):
        // bytes [100, 200) are dead gap fetched by the fused node.
        return new StaticFetchPlan(1, 0,
                List.of(new StaticFetchPlan.Node(1, 0, 300, IoTraceSchema.STAGE_DATA, "fused")),
                List.of(new StaticFetchPlan.Requirement(1, 1, 0, 100, "col a"),
                        new StaticFetchPlan.Requirement(2, 1, 200, 100, "col b")),
                List.of(),
                IoTraceSchema.STATUS_SUPPORTED, "", "h");
    }

    @Test
    void fusedBarShowsUsefulAndDeadBytes() {
        StaticFetchPlan plan = fusedWithGap();
        String bar = IoTraceRenderer.renderBar(plan, 0, 300);
        // First third useful, middle third dead, last third useful.
        assertThat(bar).hasSize(64);
        assertThat(bar.charAt(0)).isEqualTo('█');
        assertThat(bar.charAt(32)).isEqualTo('░');
        assertThat(bar.charAt(63)).isEqualTo('█');
    }

    @Test
    void unplannedSpaceRendersBlank() {
        // Two standalone nodes with unplanned space between them.
        StaticFetchPlan plan = new StaticFetchPlan(1, 0,
                List.of(new StaticFetchPlan.Node(1, 0, 100, IoTraceSchema.STAGE_DATA, "a"),
                        new StaticFetchPlan.Node(2, 200, 100, IoTraceSchema.STAGE_DATA, "b")),
                List.of(new StaticFetchPlan.Requirement(1, 1, 0, 100, "a"),
                        new StaticFetchPlan.Requirement(2, 2, 200, 100, "b")),
                List.of(),
                IoTraceSchema.STATUS_SUPPORTED, "", "h");
        String bar = IoTraceRenderer.renderBar(plan, 0, 300);
        assertThat(bar.charAt(0)).isEqualTo('█');
        assertThat(bar.charAt(32)).isEqualTo(' ');
        assertThat(bar.charAt(63)).isEqualTo('█');
    }

    @Test
    void incompletePlanIsLabeled() {
        StaticFetchPlan plan = new StaticFetchPlan(1, 2, List.of(), List.of(), List.of(),
                IoTraceSchema.STATUS_INCOMPLETE, "head(5) truncation", "h");
        String out = IoTraceRenderer.renderPlan(plan);
        assertThat(out).contains("Plan 2");
        assertThat(out).contains("INCOMPLETE: head(5) truncation");
        assertThat(out).contains("no data-stage nodes");
    }

    @Test
    void fusedNodeTableNamesRequirementCount() {
        String out = IoTraceRenderer.renderPlan(fusedWithGap());
        assertThat(out).contains("2 requirements (fused)");
    }

    @Test
    void conformantTraceRendersVerdictAndCorrelation() {
        StaticFetchPlan plan = fusedWithGap();
        List<TracingInputFile.TracedRead> trace = List.of(
                new TracingInputFile.TracedRead(9000, 8, 1_000, 250_000),        // metadata
                new TracingInputFile.TracedRead(0, 300, 2_000_000, 30_000_000)); // node 1
        FetchPlanConformance.Result result = FetchPlanConformance.match(plan, trace);
        String out = IoTraceRenderer.renderTrace(trace, result);
        assertThat(out).contains("Conformance: OK");
        assertThat(out).contains("node 1");
        assertThat(out).contains("metadata");
        assertThat(out).contains("1 read(s) outside the data-stage plan");
        // Timing columns: begin relative to the first read, human-scaled units.
        assertThat(out).contains("t+0.0µs");
        assertThat(out).contains("250.0µs");
        assertThat(out).contains("t+2.0ms");
        assertThat(out).contains("30.0ms");
    }

    @Test
    void nanosFormatIsHumanScaled() {
        assertThat(IoTraceRenderer.formatNanos(0)).isEqualTo("0.0µs");
        assertThat(IoTraceRenderer.formatNanos(999_949)).isEqualTo("999.9µs");
        assertThat(IoTraceRenderer.formatNanos(1_000_000)).isEqualTo("1.0ms");
        assertThat(IoTraceRenderer.formatNanos(9_999_000_000L)).isEqualTo("9999.0ms");
        assertThat(IoTraceRenderer.formatNanos(10_000_000_000L)).isEqualTo("10.0s");
    }

    @Test
    void timingStatsReportWallClockBusyOverlapAndThroughput() {
        StaticFetchPlan plan = fusedWithGap();
        // Two overlapping data-stage-shaped reads won't match this plan;
        // build a trace where the plan's single node matches read 2 and a
        // metadata read sits in front.
        //   metadata: begins t=0, 1 ms
        //   node 1:   begins t=1ms, 100 ms, 300 bytes
        List<TracingInputFile.TracedRead> trace = List.of(
                new TracingInputFile.TracedRead(9000, 8, 0, 1_000_000),
                new TracingInputFile.TracedRead(0, 300, 1_000_000, 100_000_000));
        FetchPlanConformance.Result result = FetchPlanConformance.match(plan, trace);
        String out = IoTraceRenderer.renderTimingStats(trace, result);

        // All reads: wall clock = 101 ms (t=0 to t=101ms), busy = 101 ms.
        assertThat(out).contains("all reads");
        assertThat(out).contains("wall clock 101.0ms");
        // Data stage: the single matched read — 100 ms, fully serial.
        assertThat(out).contains("data stage");
        assertThat(out).contains("wall clock 100.0ms");
        assertThat(out).contains("overlap 1.0x");
        assertThat(out).contains("slowest read 100.0ms for 300 B");
    }

    @Test
    void overlapExceedsOneForConcurrentReads() {
        // Two 100-byte plan nodes read fully concurrently: wall clock 10 ms,
        // busy 20 ms → overlap 2.0x.
        StaticFetchPlan plan = new StaticFetchPlan(1, 0,
                List.of(new StaticFetchPlan.Node(1, 0, 100, IoTraceSchema.STAGE_DATA, "a"),
                        new StaticFetchPlan.Node(2, 200, 100, IoTraceSchema.STAGE_DATA, "b")),
                List.of(new StaticFetchPlan.Requirement(1, 1, 0, 100, "a"),
                        new StaticFetchPlan.Requirement(2, 2, 200, 100, "b")),
                List.of(),
                IoTraceSchema.STATUS_SUPPORTED, "", "h");
        List<TracingInputFile.TracedRead> trace = List.of(
                new TracingInputFile.TracedRead(0, 100, 0, 10_000_000),
                new TracingInputFile.TracedRead(200, 100, 0, 10_000_000));
        FetchPlanConformance.Result result = FetchPlanConformance.match(plan, trace);
        String out = IoTraceRenderer.renderTimingStats(trace, result);
        assertThat(out).contains("overlap 2.0x");
    }

    @Test
    void throughputIsHumanScaled() {
        assertThat(IoTraceRenderer.throughput(300, 500)).isEqualTo("—");
        assertThat(IoTraceRenderer.throughput(500, 1_000_000_000)).isEqualTo("500 B/s");
        assertThat(IoTraceRenderer.throughput(50 * 1024, 1_000_000_000)).isEqualTo("50.0 KB/s");
        assertThat(IoTraceRenderer.throughput(7 * 1024 * 1024, 1_000_000_000)).isEqualTo("7.0 MB/s");
        assertThat(IoTraceRenderer.throughput(3L * 1024 * 1024 * 1024, 1_000_000_000)).isEqualTo("3.0 GB/s");
    }

    @Test
    void missingExecutionRendersFailure() {
        StaticFetchPlan plan = fusedWithGap();
        FetchPlanConformance.Result result = FetchPlanConformance.match(plan, List.of());
        String out = IoTraceRenderer.renderTrace(List.of(), result);
        assertThat(out).contains("Conformance: FAILED");
        assertThat(out).contains("missing execution for node 1");
    }

    @Test
    void summaryReportsOverFetch() {
        String out = IoTraceRenderer.renderSummary(1, List.of(fusedWithGap()), 64 * 1024);
        assertThat(out).contains("SUPPORTED");
        assertThat(out).contains("nodes          1");
        assertThat(out).contains("requirements 2");
        // 200 useful of 300 fetched → 100 over-fetch (33.3%).
        assertThat(out).contains("(33.3%)");
        assertThat(out).contains("(default)");
    }
}
