/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.cli.command;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import dev.hardwood.cli.internal.Sizes;
import dev.hardwood.cli.internal.table.RowTable;
import dev.hardwood.internal.iotrace.FetchPlanConformance;
import dev.hardwood.internal.iotrace.IoTraceSchema;
import dev.hardwood.internal.iotrace.StaticFetchPlan;
import dev.hardwood.internal.iotrace.TracingInputFile;

/// Renders captured [StaticFetchPlan]s and the correlated seam trace as
/// plain text: a byte-layout map per plan (the visual anchor), the
/// arrival-ordered trace with each read matched to its node, and a summary
/// block. Pure string building over immutable inputs, so it is unit-testable
/// against hand-built plans without touching a file.
final class IoTraceRenderer {

    /// Width of the layout bar in characters.
    private static final int BAR_WIDTH = 64;

    private static final char FILL_USEFUL = '█';   // █ bytes some requirement needs
    private static final char FILL_DEAD = '░';     // ░ dead gap bytes inside a fused node
    private static final char FILL_UNPLANNED = ' ';     // bytes no node fetches

    private IoTraceRenderer() {
    }

    /// Renders one plan: header, layout map, node table.
    static String renderPlan(StaticFetchPlan plan) {
        StringBuilder sb = new StringBuilder();
        List<StaticFetchPlan.Node> nodes = plan.nodesSorted();

        sb.append("Plan ").append(plan.planId());
        if (!plan.isSupported()) {
            sb.append("  [INCOMPLETE: ").append(plan.reason()).append(']');
        }
        sb.append('\n');

        if (nodes.isEmpty()) {
            sb.append("  (no data-stage nodes published)\n");
            return sb.toString();
        }

        long extentStart = nodes.get(0).offset();
        long extentEnd = nodes.get(nodes.size() - 1).offset() + nodes.get(nodes.size() - 1).length();
        sb.append("  bytes [").append(extentStart).append(" .. ").append(extentEnd)
                .append(")  (").append(Sizes.format(extentEnd - extentStart)).append(")\n");

        sb.append("  ").append(renderBar(plan, extentStart, extentEnd)).append('\n');

        String[] headers = {"Node", "Stage", "Range", "Length", "Serves"};
        List<String[]> rows = new ArrayList<>();
        for (StaticFetchPlan.Node node : nodes) {
            long reqs = plan.requirements().stream()
                    .filter(r -> r.requestNodeId() == node.nodeId()).count();
            String serves = reqs == 1 ? node.role()
                    : reqs + " requirements (fused)";
            rows.add(new String[]{
                    Long.toString(node.nodeId()),
                    node.stage(),
                    "[" + node.offset() + " .. " + (node.offset() + node.length()) + ")",
                    Sizes.format(node.length()),
                    serves
            });
        }
        sb.append(indent(RowTable.renderTable(headers, rows))).append('\n');
        return sb.toString();
    }

    /// Renders the layout bar: each position maps to a byte range of the
    /// plan's extent; `█` where a requirement needs the byte, `░` where a
    /// node fetches dead bytes, space where nothing is fetched.
    static String renderBar(StaticFetchPlan plan, long extentStart, long extentEnd) {
        long extent = extentEnd - extentStart;
        if (extent <= 0) {
            return "";
        }
        char[] bar = new char[BAR_WIDTH];
        for (int i = 0; i < BAR_WIDTH; i++) {
            long posStart = extentStart + extent * i / BAR_WIDTH;
            long posEnd = extentStart + extent * (i + 1) / BAR_WIDTH;
            if (posEnd == posStart) {
                posEnd = posStart + 1;
            }
            bar[i] = classify(plan, posStart, posEnd);
        }
        return new String(bar);
    }

    /// Classification for one bar cell covering `[start, end)`: useful wins
    /// over dead wins over unplanned, so narrow requirements stay visible.
    private static char classify(StaticFetchPlan plan, long start, long end) {
        boolean fetched = false;
        for (StaticFetchPlan.Requirement req : plan.requirements()) {
            if (req.offset() < end && start < req.offset() + req.length()) {
                return FILL_USEFUL;
            }
        }
        for (StaticFetchPlan.Node node : plan.nodes()) {
            if (node.offset() < end && start < node.offset() + node.length()) {
                fetched = true;
                break;
            }
        }
        return fetched ? FILL_DEAD : FILL_UNPLANNED;
    }

    /// Renders the arrival-ordered trace with node correlation, timing
    /// statistics, and the conformance verdict.
    static String renderTrace(List<TracingInputFile.TracedRead> trace,
                              FetchPlanConformance.Result conformance) {
        StringBuilder sb = new StringBuilder();
        sb.append("Trace (").append(trace.size()).append(" reads at the InputFile seam, invocation order)\n");

        // Begin times are shown relative to the first read (t+0). Concurrent
        // reads (prefetch) overlap: a read may begin before the previous one
        // ends — that overlap is the I/O-decode pipelining, visible here.
        long t0 = trace.isEmpty() ? 0 : trace.get(0).beginNanos();

        String[] headers = {"#", "Begin", "Duration", "Offset", "Length", "Matched"};
        List<String[]> rows = new ArrayList<>();
        int i = 1;
        for (TracingInputFile.TracedRead read : trace) {
            rows.add(new String[]{
                    Integer.toString(i++),
                    "t+" + formatNanos(read.beginNanos() - t0),
                    formatNanos(read.durationNanos()),
                    Long.toString(read.offset()),
                    Sizes.format(read.length()),
                    label(read, conformance)
            });
        }
        sb.append(indent(RowTable.renderTable(headers, rows))).append('\n');

        sb.append(renderTimingStats(trace, conformance));

        if (conformance.isConformant()) {
            sb.append("Conformance: OK — every plan node executed exactly once; ")
                    .append(conformance.unmatchedReads().size())
                    .append(" read(s) outside the data-stage plan (metadata stage).\n");
        }
        else {
            sb.append("Conformance: FAILED\n");
            for (StaticFetchPlan.Node node : conformance.missing()) {
                sb.append("  missing execution for node ").append(node.nodeId())
                        .append(" [").append(node.offset()).append(" .. ")
                        .append(node.offset() + node.length()).append(")\n");
            }
            for (StaticFetchPlan.Node node : conformance.duplicated()) {
                sb.append("  duplicate execution of node ").append(node.nodeId()).append('\n');
            }
        }
        return sb.toString();
    }

    /// Renders fetch timing statistics: wall clock, busy time, overlap, and
    /// aggregate throughput, split by data-stage reads (matched to plan
    /// nodes) versus the whole trace.
    ///
    /// The aggregate throughput is bytes over *wall clock*, so under
    /// concurrent reads sharing a connection or a link it reflects the pipe,
    /// not any single stream. Overlap = busy / wall clock: 1.0× means fully
    /// serial; N× means N reads in flight on average. All numbers describe
    /// one run on this host and network — indicative, not calibrated.
    static String renderTimingStats(List<TracingInputFile.TracedRead> trace,
                                    FetchPlanConformance.Result conformance) {
        if (trace.isEmpty()) {
            return "";
        }
        List<TracingInputFile.TracedRead> dataReads = conformance.matched().stream()
                .map(FetchPlanConformance.MatchedNode::read)
                .toList();

        StringBuilder sb = new StringBuilder();
        sb.append("Fetch timing\n");
        sb.append(statsLine("all reads", trace));
        if (!dataReads.isEmpty()) {
            sb.append(statsLine("data stage", dataReads));
            TracingInputFile.TracedRead slowest = dataReads.stream()
                    .max(Comparator.comparingLong(TracingInputFile.TracedRead::durationNanos))
                    .orElseThrow();
            sb.append(String.format("  %-12s %s for %s at offset %d (%s)%n",
                    "slowest read", formatNanos(slowest.durationNanos()),
                    Sizes.format(slowest.length()), slowest.offset(),
                    throughput(slowest.length(), slowest.durationNanos())));
        }
        return sb.toString();
    }

    private static String statsLine(String tag, List<TracingInputFile.TracedRead> reads) {
        long begin = reads.stream().mapToLong(TracingInputFile.TracedRead::beginNanos).min().orElseThrow();
        long end = reads.stream()
                .mapToLong(r -> r.beginNanos() + r.durationNanos()).max().orElseThrow();
        long wallClock = end - begin;
        long busy = reads.stream().mapToLong(TracingInputFile.TracedRead::durationNanos).sum();
        long bytes = reads.stream().mapToLong(TracingInputFile.TracedRead::length).sum();
        double overlap = wallClock > 0 ? (double) busy / wallClock : 0;

        return String.format("  %-12s wall clock %-10s busy %-10s overlap %-7s %s%n",
                tag, formatNanos(wallClock), formatNanos(busy),
                String.format("%.1fx", overlap), throughput(bytes, wallClock));
    }

    /// Bytes over elapsed nanos as a human rate; `—` when the interval is
    /// too short to be meaningful (sub-microsecond, e.g. an mmap slice).
    static String throughput(long bytes, long nanos) {
        if (nanos < 1_000) {
            return "—";
        }
        double bytesPerSecond = bytes * 1_000_000_000.0 / nanos;
        if (bytesPerSecond >= 1024 * 1024 * 1024) {
            return String.format("%.1f GB/s", bytesPerSecond / (1024.0 * 1024 * 1024));
        }
        if (bytesPerSecond >= 1024 * 1024) {
            return String.format("%.1f MB/s", bytesPerSecond / (1024.0 * 1024));
        }
        if (bytesPerSecond >= 1024) {
            return String.format("%.1f KB/s", bytesPerSecond / 1024.0);
        }
        return String.format("%.0f B/s", bytesPerSecond);
    }

    /// Human-scaled elapsed time: µs below 1 ms, ms below 10 s, else seconds.
    /// One decimal keeps columns narrow while the local (µs) vs remote (ms)
    /// difference stays unmistakable.
    static String formatNanos(long nanos) {
        if (nanos < 1_000_000L) {
            return String.format("%.1fµs", nanos / 1_000.0);
        }
        if (nanos < 10_000_000_000L) {
            return String.format("%.1fms", nanos / 1_000_000.0);
        }
        return String.format("%.1fs", nanos / 1_000_000_000.0);
    }

    private static String label(TracingInputFile.TracedRead read, FetchPlanConformance.Result conformance) {
        for (FetchPlanConformance.MatchedNode m : conformance.matched()) {
            if (m.read() == read) {
                return "node " + m.node().nodeId();
            }
        }
        return "metadata";
    }

    /// Renders the file-level summary across all plans.
    static String renderSummary(long executionId, List<StaticFetchPlan> plans, int gapSetting) {
        long useful = plans.stream().mapToLong(StaticFetchPlan::usefulBytes).sum();
        long fetched = plans.stream().mapToLong(StaticFetchPlan::fetchedBytes).sum();
        long overFetch = fetched - useful;
        long incomplete = plans.stream().filter(p -> !p.isSupported()).count();

        StringBuilder sb = new StringBuilder();
        sb.append("Summary (execution ").append(executionId).append(", ")
                .append(plans.size()).append(" plan(s))\n");
        sb.append("  status         ");
        if (incomplete == 0) {
            sb.append(IoTraceSchema.STATUS_SUPPORTED);
        }
        else {
            sb.append(incomplete).append(" of ").append(plans.size())
                    .append(" plans ").append(IoTraceSchema.STATUS_INCOMPLETE);
        }
        sb.append('\n');
        long nodes = plans.stream().mapToLong(p -> p.nodes().size()).sum();
        long requirements = plans.stream().mapToLong(p -> p.requirements().size()).sum();
        sb.append("  nodes          ").append(nodes)
                .append("    requirements ").append(requirements).append('\n');
        sb.append("  useful bytes   ").append(Sizes.format(useful))
                .append("    fetched ").append(Sizes.format(fetched))
                .append("    over-fetch ").append(Sizes.format(overFetch));
        if (fetched > 0) {
            sb.append(String.format(" (%.1f%%)", 100.0 * overFetch / fetched));
        }
        sb.append('\n');
        sb.append("  gap setting    ").append(gapSetting).append(" bytes")
                .append(gapSetting == 64 * 1024 ? " (default)" : " (override)").append('\n');
        return sb.toString();
    }

    private static String indent(String block) {
        return "  " + block.replace("\n", "\n  ").stripTrailing();
    }

    /// Sorts plans for display (by plan ID, i.e. row-group order).
    static List<StaticFetchPlan> displayOrder(List<StaticFetchPlan> plans) {
        List<StaticFetchPlan> sorted = new ArrayList<>(plans);
        sorted.sort(Comparator.comparingLong(StaticFetchPlan::planId));
        return sorted;
    }
}
