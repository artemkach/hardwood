/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.scorer;

import java.util.Comparator;
import java.util.Map;
import java.util.PriorityQueue;

/// Deterministic discrete-event scorer: `(plan, model) → modeled completion
/// time in nanoseconds`. No threads, no sleeping, no Hardwood types — a pure
/// function whose every result is verifiable with a pencil (spike item d).
///
/// The loop is: *start whatever is ready while capacity remains; when stuck,
/// jump the clock to the next completion; process all completions at that
/// instant; repeat.* Readiness is tracked with dependency counts and a ready
/// queue. Nothing about observed execution order constrains the schedule.
///
/// Declared modeled policies (arbitrary, fixed — not claims about Hardwood's
/// scheduler):
///
/// - **ready-queue order**: ascending stable node ID;
/// - **tie treatment**: all requests completing at the same instant are
///   processed before any new starts, and their newly readied successors
///   enter the queue in ID order.
///
/// The clock is a `long` in nanoseconds with overflow-checked addition. The
/// scorer asserts every node completed — a plan whose nodes cannot all run
/// (impossible here after [ScoredPlan]'s cycle rejection, but asserted anyway)
/// fails loudly.
///
/// The supported claims are model-relative: "under these declared assumptions,
/// plan X completes before plan Y." Serial and ideal-parallel evaluations
/// bound each plan's completion time but NOT the ranking between plans at
/// intermediate concurrency — intermediate values must be evaluated
/// explicitly (see the counterexample in [PlanScorerTest]).
public final class PlanScorer {

    private PlanScorer() {
    }

    private record InFlight(ScoredPlan.Node node, long finishTime) {}

    /// Computes the modeled completion time of `plan` under `model`.
    public static long cost(ScoredPlan plan, CostModel model) {
        long clock = 0;
        Map<Long, Integer> unmetDeps = plan.dependencyCounts();
        PriorityQueue<ScoredPlan.Node> ready =                  // fixed modeled policy:
                new PriorityQueue<>(Comparator.comparingLong(ScoredPlan.Node::id));
        for (ScoredPlan.Node node : plan.nodes()) {
            if (unmetDeps.get(node.id()) == 0) {
                ready.add(node);
            }
        }
        PriorityQueue<InFlight> inFlight = new PriorityQueue<>(
                Comparator.comparingLong(InFlight::finishTime)
                        .thenComparingLong(f -> f.node().id()));

        int completed = 0;
        while (!ready.isEmpty() || !inFlight.isEmpty()) {
            while (!ready.isEmpty() && inFlight.size() < model.maxConcurrency()) {
                ScoredPlan.Node node = ready.poll();
                inFlight.add(new InFlight(node,
                        Math.addExact(clock, model.requestCostNanos(node.bytes()))));
            }
            long t = inFlight.peek().finishTime();
            clock = t;
            while (!inFlight.isEmpty() && inFlight.peek().finishTime() == t) {  // batch
                ScoredPlan.Node done = inFlight.poll().node();
                completed++;
                for (long succId : plan.successorsOf(done.id())) {
                    if (unmetDeps.merge(succId, -1, Integer::sum) == 0) {
                        ready.add(plan.node(succId));
                    }
                }
            }
        }
        if (completed != plan.nodes().size()) {
            throw new IllegalStateException("Scorer bug: " + completed + " of "
                    + plan.nodes().size() + " nodes completed");
        }
        return clock;
    }
}
