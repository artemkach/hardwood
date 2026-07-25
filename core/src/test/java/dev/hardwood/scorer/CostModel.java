/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.scorer;

/// A declared storage cost model: fixed per-request latency, per-connection
/// bandwidth, and a concurrency cap. Aggregate bandwidth is deliberately
/// unlimited — its absence must be stated in every result derived from this
/// model, because fusion-vs-parallelism conclusions change with it.
///
/// The request cost formula is the exact checked ceiling arithmetic the design
/// prescribes:
///
/// ```
/// requestCostNanos = latencyNanos + ceil(bytes * 1_000_000_000 / bytesPerSecond)
/// ```
///
/// All time is a `long` in nanoseconds. Multiplication is overflow-checked;
/// `bytesPerSecond <= 0`, `latencyNanos < 0`, and `maxConcurrency < 1` are
/// rejected at construction — the model fails early rather than producing a
/// silently wrong score.
public record CostModel(long latencyNanos, long bytesPerSecond, int maxConcurrency) {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    public CostModel {
        if (latencyNanos < 0) {
            throw new IllegalArgumentException("latencyNanos must be non-negative, got " + latencyNanos);
        }
        if (bytesPerSecond <= 0) {
            throw new IllegalArgumentException("bytesPerSecond must be positive, got " + bytesPerSecond);
        }
        if (maxConcurrency < 1) {
            throw new IllegalArgumentException("maxConcurrency must be at least 1, got " + maxConcurrency);
        }
    }

    /// The same profile at a different concurrency — the sweep axis.
    public CostModel at(int concurrency) {
        return new CostModel(latencyNanos, bytesPerSecond, concurrency);
    }

    /// Modeled duration of one request transferring `bytes`.
    public long requestCostNanos(long bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("bytes must be non-negative, got " + bytes);
        }
        long transfer = Math.ceilDiv(Math.multiplyExact(bytes, NANOS_PER_SECOND), bytesPerSecond);
        return Math.addExact(latencyNanos, transfer);
    }
}
