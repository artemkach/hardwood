/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.capture;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The incomplete-recording detection half of the spike's go/no-go, exercised
/// at the reconstruction layer both mechanisms share. Each test builds a
/// well-formed [RecordSet], drops or mutates one record, and asserts the
/// dual-closure-plus-manifest protocol rejects it.
///
/// These are mechanism-independent by design: the protocol lives above
/// [CaptureSink], so a lost record is caught identically whether the loss
/// happened in a JFR buffer or (hypothetically) an observer.
class PlanExtractorLossTest {

    private static final long EXEC = 42L;
    private static final long PLAN = 0L;

    /// Builds a valid two-node record set (a fused node with two requirements
    /// plus one standalone node), each with one executed attempt, correctly
    /// sealed. Serves as the baseline the loss cases perturb.
    private static Fixture valid() {
        List<CaptureRecords.PlanNode> nodes = new ArrayList<>();
        List<CaptureRecords.PlanRequirement> reqs = new ArrayList<>();
        List<CaptureRecords.Request> requests = new ArrayList<>();

        CanonicalHash nodeHash = new CanonicalHash();
        // node 1: fused region [0,300) serving cols at [0,100) and [200,100)
        nodes.add(new CaptureRecords.PlanNode(EXEC, PLAN, 1, 0, 300, CaptureSchema.STAGE_DATA, "fused"));
        nodeHash.add(CanonicalHash.planNode(1, 0, 300, CaptureSchema.STAGE_DATA, "fused"));
        // node 2: standalone [500,50)
        nodes.add(new CaptureRecords.PlanNode(EXEC, PLAN, 2, 500, 50, CaptureSchema.STAGE_DATA, "col2"));
        nodeHash.add(CanonicalHash.planNode(2, 500, 50, CaptureSchema.STAGE_DATA, "col2"));

        reqs.add(new CaptureRecords.PlanRequirement(EXEC, PLAN, 1, 1, 0, 100, CaptureSchema.ROLE_COLUMN_FIRST_READ));
        reqs.add(new CaptureRecords.PlanRequirement(EXEC, PLAN, 2, 1, 200, 100, CaptureSchema.ROLE_COLUMN_FIRST_READ));
        reqs.add(new CaptureRecords.PlanRequirement(EXEC, PLAN, 3, 2, 500, 50, CaptureSchema.ROLE_COLUMN_FIRST_READ));

        CaptureRecords.PlanSealed planSeal = new CaptureRecords.PlanSealed(
                EXEC, PLAN, 2, 3, 0, nodeHash.digest(), CaptureSchema.STATUS_SUPPORTED, "");

        CanonicalHash attemptHash = new CanonicalHash();
        requests.add(new CaptureRecords.Request(EXEC, PLAN, 1, 1, 0, 300,
                CaptureSchema.STAGE_DATA, CaptureSchema.OUTCOME_SUCCESS, 0, 10));
        attemptHash.add(CanonicalHash.requestAttempt(1, 1, 0, 300, CaptureSchema.STAGE_DATA, CaptureSchema.OUTCOME_SUCCESS));
        requests.add(new CaptureRecords.Request(EXEC, PLAN, 2, 2, 500, 50,
                CaptureSchema.STAGE_DATA, CaptureSchema.OUTCOME_SUCCESS, 0, 10));
        attemptHash.add(CanonicalHash.requestAttempt(2, 2, 500, 50, CaptureSchema.STAGE_DATA, CaptureSchema.OUTCOME_SUCCESS));

        CaptureRecords.ExecutionSealed execSeal = new CaptureRecords.ExecutionSealed(
                EXEC, 2, attemptHash.digest(), CaptureSchema.TERMINAL_COMPLETE);

        List<CaptureRecords.PlanSealed> planSeals = new ArrayList<>();
        planSeals.add(planSeal);
        List<CaptureRecords.ExecutionSealed> execSeals = new ArrayList<>();
        execSeals.add(execSeal);
        return new Fixture(nodes, reqs, new ArrayList<>(), planSeals, requests, execSeals);
    }

    private record Fixture(
            List<CaptureRecords.PlanNode> nodes,
            List<CaptureRecords.PlanRequirement> reqs,
            List<CaptureRecords.PlanEdge> edges,
            List<CaptureRecords.PlanSealed> planSeals,
            List<CaptureRecords.Request> requests,
            List<CaptureRecords.ExecutionSealed> execSeals) {

        RecordSet set(boolean dataLoss) {
            return new RecordSet(CaptureSchema.VERSION, dataLoss, nodes, reqs, edges, planSeals, requests, execSeals);
        }
    }

    private static ScenarioManifest manifest() {
        return ScenarioManifest.single(EXEC);
    }

    @Test
    void validRecordSetReconstructsExactly() {
        StaticFetchPlan plan = PlanExtractor.extractSingle(valid().set(false), manifest());
        assertThat(plan.dataStageNodeCount()).isEqualTo(2);
        assertThat(plan.requirements()).hasSize(3);
        assertThat(plan.usefulBytes()).isEqualTo(250);      // 100 + 100 + 50
        assertThat(plan.fetchedBytes()).isEqualTo(350);     // 300 fused + 50
        assertThat(plan.isSupported()).isTrue();
    }

    @Test
    void dataLossRejected() {
        assertThatThrownBy(() -> PlanExtractor.extractSingle(valid().set(true), manifest()))
                .isInstanceOf(CaptureLossException.class)
                .hasMessageContaining("jdk.DataLoss");
    }

    @Test
    void missingPlanSealRejected() {
        Fixture f = valid();
        f.planSeals().clear();
        assertThatThrownBy(() -> PlanExtractor.extractSingle(
                new RecordSet(CaptureSchema.VERSION, false, f.nodes(), f.reqs(), f.edges(),
                        f.planSeals(), f.requests(), f.execSeals()), manifest()))
                .isInstanceOf(CaptureLossException.class)
                .hasMessageContaining("Missing plan seal");
    }

    @Test
    void missingExecutionSealRejected() {
        Fixture f = valid();
        assertThatThrownBy(() -> PlanExtractor.extractSingle(
                new RecordSet(CaptureSchema.VERSION, false, f.nodes(), f.reqs(), f.edges(),
                        f.planSeals(), f.requests(), List.of()), manifest()))
                .isInstanceOf(CaptureLossException.class)
                .hasMessageContaining("Missing execution seal");
    }

    @Test
    void lostPlanNodeCaughtByCountAndHash() {
        Fixture f = valid();
        // Drop the standalone node (id 2) and the requirement that names it, so
        // the referential check passes and the seal's node-count claim (2) is
        // what fails against the single surviving node.
        f.nodes().remove(1);
        f.reqs().removeIf(r -> r.requestNodeId() == 2);
        assertThatThrownBy(() -> PlanExtractor.extractSingle(f.set(false), manifest()))
                .isInstanceOf(CaptureLossException.class)
                .hasMessageContaining("node count mismatch");
    }

    @Test
    void mutatedPlanNodeCaughtByHash() {
        Fixture f = valid();
        CaptureRecords.PlanNode n = f.nodes().get(0);
        // Same count, mutated length — count passes, hash must catch it.
        f.nodes().set(0, new CaptureRecords.PlanNode(
                n.executionId(), n.planId(), n.nodeId(), n.offset(), n.length() + 1, n.stage(), n.role()));
        assertThatThrownBy(() -> PlanExtractor.extractSingle(f.set(false), manifest()))
                .isInstanceOf(CaptureLossException.class)
                .hasMessageContaining("Plan hash mismatch");
    }

    @Test
    void lostRequestAttemptCaughtByExecutionSeal() {
        Fixture f = valid();
        f.requests().remove(1);     // drop an attempt; plan seal still fine
        assertThatThrownBy(() -> PlanExtractor.extractSingle(f.set(false), manifest()))
                .isInstanceOf(CaptureLossException.class)
                .hasMessageContaining("attempt count mismatch");
    }

    @Test
    void duplicateRequestAttemptRejected() {
        Fixture f = valid();
        f.requests().add(f.requests().get(0));   // duplicate attempt id
        assertThatThrownBy(() -> PlanExtractor.extractSingle(f.set(false), manifest()))
                .isInstanceOf(CaptureLossException.class)
                .hasMessageContaining("Duplicate attempt ID");
    }

    @Test
    void unplannedRequestRejected() {
        Fixture f = valid();
        f.requests().add(new CaptureRecords.Request(EXEC, PLAN, 999, 3, 700, 20,
                CaptureSchema.STAGE_DATA, CaptureSchema.OUTCOME_SUCCESS, 0, 5));
        assertThatThrownBy(() -> PlanExtractor.extractSingle(f.set(false), manifest()))
                .isInstanceOf(CaptureLossException.class)
                .hasMessageContaining("unknown node");
    }

    @Test
    void requirementReferencingUnknownNodeRejected() {
        Fixture f = valid();
        f.reqs().add(new CaptureRecords.PlanRequirement(EXEC, PLAN, 99, 888, 0, 1,
                CaptureSchema.ROLE_COLUMN_FIRST_READ));
        assertThatThrownBy(() -> PlanExtractor.extractSingle(f.set(false), manifest()))
                .isInstanceOf(CaptureLossException.class)
                .hasMessageContaining("references unknown node");
    }

    @Test
    void unexpectedExecutionIdRejected() {
        Fixture f = valid();
        assertThatThrownBy(() -> PlanExtractor.extractSingle(f.set(false),
                ScenarioManifest.single(EXEC + 1)))
                .isInstanceOf(CaptureLossException.class);
    }

    @Test
    void schemaVersionMismatchRejected() {
        Fixture f = valid();
        RecordSet wrongVersion = new RecordSet(CaptureSchema.VERSION + 1, false,
                f.nodes(), f.reqs(), f.edges(), f.planSeals(), f.requests(), f.execSeals());
        assertThatThrownBy(() -> PlanExtractor.extractSingle(wrongVersion, manifest()))
                .isInstanceOf(CaptureLossException.class)
                .hasMessageContaining("Schema version mismatch");
    }

    @Test
    void attemptHashIsOrderIndependent() {
        // The execution seal must survive request attempts arriving in any
        // order (they commit from many threads). Reverse the request list and
        // confirm extraction still accepts it.
        Fixture f = valid();
        List<CaptureRecords.Request> reversed = new ArrayList<>(f.requests());
        Collections.reverse(reversed);
        RecordSet set = new RecordSet(CaptureSchema.VERSION, false, f.nodes(), f.reqs(), f.edges(),
                f.planSeals(), reversed, f.execSeals());
        assertThatCode(() -> PlanExtractor.extractSingle(set, manifest())).doesNotThrowAnyException();
    }
}
