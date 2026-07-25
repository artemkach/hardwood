/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.capture;

import java.io.IOException;

import dev.hardwood.InputFile;
import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.reader.RowReader;
import dev.hardwood.schema.ColumnProjection;

/// Test-side driver that installs a [CaptureContext], builds a reader
/// synchronously (so the [dev.hardwood.internal.reader.RowGroupIterator]
/// consumes the pending context on the build thread), reads every row, and
/// returns the validated [StaticFetchPlan].
///
/// It exercises the same install → build → read → seal → extract path both
/// mechanisms share; only the [CaptureSink] differs between the observer and
/// JFR variants, which is exactly the comparison the spike calls for.
final class CaptureHarness {

    private CaptureHarness() {
    }

    /// Runs one capture with the observer mechanism and returns the extracted,
    /// validated plan. `executionId` must match the scenario manifest.
    static StaticFetchPlan captureWithObserver(InputFile file, ColumnProjection projection,
                                               long executionId) throws IOException {
        ObserverCaptureSink sink = new ObserverCaptureSink();
        CaptureContext context = CaptureContext.start(executionId, sink);
        readUnderCapture(file, projection, context);
        return PlanExtractor.extractSingle(sink.toRecordSet(), ScenarioManifest.single(executionId));
    }

    /// Runs one capture against an arbitrary sink and returns nothing — used by
    /// the JFR test, which extracts from the dumped recording instead.
    static void readUnderCapture(InputFile file, ColumnProjection projection,
                                 CaptureContext context) throws IOException {
        file.open();
        try (CaptureControl.Scope ignored = CaptureControl.install(context);
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
