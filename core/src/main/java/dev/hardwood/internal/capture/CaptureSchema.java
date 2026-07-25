/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.capture;

/// Versioned string constants for the causal capture schema.
///
/// The schema carries only flat primitives and these interned strings so it
/// is expressible under both the JFR sink and the observer sink. Values are
/// contracts, not display labels — extraction compares them exactly, so they
/// must not drift without a schema-version bump.
public final class CaptureSchema {

    private CaptureSchema() {
    }

    /// Schema version. Extraction rejects a recording produced by a different
    /// version rather than silently reconstructing a mismatched shape.
    public static final int VERSION = 1;

    // ---- node / request stages ----

    /// Data-stage read (column chunk bytes). The only stage in the v0 plan.
    public static final String STAGE_DATA = "DATA";

    // ---- plan-seal status ----

    /// Every projected first read was statically known and captured — the
    /// plan is a complete v0 static data-fetch plan.
    public static final String STATUS_SUPPORTED = "SUPPORTED";

    /// At least one projected column's first read was not statically known
    /// (page drops, lazy sequential discovery, filter/mask truncation) — the
    /// plan is exported with this marker rather than as a complete DAG.
    public static final String STATUS_INCOMPLETE = "INCOMPLETE";

    // ---- request outcome ----

    public static final String OUTCOME_SUCCESS = "SUCCESS";
    public static final String OUTCOME_FAILURE = "FAILURE";

    // ---- execution terminal outcome ----

    public static final String TERMINAL_COMPLETE = "COMPLETE";
    public static final String TERMINAL_ABORTED = "ABORTED";

    // ---- edge kinds (reserved; v0 graphs are edge-free) ----

    public static final String EDGE_SCHEDULING = "SCHEDULING";

    // ---- requirement membership (documented; carried per-record) ----

    /// A requirement names the column whose first read it represents.
    public static final String ROLE_COLUMN_FIRST_READ = "COLUMN_FIRST_READ";
}
