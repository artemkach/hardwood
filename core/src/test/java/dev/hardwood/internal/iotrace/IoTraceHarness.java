/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.iotrace;

import java.io.IOException;

import dev.hardwood.InputFile;
import dev.hardwood.reader.FilterPredicate;
import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.reader.RowReader;
import dev.hardwood.schema.ColumnProjection;

/// Test-side driver that installs a [IoTraceContext], builds a reader
/// synchronously (so the [dev.hardwood.internal.reader.RowGroupIterator]
/// consumes the pending context on the build thread), reads every row, and
/// returns the validated [StaticFetchPlan].
///
/// It exercises the same install → build → read → seal → extract path both
/// mechanisms share; only the [IoTraceSink] differs between the observer and
/// JFR variants, which is exactly the comparison the spike calls for.
final class IoTraceHarness {

    private IoTraceHarness() {
    }

    /// A capture run's two artifacts: the reconstructed plan and the
    /// independent execution trace at the `InputFile` seam.
    record Captured(StaticFetchPlan plan, TracingInputFile trace) {}

    /// Runs one capture with the observer mechanism and returns the extracted,
    /// validated plan. `executionId` must match the scenario manifest.
    static StaticFetchPlan captureWithObserver(InputFile file, ColumnProjection projection,
                                               long executionId) throws IOException {
        ObserverIoTraceSink sink = new ObserverIoTraceSink();
        IoTraceContext context = IoTraceContext.start(executionId, sink);
        readUnderCapture(file, projection, context);
        return PlanExtractor.extractSingle(sink.toRecordSet(), ScenarioManifest.single(executionId));
    }

    /// Like [#captureWithObserver] but wraps the file in a [TracingInputFile]
    /// so the caller can match the plan against the independent trace.
    static Captured captureWithTrace(InputFile file, ColumnProjection projection,
                                     long executionId) throws IOException {
        TracingInputFile traced = new TracingInputFile(file);
        ObserverIoTraceSink sink = new ObserverIoTraceSink();
        IoTraceContext context = IoTraceContext.start(executionId, sink);
        readUnderCapture(traced, projection, context);
        StaticFetchPlan plan = PlanExtractor.extractSingle(
                sink.toRecordSet(), ScenarioManifest.single(executionId));
        return new Captured(plan, traced);
    }

    /// Runs one filtered capture with the observer mechanism. Used by the
    /// incomplete-plan test: a page-dropping filter makes a column's first
    /// read non-static, which must seal the plan `INCOMPLETE`.
    static StaticFetchPlan captureFilteredWithObserver(InputFile file, ColumnProjection projection,
                                                       FilterPredicate filter, long executionId)
            throws IOException {
        ObserverIoTraceSink sink = new ObserverIoTraceSink();
        IoTraceContext context = IoTraceContext.start(executionId, sink);
        file.open();
        try (IoTraceControl.Scope ignored = IoTraceControl.install(context);
             ParquetFileReader reader = ParquetFileReader.open(file);
             RowReader rows = reader.buildRowReader()
                     .projection(projection).filter(filter).build()) {
            while (rows.hasNext()) {
                rows.next();
            }
        }
        return PlanExtractor.extractSingle(sink.toRecordSet(), ScenarioManifest.single(executionId));
    }

    /// Runs one capture against an arbitrary sink and returns nothing — used by
    /// the JFR test, which extracts from the dumped recording instead.
    static void readUnderCapture(InputFile file, ColumnProjection projection,
                                 IoTraceContext context) throws IOException {
        file.open();
        try (IoTraceControl.Scope ignored = IoTraceControl.install(context);
             ParquetFileReader reader = ParquetFileReader.open(file)) {
            RowReader rows = projection == null
                    ? reader.rowReader()
                    : reader.buildRowReader().projection(projection).build();
            try (rows) {
                while (rows.hasNext()) {
                    rows.next();
                }
            }
        }
    }
}
