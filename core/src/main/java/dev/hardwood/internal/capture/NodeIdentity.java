/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.capture;

/// Stable semantic identity of one final request node, carried on the actual
/// request object ([dev.hardwood.internal.reader.ChunkHandle] /
/// [dev.hardwood.internal.reader.SharedRegion]) so the execution-side
/// `readRange()` seam can stamp its attempt with the same `nodeId` the plan
/// published.
///
/// This is the irreducible custom work the spike identified: it exists under
/// every capture mechanism. `(offset, length)` alone is ambiguous under
/// repeats, retries, and caches, so a node carries an execution-global,
/// monotonically assigned `nodeId` plus its human-readable role and stage.
///
/// The record is immutable and allocated only when capture is enabled — the
/// disabled path never constructs one (a `null` identity reference on the
/// request object).
public record NodeIdentity(long executionId, long planId, long nodeId, String stage, String role) {
}
