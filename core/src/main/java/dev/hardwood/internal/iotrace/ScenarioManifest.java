/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.iotrace;

import java.util.Set;

/// The external expectation the harness writes *before* a capture run.
///
/// Seal loss is detectable only against an external expectation: a lost plan
/// seal or execution seal is invisible if we only inspect the records that
/// survived. The manifest enumerates the execution IDs the run is expected to
/// produce; extraction requires, per expected execution, exactly one
/// execution closure and per-plan seal uniqueness — a missing or duplicated
/// seal rejects the artifact.
///
/// The expected *plan count* per execution has two strictness levels:
///
/// - `expectedPlanCount > 0` — the harness knows how many plans the run
///   produces (e.g. one per row group of a known fixture); extraction
///   rejects any other count. This is the stronger contract and the default
///   for experiment harnesses.
/// - `expectedPlanCount == 0` (unknown) — the caller cannot know the count
///   without reading the footer first (the `iotrace` CLI case, where
///   requiring a predeclared count would force reading the file twice);
///   extraction accepts any non-zero plan count. A *vanished individual
///   plan* is then not detectable from the manifest — but each surviving
///   plan's records are still fully seal-checked, and the execution seal
///   still closes the request stream, so a plan whose requests survived but
///   whose seal was lost is caught by the unknown-request check.
public record ScenarioManifest(Set<Long> expectedExecutionIds, int expectedPlanCount) {

    public ScenarioManifest {
        expectedExecutionIds = Set.copyOf(expectedExecutionIds);
        if (expectedPlanCount < 0) {
            throw new IllegalArgumentException(
                    "expectedPlanCount must be non-negative (0 = unknown), got " + expectedPlanCount);
        }
    }

    /// A manifest for a single-execution, single-plan scenario (the MVP's
    /// one-row-group fixture case).
    public static ScenarioManifest single(long executionId) {
        return new ScenarioManifest(Set.of(executionId), 1);
    }

    /// A manifest for a single execution with an unknown number of plans
    /// (one per row group, count not known before the footer is read).
    public static ScenarioManifest singleUnknownPlans(long executionId) {
        return new ScenarioManifest(Set.of(executionId), 0);
    }

    /// Whether the plan count per execution is predeclared.
    public boolean planCountKnown() {
        return expectedPlanCount > 0;
    }
}
