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

import dev.hardwood.internal.capture.CaptureSchema;
import dev.hardwood.internal.capture.PlanConformance;
import dev.hardwood.internal.capture.StaticFetchPlan;
import dev.hardwood.internal.capture.TracingInputFile;

import static org.assertj.core.api.Assertions.assertThat;

/// Renderer unit tests against hand-built plans: fused (with a dead gap),
/// split, incomplete, and empty shapes, plus conformance verdict rendering.
class IotraceRendererTest {

    private static StaticFetchPlan fusedWithGap() {
        // One node [0, 300) serving requirements [0,100) and [200,100):
        // bytes [100, 200) are dead gap fetched by the fused node.
        return new StaticFetchPlan(1, 0,
                List.of(new StaticFetchPlan.Node(1, 0, 300, CaptureSchema.STAGE_DATA, "fused")),
                List.of(new StaticFetchPlan.Requirement(1, 1, 0, 100, "col a"),
                        new StaticFetchPlan.Requirement(2, 1, 200, 100, "col b")),
                List.of(),
                CaptureSchema.STATUS_SUPPORTED, "", "h");
    }

    @Test
    void fusedBarShowsUsefulAndDeadBytes() {
        StaticFetchPlan plan = fusedWithGap();
        String bar = IotraceRenderer.renderBar(plan, 0, 300);
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
                List.of(new StaticFetchPlan.Node(1, 0, 100, CaptureSchema.STAGE_DATA, "a"),
                        new StaticFetchPlan.Node(2, 200, 100, CaptureSchema.STAGE_DATA, "b")),
                List.of(new StaticFetchPlan.Requirement(1, 1, 0, 100, "a"),
                        new StaticFetchPlan.Requirement(2, 2, 200, 100, "b")),
                List.of(),
                CaptureSchema.STATUS_SUPPORTED, "", "h");
        String bar = IotraceRenderer.renderBar(plan, 0, 300);
        assertThat(bar.charAt(0)).isEqualTo('█');
        assertThat(bar.charAt(32)).isEqualTo(' ');
        assertThat(bar.charAt(63)).isEqualTo('█');
    }

    @Test
    void incompletePlanIsLabeled() {
        StaticFetchPlan plan = new StaticFetchPlan(1, 2, List.of(), List.of(), List.of(),
                CaptureSchema.STATUS_INCOMPLETE, "head(5) truncation", "h");
        String out = IotraceRenderer.renderPlan(plan);
        assertThat(out).contains("Plan 2");
        assertThat(out).contains("INCOMPLETE: head(5) truncation");
        assertThat(out).contains("no data-stage nodes");
    }

    @Test
    void fusedNodeTableNamesRequirementCount() {
        String out = IotraceRenderer.renderPlan(fusedWithGap());
        assertThat(out).contains("2 requirements (fused)");
    }

    @Test
    void conformantTraceRendersVerdictAndCorrelation() {
        StaticFetchPlan plan = fusedWithGap();
        List<TracingInputFile.TracedRead> trace = List.of(
                new TracingInputFile.TracedRead(9000, 8),      // metadata
                new TracingInputFile.TracedRead(0, 300));      // node 1
        PlanConformance.Result result = PlanConformance.match(plan, trace);
        String out = IotraceRenderer.renderTrace(trace, result, false);
        assertThat(out).contains("Conformance: OK");
        assertThat(out).contains("node 1");
        assertThat(out).contains("metadata");
        assertThat(out).contains("1 read(s) outside the data-stage plan");
    }

    @Test
    void missingExecutionRendersFailure() {
        StaticFetchPlan plan = fusedWithGap();
        PlanConformance.Result result = PlanConformance.match(plan, List.of());
        String out = IotraceRenderer.renderTrace(List.of(), result, false);
        assertThat(out).contains("Conformance: FAILED");
        assertThat(out).contains("missing execution for node 1");
    }

    @Test
    void summaryReportsOverFetch() {
        String out = IotraceRenderer.renderSummary(1, List.of(fusedWithGap()), 64 * 1024);
        assertThat(out).contains("SUPPORTED");
        assertThat(out).contains("nodes          1");
        assertThat(out).contains("requirements 2");
        // 200 useful of 300 fetched → 100 over-fetch (33.3%).
        assertThat(out).contains("(33.3%)");
        assertThat(out).contains("(default)");
    }
}
