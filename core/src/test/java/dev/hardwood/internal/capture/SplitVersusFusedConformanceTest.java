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

import static org.assertj.core.api.Assertions.assertThat;

/// The spike's lazy-sequential-identity deliverable, in both plan shapes: the
/// same fixture bytes captured once as a **fused** plan (default gap) and once
/// as a **split** plan (gap override forcing per-column reads), with
/// conformance holding in both — proving the identity that reaches the lazily
/// created first [dev.hardwood.internal.reader.ChunkHandle] of an uncoalesced
/// [dev.hardwood.internal.reader.SequentialFetchPlan] is correct.
///
/// The gap knob (`hardwood.internal.maxCrossColGapBytes`) is read per iterator
/// construction, so the two captures below differ only in that property — the
/// executable-contender-control the flagship needs, exercised here for the
/// capture path rather than for timing. Production default stays 64 KB.
class SplitVersusFusedConformanceTest {

    /// 20-column flat schema, single row group, no OffsetIndex →
    /// SequentialFetchPlan path, columns stored back-to-back (0-byte gaps).
    private static final Path SEQ_FILE = Path.of("src/test/resources/yellow_tripdata_sample.parquet");

    private static final String GAP_PROPERTY = "hardwood.internal.maxCrossColGapBytes";

    @Test
    void fusedPlanCapturedWithDefaultGap() throws Exception {
        StaticFetchPlan plan = CaptureHarness.captureWithObserver(
                InputFile.of(SEQ_FILE), null, 300L);
        // 0-byte gaps ≤ 64 KB default → all columns fuse into one node.
        assertThat(plan.dataStageNodeCount()).isEqualTo(1);
        assertThat(plan.isSupported()).isTrue();
    }

    @Test
    void splitPlanCapturedWithNegativeGapOverride() throws Exception {
        // A gap override below 0 forbids bridging even the 0-byte gaps, so the
        // real planner emits one standalone node per column — a split plan on
        // identical fixture bytes. Each standalone node's lazily created first
        // ChunkHandle must still be stamped with its published identity, so
        // conformance (attempt hash == execution seal) holds.
        String previous = System.getProperty(GAP_PROPERTY);
        System.setProperty(GAP_PROPERTY, "-1");
        try {
            StaticFetchPlan plan = CaptureHarness.captureWithObserver(
                    InputFile.of(SEQ_FILE), null, 301L);
            // Many standalone nodes now, one per non-empty projected column.
            assertThat(plan.dataStageNodeCount()).isGreaterThan(1);
            // Extraction only returns a plan when the execution seal reconciles
            // — i.e. every standalone read was recorded as an attempt against
            // its node. Reaching here already proves conformance; assert the
            // supported status and one-requirement-per-node shape explicitly.
            assertThat(plan.isSupported()).isTrue();
            assertThat(plan.requirements()).hasSameSizeAs(plan.nodes());
        }
        finally {
            if (previous == null) {
                System.clearProperty(GAP_PROPERTY);
            }
            else {
                System.setProperty(GAP_PROPERTY, previous);
            }
        }
    }
}
