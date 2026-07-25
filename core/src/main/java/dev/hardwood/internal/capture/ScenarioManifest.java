/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.capture;

import java.util.Set;

/// The external expectation the harness writes *before* a capture run.
///
/// Seal loss is detectable only against an external expectation: a lost plan
/// seal or execution seal is invisible if we only inspect the records that
/// survived. The manifest enumerates the execution IDs the run is expected to
/// produce; extraction requires, per expected execution, exactly one plan
/// seal, one execution closure, and no duplicates — a missing or duplicated
/// seal rejects the artifact.
public record ScenarioManifest(Set<Long> expectedExecutionIds) {

    public ScenarioManifest {
        expectedExecutionIds = Set.copyOf(expectedExecutionIds);
    }

    /// A manifest for a single-execution scenario (the MVP's one-row-group
    /// fixture case).
    public static ScenarioManifest single(long executionId) {
        return new ScenarioManifest(Set.of(executionId));
    }
}
