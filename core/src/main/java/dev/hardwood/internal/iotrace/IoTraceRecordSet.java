/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.iotrace;

import java.util.List;

/// A mechanism-neutral bundle of captured records for one recording, grouped
/// by type. Both mechanisms materialize into this shape before extraction:
/// the observer sink accumulates directly into a [IoTraceRecordSet]; the JFR
/// extractor parses a `.jfr` recording into one. [PlanExtractor] then applies
/// identical reconstruction and validation to it regardless of origin, so the
/// go/no-go properties are tested against one code path.
public record IoTraceRecordSet(
        int schemaVersion,
        boolean dataLoss,
        List<IoTraceRecords.PlanNode> planNodes,
        List<IoTraceRecords.PlanRequirement> planRequirements,
        List<IoTraceRecords.PlanEdge> planEdges,
        List<IoTraceRecords.PlanSealed> planSeals,
        List<IoTraceRecords.Request> requests,
        List<IoTraceRecords.ExecutionSealed> executionSeals) {
}
