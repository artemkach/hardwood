/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.iotrace;

import java.util.ArrayList;
import java.util.List;

/// Matches an independent [TracingInputFile] execution trace one-to-one
/// against captured [StaticFetchPlan] final nodes.
///
/// Conformance here is measurement validity, not a correctness oracle: it
/// proves the plan being scored (or displayed) is the plan that ran. A node
/// with no matching read is *missing*; a node matched by more than one read
/// is a *duplicate*. Reads that match no node are returned as *unmatched*
/// rather than failed outright — the trace legitimately contains
/// metadata-stage reads (footer, index buffers) that the v0 data-stage plan
/// does not model, and the caller bounds or displays those per fixture.
public final class FetchPlanConformance {

    private FetchPlanConformance() {
    }

    /// The one-to-one matching result. Conformant iff `missing` and
    /// `duplicated` are empty; `unmatchedReads` are for the caller to bound
    /// or label (metadata-stage reads live here).
    public record Result(
            List<MatchedNode> matched,
            List<StaticFetchPlan.Node> missing,
            List<StaticFetchPlan.Node> duplicated,
            List<TracingInputFile.TracedRead> unmatchedReads) {

        public boolean isConformant() {
            return missing.isEmpty() && duplicated.isEmpty();
        }
    }

    /// A plan node paired with the traced read that executed it.
    public record MatchedNode(StaticFetchPlan.Node node, TracingInputFile.TracedRead read) {}

    /// Matches every data-stage node of every given plan against the trace:
    /// each node must be executed by exactly one traced read with the exact
    /// `(offset, length)`. Never throws — defects are reported in the result
    /// so callers can render or assert as they choose.
    public static Result match(List<StaticFetchPlan> plans, List<TracingInputFile.TracedRead> trace) {
        List<TracingInputFile.TracedRead> remaining = new ArrayList<>(trace);
        List<MatchedNode> matched = new ArrayList<>();
        List<StaticFetchPlan.Node> missing = new ArrayList<>();
        List<StaticFetchPlan.Node> duplicated = new ArrayList<>();

        for (StaticFetchPlan plan : plans) {
            for (StaticFetchPlan.Node node : plan.nodesSorted()) {
                // Structural matching only: covers() compares (offset, length)
                // and deliberately ignores the read's timing fields.
                TracingInputFile.TracedRead read =
                        removeCovering(remaining, node.offset(), node.length());
                if (read == null) {
                    missing.add(node);
                    continue;
                }
                matched.add(new MatchedNode(node, read));
                if (removeCovering(remaining, node.offset(), node.length()) != null) {
                    duplicated.add(node);
                }
            }
        }
        return new Result(List.copyOf(matched), List.copyOf(missing),
                List.copyOf(duplicated), List.copyOf(remaining));
    }

    /// Single-plan convenience over [#match(List, List)].
    public static Result match(StaticFetchPlan plan, List<TracingInputFile.TracedRead> trace) {
        return match(List.of(plan), trace);
    }

    /// Removes and returns the first read covering exactly `[offset,
    /// offset+length)`, or `null` if none remains.
    private static TracingInputFile.TracedRead removeCovering(
            List<TracingInputFile.TracedRead> remaining, long offset, int length) {
        for (int i = 0; i < remaining.size(); i++) {
            if (remaining.get(i).covers(offset, length)) {
                return remaining.remove(i);
            }
        }
        return null;
    }
}
