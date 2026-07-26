/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.iotrace;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/// The reconstructed, validated static data-fetch plan for one execution.
///
/// This is the canonical scorer artifact the spike names: extraction from
/// either mechanism (JFR recording or observer buffer) produces the same
/// [StaticFetchPlan], and the raw recording (if any) is retained only as
/// supporting evidence. The plan is what downstream scoring consumes; the
/// recording is transport.
///
/// v0 shape: one or more final [Node]s (a fused node serves several columns),
/// each carrying its member [Requirement]s (first reads materialized into it),
/// plus scheduling [Edge]s (edge-free in v0). `status` records whether the
/// plan is a complete static plan (`SUPPORTED`) or was exported incomplete.
public record StaticFetchPlan(
        long executionId,
        long planId,
        List<Node> nodes,
        List<Requirement> requirements,
        List<Edge> edges,
        String status,
        String reason,
        String planHash) {

    /// A final request node: one `readRange` serving `[offset, offset+length)`.
    public record Node(long nodeId, long offset, int length, String stage, String role) {}

    /// A first-read requirement materialized into `requestNodeId`.
    public record Requirement(long requirementId, long requestNodeId, long offset,
                              int length, String columnRole) {}

    /// A scheduling dependency edge (reserved; v0 graphs are edge-free).
    public record Edge(long fromNodeId, long toNodeId, String edgeKind) {}

    /// Returns the number of data-stage final nodes.
    public int dataStageNodeCount() {
        int count = 0;
        for (Node n : nodes) {
            if (IoTraceSchema.STAGE_DATA.equals(n.stage())) {
                count++;
            }
        }
        return count;
    }

    /// Returns the node with the given ID, or `null` if absent.
    public Node node(long nodeId) {
        for (Node n : nodes) {
            if (n.nodeId() == nodeId) {
                return n;
            }
        }
        return null;
    }

    /// Returns the set of useful bytes across all requirements (their first
    /// reads), as a total — the denominator for over-fetch accounting. Because
    /// v0 requirements never overlap within a plan, a plain sum is exact.
    public long usefulBytes() {
        long total = 0;
        for (Requirement r : requirements) {
            total += r.length();
        }
        return total;
    }

    /// Returns the total bytes the plan's final nodes fetch. For a fused node
    /// this includes any dead gap between the member subranges.
    public long fetchedBytes() {
        long total = 0;
        for (Node n : nodes) {
            total += n.length();
        }
        return total;
    }

    /// Whether this plan was sealed as a complete v0 static plan.
    public boolean isSupported() {
        return IoTraceSchema.STATUS_SUPPORTED.equals(status);
    }

    /// Returns the nodes sorted by ID (canonical order for serialization).
    public List<Node> nodesSorted() {
        List<Node> sorted = new ArrayList<>(nodes);
        sorted.sort(Comparator.comparingLong(Node::nodeId));
        return sorted;
    }
}
