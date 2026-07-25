/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.capture;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import dev.hardwood.InputFile;
import dev.hardwood.reader.FilterPredicate;
import dev.hardwood.schema.ColumnProjection;

import static org.assertj.core.api.Assertions.assertThat;

/// The spike's lazy-sequential-identity deliverable (spike item b), in both
/// plan shapes: the same fixture bytes captured once as a **fused** plan
/// (default gap) and once as a **split** plan (gap override forcing
/// per-column reads), with full plan-vs-execution conformance in both —
/// proving the identity that reaches the lazily created first
/// [dev.hardwood.internal.reader.ChunkHandle] of an uncoalesced
/// [dev.hardwood.internal.reader.SequentialFetchPlan] is correct.
///
/// Conformance is checked two independent ways per shape:
///
/// 1. the execution seal reconciles (the capture's own attempt records match
///    the sealed count/hash — extraction throws otherwise), and
/// 2. an independent [TracingInputFile] trace at the `InputFile` seam matches
///    the plan's nodes **one-to-one** on exact `(offset, length)` — a second
///    witness that does not share the capture's wiring.
///
/// The gap knob (`hardwood.internal.maxCrossColGapBytes`) is read per iterator
/// construction, so the two captures below differ only in that property — the
/// executable-contender-control the flagship needs, exercised here for the
/// capture path rather than for timing. Production default stays 64 KB.
class SplitVersusFusedConformanceTest {

    /// 20-column flat schema, single row group, no OffsetIndex →
    /// SequentialFetchPlan path, columns stored back-to-back (0-byte gaps).
    private static final Path SEQ_FILE = Path.of("src/test/resources/yellow_tripdata_sample.parquet");

    /// 3-column flat schema, single row group, OffsetIndex present →
    /// IndexedFetchPlan path. A selective filter on `id` drops pages, making
    /// that column's first read non-static.
    private static final Path INDEXED_FILE = Path.of("src/test/resources/page_index_test.parquet");

    private static final String GAP_PROPERTY = "hardwood.internal.maxCrossColGapBytes";

    @Test
    void fusedPlanConformsAtTheInputFileSeam() throws Exception {
        CaptureHarness.Captured captured = CaptureHarness.captureWithTrace(
                InputFile.of(SEQ_FILE), null, 300L);
        StaticFetchPlan plan = captured.plan();

        // 0-byte gaps ≤ 64 KB default → all columns fuse into one node.
        assertThat(plan.isSupported()).isTrue();
        assertThat(plan.dataStageNodeCount()).isEqualTo(1);

        // One-to-one conformance: the fused node was executed exactly once
        // with its exact range. Everything left over is metadata-stage
        // (footer reads), which the v0 data-stage plan does not model — the
        // local footer path issues three serial readRange calls.
        PlanConformance.Result result =
                PlanConformance.matchOneToOne(plan, captured.trace().reads());
        assertThat(result.unmatchedReads())
                .as("unmatched reads must all be metadata-stage (three local footer reads)")
                .hasSize(3);
    }

    @Test
    void splitPlanConformsAtTheInputFileSeam() throws Exception {
        // A gap override below 0 forbids bridging even the 0-byte gaps, so the
        // real planner emits one standalone node per column — a split plan on
        // identical fixture bytes. Each standalone node's first ChunkHandle is
        // created lazily in advanceChunk(0); its stamped identity must produce
        // an execution that matches the plan one-to-one.
        String previous = System.getProperty(GAP_PROPERTY);
        System.setProperty(GAP_PROPERTY, "-1");
        try {
            CaptureHarness.Captured captured = CaptureHarness.captureWithTrace(
                    InputFile.of(SEQ_FILE), null, 301L);
            StaticFetchPlan plan = captured.plan();

            assertThat(plan.isSupported()).isTrue();
            assertThat(plan.dataStageNodeCount()).isGreaterThan(1);
            assertThat(plan.requirements()).hasSameSizeAs(plan.nodes());

            PlanConformance.Result result =
                    PlanConformance.matchOneToOne(plan, captured.trace().reads());
            assertThat(result.unmatchedReads())
                    .as("unmatched reads must all be metadata-stage (three local footer reads)")
                    .hasSize(3);
        }
        finally {
            restore(previous);
        }
    }

    @Test
    void splitAndFusedCoverIdenticalUsefulBytes() throws Exception {
        // The two contenders describe the same fixture bytes: their
        // requirements (per-column first reads) are identical; only the
        // materialization into final nodes differs.
        StaticFetchPlan fused = CaptureHarness.captureWithObserver(
                InputFile.of(SEQ_FILE), null, 302L);

        String previous = System.getProperty(GAP_PROPERTY);
        System.setProperty(GAP_PROPERTY, "-1");
        StaticFetchPlan split;
        try {
            split = CaptureHarness.captureWithObserver(InputFile.of(SEQ_FILE), null, 303L);
        }
        finally {
            restore(previous);
        }

        assertThat(split.usefulBytes()).isEqualTo(fused.usefulBytes());
        assertThat(split.requirements()).hasSameSizeAs(fused.requirements());
        // The fixture's columns are back-to-back (0-byte gaps), so the fused
        // node spans exactly the sum of its members — no dead bytes here.
        assertThat(fused.fetchedBytes()).isEqualTo(fused.usefulBytes());
    }

    @Test
    void pageDroppingFilterSealsPlanIncomplete() throws Exception {
        // A selective filter on `id` drops pages → the column has multiple
        // page groups → its first read is not statically known. The v0
        // contract requires such a plan to be sealed INCOMPLETE (with a
        // reason), never silently exported as a complete DAG.
        StaticFetchPlan plan = CaptureHarness.captureFilteredWithObserver(
                InputFile.of(INDEXED_FILE),
                ColumnProjection.columns("id", "value", "category"),
                FilterPredicate.lt("id", 1000L), 304L);

        assertThat(plan.isSupported()).isFalse();
        assertThat(plan.status()).isEqualTo(CaptureSchema.STATUS_INCOMPLETE);
        assertThat(plan.reason()).isNotEmpty();
    }

    private static void restore(String previous) {
        if (previous == null) {
            System.clearProperty(GAP_PROPERTY);
        }
        else {
            System.setProperty(GAP_PROPERTY, previous);
        }
    }
}
