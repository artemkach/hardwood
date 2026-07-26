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
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.hardwood.InputFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Multi-row-group extraction: one execution produces one plan per row group,
/// published lazily and partly *asynchronously* (`prefetchNextRowGroup`
/// publishes plan N+1 from the common pool). These tests pin the two
/// assumptions the `iotrace` CLI rests on:
///
/// 1. every row group's plan seal is emitted before the execution seal —
///    `RowGroupIterator.close()` joins prefetch futures before sealing, so
///    the recording is complete by construction;
/// 2. the extractor reconstructs all N plans under one execution and keeps
///    per-plan loss detection intact (a lost or duplicated plan seal among N
///    is still caught).
class MultiPlanExtractionTest {

    /// 9.6 KB, 3 columns, 3 row groups.
    private static final Path MULTI_RG_FILE = Path.of("src/test/resources/filter_pushdown_int.parquet");

    @Test
    void multiRowGroupFileYieldsOnePlanPerRowGroup() throws IOException {
        ObserverIoTraceSink sink = new ObserverIoTraceSink();
        IoTraceContext context = IoTraceContext.start(500L, sink);
        IoTraceHarness.readUnderCapture(InputFile.of(MULTI_RG_FILE), null, context);

        List<StaticFetchPlan> plans = PlanExtractor.extract(
                sink.toRecordSet(), ScenarioManifest.singleUnknownPlans(500L));

        assertThat(plans).hasSize(3);
        // Plans are ordered by planId (the work-item index).
        assertThat(plans.get(0).planId()).isLessThan(plans.get(1).planId());
        assertThat(plans.get(1).planId()).isLessThan(plans.get(2).planId());
        for (StaticFetchPlan plan : plans) {
            assertThat(plan.isSupported()).isTrue();
            assertThat(plan.dataStageNodeCount()).isGreaterThanOrEqualTo(1);
        }
        // Node IDs are execution-global: no ID appears in two plans.
        long distinctNodeIds = plans.stream()
                .flatMap(p -> p.nodes().stream())
                .map(StaticFetchPlan.Node::nodeId)
                .distinct()
                .count();
        long totalNodes = plans.stream().mapToLong(p -> p.nodes().size()).sum();
        assertThat(distinctNodeIds).isEqualTo(totalNodes);
    }

    @Test
    void declaredPlanCountIsEnforced() throws IOException {
        ObserverIoTraceSink sink = new ObserverIoTraceSink();
        IoTraceContext context = IoTraceContext.start(501L, sink);
        IoTraceHarness.readUnderCapture(InputFile.of(MULTI_RG_FILE), null, context);
        IoTraceRecordSet records = sink.toRecordSet();

        // The right count passes...
        assertThat(PlanExtractor.extract(records,
                new ScenarioManifest(Set.of(501L), 3))).hasSize(3);
        // ...the wrong count is a loss signal.
        assertThatThrownBy(() -> PlanExtractor.extract(records,
                new ScenarioManifest(Set.of(501L), 2)))
                .isInstanceOf(IoTraceLossException.class)
                .hasMessageContaining("Expected 2 plan(s)");
    }

    @Test
    void lostPlanSealAmongManyIsCaughtByDeclaredCount() throws IOException {
        ObserverIoTraceSink sink = new ObserverIoTraceSink();
        IoTraceContext context = IoTraceContext.start(502L, sink);
        IoTraceHarness.readUnderCapture(InputFile.of(MULTI_RG_FILE), null, context);
        IoTraceRecordSet full = sink.toRecordSet();

        // Drop the middle plan's seal, keeping its nodes and requests.
        IoTraceRecordSet oneSealLost = new IoTraceRecordSet(full.schemaVersion(), full.dataLoss(),
                full.planNodes(), full.planRequirements(), full.planEdges(),
                full.planSeals().stream().skip(1).toList(),
                full.requests(), full.executionSeals());

        assertThatThrownBy(() -> PlanExtractor.extract(oneSealLost,
                new ScenarioManifest(Set.of(502L), 3)))
                .isInstanceOf(IoTraceLossException.class);
    }

    @Test
    void lostPlanSealWithUnknownCountIsCaughtByItsSurvivingRecords() throws IOException {
        // With an unknown plan count the manifest cannot see a vanished plan
        // — but a plan whose seal was lost while its nodes/requests survived
        // is still caught: its records reference a plan with no seal.
        ObserverIoTraceSink sink = new ObserverIoTraceSink();
        IoTraceContext context = IoTraceContext.start(503L, sink);
        IoTraceHarness.readUnderCapture(InputFile.of(MULTI_RG_FILE), null, context);
        IoTraceRecordSet full = sink.toRecordSet();

        IoTraceRecordSet oneSealLost = new IoTraceRecordSet(full.schemaVersion(), full.dataLoss(),
                full.planNodes(), full.planRequirements(), full.planEdges(),
                full.planSeals().stream().skip(1).toList(),
                full.requests(), full.executionSeals());

        assertThatThrownBy(() -> PlanExtractor.extract(oneSealLost,
                ScenarioManifest.singleUnknownPlans(503L)))
                .isInstanceOf(IoTraceLossException.class)
                .hasMessageContaining("no seal");
    }
}
