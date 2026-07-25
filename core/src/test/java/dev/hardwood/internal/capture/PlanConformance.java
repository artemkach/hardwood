/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.capture;

import java.util.ArrayList;
import java.util.List;

/// Matches an independent [TracingInputFile] execution trace one-to-one
/// against a captured [StaticFetchPlan]'s final nodes.
///
/// Conformance here is measurement validity, not a correctness oracle: it
/// proves the plan being scored is the plan that ran. A node with no matching
/// read (missing), a node matched by two reads (duplicate), or a read that
/// matches a node's range twice are all conformance failures. Reads that match
/// no node are returned to the caller rather than failed outright — the trace
/// legitimately contains metadata-stage reads (footer, index buffers) that the
/// v0 data-stage plan does not model, and the caller bounds those per fixture.
public final class PlanConformance {

    private PlanConformance() {
    }

    /// The one-to-one matching result.
    ///
    /// @param unmatchedReads traced reads that matched no plan node — for the
    ///        caller to bound (metadata-stage reads live here)
    public record Result(List<TracingInputFile.TracedRead> unmatchedReads) {}

    /// Asserts every data-stage node in `plan` was executed by exactly one
    /// traced read with the exact `(offset, length)`, and returns the reads
    /// left over. Throws [AssertionError] with a descriptive message on any
    /// missing or duplicate match.
    public static Result matchOneToOne(StaticFetchPlan plan, List<TracingInputFile.TracedRead> trace) {
        List<TracingInputFile.TracedRead> remaining = new ArrayList<>(trace);
        for (StaticFetchPlan.Node node : plan.nodesSorted()) {
            TracingInputFile.TracedRead expected =
                    new TracingInputFile.TracedRead(node.offset(), node.length());
            if (!remaining.remove(expected)) {
                throw new AssertionError("Plan node " + node.nodeId() + " ["
                        + node.offset() + ", +" + node.length()
                        + ") has no matching traced read — missing execution");
            }
            if (remaining.contains(expected)) {
                throw new AssertionError("Plan node " + node.nodeId() + " ["
                        + node.offset() + ", +" + node.length()
                        + ") matched by more than one traced read — duplicate execution");
            }
        }
        return new Result(List.copyOf(remaining));
    }
}
