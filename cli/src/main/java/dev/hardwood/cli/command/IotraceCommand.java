/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.cli.command;

import java.io.IOException;
import java.util.List;

import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.invocation.CommandInvocation;
import org.aesh.command.option.Mixin;
import org.aesh.command.option.Option;

import dev.hardwood.InputFile;
import dev.hardwood.internal.iotrace.CaptureContext;
import dev.hardwood.internal.iotrace.CaptureControl;
import dev.hardwood.internal.iotrace.ObserverCaptureSink;
import dev.hardwood.internal.iotrace.PlanConformance;
import dev.hardwood.internal.iotrace.PlanExtractor;
import dev.hardwood.internal.iotrace.ScenarioManifest;
import dev.hardwood.internal.iotrace.StaticFetchPlan;
import dev.hardwood.internal.iotrace.TracingInputFile;
import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.reader.RowReader;
import dev.hardwood.schema.ColumnProjection;

/// Runs the real read pipeline against a file, discards the decoded values,
/// and renders what the planner decided (the captured fetch plans) next to
/// what actually crossed the `InputFile` seam (the trace), with a
/// plan-vs-execution conformance verdict.
///
/// The values are read and thrown away on purpose: the planner's fetch
/// behavior (coalescing, prefetch, chunking) only materializes during a real
/// read, so the command pays for a full decode to observe a faithful plan.
@CommandDefinition(name = "iotrace", description = "Trace the I/O plan and reads performed to read a file.", generateHelp = true)
public class IotraceCommand implements Command<CommandInvocation> {

    /// Execution ID for the CLI's single capture. Arbitrary but fixed —
    /// each invocation is one process with one execution.
    private static final long EXECUTION_ID = 1L;

    private static final String GAP_PROPERTY = "hardwood.internal.maxCrossColGapBytes";

    @Mixin
    FileMixin fileMixin;

    @Option(shortName = 'c', name = "columns", description = "Comma-separated list of columns to include. Supports nested fields via dot notation (e.g. 'account.id').")
    String columns;

    @Option(shortName = 'n', name = "rows", defaultValue = RowLimits.ALL, description = "Row limit; positive = head(N), negative = tail(N), 'ALL' = every row. Truncated plans render as INCOMPLETE.")
    String rows;

    @Option(name = "max-gap", description = "Cross-column coalescing gap override in bytes (-1 forces fully split plans). Default: the planner's 64 KiB.")
    Integer maxGap;

    @Option(name = "trace-only", hasValue = false, description = "Skip plan capture and conformance; show only the seam trace.")
    boolean traceOnly;

    @Override
    public CommandResult execute(CommandInvocation ci) {
        if (fileMixin.isRemoteUri()) {
            return CommandResult.FAILURE;
        }
        InputFile inputFile = fileMixin.toInputFile();
        if (inputFile == null) {
            return CommandResult.FAILURE;
        }

        int rowLimit;
        try {
            rowLimit = RowLimits.parse(rows);
        }
        catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            return CommandResult.FAILURE;
        }

        String previousGap = maxGap != null
                ? System.setProperty(GAP_PROPERTY, Integer.toString(maxGap)) : null;
        try {
            return run(inputFile, rowLimit);
        }
        catch (IOException e) {
            System.err.println("Error reading file: " + e.getMessage());
            return CommandResult.FAILURE;
        }
        finally {
            if (maxGap != null) {
                if (previousGap == null) {
                    System.clearProperty(GAP_PROPERTY);
                }
                else {
                    System.setProperty(GAP_PROPERTY, previousGap);
                }
            }
        }
    }

    private CommandResult run(InputFile inputFile, int rowLimit) throws IOException {
        TracingInputFile traced = new TracingInputFile(inputFile);
        ObserverCaptureSink sink = new ObserverCaptureSink();
        CaptureContext context = CaptureContext.start(EXECUTION_ID, sink);

        traced.open();
        try (CaptureControl.Scope ignored = CaptureControl.install(context);
             ParquetFileReader reader = ParquetFileReader.open(traced);
             RowReader rowReader = RowLimits.buildRowReader(reader, parseProjection(), rowLimit)) {
            while (rowReader.hasNext()) {
                rowReader.next();
            }
        }

        boolean remote = fileMixin.file.startsWith("s3://");
        if (traceOnly) {
            System.out.print(IotraceRenderer.renderTrace(traced.reads(),
                    PlanConformance.match(List.of(), traced.reads()), remote));
            return CommandResult.SUCCESS;
        }

        List<StaticFetchPlan> plans = IotraceRenderer.displayOrder(PlanExtractor.extract(
                sink.toRecordSet(), ScenarioManifest.singleUnknownPlans(EXECUTION_ID)));

        for (StaticFetchPlan plan : plans) {
            System.out.print(IotraceRenderer.renderPlan(plan));
            System.out.println();
        }

        PlanConformance.Result conformance = PlanConformance.match(plans, traced.reads());
        System.out.print(IotraceRenderer.renderTrace(traced.reads(), conformance, remote));
        System.out.println();
        System.out.print(IotraceRenderer.renderSummary(EXECUTION_ID, plans, effectiveGap()));

        return conformance.isConformant() ? CommandResult.SUCCESS : CommandResult.FAILURE;
    }

    private int effectiveGap() {
        return maxGap != null ? maxGap : 64 * 1024;
    }

    private ColumnProjection parseProjection() {
        if (columns == null) {
            return ColumnProjection.all();
        }
        String[] names = columns.split(",");
        for (int i = 0; i < names.length; i++) {
            names[i] = names[i].trim();
        }
        return ColumnProjection.columns(names);
    }
}
