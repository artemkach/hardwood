/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.scorer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/// A static data-fetch plan as the scorer sees it: request nodes with byte
/// sizes and scheduling dependencies. Deliberately decoupled from Hardwood —
/// the scorer is a pure function and its inputs are plain data, so every
/// score is verifiable with a pencil (spike item d: "pure, zero Hardwood
/// coupling").
///
/// Construction validates the graph shape: duplicate node IDs, edges naming
/// unknown nodes, self-edges, and cycles are rejected eagerly — a defective
/// plan must fail loudly, never produce a number.
public final class ScoredPlan {

    /// One request node: `bytes` transferred when the node executes.
    public record Node(long id, long bytes) {}

    /// `from` must complete before `to` may start.
    public record Edge(long fromId, long toId) {}

    private final Map<Long, Node> nodesById = new TreeMap<>();
    private final Map<Long, Set<Long>> successors = new TreeMap<>();
    private final Map<Long, Integer> dependencyCounts = new TreeMap<>();

    public ScoredPlan(List<Node> nodes, List<Edge> edges) {
        for (Node node : nodes) {
            if (node.bytes() < 0) {
                throw new IllegalArgumentException(
                        "Node " + node.id() + " has negative bytes: " + node.bytes());
            }
            if (nodesById.putIfAbsent(node.id(), node) != null) {
                throw new IllegalArgumentException("Duplicate node ID: " + node.id());
            }
            successors.put(node.id(), new TreeSet<>());
            dependencyCounts.put(node.id(), 0);
        }
        for (Edge edge : edges) {
            if (!nodesById.containsKey(edge.fromId()) || !nodesById.containsKey(edge.toId())) {
                throw new IllegalArgumentException("Edge " + edge.fromId() + " -> " + edge.toId()
                        + " references an unknown node");
            }
            if (edge.fromId() == edge.toId()) {
                throw new IllegalArgumentException("Self-edge on node " + edge.fromId());
            }
            if (successors.get(edge.fromId()).add(edge.toId())) {
                dependencyCounts.merge(edge.toId(), 1, Integer::sum);
            }
        }
        rejectCycles();
    }

    /// Convenience: independent nodes only (the v0 shape — edge-free graphs).
    public static ScoredPlan independent(long... bytesPerNode) {
        List<Node> nodes = new ArrayList<>(bytesPerNode.length);
        for (int i = 0; i < bytesPerNode.length; i++) {
            nodes.add(new Node(i + 1, bytesPerNode[i]));
        }
        return new ScoredPlan(nodes, List.of());
    }

    public Collection<Node> nodes() {
        return nodesById.values();
    }

    public Set<Long> successorsOf(long nodeId) {
        return successors.get(nodeId);
    }

    public Node node(long nodeId) {
        return nodesById.get(nodeId);
    }

    /// A fresh mutable copy of the per-node unmet-dependency counts.
    public Map<Long, Integer> dependencyCounts() {
        return new TreeMap<>(dependencyCounts);
    }

    /// Kahn's algorithm: if a topological order does not cover every node, the
    /// remainder is cyclic (or unreachable through a cycle) and the plan is
    /// invalid.
    private void rejectCycles() {
        Map<Long, Integer> counts = dependencyCounts();
        ArrayDeque<Long> ready = new ArrayDeque<>();
        counts.forEach((id, count) -> {
            if (count == 0) {
                ready.add(id);
            }
        });
        int visited = 0;
        while (!ready.isEmpty()) {
            long id = ready.poll();
            visited++;
            for (long succ : successors.get(id)) {
                if (counts.merge(succ, -1, Integer::sum) == 0) {
                    ready.add(succ);
                }
            }
        }
        if (visited != nodesById.size()) {
            throw new IllegalArgumentException(
                    "Plan contains a cycle or a node unreachable through one ("
                            + (nodesById.size() - visited) + " node(s) never became ready)");
        }
    }
}
