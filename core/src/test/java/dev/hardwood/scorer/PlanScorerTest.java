/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.scorer;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Hand-computed arithmetic tests for the pure scorer (spike item d). Every
/// expected value below is derivable with a pencil from
/// `latency + ceil(bytes * 1e9 / bandwidth)` and the declared scheduling
/// policies — that verifiability is the property that makes the scorer
/// trustworthy, so the tests deliberately spell the arithmetic out.
class PlanScorerTest {

    private static final long MS = 1_000_000L;
    private static final long KIB = 1024;
    private static final long MIB = 1024 * KIB;

    /// AnyBlob's fitted same-region S3 medians: L = 30 ms, B = 50 MiB/s.
    private static final CostModel SAME_REGION_S3 =
            new CostModel(30 * MS, 50 * MIB, 1);

    // ---- single-request arithmetic ----

    @Test
    void singleRequestCostIsLatencyPlusTransfer() {
        // 50 MiB at 50 MiB/s = exactly 1 s; plus 30 ms latency.
        assertThat(SAME_REGION_S3.requestCostNanos(50 * MIB))
                .isEqualTo(30 * MS + 1_000_000_000L);
    }

    @Test
    void zeroByteRequestCostsExactlyLatency() {
        assertThat(SAME_REGION_S3.requestCostNanos(0)).isEqualTo(30 * MS);
    }

    @Test
    void transferRoundsUpToTheNextNanosecond() {
        // 1 byte at 3 bytes/s: ceil(1e9 / 3) = 333_333_334 ns — the ceiling
        // formula, not truncation (which would give ...333).
        CostModel model = new CostModel(0, 3, 1);
        assertThat(model.requestCostNanos(1)).isEqualTo(333_333_334L);
    }

    // ---- the worked example from the research doc (§A.3): fused vs split,
    // ---- two 1 MiB chunks separated by a 200 KiB gap, L = 30 ms, B = 50 MiB/s

    @Test
    void workedExampleFusedCosts74ms() {
        // fused = one request of A + gap + C = 2 MiB + 200 KiB.
        long fusedBytes = 2 * MIB + 200 * KIB;
        ScoredPlan fused = ScoredPlan.independent(fusedBytes);
        long cost = PlanScorer.cost(fused, SAME_REGION_S3.at(1));
        // 30 ms + (2 MiB + 200 KiB) / 50 MiB/s
        //   = 30 ms + ceil(2_306_048 * 1e9 / 52_428_800) ns = 30 ms + 43.984375 ms
        long expected = 30 * MS + Math.ceilDiv(fusedBytes * 1_000_000_000L, 50 * MIB);
        assertThat(cost).isEqualTo(expected);
        // ≈ 74 ms, as the doc's mental arithmetic says.
        assertThat(cost).isBetween(73 * MS, 75 * MS);
    }

    @Test
    void workedExampleSplitParallelCosts50ms() {
        // split with 2 free connections = max of two 1 MiB requests.
        ScoredPlan split = ScoredPlan.independent(MIB, MIB);
        long cost = PlanScorer.cost(split, SAME_REGION_S3.at(2));
        // 30 ms + 1 MiB / 50 MiB/s = 30 ms + 20 ms = 50 ms exactly.
        assertThat(cost).isEqualTo(50 * MS);
    }

    @Test
    void workedExampleSplitSerialCosts100ms() {
        // split serialized on one connection = two full request costs.
        ScoredPlan split = ScoredPlan.independent(MIB, MIB);
        long cost = PlanScorer.cost(split, SAME_REGION_S3.at(1));
        // 2 × (30 ms + 20 ms) = 100 ms exactly.
        assertThat(cost).isEqualTo(100 * MS);
    }

    @Test
    void workedExampleOrderingIsParallelThenFusedThenSerial() {
        long fusedBytes = 2 * MIB + 200 * KIB;
        long fused = PlanScorer.cost(ScoredPlan.independent(fusedBytes), SAME_REGION_S3.at(1));
        long parallel = PlanScorer.cost(ScoredPlan.independent(MIB, MIB), SAME_REGION_S3.at(2));
        long serial = PlanScorer.cost(ScoredPlan.independent(MIB, MIB), SAME_REGION_S3.at(1));
        // §3.1's conditional result in miniature: parallel < fused < serial.
        assertThat(parallel).isLessThan(fused);
        assertThat(fused).isLessThan(serial);
    }

    // ---- the serial break-even boundary g* = L × B: fetch dead gap bytes
    // ---- when transferring them is cheaper than a second request startup.
    // ---- With L = 30 ms and B = 50 MiB/s, g* = 0.03 s × 52_428_800 B/s
    // ---- = 1_572_864 bytes exactly.

    private static final long G_STAR = 1_572_864;

    private long fusedCost(long gap) {
        return PlanScorer.cost(ScoredPlan.independent(MIB + gap + MIB), SAME_REGION_S3.at(1));
    }

    private long splitSerialCost() {
        return PlanScorer.cost(ScoredPlan.independent(MIB, MIB), SAME_REGION_S3.at(1));
    }

    @Test
    void belowTheSerialBreakEvenFusionWins() {
        assertThat(fusedCost(G_STAR - 1)).isLessThan(splitSerialCost());
    }

    @Test
    void atTheSerialBreakEvenTheCostsAreEqual() {
        // g = L × B transfers in exactly L, so fused == split-serial to the
        // nanosecond (both sides are exact at these power-of-two rates).
        assertThat(fusedCost(G_STAR)).isEqualTo(splitSerialCost());
    }

    @Test
    void aboveTheSerialBreakEvenSplittingWins() {
        assertThat(fusedCost(G_STAR + 1)).isGreaterThan(splitSerialCost());
    }

    @Test
    void underIdealParallelismSplittingWinsAtAnyGap() {
        // The break-even is conditional on the resource model: with 2 free
        // connections the split's extra request adds no critical-path latency,
        // so splitting beats fusion even at gap 0.
        long splitParallel = PlanScorer.cost(ScoredPlan.independent(MIB, MIB), SAME_REGION_S3.at(2));
        assertThat(splitParallel).isLessThan(fusedCost(0));
    }

    // ---- §4.3's invalid-inference counterexample: endpoint rankings do not
    // ---- bound intermediate-concurrency rankings. Unit-duration nodes via a
    // ---- unit-cost model (L = 1 ns per "duration unit" would distort; use
    // ---- bytes = duration with B = 1 byte/ns equivalent).

    /// A model where a node of `bytes = d` costs exactly `d` nanoseconds:
    /// zero latency, 1e9 bytes/s = 1 byte per nanosecond.
    private static final CostModel UNIT = new CostModel(0, 1_000_000_000L, 1);

    @Test
    void endpointRankingsDoNotBoundIntermediateConcurrency() {
        // Plan X [6,6,6,2]: serial 20, ideal-parallel 6.
        // Plan Y [8,3,8,3]: serial 22, ideal-parallel 8.
        // X beats Y at both endpoints but LOSES at concurrency 2 (12 vs 11).
        ScoredPlan x = ScoredPlan.independent(6, 6, 6, 2);
        ScoredPlan y = ScoredPlan.independent(8, 3, 8, 3);

        assertThat(PlanScorer.cost(x, UNIT.at(1))).isEqualTo(20);
        assertThat(PlanScorer.cost(y, UNIT.at(1))).isEqualTo(22);
        assertThat(PlanScorer.cost(x, UNIT.at(4))).isEqualTo(6);
        assertThat(PlanScorer.cost(y, UNIT.at(4))).isEqualTo(8);

        // At concurrency 2 the ranking flips: list scheduling in stable-ID
        // order gives X = 12 and Y = 11.
        //   X: t0 start 6,6 → t6 start 6,2 → 2 done t8, 6 done t12.
        //   Y: t0 start 8,3 → t3 start 8 → t8 first 8 done, start 3 → 11.
        assertThat(PlanScorer.cost(x, UNIT.at(2))).isEqualTo(12);
        assertThat(PlanScorer.cost(y, UNIT.at(2))).isEqualTo(11);
    }

    // ---- dependencies and ties ----

    @Test
    void dependencyChainSerializesRegardlessOfConcurrency() {
        // A → B → C, each 1 ns: the chain costs 3 ns even with 3 connections.
        ScoredPlan chain = new ScoredPlan(
                List.of(new ScoredPlan.Node(1, 1), new ScoredPlan.Node(2, 1), new ScoredPlan.Node(3, 1)),
                List.of(new ScoredPlan.Edge(1, 2), new ScoredPlan.Edge(2, 3)));
        assertThat(PlanScorer.cost(chain, UNIT.at(3))).isEqualTo(3);
    }

    @Test
    void diamondDependencyJoinsAtTheSlowerBranch() {
        // A → {B(5), C(9)} → D(1): D starts when C finishes at t = 1 + 9,
        // completing at 11, with 3 connections.
        ScoredPlan diamond = new ScoredPlan(
                List.of(new ScoredPlan.Node(1, 1), new ScoredPlan.Node(2, 5),
                        new ScoredPlan.Node(3, 9), new ScoredPlan.Node(4, 1)),
                List.of(new ScoredPlan.Edge(1, 2), new ScoredPlan.Edge(1, 3),
                        new ScoredPlan.Edge(2, 4), new ScoredPlan.Edge(3, 4)));
        assertThat(PlanScorer.cost(diamond, UNIT.at(3))).isEqualTo(11);
    }

    @Test
    void simultaneousCompletionsAreBatchedBeforeNewStarts() {
        // Two 4-ns nodes complete at the same instant; their successors (1 ns
        // each) then both start at t = 4 with concurrency 2 → total 5, not 6.
        ScoredPlan plan = new ScoredPlan(
                List.of(new ScoredPlan.Node(1, 4), new ScoredPlan.Node(2, 4),
                        new ScoredPlan.Node(3, 1), new ScoredPlan.Node(4, 1)),
                List.of(new ScoredPlan.Edge(1, 3), new ScoredPlan.Edge(2, 4)));
        assertThat(PlanScorer.cost(plan, UNIT.at(2))).isEqualTo(5);
    }

    @Test
    void emptyPlanCostsZero() {
        assertThat(PlanScorer.cost(ScoredPlan.independent(), UNIT.at(1))).isEqualTo(0);
    }

    // ---- validation: fail early, never a silently wrong number ----

    @Test
    void cyclicPlanIsRejected() {
        assertThatThrownBy(() -> new ScoredPlan(
                List.of(new ScoredPlan.Node(1, 1), new ScoredPlan.Node(2, 1)),
                List.of(new ScoredPlan.Edge(1, 2), new ScoredPlan.Edge(2, 1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cycle");
    }

    @Test
    void selfEdgeIsRejected() {
        assertThatThrownBy(() -> new ScoredPlan(
                List.of(new ScoredPlan.Node(1, 1)),
                List.of(new ScoredPlan.Edge(1, 1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Self-edge");
    }

    @Test
    void danglingEdgeIsRejected() {
        assertThatThrownBy(() -> new ScoredPlan(
                List.of(new ScoredPlan.Node(1, 1)),
                List.of(new ScoredPlan.Edge(1, 99))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown node");
    }

    @Test
    void duplicateNodeIdIsRejected() {
        assertThatThrownBy(() -> new ScoredPlan(
                List.of(new ScoredPlan.Node(1, 1), new ScoredPlan.Node(1, 2)),
                List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate node ID");
    }

    @Test
    void invalidModelParametersAreRejected() {
        assertThatThrownBy(() -> new CostModel(-1, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CostModel(0, 0, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CostModel(0, 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void overflowingClockFailsLoudly() {
        // A request whose transfer alone exceeds Long.MAX_VALUE nanoseconds
        // must throw, not wrap around into a small positive score.
        CostModel slow = new CostModel(0, 1, 1);   // 1 byte/s
        assertThatThrownBy(() -> PlanScorer.cost(
                ScoredPlan.independent(Long.MAX_VALUE / 500_000_000L), slow))
                .isInstanceOf(ArithmeticException.class);
    }
}
