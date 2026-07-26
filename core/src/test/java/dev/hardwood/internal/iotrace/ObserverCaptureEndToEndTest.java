/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.iotrace;

import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

import dev.hardwood.InputFile;
import dev.hardwood.schema.ColumnProjection;

import static org.assertj.core.api.Assertions.assertThat;

/// The reconstruction, correlation, and isolation half of the spike's go/no-go,
/// run against the real planner through the observer mechanism.
///
/// The observer never loses records, so a green run here proves the *plan
/// construction and identity plumbing* are correct independently of any JFR
/// transport question — exactly the separation the spike wants: identity is
/// the irreducible custom work, mechanism only moves delivery.
class ObserverCaptureEndToEndTest {

    /// 20-column flat schema, single row group, no OffsetIndex →
    /// SequentialFetchPlan path, columns back-to-back (fuses today).
    private static final Path SEQ_FILE = Path.of("src/test/resources/yellow_tripdata_sample.parquet");

    /// 3-column flat schema, single row group, OffsetIndex present →
    /// IndexedFetchPlan path.
    private static final Path INDEXED_FILE = Path.of("src/test/resources/page_index_test.parquet");

    @Test
    void sequentialPlanFusesIntoOneNode() throws Exception {
        StaticFetchPlan plan = IoTraceHarness.captureWithObserver(
                InputFile.of(SEQ_FILE), null, 1L);

        // 20 columns' first reads coalesce into a single fused data node,
        // carrying one requirement per non-empty projected column.
        assertThat(plan.isSupported()).isTrue();
        assertThat(plan.dataStageNodeCount()).isEqualTo(1);
        assertThat(plan.requirements().size()).isGreaterThanOrEqualTo(2);
        // Every requirement materializes into the single node.
        long nodeId = plan.nodes().get(0).nodeId();
        assertThat(plan.requirements())
                .allMatch(r -> r.requestNodeId() == nodeId);
    }

    @Test
    void indexedPlanReconstructsAndConforms() throws Exception {
        StaticFetchPlan plan = IoTraceHarness.captureWithObserver(
                InputFile.of(INDEXED_FILE),
                ColumnProjection.columns("id", "value", "category"), 7L);

        assertThat(plan.isSupported()).isTrue();
        // 3 columns back-to-back → one fused node with three requirements.
        assertThat(plan.dataStageNodeCount()).isEqualTo(1);
        assertThat(plan.requirements()).hasSize(3);
        // Useful bytes never exceed fetched bytes (fused node may span a gap).
        assertThat(plan.usefulBytes()).isLessThanOrEqualTo(plan.fetchedBytes());
    }

    @Test
    void concurrentReadersAreIsolated() throws Exception {
        // Two readers capturing at the same time must not cross-contaminate:
        // each gets its own execution ID and its own plan, proving there is no
        // process-global capture state and no caller-thread affinity.
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<StaticFetchPlan> a = pool.submit(() ->
                    IoTraceHarness.captureWithObserver(InputFile.of(SEQ_FILE), null, 100L));
            Future<StaticFetchPlan> b = pool.submit(() ->
                    IoTraceHarness.captureWithObserver(
                            InputFile.of(INDEXED_FILE),
                            ColumnProjection.columns("id", "value", "category"), 200L));
            StaticFetchPlan planA = a.get();
            StaticFetchPlan planB = b.get();

            assertThat(planA.executionId()).isEqualTo(100L);
            assertThat(planB.executionId()).isEqualTo(200L);
            // The 20-column sequential file's fused node covers many more bytes
            // than the 3-column indexed file's — a coarse but decisive check
            // that neither plan leaked into the other.
            assertThat(planA.requirements().size()).isGreaterThan(planB.requirements().size());
        }
        finally {
            pool.shutdownNow();
        }
    }
}
