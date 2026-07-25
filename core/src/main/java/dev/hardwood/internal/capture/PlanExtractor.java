/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.capture;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// Reconstructs and validates [StaticFetchPlan]s from a mechanism-neutral
/// [RecordSet], enforcing the dual-closure-plus-manifest protocol.
///
/// This is the single reconstruction path both mechanisms feed, so the
/// go/no-go properties — exact plan reconstruction, request correlation
/// across async handoffs, incomplete-recording detection — are tested once
/// against one implementation. The JFR mechanism differs from the observer
/// only in *how the RecordSet is produced* (parse a `.jfr` file vs read an
/// in-memory buffer); everything below is shared.
///
/// Validation, in order (any failure throws [CaptureLossException]):
///
/// 1. schema version matches; no `jdk.DataLoss`;
/// 2. every expected execution (from the manifest) has exactly one plan seal
///    and exactly one execution seal; no unexpected execution IDs;
/// 3. seal counts equal the observed record counts; the recomputed plan hash
///    equals the sealed hash; the recomputed order-independent attempt hash
///    equals the sealed execution hash;
/// 4. semantic conformance: every requirement and edge references a node in
///    the same sealed plan; every request resolves unambiguously to a node;
///    node IDs are unique; no dangling edges; no duplicate attempt IDs.
public final class PlanExtractor {

    private PlanExtractor() {
    }

    /// Extracts the validated plan for a single-execution recording.
    public static StaticFetchPlan extractSingle(RecordSet records, ScenarioManifest manifest) {
        List<StaticFetchPlan> plans = extract(records, manifest);
        if (plans.size() != 1) {
            throw new CaptureLossException(
                    "Expected exactly one plan but reconstructed " + plans.size());
        }
        return plans.get(0);
    }

    /// Extracts and validates every expected execution's plan.
    public static List<StaticFetchPlan> extract(RecordSet records, ScenarioManifest manifest) {
        if (records.schemaVersion() != CaptureSchema.VERSION) {
            throw new CaptureLossException("Schema version mismatch: recording is "
                    + records.schemaVersion() + ", extractor expects " + CaptureSchema.VERSION);
        }
        if (records.dataLoss()) {
            throw new CaptureLossException("Recording contains jdk.DataLoss — capture was truncated");
        }

        Set<Long> expected = manifest.expectedExecutionIds();

        // Group seals by execution and enforce exactly-one, no-duplicate,
        // no-unexpected — this is where a lost or duplicated seal is caught.
        Map<Long, CaptureRecords.PlanSealed> planSealByExec = new HashMap<>();
        for (CaptureRecords.PlanSealed seal : records.planSeals()) {
            requireExpected(expected, seal.executionId(), "plan seal");
            if (planSealByExec.putIfAbsent(seal.executionId(), seal) != null) {
                throw new CaptureLossException(
                        "Duplicate plan seal for execution " + seal.executionId());
            }
        }
        Map<Long, CaptureRecords.ExecutionSealed> execSealByExec = new HashMap<>();
        for (CaptureRecords.ExecutionSealed seal : records.executionSeals()) {
            requireExpected(expected, seal.executionId(), "execution seal");
            if (execSealByExec.putIfAbsent(seal.executionId(), seal) != null) {
                throw new CaptureLossException(
                        "Duplicate execution seal for execution " + seal.executionId());
            }
        }
        for (long execId : expected) {
            if (!planSealByExec.containsKey(execId)) {
                throw new CaptureLossException("Missing plan seal for expected execution " + execId);
            }
            if (!execSealByExec.containsKey(execId)) {
                throw new CaptureLossException("Missing execution seal for expected execution " + execId);
            }
        }

        List<StaticFetchPlan> result = new ArrayList<>(expected.size());
        for (long execId : expected) {
            result.add(reconstructOne(execId, records,
                    planSealByExec.get(execId), execSealByExec.get(execId)));
        }
        return result;
    }

    private static StaticFetchPlan reconstructOne(long execId, RecordSet records,
                                                  CaptureRecords.PlanSealed planSeal,
                                                  CaptureRecords.ExecutionSealed execSeal) {
        long planId = planSeal.planId();

        // Collect this execution's nodes; reject duplicate node IDs.
        List<StaticFetchPlan.Node> nodes = new ArrayList<>();
        Set<Long> nodeIds = new HashSet<>();
        CanonicalHash nodeHash = new CanonicalHash();
        for (CaptureRecords.PlanNode n : records.planNodes()) {
            if (n.executionId() != execId) {
                continue;
            }
            requireSamePlan(n.planId(), planId, "plan node " + n.nodeId());
            if (!nodeIds.add(n.nodeId())) {
                throw new CaptureLossException("Duplicate node ID " + n.nodeId()
                        + " in execution " + execId);
            }
            nodes.add(new StaticFetchPlan.Node(n.nodeId(), n.offset(), n.length(), n.stage(), n.role()));
            nodeHash.add(CanonicalHash.planNode(n.nodeId(), n.offset(), n.length(), n.stage(), n.role()));
        }

        // Requirements must reference a node in this sealed plan.
        List<StaticFetchPlan.Requirement> requirements = new ArrayList<>();
        for (CaptureRecords.PlanRequirement r : records.planRequirements()) {
            if (r.executionId() != execId) {
                continue;
            }
            requireSamePlan(r.planId(), planId, "requirement " + r.requirementId());
            if (!nodeIds.contains(r.requestNodeId())) {
                throw new CaptureLossException("Requirement " + r.requirementId()
                        + " references unknown node " + r.requestNodeId());
            }
            requirements.add(new StaticFetchPlan.Requirement(
                    r.requirementId(), r.requestNodeId(), r.offset(), r.length(), r.columnRole()));
        }

        // Edges must reference nodes in this sealed plan (no dangling edges).
        List<StaticFetchPlan.Edge> edges = new ArrayList<>();
        for (CaptureRecords.PlanEdge e : records.planEdges()) {
            if (e.executionId() != execId) {
                continue;
            }
            requireSamePlan(e.planId(), planId, "edge");
            if (!nodeIds.contains(e.fromNodeId()) || !nodeIds.contains(e.toNodeId())) {
                throw new CaptureLossException("Dangling edge " + e.fromNodeId()
                        + " -> " + e.toNodeId() + " in execution " + execId);
            }
            edges.add(new StaticFetchPlan.Edge(e.fromNodeId(), e.toNodeId(), e.edgeKind()));
        }

        // Plan-seal reconciliation: counts and hash.
        if (nodes.size() != planSeal.nodeCount()) {
            throw new CaptureLossException("Plan node count mismatch for execution " + execId
                    + ": sealed " + planSeal.nodeCount() + ", reconstructed " + nodes.size());
        }
        if (requirements.size() != planSeal.requirementCount()) {
            throw new CaptureLossException("Requirement count mismatch for execution " + execId
                    + ": sealed " + planSeal.requirementCount() + ", reconstructed " + requirements.size());
        }
        if (edges.size() != planSeal.edgeCount()) {
            throw new CaptureLossException("Edge count mismatch for execution " + execId
                    + ": sealed " + planSeal.edgeCount() + ", reconstructed " + edges.size());
        }
        String recomputedPlanHash = nodeHash.digest();
        if (!recomputedPlanHash.equals(planSeal.planHash())) {
            throw new CaptureLossException("Plan hash mismatch for execution " + execId
                    + " — a plan node was lost or mutated in transport");
        }

        // Execution-seal reconciliation: request attempts must resolve to a
        // node in this plan, attempt IDs unique, count and order-independent
        // hash match. This is what a plan-only seal cannot do.
        Set<Long> attemptIds = new HashSet<>();
        CanonicalHash attemptHash = new CanonicalHash();
        int attemptCount = 0;
        for (CaptureRecords.Request req : records.requests()) {
            if (req.executionId() != execId) {
                continue;
            }
            requireSamePlan(req.planId(), planId, "request attempt " + req.attemptId());
            if (!nodeIds.contains(req.nodeId())) {
                throw new CaptureLossException("Unplanned request against unknown node "
                        + req.nodeId() + " in execution " + execId);
            }
            if (!attemptIds.add(req.attemptId())) {
                throw new CaptureLossException("Duplicate attempt ID " + req.attemptId()
                        + " in execution " + execId);
            }
            attemptHash.add(CanonicalHash.requestAttempt(req.nodeId(), req.attemptId(),
                    req.actualOffset(), req.actualLength(), req.stage(), req.outcome()));
            attemptCount++;
        }
        if (attemptCount != execSeal.requestAttemptCount()) {
            throw new CaptureLossException("Request attempt count mismatch for execution " + execId
                    + ": sealed " + execSeal.requestAttemptCount() + ", reconstructed " + attemptCount);
        }
        if (!attemptHash.digest().equals(execSeal.requestAttemptHash())) {
            throw new CaptureLossException("Execution attempt hash mismatch for execution " + execId
                    + " — a request attempt was lost, duplicated, or mutated in transport");
        }

        return new StaticFetchPlan(execId, planId, nodes, requirements, edges,
                planSeal.status(), planSeal.reason(), recomputedPlanHash);
    }

    private static void requireExpected(Set<Long> expected, long execId, String what) {
        if (!expected.contains(execId)) {
            throw new CaptureLossException("Unexpected execution ID " + execId
                    + " on " + what + " — not in scenario manifest");
        }
    }

    private static void requireSamePlan(long planId, long expectedPlanId, String what) {
        if (planId != expectedPlanId) {
            throw new CaptureLossException(what + " belongs to plan " + planId
                    + " but the execution's sealed plan is " + expectedPlanId);
        }
    }
}
