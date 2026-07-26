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
import java.util.TreeMap;

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
/// 2. every expected execution (from the manifest) has at least one plan
///    seal — exactly the manifest's count when declared — with per-plan seal
///    uniqueness, and exactly one execution seal; no unexpected execution IDs;
/// 3. seal counts equal the observed record counts per plan; the recomputed
///    plan hash equals each sealed hash; the recomputed order-independent
///    attempt hash equals the sealed execution hash;
/// 4. semantic conformance: every requirement and edge references a node in
///    the same sealed plan; every request resolves unambiguously to a node
///    of its claimed plan; node IDs are unique execution-wide; no dangling
///    or cross-plan edges; no duplicate attempt IDs.
public final class PlanExtractor {

    private PlanExtractor() {
    }

    /// Extracts the validated plan for a single-execution, single-plan
    /// recording. Rejects a recording with any other plan count.
    public static StaticFetchPlan extractSingle(RecordSet records, ScenarioManifest manifest) {
        List<StaticFetchPlan> plans = extract(records, manifest);
        if (plans.size() != 1) {
            throw new CaptureLossException(
                    "Expected exactly one plan but reconstructed " + plans.size());
        }
        return plans.get(0);
    }

    /// Extracts and validates every expected execution's plans, ordered by
    /// `(executionId, planId)`. One execution produces one plan per row group
    /// — plan seals are keyed by `(executionId, planId)` and each key must
    /// appear exactly once.
    public static List<StaticFetchPlan> extract(RecordSet records, ScenarioManifest manifest) {
        if (records.schemaVersion() != CaptureSchema.VERSION) {
            throw new CaptureLossException("Schema version mismatch: recording is "
                    + records.schemaVersion() + ", extractor expects " + CaptureSchema.VERSION);
        }
        if (records.dataLoss()) {
            throw new CaptureLossException("Recording contains jdk.DataLoss — capture was truncated");
        }

        Set<Long> expected = manifest.expectedExecutionIds();

        // Group plan seals by (execution, plan) and enforce per-key
        // uniqueness and no-unexpected-execution — where a duplicated seal
        // is caught. A *lost* plan seal is caught below: against the
        // manifest's plan count when declared, or by this plan's surviving
        // requests failing the unknown-request check when not.
        Map<Long, Map<Long, CaptureRecords.PlanSealed>> planSealsByExec = new HashMap<>();
        for (CaptureRecords.PlanSealed seal : records.planSeals()) {
            requireExpected(expected, seal.executionId(), "plan seal");
            Map<Long, CaptureRecords.PlanSealed> byPlan =
                    planSealsByExec.computeIfAbsent(seal.executionId(), k -> new TreeMap<>());
            if (byPlan.putIfAbsent(seal.planId(), seal) != null) {
                throw new CaptureLossException("Duplicate plan seal for execution "
                        + seal.executionId() + " plan " + seal.planId());
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
            Map<Long, CaptureRecords.PlanSealed> byPlan = planSealsByExec.get(execId);
            if (byPlan == null || byPlan.isEmpty()) {
                throw new CaptureLossException("Missing plan seal for expected execution " + execId);
            }
            if (manifest.planCountKnown() && byPlan.size() != manifest.expectedPlanCount()) {
                throw new CaptureLossException("Expected " + manifest.expectedPlanCount()
                        + " plan(s) for execution " + execId + " but found " + byPlan.size()
                        + " — a plan seal was lost or an unexpected plan appeared");
            }
            if (!execSealByExec.containsKey(execId)) {
                throw new CaptureLossException("Missing execution seal for expected execution " + execId);
            }
        }

        List<StaticFetchPlan> result = new ArrayList<>();
        for (long execId : expected) {
            result.addAll(reconstructExecution(execId, records,
                    planSealsByExec.get(execId), execSealByExec.get(execId)));
        }
        return result;
    }

    /// Reconstructs and validates all of one execution's plans, then
    /// reconciles the execution-wide request stream against the execution
    /// seal. Node IDs are execution-global, so a single ID set spans plans
    /// and every request resolves unambiguously to `(planId, nodeId)`.
    private static List<StaticFetchPlan> reconstructExecution(
            long execId, RecordSet records,
            Map<Long, CaptureRecords.PlanSealed> planSeals,
            CaptureRecords.ExecutionSealed execSeal) {

        // Per-plan accumulators, keyed by planId in stable order.
        Map<Long, List<StaticFetchPlan.Node>> nodesByPlan = new TreeMap<>();
        Map<Long, List<StaticFetchPlan.Requirement>> reqsByPlan = new TreeMap<>();
        Map<Long, List<StaticFetchPlan.Edge>> edgesByPlan = new TreeMap<>();
        Map<Long, CanonicalHash> nodeHashByPlan = new TreeMap<>();
        planSeals.keySet().forEach(planId -> {
            nodesByPlan.put(planId, new ArrayList<>());
            reqsByPlan.put(planId, new ArrayList<>());
            edgesByPlan.put(planId, new ArrayList<>());
            nodeHashByPlan.put(planId, new CanonicalHash());
        });

        // Node IDs are execution-global; map each to its plan so requests
        // (which carry both IDs) can be checked for consistent membership.
        Map<Long, Long> planByNodeId = new HashMap<>();

        for (CaptureRecords.PlanNode n : records.planNodes()) {
            if (n.executionId() != execId) {
                continue;
            }
            requireSealedPlan(planSeals, n.planId(), "plan node " + n.nodeId());
            if (planByNodeId.putIfAbsent(n.nodeId(), n.planId()) != null) {
                throw new CaptureLossException("Duplicate node ID " + n.nodeId()
                        + " in execution " + execId);
            }
            nodesByPlan.get(n.planId()).add(new StaticFetchPlan.Node(
                    n.nodeId(), n.offset(), n.length(), n.stage(), n.role()));
            nodeHashByPlan.get(n.planId()).add(CanonicalHash.planNode(
                    n.nodeId(), n.offset(), n.length(), n.stage(), n.role()));
        }

        for (CaptureRecords.PlanRequirement r : records.planRequirements()) {
            if (r.executionId() != execId) {
                continue;
            }
            requireSealedPlan(planSeals, r.planId(), "requirement " + r.requirementId());
            Long owningPlan = planByNodeId.get(r.requestNodeId());
            if (owningPlan == null || owningPlan != r.planId()) {
                throw new CaptureLossException("Requirement " + r.requirementId()
                        + " references node " + r.requestNodeId()
                        + " which is unknown or belongs to another plan");
            }
            reqsByPlan.get(r.planId()).add(new StaticFetchPlan.Requirement(
                    r.requirementId(), r.requestNodeId(), r.offset(), r.length(), r.columnRole()));
        }

        for (CaptureRecords.PlanEdge e : records.planEdges()) {
            if (e.executionId() != execId) {
                continue;
            }
            requireSealedPlan(planSeals, e.planId(), "edge");
            Long fromPlan = planByNodeId.get(e.fromNodeId());
            Long toPlan = planByNodeId.get(e.toNodeId());
            if (fromPlan == null || toPlan == null
                    || fromPlan != e.planId() || toPlan != e.planId()) {
                throw new CaptureLossException("Dangling or cross-plan edge " + e.fromNodeId()
                        + " -> " + e.toNodeId() + " in execution " + execId);
            }
            edgesByPlan.get(e.planId()).add(new StaticFetchPlan.Edge(
                    e.fromNodeId(), e.toNodeId(), e.edgeKind()));
        }

        // Per-plan seal reconciliation: counts and hash.
        List<StaticFetchPlan> plans = new ArrayList<>(planSeals.size());
        for (Map.Entry<Long, CaptureRecords.PlanSealed> entry : planSeals.entrySet()) {
            long planId = entry.getKey();
            CaptureRecords.PlanSealed seal = entry.getValue();
            List<StaticFetchPlan.Node> nodes = nodesByPlan.get(planId);
            List<StaticFetchPlan.Requirement> requirements = reqsByPlan.get(planId);
            List<StaticFetchPlan.Edge> edges = edgesByPlan.get(planId);

            if (nodes.size() != seal.nodeCount()) {
                throw new CaptureLossException("Plan node count mismatch for execution " + execId
                        + " plan " + planId + ": sealed " + seal.nodeCount()
                        + ", reconstructed " + nodes.size());
            }
            if (requirements.size() != seal.requirementCount()) {
                throw new CaptureLossException("Requirement count mismatch for execution " + execId
                        + " plan " + planId + ": sealed " + seal.requirementCount()
                        + ", reconstructed " + requirements.size());
            }
            if (edges.size() != seal.edgeCount()) {
                throw new CaptureLossException("Edge count mismatch for execution " + execId
                        + " plan " + planId + ": sealed " + seal.edgeCount()
                        + ", reconstructed " + edges.size());
            }
            String recomputedPlanHash = nodeHashByPlan.get(planId).digest();
            if (!recomputedPlanHash.equals(seal.planHash())) {
                throw new CaptureLossException("Plan hash mismatch for execution " + execId
                        + " plan " + planId + " — a plan node was lost or mutated in transport");
            }
            plans.add(new StaticFetchPlan(execId, planId, nodes, requirements, edges,
                    seal.status(), seal.reason(), recomputedPlanHash));
        }

        // Execution-seal reconciliation across ALL plans: every attempt must
        // resolve to a known node in its claimed plan, attempt IDs unique,
        // count and order-independent hash match. This is what a plan-only
        // seal cannot do.
        Set<Long> attemptIds = new HashSet<>();
        CanonicalHash attemptHash = new CanonicalHash();
        int attemptCount = 0;
        for (CaptureRecords.Request req : records.requests()) {
            if (req.executionId() != execId) {
                continue;
            }
            Long owningPlan = planByNodeId.get(req.nodeId());
            if (owningPlan == null || owningPlan != req.planId()) {
                throw new CaptureLossException("Unplanned request against unknown node "
                        + req.nodeId() + " (claimed plan " + req.planId() + ") in execution " + execId);
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

        return plans;
    }

    private static void requireExpected(Set<Long> expected, long execId, String what) {
        if (!expected.contains(execId)) {
            throw new CaptureLossException("Unexpected execution ID " + execId
                    + " on " + what + " — not in scenario manifest");
        }
    }

    private static void requireSealedPlan(Map<Long, CaptureRecords.PlanSealed> planSeals,
                                          long planId, String what) {
        if (!planSeals.containsKey(planId)) {
            throw new CaptureLossException(what + " belongs to plan " + planId
                    + " which has no seal in this execution");
        }
    }
}
